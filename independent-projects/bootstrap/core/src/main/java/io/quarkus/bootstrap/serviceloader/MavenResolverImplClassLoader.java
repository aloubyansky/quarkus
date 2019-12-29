package io.quarkus.bootstrap.serviceloader;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.aether.RepositorySystem;

import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;

/**
 * Class loader that is loading classes necessary to initialize bootstrap Maven artifact resolver classes.
 *
 */
public class MavenResolverImplClassLoader extends ClassLoader implements Closeable {

    // Class loader that is able to load the Maven Resolver API classes
    // In case this is called from a Maven plugins this classloader should be able to load the impl classes as well
    private final ClassLoader mvnResolverApi;
    // Class loader that is loading classes from the Maven home lib directory
    private URLClassLoader mvnHomeLib;
    // This class loader is loading MavenRepoInitializer classes and only those classes
    private final ClassLoader isolatedService;

    public MavenResolverImplClassLoader(ClassLoader isolatedService, ClassLoader parent) {
        super(parent);
        this.isolatedService = isolatedService;
        this.mvnResolverApi = RepositorySystem.class.getClassLoader();
    }

    @Override
    public URL getResource(String name) {
        URL url = isolatedService.getResource(name);
        if (url != null) {
            return url;
        }
        url = mvnResolverApi.getResource(name);
        if (url != null) {
            return url;
        }
        url = super.getResource(name);
        if(url != null) {
            return url;
        }
        final ClassLoader mvnHomeLib = getMvnHomeLib();
        return mvnHomeLib == null ? null : mvnHomeLib.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Enumeration<URL> urls = isolatedService.getResources(name);
        if (urls.hasMoreElements()) {
            return urls;
        }
        urls = mvnResolverApi.getResources(name);
        if (urls.hasMoreElements()) {
            return urls;
        }
        return super.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream is = isolatedService.getResourceAsStream(name);
        if (is != null) {
            return is;
        }
        is = mvnResolverApi.getResourceAsStream(name);
        if (is != null) {
            return is;
        }
        is = super.getResourceAsStream(name);
        if(is != null) {
            return is;
        }
        //System.out.println("getResourceAsStream " + name);
        final ClassLoader mvnHomeLib = getMvnHomeLib();
        return mvnHomeLib == null ? null : mvnHomeLib.getResourceAsStream(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("java.") || name.startsWith("javax.")) {
            return super.loadClass(name, resolve);
        }
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            // ignore
        } catch (Error e) {
            // potential race conditions if another thread is loading the same class
            final Class<?> existing = findLoadedClass(name);
            if (existing != null) {
                return existing;
            }
        }
        try {
            return mvnResolverApi.loadClass(name);
        } catch (ClassNotFoundException e) {
        }
        try {
            return super.loadClass(name, resolve);
        } catch (ClassNotFoundException e) {
        }
        final ClassLoader mvnLib = getMvnHomeLib();
        if (mvnLib == null) {
            throw new ClassNotFoundException(name);
        }
        return findClass(mvnLib, name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        final Class<?> existing = findLoadedClass(name);
        if (existing != null) {
            return existing;
        }
        return findClass(isolatedService, name);
    }

    private Class<?> findClass(ClassLoader source, String name) throws ClassNotFoundException, ClassFormatError {
        final String resourcePath = name.replace('.', '/') + ".class";
        final InputStream is = source.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            final byte[] bytes = readAll(is);
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException("Failed to read class file " + resourcePath, e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
            }
        }
    }

    private ClassLoader getMvnHomeLib() {
        if (mvnHomeLib != null) {
            return mvnHomeLib;
        }
        final String mavenHome = MavenArtifactResolver.getMavenHome();
        if (mavenHome == null) {
            return null;
        }
        final Path mvnLib = Paths.get(mavenHome).resolve("lib");
        if (!Files.exists(mvnLib)) {
            throw new IllegalStateException("Maven lib dir " + mvnLib + " does not exist");
        }
        List<URL> urls;
        try {
            urls = Files.list(mvnLib).map(path -> {
                try {
                    return path.toUri().toURL();
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException("Failed to translate " + path + " to URL", e);
                }
            }).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read directory " + mvnLib, e);
        }
        mvnHomeLib = new URLClassLoader(urls.toArray(new URL[urls.size()]), null);
        return mvnHomeLib;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] data = new byte[16384];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    @Override
    public void close() throws IOException {
        if(mvnHomeLib != null) {
            mvnHomeLib.close();
        }
    }
}
