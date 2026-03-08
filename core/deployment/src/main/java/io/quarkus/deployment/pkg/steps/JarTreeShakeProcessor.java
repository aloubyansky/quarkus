package io.quarkus.deployment.pkg.steps;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.MainClassBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessFieldBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassConditionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveFieldBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeBuildItem;

public class JarTreeShakeProcessor {

    static class TreeShakeEnabled implements BooleanSupplier {
        private final PackageConfig packageConfig;

        TreeShakeEnabled(PackageConfig packageConfig) {
            this.packageConfig = packageConfig;
        }

        @Override
        public boolean getAsBoolean() {
            return packageConfig.jar().treeShake() != PackageConfig.JarConfig.TreeShakeLevel.NONE
                    && packageConfig.jar().type() != PackageConfig.JarConfig.JarType.MUTABLE_JAR;
        }
    }

    @BuildStep(onlyIfNot = TreeShakeEnabled.class)
    void skipTreeShaking(BuildProducer<JarTreeShakeBuildItem> treeShakeProducer) {
        treeShakeProducer
                .produce(new JarTreeShakeBuildItem(PackageConfig.JarConfig.TreeShakeLevel.NONE, Set.of(),
                        Map.of()));
    }

    @BuildStep(onlyIf = TreeShakeEnabled.class)
    void analyzeReachableClasses(
            PackageConfig packageConfig,
            CurateOutcomeBuildItem curateOutcome,
            MainClassBuildItem mainClass,
            List<GeneratedClassBuildItem> generatedClasses,
            TransformedClassesBuildItem transformedClasses,
            List<ReflectiveClassBuildItem> reflectiveClasses,
            List<ReflectiveFieldBuildItem> reflectiveFields,
            List<ReflectiveMethodBuildItem> reflectiveMethods,
            List<ReflectiveClassConditionBuildItem> reflectiveClassConditions,
            List<JniRuntimeAccessBuildItem> jniClasses,
            List<JniRuntimeAccessFieldBuildItem> jniFields,
            List<JniRuntimeAccessMethodBuildItem> jniMethods,
            List<ServiceProviderBuildItem> serviceProviderItems,
            BuildProducer<JarTreeShakeBuildItem> treeShakeProducer) {

        try (JarTreeShakerInput input = JarTreeShakerInput.collect(
                packageConfig.jar().treeShake(),
                curateOutcome.getApplicationModel(),
                mainClass.getClassName(),
                generatedClasses,
                transformedClasses,
                reflectiveClasses,
                reflectiveFields,
                reflectiveMethods,
                reflectiveClassConditions,
                jniClasses,
                jniFields,
                jniMethods,
                serviceProviderItems)) {
            treeShakeProducer.produce(new JarTreeShaker(input).run());
        }
    }
}
