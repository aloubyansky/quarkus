package io.quarkus.deployment.dev;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.Vector;
import java.util.function.Supplier;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import io.quarkus.deployment.dev.CompilationProvider.Context;
import io.quarkus.runtime.util.ClassPathUtils;

public class ReloadableJavaFileManager implements StandardJavaFileManager {

    private final Charset charset;
    private final JavaCompiler compiler;
    private final StandardJavaFileManager staticFM;
    private final DiagnosticCollector<JavaFileObject> diagnosticsCollector;
    private StandardJavaFileManager reloadableFM;

    protected ReloadableJavaFileManager(JavaCompiler compiler, DiagnosticCollector<JavaFileObject> diagnosticsCollector,
            Context context) {
        this.compiler = compiler;
        this.charset = context.getSourceEncoding();
        this.staticFM = compiler.getStandardFileManager(diagnosticsCollector, null, charset);
        this.diagnosticsCollector = diagnosticsCollector;
    }

    public void setClassOutput(Iterable<File> files) throws IOException {
        staticFM.setLocation(StandardLocation.CLASS_OUTPUT, files);
    }

    public void setStaticClassPath(Iterable<? extends File> files) throws IOException {
        staticFM.setLocation(StandardLocation.CLASS_PATH, files);
    }

    public void setReloadableClassPath(Collection<File> files) throws IOException {
        if (reloadableFM != null) {
            reloadableFM.close();
            reloadableFM = null;
        }
        if (!files.isEmpty()) {
            reloadableFM = compiler.getStandardFileManager(diagnosticsCollector, null, charset);
            reloadableFM.setLocation(StandardLocation.CLASS_PATH, files);
        }
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        return staticFM.getJavaFileObjectsFromFiles(files);
    }

