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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.LocalProject;
import io.quarkus.bootstrap.resolver.LocalWorkspace;

/**
 * Provides workspace related information based on Gradle project.
 */
public class LocalGradleWorkspace implements LocalWorkspace {
    private static final Logger logger = LoggerFactory.getLogger(LocalGradleWorkspace.class);
    private final Path rootProjectDir;
    private final Map<AppArtifactKey, LocalGradleProject> projects;

    public LocalGradleWorkspace(Path rootProjectDir, Map<AppArtifactKey, LocalGradleProject> projects) {
        this.rootProjectDir = rootProjectDir;
        this.projects = projects;
    }
    
    /**
     * Returns a hash built from files that can influence classpath.
     */
    @Override
    public int getId() {
        long start = System.currentTimeMillis();
        Path propsFile = rootProjectDir.resolve("gradle.properties");
        Path buildFile = rootProjectDir.resolve("build.gradle");
        
        int id = getHash(propsFile) * 13 + getHash(buildFile);
        logger.info("Computed Gradle workspace id in {}ms: {}", System.currentTimeMillis() - start, id);
        return id;
    }
    
    @Override
    public Map<AppArtifactKey, LocalProject> getProjects() {
        return Collections.unmodifiableMap(projects);
    }

    private int getHash(Path file) {
        try {
            if (Files.exists(file)) {
                return Arrays.hashCode(Files.readAllBytes(file));
            }
        } catch (IOException e) {
            logger.warn("Failed to checksum file {}", file, e);
        }
        return 0;
    }
}
