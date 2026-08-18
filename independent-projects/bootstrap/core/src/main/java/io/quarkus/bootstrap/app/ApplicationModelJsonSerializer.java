package io.quarkus.bootstrap.app;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.json.Json;
import io.quarkus.bootstrap.json.Json.JsonArrayBuilder;
import io.quarkus.bootstrap.json.Json.JsonObjectBuilder;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.model.ExtensionDevModeConfig;
import io.quarkus.bootstrap.model.JvmOption;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.PlatformReleaseInfo;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;

/**
 * Serializes an {@link ApplicationModel} directly to JSON using the bootstrap JSON API.
 */
class ApplicationModelJsonSerializer {

    static JsonObjectBuilder toJson(ApplicationModel model) {
        final JsonObjectBuilder root = Json.object();
        root.set(BootstrapConstants.MAPPABLE_APP_ARTIFACT, serializeResolvedDependency(model.getAppArtifact()));
        root.set(BootstrapConstants.MAPPABLE_DEPENDENCIES, serializeDependencies(
                model.getDependenciesWithAnyFlag(DependencyFlags.DEPLOYMENT_CP | DependencyFlags.COMPILE_ONLY)));
        root.set(BootstrapConstants.MAPPABLE_PLATFORM_IMPORTS, serializePlatformImports(model.getPlatforms()));

        if (!model.getExtensionCapabilities().isEmpty()) {
            root.set(BootstrapConstants.MAPPABLE_CAPABILITIES, serializeCapabilities(model.getExtensionCapabilities()));
        }
        if (!model.getReloadableWorkspaceDependencies().isEmpty()) {
            root.set(BootstrapConstants.MAPPABLE_LOCAL_PROJECTS,
                    serializeArtifactKeys(model.getReloadableWorkspaceDependencies().stream().sorted().toList()));
        }
        if (!model.getRemovedResources().isEmpty()) {
            root.set(BootstrapConstants.MAPPABLE_EXCLUDED_RESOURCES,
                    serializeExcludedResources(model.getRemovedResources()));
        }
        if (!model.getExtensionDevModeConfig().isEmpty()) {
            root.set(BootstrapConstants.MAPPABLE_EXTENSION_DEV_CONFIG,
                    serializeExtensionDevModeConfigs(model.getExtensionDevModeConfig()));
        }
        return root;
    }

    private static JsonArrayBuilder serializeDependencies(Iterable<ResolvedDependency> deps) {
        final JsonArrayBuilder arr = Json.array();
        for (ResolvedDependency dep : deps) {
            arr.append(serializeResolvedDependency(dep));
        }
        return arr;
    }

    private static JsonObjectBuilder serializeResolvedDependency(ResolvedDependency dep) {
        final JsonObjectBuilder json = Json.object();
        serializeDependencyFields(json, dep);
        final JsonArrayBuilder paths = Json.array();
        dep.getResolvedPaths().forEach(p -> paths.append(p.toString()));
        json.set(BootstrapConstants.MAPPABLE_RESOLVED_PATHS, paths);

        final Collection<ArtifactCoords> transitiveDeps = dep.getDependencies();
        if (transitiveDeps != null && !transitiveDeps.isEmpty()) {
            final JsonArrayBuilder depsArr = Json.array();
            for (ArtifactCoords coords : transitiveDeps) {
                depsArr.append(coords.toGACTVString());
            }
            json.set(BootstrapConstants.MAPPABLE_DEPENDENCIES, depsArr);
        }
        final Collection<Dependency> directDeps = dep.getDirectDependencies();
        if (directDeps != null && !directDeps.isEmpty()) {
            final JsonArrayBuilder directArr = Json.array();
            for (Dependency d : directDeps) {
                directArr.append(serializeDependency(d));
            }
            json.set(BootstrapConstants.MAPPABLE_DIRECT_DEPS, directArr);
        }
        final WorkspaceModule module = dep.getWorkspaceModule();
        if (module != null) {
            json.set(BootstrapConstants.MAPPABLE_MODULE, serializeWorkspaceModule(module));
        }
        return json;
    }

    private static JsonObjectBuilder serializeDependency(Dependency dep) {
        final JsonObjectBuilder json = Json.object();
        serializeDependencyFields(json, dep);
        return json;
    }

    private static void serializeDependencyFields(JsonObjectBuilder json, Dependency dep) {
        json.set(BootstrapConstants.MAPPABLE_MAVEN_ARTIFACT, dep.toGACTVString());
        json.set(BootstrapConstants.MAPPABLE_SCOPE, dep.getScope());
        json.set(BootstrapConstants.MAPPABLE_FLAGS, dep.getFlags());
        final Collection<ArtifactKey> exclusions = dep.getExclusions();
        if (exclusions != null && !exclusions.isEmpty()) {
            json.set(BootstrapConstants.MAPPABLE_EXCLUSIONS, serializeArtifactKeys(exclusions));
        }
    }

