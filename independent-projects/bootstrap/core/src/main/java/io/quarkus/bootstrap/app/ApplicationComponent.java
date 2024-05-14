package io.quarkus.bootstrap.app;

import java.nio.file.Path;
import java.util.Objects;

public class ApplicationComponent {

    public static Builder builder() {
        return new ApplicationComponent().new Builder();
    }

    public class Builder {

        private boolean built;

        private Builder() {
        }

        public Builder setPath(Path componentPath) {
            ensureNotBuilt();
            path = componentPath;
            return this;
        }

        public ApplicationComponent build() {
            ensureNotBuilt();
            Objects.requireNonNull(path, "path is null");
            built = true;
            return ApplicationComponent.this;
        }

        private void ensureNotBuilt() {
            if (built) {
                throw new RuntimeException("This builder instance has already been built");
            }
        }
    }

    private ApplicationComponent() {
    }

    private Path path;

    public Path getPath() {
        return path;
    }
}
