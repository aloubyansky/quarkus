package io.quarkus.deployment.pkg.steps;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import io.quarkus.builder.BuildResult;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeBuildItem;

/**
 * Result handler of the dependency trim report goals, passed to {@code AugmentAction.performCustomBuild} with
 * {@link JarTreeShakeBuildItem} and {@link CurateOutcomeBuildItem} as the final outputs.
 * <p>
 * The context is a mutable {@code Map<String, Object>}. It may carry the report options
 * {@link #BARELY_USED_MAX_CLASSES} and {@link #MAX_LISTED_CLASSES} as {@link Integer}s, and receives
 * {@link #CLASSES_SHAKEN} as a {@link Boolean} and, if classes were shaken, {@link #REPORT} as a {@code List<String>}
 * of report lines. Only JDK types cross back, since the handler runs in the deployment class loader.
 */
public class DependencyTrimHandler implements BiConsumer<Object, BuildResult> {

    /** Maximum number of kept classes for a dependency to be reported as barely used. */
    public static final String BARELY_USED_MAX_CLASSES = "barely-used-max-classes";
    /** Maximum number of kept class names listed for a barely used dependency. */
    public static final String MAX_LISTED_CLASSES = "max-listed-classes";
    /** Whether the analysis removed classes; if not, no report is produced. */
    public static final String CLASSES_SHAKEN = "classes-shaken";
    /** Report lines. */
    public static final String REPORT = "report";

    @Override
    @SuppressWarnings("unchecked")
    public void accept(Object context, BuildResult buildResult) {
        final Map<String, Object> ctx = (Map<String, Object>) context;
        final JarTreeShakeBuildItem treeShake = buildResult.consume(JarTreeShakeBuildItem.class);
        ctx.put(CLASSES_SHAKEN, treeShake.isClassesShaken());
        if (!treeShake.isClassesShaken()) {
            return;
        }
        final List<String> report = new DependencyTrimReport(treeShake,
                buildResult.consume(CurateOutcomeBuildItem.class).getApplicationModel(),
                intOption(ctx, BARELY_USED_MAX_CLASSES, DependencyTrimReport.DEFAULT_BARELY_USED_MAX_CLASSES),
                intOption(ctx, MAX_LISTED_CLASSES, DependencyTrimReport.DEFAULT_MAX_LISTED_CLASSES))
                .render();
        ctx.put(REPORT, report);
    }

    private static int intOption(Map<String, Object> ctx, String name, int defaultValue) {
        final Object value = ctx.get(name);
        return value == null ? defaultValue : (Integer) value;
    }
}