    private static JsonObjectBuilder serializePlatformImports(PlatformImports platforms) {
        final JsonObjectBuilder json = Json.object();
        final Map<String, String> props = platforms.getPlatformProperties();
        if (props != null && !props.isEmpty()) {
            final JsonObjectBuilder propsJson = Json.object();
            for (Map.Entry<String, String> entry : props.entrySet()) {
                propsJson.set(entry.getKey(), entry.getValue());
            }
            json.set(BootstrapConstants.MAPPABLE_PLATFORM_PROPS, propsJson);
        }
        final Collection<PlatformReleaseInfo> releaseInfo = platforms.getPlatformReleaseInfo();
        if (releaseInfo != null) {
            final JsonArrayBuilder arr = Json.array();
            for (PlatformReleaseInfo info : releaseInfo) {
                arr.append(serializePlatformReleaseInfo(info));
            }
            json.set(BootstrapConstants.MAPPABLE_PLATFORM_RELEASE_INFO, arr);
        }
        final Collection<ArtifactCoords> boms = platforms.getImportedPlatformBoms();
        if (boms != null) {
            final JsonArrayBuilder arr = Json.array();
            for (ArtifactCoords bom : boms) {
                arr.append(bom.toGACTVString());
            }
            json.set(BootstrapConstants.MAPPABLE_IMPORTED_BOMS, arr);
        }
        return json;
    }

    private static JsonObjectBuilder serializePlatformReleaseInfo(PlatformReleaseInfo info) {
        final JsonObjectBuilder json = Json.object();
        if (info.getPlatformKey() != null) {
            json.set(BootstrapConstants.MAPPABLE_PLATFORM_KEY, info.getPlatformKey());
        }
        if (info.getStream() != null) {
            json.set(BootstrapConstants.MAPPABLE_STREAM, info.getStream());
        }
        if (info.getVersion() != null) {
            json.set(BootstrapConstants.MAPPABLE_VERSION, info.getVersion());
        }
        final List<ArtifactCoords> boms = info.getBoms();
        if (boms != null && !boms.isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (ArtifactCoords bom : boms) {
                arr.append(bom.toGACTVString());
            }
            json.set(BootstrapConstants.MAPPABLE_BOMS, arr);
        }
        return json;
    }

    private static JsonArrayBuilder serializeCapabilities(Collection<ExtensionCapabilities> capabilities) {
        final JsonArrayBuilder arr = Json.array();
        for (ExtensionCapabilities cap : capabilities) {
            final JsonObjectBuilder json = Json.object();
            json.set(BootstrapConstants.MAPPABLE_EXTENSION, cap.getExtension());
            if (!cap.getProvidesCapabilities().isEmpty()) {
                json.set(BootstrapConstants.MAPPABLE_PROVIDED, serializeStrings(cap.getProvidesCapabilities()));
            }
            if (!cap.getRequiresCapabilities().isEmpty()) {
                json.set(BootstrapConstants.MAPPABLE_REQUIRED, serializeStrings(cap.getRequiresCapabilities()));
            }
            arr.append(json);
        }
        return arr;
    }

