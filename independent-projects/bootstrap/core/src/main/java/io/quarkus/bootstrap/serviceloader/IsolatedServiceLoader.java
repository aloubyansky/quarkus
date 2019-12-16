package io.quarkus.bootstrap.serviceloader;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.function.Function;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;

public class IsolatedServiceLoader {

    public static <I,R> Function<I,R> loadFunctionService(Class<? extends Function<I,R>> functionService) throws AppModelResolverException {
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        final String servicePath = "META-INF/services/" + functionService.getName();
        Enumeration<URL> providers;
        try {
            providers = tccl.getResources(servicePath);
        } catch (IOException e) {
            throw new AppModelResolverException("Failed to search for " + servicePath + " resources on the classpath", e);
        }
        if(!providers.hasMoreElements()) {
            throw new IllegalStateException("Failed to locate " + servicePath + " on the classpath");
        }

        URL url = providers.nextElement();
        if(providers.hasMoreElements()) {
            final StringBuilder buf = new StringBuilder();
            buf.append("Expected only one implementation of ")
            .append(functionService.getName())
            .append(" on the classpath but found ")
            .append(readContent(url));
            while(providers.hasMoreElements()) {
                buf.append(", ").append(readContent(providers.nextElement()));
            }
            throw new IllegalStateException(buf.toString());
        }

        final URLClassLoader servicesCl = new URLClassLoader(new URL[] {getRoot(url, servicePath)}, null);
        final MavenResolverImplClassLoader mvnResolverImplLoader = new MavenResolverImplClassLoader(
                servicesCl, Thread.currentThread().getContextClassLoader());
        try {
            final Class<?> loadClass = mvnResolverImplLoader.loadClass(readContent(url));
            final Function<I, R> target = functionService.cast(loadClass.newInstance());
            return t -> {
                try {
                    return target.apply(t);
                } catch(Throwable e) {
                    closeUcl(mvnResolverImplLoader);
                    throw e;
                } finally {
                    // in the current impl it's ok to close the service CL at this point
                    closeUcl(servicesCl);
                }
            };
        } catch (AppModelResolverException e) {
            closeUcl(servicesCl);
            closeUcl(mvnResolverImplLoader);
            throw e;
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            closeUcl(servicesCl);
            closeUcl(mvnResolverImplLoader);
            final StringBuilder buf = new StringBuilder();
            buf.append("Failed to initialize Maven artifact resolver");
            if (MavenArtifactResolver.getMavenHome() == null) {
                buf.append(". Neither maven.home property nor MAVEN_HOME envinroment variable was available.")
                .append(" Either set one of those or make sure all the necessary depdendencies to initialize the Maven resolver are on the classpath.");
            }
            throw new AppModelResolverException(buf.toString(), e);
        } catch (InstantiationException | IllegalAccessException e) {
            closeUcl(servicesCl);
            closeUcl(mvnResolverImplLoader);
            throw new AppModelResolverException("Failed to instantiate " + readContent(url), e);
        } catch (Throwable t) {
            closeUcl(servicesCl);
            closeUcl(mvnResolverImplLoader);
            throw t;
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }
    }

    private static void closeUcl(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
        }
    }

    private static URL getRoot(URL url, final String servicePath) throws AppModelResolverException {
        try {
        return url.getProtocol().equals("jar")
                ? new URL(url.getFile().substring(0, url.getFile().length() - servicePath.length() - 2))
                : new URL(url.getProtocol(), url.getHost(),
                        url.getFile().substring(0, url.getFile().length() - servicePath.length()));
        } catch(MalformedURLException e) {
            throw new AppModelResolverException("Failed to create the root URL for " + servicePath + " from " + url, e);
        }
    }

    private static String readContent(final URL serviceUrl) throws AppModelResolverException {
        try(ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            final byte[] bytes = new byte[128];
            try(InputStream is = serviceUrl.openStream()) {
                int bytesRead;
                while((bytesRead = is.read(bytes)) > 0) {
                    os.write(bytes, 0, bytesRead);
                }
            }
            return os.toString("utf-8");
        } catch(Exception e) {
            throw new AppModelResolverException("Failed to read content of " + serviceUrl, e);
        }
    }
}
