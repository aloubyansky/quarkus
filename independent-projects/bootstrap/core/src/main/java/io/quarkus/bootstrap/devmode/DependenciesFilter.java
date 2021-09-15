package io.quarkus.bootstrap.devmode;

import io.quarkus.bootstrap.model.gradle.ApplicationModel;
import io.quarkus.bootstrap.workspace.ProjectModule;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

public class DependenciesFilter {

    private static final Logger log = Logger.getLogger(DependenciesFilter.class);

    public static List<ProjectModule> getReloadableModules(ApplicationModel appModel) {
        final List<ProjectModule> reloadable = new ArrayList<>();
        if (appModel.getApplicationModule() != null) {
            reloadable.add(appModel.getApplicationModule());
        }
        appModel.getDependencies().forEach(d -> {
            final ProjectModule module = d.getArtifact().getModule();
            if (module != null) {
                if (d.isReloadable()) {
                    reloadable.add(module);
                } else {
                    //if this project also contains Quarkus extensions we do no want to include these in the discovery
                    //a bit of an edge case, but if you try and include a sample project with your extension you will
                    //run into problems without this
                    log.warn("Local Quarkus extension dependency " + module.getId() + " will not be hot-reloadable");
                }
            }
        });
        return reloadable;
    }
}
