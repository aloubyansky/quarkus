package io.quarkus.devtools.project.update;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jboss.logging.Logger;

import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;

public class QuarkusPomUpdateRecipe {

    private static final String NAME = QuarkusPomUpdateRecipe.class.getName();

    public static Generator generator() {
        return new Generator();
    }

    public static class Generator {

        private Path pom;
        private final Map<String, UpdateProperty> propUpdates = new HashMap<>(3);
        private final Map<ArtifactKey, UpdateManagedDependency> updatedManagedDeps = new HashMap<>(3);
        private final Map<ArtifactKey, RemoveManagedDependency> removedManagedDeps = new HashMap<>(1);
        private final Map<ArtifactKey, AddManagedDependency> addedManagedDeps = new HashMap<>(3);
        private final Map<ArtifactKey, UpdateDependency> updatedDeps = new HashMap<>(3);
        private final Map<ArtifactKey, UpdatePluginVersion> pluginVersionUpdates = new HashMap<>(0);

        private Generator() {
        }

        public Generator setPom(Path pom) {
            this.pom = pom;
            return this;
        }

        public Generator updateProperty(String name, String newValue) {
            return updateProperty(name, newValue, false);
        }

        public Generator updateProperty(String name, String newValue, boolean addIfMissing) {
            final UpdateProperty update = new UpdateProperty(name, newValue, addIfMissing, false);
            final UpdateProperty prev = propUpdates.put(update.getKey(), update);
            if (prev != null && prev.isConflicting(update)) {
                Logger.getLogger(getClass()).warn("Caught conflicting update of property " + name + " in " + pom
                        + " with values " + newValue + " and " + prev.newValue);
            }
            return this;
        }

        public Generator updateManagedDependency(String oldGroupId, String oldArtifactId, String newGroupId,
                String newArtifactId, String newVersion) {
            return updateManagedDependency(oldGroupId, oldArtifactId, newGroupId, newArtifactId, newVersion, null);
        }

        public Generator updateManagedDependency(String oldGroupId, String oldArtifactId, String newGroupId,
                String newArtifactId, String newVersion, String versionPattern) {
            final UpdateManagedDependency update = new UpdateManagedDependency(oldGroupId, oldArtifactId, newGroupId,
                    newArtifactId, newVersion, versionPattern);
            final UpdateManagedDependency prev = updatedManagedDeps.put(update.getKey(), update);
            if (prev != null && prev.isConflicting(update)) {
                Logger.getLogger(getClass())
                        .warn("Caught conflicting update of managed dependency " + update.getKey() + " in " + pom
                                + " with values " + newGroupId + ":" + newArtifactId + ":" + newVersion
                                + " and version pattern " + versionPattern);
            }
            return this;
        }

        public Generator removeManagedDependency(String groupId, String artifactId, String scope) {
            final RemoveManagedDependency update = new RemoveManagedDependency(groupId, artifactId, scope);
            removedManagedDeps.put(update.getKey(), update);
            return this;
        }

        public Generator addManagedDependency(String groupId, String artifactId, String classifier,
                String type, String version, String scope) {
            final AddManagedDependency update = new AddManagedDependency(groupId, artifactId, classifier,
                    type, version, scope);
            final AddManagedDependency prev = addedManagedDeps.put(update.getKey(), update);
            if (prev != null && prev.isConflicting(update)) {
                Logger.getLogger(getClass())
                        .warn("Caught conflicting addition of managed dependency " + update.getKey() + " in " + pom
                                + " with values " + update.getKey() + ":" + version
                                + " and scope " + scope);
            }
            return this;
        }

        public Generator updateDependency(String oldGroupId, String oldArtifactId, String newGroupId,
                String newArtifactId, String newVersion) {
            final UpdateDependency update = new UpdateDependency(oldGroupId, oldArtifactId, newGroupId, newArtifactId,
                    newVersion);
            final UpdateDependency prev = updatedDeps.put(update.getKey(), update);
            if (prev != null && prev.isConflicting(update)) {
                Logger.getLogger(getClass())
                        .warn("Caught conflicting update of dependency " + update.getKey() + " in " + pom
                                + " with values " + newGroupId + ":" + newArtifactId + ":" + newVersion);
            }
            return this;
        }

        public Generator updatePluginVersion(String groupId, String artifactId, String newVersion) {
            final UpdatePluginVersion update = new UpdatePluginVersion(groupId, artifactId, newVersion);
            final UpdatePluginVersion prev = pluginVersionUpdates.put(update.getKey(), update);
            if (prev != null && prev.isConflicting(update)) {
                Logger.getLogger(getClass())
                        .warn("Caught conflicting update of plugin " + update.getKey() + " in " + pom
                                + " with values " + newVersion + " and " + prev.newVersion);
            }
            return this;
        }

