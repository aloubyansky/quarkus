package io.quarkus.bootstrap.resolver.maven.workspace;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.bootstrap.model.AppArtifactKey;

/**
 *
 * @author Alexey Loubyansky
 */
public class LocalWorkspace {

    private final Map<AppArtifactKey, LocalProject> projects = new HashMap<>();

    private long lastModified;
    private int id = 1;

    protected void addProject(LocalProject project, long lastModified) {
        projects.put(project.getKey(), project);
        if(lastModified > this.lastModified) {
            this.lastModified = lastModified;
        }
        id = 31 * id + (int) (lastModified ^ (lastModified >>> 32));
    }

    public LocalProject getProject(String groupId, String artifactId) {
        return getProject(new AppArtifactKey(groupId, artifactId));
    }

    public LocalProject getProject(AppArtifactKey key) {
        return projects.get(key);
    }

    public long getLastModified() {
        return lastModified;
    }

    public int getId() {
        return id;
    }

    public Map<AppArtifactKey, LocalProject> getProjects() {
        return projects;
    }
}
