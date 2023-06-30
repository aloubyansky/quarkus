package io.quarkus.deployment.configuration;

import static io.smallrye.config.SecretKeys.doLocked;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;

import org.eclipse.microprofile.config.Config;

import io.quarkus.deployment.BootstrapConfig;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;

@Priority(Priorities.APPLICATION)
public class BuildTimeConfigurationInterceptor implements ConfigSourceInterceptor {

    public interface ConfigurationWriter {
        void write(BootstrapConfig.DumpConfig config, BuildTimeConfigurationReader.ReadResult configReadResult,
                LaunchMode launchMode, Path buildDirectory);
    }

    private boolean enabled;
    private Map<String, String> readOptions = Map.of();
    private final ConfigurationWriter writer = new ConfigurationWriter() {
        @Override
        public void write(BootstrapConfig.DumpConfig config, BuildTimeConfigurationReader.ReadResult configReadResult,
                LaunchMode launchMode, Path buildDirectory) {
            if (!config.enabled) {
                return;
            }

            Path file = config.file.orElse(null);
            if (file == null) {
                final Path dir = config.directory.orElseGet(() -> (buildDirectory.getParent() == null
                        ? buildDirectory
                        : buildDirectory.getParent()).resolve(".quarkus"));
                file = dir
                        .resolve(config.filePrefix + "-" + launchMode.getDefaultProfile() + config.fileSuffix);
            } else if (!file.isAbsolute()) {
                file = config.directory.orElse(buildDirectory).resolve(file);
            }

            if (file.getParent() != null) {
                try {
                    Files.createDirectories(file.getParent());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            final Map<String, String> allBuildTimeValus = configReadResult.getAllBuildTimeValues();
            final Map<String, String> buildTimeRuntimeValues = configReadResult.getBuildTimeRunTimeValues();
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                final List<String> names = new ArrayList<>(readOptions.size());
                for (var name : readOptions.keySet()) {
                    if (allBuildTimeValus.containsKey(name) || buildTimeRuntimeValues.containsKey(name)) {
                        names.add(name);
                    }
                }
                Collections.sort(names);
                for (String name : names) {
                    var value = readOptions.get(name);
                    if (value != null) {
                        writer.write(name);
                        writer.write("=");
                        writer.write(value);
                        writer.newLine();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    void configure(Config config) {
        enabled = config.getValue("quarkus.bootstrap.config-recorder.enabled", boolean.class);
        if (enabled) {
            readOptions = new ConcurrentHashMap<>();
        }
    }

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (!enabled) {
            return context.proceed(name);
        }
        final ConfigValue configValue = doLocked(() -> context.proceed(name));
        readOptions.put(name, configValue == null ? "NULL" : configValue.getValue());
        return configValue;
    }

    public ConfigurationWriter getConfigurationWriter() {
        return writer;
    }
}