        public QuarkusPomUpdateRecipe generate() {
            final StringWriter writer = new StringWriter();
            try (StringWriter w = writer) {
                println(w, "type: specs.openrewrite.org/v1beta/recipe");
                println(w, "name: " + NAME);
                println(w, "displayName: Update Quarkus project");
                println(w, "recipeList:");
                for (UpdateProperty pu : propUpdates.values()) {
                    pu.addToRecipe(w);
                }
                for (UpdateManagedDependency pu : updatedManagedDeps.values()) {
                    pu.addToRecipe(w);
                }
                for (RemoveManagedDependency pu : removedManagedDeps.values()) {
                    pu.addToRecipe(w);
                }
                for (AddManagedDependency pu : addedManagedDeps.values()) {
                    pu.addToRecipe(w);
                }
                for (UpdateDependency pu : updatedDeps.values()) {
                    pu.addToRecipe(w);
                }
                for (UpdatePluginVersion pu : pluginVersionUpdates.values()) {
                    pu.addToRecipe(w);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to write a recipe", e);
            }
            return new QuarkusPomUpdateRecipe(pom, writer.getBuffer().toString());
        }
    }

    private final Path pom;
    private final String recipe;

    private QuarkusPomUpdateRecipe(Path pom, String recipe) {
        this.pom = Objects.requireNonNull(pom);
        this.recipe = Objects.requireNonNull(recipe);
    }

    public String getName() {
        return NAME;
    }

    public Path getPom() {
        return pom;
    }

    public Path getProjectDir() {
        final Path parent = pom.getParent();
        return parent == null ? Path.of("") : parent;
    }

    public String toYaml() {
        return recipe;
    }

    @Override
    public String toString() {
        return recipe;
    }

    private static class UpdateManagedDependency {
        final String oldGroupId;
        final String oldArtifactId;
        final String newGroupId;
        final String newArtifactId;
        final String newVersion;
        final String versionPattern;

        private UpdateManagedDependency(String oldGroupId, String oldArtifactId, String newGroupId, String newArtifactId,
                String newVersion, String versionPattern) {
            super();
            this.oldGroupId = oldGroupId;
            this.oldArtifactId = oldArtifactId;
            this.newGroupId = newGroupId;
            this.newArtifactId = newArtifactId;
            this.newVersion = newVersion;
            this.versionPattern = versionPattern;
        }

        private ArtifactKey getKey() {
            return ArtifactKey.ga(oldGroupId, oldArtifactId);
        }

        private boolean isConflicting(UpdateManagedDependency other) {
            return !Objects.equals(newGroupId, other.newGroupId)
                    || !Objects.equals(newArtifactId, other.newArtifactId)
                    || !Objects.equals(newVersion, other.newVersion)
                    || !Objects.equals(versionPattern, other.versionPattern);
        }

        private void addToRecipe(Writer w) throws IOException {
            println(w, "  - org.openrewrite.maven.ChangeManagedDependencyGroupIdAndArtifactId:");
            println(w, "      oldGroupId: " + oldGroupId);
            println(w, "      oldArtifactId: " + oldArtifactId);
            println(w, "      newGroupId: " + (newGroupId != null && !newGroupId.equals(oldGroupId) ? newGroupId : oldGroupId));
            println(w, "      newArtifactId: "
                    + (newArtifactId != null && !newArtifactId.equals(oldArtifactId) ? newArtifactId : oldArtifactId));
            if (newVersion != null) {
                println(w, "      newVersion: " + newVersion);
                if (versionPattern != null) {
                    println(w, "      versionPattern: " + versionPattern);
                }
            }
        }
    }

    private static class RemoveManagedDependency {
        final String groupId;
        final String artifactId;
        final String scope;

        private RemoveManagedDependency(String groupId, String artifactId, String scope) {
            super();
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.scope = scope;
        }

        private ArtifactKey getKey() {
            return ArtifactKey.ga(groupId, artifactId);
        }

        private boolean isConflicting(UpdateManagedDependency other) {
            return false;
        }

        private void addToRecipe(Writer w) throws IOException {
            println(w, "  - org.openrewrite.maven.RemoveManagedDependency:");
            println(w, "      groupId: " + groupId);
            println(w, "      artifactId: " + artifactId);
            if (scope != null) {
                println(w, "      scope: " + scope);
            }
        }
    }

