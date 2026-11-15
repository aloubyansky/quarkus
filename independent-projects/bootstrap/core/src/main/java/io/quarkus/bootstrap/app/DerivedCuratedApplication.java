package io.quarkus.bootstrap.app;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;

/**
 * An application {@link CuratedApplication#derive(io.quarkus.paths.PathCollection, String) derived} from another
 * one, sharing the augmentation class loader of its parent.
 * <p>
 * The parent owns the augmentation class loader: this application never creates or closes one itself, so the
 * parent has to stay open for as long as this application is in use.
 * <p>
 * Everything else belongs to this application, including the base runtime class loader and its class path
 * elements, which are not shared with the parent.
 */
final class DerivedCuratedApplication extends CuratedApplication {

    private static final long serialVersionUID = 1L;

    /**
     * The application owning the augmentation class loader. It is not serialized with this application.
     */
    private final transient CuratedApplication parent;

    /**
     * @param parent application owning the augmentation class loader
     * @param quarkusBootstrap bootstrap configuration of this application
     * @param curationResult curation result, shared with the parent
     * @param configuredClassLoading class loading configuration of this application
     */
    DerivedCuratedApplication(CuratedApplication parent, QuarkusBootstrap quarkusBootstrap,
            CurationResult curationResult, ConfiguredClassLoading configuredClassLoading) {
        super(quarkusBootstrap, curationResult, configuredClassLoading);
        this.parent = parent;
    }

    /**
     * @return augmentation class loader of the parent, created if necessary
     */
    @Override
    public QuarkusClassLoader getOrCreateAugmentClassLoader() {
        return parent.getOrCreateAugmentClassLoader();
    }

    /**
     * @return augmentation class loader of the parent, or {@code null} if it has not been created yet
     */
    @Override
    public QuarkusClassLoader getAugmentClassLoader() {
        return parent.getAugmentClassLoader();
    }

    /**
     * Does nothing: the augmentation class loader is closed by the parent.
     */
    @Override
    protected void closeAugmentClassLoader() {
    }
}
