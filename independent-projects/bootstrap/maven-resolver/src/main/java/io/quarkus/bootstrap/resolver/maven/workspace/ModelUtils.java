package io.quarkus.bootstrap.resolver.maven.workspace;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import io.quarkus.bootstrap.util.PropertyUtils;
import io.quarkus.fs.util.ZipUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;

/**
 * @author Alexey Loubyansky
 */
public class ModelUtils {

    /**
     * Matches specific properties that are allowed to be used in a version as per Maven spec.
     *
     * @see <a href="https://maven.apache.org/maven-ci-friendly.html">Maven CI Friendly Versions (maven.apache.org)</a>
     */
    private static Pattern unresolvedVersionPattern;

    private static Pattern getUnresolvedVersionPattern() {
        return unresolvedVersionPattern == null
                ? unresolvedVersionPattern = Pattern
                        .compile(Pattern.quote("${") + "(revision|sha1|changelist)" + Pattern.quote("}"))
                : unresolvedVersionPattern;
    }

    private static final String STATE_ARTIFACT_INITIAL_VERSION = "1";

    /**
     * Returns the provisioning state artifact for the given application artifact
     *
     * @param appArtifact application artifact
     * @return provisioning state artifact
     */
    public static ArtifactCoords getStateArtifact(ArtifactCoords appArtifact) {
        return new GACTV(appArtifact.getGroupId() + ".quarkus.curate",
                appArtifact.getArtifactId(),
                "",
                "pom",
                STATE_ARTIFACT_INITIAL_VERSION);
    }

    public static ResolvedDependency resolveAppArtifact(Path appJar) throws IOException {
        try (FileSystem fs = ZipUtils.newFileSystem(appJar)) {
            final Path metaInfMaven = fs.getPath("META-INF", "maven");
            if (Files.exists(metaInfMaven)) {
                try (DirectoryStream<Path> groupIds = Files.newDirectoryStream(metaInfMaven)) {
                    for (Path groupIdPath : groupIds) {
                        if (!Files.isDirectory(groupIdPath)) {
                            continue;
                        }
                        try (DirectoryStream<Path> artifactIds = Files.newDirectoryStream(groupIdPath)) {
                            for (Path artifactIdPath : artifactIds) {
                                if (!Files.isDirectory(artifactIdPath)) {
                                    continue;
                                }
                                final Path propsPath = artifactIdPath.resolve("pom.properties");
                                if (Files.exists(propsPath)) {
                                    final Properties props = loadPomProps(appJar, artifactIdPath);
                                    return ResolvedDependencyBuilder.newInstance()
                                            .setGroupId(props.getProperty("groupId"))
                                            .setArtifactId(props.getProperty("artifactId"))
                                            .setVersion(props.getProperty("version"))
                                            .setResolvedPath(appJar)
                                            .build();
                                }
                            }
                        }
                    }
                }
            }
            return ResolvedDependencyBuilder.newInstance()
                    .setGroupId("unknown")
                    .setArtifactId("unknown")
                    .setVersion("1.0-SNAPSHOT")
                    .setResolvedPath(appJar)
                    .build();
        }
    }

    static Model readAppModel(Path appJar) throws IOException {
        try (FileSystem fs = ZipUtils.newFileSystem(appJar)) {
            final Path metaInfMaven = fs.getPath("META-INF", "maven");
            if (Files.exists(metaInfMaven)) {
                try (DirectoryStream<Path> groupIds = Files.newDirectoryStream(metaInfMaven)) {
                    for (Path groupIdPath : groupIds) {
                        if (!Files.isDirectory(groupIdPath)) {
                            continue;
                        }
                        try (DirectoryStream<Path> artifactIds = Files.newDirectoryStream(groupIdPath)) {
                            for (Path artifactIdPath : artifactIds) {
                                if (!Files.isDirectory(artifactIdPath)) {
                                    continue;
                                }
                                final Path pomXml = artifactIdPath.resolve("pom.xml");
                                if (Files.exists(pomXml)) {
                                    return readModel(pomXml);
                                }
                            }
                        }
                    }
                }
            }
            throw new IOException("Failed to located META-INF/maven/<groupId>/<artifactId>/pom.xml in " + appJar);
        }
    }

