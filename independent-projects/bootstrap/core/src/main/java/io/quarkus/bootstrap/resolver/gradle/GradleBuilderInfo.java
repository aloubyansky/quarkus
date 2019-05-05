/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
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

package io.quarkus.bootstrap.resolver.gradle;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.bootstrap.BootstrapDependencyProcessingException;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.BuilderInfo;
import io.quarkus.bootstrap.resolver.LocalProject;
import io.quarkus.bootstrap.resolver.gradle.workspace.LocalGradleProject;

/**
 * Provides build information from Gradle.
 */
public class GradleBuilderInfo implements BuilderInfo {
    private static final Logger logger = LoggerFactory.getLogger(GradleBuilderInfo.class);
    private LocalGradleProject localProject;

    public GradleBuilderInfo(Path rootProjectDir) {
        long start = System.currentTimeMillis();
        localProject = new LocalGradleProject(rootProjectDir);
        logger.info("Created Gradle connection in {}ms", System.currentTimeMillis() - start);
    }
    
    @Override
    public LocalProject getLocalProject() {
        return localProject;
    }

    @Override
    public BuilderInfo withClasspathCaching(boolean caching) {
        // unused information
        return this;
    }

    @Override
    public BuilderInfo withLocalProjectsDiscovery(boolean localProjectsDiscovery) {
        // unused information
        return this;
    }

    @Override
    public void close() {
        localProject.close();
    }

    @Override
    public List<AppDependency> getDeploymentDependencies(boolean offline)
            throws BootstrapDependencyProcessingException, AppModelResolverException {
        long start = System.currentTimeMillis();
        List<AppDependency> deps = localProject.getDependencies(offline);
        logger.info("Found {} dependencies in {}ms", deps.size(), System.currentTimeMillis() - start);
        return deps;
    }
}
