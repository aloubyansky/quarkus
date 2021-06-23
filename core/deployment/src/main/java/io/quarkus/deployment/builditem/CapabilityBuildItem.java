package io.quarkus.deployment.builditem;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Objects;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;

/**
 * Represents a technical capability that can be queried by other extensions.
 * <p>
 * Build steps can inject {@link Capabilities} - a convenient build item that holds the set of registered capabilities.
 * <p>
 * An extension may provide multiple capabilities. But only a single provider of a given capability is allowed
 * in an application. If multiple providers of the same capability are detected during the build of an application,
 * the build will fail with the corresponding error message. By default, capabilities are not displayed to users.
 * <p>
 * Capabilities should follow the naming conventions of Java packages; e.g. {@code io.quarkus.security.jpa}. Capabilities
 * provided by core extensions should be listed in the {@link Capability} interface and their name should always start with the
 * {@code io.quarkus} prefix.
 *
 * @see Capabilities
 * @see Capability
 */
public final class CapabilityBuildItem extends MultiBuildItem {

    private final String name;
    private final String provider;

    /**
     * @deprecated in favor of {@link #CapabilityBuildItem(String, String))} that also accepts the provider
     *             of the capability to be highlighted in the error messages in case of detected capability conflicts.
     *
     * @param name capability name
     */
    @Deprecated
    public CapabilityBuildItem(String name) {
        this(name, "<unknown>");
        new Exception("DEPRECATED CTOR " + name).printStackTrace();
        System.out.println("CLASS RESOURCE: " + Thread.currentThread().getContextClassLoader()
                .getResource("org/kie/kogito/quarkus/processes/deployment/ProcessesAssetsProcessor.class"));
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                    .getResources(BootstrapConstants.DESCRIPTOR_PATH);
            while (resources.hasMoreElements()) {
                System.out.println(" - " + resources.nextElement());
            }

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if ("kogito-processes".equals(name)) {
            final Path p = Paths.get(
                    "/home/runner/work/kogito-runtimes/kogito-runtimes/kiegroup_kogito_runtimes/kogito-quarkus-parent/kogito-quarkus-processes-extension/kogito-quarkus-processes-deployment/src/main/java/org/kie/kogito/quarkus/processes/deployment/ProcessesAssetsProcessor.java");
            if (Files.exists(p)) {
                try (BufferedReader reader = Files.newBufferedReader(p)) {
                    String line = reader.readLine();
                    while (line != null) {
                        System.out.println(line);
                        line = reader.readLine();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                System.out.println("DOES NOT EXIST " + p);
            }
        }
    }

    /**
     * <b>IMPORTANT:</b> in most cases, capability build items should not be produced by build steps of specific
     * extensions but be configured in their extension descriptors instead. Capabilities produced from
     * extension build steps aren't available for the Quarkus dev tools. As a consequences, such capabilities
     * can not be taken into account when analyzing extension compatibility during project creation or when
     * adding new extensions to a project.
     *
     * @param name capability name
     * @param provider capability provider
     */
    public CapabilityBuildItem(String name, String provider) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
    }

    public String getName() {
        return name;
    }

    public String getProvider() {
        return provider;
    }
}
