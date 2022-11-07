package io.quarkus.devtools.project.configuration.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;

abstract class AbstractModuleContainer implements ModuleContainer {

    protected final WorkspaceModuleId id;
    protected Model effectiveModel;
    private Map<ArtifactKey, Dependency> allEffectiveManagedDeps;
    private List<RemoteRepository> effectiveRepos;

    public AbstractModuleContainer(WorkspaceModuleId id) {
        this.id = id;
    }

    public WorkspaceModuleId getId() {
        return id;
    }

    protected Map<ArtifactKey, Dependency> getAllEffectiveManagedDeps() {
        if (allEffectiveManagedDeps == null) {
            allEffectiveManagedDeps = ModuleContainer.getManagedDependencies(getEffectiveModel());
        }
        return allEffectiveManagedDeps;
    }

    @Override
    public Model getEffectiveModel() {
        return effectiveModel == null ? effectiveModel = resolveEffectiveModel() : effectiveModel;
    }

    protected abstract Model resolveEffectiveModel();

    @Override
    public boolean isPlatformBomEnforced(ArtifactCoords platformBom) {
        return getAllEffectiveManagedDeps().containsKey(ModuleContainer.getPlatformDescriptorKey(platformBom));
    }

    @Override
    public Dependency getEnforcedVersionConstraintOrNull(ArtifactKey key) {
        return getAllEffectiveManagedDeps().get(key);
    }

    @Override
    public ResolvedValue resolvePropertyValue(String expr) {
        var name = ConfiguredValue.getPropertyName(expr);
        if (name.startsWith("project.")) {
            final String projectProp = name.substring("project.".length());
            switch (projectProp) {
                case "version":
                    return ResolvedValue.of(id.getVersion(), ValueSource.local(id, name, getPomFile()));
                case "groupId":
                    return ResolvedValue.of(id.getGroupId(), ValueSource.local(id, name, getPomFile()));
                case "artifactId":
                    return ResolvedValue.of(id.getArtifactId(), ValueSource.local(id, name, getPomFile()));
            }
        }
        return doResolvePropertyValue(name, expr);
    }

    protected abstract ResolvedValue doResolvePropertyValue(String propertyName, String expr);

    protected abstract Path getPomFile();

    List<RemoteRepository> getEffectiveRepositories() {
        if (effectiveRepos == null) {
            final Model effectiveModel = getEffectiveModel();
            effectiveRepos = new ArrayList<>(effectiveModel.getRepositories().size());
            for (org.apache.maven.model.Repository r : effectiveModel.getRepositories()) {
                final RemoteRepository.Builder rb = new RemoteRepository.Builder(r.getId(), r.getLayout(), r.getUrl())
                        .setContentType(r.getLayout());
                var rp = r.getReleases();
                if (rp != null) {
                    rb.setReleasePolicy(new RepositoryPolicy(Boolean.parseBoolean(rp.getEnabled()),
                            rp.getUpdatePolicy() == null ? RepositoryPolicy.UPDATE_POLICY_NEVER : rp.getUpdatePolicy(),
                            rp.getChecksumPolicy() == null ? RepositoryPolicy.CHECKSUM_POLICY_WARN
                                    : rp.getChecksumPolicy()));
                }
                rp = r.getSnapshots();
                if (rp != null) {
                    rb.setSnapshotPolicy(new RepositoryPolicy(Boolean.parseBoolean(rp.getEnabled()),
                            rp.getUpdatePolicy() == null ? RepositoryPolicy.UPDATE_POLICY_DAILY : rp.getUpdatePolicy(),
                            rp.getChecksumPolicy() == null ? RepositoryPolicy.CHECKSUM_POLICY_WARN
                                    : rp.getChecksumPolicy()));
                }
                effectiveRepos.add(rb.build());
            }
        }
        return effectiveRepos;
    }
}
