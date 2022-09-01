package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ParameterResolver;

public interface QuarkusMainIntegrationTestExtensionInterface extends BeforeEachCallback, AfterEachCallback, ParameterResolver {

}
