package io.quarkus.devtools.project.configuration.maven;

import java.nio.file.Path;

import org.apache.maven.model.Model;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;

class ExternalModuleContainer extends AbstractModuleContainer {

    private static final Logger log = Logger.getLogger(ExternalModuleContainer.class);

    public ExternalModuleContainer(WorkspaceModuleId id, Model effectiveModel) {
        super(id);
        this.effectiveModel = effectiveModel;
    }

    @Override
    public boolean isProjectModule() {
        return false;
    }

    @Override
    public ValueSource getValueSource() {
        return ValueSource.external(id, null);
    }

    @Override
    public ValueSource getValueSource(String propertyName) {
        return ValueSource.external(id, propertyName, null);
    }

    @Override
    public ResolvedValue doResolvePropertyValue(String propName, String expr) {
        var value = effectiveModel.getProperties().getProperty(propName);
        if (value == null) {
            log.warn("Failed to locate property " + expr + " in " + id);
            return ResolvedValue.of(expr, getValueSource());
        }
        while (ConfiguredValue.isPropertyExpression(value)) {
            propName = ConfiguredValue.getPropertyName(value);
            value = effectiveModel.getProperties().getProperty(propName);
            if (value == null) {
                log.warn("Failed to locate property " + propName + " in " + id);
                return ResolvedValue.of(expr, getValueSource());
            }
        }
        return ResolvedValue.of(value, getValueSource(propName));
    }

    @Override
    protected Path getPomFile() {
        return null;
    }

    @Override
    protected Model resolveEffectiveModel() {
        return effectiveModel;
    }
}
