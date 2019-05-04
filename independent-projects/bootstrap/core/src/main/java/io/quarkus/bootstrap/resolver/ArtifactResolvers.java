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

package io.quarkus.bootstrap.resolver;

import java.util.List;

import io.quarkus.bootstrap.BootstrapDependencyProcessingException;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalMavenProject;

/**
 * Provides access to the active artifacts resolver implementation.
 * 
 * Its behavior should be controlled by what service implementations
 * are available on the classpath.
 */
public class ArtifactResolvers {
    public static ArtifactResolver getArtifactResolver() {
        return new MavenArtifactResolverFacade();
    }

    static class MavenArtifactResolverFacade implements ArtifactResolver {
        @Override
        public List<AppDependency> getDeploymentDependencies(boolean offline, LocalProject genericLocalProject) throws BootstrapDependencyProcessingException, AppModelResolverException {
            LocalMavenProject localProject = (LocalMavenProject)genericLocalProject;
            final MavenArtifactResolver.Builder mvn = MavenArtifactResolver.builder()
                    .setWorkspace(localProject.getWorkspace());
            mvn.setOffline(offline);
            return new BootstrapAppModelResolver(mvn.build()).resolveModel(localProject.getAppArtifact()).getDeploymentDependencies();
        }
    }
}