    @Override
    public int isSupportedOption(String option) {
        return staticFM.isSupportedOption(option);
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
        System.out.println("ReloadableJavaFileManager.getClassLoader " + location);
        if (reloadableFM == null) {
            return staticFM.getClassLoader(location);
        }
        ClassLoader staticCl = staticFM.getClassLoader(location);
        if (staticCl == null) {
            return reloadableFM.getClassLoader(location);
        }
        final ClassLoader reloadableCl = reloadableFM.getClassLoader(location);
        if (reloadableCl == null) {
            return staticCl;
        }
        System.out.println("  merging");
        return new JoinClassLoader(staticCl.getParent(), staticCl, reloadableCl);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse)
            throws IOException {
        return combinedIterable(() -> {
            try {
                return staticFM.list(location, packageName, kinds, recurse);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, reloadableFM == null ? null : () -> {
            try {
                return reloadableFM.list(location, packageName, kinds, recurse);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        String result = staticFM.inferBinaryName(location, file);
        if (result == null && reloadableFM != null) {
            return reloadableFM.inferBinaryName(location, file);
        }
        return result;
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return staticFM.isSameFile(a, b);
    }

    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        boolean result = staticFM.handleOption(current, remaining);
        if (!result && reloadableFM != null) {
            result = reloadableFM.handleOption(current, remaining);
        }
        return result;
    }

    @Override
    public boolean hasLocation(Location location) {
        return staticFM.hasLocation(location) || reloadableFM != null && reloadableFM.hasLocation(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        JavaFileObject result = staticFM.getJavaFileForInput(location, className, kind);
        if (result == null && reloadableFM != null) {
            result = reloadableFM.getJavaFileForInput(location, className, kind);
        }
        return result;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling)
            throws IOException {
        JavaFileObject result = staticFM.getJavaFileForOutput(location, className, kind, sibling);
        if (result == null && reloadableFM != null) {
            result = reloadableFM.getJavaFileForOutput(location, className, kind, sibling);
        }
        return result;
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        FileObject result = staticFM.getFileForInput(location, packageName, relativeName);
        if (result == null && reloadableFM != null) {
            result = reloadableFM.getFileForInput(location, packageName, relativeName);
        }
        return result;
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling)
            throws IOException {
        FileObject result = staticFM.getFileForOutput(location, packageName, relativeName, sibling);
        if (result == null && reloadableFM != null) {
            result = reloadableFM.getFileForOutput(location, packageName, relativeName, sibling);
        }
        return result;
    }

    @Override
    public void flush() throws IOException {
        staticFM.close();
        if (reloadableFM != null) {
            reloadableFM.close();
        }
    }

    @Override
    public void close() throws IOException {
        staticFM.close();
        if (reloadableFM != null) {
            reloadableFM.close();
            reloadableFM = null;
        }
    }

    @Override
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        if (reloadableFM == null) {
            return staticFM.getLocationForModule(location, moduleName);
        }
        final Location loc = staticFM.getLocationForModule(location, moduleName);
        return loc != null ? loc : reloadableFM.getLocationForModule(location, moduleName);
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject fo) throws IOException {
        if (reloadableFM == null) {
            return staticFM.getLocationForModule(location, fo);
        }
        final Location loc = staticFM.getLocationForModule(location, fo);
        return loc != null ? loc : reloadableFM.getLocationForModule(location, fo);

    }

    @Override
    public <S> ServiceLoader<S> getServiceLoader(Location location, Class<S> service) throws IOException {
        if (reloadableFM == null) {
            return staticFM.getServiceLoader(location, service);
        }
        final ServiceLoader<S> result = staticFM.getServiceLoader(location, service);
        return result != null ? result : reloadableFM.getServiceLoader(location, service);
    }

    @Override
    public String inferModuleName(Location location) throws IOException {
        if (reloadableFM == null) {
            return staticFM.inferModuleName(location);
        }
        final String result = staticFM.inferModuleName(location);
        return result != null ? result : reloadableFM.inferModuleName(location);
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        return combinedIterable(() -> {
            try {
                return staticFM.listLocationsForModules(location);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, reloadableFM == null ? null : () -> {
            try {
                return reloadableFM.listLocationsForModules(location);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private <T> Iterable<T> combinedIterable(Supplier<Iterable<T>> required, Supplier<Iterable<T>> optional) {
        if (optional == null) {
            return required.get();
        }
        final Iterable<T> requiredI = required.get();
        if (requiredI == null) {
            return optional.get();
        }
        final Iterable<T> optionalI = optional.get();
        if (optionalI == null) {
            return requiredI;
        }
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                final Iterator<T> ri = requiredI.iterator();
                final Iterator<T> oi = optionalI.iterator();
                if (!ri.hasNext()) {
                    return oi;
                }
                if (!oi.hasNext()) {
                    return ri;
                }
                return new Iterator<T>() {

                    boolean currentIsStatic = true;

                    @Override
                    public boolean hasNext() {
                        return ri.hasNext() || oi.hasNext();
                    }

                    @Override
                    public T next() {
                        if (currentIsStatic = ri.hasNext()) {
                            return ri.next();
                        }
                        return oi.next();
                    }

                    @Override
                    public void remove() {
                        if (currentIsStatic) {
                            ri.remove();
                        } else {
                            oi.remove();
                        }
                    }
                };
            }
        };
    }

    @Override
    public boolean contains(Location location, FileObject fo) throws IOException {
        return staticFM.contains(location, fo) || reloadableFM != null && reloadableFM.contains(location, fo);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return staticFM.getJavaFileObjects(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        return staticFM.getJavaFileObjectsFromStrings(names);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return staticFM.getJavaFileObjects(names);
    }

    @Override
    public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        staticFM.setLocation(location, files);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterable<File> getLocation(Location location) {
        return combinedIterable(() -> ((Iterable<File>) staticFM.getLocation(location)),
                reloadableFM == null ? null : () -> (Iterable<File>) reloadableFM.getLocation(location));
    }

    /**
     * A class loader that combines multiple class loaders into one.<br>
     * The classes loaded by this class loader are associated with this class loader,
     * i.e. Class.getClassLoader() points to this class loader.
     */
    public static class JoinClassLoader extends ClassLoader {

        private ClassLoader[] delegateClassLoaders;

        public JoinClassLoader(ClassLoader parent, ClassLoader... delegateClassLoaders) {
            super(parent);
            this.delegateClassLoaders = delegateClassLoaders;
        }

        protected Class<?> findClass(String name) throws ClassNotFoundException {
            // It would be easier to call the loadClass() methods of the delegateClassLoaders
            // here, but we have to load the class from the byte code ourselves, because we
            // need it to be associated with our class loader.
            String path = name.replace('.', '/') + ".class";
            URL url = findResource(path);
            if (url == null) {
                throw new ClassNotFoundException(name);
            }
            ByteBuffer byteCode;
            try {
                byteCode = loadResource(url);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
            return defineClass(name, byteCode, null);
        }

        private ByteBuffer loadResource(URL url) throws IOException {
            return ClassPathUtils.readStream(url, stream -> {
                try {
                    return ByteBuffer.wrap(read(stream, Math.max(MAX_BUFFER_SIZE, stream.available())));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        protected URL findResource(String name) {
            for (ClassLoader delegate : delegateClassLoaders) {
                URL resource = delegate.getResource(name);
                if (resource != null) {
                    return resource;
                }
            }
            return null;
        }

        protected Enumeration<URL> findResources(String name) throws IOException {
            Vector<URL> vector = new Vector<URL>();
            for (ClassLoader delegate : delegateClassLoaders) {
                Enumeration<URL> enumeration = delegate.getResources(name);
                while (enumeration.hasMoreElements()) {
                    vector.add(enumeration.nextElement());
                }
            }
            return vector.elements();
        }

        private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;
        private static final int BUFFER_SIZE = 8192;

        private static byte[] read(InputStream source, int initialSize) throws IOException {
            int capacity = initialSize;
            byte[] buf = new byte[capacity];
            int nread = 0;
            int n;
            for (;;) {
                // read to EOF which may read more or less than initialSize (eg: file
                // is truncated while we are reading)
                while ((n = source.read(buf, nread, capacity - nread)) > 0)
                    nread += n;

                // if last call to source.read() returned -1, we are done
                // otherwise, try to read one more byte; if that failed we're done too
                if (n < 0 || (n = source.read()) < 0)
                    break;

                // one more byte was read; need to allocate a larger buffer
                if (capacity <= MAX_BUFFER_SIZE - capacity) {
                    capacity = Math.max(capacity << 1, BUFFER_SIZE);
                } else {
                    if (capacity == MAX_BUFFER_SIZE)
                        throw new OutOfMemoryError("Required array size too large");
                    capacity = MAX_BUFFER_SIZE;
                }
                buf = Arrays.copyOf(buf, capacity);
                buf[nread++] = (byte) n;
            }
            return (capacity == nread) ? buf : Arrays.copyOf(buf, nread);
        }

    }
}
