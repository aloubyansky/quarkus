/*
 * Copyright 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.quarkus.bootstrap.resolver.gradle.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.UnknownModelException;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.gradle.GradlePublication;
import org.gradle.tooling.model.gradle.ProjectPublications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.resolver.LocalProject;
import io.quarkus.bootstrap.resolver.LocalWorkspace;

/**
 * Represents a local gradle project.
 * 
 * Only supports a single main project at present.
 */
public class LocalGradleProject implements LocalProject {
    private static final Logger logger = LoggerFactory.getLogger(LocalGradleProject.class);
    private static final String BUILD_GRADLE = "build.gradle";
    private final ProjectConnection pc;
    private Path projectDir;
    private EclipseProject eclipseProject;

    private LocalGradleProject(Path projectDir) {
        this.projectDir = projectDir;

        pc = GradleConnector.newConnector()
                .forProjectDirectory(projectDir.toFile())
                .connect();
    }

    public static LocalProject load(Path appClassesDir) throws BootstrapException {
        Path projectDir = locateCurrentProjectDir(appClassesDir);
        return new LocalGradleProject(projectDir);
    }

    @Override
    public Path getOutputDir() {
        return projectDir.resolve("build");
    }

    @Override
    public LocalWorkspace getWorkspace() {
        // TODO: Only handles the one main project. Not clear if more is really needed.
        Map<AppArtifactKey, LocalGradleProject> projects = new HashMap<>();
        getPublication()
            .map(gav -> new AppArtifactKey(gav.getGroup(), gav.getName()))
            .ifPresent(key -> projects.put(key, this));
        
        return new LocalGradleWorkspace(projectDir, projects);
    }
    
    private Optional<GradleModuleVersion> getPublication() {
        ProjectPublications projectPublications = getModel(ProjectPublications.class);
        if (projectPublications == null) {
            return Optional.empty();
        }
        return projectPublications.getPublications().stream()
            .map(GradlePublication::getId)
            .findFirst();
    }

    @Override
    public AppArtifact getAppArtifact() {
        return new AppArtifact(getGroupId(), getArtifactId(), "", "jar", getVersion());
    }

    @Override
    public String getGroupId() {
        return getPublication().map(GradleModuleVersion::getGroup).orElse("na");
    }

    @Override
    public String getArtifactId() {
        return getPublication().map(GradleModuleVersion::getName).orElse("na");
    }

    @Override
    public String getVersion() {
        return getPublication().map(GradleModuleVersion::getVersion).orElse("na");
    }

    public List<AppDependency> getDependencies(boolean offline) {
        DomainObjectSet<? extends EclipseExternalDependency> deps = getEclipseProject().getClasspath();
        return deps.stream()
            .map(this::toArtifact)
            .map(artifact -> new AppDependency(artifact, "compile"))
            .collect(Collectors.toList());
    }

    private EclipseProject getEclipseProject() {
        if (eclipseProject == null) {
            eclipseProject = getModel(EclipseProject.class);
            if (eclipseProject == null) {
                throw new IllegalStateException("Unable to get eclipse model from gradle");
            }
        }
        return eclipseProject;
    }
    
    private AppArtifact toArtifact(EclipseExternalDependency dep) {
        GradleModuleVersion gav = dep.getGradleModuleVersion();
        if (gav == null) {
            throw new IllegalStateException("No GAV found for dependency " + dep.getFile());
        }
        AppArtifact artifact = new AppArtifact(gav.getGroup(), gav.getName(), gav.getVersion());
        artifact.setPath(dep.getFile().toPath());
        return artifact;
    }
    
    /**
     * Note that this will not work from a flatDir child-project.
     */
    private static Path locateCurrentProjectDir(Path path) throws BootstrapException {
        Path p = path;
        while (p != null) {
            if (Files.exists(p.resolve(BUILD_GRADLE))) {
                return p;
            }
            p = p.getParent();
        }
        throw new BootstrapException("Failed to locate project build.gradle for " + path);
    }

    private <T> T getModel(Class<T> type) {
        try {
            return pc.getModel(type);
        } catch (UnknownModelException e) {
            logger.warn("Failed to access Gradle model of type " + type, e);
            return null;
        }
    }
}
