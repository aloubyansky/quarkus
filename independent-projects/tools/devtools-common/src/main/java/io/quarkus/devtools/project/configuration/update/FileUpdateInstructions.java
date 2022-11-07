package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class FileUpdateInstructions {

    public static class Builder {
        private Path file;
        private List<PomPropertyInstruction> pomProps = new ArrayList<>(4);
        private List<BomInstruction> boms = new ArrayList<>(4);
        private List<DependencyInstruction> deps = new ArrayList<>(4);
        private List<MavenPluginInstruction> mavenPlugins = new ArrayList<>(1);
        private List<UpdateInstruction> unsorted = new ArrayList<>(0);

        private Builder() {
        }

        public Builder setFile(Path file) {
            this.file = file;
            return this;
        }

        public Builder add(UpdateInstruction instruction) {
            if (instruction instanceof PomPropertyInstruction prop) {
                pomProps.add(prop);
            } else if (instruction instanceof BomInstruction bom) {
                boms.add(bom);
            } else if (instruction instanceof DependencyInstruction dep) {
                deps.add(dep);
            } else if (instruction instanceof MavenPluginInstruction plugin) {
                mavenPlugins.add(plugin);
            } else {
                unsorted.add(instruction);
            }
            return this;
        }

        public FileUpdateInstructions build() {
            // sort the instructions just for consistency
            if (!pomProps.isEmpty()) {
                pomProps.sort(Comparator.comparing(PomPropertyInstruction::getPropertyName));
            }
            if (!boms.isEmpty()) {
                boms.sort((bom1, bom2) -> {
                    var key1 = bom1.getKey().bomKey();
                    var key2 = bom2.getKey().bomKey();
                    int i = key1.getGroupId().compareTo(key2.getGroupId());
                    if (i != 0) {
                        return i;
                    }
                    return key1.getArtifactId().compareTo(key2.getArtifactId());
                });
            }
            if (!deps.isEmpty()) {
                deps.sort((dep1, dep2) -> {
                    var key1 = dep1.getKey().artifactKey();
                    var key2 = dep2.getKey().artifactKey();
                    int i = key1.getGroupId().compareTo(key2.getGroupId());
                    if (i != 0) {
                        return i;
                    }
                    i = key1.getArtifactId().compareTo(key2.getArtifactId());
                    if (i != 0) {
                        return i;
                    }
                    i = key1.getClassifier().compareTo(key2.getClassifier());
                    if (i != 0) {
                        return i;
                    }
                    return key1.getType().compareTo(key2.getType());
                });
            }
            if (!mavenPlugins.isEmpty()) {
                mavenPlugins.sort((dep1, dep2) -> {
                    var key1 = dep1.getKey().artifactKey();
                    var key2 = dep2.getKey().artifactKey();
                    int i = key1.getGroupId().compareTo(key2.getGroupId());
                    if (i != 0) {
                        return i;
                    }
                    return key1.getArtifactId().compareTo(key2.getArtifactId());
                });
            }
            return new FileUpdateInstructions(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final Path file;
    private final List<PomPropertyInstruction> pomProps;
    private final List<BomInstruction> boms;
    private final List<DependencyInstruction> deps;
    private final List<MavenPluginInstruction> mavenPlugins;
    private final List<UpdateInstruction> unsorted;

    private FileUpdateInstructions(Builder builder) {
        this.file = Objects.requireNonNull(builder.file, "File is null");
        this.pomProps = builder.pomProps;
        this.boms = builder.boms;
        this.deps = builder.deps;
        this.mavenPlugins = builder.mavenPlugins;
        this.unsorted = builder.unsorted;
    }

    public Path getFile() {
        return file;
    }

    public boolean hasPomProperties() {
        return !pomProps.isEmpty();
    }

    public List<PomPropertyInstruction> getProperties() {
        return pomProps;
    }

    public boolean hasBoms() {
        return !boms.isEmpty();
    }

    public List<BomInstruction> getBoms() {
        return boms;
    }

    public boolean hasDependencies() {
        return !deps.isEmpty();
    }

    public List<DependencyInstruction> getDependencies() {
        return deps;
    }

    public boolean hasMavenPlugins() {
        return !mavenPlugins.isEmpty();
    }

    public List<MavenPluginInstruction> getMavenPlugins() {
        return mavenPlugins;
    }

    public boolean hasUnsorted() {
        return !unsorted.isEmpty();
    }

    public List<UpdateInstruction> getUnsorted() {
        return unsorted;
    }
}
