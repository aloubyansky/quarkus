package io.quarkus.bootstrap.resolver.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.resolution.WorkspaceModelResolver;

import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;

public class BootstrapWorkspaceModelResolver implements WorkspaceModelResolver {

    private final LocalWorkspace ws;

    public BootstrapWorkspaceModelResolver(LocalWorkspace ws) {
        this.ws = ws;
    }

    @Override
    public Model resolveRawModel(String groupId, String artifactId, String versionConstraint)
            throws UnresolvableModelException {
        final LocalProject project = ws.getProject(groupId, artifactId);
        if(project == null || !project.getVersion().equals(versionConstraint)) {
            return null;
        }
        return project.getRawModel();
    }

    @Override
    public Model resolveEffectiveModel(String groupId, String artifactId, String versionConstraint)
            throws UnresolvableModelException {
        return null;
    }
}
