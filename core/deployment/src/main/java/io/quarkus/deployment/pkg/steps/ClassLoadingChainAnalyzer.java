package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Discovers classes that are loaded dynamically via {@code ClassLoader.loadClass()} or
 * {@code Class.forName()} through call chains that cannot be statically traced by the
 * tree-shaker's BFS reachability analysis.
 *
 * <h2>Problem</h2>
 * <p>
 * Many libraries construct class names at runtime using {@code StringBuilder},
 * {@code invokedynamic} {@code StringConcatFactory}, or similar mechanisms, and then load
 * them via {@code ClassLoader.loadClass()} or {@code Class.forName()} during static
 * initialization ({@code <clinit>}) or constructor execution ({@code <init>}). Because
 * the class name strings are computed dynamically, the tree-shaker's BFS over constant
 * pool references and method instructions cannot trace which classes will actually be
 * loaded. Without special handling, these dynamically loaded classes would be incorrectly
 * removed from the final artifact.
 *
 * <h2>Solution overview</h2>
 * <p>
 * Rather than attempting to reconstruct class name strings from bytecode (which is
 * fragile and incomplete), this analyzer uses a three-phase approach:
 *
 * <h3>Phase 1 -- Bytecode analysis (seed propagation)</h3>
 * <p>
 * Scans all reachable classes and builds a method-level call graph. Starting from seed
 * methods ({@code ClassLoader.loadClass(String)}, {@code Class.forName(String)},
 * {@code Class.forName(String, boolean, ClassLoader)}, and
 * {@code MethodHandles.Lookup.findClass(String)}), propagates backwards through the
 * call graph using a fixed-point algorithm to identify every application method that
 * transitively calls a class-loading seed. JDK and infrastructure classes
 * ({@code java/}, {@code javax/}, {@code jakarta/}, {@code sun/}, {@code org/objectweb/})
 * are excluded from propagation to avoid false positives. A caller index
 * (callee to callers) is built as a side effect for use in Phase 2.
 *
 * <h3>Phase 2 -- Entry point discovery (caller chain walk)</h3>
 * <p>
 * Walks up the caller index from the class-loading methods identified in Phase 1,
 * searching for {@code <init>} or {@code <clinit>} methods that appear in the call
 * chain. These represent "entry point" classes whose construction or static initialization
 * ultimately triggers the dynamic class loading. The walk is depth-limited to
 * {@value #MAX_CALLER_DEPTH} levels to keep the search bounded.
 *
 * <h3>Phase 3 -- Dynamic execution with RecordingClassLoader</h3>
 * <p>
 * Each entry point class is loaded and instantiated inside an isolated
 * {@link RecordingClassLoader} to dynamically capture all class load attempts that
 * occur during its initialization and construction.
 *
 * <h2>RecordingClassLoader</h2>
 * <p>
 * The {@link RecordingClassLoader} extends {@code ClassLoader} with the platform class
 * loader as its parent, isolating it from the application classpath. It loads dependency
 * classes from a pre-resolved in-memory bytecode map. Every {@code loadClass()} attempt
 * is recorded, including attempts that fail with {@code ClassNotFoundException}. After
 * instantiation, if the resulting object implements {@code java.util.Map}, the analyzer
 * also extracts {@code String} values from the map entries and checks them against known
 * class names. This handles patterns like BouncyCastle's {@code addAlgorithm()} which
 * stores class names as map values for deferred loading rather than loading them
 * immediately.
 *
 * <h2>Fixed-point loop</h2>
 * <p>
 * This analyzer is designed to be called from {@code JarTreeShaker} in an outer
 * fixed-point loop. Each invocation returns newly discovered class names. These are fed
 * back into the BFS reachability analysis, which may mark additional classes as
 * reachable, and the analysis repeats. The loop terminates when no new classes are
 * discovered in an iteration.
 *
 * <h2>Example: BouncyCastle</h2>
 * <p>
 * BouncyCastle's {@code BouncyCastleProvider} constructor calls {@code setup()}, which
 * calls {@code loadAlgorithms()}, which calls {@code loadServiceClass()}, which calls
 * {@code ClassUtil.loadClass()}, which ultimately calls {@code ClassLoader.loadClass()}.
 * Phase 1 marks all these methods as class-loading methods. Phase 2 walks up to the
 * {@code BouncyCastleProvider.<init>} constructor and identifies it as an entry point.
 * Phase 3 instantiates it and records every dynamically loaded class. Additionally, each
 * {@code $Mappings} class stores sub-class names via {@code addAlgorithm(key, className)},
 * which populates the provider's internal map. These class names are captured by
 * inspecting the map values after instantiation.
 */
class ClassLoadingChainAnalyzer {

    private static final Logger log = Logger.getLogger(ClassLoadingChainAnalyzer.class.getName());

    /** JDK methods that load classes by name — the seeds for call chain propagation. */
    private static final Set<String> SEED_METHODS = Set.of(
            "java/lang/ClassLoader.loadClass(Ljava/lang/String;)Ljava/lang/Class;",
            "java/lang/ClassLoader.loadClass(Ljava/lang/String;Z)Ljava/lang/Class;",
            "java/lang/ClassLoader.findClass(Ljava/lang/String;)Ljava/lang/Class;",
            "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
            "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            "java/lang/Class.forName(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;",
            "java/lang/invoke/MethodHandles$Lookup.findClass(Ljava/lang/String;)Ljava/lang/Class;");

    private static final int MAX_CALLER_DEPTH = 5;

    private final Map<String, Supplier<byte[]>> allBytecode;
    private final Set<String> depClassNames;
    private final Map<String, Set<String>> callerIndex = new HashMap<>();
    private final Map<String, Set<String>> methodCallees = new HashMap<>();

    ClassLoadingChainAnalyzer(Map<String, Supplier<byte[]>> allBytecode, Set<String> depClassNames) {
        this.allBytecode = allBytecode;
        this.depClassNames = depClassNames;
    }

    /**
     * Analyzes the given classes for dynamic class-loading chains and returns
     * any entry point class names whose {@code <init>}/{@code <clinit>} triggers
     * dynamic class loading.
     * <p>
     * The call graph ({@code callerIndex}, {@code methodCallees}) is preserved
     * across invocations and extended incrementally with each call.
     *
     * @param classesToScan class names to scan and add to the call graph
     * @return entry point class names, or empty set if none found
     */
    Set<String> findEntryPoints(Set<String> classesToScan) {
        buildCallGraph(classesToScan);
        Set<String> classLoadingMethods = propagateFromSeeds(methodCallees);
        if (classLoadingMethods.isEmpty()) {
            return Set.of();
        }
        log.debugf("Found %d class-loading methods", classLoadingMethods.size());
        return findEntryPointClasses(classLoadingMethods, callerIndex, depClassNames);
    }

    /**
     * Scans the given classes and extends the call graph maps:
     * <ul>
     * <li>{@code methodCallees}: method → set of methods it calls</li>
     * <li>{@code callerIndex}: method → set of methods that call it (reverse index)</li>
     * </ul>
     */
    private void buildCallGraph(Set<String> classesToScan) {
        for (String className : classesToScan) {
            Supplier<byte[]> supplier = allBytecode.get(className);
            if (supplier == null) {
                continue;
            }
            byte[] bytecode;
            try {
                bytecode = supplier.get();
            } catch (Exception e) {
                continue;
            }
            String internalOwner = className.replace('.', '/');
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    String callerKey = internalOwner + "." + name + descriptor;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname,
                                String mdescriptor, boolean isInterface) {
                            String calleeKey = owner + "." + mname + mdescriptor;
                            // Skip JDK/infra callees that aren't class-loading seeds —
                            // they can never propagate and dominate the graph size
                            if (!SEED_METHODS.contains(calleeKey) && isJdkOrInfraClass(calleeKey)) {
                                return;
                            }
                            methodCallees.computeIfAbsent(callerKey, k -> new HashSet<>()).add(calleeKey);
                            callerIndex.computeIfAbsent(calleeKey, k -> new HashSet<>()).add(callerKey);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        }
    }

    /**
     * Starting from {@link #SEED_METHODS}, propagates through the call graph to find all
     * application methods that transitively call a class-loading seed.
     * JDK/infrastructure methods are excluded from propagation.
     */
    private static Set<String> propagateFromSeeds(Map<String, Set<String>> methodCallees) {
        Set<String> classLoadingMethods = new HashSet<>(SEED_METHODS);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Set<String>> entry : methodCallees.entrySet()) {
                if (classLoadingMethods.contains(entry.getKey())) {
                    continue;
                }
                if (isJdkOrInfraClass(entry.getKey())) {
                    continue;
                }
                for (String callee : entry.getValue()) {
                    if (classLoadingMethods.contains(callee)) {
                        classLoadingMethods.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }

        classLoadingMethods.removeAll(SEED_METHODS);
        return classLoadingMethods;
    }

    /**
     * Phase 2: Walk up from class-loading methods using the caller index to find
     * classes whose {@code <init>} or {@code <clinit>} triggers the chain.
     * Depth-limited to {@link #MAX_CALLER_DEPTH} levels.
     */
    private static Set<String> findEntryPointClasses(
            Set<String> classLoadingMethods,
            Map<String, Set<String>> callerIndex,
            Set<String> depClassNames) {

        Set<String> entryPointClasses = new HashSet<>();
        Set<String> visited = new HashSet<>(classLoadingMethods);
        Queue<String> current = new ArrayDeque<>(classLoadingMethods);

        for (int depth = 0; depth < MAX_CALLER_DEPTH && !current.isEmpty(); depth++) {
            Queue<String> next = new ArrayDeque<>();
            while (!current.isEmpty()) {
                String method = current.poll();

                // Check if this method is <init> or <clinit>
                String methodPart = extractMethodName(method);
                if ("<init>".equals(methodPart) || "<clinit>".equals(methodPart)) {
                    addIfDependencyClass(method, depClassNames, entryPointClasses);
                }

                // Walk up to callers
                Set<String> callers = callerIndex.get(method);
                if (callers != null) {
                    for (String caller : callers) {
                        if (visited.add(caller)) {
                            next.add(caller);
                        }
                    }
                }
            }
            current = next;
        }

        // Also check the last level for <init>/<clinit>
        for (String method : current) {
            String methodPart = extractMethodName(method);
            if ("<init>".equals(methodPart) || "<clinit>".equals(methodPart)) {
                addIfDependencyClass(method, depClassNames, entryPointClasses);
            }
        }

        return entryPointClasses;
    }

    /**
     * Adds the class from a method key to the entry point set if it is a dependency class.
     * Generated classes and app classes are excluded — they don't dynamically load
     * dependency classes in ways that affect tree-shaking.
     */
    private static void addIfDependencyClass(String methodKey, Set<String> depClassNames,
            Set<String> entryPointClasses) {
        String className = extractClassName(methodKey);
        if (className != null) {
            String dotName = className.replace('/', '.');
            if (depClassNames.contains(dotName)) {
                entryPointClasses.add(dotName);
            }
        }
    }

    /**
     * Finds the dot+paren boundary in a method key like "com/example/Foo.methodName(...)V".
     * Returns the dot index, or -1 if the key is malformed.
     */
    private static int findMethodSeparator(String methodKey) {
        int parenIdx = methodKey.indexOf('(');
        return parenIdx < 0 ? -1 : methodKey.lastIndexOf('.', parenIdx);
    }

    /** Extracts the method name from a key like {@code "com/example/Foo.bar(I)V"} → {@code "bar"}. */
    private static String extractMethodName(String methodKey) {
        int dotIdx = findMethodSeparator(methodKey);
        return dotIdx < 0 ? null : methodKey.substring(dotIdx + 1, methodKey.indexOf('('));
    }

    /**
     * Extracts the class name (internal form) from a key like {@code "com/example/Foo.bar(I)V"} → {@code "com/example/Foo"}.
     */
    private static String extractClassName(String methodKey) {
        int dotIdx = findMethodSeparator(methodKey);
        return dotIdx < 0 ? null : methodKey.substring(0, dotIdx);
    }

    /**
     * Phase 3: Execute entry point classes in a forked JVM to capture which classes
     * get loaded during static initialization and construction.
     * <p>
     * Running in a separate process ensures complete isolation of global JVM state
     * (System.out/err, security providers, system properties, TCCL) and that all
     * Metaspace consumed by {@code defineClass} is reclaimed when the process exits.
     * This is critical because multiple tree-shaker instances may run in parallel
     * during CI builds with {@code -T2C}.
     * <p>
     * Only generated and transformed bytecode is written to a temp directory.
     * Dependency JARs and the app artifact are included on the classpath by path,
     * with the temp dir first so transformed classes override originals.
     *
     * @param entryPointClasses classes whose init/clinit triggers dynamic class loading
     * @param generatedBytecode generated classes (in-memory only, must be written to disk)
     * @param transformedBytecode transformed classes that override originals in dep JARs
     * @param allKnownClasses all known class names for Map value extraction
     * @param depJarPaths file system paths to dependency JARs
     * @param appPaths file system paths to the application artifact
     */
    static Set<String> executeEntryPoints(
            Set<String> entryPointClasses,
            Map<String, Supplier<byte[]>> generatedBytecode,
            Map<String, Supplier<byte[]>> transformedBytecode,
            Set<String> allKnownClasses,
            List<Path> depJarPaths,
            List<Path> appPaths) {

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("tree-shake-recording");

            // Write only generated and transformed bytecode to temp dir
            writeBytecodeToDir(tempDir, generatedBytecode);
            writeBytecodeToDir(tempDir, transformedBytecode);

            // Write input file: entry points, then separator, then allKnownClasses
            Path inputFile = tempDir.resolve("_input.txt");
            List<String> inputLines = new ArrayList<>(entryPointClasses.size() + allKnownClasses.size() + 1);
            inputLines.addAll(entryPointClasses);
            inputLines.add("---");
            inputLines.addAll(allKnownClasses);
            Files.write(inputFile, inputLines, StandardCharsets.UTF_8);

            // Copy RecordingMain class to the temp dir
            writeRecordingMainClass(tempDir);

            // Build classpath: temp dir first (overrides), then dep JARs, then app
            StringBuilder cpBuilder = new StringBuilder();
            cpBuilder.append(tempDir);
            String pathSep = System.getProperty("path.separator");
            for (Path p : depJarPaths) {
                cpBuilder.append(pathSep).append(p);
            }
            for (Path p : appPaths) {
                cpBuilder.append(pathSep).append(p);
            }

            // Write @argfile to avoid command-line length limits (Windows)
            Path argFile = tempDir.resolve("_jvm.args");
            Files.writeString(argFile,
                    "-cp\n" + cpBuilder + "\n-XX:MaxMetaspaceSize=256m",
                    StandardCharsets.UTF_8);

            // Fork JVM
            String javaCmd = ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());

            ProcessBuilder pb = new ProcessBuilder(
                    javaCmd,
                    "@" + argFile,
                    RecordingMain.class.getName(),
                    inputFile.toString());
            pb.redirectErrorStream(false);

            Process proc = pb.start();

            // Drain stderr in background to avoid blocking
            Thread stderrDrainer = new Thread(() -> {
                try {
                    proc.getErrorStream().transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            }, "tree-shake-stderr-drainer");
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            // Read discovered class names from stdout
            Set<String> discovered = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        discovered.add(line);
                    }
                }
            }

            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                log.warnf("Class-loading chain analysis forked JVM exited with code %d", exitCode);
            }

            return discovered;

        } catch (IOException | InterruptedException e) {
            log.warnf(e, "Failed to run class-loading chain analysis in forked JVM, skipping Phase 3");
            return Set.of();
        } finally {
            if (tempDir != null) {
                deleteRecursive(tempDir);
            }
        }
    }

    private static void writeBytecodeToDir(Path dir, Map<String, Supplier<byte[]>> bytecodeMap) throws IOException {
        for (var entry : bytecodeMap.entrySet()) {
            byte[] bytes;
            try {
                bytes = entry.getValue().get();
            } catch (Exception e) {
                continue;
            }
            String classFile = entry.getKey().replace('.', '/') + ".class";
            Path target = dir.resolve(classFile);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
    }

    /**
     * Writes the self-contained {@code RecordingMain.class} to the temp directory.
     */
    private static void writeRecordingMainClass(Path tempDir) throws IOException {
        String baseName = RecordingMain.class.getName().replace('.', '/');
        for (String suffix : new String[] { ".class", "$RecordingURLClassLoader.class" }) {
            String resourcePath = baseName + suffix;
            try (var is = ClassLoadingChainAnalyzer.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    if (suffix.equals(".class")) {
                        throw new IOException("Cannot find RecordingMain.class on classpath");
                    }
                    continue;
                }
                Path target = tempDir.resolve(resourcePath);
                Files.createDirectories(target.getParent());
                Files.write(target, is.readAllBytes());
            }
        }
    }

    private static void deleteRecursive(Path path) {
        try {
            Files.walk(path)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    /**
     * Checks whether a method key belongs to a JDK or infrastructure class that should not
     * be considered as a class-loading method for propagation purposes.
     */
    private static boolean isJdkOrInfraClass(String methodKey) {
        return methodKey.startsWith("java/")
                || methodKey.startsWith("javax/")
                || methodKey.startsWith("jakarta/")
                || methodKey.startsWith("org/objectweb/")
                || methodKey.startsWith("sun/");
    }
}
