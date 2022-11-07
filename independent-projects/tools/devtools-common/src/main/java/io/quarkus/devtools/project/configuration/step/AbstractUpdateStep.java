package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;
import java.util.Objects;

public abstract class AbstractUpdateStep<I, K> implements UpdateStep<I, K> {

    private final I id;
    private final K key;
    private final Path file;

    public AbstractUpdateStep(I id, K key, Path file) {
        this.id = Objects.requireNonNull(id);
        this.key = Objects.requireNonNull(key);
        this.file = Objects.requireNonNull(file);
    }

    @Override
    public I getId() {
        return id;
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public Path getFile() {
        return file;
    }
}
