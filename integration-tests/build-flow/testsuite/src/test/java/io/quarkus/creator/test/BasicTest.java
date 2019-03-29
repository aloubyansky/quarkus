/**
 *
 */
package io.quarkus.creator.test;

import org.junit.Test;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.resolver.PropsBuilder;
import io.quarkus.bootstrap.resolver.TsArtifact;
import io.quarkus.bootstrap.resolver.TsJar;
import io.quarkus.bootstrap.resolver.TsResolverSetupCleanup;

public class BasicTest extends TsResolverSetupCleanup {

    @Test
    public void testMain() throws Exception {

        final String groupId = "io.quarkus.extension.test";
        final String artifactId = "test-artifact";
        final String version = "1.0";

        final TsArtifact rtArtifact = TsArtifact.jar(groupId, artifactId, version);
        final TsArtifact dpArtifact = TsArtifact.jar(groupId, artifactId + "-deployment", version);
        final TsJar rtJar = newJar().addFile(
                PropsBuilder.init(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT, dpArtifact.toString()).build(),
                BootstrapConstants.QUARKUS, BootstrapConstants.DESCRIPTOR_FILE_NAME);
        install(rtArtifact, rtJar.getPath());
    }
}
