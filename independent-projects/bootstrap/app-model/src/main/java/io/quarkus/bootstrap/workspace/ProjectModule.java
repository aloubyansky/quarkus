package io.quarkus.bootstrap.workspace;

import io.quarkus.paths.PathCollection;
import java.io.File;
import java.util.Collection;

public interface ProjectModule {

    ProjectModuleId getId();

    File getModuleDir();

    File getBuildDir();

    Collection<ProcessedSources> getMainSources();

    Collection<ProcessedSources> getMainResources();

    Collection<ProcessedSources> getTestSources();

    Collection<ProcessedSources> getTestResources();

    PathCollection getBuildFiles();
}
