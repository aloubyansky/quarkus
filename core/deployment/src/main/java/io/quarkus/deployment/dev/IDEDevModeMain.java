package io.quarkus.deployment.dev;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.BootstrapGradleException;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.devmode.DependenciesFilter;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.util.BootstrapUtils;
import io.quarkus.bootstrap.utils.BuildToolHelper;
import io.quarkus.deployment.dev.DevModeContext.ModuleInfo;
import io.quarkus.dev.spi.DevModeType;
import io.quarkus.maven.dependency.ResolvedDependency;

public class IDEDevModeMain implements BiConsumer<CuratedApplication, Map<String, Object>>, Closeable {

    private static final Logger log = Logger.getLogger(IDEDevModeMain.class.getName());
    private static final String APP_PROJECT = "app-project";

    private IsolatedDevModeMain delegate;

    @Override
    public void accept(CuratedApplication curatedApplication, Map<String, Object> stringObjectMap) {
        Path appClasses = (Path) stringObjectMap.get("app-classes");
        DevModeContext devModeContext = new DevModeContext();
        devModeContext.setArgs((String[]) stringObjectMap.get("args"));

        ApplicationModel appModel = null;
        try {
            if (BuildToolHelper.isMavenProject(appClasses)) {
                appModel = curatedApplication.getApplicationModel();
            } else {
                appModel = BootstrapUtils
                        .deserializeQuarkusModel((Path) stringObjectMap.get(BootstrapConstants.SERIALIZED_APP_MODEL));
            }

            if (appModel != null) {
                for (ResolvedDependency project : DependenciesFilter.getReloadableModules(appModel)) {
                    final ModuleInfo module = toModule(project);
                    if (project.getKey().equals(appModel.getAppArtifact().getKey())
                            && project.getVersion().equals(appModel.getAppArtifact().getVersion())) {
                        devModeContext.setApplicationRoot(module);
                    } else {
                        devModeContext.getAdditionalModules().add(module);
                        devModeContext.getLocalArtifacts().add(project.getKey());
                    }
                }
            }
        } catch (AppModelResolverException e) {
            log.error("Failed to load workspace, hot reload will not be available", e);
        }

        terminateIfRunning();
        delegate = new IsolatedDevModeMain();
        Map<String, Object> params = new HashMap<>();
        params.put(DevModeContext.class.getName(), devModeContext);
        params.put(DevModeType.class.getName(), DevModeType.LOCAL);
        delegate.accept(curatedApplication,
                params);
    }

    @Override
    public void close() {
        terminateIfRunning();
    }

    private void terminateIfRunning() {
        if (delegate != null) {
            delegate.close();
        }
    }

    private DevModeContext.ModuleInfo toModule(ResolvedDependency module) throws BootstrapGradleException {
        return new DevModeContext.ModuleInfo.Builder()
                .setArtifactKey(module.getKey())
                .setProjectDirectory(module.getWorkspaceModule().getModuleDir().getPath())
                .setPreBuildOutputDir(module.getWorkspaceModule().getBuildDir().toPath().resolve("generated-sources")
                        .toAbsolutePath().toString())
                .setTargetDir(module.getWorkspaceModule().getBuildDir().toString()).build();
    }
}
