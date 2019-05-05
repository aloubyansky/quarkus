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

package io.quarkus.bootstrap.resolver;

import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.resolver.gradle.GradleBuilderInfo;
import io.quarkus.bootstrap.resolver.maven.MavenBuilderInfo;

/**
 * Factory for providing BuilderInfo.
 * 
 * Uses existence of a builder configuration file in
 * parent directories to determine type.
 */
public class BuilderInfos {
    private static final String BUILD_GRADLE = "build.gradle";
    private static final String POM_XML = "pom.xml";

    public static BuilderInfo find(Path path) throws BootstrapException {
        Path p = path;
        while (p != null) {
            if (Files.exists(p.resolve(POM_XML))) {
                return new MavenBuilderInfo(p);
            }
            if (Files.exists(p.resolve(BUILD_GRADLE))) {
                return new GradleBuilderInfo(p);
            }
            p = p.getParent();
        }
        throw new BootstrapException("Failed to locate builder file for " + path);
    }
}
