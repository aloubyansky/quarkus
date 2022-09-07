package io.quarkus.test.junit;

import static io.quarkus.test.common.PathTestHelper.getAppClassLocationForTestLocation;
import static io.quarkus.test.common.PathTestHelper.getTestClassesLocation;
import static java.lang.ProcessBuilder.Redirect.DISCARD;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import javax.enterprise.inject.Alternative;
import javax.inject.Inject;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.JUnitException;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.utils.BuildToolHelper;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.deployment.builditem.DevServicesLauncherConfigResultBuildItem;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.configuration.ProfileManager;
import io.quarkus.test.common.ArtifactLauncher;
import io.quarkus.test.common.LauncherUtil;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.TestClassIndexer;
import io.quarkus.test.common.TestResourceManager;
import io.quarkus.test.common.http.TestHTTPResourceManager;

public final class IntegrationTestUtil {

    public static final int DEFAULT_PORT = 8081;
    public static final int DEFAULT_HTTPS_PORT = 8444;
    public static final long DEFAULT_WAIT_TIME_SECONDS = 60;

    private static final String DOCKER_BINARY = "docker";

    private IntegrationTestUtil() {
    }

    static void ensureNoInjectAnnotationIsUsed(Class<?> testClass) {
        Class<?> current = testClass;
        while (current.getSuperclass() != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getAnnotation(Inject.class) != null) {
                    throw new JUnitException(
                            "@Inject is not supported in @NativeImageTest and @QuarkusIntegrationTest tests. Offending field is "
                                    + field.getDeclaringClass().getTypeName() + "."
                                    + field.getName());
                }
                if (field.getAnnotation(ConfigProperty.class) != null) {
                    throw new JUnitException(
                            "@ConfigProperty is not supported in @NativeImageTest and @QuarkusIntegrationTest tests. Offending field is "
                                    + field.getDeclaringClass().getTypeName() + "."
                                    + field.getName());
                }
            }
            current = current.getSuperclass();
        }

    }

    static Class<? extends QuarkusTestProfile> findProfile(Class<?> testClass) {
        while (testClass != Object.class) {
            TestProfile annotation = testClass.getAnnotation(TestProfile.class);
            if (annotation != null) {
                return annotation.value();
            }
            testClass = testClass.getSuperclass();
        }
        return null;
    }

    static void doProcessTestInstance(Object testInstance, ExtensionContext context) {
        TestHTTPResourceManager.inject(testInstance);
        ExtensionContext root = context.getRoot();
        ExtensionContext.Store store = root.getStore(ExtensionContext.Namespace.GLOBAL);
        QuarkusTestExtensionState state = store.get(QuarkusTestExtensionState.class.getName(),
                QuarkusTestExtensionState.class);
        Object testResourceManager = state.testResourceManager;
        if (!(testResourceManager instanceof TestResourceManager)) {
            throw new RuntimeException(
                    "An unexpected situation occurred while trying to instantiate the testing infrastructure. Have you perhaps mixed @QuarkusTest and @QuarkusIntegrationTest in the same test run?");
        }
        ((TestResourceManager) state.testResourceManager).inject(testInstance);
    }

    static Map<String, String> getSysPropsToRestore() {
        Map<String, String> sysPropRestore = new HashMap<>();
        sysPropRestore.put(ProfileManager.QUARKUS_TEST_PROFILE_PROP,
                System.getProperty(ProfileManager.QUARKUS_TEST_PROFILE_PROP));
        return sysPropRestore;
    }

    static TestProfileAndProperties determineTestProfileAndProperties(Class<? extends QuarkusTestProfile> profile,
            Map<String, String> sysPropRestore)
            throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        final Map<String, String> properties = new HashMap<>();
        QuarkusTestProfile testProfile = null;
        if (profile != null) {
            testProfile = profile.getDeclaredConstructor().newInstance();
            properties.putAll(testProfile.getConfigOverrides());
            final Set<Class<?>> enabledAlternatives = testProfile.getEnabledAlternatives();
            if (!enabledAlternatives.isEmpty()) {
                properties.put("quarkus.arc.selected-alternatives", enabledAlternatives.stream()
                        .peek((c) -> {
                            if (!c.isAnnotationPresent(Alternative.class)) {
                                throw new RuntimeException(
                                        "Enabled alternative " + c + " is not annotated with @Alternative");
                            }
                        })
                        .map(Class::getName).collect(Collectors.joining(",")));
            }
            final String configProfile = testProfile.getConfigProfile();
            if (configProfile != null) {
                properties.put(ProfileManager.QUARKUS_PROFILE_PROP, configProfile);
            }
            properties.put("quarkus.configuration.build-time-mismatch-at-runtime", "fail");
            for (Map.Entry<String, String> i : properties.entrySet()) {
                sysPropRestore.put(i.getKey(), System.getProperty(i.getKey()));
            }
            for (Map.Entry<String, String> i : properties.entrySet()) {
                System.setProperty(i.getKey(), i.getValue());
            }
        }
        return new TestProfileAndProperties(testProfile, properties);
    }

    /**
     * Since {@link TestResourceManager} is loaded from the ClassLoader passed in as an argument,
     * we need to convert the user input {@link QuarkusTestProfile.TestResourceEntry} into instances of
     * {@link TestResourceManager.TestResourceClassEntry}
     * that are loaded from that ClassLoader
     */
    public static <T> List<T> getAdditionalTestResources(
            QuarkusTestProfile profileInstance, ClassLoader classLoader) {
        if ((profileInstance == null) || profileInstance.testResources().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Constructor<?> testResourceClassEntryConstructor = Class
                    .forName(TestResourceManager.TestResourceClassEntry.class.getName(), true, classLoader)
                    .getConstructor(Class.class, Map.class, Annotation.class, boolean.class);

            List<QuarkusTestProfile.TestResourceEntry> testResources = profileInstance.testResources();
            List<T> result = new ArrayList<>(testResources.size());
            for (QuarkusTestProfile.TestResourceEntry testResource : testResources) {
                T instance = (T) testResourceClassEntryConstructor.newInstance(
                        Class.forName(testResource.getClazz().getName(), true, classLoader), testResource.getArgs(),
                        null, testResource.isParallel());
                result.add(instance);
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to handle profile " + profileInstance.getClass(), e);
        }
    }

    static void startLauncher(ArtifactLauncher launcher, Map<String, String> additionalProperties, Runnable sslSetter)
            throws IOException {
        launcher.includeAsSysProps(additionalProperties);
        try {
            launcher.start();
        } catch (IOException e) {
            try {
                launcher.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
        if (launcher.listensOnSsl()) {
            if (sslSetter != null) {
                sslSetter.run();
            }
        }
    }

    static ArtifactLauncher.InitContext.DevServicesLaunchResult handleDevServices(ExtensionContext context,
            boolean isDockerAppLaunch) throws Exception {
        Class<?> requiredTestClass = context.getRequiredTestClass();
        Path testClassLocation = getTestClassesLocation(requiredTestClass);
        final Path appClassLocation = getAppClassLocationForTestLocation(testClassLocation.toString());

        final PathList.Builder rootBuilder = PathList.builder();

        if (!appClassLocation.equals(testClassLocation)) {
            rootBuilder.add(testClassLocation);
            // if test classes is a dir, we should also check whether test resources dir exists as a separate dir (gradle)
            // TODO: this whole app/test path resolution logic is pretty dumb, it needs be re-worked using proper workspace discovery
            final Path testResourcesLocation = PathTestHelper.getResourcesForClassesDirOrNull(testClassLocation, "test");
            if (testResourcesLocation != null) {
                rootBuilder.add(testResourcesLocation);
            }
        }
        final QuarkusBootstrap.Builder runnerBuilder = QuarkusBootstrap.builder()
                .setIsolateDeployment(true)
                .setMode(QuarkusBootstrap.Mode.TEST);

        final Path projectRoot = Paths.get("").normalize().toAbsolutePath();
        runnerBuilder.setProjectRoot(projectRoot);
        runnerBuilder.setTargetDirectory(PathTestHelper.getProjectBuildDir(projectRoot, testClassLocation));

        rootBuilder.add(appClassLocation);
        final Path appResourcesLocation = PathTestHelper.getResourcesForClassesDirOrNull(appClassLocation, "main");
        if (appResourcesLocation != null) {
            rootBuilder.add(appResourcesLocation);
        }

        // If gradle project running directly with IDE
        if (System.getProperty(BootstrapConstants.SERIALIZED_TEST_APP_MODEL) == null) {
            ApplicationModel model = BuildToolHelper.enableGradleAppModelForTest(projectRoot);
            if (model != null && model.getApplicationModule() != null) {
                final ArtifactSources testSources = model.getApplicationModule().getTestSources();
                if (testSources != null) {
                    for (SourceDir src : testSources.getSourceDirs()) {
                        if (!Files.exists(src.getOutputDir())) {
                            final Path classes = src.getOutputDir();
                            if (!rootBuilder.contains(classes)) {
                                rootBuilder.add(classes);
                            }
                        }
                    }
                }
                for (SourceDir src : model.getApplicationModule().getMainSources().getSourceDirs()) {
                    if (!Files.exists(src.getOutputDir())) {
                        final Path classes = src.getOutputDir();
                        if (!rootBuilder.contains(classes)) {
                            rootBuilder.add(classes);
                        }
                    }
                }
            }
        } else if (System.getProperty(BootstrapConstants.OUTPUT_SOURCES_DIR) != null) {
            final String[] sourceDirectories = System.getProperty(BootstrapConstants.OUTPUT_SOURCES_DIR).split(",");
            for (String sourceDirectory : sourceDirectories) {
                final Path directory = Paths.get(sourceDirectory);
                if (Files.exists(directory) && !rootBuilder.contains(directory)) {
                    rootBuilder.add(directory);
                }
            }
        }
        runnerBuilder.setApplicationRoot(rootBuilder.build());

        CuratedApplication curatedApplication = runnerBuilder
                .setTest(true)
                .build()
                .bootstrap();

        Index testClassesIndex = TestClassIndexer.indexTestClasses(requiredTestClass);
        // we need to write the Index to make it reusable from other parts of the testing infrastructure that run in different ClassLoaders
        TestClassIndexer.writeIndex(testClassesIndex, requiredTestClass);

        Map<String, String> propertyMap = new HashMap<>();
        AugmentAction augmentAction;
        String networkId = null;
        if (isDockerAppLaunch) {
            // when the application is going to be launched as a docker container, we need to make containers started by DevServices
            // use a shared network that the application container can then use as well
            augmentAction = curatedApplication.createAugmentor(
                    "io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem$Factory", Collections.emptyMap());
        } else {
            augmentAction = curatedApplication.createAugmentor();
        }
        augmentAction.performCustomBuild(NativeDevServicesHandler.class.getName(), new BiConsumer<String, String>() {
            @Override
            public void accept(String s, String s2) {
                propertyMap.put(s, s2);
            }
        }, DevServicesLauncherConfigResultBuildItem.class.getName());

        boolean manageNetwork = false;
        if (isDockerAppLaunch) {
            // obtain the ID of the shared network - this needs to be done after the augmentation has been run
            // or else we run into various ClassLoader problems
            try {
                Class<?> networkClass = curatedApplication.getAugmentClassLoader()
                        .loadClass("org.testcontainers.containers.Network");
                Object sharedNetwork = networkClass.getField("SHARED").get(null);
                networkId = (String) networkClass.getMethod("getId").invoke(sharedNetwork);
            } catch (Exception e) {
                // use the network the use has specified or else just generate one if none is configured

                Config config = LauncherUtil.installAndGetSomeConfig();
                Optional<String> networkIdOpt = config
                        .getOptionalValue("quarkus.test.container.network", String.class);
                if (networkIdOpt.isPresent()) {
                    networkId = networkIdOpt.get();
                } else {
                    networkId = "quarkus-integration-test-" + RandomStringUtils.random(5, true, false);
                    manageNetwork = true;
                }
            }
        }

        DefaultDevServicesLaunchResult result = new DefaultDevServicesLaunchResult(propertyMap, networkId, manageNetwork,
                curatedApplication);
        createNetworkIfNecessary(result);
        return result;
    }

    // this probably isn't the best place for this method, but we need to create the docker container before
    // user code is aware of the network
    private static void createNetworkIfNecessary(
            final ArtifactLauncher.InitContext.DevServicesLaunchResult devServicesLaunchResult) {
        if (devServicesLaunchResult.manageNetwork() && (devServicesLaunchResult.networkId() != null)) {
            try {
                int networkCreateResult = new ProcessBuilder().redirectError(DISCARD).redirectOutput(DISCARD)
                        .command(DOCKER_BINARY, "network", "create", devServicesLaunchResult.networkId()).start().waitFor();
                if (networkCreateResult > 0) {
                    throw new RuntimeException("Creating container network '" + devServicesLaunchResult.networkId()
                            + "' completed unsuccessfully");
                }
                // do the cleanup in a shutdown hook because there might be more services (launched via QuarkusTestResourceLifecycleManager) connected to the network
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            new ProcessBuilder().redirectError(DISCARD).redirectOutput(DISCARD)
                                    .command(DOCKER_BINARY, "network", "rm", devServicesLaunchResult.networkId()).start()
                                    .waitFor();
                        } catch (InterruptedException | IOException ignored) {
                            System.out.println(
                                    "Unable to delete container network '" + devServicesLaunchResult.networkId() + "'");
                        }
                    }
                }));
            } catch (Exception e) {
                throw new RuntimeException("Creating container network '" + devServicesLaunchResult.networkId()
                        + "' completed unsuccessfully");
            }
        }
    }

    static class DefaultDevServicesLaunchResult implements ArtifactLauncher.InitContext.DevServicesLaunchResult {
        private final Map<String, String> properties;
        private final String networkId;
        private final boolean manageNetwork;
        private final CuratedApplication curatedApplication;

        DefaultDevServicesLaunchResult(Map<String, String> properties, String networkId,
                boolean manageNetwork, CuratedApplication curatedApplication) {
            this.properties = properties;
            this.networkId = networkId;
            this.manageNetwork = manageNetwork;
            this.curatedApplication = curatedApplication;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public String networkId() {
            return networkId;
        }

        @Override
        public boolean manageNetwork() {
            return manageNetwork;
        }

        @Override
        public void close() {
            curatedApplication.close();
        }
    }
}
