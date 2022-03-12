package io.quarkus.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

@Mojo(name = "prepare-test", defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class PrepareTestMojo extends QuarkusBootstrapMojo {

    @Override
    protected boolean beforeExecute() throws MojoExecutionException, MojoFailureException {
        return true;
    }

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        /* @formatter:off
        final LocalWorkspace workspace = bootstrapProvider.bootstrapper(this).artifactResolver(this, LaunchMode.TEST)
                .getMavenContext().getWorkspace();
        if (workspace != null) {
            Path workspaceDat = Path.of("target/quarkus/bootstrap/workspace.dat");
            try {
                Files.createDirectories(workspaceDat.getParent());
                try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(workspaceDat))) {
                    out.writeObject(workspace);
                }
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        final Path serializedModelPath = BootstrapUtils.resolveSerializedAppModelPath(Path.of("target"), true);
        if (Files.exists(serializedModelPath)) {
            return;
        }
        try {
            BootstrapUtils.serializeAppModel(bootstrapProvider.resolveApplicationModel(this, LaunchMode.TEST),
                    serializedModelPath);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to prepare the Quarkus application model", e);
        }
        @formatter:on */
    }
}
