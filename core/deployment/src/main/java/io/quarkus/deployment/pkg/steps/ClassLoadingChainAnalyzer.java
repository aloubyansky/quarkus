package io.quarkus.deployment.pkg.steps;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
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
 * methods ({@code ClassLoader.loadClass(String)}, {@code Class.forName(String)}, and
 * {@code Class.forName(String, boolean, ClassLoader)}), propagates backwards through the
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

    private static final String CLASSLOADER_LOAD_CLASS = "java/lang/ClassLoader.loadClass(Ljava/lang/String;)Ljava/lang/Class;";
    private static final String CLASS_FOR_NAME_1 = "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;";
    private static final String CLASS_FOR_NAME_3 = "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;";

    private static final int MAX_CALLER_DEPTH = 5;

    /**
     * Analyzes the reachable classes for dynamic class-loading chains and returns
     * any newly discovered class names that should be added to the reachable set.
     *
     * @param reachableClasses the current set of reachable class names (dot-separated)
     * @param allBytecode all available bytecode (dep + app + generated)
     * @param allKnownClasses the set of all known class names in the application
     * @return newly discovered class names (may be empty, never null)
     */
    static Set<String> analyze(
            Set<String> reachableClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Set<String> allKnownClasses) {

        // Phase 1: Identify methods that call class-loading methods (directly or transitively)
        // Also builds a caller index as a side effect
        Map<String, Set<String>> callerIndex = new HashMap<>();
        Set<String> classLoadingMethods = findClassLoadingMethods(reachableClasses, allBytecode, callerIndex);
        if (classLoadingMethods.isEmpty()) {
            log.debug("No class-loading methods found in reachable classes");
            return new HashSet<>();
        }
        log.debugf("Found %d class-loading methods", classLoadingMethods.size());

        // Phase 2: Find entry point classes
        Set<String> entryPointClasses = findEntryPointClasses(classLoadingMethods, callerIndex);
        if (entryPointClasses.isEmpty()) {
            log.debug("No entry point classes found for class-loading chains");
            return new HashSet<>();
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
     * Phase 1: Find all methods in reachable classes that eventually call
     * ClassLoader.loadClass() or Class.forName(). Uses fixed-point propagation.
     * Also populates the callerIndex (methodKey -> set of methodKeys that call it).
     */
    private static Set<String> findClassLoadingMethods(
            Set<String> reachableClasses,
            Map<String, Supplier<byte[]>> allBytecode,
            Map<String, Set<String>> callerIndex) {

        // Seed set
        Set<String> classLoadingMethods = new HashSet<>();
        classLoadingMethods.add(CLASSLOADER_LOAD_CLASS);
        classLoadingMethods.add(CLASS_FOR_NAME_1);
        classLoadingMethods.add(CLASS_FOR_NAME_3);

        // Build method call graph and caller index in one pass
        // methodCallees: method -> set of methods it calls
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
                            methodCallees.computeIfAbsent(callerKey, k -> new HashSet<>()).add(calleeKey);
                            callerIndex.computeIfAbsent(calleeKey, k -> new HashSet<>()).add(callerKey);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        }

        // Propagate to fixed point: if method A calls method B which is a class-loading method
        // (and B is not a JDK/infra class), then A is also a class-loading method
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

        // Remove the seed JDK methods
        classLoadingMethods.remove(CLASSLOADER_LOAD_CLASS);
        classLoadingMethods.remove(CLASS_FOR_NAME_1);
        classLoadingMethods.remove(CLASS_FOR_NAME_3);

        return classLoadingMethods;
    }

    /**
     * Phase 2: Walk up from class-loading methods using the caller index to find
     * classes whose {@code <init>} or {@code <clinit>} triggers the chain.
     * Depth-limited to {@link #MAX_CALLER_DEPTH} levels.
     */
    private static Set<String> findEntryPointClasses(
            Set<String> classLoadingMethods,
            Map<String, Set<String>> callerIndex) {

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
                    String className = extractClassName(method);
                    if (className != null) {
                        entryPointClasses.add(className.replace('/', '.'));
                    }
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
                String className = extractClassName(method);
                if (className != null) {
                    entryPointClasses.add(className.replace('/', '.'));
                }
            }
        }

        return entryPointClasses;
    }

    /**
     * Extracts the method name from a method key like "com/example/Foo.methodName(Ljava/lang/String;)V".
     */
    private static String extractMethodName(String methodKey) {
        int parenIdx = methodKey.indexOf('(');
        if (parenIdx < 0) {
            return null;
        }
        int dotIdx = methodKey.lastIndexOf('.', parenIdx);
        if (dotIdx < 0) {
            return null;
        }
        return methodKey.substring(dotIdx + 1, parenIdx);
    }

    /**
     * Extracts the class name (internal form) from a method key.
     */
    private static String extractClassName(String methodKey) {
        int parenIdx = methodKey.indexOf('(');
        if (parenIdx < 0) {
            return null;
        }
        int dotIdx = methodKey.lastIndexOf('.', parenIdx);
        if (dotIdx < 0) {
            return null;
        }
        return methodKey.substring(0, dotIdx);
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

        // Resolve bytecode once for all entry points
        Map<String, byte[]> bytecodeMap = new HashMap<>();
        for (Map.Entry<String, Supplier<byte[]>> entry : allBytecode.entrySet()) {
            try {
                bytecodeMap.put(entry.getKey(), entry.getValue().get());
            } catch (Exception e) {
                // skip unreadable classes
            }
        }

        // Suppress stdout/stderr during recording: loaded classes (BouncyCastle, SLF4J, etc.)
        // may print warnings, stack traces, or status messages during their static initialization.
        // These are expected and harmless — redirect to a null stream to keep the build log clean.
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        java.io.PrintStream nullStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        try {
            System.setOut(nullStream);
            System.setErr(nullStream);
            for (String entryPoint : entryPointClasses) {
                log.debugf("Executing entry point class: %s", entryPoint);

                RecordingClassLoader loader = new RecordingClassLoader(bytecodeMap);

                try {
                    Class<?> clazz = Class.forName(entryPoint, true, loader);
                    try {
                        Object instance = clazz.getConstructor().newInstance();
                        // If the instance is a Map (like BouncyCastle's Provider which extends Properties),
                        // iterate values and record strings that match known classes
                        if (instance instanceof java.util.Map) {
                            java.util.Map<?, ?> map = (java.util.Map<?, ?>) instance;
                            for (Object value : map.values()) {
                                if (value instanceof String) {
                                    String strValue = (String) value;
                                    if (allKnownClasses.contains(strValue)) {
                                        allDiscovered.add(strValue);
                                    }
                                }
                            }
                        }
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

                allDiscovered.addAll(loader.getLoadedClassNames());
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        return allDiscovered;
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

        private final Map<String, byte[]> bytecodeMap;
        private final Set<String> loadedClassNames = new HashSet<>();

        RecordingClassLoader(Map<String, byte[]> bytecodeMap) {
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
            byte[] bytes = bytecodeMap.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }

        Set<String> getLoadedClassNames() {
            return loadedClassNames;
        }
    }
}
