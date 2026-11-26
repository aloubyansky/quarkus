package io.quarkus.maven;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeBuildItem;
import io.quarkus.deployment.pkg.steps.DependencyTrimHandler;
import io.quarkus.maven.dependency.ArtifactCoords;

/**
 * Reports the runtime dependencies of the application that look unused or barely used, to help trim them.
 * <p>
 * It runs the tree-shake analysis of a production build, without packaging the application, and logs the
 * dependencies whose classes are all unreachable, the dependencies without classes, and the dependencies with only
 * a few reachable classes. The report is based on static analysis and heuristics, so the application has to be tested
 * after removing a dependency. It doesn't modify the project.
 * <p>
 * The application has to be compiled first, e.g. {@code mvn compile quarkus:dependency-trim}.
 */
@Mojo(name = "dependency-trim", defaultPhase = LifecyclePhase.NONE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class DependencyTrimMojo extends QuarkusBootstrapMojo {

    private static final String TREE_SHAKE_MODE = "quarkus.package.jar.tree-shake.mode";
    private static final String JAR_TYPE = "quarkus.package.jar.type";

    /**
     * Whether to skip the execution of the goal
     */
    @Parameter(defaultValue = "false", property = "quarkus.dependency.trim.skip")
    boolean skip;

    /**
     * Maximum number of reachable classes for a dependency with unreachable classes to be reported as barely used.
     */
    @Parameter(defaultValue = "10", property = "quarkus.dependency.trim.barely-used-max-classes")
    int barelyUsedMaxClasses;

    /**
     * Maximum number of reachable class names listed for each barely used dependency.
     */
    @Parameter(defaultValue = "20", property = "quarkus.dependency.trim.max-listed-classes")
    int maxListedClasses;

    @Override
    protected boolean beforeExecute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping Quarkus dependency trim report");
            return false;
        }
        if (mavenProject().getPackaging().equals(ArtifactCoords.TYPE_POM)) {
            getLog().info("Type of the artifact is POM, skipping dependency-trim goal");
            return false;
        }
        if (!new File(mavenProject().getBuild().getOutputDirectory()).isDirectory()) {
            throw new MojoExecutionException("The application classes were not found in "
                    + mavenProject().getBuild().getOutputDirectory()
                    + ", compile the application first, e.g. mvn compile quarkus:dependency-trim");
        }
        return true;
    }

    @Override
    protected void doExecute() throws MojoExecutionException {
        final Map<String, String> originalProperties = new HashMap<>();
        // tree shaking is forced regardless of the application configuration
        setSystemProperty(TREE_SHAKE_MODE, "classes", originalProperties);
        if (setNativeEnabledIfNativeProfileEnabled()) {
            originalProperties.put("quarkus.native.enabled", null);
        }
        try (CuratedApplication curatedApplication = bootstrapApplication()) {
            Map<String, Object> result = analyze(curatedApplication);
            if (!(Boolean) result.get(DependencyTrimHandler.CLASSES_SHAKEN)) {
                // tree shaking is never performed for a mutable-jar, which is the only remaining reason
                getLog().debug("Tree shaking was not performed, re-running the analysis for a fast-jar");
                setSystemProperty(JAR_TYPE, "fast-jar", originalProperties);
                result = analyze(curatedApplication);
                if (!(Boolean) result.get(DependencyTrimHandler.CLASSES_SHAKEN)) {
                    throw new MojoExecutionException("Tree shaking was not performed for the application");
                }
            }
            logReport(result);
        } finally {
            restoreSystemProperties(originalProperties);
        }
    }

    private Map<String, Object> analyze(CuratedApplication curatedApplication) {
        final Map<String, Object> context = new HashMap<>();
        context.put(DependencyTrimHandler.BARELY_USED_MAX_CLASSES, barelyUsedMaxClasses);
        context.put(DependencyTrimHandler.MAX_LISTED_CLASSES, maxListedClasses);
        curatedApplication.createAugmentor().performCustomBuild(DependencyTrimHandler.class.getName(), context,
                JarTreeShakeBuildItem.class.getName(), CurateOutcomeBuildItem.class.getName());
        return context;
    }

    @SuppressWarnings("unchecked")
    private void logReport(Map<String, Object> result) {
        for (String line : (List<String>) result.get(DependencyTrimHandler.REPORT)) {
            getLog().info(line);
        }
    }

    /**
     * Sets a system property, remembering its original value the first time it is set.
     */
    private static void setSystemProperty(String name, String value, Map<String, String> originalProperties) {
        final String original = System.setProperty(name, value);
        if (!originalProperties.containsKey(name)) {
            originalProperties.put(name, original);
        }
    }

    private static void restoreSystemProperties(Map<String, String> originalProperties) {
        for (Map.Entry<String, String> property : originalProperties.entrySet()) {
            if (property.getValue() == null) {
                System.clearProperty(property.getKey());
            } else {
                System.setProperty(property.getKey(), property.getValue());
            }
        }
    }
}
