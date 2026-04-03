package io.quarkus.deployment.pkg.steps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassLoadingChainAnalyzerTest {

    /**
     * Verifies that RecordingClassLoader records the name of a successfully loaded class.
     */
    @Test
    void recordingClassLoaderCapturesLoadedClasses() throws Exception {
        String className = "com.test.SimpleClass";
        byte[] bytecode = generateSimpleClass(className);

        Map<String, byte[]> bytecodeMap = new HashMap<>();
        bytecodeMap.put(className, bytecode);

        // Use reflection to access the private inner class
        ClassLoader loader = createRecordingClassLoader(bytecodeMap);
        loader.loadClass(className);

        Set<String> loaded = getLoadedClassNames(loader);
        assertTrue(loaded.contains(className), "Should have recorded the loaded class name");
    }

    /**
     * Verifies that RecordingClassLoader records class names even when loading fails.
     */
    @Test
    void recordingClassLoaderCapturesFailedAttempts() throws Exception {
        Map<String, byte[]> bytecodeMap = new HashMap<>();
        ClassLoader loader = createRecordingClassLoader(bytecodeMap);

        try {
            loader.loadClass("com.test.NonExistent");
        } catch (ClassNotFoundException e) {
            // expected
        }

        Set<String> loaded = getLoadedClassNames(loader);
        assertTrue(loaded.contains("com.test.NonExistent"),
                "Should have recorded the class name even though loading failed");
    }

    /**
     * Verifies that when class A calls Class.forName("com.test.B") in its constructor,
     * loading and instantiating A causes B to be recorded too.
     */
    @Test
    void recordingClassLoaderCapturesDependencyChain() throws Exception {
        String classA = "com.test.ClassA";
        String classB = "com.test.ClassB";

        byte[] bytecodeA = generateClassThatLoads(classA, classB);
        byte[] bytecodeB = generateSimpleClass(classB);

        Map<String, byte[]> bytecodeMap = new HashMap<>();
        bytecodeMap.put(classA, bytecodeA);
        bytecodeMap.put(classB, bytecodeB);

        ClassLoader loader = createRecordingClassLoader(bytecodeMap);
        Class<?> clazz = Class.forName(classA, true, loader);
        clazz.getConstructor().newInstance();

        Set<String> loaded = getLoadedClassNames(loader);
        assertTrue(loaded.contains(classA), "Should have recorded class A");
        assertTrue(loaded.contains(classB), "Should have recorded class B loaded by A's constructor");
    }

    /**
     * Tests the full analyze() flow: Util has a method that calls Class.forName(),
     * Provider's constructor calls Util.load("com.test.Target"). Provider and Util are
     * reachable but Target is not. Verify Target is discovered.
     */
    @Test
    void analyzeFindsClassesLoadedDuringInit() {
        String utilClass = "com.test.Util";
        String providerClass = "com.test.Provider";
        String targetClass = "com.test.Target";

        byte[] utilBytecode = generateUtilClass(utilClass);
        byte[] providerBytecode = generateProviderClass(providerClass, utilClass, targetClass);
        byte[] targetBytecode = generateSimpleClass(targetClass);

        Map<String, Supplier<byte[]>> allBytecode = new HashMap<>();
        allBytecode.put(utilClass, () -> utilBytecode);
        allBytecode.put(providerClass, () -> providerBytecode);
        allBytecode.put(targetClass, () -> targetBytecode);

        Set<String> reachable = new HashSet<>();
        reachable.add(utilClass);
        reachable.add(providerClass);

        Set<String> allKnown = new HashSet<>();
        allKnown.add(utilClass);
        allKnown.add(providerClass);
        allKnown.add(targetClass);

        Set<String> discovered = ClassLoadingChainAnalyzer.analyze(reachable, allBytecode, allKnown);
        assertTrue(discovered.contains(targetClass),
                "Should discover Target class loaded during Provider's init");
        assertFalse(discovered.contains(utilClass), "Util is already reachable");
        assertFalse(discovered.contains(providerClass), "Provider is already reachable");
    }

    /**
     * Tests that when an entry point class extends HashMap, stores class names as values,
     * and also has a class-loading chain (like BouncyCastle's Provider), the map values
     * that match known classes are discovered.
     */
    @Test
    void analyzeExtractsMapValues() {
        String mapProviderClass = "com.test.MapProvider";
        String mapTargetClass = "com.test.MapTarget";
        String loadedClass = "com.test.LoadedByProvider";

        byte[] mapProviderBytecode = generateMapProviderClass(mapProviderClass, mapTargetClass, loadedClass);
        byte[] mapTargetBytecode = generateSimpleClass(mapTargetClass);
        byte[] loadedBytecode = generateSimpleClass(loadedClass);

        Map<String, Supplier<byte[]>> allBytecode = new HashMap<>();
        allBytecode.put(mapProviderClass, () -> mapProviderBytecode);
        allBytecode.put(mapTargetClass, () -> mapTargetBytecode);
        allBytecode.put(loadedClass, () -> loadedBytecode);

        Set<String> reachable = new HashSet<>();
        reachable.add(mapProviderClass);

        Set<String> allKnown = new HashSet<>();
        allKnown.add(mapProviderClass);
        allKnown.add(mapTargetClass);
        allKnown.add(loadedClass);

        Set<String> discovered = ClassLoadingChainAnalyzer.analyze(reachable, allBytecode, allKnown);
        assertTrue(discovered.contains(mapTargetClass),
                "Should discover MapTarget from map values in MapProvider");
    }

    /**
     * Tests that when app bytecode and transformed (dep) bytecode exist for the same class,
     * the transformed version is used. Simulates a bytecode transformer that adds a
     * Class.forName() call to a class — the original bytecode doesn't reference the target,
     * but the transformed version does.
     */
    @Test
    void analyzeUsesTransformedBytecodeOverOriginal() {
        String appClass = "com.test.TransformedApp";
        String targetClass = "com.test.TransformTarget";

        // Original app bytecode — simple class, no class loading
        byte[] originalBytecode = generateSimpleClass(appClass);
        // Transformed bytecode — constructor calls Class.forName(targetClass)
        byte[] transformedBytecode = generateClassThatLoads(appClass, targetClass);
        byte[] targetBytecode = generateSimpleClass(targetClass);

        // Simulate the tree-shaker's bytecode map construction:
        // app bytecode first, then dep bytecode (transformed) overwrites
        Map<String, Supplier<byte[]>> allBytecode = new HashMap<>();
        allBytecode.put(appClass, () -> originalBytecode); // app version first
        allBytecode.put(appClass, () -> transformedBytecode); // transformed overwrites
        allBytecode.put(targetClass, () -> targetBytecode);

        Set<String> reachable = new HashSet<>();
        reachable.add(appClass);

        Set<String> allKnown = new HashSet<>();
        allKnown.add(appClass);
        allKnown.add(targetClass);

        Set<String> discovered = ClassLoadingChainAnalyzer.analyze(reachable, allBytecode, allKnown);
        assertTrue(discovered.contains(targetClass),
                "Should discover TransformTarget via transformed bytecode, not original");
    }

    /**
     * Tests that when only the original (non-transformed) app bytecode is available,
     * the target class that would be in the transformed version is NOT discovered.
     * This is the negative case for {@link #analyzeUsesTransformedBytecodeOverOriginal()}.
     */
    @Test
    void analyzeDoesNotDiscoverTargetFromOriginalBytecode() {
        String appClass = "com.test.OriginalApp";
        String targetClass = "com.test.MissingTarget";

        // Original app bytecode only — no class loading
        byte[] originalBytecode = generateSimpleClass(appClass);
        byte[] targetBytecode = generateSimpleClass(targetClass);

        Map<String, Supplier<byte[]>> allBytecode = new HashMap<>();
        allBytecode.put(appClass, () -> originalBytecode);
        allBytecode.put(targetClass, () -> targetBytecode);

        Set<String> reachable = new HashSet<>();
        reachable.add(appClass);

        Set<String> allKnown = new HashSet<>();
        allKnown.add(appClass);
        allKnown.add(targetClass);

        Set<String> discovered = ClassLoadingChainAnalyzer.analyze(reachable, allBytecode, allKnown);
        assertFalse(discovered.contains(targetClass),
                "Should NOT discover MissingTarget when only original bytecode is used");
    }

    /**
     * Tests that MethodHandles.Lookup.findClass() is recognized as a class-loading seed.
     * A Util class uses findClass() to load a target, and a Provider constructor calls Util.
     * The chain analysis should identify the Provider as an entry point and discover the target.
     */
    @Test
    void analyzeFindsClassesLoadedViaMethodHandlesFindClass() {
        String utilClass = "com.test.FindClassUtil";
        String providerClass = "com.test.FindClassProvider";
        String targetClass = "com.test.FindClassTarget";

        byte[] utilBytecode = generateFindClassUtil(utilClass);
        byte[] providerBytecode = generateProviderClass(providerClass, utilClass, targetClass);
        byte[] targetBytecode = generateSimpleClass(targetClass);

        Map<String, Supplier<byte[]>> allBytecode = new HashMap<>();
        allBytecode.put(utilClass, () -> utilBytecode);
        allBytecode.put(providerClass, () -> providerBytecode);
        allBytecode.put(targetClass, () -> targetBytecode);

        Set<String> reachable = new HashSet<>();
        reachable.add(utilClass);
        reachable.add(providerClass);

        Set<String> allKnown = new HashSet<>();
        allKnown.add(utilClass);
        allKnown.add(providerClass);
        allKnown.add(targetClass);

        Set<String> discovered = ClassLoadingChainAnalyzer.analyze(reachable, allBytecode, allKnown);
        assertTrue(discovered.contains(targetClass),
                "Should discover FindClassTarget loaded via MethodHandles.Lookup.findClass chain");
    }

    // ---- Helper methods ----

    /**
     * Generates a simple class with a no-arg constructor that does nothing.
     */
    private static byte[] generateSimpleClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // Default constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a class whose constructor calls Class.forName(targetClassName).
     */
    private static byte[] generateClassThatLoads(String className, String targetClassName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // Class.forName(targetClassName)
        mv.visitLdcInsn(targetClassName);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a Util class with a static method:
     * static Class<?> load(String name) { return Class.forName(name); }
     */
    private static byte[] generateUtilClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // Default constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // static Class<?> load(String name) { return Class.forName(name); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load",
                "(Ljava/lang/String;)Ljava/lang/Class;", null,
                new String[] { "java/lang/ClassNotFoundException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a Util class with a static method that uses MethodHandles.Lookup.findClass:
     * static Class<?> load(String name) { return MethodHandles.lookup().findClass(name); }
     */
    private static byte[] generateFindClassUtil(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // Default constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // static Class<?> load(String name) { return MethodHandles.lookup().findClass(name); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load",
                "(Ljava/lang/String;)Ljava/lang/Class;", null,
                new String[] { "java/lang/ClassNotFoundException", "java/lang/IllegalAccessException" });
        mv.visitCode();
        // MethodHandles.lookup()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        // .findClass(name)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findClass",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a Provider class whose constructor calls Util.load(targetClassName).
     */
    private static byte[] generateProviderClass(String className, String utilClassName, String targetClassName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');
        String utilInternal = utilClassName.replace('.', '/');
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // Util.load(targetClassName)
        mv.visitLdcInsn(targetClassName);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, utilInternal, "load",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a class that extends HashMap, puts a class name as a value, and also
     * calls Class.forName() to trigger the class-loading chain detection:
     *
     * <pre>
     * public MapProvider() {
     *     put("key", mapTargetClassName);
     *     Class.forName(loadedClassName);
     * }
     * </pre>
     */
    private static byte[] generateMapProviderClass(String className, String mapTargetClassName,
            String loadedClassName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        String internalName = className.replace('.', '/');
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/util/HashMap", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        // this.put("key", mapTargetClassName)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitLdcInsn(mapTargetClassName);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        // Class.forName(loadedClassName) - triggers class-loading chain detection
        mv.visitLdcInsn(loadedClassName);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- Reflection helpers to access private RecordingClassLoader ----

    @SuppressWarnings("unchecked")
    private static ClassLoader createRecordingClassLoader(Map<String, byte[]> bytecodeMap) throws Exception {
        Class<?> analyzerClass = ClassLoadingChainAnalyzer.class;
        Class<?>[] declaredClasses = analyzerClass.getDeclaredClasses();
        Class<?> recordingClass = null;
        for (Class<?> c : declaredClasses) {
            if (c.getSimpleName().equals("RecordingClassLoader")) {
                recordingClass = c;
                break;
            }
        }
        if (recordingClass == null) {
            throw new AssertionError("RecordingClassLoader inner class not found");
        }
        Constructor<?> ctor = recordingClass.getDeclaredConstructor(Map.class);
        ctor.setAccessible(true);
        return (ClassLoader) ctor.newInstance(bytecodeMap);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getLoadedClassNames(ClassLoader loader) throws Exception {
        java.lang.reflect.Method method = loader.getClass().getDeclaredMethod("getLoadedClassNames");
        method.setAccessible(true);
        return (Set<String>) method.invoke(loader);
    }
}
