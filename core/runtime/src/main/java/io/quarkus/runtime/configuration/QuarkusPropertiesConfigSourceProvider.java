package io.quarkus.runtime.configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import io.smallrye.config.common.MapBackedConfigSource;
import io.smallrye.config.common.utils.ConfigSourceUtil;

public class QuarkusPropertiesConfigSourceProvider implements ConfigSourceProvider {

    private List<ConfigSource> configSources = new ArrayList<>();

    public QuarkusPropertiesConfigSourceProvider(String propertyFileName, boolean optional, ClassLoader classLoader) {
        try {
            Enumeration<URL> propertyFileUrls = classLoader.getResources(propertyFileName);

            if (!optional && !propertyFileUrls.hasMoreElements()) {
                throw new IllegalStateException(propertyFileName + " wasn't found.");
            }

            while (propertyFileUrls.hasMoreElements()) {
                URL propertyFileUrl = propertyFileUrls.nextElement();
                configSources.add(new PropertiesConfigSource(propertyFileUrl));
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("problem while loading microprofile-config.properties files", ioe);
        }

    }

    @Override
    public List<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
        return configSources;
    }

    private static class PropertiesConfigSource extends MapBackedConfigSource {
        private static final long serialVersionUID = 1866835565147832432L;

        private static final String NAME_PREFIX = "PropertiesConfigSource[source=";

        /**
         * Construct a new instance
         *
         * @param url a property file location
         * @throws IOException if an error occurred when reading from the input stream
         */
        public PropertiesConfigSource(URL url) throws IOException {
            super(NAME_PREFIX + url.toString() + "]", urlToMap(url));
        }

        public PropertiesConfigSource(Properties properties, String source) {
            super(NAME_PREFIX + source + "]", ConfigSourceUtil.propertiesToMap(properties));
        }

        public PropertiesConfigSource(Map<String, String> properties, String source, int ordinal) {
            super(NAME_PREFIX + source + "]", properties, ordinal);
        }
    }

    private static Map<String, String> urlToMap(URL url) throws IOException {

        final Properties props;
        if ("jar".equals(url.getProtocol())) {
            final String file = url.getFile();
            final int em = file.lastIndexOf('!');
            final Path jar;
            try {
                jar = toPath(new URL(file.substring(0, em)));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve the JAR path from " + file);
            }
            try (FileSystem jarFs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
                props = loadProperties(jarFs.getPath(file.substring(em + 1)));
            }
        } else if ("file".equals(url.getProtocol())) {
            try {
                props = loadProperties(toPath(url));
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Failed to resolve the path from " + url);
            }
        } else {
            throw new IllegalStateException("Unsupported protocol " + url);
        }

        return ConfigSourceUtil.propertiesToMap(props);
    }

    private static Properties loadProperties(Path p) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(p)) {
            final Properties props = new Properties();
            props.load(reader);
            return props;
        }
    }

    private static Path toPath(final URL url) throws URISyntaxException {
        return Paths.get(url.toURI());
    }
}