    private static JsonObjectBuilder serializeExcludedResources(Map<ArtifactKey, Set<String>> removedResources) {
        final JsonObjectBuilder json = Json.object();
        removedResources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> json.set(entry.getKey().toString(),
                        serializeStrings(entry.getValue().stream().sorted().toList())));
        return json;
    }

    private static JsonArrayBuilder serializeExtensionDevModeConfigs(
            Collection<ExtensionDevModeConfig> configs) {
        final JsonArrayBuilder arr = Json.array();
        for (ExtensionDevModeConfig config : configs) {
            final JsonObjectBuilder json = Json.object();
            json.set(BootstrapConstants.MAPPABLE_EXTENSION, config.getExtensionKey().toGacString());
            if (!config.getJvmOptions().isEmpty()) {
                final JsonArrayBuilder optsArr = Json.array();
                for (JvmOption opt : config.getJvmOptions()) {
                    final JsonObjectBuilder optJson = Json.object();
                    optJson.set(BootstrapConstants.MAPPABLE_NAME, opt.getName());
                    optJson.set(BootstrapConstants.MAPPABLE_JVM_OPTION_GROUP_PREFIX, opt.getPropertyGroupPrefix());
                    optJson.set(BootstrapConstants.MAPPABLE_VALUES, serializeStrings(opt.getValues()));
                    optsArr.append(optJson);
                }
                json.set(BootstrapConstants.MAPPABLE_JVM_OPTIONS, optsArr);
            }
            if (!config.getLockJvmOptions().isEmpty()) {
                json.set(BootstrapConstants.MAPPABLE_LOCK_JVM_OPTIONS, serializeStrings(config.getLockJvmOptions()));
            }
            arr.append(json);
        }
        return arr;
    }

    private static JsonObjectBuilder serializeWorkspaceModule(WorkspaceModule module) {
        final JsonObjectBuilder json = Json.object();
        json.set(BootstrapConstants.MAPPABLE_MODULE_ID, module.getId().toString());
        if (module.getModuleDir() != null) {
            json.set(BootstrapConstants.MAPPABLE_MODULE_DIR, module.getModuleDir().toString());
        }
        if (module.getBuildDir() != null) {
            json.set(BootstrapConstants.MAPPABLE_BUILD_DIR, module.getBuildDir().toString());
        }
        if (!module.getBuildFiles().isEmpty()) {
            final JsonArrayBuilder buildFilesArr = Json.array();
            module.getBuildFiles().forEach(p -> buildFilesArr.append(p.toString()));
            json.set(BootstrapConstants.MAPPABLE_BUILD_FILES, buildFilesArr);
        }
        if (!module.getSourceClassifiers().isEmpty()) {
            final JsonArrayBuilder sourcesArr = Json.array();
            for (String classifier : module.getSourceClassifiers()) {
                sourcesArr.append(serializeArtifactSources(module.getSources(classifier)));
            }
            json.set(BootstrapConstants.MAPPABLE_ARTIFACT_SOURCES, sourcesArr);
        }
        if (module.getParent() != null) {
            json.set(BootstrapConstants.MAPPABLE_PARENT, module.getParent().getId().toString());
        }
        final Collection<String> testExclusions = module.getTestClasspathDependencyExclusions();
        if (testExclusions != null && !testExclusions.isEmpty()) {
            json.set(BootstrapConstants.MAPPABLE_TEST_CP_DEPENDENCY_EXCLUSIONS, serializeStrings(testExclusions));
        }
        final Collection<String> testCpElements = module.getAdditionalTestClasspathElements();
        if (testCpElements != null && !testCpElements.isEmpty()) {
            json.set(BootstrapConstants.MAPPABLE_TEST_ADDITIONAL_CP_ELEMENTS, serializeStrings(testCpElements));
        }
        final Collection<Dependency> depConstraints = module.getDirectDependencyConstraints();
        if (depConstraints != null && !depConstraints.isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (Dependency dep : depConstraints) {
                arr.append(serializeDependency(dep));
            }
            json.set(BootstrapConstants.MAPPABLE_DIRECT_DEP_CONSTRAINTS, arr);
        }
        final Collection<Dependency> directDeps = module.getDirectDependencies();
        if (directDeps != null && !directDeps.isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (Dependency dep : directDeps) {
                arr.append(serializeDependency(dep));
            }
            json.set(BootstrapConstants.MAPPABLE_DIRECT_DEPS, arr);
        }
        return json;
    }

    private static JsonObjectBuilder serializeArtifactSources(ArtifactSources sources) {
        final JsonObjectBuilder json = Json.object();
        json.set(BootstrapConstants.MAPPABLE_CLASSIFIER, sources.getClassifier());
        if (!sources.getSourceDirs().isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (SourceDir dir : sources.getSourceDirs()) {
                arr.append(serializeSourceDir(dir));
            }
            json.set(BootstrapConstants.MAPPABLE_SOURCES, arr);
        }
        if (!sources.getResourceDirs().isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (SourceDir dir : sources.getResourceDirs()) {
                arr.append(serializeSourceDir(dir));
            }
            json.set(BootstrapConstants.MAPPABLE_RESOURCES, arr);
        }
        return json;
    }

    private static JsonObjectBuilder serializeSourceDir(SourceDir dir) {
        final JsonObjectBuilder json = Json.object();
        if (dir.getDir() != null) {
            json.set(BootstrapConstants.MAPPABLE_SRC_DIR, dir.getDir().toString());
        }
        final PathFilter srcFilter = dir.getSourceFilter();
        if (srcFilter != null) {
            json.set(BootstrapConstants.MAPPABLE_SRC_PATH_FILTER, serializePathFilter(srcFilter));
        }
        if (dir.getOutputDir() != null) {
            json.set(BootstrapConstants.MAPPABLE_DEST_DIR, dir.getOutputDir().toString());
        }
        final PathFilter destFilter = dir.getDestinationFilter();
        if (destFilter != null) {
            json.set(BootstrapConstants.MAPPABLE_DEST_PATH_FILTER, serializePathFilter(destFilter));
        }
        if (dir.getAptSourcesDir() != null) {
            json.set(BootstrapConstants.MAPPABLE_APT_SOURCES_DIR, dir.getAptSourcesDir().toString());
        }
        return json;
    }

    private static JsonObjectBuilder serializePathFilter(PathFilter filter) {
        final JsonObjectBuilder json = Json.object();
        if (!filter.getIncludes().isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (Pattern p : filter.getIncludes()) {
                arr.append(p.toString());
            }
            json.set(BootstrapConstants.MAPPABLE_INCLUDES, arr);
        }
        if (!filter.getExcludes().isEmpty()) {
            final JsonArrayBuilder arr = Json.array();
            for (Pattern p : filter.getExcludes()) {
                arr.append(p.toString());
            }
            json.set(BootstrapConstants.MAPPABLE_EXCLUDES, arr);
        }
        return json;
    }

    private static JsonArrayBuilder serializeArtifactKeys(Collection<? extends ArtifactKey> keys) {
        final JsonArrayBuilder arr = Json.array();
        for (ArtifactKey key : keys) {
            arr.append(key.toString());
        }
        return arr;
    }

    private static JsonArrayBuilder serializeStrings(Collection<String> strings) {
        final JsonArrayBuilder arr = Json.array();
        for (String s : strings) {
            arr.append(s);
        }
        return arr;
    }
}
