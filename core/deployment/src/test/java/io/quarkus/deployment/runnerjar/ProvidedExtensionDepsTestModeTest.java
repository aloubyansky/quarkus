package io.quarkus.deployment.runnerjar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.aether.util.artifact.JavaScopes;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.TsArtifact;
import io.quarkus.bootstrap.resolver.TsDependency;
import io.quarkus.bootstrap.resolver.TsQuarkusExt;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyFlags;

public class ProvidedExtensionDepsTestModeTest extends BootstrapFromOriginalJarTestBase {

    @Override
    protected boolean isBootstrapForTestMode() {
        return true;
    }

    @Override
    protected TsArtifact composeApplication() {

        final TsArtifact extADep = TsArtifact.jar("ext-a-dep");
        addToExpectedLib(extADep);

        final TsArtifact depC1 = TsArtifact.jar("dep-c");
        extADep.addDependency(depC1);

        final TsArtifact extAProvidedDep = TsArtifact.jar("ext-a-provided-dep");

        final TsArtifact extADeploymentDep = TsArtifact.jar("ext-a-deployment-dep");
        final TsArtifact extAOptionalDeploymentDep = TsArtifact.jar("ext-a-provided-deployment-dep");

        final TsQuarkusExt extA = new TsQuarkusExt("ext-a");
        addToExpectedLib(extA.getRuntime());
        extA.getRuntime()
                .addDependency(extADep)
                .addDependency(new TsDependency(extAProvidedDep, "provided"));
        extA.getDeployment()
                .addDependency(extADeploymentDep)
                .addDependency(new TsDependency(extAOptionalDeploymentDep, "provided"));

        final TsQuarkusExt extB = new TsQuarkusExt("ext-b");
        this.install(extB);
        addToExpectedLib(extB.getRuntime()); // test mode enabled

        final TsArtifact directProvidedDep = TsArtifact.jar("direct-provided-dep");
        addToExpectedLib(directProvidedDep); // test mode enabled

        final TsArtifact depC2 = TsArtifact.jar("dep-c", "2");
        addToExpectedLib(depC2); // in this case provided version will override the compile one
        directProvidedDep.addDependency(depC2);

        final TsArtifact transitiveProvidedDep = TsArtifact.jar("transitive-provided-dep");
        directProvidedDep.addDependency(transitiveProvidedDep);
        addToExpectedLib(transitiveProvidedDep); // test mode enabled

        return TsArtifact.jar("app")
                .addManagedDependency(platformDescriptor())
                .addManagedDependency(platformProperties())
                .addDependency(new TsDependency(directProvidedDep, "provided"))
                .addDependency(extA)
                .addDependency(extB, "provided");
    }

    @Override
    protected void assertAppModel(ApplicationModel model) throws Exception {

        final Set<Dependency> compileOnly = new HashSet<>();
        compileOnly.add(new ArtifactDependency(ArtifactCoords.jar("io.quarkus.bootstrap.test", "ext-b", "1"),
                JavaScopes.PROVIDED,
                DependencyFlags.RUNTIME_CP,
                DependencyFlags.DEPLOYMENT_CP,
                DependencyFlags.RUNTIME_EXTENSION_ARTIFACT,
                DependencyFlags.DIRECT,
                DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT,
                DependencyFlags.COMPILE_ONLY));
        compileOnly.add(new ArtifactDependency(ArtifactCoords.jar("io.quarkus.bootstrap.test", "direct-provided-dep", "1"),
                JavaScopes.PROVIDED,
                DependencyFlags.RUNTIME_CP,
                DependencyFlags.DEPLOYMENT_CP,
                DependencyFlags.DIRECT,
                DependencyFlags.COMPILE_ONLY));
        compileOnly.add(new ArtifactDependency(ArtifactCoords.jar("io.quarkus.bootstrap.test", "transitive-provided-dep", "1"),
                JavaScopes.PROVIDED,
                DependencyFlags.RUNTIME_CP,
                DependencyFlags.DEPLOYMENT_CP,
                DependencyFlags.COMPILE_ONLY));
        assertEquals(compileOnly, getDependenciesWithFlag(model, DependencyFlags.COMPILE_ONLY));

        Set<Dependency> expected = new HashSet<>();
        expected.add(new ArtifactDependency(ArtifactCoords.jar("io.quarkus.bootstrap.test", "ext-a-deployment", "1"),
                DependencyFlags.DEPLOYMENT_CP));
        expected.add(new ArtifactDependency(ArtifactCoords.jar("io.quarkus.bootstrap.test", "ext-a-deployment-dep", "1"),
                DependencyFlags.DEPLOYMENT_CP));
        expected.add(new ArtifactDependency(ArtifactCoords.jar("io.quarkus.bootstrap.test", "ext-b-deployment", "1"),
                JavaScopes.PROVIDED,
                DependencyFlags.DEPLOYMENT_CP));

        assertEquals(expected, getDeploymentOnlyDeps(model));
    }
}
