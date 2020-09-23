package io.quarkus.platform.descriptor.loader.json.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import io.quarkus.platform.descriptor.ResourcePathConsumer;
import io.quarkus.platform.descriptor.loader.json.DirectoryResourceLoader;
import io.quarkus.platform.descriptor.loader.json.ResourceLoader;
import io.quarkus.platform.descriptor.loader.json.ZipResourceLoader;

class PathResourceLoader implements ResourceLoader {

    private final Supplier<Path> pathSupplier;
    private ResourceLoader delegate;

    PathResourceLoader(Supplier<Path> pathSupplier) {
        this.pathSupplier = pathSupplier;
    }

    private ResourceLoader delegate() {
        if (delegate != null) {
            return delegate;
        }
        final Path path = pathSupplier.get();
        return delegate = Files.isDirectory(path) ? new DirectoryResourceLoader(path) : new ZipResourceLoader(path);
    }

    @Override
    public <T> T loadResourceAsPath(String name, ResourcePathConsumer<T> consumer) throws IOException {
        return delegate().loadResourceAsPath(name, consumer);
    }
}
