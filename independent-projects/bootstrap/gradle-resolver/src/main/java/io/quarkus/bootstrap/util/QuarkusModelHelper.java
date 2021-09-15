package io.quarkus.bootstrap.util;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.BootstrapGradleException;
import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.model.PathsCollection;
import io.quarkus.bootstrap.model.gradle.ApplicationModel;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.maven.dependency.Artifact;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.Dependency;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class QuarkusModelHelper {

    private QuarkusModelHelper() {

    }

    public final static String SERIALIZED_QUARKUS_MODEL = "quarkus-internal.serialized-quarkus-model.path";
    public final static String[] DEVMODE_REQUIRED_TASKS = new String[] { "classes" };
    public final static String[] TEST_REQUIRED_TASKS = new String[] { "classes", "testClasses" };
    public final static List<String> ENABLE_JAR_PACKAGING = Collections
            .singletonList("-Dorg.gradle.java.compile-classpath-packaging=true");

    public static void exportModel(ApplicationModel model, boolean test) throws AppModelResolverException, IOException {
        Path serializedModel = QuarkusModelHelper
                .serializeAppModel(model, test);
        System.setProperty(test ? BootstrapConstants.SERIALIZED_TEST_APP_MODEL : BootstrapConstants.SERIALIZED_APP_MODEL,
                serializedModel.toString());
    }

    public static Path serializeAppModel(ApplicationModel model, boolean test) throws AppModelResolverException, IOException {
        final Path serializedModel = File.createTempFile("quarkus-" + (test ? "test-" : "") + "app-model", ".dat").toPath();
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(serializedModel))) {
            out.writeObject(QuarkusModelHelper.convert(model));
        }
        return serializedModel;
    }

    public static Path serializeQuarkusModel(ApplicationModel model) throws IOException {
        final Path serializedModel = File.createTempFile("quarkus-model", ".dat").toPath();
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(serializedModel))) {
            out.writeObject(model);
        }
        return serializedModel;
    }

    public static ApplicationModel deserializeQuarkusModel(Path modelPath) throws BootstrapGradleException {
        if (Files.exists(modelPath)) {
            try (InputStream existing = Files.newInputStream(modelPath);
                    ObjectInputStream object = new ObjectInputStream(existing)) {
                ApplicationModel model = (ApplicationModel) object.readObject();
                IoUtils.recursiveDelete(modelPath);
                return model;
            } catch (IOException | ClassNotFoundException e) {
                throw new BootstrapGradleException("Failed to deserialize quarkus model", e);
            }
        }
        throw new BootstrapGradleException("Unable to locate quarkus model");
    }

    public static AppModel convert(ApplicationModel model) throws AppModelResolverException {

        AppModel.Builder appBuilder = new AppModel.Builder()
                .setAppArtifact(toAppArtifact(model.getAppArtifact()))
                .setPlatformImports(model.getPlatformImports());

        model.getDependencies().forEach(d -> appBuilder.addDependency(toAppDependency(d)));

        if (model.getExtensionCapabilities() == null || model.getExtensionCapabilities().isEmpty()) {
            appBuilder.setCapabilitiesContracts(Collections.emptyMap());
        } else {
            final Map<String, CapabilityContract> capabilitiesContracts = new HashMap<>(
                    model.getExtensionCapabilities().size());
            for (ExtensionCapabilities e : model.getExtensionCapabilities()) {
                capabilitiesContracts.put(e.getExtension(), new CapabilityContract(e.getExtension(),
                        new ArrayList<>(e.getProvidesCapabilities())));
            }
            appBuilder.setCapabilitiesContracts(capabilitiesContracts);
        }

        model.getLowerPriorityArtifacts().forEach(a -> appBuilder.addLesserPriorityArtifact(toAppArtifactKey(a)));
        model.getParentFirst().forEach(a -> appBuilder.addParentFirstArtifact(toAppArtifactKey(a)));
        model.getRunnerParentFirst().forEach(a -> appBuilder.addRunnerParentFirstArtifact(toAppArtifactKey(a)));

        if (model.getApplicationModule() != null) {
            final ArtifactKey key = model.getAppArtifact().getKey();
            appBuilder.addLocalProjectArtifact(new AppArtifactKey(key.getGroupId(), key.getArtifactId()));
        }
        model.getProjectModules().forEach(
                p -> appBuilder.addLocalProjectArtifact(new AppArtifactKey(p.getId().getGroupId(), p.getId().getArtifactId())));

        return appBuilder.build();
    }

    private static AppArtifactKey toAppArtifactKey(ArtifactKey a) {
        return new AppArtifactKey(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getType());
    }

    public static AppDependency toAppDependency(Dependency dependency, int... flags) {
        int allFlags = dependency.getFlags();
        for (int f : flags) {
            allFlags |= f;
        }
        return new AppDependency(toAppArtifact(dependency.getArtifact()), "runtime", allFlags);
    }

    private static AppArtifact toAppArtifact(Artifact artifact) {
        final ArtifactCoords coords = artifact.getCoords();
        AppArtifact appArtifact = new AppArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(),
                coords.getType(), coords.getVersion());
        appArtifact.setPaths(PathsCollection.from(artifact.getResolvedPaths()));
        return appArtifact;
    }

    public static PathsCollection toPathsCollection(Collection<File> files) {
        PathsCollection.Builder paths = PathsCollection.builder();
        for (File f : files) {
            paths.add(f.toPath());
        }
        return paths.build();
    }

    public static Properties resolveDescriptor(final Path path) {
        final Properties rtProps;
        if (!Files.exists(path)) {
            // not a platform artifact
            return null;
        }
        rtProps = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            rtProps.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load extension description " + path, e);
        }
        return rtProps;
    }

    static AppDependency alignVersion(AppDependency dependency, Map<AppArtifactKey, AppDependency> versionMap) {
        AppArtifactKey appKey = new AppArtifactKey(dependency.getArtifact().getGroupId(),
                dependency.getArtifact().getArtifactId());
        if (versionMap.containsKey(appKey)) {
            return versionMap.get(appKey);
        }
        return dependency;
    }

}
