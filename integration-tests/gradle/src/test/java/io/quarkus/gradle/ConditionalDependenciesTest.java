package io.quarkus.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * This class uses test order because all tests depend on extension publication which can be done once.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConditionalDependenciesTest extends QuarkusGradleWrapperTestBase {

    @Test
    @Order(1)
    public void publishTestExtensions() throws IOException, InterruptedException, URISyntaxException {
        File dependencyProject = getProjectDir("conditional-dependencies");
        runGradleWrapper(dependencyProject, ":ext-quarkus-a:runtime:publishToMavenLocal",
                ":ext-quarkus-a:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-b:runtime:publishToMavenLocal",
                ":ext-quarkus-b:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-c:runtime:publishToMavenLocal",
                ":ext-quarkus-c:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-d:runtime:publishToMavenLocal",
                ":ext-quarkus-d:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-e:runtime:publishToMavenLocal",
                ":ext-quarkus-e:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-f:runtime:publishToMavenLocal",
                ":ext-quarkus-f:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-g:runtime:publishToMavenLocal",
                ":ext-quarkus-g:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-h:runtime:publishToMavenLocal",
                ":ext-quarkus-h:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-i:runtime:publishToMavenLocal",
                ":ext-quarkus-i:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-j:runtime:publishToMavenLocal",
                ":ext-quarkus-j:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-k:runtime:publishToMavenLocal",
                ":ext-quarkus-k:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-l:runtime:publishToMavenLocal",
                ":ext-quarkus-l:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-m:runtime:publishToMavenLocal",
                ":ext-quarkus-m:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-n:runtime:publishToMavenLocal",
                ":ext-quarkus-n:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-o:runtime:publishToMavenLocal",
                ":ext-quarkus-o:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-p:runtime:publishToMavenLocal",
                ":ext-quarkus-p:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-r:runtime:publishToMavenLocal",
                ":ext-quarkus-r:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-s:runtime:publishToMavenLocal",
                ":ext-quarkus-s:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-t:runtime:publishToMavenLocal",
                ":ext-quarkus-t:deployment:publishToMavenLocal");
        runGradleWrapper(dependencyProject, ":ext-quarkus-u:runtime:publishToMavenLocal",
                ":ext-quarkus-u:deployment:publishToMavenLocal");
    }

    @Test
    @Order(2)
    public void shouldImportConditionalDependency() throws IOException, URISyntaxException, InterruptedException {
        final File projectDir = getProjectDir("conditional-test-project");

        runGradleWrapper(projectDir, "clean", ":runner:quarkusBuild");

        final File buildDir = new File(projectDir, "runner" + File.separator + "build");
        final Path mainLib = buildDir.toPath().resolve("quarkus-app").resolve("lib").resolve("main");

        assertThat(mainLib.resolve("org.acme.ext-quarkus-a-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-b-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-c-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-e-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-d-1.0-SNAPSHOT.jar")).doesNotExist();
    }

    @Test
    @Order(3)
    public void shouldNotImportConditionalDependency() throws IOException, URISyntaxException, InterruptedException {
        final File projectDir = getProjectDir("conditional-test-project");

        runGradleWrapper(projectDir, "clean", ":runner-with-exclude:quarkusBuild");

        final File buildDir = new File(projectDir, "runner-with-exclude" + File.separator + "build");
        final Path mainLib = buildDir.toPath().resolve("quarkus-app").resolve("lib").resolve("main");

        assertThat(mainLib.resolve("org.acme.ext-quarkus-a-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-b-1.0-SNAPSHOT.jar")).doesNotExist();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-c-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-e-1.0-SNAPSHOT.jar")).doesNotExist();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-d-1.0-SNAPSHOT.jar")).doesNotExist();
    }

    @Test
    @Order(4)
    public void shouldNotFailIfConditionalDependencyIsExplicitlyDeclared()
            throws IOException, URISyntaxException, InterruptedException {
        final File projectDir = getProjectDir("conditional-test-project");

        runGradleWrapper(projectDir, "clean", ":runner-with-explicit-import:quarkusBuild");

        final File buildDir = new File(projectDir, "runner-with-explicit-import" + File.separator + "build");
        final Path mainLib = buildDir.toPath().resolve("quarkus-app").resolve("lib").resolve("main");

        assertThat(mainLib.resolve("org.acme.ext-quarkus-a-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-b-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-c-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-e-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-d-1.0-SNAPSHOT.jar")).doesNotExist();
    }

    @Test
    @Order(5)
    public void scenarioTwo() throws Exception {

        // F -> G -> H?(I,J) -> K -> T
        // L -> J -> P?(O)
        // M -> N?(K) -> I -> O?(H)
        // M -> R?(I) -> S?(T) -> U

        final File projectDir = getProjectDir("conditional-test-project");

        runGradleWrapper(projectDir, "clean", ":scenario-two:quarkusBuild", "-Dquarkus.package.type=mutable-jar");

        final File buildDir = new File(projectDir, "scenario-two" + File.separator + "build");
        final Path mainLib = buildDir.toPath().resolve("quarkus-app").resolve("lib").resolve("main");

        assertThat(mainLib.resolve("org.acme.ext-quarkus-f-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-g-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-h-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-i-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-j-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-k-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-l-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-m-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-n-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-o-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-p-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-r-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-s-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-t-1.0-SNAPSHOT.jar")).exists();
        assertThat(mainLib.resolve("org.acme.ext-quarkus-u-1.0-SNAPSHOT.jar")).exists();

        final Path deploymentLib = buildDir.toPath().resolve("quarkus-app").resolve("lib").resolve("deployment");
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-f-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-g-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-h-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-i-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-j-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-k-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-l-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-m-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-n-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-o-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-p-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-r-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-s-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-t-deployment-1.0-SNAPSHOT.jar")).exists();
        assertThat(deploymentLib.resolve("org.acme.ext-quarkus-u-deployment-1.0-SNAPSHOT.jar")).exists();
    }
}
