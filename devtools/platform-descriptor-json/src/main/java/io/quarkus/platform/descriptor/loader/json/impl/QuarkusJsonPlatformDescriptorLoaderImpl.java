package io.quarkus.platform.descriptor.loader.json.impl;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.dependencies.Extension;
import io.quarkus.platform.descriptor.loader.json.QuarkusJsonPlatformDescriptorLoader;
import io.quarkus.platform.descriptor.loader.json.QuarkusJsonPlatformDescriptorLoaderContext;

public class QuarkusJsonPlatformDescriptorLoaderImpl
        implements QuarkusJsonPlatformDescriptorLoader<QuarkusJsonPlatformDescriptor> {

    @Override
    public QuarkusJsonPlatformDescriptor load(final QuarkusJsonPlatformDescriptorLoaderContext context) {

        final QuarkusJsonPlatformDescriptor platform = context
                .parseJson(is -> {
                    try {
                        ObjectMapper mapper = new ObjectMapper()
                                .enable(JsonParser.Feature.ALLOW_COMMENTS)
                                .enable(JsonParser.Feature.ALLOW_NUMERIC_LEADING_ZEROS)
                                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                .setPropertyNamingStrategy(PropertyNamingStrategy.KEBAB_CASE);
                        return mapper.readValue(is, QuarkusJsonPlatformDescriptor.class);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse JSON stream", e);
                    }
                });

        if (context.getArtifactResolver() != null) {
            platform.setManagedDependencies(context.getArtifactResolver().getManagedDependencies(platform.getBomGroupId(),
                    platform.getBomArtifactId(), null, "pom", platform.getBomVersion()));
        }
        platform.setResourceLoader(context.getResourceLoader());
        platform.setMessageWriter(context.getMessageWriter());

        for (Extension ext : platform.getExtensions()) {
            if (ext.getCodestart() == null) {
                continue;
            }
            context.getMessageWriter().info("Adding codestart to '" + ext.getName() + "'");
            final String classifier = ext.getClassifier() == null ? "" : ext.getClassifier();
            final String type = ext.getType() == null ? "jar" : ext.getType();
            ext.setCodestartResourceLoader(new PathResourceLoader(() -> {
                context.getMessageWriter().info("Resolving codestart for '" + ext.getName() + "'");
                try {
                    return context.getArtifactResolver().process(ext.getGroupId(), ext.getArtifactId() + "-codestart",
                            classifier, type, ext.getVersion(), Path::toAbsolutePath);
                } catch (AppModelResolverException e) {
                    throw new RuntimeException("Failed to resolve codestart for '" + ext.getName() + "'", e);
                }
            }));
        }
        return platform;
    }
}
