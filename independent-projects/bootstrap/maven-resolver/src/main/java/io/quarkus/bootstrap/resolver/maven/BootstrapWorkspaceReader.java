package io.quarkus.bootstrap.resolver.maven;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import io.quarkus.bootstrap.model.AppArtifactCoords;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;

public class BootstrapWorkspaceReader implements WorkspaceReader {

    private final LocalWorkspace ws;
    private final WorkspaceRepository wsRepo = new WorkspaceRepository();
    private AppArtifactKey lastFindVersionsKey;
    private List<String> lastFindVersions;

    public BootstrapWorkspaceReader(LocalWorkspace ws) {
        this.ws = ws;
    }

    @Override
    public WorkspaceRepository getRepository() {
        return wsRepo;
    }

    @Override
    public File findArtifact(Artifact artifact) {
        final LocalProject lp = ws.getProject(artifact.getGroupId(), artifact.getArtifactId());
        if (lp == null || !lp.getVersion().equals(artifact.getVersion())) {
            return null;
        }
        final String type = artifact.getExtension();
        if (type.equals(AppArtifactCoords.TYPE_JAR)) {
            final File file = lp.getClassesDir().toFile();
            if (file.exists()) {
                return file;
            }
        } else if (type.equals(AppArtifactCoords.TYPE_POM)) {
            final File file = lp.getDir().resolve("pom.xml").toFile();
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        if (lastFindVersionsKey != null && artifact.getVersion().equals(lastFindVersions.get(0))
                && lastFindVersionsKey.getArtifactId().equals(artifact.getArtifactId())
                && lastFindVersionsKey.getGroupId().equals(artifact.getGroupId())) {
            return lastFindVersions;
        }
        lastFindVersionsKey = new AppArtifactKey(artifact.getGroupId(), artifact.getArtifactId());
        final LocalProject lp = ws.getProject(lastFindVersionsKey);
        if (lp == null || !lp.getVersion().equals(artifact.getVersion())) {
            lastFindVersionsKey = null;
            return Collections.emptyList();
        }
        return lastFindVersions = Collections.singletonList(artifact.getVersion());
    }
}
