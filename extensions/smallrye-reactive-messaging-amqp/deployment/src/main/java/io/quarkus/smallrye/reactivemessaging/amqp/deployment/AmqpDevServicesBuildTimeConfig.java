package io.quarkus.smallrye.reactivemessaging.amqp.deployment;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

@ConfigGroup
public interface AmqpDevServicesBuildTimeConfig {

    /**
     * If Dev Services for AMQP has been explicitly enabled or disabled. Dev Services are generally enabled
     * by default, unless there is an existing configuration present. For AMQP, Dev Services starts a broker unless
     * {@code amqp-host} or {@code amqp-port} are set or if all the Reactive Messaging AMQP channel are configured with
     * {@code host} or {@code port}.
     */
    Optional<Boolean> enabled();

    /**
     * Optional fixed port the dev service will listen to.
     * <p>
     * If not defined, the port will be chosen randomly.
     */
    Optional<Integer> port();

    /**
     * The image to use.
     * Note that only ActiveMQ Artemis images are supported.
     * Specifically, the image repository must end with {@code artemiscloud/activemq-artemis-broker}.
     * <p>
     * Check the <a href="https://quay.io/repository/artemiscloud/activemq-artemis-broker">activemq-artemis-broker on Quay
     * page</a>
     * to find the available versions.
     */
    @WithDefault("quay.io/artemiscloud/activemq-artemis-broker:1.0.25")
    String imageName();

    /**
     * The value of the {@code AMQ_EXTRA_ARGS} environment variable to pass to the container.
     * For ActiveMQ Artemis Broker <= 1.0.21, set this property to
     * {@code --no-autotune --mapped --no-fsync --relax-jolokia --http-host 0.0.0.0}
     */
    @WithDefault("--no-autotune --mapped --no-fsync --relax-jolokia")
    String extraArgs();

    /**
     * Indicates if the AMQP broker managed by Quarkus Dev Services is shared.
     * When shared, Quarkus looks for running containers using label-based service discovery.
     * If a matching container is found, it is used, and so a second one is not started.
     * Otherwise, Dev Services for AMQP starts a new container.
     * <p>
     * The discovery uses the {@code quarkus-dev-service-amqp} label.
     * The value is configured using the {@code service-name} property.
     * <p>
     * Container sharing is only used in dev mode.
     */
    @WithDefault("true")
    boolean shared();

    /**
     * The value of the {@code quarkus-dev-service-aqmp} label attached to the started container.
     * This property is used when {@code shared} is set to {@code true}.
     * In this case, before starting a container, Dev Services for AMQP looks for a container with the
     * {@code quarkus-dev-service-amqp} label
     * set to the configured value. If found, it will use this container instead of starting a new one. Otherwise, it
     * starts a new container with the {@code quarkus-dev-service-amqp} label set to the specified value.
     * <p>
     * This property is used when you need multiple shared AMQP brokers.
     */
    @WithDefault("amqp")
    String serviceName();

    /**
     * Environment variables that are passed to the container.
     */
    @ConfigDocMapKey("environment-variable-name")
    Map<String, String> containerEnv();

}
