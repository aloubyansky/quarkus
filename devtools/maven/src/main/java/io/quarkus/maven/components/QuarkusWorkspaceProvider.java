package io.quarkus.maven.components;

import java.util.List;

import org.apache.maven.model.building.ModelBuilder;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.impl.Deployer;
import org.eclipse.aether.impl.MetadataResolver;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.impl.VersionRangeResolver;
import org.eclipse.aether.impl.VersionResolver;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContextConfig;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.MavenModelBuilder;
import io.smallrye.beanbag.BeanSupplier;
import io.smallrye.beanbag.DependencyFilter;
import io.smallrye.beanbag.Scope;
import io.smallrye.beanbag.maven.MavenFactory;

@Component(role = QuarkusWorkspaceProvider.class, instantiationStrategy = "singleton")
public class QuarkusWorkspaceProvider {

    @Requirement(role = VersionResolver.class, optional = false)
    VersionResolver versionResolver;

    @Requirement(role = VersionRangeResolver.class, optional = false)
    VersionRangeResolver versionRangeResolver;

    @Requirement(role = ArtifactResolver.class, optional = false)
    ArtifactResolver artifactResolver;

    @Requirement(role = MetadataResolver.class, optional = false)
    MetadataResolver metadataResolver;

    @Requirement(role = Deployer.class, optional = false)
    Deployer deployer;

    @Requirement(role = RemoteRepositoryManager.class, optional = false)
    RemoteRepositoryManager remoteRepoManager;

    private volatile BootstrapMavenContext ctx;

    public BootstrapMavenContext getMavenContext() {
        return ctx == null ? ctx = createMavenContext(BootstrapMavenContext.config()) : ctx;
    }

    public RepositorySystem getRepositorySystem() {
        try {
            return getMavenContext().getRepositorySystem();
        } catch (BootstrapMavenException e) {
            throw new RuntimeException("Failed to initialize Maven repository system", e);
        }
    }

    public RemoteRepositoryManager getRemoteRepositoryManager() {
        return remoteRepoManager;
    }

    public BootstrapMavenContext createMavenContext(BootstrapMavenContextConfig<?> config) {
        try {
            return new BootstrapMavenContext(config) {
                @Override
                protected MavenFactory configureMavenFactory() {
                    final BootstrapMavenContext ctx = this;
                    return MavenFactory.create(
                            List.of(RepositorySystem.class.getClassLoader(),
                                    getClass().getClassLoader()),
                            builder -> builder.addBeanInstance(versionResolver)
                                    .addBeanInstance(versionRangeResolver)
                                    .addBeanInstance(artifactResolver)
                                    .addBeanInstance(metadataResolver)
                                    .addBeanInstance(deployer)
                                    .addBeanInstance(remoteRepoManager)
                                    .addBean(ModelBuilder.class)
                                    .setSupplier(new BeanSupplier<ModelBuilder>() {
                                        @Override
                                        public ModelBuilder get(Scope scope) {
                                            return new MavenModelBuilder(ctx);
                                        }
                                    }).setPriority(100).build(),
                            DependencyFilter.ACCEPT);
                }
            };
        } catch (BootstrapMavenException e) {
            throw new RuntimeException("Failed to initialize Quarkus Maven context", e);
        }
    }

    public MavenArtifactResolver createArtifactResolver(BootstrapMavenContextConfig<?> config) {
        try {
            return new MavenArtifactResolver(createMavenContext(config));
        } catch (BootstrapMavenException e) {
            throw new RuntimeException("Failed to initialize Maven artifact resolver", e);
        }
    }
}
