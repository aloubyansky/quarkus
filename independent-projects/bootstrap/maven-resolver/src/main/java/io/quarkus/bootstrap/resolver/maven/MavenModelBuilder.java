package io.quarkus.bootstrap.resolver.maven;

import java.io.File;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.Result;
import org.apache.maven.model.merge.MavenModelMerger;
import org.apache.maven.model.resolution.WorkspaceModelResolver;

/**
 *
 * @author Alexey Loubyansky
 */
public class MavenModelBuilder implements ModelBuilder {

    private final ModelBuilder builder;
    private final WorkspaceModelResolver modelResolver;

    public MavenModelBuilder(WorkspaceModelResolver wsModelResolver) {
        builder = new BootstrapModelBuilderFactory().newInstance();
        modelResolver = wsModelResolver;
    }

    @Override
    public ModelBuildingResult build(ModelBuildingRequest request) throws ModelBuildingException {
        if(modelResolver != null) {
            request.setWorkspaceModelResolver(modelResolver);
        }
        logClass(MavenModelMerger.class);
        logClass(Model.class);
        return builder.build(request);
    }

    private void logClass(Class<?> cls) {
        System.out.println(cls + " loaded by " + cls.getClassLoader() + " from "
        + cls.getClassLoader().getResource(cls.getName().replace('.', '/') + ".class"));
    }

    @Override
    public ModelBuildingResult build(ModelBuildingRequest request, ModelBuildingResult result) throws ModelBuildingException {
        return builder.build(request, result);
    }

    @Override
    public Result<? extends Model> buildRawModel(File pomFile, int validationLevel, boolean locationTracking) {
        return builder.buildRawModel(pomFile, validationLevel, locationTracking);
    }

}
