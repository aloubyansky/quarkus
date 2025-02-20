package io.quarkus.devtools.project.configuration.maven;

import java.util.HashMap;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.util.PlatformArtifacts;

interface ModuleContainer {

    static Map<ArtifactKey, Dependency> getManagedDependencies(Model model) {
        var dm = model.getDependencyManagement();
        if (dm == null) {
            return Map.of();
        }
        var result = new HashMap<ArtifactKey, org.apache.maven.model.Dependency>(dm.getDependencies().size());
        for (var d : dm.getDependencies()) {
            result.put(getKey(d), d);
        }
        return result;
    }

    static ArtifactKey getKey(org.apache.maven.model.Dependency d) {
        return ArtifactKey.of(d.getGroupId(), d.getArtifactId(),
                d.getClassifier() == null ? ArtifactCoords.DEFAULT_CLASSIFIER : d.getClassifier(),
                d.getType() == null ? ArtifactCoords.TYPE_JAR : d.getType());
    }

    static ArtifactKey getPlatformDescriptorKey(ArtifactCoords platformBom) {
        return ArtifactKey.of(platformBom.getGroupId(), PlatformArtifacts.ensureCatalogArtifactId(platformBom.getArtifactId()),
                platformBom.getVersion(), "json");
    }

    /**
     * Module GAV.
     *
     * @return module GAV
     */
    WorkspaceModuleId getId();

    /**
     * Indicates whether a module belongs to a local project or is an external one.
     *
     * @return true if the module belongs to a local project, otherwise - false
     */
    boolean isProjectModule();

    default ResolvedValue resolveValue(String expr) {
        return ConfiguredValue.isPropertyExpression(expr) ? resolvePropertyValue(expr) : resolveLiteralValue(expr);
    }

    default ResolvedValue resolveLiteralValue(String value) {
        return ResolvedValue.of(value, getValueSource());
    }

    ResolvedValue resolvePropertyValue(String expr);

    ValueSource getValueSource();

    ValueSource getValueSource(String propertyName);

    boolean isPlatformBomEnforced(ArtifactCoords platformBom);
}
