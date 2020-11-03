package io.quarkus.maven;

import static io.quarkus.devtools.project.extensions.Extensions.toKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;

import io.quarkus.bootstrap.model.AppArtifactCoords;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.devtools.project.BuildTool;
import io.quarkus.devtools.project.buildfile.BuildFile;
import io.quarkus.maven.utilities.MojoUtils;
import io.quarkus.platform.descriptor.QuarkusPlatformDescriptor;

public class MavenProjectBuildFile extends BuildFile {

    private final ArtifactDescriptorResult projectDescriptor;
    private final MavenProject project;
    protected List<AppArtifactCoords> dependencies;
    protected List<AppArtifactCoords> managedDependencies;
    protected Model model;

    public MavenProjectBuildFile(Path projectDirPath, QuarkusPlatformDescriptor platformDescriptor, MavenProject project,
            ArtifactDescriptorResult projectDescriptor) {
        super(projectDirPath, platformDescriptor);
        this.project = project;
        this.projectDescriptor = projectDescriptor;
    }

    @Override
    public BuildTool getBuildTool() {
        return BuildTool.MAVEN;
    }

    @Override
    protected boolean addDependency(AppArtifactCoords coords, boolean managed) {
        final Dependency d = new Dependency();
        d.setGroupId(coords.getGroupId());
        d.setArtifactId(coords.getArtifactId());
        if (!managed) {
            d.setVersion(coords.getVersion());
        }
        // When classifier is empty, you get  <classifier></classifier> in the pom.xml
        if (coords.getClassifier() != null && !coords.getClassifier().isEmpty()) {
            d.setClassifier(coords.getClassifier());
        }
        d.setType(coords.getType());
        if ("pom".equalsIgnoreCase(coords.getType())) {
            if (!getManagedDependencies().contains(coords)) {
                d.setScope("import");
                DependencyManagement dependencyManagement = getModel().getDependencyManagement();
                if (dependencyManagement == null) {
                    dependencyManagement = new DependencyManagement();
                    getModel().setDependencyManagement(dependencyManagement);
                }
                dependencyManagement.addDependency(d);
                getManagedDependencies().add(coords);
                return true;
            }
        } else if (!getDependencies().contains(coords)) {
            getModel().addDependency(d);
            getDependencies().add(coords);
            return true;
        }
        return false;
    }

    @Override
    protected void removeDependency(AppArtifactKey key) throws IOException {
        if (getModel() != null) {
            final Iterator<AppArtifactCoords> i = getDependencies().iterator();
            while (i.hasNext()) {
                final AppArtifactCoords a = i.next();
                if (a.getKey().equals(key)) {
                    i.remove();
                    break;
                }
                getModel().getDependencies().removeIf(d -> Objects.equals(toKey(d), key));
            }
        }
    }

    @Override
    protected List<AppArtifactCoords> getDependencies() {
        if (dependencies == null) {
            final List<org.eclipse.aether.graph.Dependency> projectDeps = projectDescriptor.getDependencies();
            dependencies = new ArrayList<>(projectDeps.size());
            for (org.eclipse.aether.graph.Dependency dep : projectDeps) {
                org.eclipse.aether.artifact.Artifact a = dep.getArtifact();
                dependencies.add(new AppArtifactCoords(a.getGroupId(), a.getArtifactId(), a.getClassifier(),
                        a.getExtension(), a.getVersion()));
                System.out.println("dep " + a);
            }
        }
        return dependencies;
    }

    protected List<AppArtifactCoords> getManagedDependencies() {
        if (managedDependencies == null) {
            final List<org.eclipse.aether.graph.Dependency> managedDeps = projectDescriptor.getManagedDependencies();
            managedDependencies = new ArrayList<>(managedDeps.size());
            for (org.eclipse.aether.graph.Dependency dep : managedDeps) {
                org.eclipse.aether.artifact.Artifact a = dep.getArtifact();
                managedDependencies.add(new AppArtifactCoords(a.getGroupId(), a.getArtifactId(), a.getClassifier(),
                        a.getExtension(), a.getVersion()));
            }
        }
        return dependencies;
    }

    @Override
    protected void writeToDisk() throws IOException {
        if (model == null) {
            return;
        }
        try (ByteArrayOutputStream pomOutputStream = new ByteArrayOutputStream()) {
            MojoUtils.write(getModel(), pomOutputStream);
            writeToProjectFile(BuildTool.MAVEN.getDependenciesFile(), pomOutputStream.toByteArray());
        }
    }

    @Override
    protected String getProperty(String propertyName) {
        return String.valueOf(projectDescriptor.getProperties().get(propertyName));
    }

    @Override
    protected void refreshData() {
    }

    private Model getModel() {
        if (model == null) {
            model = project.getModel().clone();
        }
        return model;
    }
}
