package io.quarkus.deployment.pkg.steps;

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

import io.quarkus.maven.dependency.ArtifactKey;

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

    /**
     * Analyzes the reachable classes for dynamic class-loading chains and returns
     * any newly discovered class names that should be added to the reachable set.
     *
     * @param reachableClasses the current set of reachable class names (dot-separated)
     * @param allBytecode all available bytecode (dep + app + generated), used for Phases 1+2
     * @param depClassNames class names from dependencies (used to limit entry points
     *        to dependency classes, excluding generated and app classes)
     * @return entry point class names whose {@code <init>}/{@code <clinit>} triggers dynamic class loading,
     *         or empty set if none found
     */
    static Set<String> findEntryPoints(
            Set<String> reachableClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Set<String> depClassNames) {
        return identifyEntryPoints(reachableClasses, allBytecode, depClassNames);
    }

    /**
     * Phases 1+2: Builds a method-level call graph, propagates from class-loading seeds,
     * then walks up to find {@code <init>}/{@code <clinit>} entry points.
     * The call graph and caller index are scoped to this method so they become eligible
     * for GC before Phase 3 runs.
     */
    static Set<String> identifyEntryPoints(
            Set<String> reachableClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Set<String> depClassNames) {

        Map<String, Set<String>> callerIndex = new HashMap<>();
        Map<String, Set<String>> methodCallees = buildCallGraph(reachableClasses, allBytecode, callerIndex);
        Set<String> classLoadingMethods = propagateFromSeeds(methodCallees);
        if (classLoadingMethods.isEmpty()) {
            return Set.of();
        }
        log.debugf("Found %d class-loading methods", classLoadingMethods.size());
        return findEntryPointClasses(classLoadingMethods, callerIndex, depClassNames);
    }

    /**
     * Scans reachable bytecode and builds two maps in a single pass:
     * <ul>
     * <li>{@code methodCallees}: method → set of methods it calls</li>
     * <li>{@code callerIndex}: method → set of methods that call it (reverse index)</li>
     * </ul>
     */
    private static Map<String, Set<String>> buildCallGraph(
            Set<String> reachableClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Map<String, Set<String>> callerIndex) {

        Map<String, Set<String>> methodCallees = new HashMap<>();

        for (String className : reachableClasses) {
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

        return methodCallees;
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
     * Phase 3: Execute entry point classes in RecordingClassLoaders, grouped by dependency.
     * Each dependency group gets its own classloader so it can be GC'd independently
     * after processing, limiting peak Metaspace usage.
     */
    static Set<String> executeEntryPoints(
            Set<String> entryPointClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Set<String> allKnownClasses,
            Map<String, ArtifactKey> classToDep) {

        // Group entry points by dependency
        Map<ArtifactKey, List<String>> groups = new HashMap<>();
        List<String> noDep = new ArrayList<>();
        for (String ep : entryPointClasses) {
            ArtifactKey dep = classToDep.get(ep);
            if (dep != null) {
                groups.computeIfAbsent(dep, k -> new ArrayList<>()).add(ep);
            } else {
                noDep.add(ep);
            }
        }
        if (!noDep.isEmpty()) {
            groups.put(null, noDep);
        }

        Set<String> allDiscovered = new HashSet<>();
        java.security.Provider[] providersBefore = java.security.Security.getProviders();
        java.util.Properties sysPropsCopy = new java.util.Properties();
        sysPropsCopy.putAll(System.getProperties());

        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        Thread currentThread = Thread.currentThread();
        ClassLoader originalTccl = currentThread.getContextClassLoader();

        try {
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

            for (var group : groups.values()) {
                RecordingClassLoader loader = new RecordingClassLoader(allBytecode);
                currentThread.setContextClassLoader(loader);

                for (String entryPoint : group) {
                    executeEntryPoint(entryPoint, loader, allKnownClasses, allDiscovered);
                }

                allDiscovered.addAll(loader.getLoadedClassNames());

                // Cleanup after each group so the classloader can be GC'd
                restoreSecurityProviders(providersBefore);
                System.setProperties(sysPropsCopy);
                sysPropsCopy = new java.util.Properties();
                sysPropsCopy.putAll(System.getProperties());
                cleanupNewThreads(loader);
            }
        } finally {
            currentThread.setContextClassLoader(originalTccl);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        return allDiscovered;
    }

    /**
     * Loads and instantiates a single entry point class in the given RecordingClassLoader.
     * Records all class load attempts, plus class name strings from Map values.
     */
    private static void executeEntryPoint(String entryPoint, RecordingClassLoader loader,
            Set<String> allKnownClasses, Set<String> discovered) {
        try {
            Class<?> clazz = Class.forName(entryPoint, true, loader);
            try {
                Object instance = clazz.getConstructor().newInstance();
                if (instance instanceof java.util.Map<?, ?> map) {
                    for (Object value : map.values()) {
                        if (value instanceof String strValue && allKnownClasses.contains(strValue)) {
                            discovered.add(strValue);
                        }
                    }
                }
            } catch (Exception | LinkageError ignored) {
            }
        } catch (Exception | LinkageError ignored) {
        }
    }

    /**
     * Restores the security provider list to the state captured before Phase 3.
     */
    private static void restoreSecurityProviders(java.security.Provider[] before) {
        Set<String> originalNames = new HashSet<>(before.length);
        for (java.security.Provider p : before) {
            originalNames.add(p.getName());
        }
        for (java.security.Provider p : java.security.Security.getProviders()) {
            if (!originalNames.contains(p.getName())) {
                java.security.Security.removeProvider(p.getName());
            }
        }
    }

    /**
     * Interrupts threads spawned during Phase 3 whose context classloader is the
     * RecordingClassLoader. Only targets threads tied to our classloader to avoid
     * interfering with other build steps running in parallel.
     */
    private static void cleanupNewThreads(ClassLoader recordingLoader) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t != Thread.currentThread() && t.getContextClassLoader() == recordingLoader) {
                t.interrupt();
            }
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

    /**
     * A ClassLoader that loads classes from an in-memory bytecode map and records
     * all class names that are attempted to be loaded (including failed attempts).
     * Uses the platform class loader as parent to isolate from the application classpath.
     */
    static class RecordingClassLoader extends ClassLoader {

        private final Map<String, Supplier<byte[]>> bytecodeMap;
        private final Set<String> loadedClassNames = new HashSet<>();

        RecordingClassLoader(Map<String, Supplier<byte[]>> bytecodeMap) {
            super(ClassLoader.getPlatformClassLoader());
            this.bytecodeMap = bytecodeMap;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            loadedClassNames.add(name);
            return super.loadClass(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Supplier<byte[]> supplier = bytecodeMap.get(name);
            if (supplier != null) {
                try {
                    byte[] bytes = supplier.get();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (Exception e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            throw new ClassNotFoundException(name);
        }

        Set<String> getLoadedClassNames() {
            return loadedClassNames;
        }
    }
}
