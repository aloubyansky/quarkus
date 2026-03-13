package io.quarkus.test.junit.classloading;

import static io.quarkus.runtime.LaunchMode.TEST;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.condition.OS;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.StartupAction;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.runtime.JVMUnsafeWarningsControl;
import io.quarkus.test.common.FacadeClassLoaderProvider;
import io.quarkus.test.junit.AppMakerHelper;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestExtension;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.TestProfileAndProperties;
import io.quarkus.test.junit.TestProfileAndProperties.TestProfileConfigSource;
import io.quarkus.test.junit.TestResourceUtil;

/**
 * JUnit has many interceptors and listeners, but it does not allow us to intercept test discovery in a fine-grained way that
 * would allow us to swap the thread context classloader.
 * Since we can't intercept with a JUnit hook, we hijack from inside the classloader.
 * <p>
 * We need to load all our test classes in one go, during the discovery phase, before we start the applications.
 * We may need several applications and therefore, several classloaders, depending on what profiles are set.
 * To solve that, we prepare the applications, to get classloaders, and file them here.
 * <p>
 * Final, since some code does instanceof checks using the class name.
 */
public final class FacadeClassLoader extends ClassLoader implements Closeable {
    private static final Logger log = Logger.getLogger(FacadeClassLoader.class);

    private static final String NAME = "FacadeLoader";
    public static final String VALUE = "value";
    public static final String KEY_PREFIX = "QuarkusTest-";
    public static final String DISPLAY_NAME_PREFIX = "JUnit";
    // It would be nice, and maybe theoretically possible, to re-use the curated application? However, the pre-classloadingrewrite version of the codebase did not reuse curated applications between profiles,
    // which suggests it would be a major rewrite to do so. If they are re-used, most things still work, but config when there are multiple profiles does not work; see, for example, integration-tests/smallrye-config

    // We re-use curated applications across application starts; be careful what classloader this class is loaded with
    private static final Map<String, CuratedApplication> curatedApplications = new HashMap<>();

    // JUnit discovery is single threaded, so no need for concurrency on this map
    private final Map<String, QuarkusClassLoader> runtimeClassLoaders = new HashMap<>();
    private static final String NO_PROFILE = "no-profile";

    // Jandex annotation DotName constants
    private static final DotName QUARKUS_TEST = DotName.createSimple("io.quarkus.test.junit.QuarkusTest");
    private static final DotName QUARKUS_INTEGRATION_TEST = DotName.createSimple(QuarkusIntegrationTest.class.getName());
    private static final DotName TEST_PROFILE = DotName.createSimple(TestProfile.class.getName());
    private static final DotName DISABLED = DotName.createSimple("org.junit.jupiter.api.Disabled");
    private static final DotName DISABLED_ON_OS = DotName.createSimple("org.junit.jupiter.api.condition.DisabledOnOs");
    private static final DotName NESTED = DotName.createSimple("org.junit.jupiter.api.Nested");
    private static final DotName EXTEND_WITH = DotName.createSimple("org.junit.jupiter.api.extension.ExtendWith");
    private static final DotName REGISTER_EXTENSION = DotName.createSimple("org.junit.jupiter.api.extension.RegisterExtension");
    private static final DotName TEST_ANNOTATION = DotName.createSimple("org.junit.jupiter.api.Test");
    private static final DotName OBJECT_NAME = DotName.createSimple(Object.class.getName());

    private static final String QUARKUS_TEST_EXTENSION_NAME = QuarkusTestExtension.class.getName();

    /*
     * A Jandex index built from the classpath directories (not JARs) at construction time.
     * Used to inspect test class annotations without loading classes, replacing the previous
     * peekingClassLoader approach. This avoids loading every class twice during discovery
     * and eliminates NoClassDefFoundError issues from class loading side effects.
     */
    private Index jandexIndex;

    // JUnit extensions can be registered by a service loader - see https://docs.junit.org/current/user-guide/#extensions-registration
    private boolean isServiceLoaderMechanism;

