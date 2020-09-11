package io.quarkus.maven;

public class ProdBuildTreeMojoTest extends TreeMojoTestBase {
    @Override
    protected String mode() {
        return "prod";
    }
}
