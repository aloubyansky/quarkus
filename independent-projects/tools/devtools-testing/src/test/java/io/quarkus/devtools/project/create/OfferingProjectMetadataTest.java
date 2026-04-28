package io.quarkus.devtools.project.create;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.devtools.testing.registry.client.TestRegistryClientBuilder;
import io.quarkus.maven.dependency.ArtifactCoords;

public class OfferingProjectMetadataTest extends MultiplePlatformBomsTestBase {

    private static final String DOWNSTREAM_PLATFORM_KEY = "io.downstream.platform";
    private static final String UPSTREAM_PLATFORM_KEY = "io.upstream.platform";

    @BeforeAll
    public static void setup() throws Exception {
        TestRegistryClientBuilder.newInstance()
                //.debug()
                .baseDir(configDir())
                .newRegistry("downstream.registry.test")
                .recognizedQuarkusVersions("*downstream")
                .setOffering("offering-a")
                .newPlatform(DOWNSTREAM_PLATFORM_KEY)
                .newStream("1.1")
                .newRelease("1.1.1.downstream")
                .quarkusVersion("1.1.1.downstream")
                .upstreamQuarkusVersion("1.1.1")
                .addCoreMember()
                .addOfferingProjectProperty("offering-a", "maven-plugin-groupId", "io.downstream.platform")
                .addOfferingProjectMavenRepo("offering-a", "downstream-repo",
                        "https://downstream.example.com/maven")
                .addExtensionWithMetadata("io.quarkus", "quarkus-rest", "1.1.1.downstream",
                        Map.of("offering-a-support", List.of("supported")))
                .release()
                .stream().platform().registry()
                .clientBuilder()
                .newRegistry("upstream.registry.test")
                .newPlatform(UPSTREAM_PLATFORM_KEY)
                .newStream("1.1")
                .newRelease("1.1.1")
                .quarkusVersion("1.1.1")
                .addCoreMember()
                .addExtension("io.quarkus", "quarkus-rest", "1.1.1")
                .release()
                .registry()
                .clientBuilder()
                .build();

        enableRegistryClient();
    }

    @Override
    protected String getMainPlatformKey() {
        return DOWNSTREAM_PLATFORM_KEY;
    }

    @Test
    public void offeringProjectMetadataMergedIntoCatalog() throws Exception {
        final Path projectDir = newProjectDir("offering-project-metadata");
        createProject(projectDir, List.of("quarkus-rest"));

        final Model model = ModelUtils.readModel(projectDir.resolve("pom.xml"));

        assertThat(model.getProperties().getProperty("quarkus.platform.group-id"))
                .isEqualTo(DOWNSTREAM_PLATFORM_KEY);

        assertThat(model.getRepositories()).anySatisfy(repo -> {
            assertThat(repo.getId()).isEqualTo("downstream-repo");
            assertThat(repo.getUrl()).isEqualTo("https://downstream.example.com/maven");
        });
    }

    @Test
    public void offeringPropertyOverridesDefault() throws Exception {
        final Path projectDir = newProjectDir("offering-property-override");
        createProject(projectDir, List.of("quarkus-rest"));

        assertModel(projectDir,
                List.of(mainPlatformBom()),
                List.of(ArtifactCoords.jar("io.quarkus", "quarkus-rest", null)),
                "1.1.1.downstream");
    }
}
