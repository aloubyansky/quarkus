package io.quarkus.maven;

import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;

import java.util.List;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "test-setup")
public class TestSetupLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    /**
     * Invoked after all MavenProject instances have been created.
     *
     * This callback is intended to allow extensions to manipulate MavenProjects
     * before they are sorted and actual build execution starts.
     */
    public void afterProjectsRead(MavenSession session)
            throws MavenExecutionException {
        System.out.println("PROJECTS READ");
        /* @formatter:off

        System.out.println("Current project: " + session.getCurrentProject().getArtifact());

        final List<Plugin> buildPlugins = session.getCurrentProject().getBuildPlugins();
        Plugin quarkusPlugin = null;
        Plugin surefirePlugin = null;
        for (Plugin plugin : buildPlugins) {
            if (plugin.getArtifactId().equals("quarkus-maven-plugin")
                    && plugin.getGroupId().equals("io.quarkus")) {
                quarkusPlugin = plugin;
            } else if (plugin.getArtifactId().equals("maven-surefire-plugin")
                    && plugin.getGroupId().equals("org.apache.maven.plugins")) {
                surefirePlugin = plugin;
            }
        }
        if (quarkusPlugin == null) {
            System.out.println("Failed to locate io.quarkus:quarkus-maven-plugin in the project");
            return;
        }
        PluginExecution testSetup = null;
        for (PluginExecution ex : quarkusPlugin.getExecutions()) {
            if (ex.getGoals().contains("it-setup")) {
                testSetup = ex;
            }
        }
        if (testSetup == null) {
            System.out.println("Failed to locate it-setup goal");
            return;
        }
        if (testSetup.getConfiguration() == null) {
            System.out.println("Quarkus it-setup is missing configuration");
            return;
        }
        System.out.println("Quarkus it-setup config: " + testSetup.getConfiguration());
        Xpp3Dom config = (Xpp3Dom) testSetup.getConfiguration();
        Xpp3Dom testAppElement = config.getChild("testApp");
        if (testAppElement == null) {
            System.out.println("Quarkus it-setup is missing testApp parameter");
            return;
        }
        System.out.println("testApp: " + testAppElement.getValue());

        final String[] coords = testAppElement.getValue().split(":");
        final Artifact appArtifact = new DefaultArtifact(coords[0], coords[1], null, "jar", coords[2]);

        final MavenArtifactResolver mvn;
        try {
            final RepositorySystem repoSystem = session.getContainer().lookup(RepositorySystem.class);
            mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(session.getRepositorySession())
                    .setRemoteRepositories(session.getCurrentProject().getRemoteProjectRepositories())
                    .build();
        } catch (Exception e) {
            throw new MavenExecutionException("Failed to init Maven artifact resolver", e);
        }

        final Artifact appTestArtifact = new DefaultArtifact(coords[0], coords[1], "tests", "jar", coords[2]);
        final File appTestFile;
        try {
            appTestFile = mvn.resolve(appTestArtifact).getArtifact().getFile();
        } catch (AppModelResolverException e) {
            throw new MavenExecutionException("Failed to resolve " + appTestArtifact, e);
        }
        System.out.println("Test file: " + appTestFile);

        // Collect current managed deps
        final List<org.apache.maven.model.Dependency> projectManagedDep = session.getCurrentProject().getDependencyManagement()
                .getDependencies();
        final List<Dependency> managedDeps = new ArrayList<>(projectManagedDep.size());
        for (org.apache.maven.model.Dependency d : projectManagedDep) {
            managedDeps.add(new Dependency(
                    new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion()),
                    d.getScope()));
        }

        List<Element> additionalCp = new ArrayList<>();
        try {
            DependencyResult result = mvn.resolveDependencies(appArtifact, "bla");

            for (DependencyNode node : result.getRoot().getChildren()) {
                collectCp(mvn, node, additionalCp, 0);
            }
        } catch (Exception e) {
            throw new MavenExecutionException("Failed to process test deps", e);
        }

        Xpp3Dom surefireConfig = configuration(
                element(name("skipTests"), "false"),

                element(name("dependenciesToScan"),
                        element(name("dependency"), appArtifact.getGroupId() + ":" + appArtifact.getArtifactId())),

                element(name("additionalClasspathElements"), additionalCp.toArray(new Element[additionalCp.size()])));

        Xpp3Dom surefireOriginal = (Xpp3Dom) surefirePlugin.getConfiguration();
        System.out.println(surefireOriginal.getChild("additionaClasspathElements"));
        Xpp3Dom c = configuration(
                element(name("additionalClasspathElements"), additionalCp.toArray(new Element[additionalCp.size()])));
        surefireOriginal.addChild(c.getChild("additionalClasspathElements"));
@formatter:off */
    }

    /**
     * Invoked after MavenSession instance has been created.
     *
     * This callback is intended to allow extensions to inject execution properties,
     * activate profiles and perform similar tasks that affect MavenProject
     * instance construction.
     */
    // TODO This is too early for build extensions, so maybe just remove it?
    public void afterSessionStart(MavenSession session)
            throws MavenExecutionException {
        System.out.println("SESSION STARTED");
    }

    /**
     * Invoked after all projects were built.
     *
     * This callback is intended to allow extensions to perform cleanup of any
     * allocated external resources after the build. It is invoked on best-effort
     * basis and may be missed due to an Error or RuntimeException in Maven core
     * code.
     *
     * @since 3.2.1, MNG-5389
     */
    public void afterSessionEnd(MavenSession session)
            throws MavenExecutionException {
        System.out.println("SESSION ENDED");
    }

    private void collectCp(MavenArtifactResolver mvn, DependencyNode node, List<Element> cp, int depth)
            throws MojoExecutionException {
        final Dependency dep = node.getDependency();
        if (dep == null) {
            return;
        }
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < depth; ++i) {
            buf.append("  ");
        }
        buf.append(dep);
        System.out.println(buf);

        final String path;
        try {
            path = mvn.resolve(node.getArtifact()).getArtifact().getFile().getAbsolutePath();
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to resolve " + node.getArtifact(), e);
        }
        cp.add(element(name("additionalClasspathElement"), path));
        //getLog().info("Additional CP: " + path);

        final List<DependencyNode> children = node.getChildren();
        if (!children.isEmpty()) {
            for (DependencyNode child : children) {
                collectCp(mvn, child, cp, depth + 1);
            }
        }
    }
}
