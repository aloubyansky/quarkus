package io.quarkus.bootstrap.serviceloader;

import java.net.URL;
import java.net.URLClassLoader;

class URLClassLoaderWithPackageAccess extends URLClassLoader {

    public URLClassLoaderWithPackageAccess(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    protected Package doGetPackage(String name) {
        return super.getPackage(name);
    }
}
