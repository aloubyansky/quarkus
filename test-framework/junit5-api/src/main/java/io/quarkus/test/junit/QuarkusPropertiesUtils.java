package io.quarkus.test.junit;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Properties;

import org.junit.jupiter.api.extension.ExtensionContext;

public class QuarkusPropertiesUtils {

    static Path determineBuildOutputDirectory(ExtensionContext context) {
        String buildOutputDirStr = System.getProperty("build.output.directory");
        Path result = null;
        if (buildOutputDirStr != null) {
            result = Paths.get(buildOutputDirStr);
        } else {
            // we need to guess where the artifact properties file is based on the location of the test class
            Class<?> testClass = context.getRequiredTestClass();
            final CodeSource codeSource = testClass.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                URL codeSourceLocation = codeSource.getLocation();
                Path artifactPropertiesDirectory = determineBuildOutputDirectory(codeSourceLocation);
                if (artifactPropertiesDirectory == null) {
                    throw new IllegalStateException(
                            "Unable to determine the output of the Quarkus build. Consider setting the 'build.output.directory' system property.");
                }
                result = artifactPropertiesDirectory;
            }
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Unable to locate the artifact metadata file created that must be created by Quarkus in order to run tests annotated with '@QuarkusIntegrationTest'.");
        }
        if (!Files.isDirectory(result)) {
            throw new IllegalStateException(
                    "The determined Quarkus build output '" + result.toAbsolutePath().toString() + "' is not a directory");
        }
        return result;
    }

    private static Path determineBuildOutputDirectory(final URL url) {
        if (url == null) {
            return null;
        }
        if (url.getProtocol().equals("file")) {
            if (url.getPath().endsWith("test-classes/")) {
                // we have the maven test classes dir
                return toPath(url).getParent();
            } else if (url.getPath().endsWith("test/") || url.getPath().endsWith("integrationTest/")) {
                // we have the gradle test classes dir, build/classes/java/test
                return toPath(url).getParent().getParent().getParent();
            } else if (url.getPath().contains("/target/surefire/")) {
                // this will make mvn failsafe:integration-test work
                String path = url.getPath();
                int index = path.lastIndexOf("/target/");
                try {
                    return Paths.get(new URI("file:" + (path.substring(0, index) + "/target/")));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            } else if (url.getPath().endsWith("-tests.jar")) {
                // integration platform test
                final Path baseDir = Path.of("").normalize().toAbsolutePath();
                Path outputDir = baseDir.resolve("target");
                if (Files.exists(outputDir)) {
                    return outputDir;
                }
                outputDir = baseDir.resolve("build");
                if (Files.exists(outputDir)) {
                    return outputDir;
                }
            }
        }
        return null;
    }

    private static Path toPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static Properties readQuarkusArtifactProperties(ExtensionContext context) {
        Path buildOutputDirectory = determineBuildOutputDirectory(context);
        Path artifactProperties = buildOutputDirectory.resolve("quarkus-artifact.properties");
        if (!Files.exists(artifactProperties)) {
            TestLauncher testLauncher = determineTestLauncher();
            String errorMessage = "Unable to locate the artifact metadata file created that must be created by Quarkus in order to run integration tests. ";
            if (testLauncher == TestLauncher.MAVEN) {
                errorMessage += "Make sure this test is run after 'mvn package'. ";
                if (context.getTestClass().isPresent()) {
                    String testClassName = context.getTestClass().get().getName();
                    if (testClassName.endsWith("Test")) {
                        errorMessage += "The easiest way to ensure this is by having the 'maven-failsafe-plugin' run the test instead of the 'maven-surefire-plugin'.";
                    }
                }
            } else if (testLauncher == TestLauncher.GRADLE) {
                errorMessage += "Make sure this test is run after the 'quarkusBuild' Gradle task.";
            } else {
                errorMessage += "Make sure this test is run after the Quarkus artifact is built from your build tool.";
            }
            throw new IllegalStateException(errorMessage);
        }
        try {
            Properties properties = new Properties();
            properties.load(new FileInputStream(artifactProperties.toFile()));
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Unable to read artifact metadata file created that must be created by Quarkus in order to run integration tests.",
                    e);
        }
    }

    public static String getArtifactType(Properties quarkusArtifactProperties) {
        String artifactType = quarkusArtifactProperties.getProperty("type");
        if (artifactType == null) {
            throw new IllegalStateException("Unable to determine the type of artifact created by the Quarkus build");
        }
        return artifactType;
    }

    public static void activateLogging() {
        // calling this method of the Recorder essentially sets up logging and configures most things
        // based on the provided configuration

        //we need to run this from the TCCL, as we want to activate it from
        //inside the isolated CL, if one exists
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> lrs = cl.loadClass("io.quarkus.runtime.logging.LoggingSetupRecorder");
            lrs.getDeclaredMethod("handleFailedStart").invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TestLauncher determineTestLauncher() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        int i = stackTrace.length - 1;
        TestLauncher testLauncher = TestLauncher.UNKNOWN;
        while (true) {
            StackTraceElement element = stackTrace[i--];
            String className = element.getClassName();
            if (className.startsWith("org.apache.maven")) {
                testLauncher = TestLauncher.MAVEN;
                break;
            }
            if (className.startsWith("org.gradle")) {
                testLauncher = TestLauncher.GRADLE;
            }
            if (i == 0) {
                break;
            }
        }
        return testLauncher;
    }

    private enum TestLauncher {
        MAVEN,
        GRADLE,
        UNKNOWN
    }
}
