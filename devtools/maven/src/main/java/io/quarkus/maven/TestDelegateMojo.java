package io.quarkus.maven;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyResult;
import org.twdata.maven.mojoexecutor.MavenCompatibilityHelper;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;

@Mojo(name = "it-setup", defaultPhase = LifecyclePhase.TEST)
public class TestDelegateMojo extends AbstractMojo {

    @Component
    private MavenProject mavenProject;

    @Component
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager pluginManager;

    @Component
    private Invoker invoker;

    @Parameter(required = true)
    private String testApp;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    //@Component(hint = "test-setup")
    //private TestSetupLifecycleParticipant testSetupExt;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        getLog().info("TEST SETUP " + testApp);

        final String[] coords = testApp.split(":");
        final Artifact appArtifact = new DefaultArtifact(coords[0], coords[1], null, "jar", coords[2]);

        final MavenArtifactResolver mvn;
        try {
            mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(repos)
                    .build();
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to init Maven artifact resolver", e);
        }

        final Artifact appTestArtifact = new DefaultArtifact(coords[0], coords[1], "tests", "jar", coords[2]);
        final File appTestFile;
        try {
            appTestFile = mvn.resolve(appTestArtifact).getArtifact().getFile();
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to resolve " + appTestArtifact, e);
        }

        getLog().info("Tests: " + appTestFile);

        // Collect current managed deps
        final List<org.apache.maven.model.Dependency> projectManagedDep = mavenProject.getDependencyManagement()
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
            throw new MojoExecutionException("Failed to process test deps", e);
        }

        final List<Plugin> buildPlugins = mavenProject.getBuildPlugins();
        Plugin surefirePlugin = null;
        for (Plugin plugin : buildPlugins) {
            if (plugin.getArtifactId().equals("maven-surefire-plugin")
                    && plugin.getGroupId().equals("org.apache.maven.plugins")) {
                surefirePlugin = plugin;
            }
        }
        if (surefirePlugin == null) {
            throw new MojoExecutionException("Failed to locate surefire plugin for the project");
        }

        Xpp3Dom config = configuration(
                element(name("skipTests"), "false"),

                element(name("dependenciesToScan"),
                        element(name("dependency"), appArtifact.getGroupId() + ":" + appArtifact.getArtifactId())),

                element(name("additionalClasspathElements"), additionalCp.toArray(new Element[additionalCp.size()])));

        final ExecutionEnvironment env = executionEnvironment(
                mavenProject,
                mavenSession,
                pluginManager);
        PluginDescriptor pluginDescriptor;
        try {
            //pluginDescriptor = pluginManager.loadPlugin(surefirePlugin,
            //        mavenSession.getCurrentProject().getRemotePluginRepositories(), repoSession);
            pluginDescriptor = MavenCompatibilityHelper.loadPluginDescriptor(surefirePlugin, env, mavenSession);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new MojoExecutionException("Failed to load plugin descriptor");
        }

        MojoDescriptor mojoDescriptor = pluginDescriptor.getMojo("test");
        if (mojoDescriptor == null) {
            throw new MojoExecutionException("Could not find goal '" + "test#1" + "' in plugin "
                    + surefirePlugin.getGroupId() + ":"
                    + surefirePlugin.getArtifactId() + ":"
                    + surefirePlugin.getVersion());
        }

        /*
        @formatter:off
        ComponentConfigurator basic;
        try {
            basic = mavenSession.getContainer().lookup(ComponentConfigurator.class, "basic");
        } catch (ComponentLookupException e1) {
            throw new MojoExecutionException("Failed to resolve the basic configurator", e1);
        }
        mavenSession.getContainer().addComponent(new ComponentConfigurator() {

            @Override
            public void configureComponent(Object component, PlexusConfiguration configuration, ClassRealm realm)
                    throws ComponentConfigurationException {
                System.out.println("CONFIGURE 1");
            }

            @Override
            public void configureComponent(Object component, PlexusConfiguration configuration, ExpressionEvaluator evaluator,
                    ClassRealm realm) throws ComponentConfigurationException {
                System.out.println("CONFIGURE 2");
            }

            @Override
            public void configureComponent(Object component, PlexusConfiguration configuration, ExpressionEvaluator evaluator,
                    ClassRealm realm, ConfigurationListener listener) throws ComponentConfigurationException {
                System.out.println("CONFIGURE 3 " + component.getClass());
                basic.configureComponent(component, configuration, evaluator, realm, listener);

                try {
                    System.out.println("Session " + component.getClass().getMethod("getSession").invoke(component));
                } catch (IllegalAccessException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                } catch (IllegalArgumentException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                } catch (InvocationTargetException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                } catch (NoSuchMethodException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                } catch (SecurityException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }

                try {
                    component.getClass().getMethod("setSession", MavenSession.class).invoke(component, mavenSession);
                    listener.notifyFieldChangeUsingSetter("session", mavenSession, component);

                    component.getClass().getMethod("setProject", MavenProject.class).invoke(component, mavenProject);
                    listener.notifyFieldChangeUsingSetter("project", mavenProject, component);

                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                //surefire.setSession(mavenSession);
                //surefire.setProject(mavenProject);
            }
        }, ComponentConfigurator.class, "quarkus-platform");

        mojoDescriptor.setComponentConfigurator("quarkus-platform");
        System.out.println(
                "HINT " + mojoDescriptor.getRoleHint() + " " + mojoDescriptor.getRole());
@formatter:on
*/

        //MojoExecution exe = new MojoExecution(mojoDescriptor, "quarkus-platform-test");
        //MojoExecution exe = new MojoExecution(mojoDescriptor, (Xpp3Dom) surefirePlugin.getConfiguration());
        //MojoExecution exe = new MojoExecution(mojoDescriptor, config);

        /* @formatter:off
        try {
            pluginManager.executeMojo(mavenSession, exe);
        } catch (PluginConfigurationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (PluginManagerException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        @formatter:on
        */
        executeMojo(
                surefirePlugin,
                //goal("test#quarkus-platform-test"),
                goal("test"),
                config,
                env);

        getLog().info("DONE");
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
