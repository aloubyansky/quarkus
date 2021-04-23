package io.quarkus.deployment.conditionaldeps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.TsArtifact;
import io.quarkus.bootstrap.resolver.TsQuarkusExt;
import io.quarkus.deployment.runnerjar.ExecutableOutputOutcomeTestBase;

public class ConditionalDependencyScenarioTwoTest extends ExecutableOutputOutcomeTestBase {

    @Override
    protected TsArtifact modelApp() {

        // dependencies
        // f -> g -> h?(i,j) -> k
        // l -> j -> p?(o)
        // m -> n?(g) -> i -> o?(h)

        final TsQuarkusExt extF = new TsQuarkusExt("ext-f");
        final TsQuarkusExt extG = new TsQuarkusExt("ext-g");
        final TsQuarkusExt extH = new TsQuarkusExt("ext-h");
        final TsQuarkusExt extI = new TsQuarkusExt("ext-i");
        final TsQuarkusExt extJ = new TsQuarkusExt("ext-j");
        final TsQuarkusExt extK = new TsQuarkusExt("ext-k");
        final TsQuarkusExt extL = new TsQuarkusExt("ext-l");
        final TsQuarkusExt extM = new TsQuarkusExt("ext-m");
        final TsQuarkusExt extN = new TsQuarkusExt("ext-n");
        final TsQuarkusExt extO = new TsQuarkusExt("ext-o");
        final TsQuarkusExt extP = new TsQuarkusExt("ext-p");

        extF.addDependency(extG);

        extG.setConditionalDeps(extH);

        extH.setDependencyCondition(extI, extJ);
        extH.addDependency(extK);

        extL.addDependency(extJ);

        extM.setConditionalDeps(extN);

        extN.setDependencyCondition(extG);
        extN.addDependency(extI);

        extI.setConditionalDeps(extO);

        extO.setDependencyCondition(extH);

        extJ.setConditionalDeps(extP);

        extP.setDependencyCondition(extO);

        addToExpectedLib(extF.getRuntime());
        addToExpectedLib(extG.getRuntime());
        addToExpectedLib(extH.getRuntime());
        addToExpectedLib(extI.getRuntime());
        addToExpectedLib(extJ.getRuntime());
        addToExpectedLib(extK.getRuntime());
        addToExpectedLib(extL.getRuntime());
        addToExpectedLib(extM.getRuntime());
        addToExpectedLib(extN.getRuntime());
        addToExpectedLib(extO.getRuntime());
        addToExpectedLib(extP.getRuntime());

        install(extF);
        install(extG);
        install(extH);
        install(extI);
        install(extJ);
        install(extK);
        install(extL);
        install(extM);
        install(extN);
        install(extO);
        install(extP);

        return TsArtifact.jar("app")
                .addManagedDependency(platformDescriptor())
                .addManagedDependency(platformProperties())
                .addDependency(extF)
                .addDependency(extL)
                .addDependency(extM);
    }

    @Override
    protected void assertAppModel(AppModel appModel) throws Exception {
        final List<AppDependency> deploymentDeps = appModel.getDeploymentDependencies();
        final Set<AppDependency> expected = new HashSet<>();
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-f-deployment", TsArtifact.DEFAULT_VERSION), "compile"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-g-deployment", TsArtifact.DEFAULT_VERSION), "compile"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-h-deployment", TsArtifact.DEFAULT_VERSION), "runtime"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-k-deployment", TsArtifact.DEFAULT_VERSION), "runtime"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-l-deployment", TsArtifact.DEFAULT_VERSION), "compile"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-j-deployment", TsArtifact.DEFAULT_VERSION), "compile"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-m-deployment", TsArtifact.DEFAULT_VERSION), "compile"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-n-deployment", TsArtifact.DEFAULT_VERSION), "runtime"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-i-deployment", TsArtifact.DEFAULT_VERSION), "runtime"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-o-deployment", TsArtifact.DEFAULT_VERSION), "runtime"));
        expected.add(new AppDependency(
                new AppArtifact(TsArtifact.DEFAULT_GROUP_ID, "ext-p-deployment", TsArtifact.DEFAULT_VERSION), "runtime"));
        assertEquals(expected, new HashSet<>(deploymentDeps));
    }
}
