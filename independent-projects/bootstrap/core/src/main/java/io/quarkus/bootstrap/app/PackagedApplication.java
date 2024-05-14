package io.quarkus.bootstrap.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class PackagedApplication {

    public static Builder builder() {
        return new PackagedApplication().new Builder();
    }

    public class Builder {

        private boolean built;

        private Builder() {
        }

        public Builder setRunner(ApplicationComponent applicationRunner) {
            ensureNotBuilt();
            runner = applicationRunner;
            return this;
        }

        public Builder setDistributionDirectory(Path distributionDirectory) {
            ensureNotBuilt();
            distDir = distributionDirectory;
            return this;
        }

        public Builder addComponent(ApplicationComponent component) {
            if (components.isEmpty()) {
                components = new ArrayList<>();
            }
            components.add(component);
            return this;
        }

        public PackagedApplication build() {
            ensureNotBuilt();
            Objects.requireNonNull(runner, "runner is null");
            built = true;
            return PackagedApplication.this;
        }

        private void ensureNotBuilt() {
            if (built) {
                throw new RuntimeException("This builder instance has already been built");
            }
        }
    }

    private PackagedApplication() {
    }

    private Path distDir;
    private ApplicationComponent runner;
    private List<ApplicationComponent> components = List.of();

    public Path getDistributionDirectory() {
        return distDir;
    }

    public ApplicationComponent getRunner() {
        return runner;
    }

    public Collection<ApplicationComponent> getComponents() {
        return components;
    }
}
