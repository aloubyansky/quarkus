package io.quarkus.maven;

public class DevBuildTreeMojoTest extends TreeMojoTestBase {
    @Override
    protected String mode() {
        return "dev";
    }
}
