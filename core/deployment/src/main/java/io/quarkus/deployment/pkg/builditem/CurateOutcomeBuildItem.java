package io.quarkus.deployment.pkg.builditem;

import java.nio.file.Path;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.builder.item.SimpleBuildItem;

public final class CurateOutcomeBuildItem extends SimpleBuildItem {

    private final ApplicationModel appModel;

    public CurateOutcomeBuildItem(ApplicationModel appModel) {
        this.appModel = appModel;
    }

    public ApplicationModel getApplicationModel() {
        //        System.out.println("APP MODULE " + getApplicationModuleDirectory() + ", module available "
        //               + (appModel.getApplicationModule() != null));
        return appModel;
    }

    /**
     * Returns the path to the application module directory.
     * For a single module project it will the project directory. For a multimodule project,
     * it will the directory of the application module.
     *
     * @return application module directory, never null
     */
    public Path getApplicationModuleDirectory() {
        final WorkspaceModule module = appModel.getApplicationModule();
        // modules are by default available in dev and test modes, and in prod mode if
        // quarkus.bootstrap.workspace-discovery system or project property is true,
        // otherwise it could be null
        if (module != null) {
            return module.getModuleDir().toPath();
        }
        final String basedir = System.getProperty("basedir");
        if (basedir != null) {
            return Path.of(basedir);
        }
        // if the module isn't available, return the current directory
        return Path.of("").toAbsolutePath();
    }
}
