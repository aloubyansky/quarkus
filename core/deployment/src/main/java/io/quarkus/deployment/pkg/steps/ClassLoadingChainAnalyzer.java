package io.quarkus.deployment.pkg.steps;

import java.security.Provider;
import java.security.Security;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
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

    /**
     * Analyzes the reachable classes for dynamic class-loading chains and returns
     * any newly discovered class names that should be added to the reachable set.
     *
     * @param reachableClasses the current set of reachable class names (dot-separated)
     * @param allBytecode all available bytecode (dep + app + generated)
     * @param allKnownClasses the set of all known class names in the application
     * @param depClassNames class names from dependencies (used to limit entry points
     *        to dependency classes, excluding generated and app classes)
     * @return newly discovered class names (may be empty, never null)
     */
    static Set<String> analyze(
            Set<String> reachableClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Set<String> allKnownClasses,
            Set<String> depClassNames) {

        // Phases 1+2: find entry point classes whose <init>/<clinit> triggers class loading.
        // Separated into its own method so the call graph and caller index go out of scope
        // before Phase 3 allocates the RecordingClassLoader.
        Set<String> entryPointClasses = identifyEntryPoints(reachableClasses, allBytecode, depClassNames);
        if (entryPointClasses.isEmpty()) {
            return Set.of();
        }
        log.debugf("Found %d entry point classes for class-loading chains", entryPointClasses.size());

        // Phase 3: Execute entry points in a RecordingClassLoader
        Set<String> discovered = executeWithRecordingClassLoader(
                entryPointClasses, allBytecode, allKnownClasses);

        // Filter: only return classes that are known but not yet reachable
        discovered.retainAll(allKnownClasses);
        discovered.removeAll(reachableClasses);
        return discovered;
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
     * Phase 3: Execute entry point classes in a RecordingClassLoader to capture
     * which classes get loaded during static initialization and construction.
     */
    private static Set<String> executeWithRecordingClassLoader(
            Set<String> entryPointClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Set<String> allKnownClasses) {

        Set<String> allDiscovered = new HashSet<>();
        // Reuse a single classloader across all entry points so each class is
        // defined at most once, avoiding Metaspace exhaustion from many short-lived
        // classloaders each defining overlapping sets of classes.
        // Uses the platform classloader as parent so that app/dep classes are loaded
        // by this classloader (child-first), ensuring transitive Class.forName() calls
        // from loaded classes go through our loadClass() and get recorded.
        RecordingClassLoader loader = new RecordingClassLoader(allBytecode);

        // Snapshot global state that entry point class init may mutate
        Provider[] providersBefore = Security.getProviders();
        Properties sysProps = System.getProperties();
        Properties sysPropsCopy = new Properties();
        sysPropsCopy.putAll(sysProps);
        // Suppress stdout/stderr: loaded classes may print warnings or stack traces
        // during their static initialization. These are expected and harmless.
        // Set the thread context classloader to the RecordingClassLoader so that
        // ServiceLoader and other TCCL-based lookups resolve classes from the same
        // classloader, avoiding cross-classloader subtype check failures.
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        Thread currentThread = Thread.currentThread();
        ClassLoader originalTccl = currentThread.getContextClassLoader();
        try {
            System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            currentThread.setContextClassLoader(loader);
            for (String entryPoint : entryPointClasses) {
                executeEntryPoint(entryPoint, loader, allKnownClasses, allDiscovered);
            }
        } finally {
            currentThread.setContextClassLoader(originalTccl);
            System.setOut(originalOut);
            System.setErr(originalErr);
            // Restore security providers: remove any that were added during class init
            restoreSecurityProviders(providersBefore);

            // Restore system properties
            System.setProperties(sysPropsCopy);

            // Interrupt any threads started during class init
            cleanupNewThreads(loader);
        }

        allDiscovered.addAll(loader.getLoadedClassNames());
        return allDiscovered;
    }

    /**
     * Loads and instantiates a single entry point class in the shared RecordingClassLoader.
     * Records all class load attempts, plus class name strings from Map values.
     */
    private static void executeEntryPoint(String entryPoint, RecordingClassLoader loader,
            Set<String> allKnownClasses, Set<String> discovered) {
        log.debugf("Executing entry point class: %s", entryPoint);

        try {
            Class<?> clazz = Class.forName(entryPoint, true, loader);
            try {
                Object instance = clazz.getConstructor().newInstance();
                collectClassNamesFromMapValues(instance, allKnownClasses, discovered);
            } catch (Exception e) {
                log.debugf("Could not instantiate entry point %s: %s", entryPoint, e.getMessage());
            } catch (LinkageError e) {
                log.debugf("LinkageError instantiating entry point %s: %s", entryPoint, e.getMessage());
            }
        } catch (Exception e) {
            log.debugf("Could not load entry point %s: %s", entryPoint, e.getMessage());
        } catch (LinkageError e) {
            log.debugf("LinkageError loading entry point %s: %s", entryPoint, e.getMessage());
        }
    }

    /**
     * If the object is a Map (e.g., BouncyCastle's Provider extends Properties),
     * extracts String values that match known class names. These are class names stored
     * via methods like {@code addAlgorithm(key, className)} for deferred loading.
     */
    private static void collectClassNamesFromMapValues(Object instance,
            Set<String> allKnownClasses, Set<String> discovered) {
        if (instance instanceof java.util.Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof String strValue && allKnownClasses.contains(strValue)) {
                    discovered.add(strValue);
                }
            }
        }
    }

    /**
     * Restores the security provider list to the state captured before Phase 3.
     * Entry point class init (e.g. BouncyCastleProvider) may call
     * {@code Security.addProvider(this)}, anchoring the classloader via a JDK static field.
     */
    private static void restoreSecurityProviders(Provider[] before) {
        Set<String> originalNames = new HashSet<>(before.length);
        for (Provider p : before) {
            originalNames.add(p.getName());
        }
        for (Provider p : Security.getProviders()) {
            if (!originalNames.contains(p.getName())) {
                log.debugf("Removing security provider added during class-loading analysis: %s", p.getName());
                Security.removeProvider(p.getName());
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
                log.debugf("Interrupting thread started during class-loading analysis: %s", t.getName());
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
    private static class RecordingClassLoader extends ClassLoader {

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
