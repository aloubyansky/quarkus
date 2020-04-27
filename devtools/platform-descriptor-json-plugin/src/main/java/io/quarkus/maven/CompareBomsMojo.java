package io.quarkus.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.MavenProject;

import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.maven.utilities.MojoUtils;

@Mojo(name = "compare-boms", defaultPhase = LifecyclePhase.INSTALL)
public class CompareBomsMojo extends AbstractMojo {

    @Component
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final File flattenedPom = new File(project.getBasedir(), ".flattened-pom.xml");
        if (!flattenedPom.exists()) {
            getLog().warn(flattenedPom + " does not exist");
            return;
        }
        final Model flattenedModel;
        try {
            flattenedModel = MojoUtils.readPom(flattenedPom);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to parse " + flattenedPom);
        }

        final File generatedPom = new File(project.getBasedir(), ".quarkus-platform-bom.xml");
        if (!generatedPom.exists()) {
            getLog().warn(flattenedPom + " does not exist");
            return;
        }
        final Model generatedModel;
        try {
            generatedModel = MojoUtils.readPom(generatedPom);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to parse " + generatedPom);
        }

        Map<AppArtifactKey, Dependency> generatedDeps = new HashMap<>();
        List<String> generatedOrdered = new ArrayList<>();
        for (Dependency dep : generatedModel.getDependencyManagement().getDependencies()) {
            final AppArtifactKey key = getKey(dep);
            generatedDeps.put(key, dep);
            generatedOrdered.add(key.toString());
        }
        Collections.sort(generatedOrdered);

        Map<AppArtifactKey, Dependency> flattenedDeps = new HashMap<>();
        List<String> flattenedOrdered = new ArrayList<>();
        int i = 1;
        System.out.println("MISSING FROM THE GENERATED BOM");
        for (Dependency dep : flattenedModel.getDependencyManagement().getDependencies()) {
            final AppArtifactKey key = getKey(dep);
            flattenedDeps.put(key, dep);
            flattenedOrdered.add(key.toString());

            if (!generatedDeps.containsKey(key) && isAcceptableBomDependency(dep)) {
                System.out.println(i++ + ") " + dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                        + (dep.getClassifier() == null ? "" : dep.getClassifier())
                        + ":" + dep.getType() + ":" + dep.getVersion()
                        + (dep.getScope() == null ? "" : "(" + dep.getScope() + ")"));
            }
        }
        Collections.sort(flattenedOrdered);

        i = 1;
        System.out.println("MISSING FROM THE FLATTENED BOM");
        for (AppArtifactKey key : generatedDeps.keySet()) {
            if (!flattenedDeps.containsKey(key) && isAcceptableBomDependency(generatedDeps.get(key))) {
                final Dependency dep = generatedDeps.get(key);
                System.out.println(i++ + ") " + dep.getGroupId() + ":" + dep.getArtifactId() + ":"
                        + (dep.getClassifier() == null ? "" : dep.getClassifier())
                        + ":" + dep.getType() + ":" + dep.getVersion()
                        + (dep.getScope() == null ? "" : "(" + dep.getScope() + ")"));
            }
        }

        System.out.println("Generated deps: " + generatedDeps.size());
        System.out.println("Flattened deps: " + flattenedDeps.size());

        orderDeps(new File(project.getBasedir(), "ordered-flattened-pom.xml"), flattenedDeps, flattenedOrdered);
        orderDeps(new File(project.getBasedir(), "ordered-generated-pom.xml"), generatedDeps, generatedOrdered);
    }

    private static boolean isAcceptableBomDependency(Dependency artifact) {
        return !"javadoc".equals(artifact.getClassifier())
                && !"tests".equals(artifact.getClassifier())
                && !"sources".equals(artifact.getClassifier());
    }

    private void orderDeps(File f, Map<AppArtifactKey, Dependency> deps, List<String> ordered) {
        try {
            final Model model = new Model();
            DependencyManagement dm = new DependencyManagement();
            model.setDependencyManagement(dm);
            for (String s : ordered) {
                Dependency dep = deps.get(AppArtifactKey.fromString(s));
                if (dep == null) {
                    throw new IllegalStateException("Failed to locate " + s);
                }
                dm.addDependency(dep);
            }
            MojoUtils.write(model, f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static AppArtifactKey getKey(Dependency dep) {
        return new AppArtifactKey(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType());
    }
}
