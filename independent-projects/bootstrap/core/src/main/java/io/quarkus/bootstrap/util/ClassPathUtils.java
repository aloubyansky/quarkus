package io.quarkus.bootstrap.util;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.function.Consumer;

public class ClassPathUtils {

    public static void consumeClasspathResources(String resource, Consumer<Path> consumer)
            throws IOException, URISyntaxException {
        consumeClasspathResources(Thread.currentThread().getContextClassLoader(), resource, consumer);
    }

    public static void consumeClasspathResources(ClassLoader cl, String resource, Consumer<Path> consumer)
            throws IOException, URISyntaxException {
        final Enumeration<URL> resources = cl.getResources(resource);
        while (resources.hasMoreElements()) {
            final URL url = resources.nextElement();
            if ("jar".equals(url.getProtocol())) {
                final String file = url.getFile();
                final Path jar = toPath(new URL(file.substring(0, file.length() - resource.length() - 2)));
                try (FileSystem jarFs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
                    consumer.accept(jarFs.getPath("/").resolve(resource));
                }
            } else if ("file".equals(url.getProtocol())) {
                consumer.accept(toPath(url));
            } else {
                continue;
            }
        }
    }

    public static Path toPath(final URL url) throws URISyntaxException {
        return Paths.get(url.toURI());
    }
}