    private static class AddManagedDependency {
        final String groupId;
        final String artifactId;
        final String classifier;
        final String type;
        final String version;
        final String scope;

        private AddManagedDependency(String groupId, String artifactId, String classifier, String type, String version,
                String scope) {
            super();
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.classifier = classifier;
            this.type = type;
            this.version = version;
            this.scope = scope;
        }

        private ArtifactKey getKey() {
            return ArtifactKey.of(groupId, artifactId, classifier, type);
        }

        private boolean isConflicting(AddManagedDependency other) {
            return !Objects.equals(version, other.version)
                    || Objects.equals(scope, other.scope);
        }

        private void addToRecipe(Writer w) throws IOException {
            println(w, "  - org.openrewrite.maven.AddManagedDependency:");
            println(w, "      groupId: " + groupId);
            println(w, "      artifactId: " + artifactId);
            if (classifier != null && !classifier.isEmpty()) {
                println(w, "      classifier: " + classifier);
            }
            if (type != null && !type.isEmpty() && !type.equals(ArtifactCoords.TYPE_JAR)) {
                println(w, "      type: " + type);
            }
            println(w, "      version: " + version);
            if (scope != null) {
                println(w, "      scope: " + scope);
            }
        }
    }

    private static class UpdateDependency {
        final String oldGroupId;
        final String oldArtifactId;
        final String newGroupId;
        final String newArtifactId;
        final String newVersion;

        private UpdateDependency(String oldGroupId, String oldArtifactId, String newGroupId, String newArtifactId,
                String newVersion) {
            super();
            this.oldGroupId = oldGroupId;
            this.oldArtifactId = oldArtifactId;
            this.newGroupId = newGroupId;
            this.newArtifactId = newArtifactId;
            this.newVersion = newVersion;
        }

        private ArtifactKey getKey() {
            return ArtifactKey.ga(oldGroupId, oldArtifactId);
        }

        private boolean isConflicting(UpdateDependency other) {
            return !Objects.equals(newVersion, other.newVersion)
                    || Objects.equals(newGroupId, other.newGroupId)
                    || Objects.equals(newArtifactId, other.newArtifactId);
        }

        private void addToRecipe(Writer w) throws IOException {
            println(w, "  - org.openrewrite.maven.ChangeDependencyGroupIdAndArtifactId:");
            println(w, "      oldGroupId: " + oldGroupId);
            println(w, "      oldArtifactId: " + oldArtifactId);
            if (newGroupId != null && !newGroupId.isEmpty()) {
                println(w, "      newGroupId: " + newGroupId);
            }
            if (newArtifactId != null && !newArtifactId.isEmpty()) {
                println(w, "      newArtifactId: " + newArtifactId);
            }
            println(w, "      newVersion: " + newVersion);
        }
    }

    private static class UpdatePluginVersion {
        final String groupId;
        final String artifactId;
        final String newVersion;

        private UpdatePluginVersion(String groupId, String artifactId, String newVersion) {
            super();
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.newVersion = newVersion;
        }

        private ArtifactKey getKey() {
            return ArtifactKey.ga(groupId, artifactId);
        }

        private boolean isConflicting(UpdatePluginVersion other) {
            return !Objects.equals(newVersion, other.newVersion);
        }

        private void addToRecipe(Writer w) throws IOException {
            println(w, "  - org.openrewrite.maven.UpgradePluginVersion:");
            println(w, "      oldGroupId: " + groupId);
            println(w, "      oldArtifactId: " + artifactId);
            println(w, "      newVersion: " + newVersion);
        }
    }

    private static class UpdateProperty {

        final String key;
        final String newValue;
        final boolean addIfMissing;
        final boolean trustParent;

        private UpdateProperty(String key, String newValue, boolean addIfMissing, boolean trustParent) {
            this.key = key;
            this.newValue = newValue;
            this.addIfMissing = addIfMissing;
            this.trustParent = trustParent;
        }

        private String getKey() {
            return key;
        }

        private boolean isConflicting(UpdateProperty other) {
            return !newValue.equals(other.newValue);
        }

        private void addToRecipe(Writer w) throws IOException {
            println(w, "  - org.openrewrite.maven.ChangePropertyValue:");
            println(w, "      key: " + key);
            println(w, "      newValue: " + newValue);
            println(w, "      addIfMissing: " + addIfMissing);
            println(w, "      trustParent: " + trustParent);
        }
    }

    private static void println(Writer writer, String line) throws IOException {
        writer.write(line);
        writer.write(System.lineSeparator());
    }
}
