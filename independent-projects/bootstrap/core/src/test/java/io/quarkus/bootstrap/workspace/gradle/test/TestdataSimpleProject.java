/**
 *
 */
package io.quarkus.bootstrap.workspace.gradle.test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import io.quarkus.bootstrap.util.IoUtils;

public class TestdataSimpleProject {
    public static final String DEFAULT_GROUP_ID = "io.quarkus.test";
    public static final String DEFAULT_VERSION = "1.0";

    private TestdataSimpleProject() {}

    public static void setupSimple(Path workDir) throws IOException {
        IoUtils.copy(Paths.get("test/gradle-workspace/simple"), workDir);
    }
    
    public static void cleanupSimple(Path workDir) {
        IoUtils.recursiveDelete(workDir);
    }
}
