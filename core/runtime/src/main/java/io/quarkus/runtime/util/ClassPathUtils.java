package io.quarkus.runtime.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.function.Consumer;
import java.util.function.Function;

public class ClassPathUtils {

    public static void consumeAsStreams(String resource, Consumer<InputStream> consumer) throws IOException {
        consumeAsStreams(Thread.currentThread().getContextClassLoader(), resource, consumer);
    }

    public static void consumeAsStreams(ClassLoader cl, String resource, Consumer<InputStream> consumer) throws IOException {
        final Enumeration<URL> resources = cl.getResources(resource);
        while (resources.hasMoreElements()) {
            consumeStream(resources.nextElement(), consumer);
        }
    }

    public static void consumeAsPaths(String resource, Consumer<Path> consumer) throws IOException {
        consumeAsPaths(Thread.currentThread().getContextClassLoader(), resource, consumer);
    }

    public static void consumeAsPaths(ClassLoader cl, String resource, Consumer<Path> consumer) throws IOException {
        final Enumeration<URL> resources = cl.getResources(resource);
        while (resources.hasMoreElements()) {
            consumeAsPath(resources.nextElement(), consumer);
        }
    }

    public static void consumeAsPath(URL url, Consumer<Path> consumer) {
        processAsPath(url, p -> {
            consumer.accept(p);
            return null;
        });
    }

    public static <R> R processAsPath(URL url, Function<Path, R> function) {
        if ("jar".equals(url.getProtocol())) {
            final String file = url.getFile();
            final int exclam = file.lastIndexOf('!');
            final Path jar;
            try {
                jar = toLocalPath(exclam >= 0 ? new URL(file.substring(0, exclam)) : url);
            } catch (MalformedURLException e) {
                throw new RuntimeException("Failed to create a URL for '" + file.substring(0, exclam) + "'", e);
            }
            try (FileSystem jarFs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
                Path localPath = jarFs.getPath("/");
                if (exclam >= 0) {
                    localPath = localPath.resolve(file.substring(exclam + 1));
                }
                return function.apply(localPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + jar, e);
            }
        }

        if ("file".equals(url.getProtocol())) {
            return function.apply(toLocalPath(url));
        }

        if ("resource".equals(url.getProtocol())) {
            return function.apply(toLocalPath(url));
        }

        throw new IllegalArgumentException("Unexpected protocol " + url.getProtocol() + " for URL " + url);
    }

    public static void consumeStream(URL url, Consumer<InputStream> consumer) throws IOException {
        readStream(url, is -> {
            consumer.accept(is);
            return null;
        });
    }

    public static <R> R readStream(URL url, Function<InputStream, R> function) throws IOException {
        if ("jar".equals(url.getProtocol())) {
            final String file = url.getFile();
            final int exclam = file.lastIndexOf('!');
            final Path jar = toLocalPath(exclam >= 0 ? new URL(file.substring(0, exclam)) : url);
            try (FileSystem jarFs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
                try (InputStream is = Files.newInputStream(jarFs.getPath(file.substring(exclam + 1)))) {
                    return function.apply(is);
                }
            }
        }
        if ("file".equals(url.getProtocol())) {
            try (InputStream is = Files.newInputStream(toLocalPath(url))) {
                return function.apply(is);
            }
        }
        try (InputStream is = url.openStream()) {
            return function.apply(is);
        }
    }

    public static Path toLocalPath(final URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Failed to translate " + url + " to local path", e);
        }
    }
}
