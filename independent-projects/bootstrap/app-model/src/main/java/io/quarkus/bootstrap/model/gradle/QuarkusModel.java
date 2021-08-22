package io.quarkus.bootstrap.model.gradle;

import io.quarkus.bootstrap.model.PlatformImports;
import java.util.List;
import java.util.Map;

public interface QuarkusModel {

    Workspace getWorkspace();

    List<Dependency> getAppDependencies();

    List<Dependency> getExtensionDependencies();

    List<Dependency> getEnforcedPlatformDependencies();

    PlatformImports getPlatformImports();

    Map<String, String> getProjectProperties();
}