    // Whether the @QuarkusTest annotation is on the classpath at all
    private boolean quarkusTestAvailable;

    // TODO maybe refactor this into a ContinuousFacadeClassLoader subclass
    private final Map<String, String> profileNames;
    private final Set<String> quarkusTestClasses;
    private final boolean isAuxiliaryApplication;
    private QuarkusClassLoader keyMakerClassLoader;

    private final boolean isContinuousTesting;

    private final List<FacadeClassLoaderProvider> facadeClassLoaderProviders;

    public FacadeClassLoader(ClassLoader parent) {
        this(parent, false, null, null, null, System.getProperty("java.class.path"));
    }

    // Also called reflectively by JUnitTestRunner
    public FacadeClassLoader(ClassLoader parent, boolean isAuxiliaryApplication, CuratedApplication curatedApplication,
            final Map<String, String> profileNames,
            final Set<String> quarkusTestClasses, final String classesPath) {
        super(parent);
        // Note that in normal testing, the parent is the system classloader, and in continuous testing, the parent is a quarkus classloader
        // It would be nice to resolve that inconsistency, but I'm not sure it's very possible

        if (quarkusTestClasses != null) {
            isContinuousTesting = true;
        } else {
            isContinuousTesting = false;
        }

        // Don't make a no-profile curated application if our caller had one already
        if (curatedApplication != null) {
            curatedApplications.put(getProfileKey(null), curatedApplication);
        }

        this.quarkusTestClasses = quarkusTestClasses;
        this.isAuxiliaryApplication = isAuxiliaryApplication;

        // Build a Jandex index from all classpath entries for annotation inspection.
        // This replaces the peekingClassLoader: instead of loading every class to inspect annotations,
        // we read bytecode directly via Jandex - no class loading, no static initializers, no dependency resolution.
        // Both directories (e.g. target/test-classes) and JARs (e.g. test-jar dependencies used with
        // surefire's dependenciesToScan) are indexed.
        Indexer indexer = new Indexer();
        for (String spec : classesPath.split(File.pathSeparator)) {
            Path path = Path.of(spec);
            if (Files.isDirectory(path)) {
                indexDirectory(indexer, path);
            } else if (spec.endsWith(".jar") && Files.isRegularFile(path)) {
                indexJar(indexer, path);
            }
        }
        jandexIndex = indexer.complete();

        // Check if QuarkusTest is reachable at all
        try {
            parent.loadClass("io.quarkus.test.junit.QuarkusTest");
            quarkusTestAvailable = true;
        } catch (ClassNotFoundException e) {
            // If QuarkusTest is not on the classpath, that's fine; it just means we definitely won't have QuarkusTests
            log.debug("QuarkusTest not on classpath: " + e);
            quarkusTestAvailable = false;
        }

        // Determine the annotation loader - need this to scan META-INF/services
        // If this is launched with a launcher, java.class.path may be very minimal - see https://maven.apache.org/surefire/maven-surefire-plugin/examples/class-loading.html
        ClassLoader annotationLoader = parent;

        // We want to see what services are registered, but without going through the service loader, since that results in a huge catastrophe of class not found exceptions
        // as the service loader tries to instantiate things in a nobbled loader. Instead, do it in a crude, safe, way by looking for the resource files and reading them.
        try {
            Enumeration<URL> declaredExtensions = annotationLoader
                    .getResources("META-INF/services/org.junit.jupiter.api.extension.Extension");
            while (declaredExtensions.hasMoreElements()) {
                URL url = declaredExtensions.nextElement();
                try (InputStream in = url.openStream()) {
                    String contents = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (QUARKUS_TEST_EXTENSION_NAME.equals(contents)) {
                        isServiceLoaderMechanism = true;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not check service loader registrations: " + e);
            throw new RuntimeException(e);
        }

        if (profileNames != null) {
            this.profileNames = new HashMap<>(profileNames);
        } else {
            // We set it to null so we know not to look in it
            this.profileNames = null;
        }

        facadeClassLoaderProviders = new ArrayList<>();
        ServiceLoader<FacadeClassLoaderProvider> loader = ServiceLoader.load(FacadeClassLoaderProvider.class,
                FacadeClassLoader.class.getClassLoader());
        loader.forEach(facadeClassLoaderProviders::add);
        runtimeInitializeForTests();
    }

    /**
     * This is not strictly related to tests, but we have some tuning to do on the JVM
     * which should really happen before any further Quarkus classes are loaded.
     */
    private void runtimeInitializeForTests() {
        JVMUnsafeWarningsControl.disableUnsafeRelatedWarnings();
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        // Debuggers, beware: If the same class is loaded with the same FacadeClassLoader instance using Class.forName(), a cached copy will be returned,
        // and nothing will be logged here; it will look like classloading didn't happen, but the problem is actually a stale instance
        log.debugf("Facade classloader loading %s for the first time\n", name);

        if (jandexIndex == null) {
            throw new RuntimeException("Attempted to load classes with a closed classloader: " + this);
        }
        boolean isQuarkusTest = false;
        boolean isIntegrationTest = false;

        // If the service loader mechanism is being used, QuarkusTestExtension gets loaded before any extensions which use it. We need to make sure it's on the right classloader.
        if (isServiceLoaderMechanism && (name.equals(QUARKUS_TEST_EXTENSION_NAME))) {
            try {
                // We don't have enough information to make a runtime classloader yet, but we can make a curated application and a base classloader
                QuarkusClassLoader runtimeClassLoader = getOrCreateBaseClassLoader(getProfileKey(null), null);
                return runtimeClassLoader.loadClass(name);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        try {

            String profileClassName = null;
            if (isContinuousTesting && !isServiceLoaderMechanism) {
                isQuarkusTest = quarkusTestClasses.contains(name);
                profileClassName = profileNames != null ? profileNames.get(name) : null;
            } else {
                if (quarkusTestAvailable) {
                    // Use the Jandex index to inspect annotations without loading the class
                    ClassInfo classInfo = jandexIndex.getClassByName(name);

                    if (classInfo == null) {
                        // Class is not in test classes directories (it's from a JAR or not on the classpath).
                        // It can't be a user test class, so delegate to parent.
                        return super.loadClass(name);
                    }

                    if (!classInfo.isAnnotation()) {

                        if (isServiceLoaderMechanism) {
                            // If a service loader was used to register QuarkusTestExtension, every JUnit test is a QuarkusTest
                            isQuarkusTest = hasTestMethods(classInfo);
                        }

                        // Because (until we do https://github.com/quarkusio/quarkus/issues/45785) we start dev services for disabled tests when we load them with the quarkus classloader, and those dev services often fail, put in some
                        // bypasses.
                        // These bypasses are also useful for performance.
                        boolean isDisabled = hasAnnotation(classInfo, DISABLED)
                                || isDisabledOnCurrentOs(classInfo);

                        // If a whole test class has an @Disabled annotation, do not bother creating a quarkus app for it
                        // Pragmatically, this fixes a LinkageError in grpc-cli which only reproduces in CI, but it's also probably what users would expect
                        if (!isDisabled) {
                            // There are several ways a test could be identified as a QuarkusTest:
                            // A Quarkus Test could be annotated with @QuarkusTest or with @ExtendWith[... QuarkusTestExtension.class ] or @RegisterExtension (or the service loader mechanism could be used)

                            isQuarkusTest = isQuarkusTest
                                    || isQuarkusTest(classInfo);

                            if (isQuarkusTest) {
                                // Many integration tests have Quarkus higher up in the hierarchy, but they do not count as QuarkusTests and have to be run differently
                                isIntegrationTest = hasAnnotation(classInfo, QUARKUS_INTEGRATION_TEST);

                                profileClassName = getTestProfileClassName(classInfo);
                            }
                        }
                    }
                }
            }

            if (isQuarkusTest && !isIntegrationTest) {
                QuarkusClassLoader runtimeClassLoader = getQuarkusClassLoader(name, profileClassName);
                return runtimeClassLoader.loadClass(name);
            } else {
                for (FacadeClassLoaderProvider p : facadeClassLoaderProviders) {
                    ClassLoader cl = p.getClassLoader(name, getParent());
                    if (cl != null) {
                        return cl.loadClass(name);
                    }
                }

                return super.loadClass(name);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Checks whether a class is a QuarkusTest using the Jandex index.
     * Walks class hierarchy and meta-annotations within the index,
     * with a reflection fallback for superclasses not in the index.
     */
    private boolean isQuarkusTest(ClassInfo classInfo) {
        if (hasQuarkusTestMarker(classInfo)) {
            return true;
        }

        // Walk superclass hierarchy
        DotName superName = classInfo.superName();
        while (superName != null && !OBJECT_NAME.equals(superName)) {
            ClassInfo superInfo = jandexIndex.getClassByName(superName);
            if (superInfo != null) {
                if (hasQuarkusTestMarker(superInfo)) {
                    return true;
                }
                superName = superInfo.superName();
            } else {
                // Superclass not in index (from a JAR) - fall back to reflection for the rest of the hierarchy.
                // This is a targeted load of just this superclass, not every class.
                if (isQuarkusTestViaReflection(superName.toString())) {
                    return true;
                }
                break;
            }
        }

        // Check @Nested parent
        if (classInfo.hasDeclaredAnnotation(NESTED)) {
            DotName enclosingName = classInfo.enclosingClass();
            if (enclosingName != null) {
                ClassInfo parentInfo = jandexIndex.getClassByName(enclosingName);
                if (parentInfo != null) {
                    return isQuarkusTest(parentInfo);
                }
            }
        }

        return false;
    }

    /**
     * Checks if a class has any of the markers that make it a QuarkusTest:
     * {@code @QuarkusTest} (including via meta-annotation within the index),
     * {@code @ExtendWith(QuarkusTestExtension.class)}, or
     * {@code @RegisterExtension} field of type QuarkusTestExtension.
     */
    private boolean hasQuarkusTestMarker(ClassInfo classInfo) {
        // Direct checks on this class
        if (classInfo.hasDeclaredAnnotation(QUARKUS_TEST)) {
            return true;
        }
        if (hasExtendWithQuarkusTestExtensionDirect(classInfo)) {
            return true;
        }
        // Check @RegisterExtension fields with QuarkusTestExtension type
        if (hasRegisterExtensionField(classInfo)) {
            return true;
        }
        // Walk meta-annotations looking for @QuarkusTest or @ExtendWith(QuarkusTestExtension.class)
        for (AnnotationInstance ann : classInfo.declaredAnnotations()) {
            ClassInfo annClassInfo = jandexIndex.getClassByName(ann.name());
            if (annClassInfo != null && hasQuarkusTestMarkerRecursive(annClassInfo, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively walks meta-annotations in the Jandex index looking for
     * {@code @QuarkusTest} or {@code @ExtendWith(QuarkusTestExtension.class)}.
     */
    private boolean hasQuarkusTestMarkerRecursive(ClassInfo annotationClassInfo, Set<DotName> visited) {
        if (!visited.add(annotationClassInfo.name())) {
            return false;
        }
        if (annotationClassInfo.hasDeclaredAnnotation(QUARKUS_TEST)) {
            return true;
        }
        if (hasExtendWithQuarkusTestExtensionDirect(annotationClassInfo)) {
            return true;
        }
        for (AnnotationInstance ann : annotationClassInfo.declaredAnnotations()) {
            ClassInfo nextAnn = jandexIndex.getClassByName(ann.name());
            if (nextAnn != null && hasQuarkusTestMarkerRecursive(nextAnn, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a class has a given annotation, including via meta-annotations
     * (annotations on annotations) within the Jandex index.
     */
    private boolean hasAnnotation(ClassInfo classInfo, DotName annotationName) {
        if (classInfo.hasDeclaredAnnotation(annotationName)) {
            return true;
        }
        // Walk meta-annotations: check if any annotation on this class is itself annotated with the target
        for (AnnotationInstance ann : classInfo.declaredAnnotations()) {
            ClassInfo annClassInfo = jandexIndex.getClassByName(ann.name());
            if (annClassInfo != null && hasAnnotationRecursive(annClassInfo, annotationName, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnnotationRecursive(ClassInfo annotationClassInfo, DotName target, Set<DotName> visited) {
        if (!visited.add(annotationClassInfo.name())) {
            return false;
        }
        if (annotationClassInfo.hasDeclaredAnnotation(target)) {
            return true;
        }
        for (AnnotationInstance ann : annotationClassInfo.declaredAnnotations()) {
            ClassInfo nextAnn = jandexIndex.getClassByName(ann.name());
            if (nextAnn != null && hasAnnotationRecursive(nextAnn, target, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fallback for checking @QuarkusTest on superclasses that are not in the Jandex index
     * (e.g., base test classes in library JARs). Loads only the specific superclass via parent CL.
     */
    private boolean isQuarkusTestViaReflection(String className) {
        try {
            Class<?> cls = getParent().loadClass(className);
            // Walk up until we find @QuarkusTest or reach Object
            while (cls != null && cls != Object.class) {
                for (java.lang.annotation.Annotation ann : cls.getDeclaredAnnotations()) {
                    String annName = ann.annotationType().getName();
                    if (annName.equals(QUARKUS_TEST.toString())) {
                        return true;
                    }
                    // Check meta-annotations one level deep (covers @QuarkusTest on custom annotations)
                    for (java.lang.annotation.Annotation metaAnn : ann.annotationType().getDeclaredAnnotations()) {
                        if (metaAnn.annotationType().getName().equals(QUARKUS_TEST.toString())) {
                            return true;
                        }
                    }
                }
                // Also check @ExtendWith
                for (java.lang.annotation.Annotation ann : cls.getDeclaredAnnotations()) {
                    if (ann.annotationType().getName().equals(EXTEND_WITH.toString())) {
                        if (ann.toString().contains(QUARKUS_TEST_EXTENSION_NAME)) {
                            return true;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.debugf("Could not load superclass %s for QuarkusTest check: %s", className, e);
        }
        return false;
    }

    /**
     * Check if any methods have @Test annotation (for service loader mechanism).
     */
    private boolean hasTestMethods(ClassInfo classInfo) {
        return classInfo.methods().stream().anyMatch(m -> m.hasAnnotation(TEST_ANNOTATION));
    }

    private boolean isDisabledOnCurrentOs(ClassInfo classInfo) {
        AnnotationInstance ann = classInfo.declaredAnnotation(DISABLED_ON_OS);
        if (ann == null) {
            return false;
        }
        AnnotationValue value = ann.value();
        if (value == null) {
            return false;
        }
        for (String osName : value.asEnumArray()) {
            try {
                if (OS.valueOf(osName).isCurrentOs()) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Unknown OS enum constant - ignore
            }
        }
        return false;
    }

    /**
     * Checks if this specific class/annotation has {@code @ExtendWith(QuarkusTestExtension.class)} directly declared.
     * Does NOT walk meta-annotations — that is handled by {@link #hasQuarkusTestMarkerRecursive}.
     */
    private boolean hasExtendWithQuarkusTestExtensionDirect(ClassInfo classInfo) {
        AnnotationInstance ann = classInfo.declaredAnnotation(EXTEND_WITH);
        if (ann == null) {
            return false;
        }
        AnnotationValue value = ann.value();
        if (value == null) {
            return false;
        }
        for (Type type : value.asClassArray()) {
            if (type.name().toString().equals(QUARKUS_TEST_EXTENSION_NAME)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRegisterExtensionField(ClassInfo classInfo) {
        for (FieldInfo field : classInfo.fields()) {
            if (field.hasAnnotation(REGISTER_EXTENSION)
                    && field.type().name().toString().equals(QUARKUS_TEST_EXTENSION_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the @TestProfile value class name from a ClassInfo, or null if not present.
     * Walks meta-annotations and enclosing classes for @Nested tests.
     */
    private String getTestProfileClassName(ClassInfo classInfo) {
        // Direct check
        AnnotationInstance profileAnn = classInfo.declaredAnnotation(TEST_PROFILE);
        if (profileAnn != null) {
            AnnotationValue value = profileAnn.value();
            if (value != null) {
                return value.asClass().name().toString();
            }
        }
        // For @Nested classes, check enclosing class
        if (classInfo.hasDeclaredAnnotation(NESTED)) {
            DotName enclosingName = classInfo.enclosingClass();
            if (enclosingName != null) {
                ClassInfo enclosing = jandexIndex.getClassByName(enclosingName);
                if (enclosing != null) {
                    return getTestProfileClassName(enclosing);
                }
            }
        }
        return null;
    }

    private QuarkusClassLoader getQuarkusClassLoader(String testClassName, String profileClassName) {
        String profileKey = getProfileKey(profileClassName);

        try {
            String key;
            QuarkusClassLoader classLoader;

            // Load test class from parent CL for downstream APIs that need Class<?>
            Class<?> testClass = getParent().loadClass(testClassName);
            Class<?> profileClass = profileClassName != null ? getParent().loadClass(profileClassName) : null;

            // We cannot directly access TestResourceUtil as long as we're in the core module, but the app classloaders can.
            // But, chicken-and-egg, we may not have an app classloader yet. However, if we don't, we won't need to worry about restarts, but this instance clearly cannot need a restart

            // If we make a classloader with a null profile, we get the problem of starting dev services multiple times, which is very bad (if temporary) - once that issue is fixed, could reconsider
            if (keyMakerClassLoader == null) {
                // Making a classloader uses the profile key to look up a curated application
                classLoader = getOrCreateRuntimeClassLoader(profileKey, testClass, Optional.ofNullable(profileClass));
                keyMakerClassLoader = classLoader;

                // We cannot use the startup action one because it's a base runtime classloader and so will not have the right access to application classes (they're in its banned list)
                final String resourceKey = getResourceKey(testClass, profileClass);

                // The resource key might be null, and that's ok
                key = profileKey + resourceKey;
            } else {
                final String resourceKey = getResourceKey(testClass, profileClass);

                // The resource key might be null, and that's ok
                key = profileKey + resourceKey;
                classLoader = runtimeClassLoaders.get(key);
                if (classLoader == null) {
                    // Making a classloader uses the profile key to look up a curated application
                    classLoader = getOrCreateRuntimeClassLoader(profileKey, testClass, Optional.ofNullable(profileClass));
                }
            }

            runtimeClassLoaders.put(key, classLoader);
            return classLoader;
        } catch (RuntimeException e) {
            // Exceptions here get swallowed by the JUnit framework and we don't get any debug information unless we print it ourself
            e.printStackTrace();
            throw e;
        } catch (Exception e) {
            // Exceptions here get swallowed by the JUnit framework and we don't get any debug information unless we print it ourself
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static String getProfileKey(String profileClassName) {
        return KEY_PREFIX + (profileClassName != null ? profileClassName : NO_PROFILE);
    }

    private String getResourceKey(Class<?> requiredTestClass, Class<?> profile)
            throws NoSuchMethodException, ClassNotFoundException, IllegalAccessException, InvocationTargetException {

        String resourceKey;

        ClassLoader classLoader = keyMakerClassLoader;
        // We have to access TestResourceUtil reflectively, because if we used this class's classloader, it might be an augmentation classloader without access to application classes
        Method method = Class
                .forName(TestResourceUtil.class.getName(), true, classLoader)
                .getMethod("getReloadGroupIdentifier", Class.class, Class.class);

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(keyMakerClassLoader);
            // When we load the TestResourceUtil loading gets delegated to a base runtime classloader, which cannot see the app classes; so we need to pre-port the profile to its classloader before passing it to it
            Class<?> transliteratedProfile = profile != null ? keyMakerClassLoader.loadClass(profile.getName()) : null;
            // we reload the test resources (and thus the application) if we changed test class and the new test class is not a nested class, and if we had or will have per-test test resources
            resourceKey = (String) method.invoke(null, requiredTestClass, transliteratedProfile);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        return resourceKey;
    }

    private CuratedApplication getOrCreateCuratedApplication(String key, Class<?> requiredTestClass, List<Path> additionalPaths)
            throws Exception {
        CuratedApplication curatedApplication = curatedApplications.get(key);

        if (curatedApplication == null) {
            String displayName = DISPLAY_NAME_PREFIX + key;
            // TODO should we use clonedBuilder here, like TestSupport does?
            curatedApplication = AppMakerHelper.makeCuratedApplication(requiredTestClass, additionalPaths, displayName,
                    isAuxiliaryApplication);
            curatedApplications.put(key, curatedApplication);
        }

        return curatedApplication;
    }

    private QuarkusClassLoader getOrCreateBaseClassLoader(String key, Class<?> requiredTestClass) throws Exception {
        CuratedApplication curatedApplication = getOrCreateCuratedApplication(key, requiredTestClass, List.of());
        return curatedApplication.getOrCreateBaseRuntimeClassLoader();
    }

    @SuppressWarnings("unchecked")
    private QuarkusClassLoader getOrCreateRuntimeClassLoader(String key, Class<?> requiredTestClass, Optional<Class<?>> profile)
            throws Exception {

        // Generate Profile Resource
        List<Path> additionalPaths = new ArrayList<>();
        if (profile.isPresent()) {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(profile.get().getClassLoader());

            TestProfileAndProperties testProfileAndProperties = TestProfileAndProperties.of(profile.get(), TEST);
            TestProfileConfigSource testProfileConfigSource = testProfileAndProperties.toTestProfileConfigSource();
            additionalPaths.add(testProfileConfigSource.getPropertiesLocation());

            Thread.currentThread().setContextClassLoader(old);
        }

        CuratedApplication curatedApplication = getOrCreateCuratedApplication(key, requiredTestClass, additionalPaths);
        StartupAction startupAction = AppMakerHelper.getStartupAction(requiredTestClass, curatedApplication, profile);
        return startupAction.getClassLoader();
    }

    public boolean isServiceLoaderMechanism() {
        return isServiceLoaderMechanism;
    }

    /**
     * Returns true if, after test discovery, multiple classloaders have been created. This would mean multiple Quarkus
     * applications will be started to run the tests.
     */
    public boolean hasMultipleClassLoaders() {
        return runtimeClassLoaders != null && runtimeClassLoaders.size() > 1;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void close() throws IOException {
        jandexIndex = null;

        // Null out the keymaker classloader and runtime classloaders, but don't close them, since we assume they will be closed by the test framework closing the owning application
        keyMakerClassLoader = null;
        runtimeClassLoaders.clear();

    }

    private static void indexDirectory(Indexer indexer, Path directory) {
        try {
            Files.walkFileTree(directory, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".class")) {
                        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
                            indexer.index(in);
                        } catch (Exception e) {
                            // ignore - class files that can't be indexed are skipped
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Could not index directory " + directory + ": " + e);
        }
    }

    private static void indexJar(Indexer indexer, Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        indexer.index(in);
                    } catch (Exception e) {
                        // ignore - class files that can't be indexed are skipped
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not index JAR " + jarPath + ": " + e);
        }
    }
}
