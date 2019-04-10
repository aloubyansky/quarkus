/**
 *
 */
package io.quarkus.bootstrap.resolver.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.composition.DefaultDependencyManagementImporter;
import org.apache.maven.model.inheritance.DefaultInheritanceAssembler;
import org.apache.maven.model.interpolation.StringSearchModelInterpolator;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.management.DefaultDependencyManagementInjector;
import org.apache.maven.model.management.DefaultPluginManagementInjector;
import org.apache.maven.model.normalization.DefaultModelNormalizer;
import org.apache.maven.model.path.DefaultModelPathTranslator;
import org.apache.maven.model.path.DefaultModelUrlNormalizer;
import org.apache.maven.model.path.DefaultPathTranslator;
import org.apache.maven.model.path.DefaultUrlNormalizer;
import org.apache.maven.model.profile.DefaultProfileInjector;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.superpom.DefaultSuperPomProvider;
import org.apache.maven.model.validation.DefaultModelValidator;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResult;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;

public class ModelBuilderUtils {

    private static class ModelResolverImpl implements ModelResolver {

        protected final LocalWorkspace workspace;
        protected final MavenArtifactResolver resolver;

        protected ModelResolverImpl(LocalWorkspace workspace, MavenArtifactResolver resolver) {
            this.workspace = workspace;
            this.resolver = resolver;
        }

        private static void log(String msg) {
            System.out.println("ModelBuilder " + msg);
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            log("resolveModel " + groupId + ":" + artifactId + ":" + version);
            final LocalProject project = workspace.getProject(groupId, artifactId);
            if(project == null) {
                ArtifactResult resolve;
                try {
                    resolve = resolver.resolve(new DefaultArtifact(groupId, artifactId, "", "pom", version));
                } catch (AppModelResolverException e) {
                    throw new UnresolvableModelException("Failed to resolve", groupId, artifactId, version, e);
                }
                return new FileModelSource(resolve.getArtifact().getFile());
            }
            log("  from workspace");
            return new FileModelSource(project.getDir().resolve("pom.xml").toFile());
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addRepository(Repository repository) throws InvalidRepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
            log("addRepository " + repository + ", " + replace);
        }

        @Override
        public ModelResolver newCopy() {
            log("newCopy");
            return this;
        }
    }

    public static ModelBuilder newModelBuilder() {
        final DefaultModelBuilder modelBuilder = new DefaultModelBuilder();
        modelBuilder.setProfileSelector(new DefaultProfileSelector());

        final DefaultModelReader modelReader = new DefaultModelReader();
        final DefaultModelProcessor modelProcessor = new DefaultModelProcessor();
        modelProcessor.setModelReader(modelReader);

        final DefaultSuperPomProvider superPomProvider = new DefaultSuperPomProvider();
        superPomProvider.setModelProcessor(modelProcessor);

        modelBuilder.setSuperPomProvider(superPomProvider);
        modelBuilder.setModelProcessor(modelProcessor);
        modelBuilder.setModelNormalizer(new DefaultModelNormalizer());
        modelBuilder.setModelValidator(new DefaultModelValidator());
        modelBuilder.setProfileInjector(new DefaultProfileInjector());
        modelBuilder.setInheritanceAssembler(new DefaultInheritanceAssembler());

        final DefaultUrlNormalizer urlNormalizer = new DefaultUrlNormalizer();
        final DefaultModelUrlNormalizer modelUrlNormalizer = new DefaultModelUrlNormalizer();
        modelUrlNormalizer.setUrlNormalizer(urlNormalizer);

        final DefaultPathTranslator pathTranslator = new DefaultPathTranslator();

        final StringSearchModelInterpolator modelInterpolator = new StringSearchModelInterpolator();
        modelInterpolator.setPathTranslator(pathTranslator);
        modelInterpolator.setUrlNormalizer(urlNormalizer);
        modelBuilder.setModelInterpolator(modelInterpolator);

        modelBuilder.setModelUrlNormalizer(modelUrlNormalizer);
        final DefaultModelPathTranslator modelPathTranslator = new DefaultModelPathTranslator();
        modelPathTranslator.setPathTranslator(pathTranslator);
        modelBuilder.setModelPathTranslator(modelPathTranslator);
        modelBuilder.setPluginManagementInjector(new DefaultPluginManagementInjector());
        modelBuilder.setDependencyManagementImporter(new DefaultDependencyManagementImporter());
        modelBuilder.setDependencyManagementInjector(new DefaultDependencyManagementInjector());
        return modelBuilder;
    }

    public static Model buildEffectiveModel(LocalProject project) throws AppModelResolverException {
        return buildEffectiveModel(project, MavenArtifactResolver.builder().setWorkspace(project.getWorkspace()).build());
    }

    public static Model buildEffectiveModel(LocalProject project, MavenArtifactResolver resolver) throws AppModelResolverException {
        try {
            final ModelBuildingResult result = newModelBuilder().build(new DefaultModelBuildingRequest().setRawModel(project.getRawModel())
                    .setModelResolver(new ModelResolverImpl(project.getWorkspace(), resolver)));
            return result.getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new AppModelResolverException("Failed to build effective model for " + project.getAppArtifact(), e);
        }
    }
}