    public static String getGroupId(Model model) {
        String groupId = model.getGroupId();
        if (groupId != null) {
            return groupId;
        }
        final Parent parent = model.getParent();
        if (parent != null) {
            groupId = parent.getGroupId();
            if (groupId != null) {
                return groupId;
            }
        }
        throw new IllegalStateException("Failed to determine groupId for project model");
    }

    /**
     * Returns the raw version of the model. If the model does not include
     * the version directly, it will return the version of the parent.
     * The version is raw in a sense if it's a property expression, the
     * expression will not be resolved.
     *
     * @param model POM
     * @return raw model
     */
    public static String getRawVersion(Model model) {
        String version = model.getVersion();
        if (version != null) {
            return version;
        }
        final Parent parent = model.getParent();
        if (parent != null) {
            version = parent.getVersion();
            if (version != null) {
                return version;
            }
        }
        throw new IllegalStateException("Failed to determine version for project model");
    }

    public static String getVersion(Model model) {
        final String rawVersion = getRawVersion(model);
        return isUnresolvedVersion(rawVersion) ? resolveVersion(rawVersion, model) : rawVersion;
    }

    public static boolean isUnresolvedVersion(String version) {
        return getUnresolvedVersionPattern().matcher(version).find();
    }

    public static String resolveVersion(String rawVersion, Model rawModel) {
        final Map<String, String> props = new HashMap<>(rawModel.getProperties().size() + System.getProperties().size());
        putAll(props, rawModel.getProperties());
        putAll(props, System.getProperties());

        Matcher matcher = getUnresolvedVersionPattern().matcher(rawVersion);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            final String resolved = props.get(matcher.group(1));
            if (resolved == null) {
                return null;
            }
            if (resolved.contains("${")) {
                throw new IllegalArgumentException("Illegal placeholder in Maven CI friendly version property \""
                        + matcher.group(1) + "\": " + resolved
                        + "\n\tPlease consult https://maven.apache.org/maven-ci-friendly.html#single-project-setup");
            }
            matcher.appendReplacement(sb, resolved);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static void putAll(Map<String, String> map, Properties props) {
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            map.put(e.getKey().toString(), e.getValue().toString());
        }
    }

    /**
     * If the model contains properties, this method overrides those that appear to be
     * defined as system properties.
     */
    public static Model applySystemProperties(Model model) {
        final Properties props = model.getProperties();
        for (Map.Entry<Object, Object> prop : model.getProperties().entrySet()) {
            final String systemValue = PropertyUtils.getProperty(prop.getKey().toString());
            if (systemValue != null) {
                props.put(prop.getKey(), systemValue);
            }
        }
        return model;
    }

    private static Properties loadPomProps(Path appJar, Path artifactIdPath) throws IOException {
        final Path propsPath = artifactIdPath.resolve("pom.properties");
        if (!Files.exists(propsPath)) {
            throw new IOException("Failed to located META-INF/maven/<groupId>/<artifactId>/pom.properties in " + appJar);
        }
        final Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(propsPath)) {
            props.load(reader);
        }
        return props;
    }

    public static Model readModel(final Path pomXml) throws IOException {
        Model model = readModel(Files.newInputStream(pomXml));
        model.setPomFile(pomXml.toFile());
        return model;
    }

    public static Model readModel(InputStream stream) throws IOException {
        try (InputStream is = stream) {
            return new MavenXpp3Reader().read(stream);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse POM", e);
        }
    }

    public static void persistModel(Path pomFile, Model model) throws IOException {
        final MavenXpp3Writer xpp3Writer = new MavenXpp3Writer();
        try (BufferedWriter pomFileWriter = Files.newBufferedWriter(pomFile)) {
            xpp3Writer.write(pomFileWriter, model);
        }
    }
}
