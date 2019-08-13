package io.quarkus.maven;

import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name = "parent-test", defaultPhase = LifecyclePhase.INSTALL)
public class TestMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        System.out.println("HELLO");

        List<Plugin> buildPlugins = project.getBuildPlugins();
        for (Plugin plugin : buildPlugins) {
            System.out.println(plugin.getId());
        }
    }

}
