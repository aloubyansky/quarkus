/*
 * Copyright 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.quarkus.test.junit;

import static io.quarkus.test.common.PathTestHelper.getAppClassLocation;
import static io.quarkus.test.common.PathTestHelper.getTestClassesLocation;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarFile;

import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResult;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;
import org.junit.platform.commons.JUnitException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.opentest4j.TestAbortedException;

import io.quarkus.bootstrap.BootstrapClassLoaderFactory;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.model.AppArtifactCoords;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.MavenRepoInitializer;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.bootstrap.util.PropertyUtils;
import io.quarkus.bootstrap.util.ZipUtils;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.ClassOutput;
import io.quarkus.deployment.QuarkusClassWriter;
import io.quarkus.deployment.builditem.TestAnnotationBuildItem;
import io.quarkus.deployment.builditem.TestClassPredicateBuildItem;
import io.quarkus.deployment.util.IoUtil;
import io.quarkus.runner.RuntimeRunner;
import io.quarkus.runner.TransformerTarget;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.common.NativeImageLauncher;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.PropertyTestUtil;
import io.quarkus.test.common.RestAssuredURLManager;
import io.quarkus.test.common.TestInjectionManager;
import io.quarkus.test.common.TestResourceManager;
import io.quarkus.test.common.TestScopeManager;
import io.quarkus.test.common.http.TestHTTPResourceManager;

public class QuarkusTestExtension
        implements BeforeEachCallback, AfterEachCallback, TestInstanceFactory, BeforeAllCallback {

    private URLClassLoader appCl;
    private ClassLoader originalCl;
    private static boolean failedBoot;

    private void logUrls(ClassLoader cl) {
        final ClassLoader parent = cl.getParent();
        if (parent != null) {
            logUrls(parent);
        }
        log("CL " + cl.getClass().getName());
        if (!(cl instanceof URLClassLoader)) {
            return;
        }
        final List<String> list = new ArrayList<>();
        URL[] urls = ((URLClassLoader) cl).getURLs();
        if (urls.length > 1) {
            for (URL url : urls) {
                list.add(url.toExternalForm());
            }
        } else {
            try {
                try (JarFile jar = new JarFile(Paths.get(urls[0].toURI()).toFile())) {
                    String cp = jar.getManifest().getMainAttributes().getValue("Class-Path");
                    if (cp != null) {
                        for (String entry : cp.split("\\s+")) {
                            list.add(entry);
                        }
                    } else {
                        list.add(urls[0].toExternalForm());
                    }
                }
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        Collections.sort(list);
        for (String s : list) {
            log(s);
        }
    }

    private BufferedWriter writer;

    private void log(Object msg) {
        try {
            writer.write(msg == null ? "null" : msg.toString());
            writer.newLine();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private ExtensionState doJavaStart(ExtensionContext context, TestResourceManager testResourceManager) {

        final LinkedBlockingDeque<Runnable> shutdownTasks = new LinkedBlockingDeque<>();

        Path appClassLocation = getAppClassLocation(context.getRequiredTestClass());
        Path testClassLocation = getTestClassesLocation(context.getRequiredTestClass());
        ClassLoader testClassLoader = context.getRequiredTestClass().getClassLoader();

        logUrls(Thread.currentThread().getContextClassLoader());
        Set<AppArtifactKey> cpDeps = new HashSet<>();
        final Model model = new Model();
        model.setVersion("4.0.0");
        model.setGroupId("io.quarkus.test");
        model.setArtifactId("deployment-classpath");
        model.setVersion("1");
        model.setPackaging("pom");
        final Path deploymentPom = appClassLocation.getParent().resolve("deployment-pom").resolve("pom.xml");
        model.setPomFile(deploymentPom.toFile());

        try {
            log("CLASSES DIRS");
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources("");
            while (resources.hasMoreElements()) {
                log(resources.nextElement());
            }
        } catch (IOException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
        }
        try {
            final Enumeration<URL> metaInfUrls = Thread.currentThread().getContextClassLoader().getResources("META-INF");
            while (metaInfUrls.hasMoreElements()) {
                final URL nextElement = metaInfUrls.nextElement();
                String path = nextElement.getPath();
                final int exlamMark = path.lastIndexOf('!');
                if (exlamMark < 0) {
                    processMetaInf(null, Paths.get(path), cpDeps, model);
                } else {
                    path = path.substring(0, exlamMark);
                    final Path jar = Paths.get(new URL(path).toURI());
                    try (FileSystem jarFs = ZipUtils.newFileSystem(jar)) {
                        processMetaInf(jar, jarFs.getPath("META-INF"), cpDeps, model);
                    }
                }
            }
            Files.createDirectories(deploymentPom.getParent());
            ModelUtils.persistModel(deploymentPom, model);
        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        try {
            final LocalWorkspace ws = new LocalWorkspace();
            try {
                new LocalProject(model, ws);
            } catch (BootstrapException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            final MavenArtifactResolver mvn = MavenArtifactResolver.builder()
                    .setOffline(false)
                    .setWorkspace(ws)
                    .build();
            final DependencyResult result = mvn
                    .resolveDependencies(new DefaultArtifact("io.quarkus.test", "deployment-classpath", "", "pom", "1"));
            log("DEPLOYMENT CP");
            List<URL> urls = new ArrayList<>();
            for (ArtifactResult ar : result.getArtifactResults()) {
                final Artifact artifact = ar.getArtifact();
                if (cpDeps.contains(new AppArtifactKey(artifact.getGroupId(), artifact.getArtifactId()))) {
                    continue;
                }
                urls.add(artifact.getFile().toURI().toURL());
            }

            final List<String> ordered = new ArrayList<>(urls.size());
            for (URL url : urls) {
                ordered.add(url.toExternalForm());
            }
            Collections.sort(ordered);
            for (String entry : ordered) {
                log(entry);
            }

            appCl = new URLClassLoader(urls.toArray(new URL[urls.size()]), getClass().getClassLoader());

        } catch (Exception e1) {
            throw new IllegalStateException("Failed to create deployment CL", e1);
        }

        try {
            BootstrapClassLoaderFactory.newInstance()
                    .setAppClasses(appClassLocation)
                    .addToClassPath(testClassLocation)
                    .setParent(getClass().getClassLoader())
                    .setOffline(PropertyUtils.getBooleanOrNull(BootstrapClassLoaderFactory.PROP_OFFLINE))
                    .setLocalProjectsDiscovery(
                            PropertyUtils.getBoolean(BootstrapClassLoaderFactory.PROP_WS_DISCOVERY, true))
                    .setEnableClasspathCache(PropertyUtils.getBoolean(BootstrapClassLoaderFactory.PROP_CP_CACHE, false))
                    .newDeploymentClassLoader();
        } catch (BootstrapException e) {
            throw new IllegalStateException("Failed to create the boostrap class loader", e);
        }

        originalCl = setCCL(appCl);

        RuntimeRunner runtimeRunner = RuntimeRunner.builder()
                .setLaunchMode(LaunchMode.TEST)
                .setClassLoader(appCl)
                .setTarget(appClassLocation)
                .addAdditionalArchive(testClassLocation)
                .setClassOutput(new ClassOutput() {
                    @Override
                    public void writeClass(boolean applicationClass, String className, byte[] data) throws IOException {
                        Path location = testClassLocation.resolve(className.replace('.', '/') + ".class");
                        Files.createDirectories(location.getParent());
                        try (FileOutputStream out = new FileOutputStream(location.toFile())) {
                            out.write(data);
                        }
                        shutdownTasks.add(new DeleteRunnable(location));
                    }

                    @Override
                    public void writeResource(String name, byte[] data) throws IOException {
                        Path location = testClassLocation.resolve(name);
                        Files.createDirectories(location.getParent());
                        try (FileOutputStream out = new FileOutputStream(location.toFile())) {
                            out.write(data);
                        }
                        shutdownTasks.add(new DeleteRunnable(location));
                    }
                })
                .setTransformerTarget(new TransformerTarget() {
                    @Override
                    public void setTransformers(
                            Map<String, List<BiFunction<String, ClassVisitor, ClassVisitor>>> functions) {
                        ClassLoader main = Thread.currentThread().getContextClassLoader();

                        //we need to use a temp class loader, or the old resource location will be cached
                        ClassLoader temp = new ClassLoader() {
                            @Override
                            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                                // First, check if the class has already been loaded
                                Class<?> c = findLoadedClass(name);
                                if (c == null) {
                                    c = findClass(name);
                                }
                                if (resolve) {
                                    resolveClass(c);
                                }
                                return c;
                            }

                            @Override
                            public URL getResource(String name) {
                                return main.getResource(name);
                            }

                            @Override
                            public Enumeration<URL> getResources(String name) throws IOException {
                                return main.getResources(name);
                            }
                        };
                        for (Map.Entry<String, List<BiFunction<String, ClassVisitor, ClassVisitor>>> e : functions
                                .entrySet()) {
                            String resourceName = e.getKey().replace('.', '/') + ".class";
                            try (InputStream stream = temp.getResourceAsStream(resourceName)) {
                                if (stream == null) {
                                    System.err.println("Failed to transform " + e.getKey());
                                    continue;
                                }
                                byte[] data = IoUtil.readBytes(stream);

                                ClassReader cr = new ClassReader(data);
                                ClassWriter cw = new QuarkusClassWriter(cr,
                                        ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {

                                    @Override
                                    protected ClassLoader getClassLoader() {
                                        return temp;
                                    }
                                };
                                ClassLoader old = Thread.currentThread().getContextClassLoader();
                                Thread.currentThread().setContextClassLoader(temp);
                                try {
                                    ClassVisitor visitor = cw;
                                    for (BiFunction<String, ClassVisitor, ClassVisitor> i : e.getValue()) {
                                        visitor = i.apply(e.getKey(), visitor);
                                    }
                                    cr.accept(visitor, 0);
                                } finally {
                                    Thread.currentThread().setContextClassLoader(old);
                                }

                                Path location = testClassLocation.resolve(resourceName);
                                Files.createDirectories(location.getParent());
                                try (FileOutputStream out = new FileOutputStream(location.toFile())) {
                                    out.write(cw.toByteArray());
                                }
                                shutdownTasks.add(new DeleteRunnable(location));
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
                })
                .addChainCustomizer(new Consumer<BuildChainBuilder>() {
                    @Override
                    public void accept(BuildChainBuilder buildChainBuilder) {
                        buildChainBuilder.addBuildStep(new BuildStep() {
                            @Override
                            public void execute(BuildContext context) {
                                context.produce(new TestClassPredicateBuildItem(new Predicate<String>() {
                                    @Override
                                    public boolean test(String className) {
                                        return PathTestHelper.isTestClass(className, testClassLoader);
                                    }
                                }));
                            }
                        }).produces(TestClassPredicateBuildItem.class)
                                .build();
                    }
                })
                .addChainCustomizer(new Consumer<BuildChainBuilder>() {
                    @Override
                    public void accept(BuildChainBuilder buildChainBuilder) {
                        buildChainBuilder.addBuildStep(new BuildStep() {
                            @Override
                            public void execute(BuildContext context) {
                                context.produce(new TestAnnotationBuildItem(QuarkusTest.class.getName()));
                            }
                        }).produces(TestAnnotationBuildItem.class)
                                .build();
                    }
                })
                .build();
        runtimeRunner.run();

        Closeable shutdownTask = new Closeable() {
            @Override
            public void close() throws IOException {
                runtimeRunner.close();
                while (!shutdownTasks.isEmpty()) {
                    shutdownTasks.pop().run();
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    shutdownTask.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "Quarkus Test Cleanup Shutdown task"));
        return new ExtensionState(testResourceManager, shutdownTask, false);
    }

    private void processMetaInf(Path jar, Path metaInf, Set<AppArtifactKey> cpDeps, final Model model)
            throws IOException {

        String groupId = null;
        String artifactId = null;
        String version = null;

        final Properties props = new Properties();

        if (jar != null) {
            try {
                final String localRepo = MavenRepoInitializer.getLocalRepo(MavenRepoInitializer.getSettings());
                if (jar.startsWith(localRepo)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(jar.getParent())) {
                        for (Path p : stream) {
                            if (p.getFileName().toString().endsWith(".pom")) {
                                final Model pom = ModelUtils.readModel(p);
                                groupId = pom.getGroupId();
                                if (groupId == null) {
                                    if (pom.getParent() != null) {
                                        groupId = pom.getParent().getGroupId();
                                    }
                                    if (groupId == null) {
                                        throw new IllegalStateException("Failed to determine groupId for " + jar);
                                    }
                                }
                                artifactId = pom.getArtifactId();
                                if (artifactId == null) {
                                    throw new IllegalStateException("Failed to determine artifactId for " + jar);
                                }
                                version = pom.getVersion();
                                if (version == null) {
                                    if (pom.getParent() != null) {
                                        version = pom.getParent().getVersion();
                                    }
                                    if (version == null) {
                                        throw new IllegalStateException("Failed to determine version for " + jar);
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (AppModelResolverException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        if (groupId == null || artifactId == null || version == null) {
            final Path mavenDir = metaInf.resolve("maven");
            if (Files.exists(mavenDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(mavenDir)) {
                    final Iterator<Path> i = stream.iterator();
                    loop: while (i.hasNext()) {
                        Path p = i.next();
                        if (!Files.isDirectory(p)) {
                            continue;
                        }
                        try (DirectoryStream<Path> stream2 = Files.newDirectoryStream(p)) {
                            for (Path a : stream2) {
                                final Path pomProps = a.resolve("pom.properties");
                                if (Files.exists(pomProps)) {
                                    try (InputStream is = Files.newInputStream(pomProps)) {
                                        props.load(is);
                                    }
                                    groupId = props.getProperty("groupId");
                                    artifactId = props.getProperty("artifactId");
                                    version = props.getProperty("version");
                                    break loop;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (groupId == null || artifactId == null || version == null) {
            log("FAILED TO DETERMINE MVN COORDS for " + jar + " / " + metaInf);
            return;
        }

        final boolean newCpDep = cpDeps.add(new AppArtifactKey(groupId, artifactId));

        final Path descrPath = metaInf.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME);
        if (Files.exists(descrPath)) {
            props.clear();
            try (InputStream is = Files.newInputStream(descrPath)) {
                props.load(is);
            }
            final String deployment = props
                    .getProperty(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT);
            if (deployment != null) {
                final AppArtifactCoords coords = AppArtifactCoords.fromString(deployment);
                //                                    deps.add(new Dependency(
                //                                            new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(),
                //                                                    coords.getClassifier(), coords.getType(), coords.getVersion()),
                //                                            "compile"));
                org.apache.maven.model.Dependency deploymentDep = new org.apache.maven.model.Dependency();
                deploymentDep.setGroupId(coords.getGroupId());
                deploymentDep.setArtifactId(coords.getArtifactId());
                deploymentDep.setVersion(coords.getVersion());
                model.addDependency(deploymentDep);
            }
        }

        if (newCpDep) {
            DependencyManagement dm = model.getDependencyManagement();
            if (dm == null) {
                dm = new DependencyManagement();
                model.setDependencyManagement(dm);
            }
            org.apache.maven.model.Dependency modelDep = new org.apache.maven.model.Dependency();
            modelDep.setGroupId(groupId);
            modelDep.setArtifactId(artifactId);
            modelDep.setVersion(version);
            dm.addDependency(modelDep);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        RestAssuredURLManager.clearURL();
        TestScopeManager.setup();
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        RestAssuredURLManager.setURL();
        TestScopeManager.tearDown();
    }

    @Override
    public Object createTestInstance(TestInstanceFactoryContext factoryContext, ExtensionContext extensionContext)
            throws TestInstantiationException {
        if (failedBoot) {
            try {
                return extensionContext.getRequiredTestClass().newInstance();
            } catch (Exception e) {
                throw new TestInstantiationException("Boot failed", e);
            }
        }
        ExtensionContext root = extensionContext.getRoot();
        ExtensionContext.Store store = root.getStore(ExtensionContext.Namespace.GLOBAL);
        ExtensionState state = store.get(ExtensionState.class.getName(), ExtensionState.class);
        PropertyTestUtil.setLogFileProperty();
        boolean substrateTest = extensionContext.getRequiredTestClass().isAnnotationPresent(SubstrateTest.class);
        if (state == null) {
            TestResourceManager testResourceManager = new TestResourceManager(extensionContext.getRequiredTestClass());
            try {
                Map<String, String> systemProps = testResourceManager.start();

                if (substrateTest) {
                    NativeImageLauncher launcher = new NativeImageLauncher(extensionContext.getRequiredTestClass());
                    launcher.addSystemProperties(systemProps);
                    try {
                        launcher.start();
                    } catch (IOException e) {
                        throw new JUnitException("Quarkus native image start failed, original cause: " + e);
                    }
                    state = new ExtensionState(testResourceManager, launcher, true);
                } else {
                    try (BufferedWriter writer = Files
                            .newBufferedWriter(Paths.get(System.getProperty("user.dir")).resolve("quarkus-test-ext.log"))) {
                        this.writer = writer;
                        state = doJavaStart(extensionContext, testResourceManager);
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                store.put(ExtensionState.class.getName(), state);

            } catch (RuntimeException e) {
                testResourceManager.stop();
                failedBoot = true;
                throw e;
            }
        } else {
            if (substrateTest != state.isSubstrate()) {
                throw new RuntimeException(
                        "Attempted to mix @SubstrateTest and JVM mode tests in the same test run. This is not allowed.");
            }
        }

        try {
            Constructor<?> ctor = factoryContext.getTestClass().getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            TestHTTPResourceManager.inject(instance);
            TestInjectionManager.inject(instance);
            return instance;
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new TestInstantiationException("Failed to create test instance", e);
        }
    }

    private static ClassLoader setCCL(ClassLoader cl) {
        final Thread thread = Thread.currentThread();
        final ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(cl);
        return original;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (failedBoot) {
            throw new TestAbortedException("Not running test as boot failed");
        }
    }

    class ExtensionState implements ExtensionContext.Store.CloseableResource {

        private final TestResourceManager testResourceManager;
        private final Closeable resource;
        private final boolean substrate;

        ExtensionState(TestResourceManager testResourceManager, Closeable resource, boolean substrate) {
            this.testResourceManager = testResourceManager;
            this.resource = resource;
            this.substrate = substrate;
        }

        @Override
        public void close() throws Throwable {
            testResourceManager.stop();
            try {
                resource.close();
            } finally {
                if (QuarkusTestExtension.this.originalCl != null) {
                    setCCL(QuarkusTestExtension.this.originalCl);
                }
            }
            if (appCl != null) {
                appCl.close();
            }
        }

        public boolean isSubstrate() {
            return substrate;
        }
    }

    static class DeleteRunnable implements Runnable {
        final Path path;

        DeleteRunnable(Path path) {
            this.path = path;
        }

        @Override
        public void run() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
