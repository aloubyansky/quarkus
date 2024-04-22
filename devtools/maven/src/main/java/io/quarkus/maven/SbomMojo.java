package io.quarkus.maven;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.MailingList;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.cyclonedx.BomGeneratorFactory;
import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.Tool;
import org.cyclonedx.util.LicenseResolver;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.util.listener.ChainedRepositoryListener;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.EffectiveModelResolver;
import io.quarkus.bootstrap.resolver.maven.IncubatingApplicationModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.runtime.LaunchMode;

/**
 * Quarkus application SBOM generator
 */
@Mojo(name = "sbom", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class SbomMojo extends QuarkusBootstrapMojo {

    private static final List<String> HASH_ALGS = List.of("MD5", "SHA-1", "SHA-256", "SHA-512", "SHA-384", "SHA3-384",
            "SHA3-256", "SHA3-512");

    private static final Comparator<ArtifactCoords> ARTIFACT_COORDS_COMPARATOR = (c1, c2) -> {
        var i = c1.getGroupId().compareTo(c2.getGroupId());
        if (i != 0) {
            return i;
        }
        i = c1.getArtifactId().compareTo(c2.getArtifactId());
        if (i != 0) {
            return i;
        }
        i = c1.getVersion().compareTo(c2.getVersion());
        if (i != 0) {
            return i;
        }
        i = c1.getClassifier().compareTo(c2.getClassifier());
        if (i != 0) {
            return i;
        }
        return c1.getType().compareTo(c2.getType());
    };

    /**
     * Skip the execution of this mojo
     */
    @Parameter(defaultValue = "false", property = "quarkus.sbom.skip")
    boolean skip = false;

    @Parameter(property = "launchMode")
    String mode;

    /**
     * File to store the SBOM in. If not configured, the SBOM will be stored in the ${project.build.directory} directory.
     */
    @Parameter(property = "quarkus.sbom.output-file")
    File outputFile;

    @Parameter(property = "quarkus.sbom.artifact.classifier", defaultValue = "sbom")
    String artifactClassifier;

    @Parameter(property = "quarkus.sbom.artifact.type", defaultValue = "json")
    String artifactType;

    @Parameter(property = "quarkus.sbom.artifact.attach")
    boolean artifactAttach;

    /**
     * Should license text be included in bom?
     */
    @Parameter(property = "quarkus.sbom.include-license-text", defaultValue = "false")
    private boolean includeLicenseText;

    @Parameter(property = "quarkus.sbom.schema-version", defaultValue = "1.4")
    String schemaVersion;
    private CycloneDxSchema.Version effectiveSchemaVersion;

    @Component
    MavenProjectHelper projectHelper;

    final Map<ArtifactCoords, List<RemoteRepository>> artifactRepos = new ConcurrentHashMap<>();

    @Override
    protected boolean beforeExecute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping config dump");
            return false;
        }
        if (!IncubatingApplicationModelResolver.isIncubatingEnabled(mavenProject().getProperties())) {
            throw new MojoExecutionException("This goal depends on an incubating feature that can be enabled by setting "
                    + "by setting system or project property quarkus.bootstrap.incubating-model-resolver to true");
        }
        return true;
    }

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        final String lifecyclePhase = mojoExecution.getLifecyclePhase();
        if (mode == null) {
            if (lifecyclePhase == null) {
                mode = "NORMAL";
            } else {
                mode = lifecyclePhase.contains("test") ? "TEST" : "NORMAL";
            }
        }
        final LaunchMode launchMode = LaunchMode.valueOf(mode);
        if (getLog().isDebugEnabled()) {
            getLog().debug("Bootstrapping Quarkus application in mode " + launchMode);
        }

        var sbomFile = getSbomFile();
        var bom = new Bom();
        bom.setMetadata(new Metadata());
        addToolInfo(bom);

        var artifactResolver = getArtifactResolver(launchMode);

        var session = new DefaultRepositorySystemSession(artifactResolver.getSession());
        session.setRepositoryListener(ChainedRepositoryListener.newInstance(new AbstractRepositoryListener() {
            @Override
            public void artifactResolved(RepositoryEvent event) {
                var a = event.getArtifact();
                final ArtifactRequest req = (ArtifactRequest) event.getTrace().getData();
                artifactRepos.put(ArtifactCoords.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension(),
                        a.getVersion()), req.getRepositories());
            }
        }, artifactResolver.getSession().getRepositoryListener()));
        try {
            artifactResolver = MavenArtifactResolver.builder()
                    .setRepositorySystem(artifactResolver.getSystem())
                    .setRepositorySystemSession(session)
                    .setRemoteRepositoryManager(artifactResolver.getRemoteRepositoryManager())
                    .setRemoteRepositories(artifactResolver.getRepositories())
                    .setWorkspaceDiscovery(false)
                    .build();
        } catch (BootstrapMavenException e) {
            throw new MojoExecutionException("Failed to initialize Maven artifact resolver", e);
        }

        var modelResolver = new EffectiveModelResolver(artifactResolver);
        var appModel = resolveApplicationModel(launchMode, artifactResolver);
        addApplicationComponent(bom, appModel.getAppArtifact(), modelResolver);
        for (var dep : appModel.getDependencies()) {
            addComponent(bom, dep, modelResolver);
        }

        persistSbom(bom, sbomFile);
        if (artifactAttach && sbomFile.exists()) {
            projectHelper.attachArtifact(mavenProject(), artifactType, artifactClassifier, sbomFile);
        }
    }

    private void addApplicationComponent(Bom bom, ResolvedDependency appArtifact, EffectiveModelResolver modelResolver) {
        var c = getComponent(appArtifact, modelResolver);
        c.setType(org.cyclonedx.model.Component.Type.APPLICATION);
        bom.getMetadata().setComponent(c);
        bom.addComponent(c);
    }

    private void addComponent(Bom bom, ResolvedDependency dep, EffectiveModelResolver modelResolver) {
        final org.cyclonedx.model.Component c = getComponent(dep, modelResolver);
        bom.addComponent(c);
        var deps = dep.getDependencies();
        if (!deps.isEmpty()) {
            final Dependency d = new Dependency(c.getBomRef());
            for (var depCoords : sortAlphabetically(deps)) {
                d.addDependency(new Dependency(getPackageURL(depCoords).toString()));
            }
            bom.addDependency(d);
        }
    }

    private org.cyclonedx.model.Component getComponent(ResolvedDependency dep, EffectiveModelResolver modelResolver) {
        final Model model = modelResolver.resolveEffectiveModel(dep, artifactRepos.getOrDefault(
                ArtifactCoords.of(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType(), dep.getVersion()),
                List.of()));
        final org.cyclonedx.model.Component c = new org.cyclonedx.model.Component();
        extractComponentMetadata(model, c, schemaVersion(), includeLicenseText);
        if (c.getPublisher() == null) {
            c.setPublisher("Central");
        }
        c.setGroup(dep.getGroupId());
        c.setName(dep.getArtifactId());
        c.setVersion(dep.getVersion());
        final PackageURL purl = getPackageURL(dep);
        c.setPurl(purl);
        c.setBomRef(purl.toString());

        final List<Property> props = new ArrayList<>(2);
        addProperty(props, "package:type", "maven");
        if (!ArtifactCoords.TYPE_POM.equals(dep.getType())) {
            addProperty(props, "package:language", "java");
        }
        addProperty(props, "quarkus:dependency:scope", dep.isRuntimeCp() ? "runtime" : "development");
        c.setProperties(props);
        c.setType(org.cyclonedx.model.Component.Type.LIBRARY);
        return c;
    }

    private static PackageURL getPackageURL(ArtifactCoords dep) {
        final TreeMap<String, String> qualifiers = new TreeMap<>();
        qualifiers.put("type", dep.getType());
        if (!dep.getClassifier().isEmpty()) {
            qualifiers.put("classifier", dep.getClassifier());
        }
        final PackageURL purl;
        try {
            purl = new PackageURL(PackageURL.StandardTypes.MAVEN,
                    dep.getGroupId(),
                    dep.getArtifactId(),
                    dep.getVersion(),
                    qualifiers, null);
        } catch (MalformedPackageURLException e) {
            throw new RuntimeException("Failed to generate Purl for " + dep.toCompactCoords(), e);
        }
        return purl;
    }

    static void addProperty(List<Property> props, String name, String value) {
        var prop = new Property();
        prop.setName(name);
        prop.setValue(value);
        props.add(prop);
    }

    private ApplicationModel resolveApplicationModel(LaunchMode launchMode, MavenArtifactResolver artifactResolver)
            throws MojoExecutionException {
        QuarkusClassLoader deploymentClassLoader = null;
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        final boolean clearNativeEnabledSystemProperty = setNativeEnabledIfNativeProfileEnabled();
        try {
            CuratedApplication curatedApplication = bootstrapApplication(launchMode, artifactResolver);
            deploymentClassLoader = curatedApplication.createDeploymentClassLoader();
            Thread.currentThread().setContextClassLoader(deploymentClassLoader);

            return curatedApplication.getApplicationModel();
        } catch (Exception any) {
            throw new MojoExecutionException("Failed to bootstrap Quarkus application", any);
        } finally {
            if (clearNativeEnabledSystemProperty) {
                System.clearProperty("quarkus.native.enabled");
            }
            Thread.currentThread().setContextClassLoader(originalCl);
            if (deploymentClassLoader != null) {
                deploymentClassLoader.close();
            }
        }
    }

    private void persistSbom(Bom bom, File sbomFile) throws MojoExecutionException {
        var specVersion = schemaVersion();
        final String sbomContent;
        if (artifactType.equalsIgnoreCase("json")) {
            sbomContent = BomGeneratorFactory.createJson(specVersion, bom).toJsonString();
        } else if (artifactType.equalsIgnoreCase("xml")) {
            try {
                sbomContent = BomGeneratorFactory.createXml(specVersion, bom).toXmlString();
            } catch (GeneratorException e) {
                throw new MojoExecutionException("Failed to generate SBOM in XML format", e);
            }
        } else {
            throw new MojoExecutionException(
                    "Unsupported SBOM artifact type " + artifactType + ", supported types are json and xml");
        }

        var parentDir = sbomFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
        if (getLog().isDebugEnabled()) {
            getLog().debug("SBOM Content:" + System.lineSeparator() + sbomContent);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sbomFile))) {
            writer.write(sbomContent);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write to " + outputFile, e);
        }
    }

    protected CycloneDxSchema.Version schemaVersion() {
        if (effectiveSchemaVersion == null) {
            if ("1.0".equals(schemaVersion)) {
                effectiveSchemaVersion = CycloneDxSchema.Version.VERSION_10;
            } else if ("1.1".equals(schemaVersion)) {
                effectiveSchemaVersion = CycloneDxSchema.Version.VERSION_11;
            } else if ("1.2".equals(schemaVersion)) {
                effectiveSchemaVersion = CycloneDxSchema.Version.VERSION_12;
            } else if ("1.3".equals(schemaVersion)) {
                effectiveSchemaVersion = CycloneDxSchema.Version.VERSION_13;
            } else if ("1.4".equals(schemaVersion)) {
                effectiveSchemaVersion = CycloneDxSchema.Version.VERSION_14;
            } else {
                effectiveSchemaVersion = CycloneDxSchema.Version.VERSION_15;
            }
        }
        return effectiveSchemaVersion;
    }

    private String getSbomFilename() {
        var a = mavenProject().getArtifact();
        var sb = new StringBuilder();
        sb.append(a.getArtifactId()).append("-").append(a.getVersion());
        if (!artifactClassifier.isEmpty()) {
            sb.append("-").append(artifactClassifier);
        }
        return sb.append(".").append(artifactType).toString();
    }

    private File getSbomFile() {
        var f = outputFile;
        if (f == null) {
            f = new File(mavenProject().getBuild().getDirectory(), getSbomFilename());
        }
        if (getLog().isDebugEnabled()) {
            getLog().debug("SBOM will be stored in " + f);
        }
        return f;
    }

    private static List<ArtifactCoords> sortAlphabetically(Collection<ArtifactCoords> col) {
        var list = new ArrayList<>(col);
        list.sort(ARTIFACT_COORDS_COMPARATOR);
        return list;
    }

    private void extractComponentMetadata(Model project, org.cyclonedx.model.Component component,
            CycloneDxSchema.Version schemaVersion, boolean includeLicenseText) {
        if (component.getPublisher() == null) {
            // If we don't already have publisher information, retrieve it.
            if (project.getOrganization() != null) {
                component.setPublisher(project.getOrganization().getName());
            }
        }
        if (component.getDescription() == null) {
            // If we don't already have description information, retrieve it.
            component.setDescription(project.getDescription());
        }
        if (component.getLicenseChoice() == null || component.getLicenseChoice().getLicenses() == null
                || component.getLicenseChoice().getLicenses().isEmpty()) {
            // If we don't already have license information, retrieve it.
            if (project.getLicenses() != null) {
                component.setLicenseChoice(resolveMavenLicenses(project.getLicenses(), schemaVersion, includeLicenseText));
            }
        }
        if (CycloneDxSchema.Version.VERSION_10 != schemaVersion) {
            addExternalReference(ExternalReference.Type.WEBSITE, project.getUrl(), component);
            if (project.getCiManagement() != null) {
                addExternalReference(ExternalReference.Type.BUILD_SYSTEM, project.getCiManagement().getUrl(), component);
            }
            if (project.getDistributionManagement() != null) {
                addExternalReference(ExternalReference.Type.DISTRIBUTION, project.getDistributionManagement().getDownloadUrl(),
                        component);
                if (project.getDistributionManagement().getRepository() != null) {
                    ExternalReference.Type type = (schemaVersion.getVersion() < 1.5) ? ExternalReference.Type.DISTRIBUTION
                            : ExternalReference.Type.DISTRIBUTION_INTAKE;
                    addExternalReference(type, project.getDistributionManagement().getRepository().getUrl(), component);
                }
            }
            if (project.getIssueManagement() != null) {
                addExternalReference(ExternalReference.Type.ISSUE_TRACKER, project.getIssueManagement().getUrl(), component);
            }
            if (project.getMailingLists() != null && project.getMailingLists().size() > 0) {
                for (MailingList list : project.getMailingLists()) {
                    String url = list.getArchive();
                    if (url == null) {
                        url = list.getSubscribe();
                    }
                    addExternalReference(ExternalReference.Type.MAILING_LIST, url, component);
                }
            }
            if (project.getScm() != null) {
                addExternalReference(ExternalReference.Type.VCS, project.getScm().getUrl(), component);
            }
        }
    }

    private LicenseChoice resolveMavenLicenses(final List<org.apache.maven.model.License> projectLicenses,
            final CycloneDxSchema.Version schemaVersion, boolean includeLicenseText) {
        final LicenseChoice licenseChoice = new LicenseChoice();
        for (org.apache.maven.model.License artifactLicense : projectLicenses) {
            boolean resolved = false;
            if (artifactLicense.getName() != null) {
                final LicenseChoice resolvedByName = LicenseResolver.resolve(artifactLicense.getName(), includeLicenseText);
                resolved = resolveLicenseInfo(licenseChoice, resolvedByName, schemaVersion);
            }
            if (artifactLicense.getUrl() != null && !resolved) {
                final LicenseChoice resolvedByUrl = LicenseResolver.resolve(artifactLicense.getUrl(), includeLicenseText);
                resolved = resolveLicenseInfo(licenseChoice, resolvedByUrl, schemaVersion);
            }
            if (artifactLicense.getName() != null && !resolved) {
                final License license = new License();
                license.setName(artifactLicense.getName().trim());
                if (StringUtils.isNotBlank(artifactLicense.getUrl())) {
                    try {
                        final URI uri = new URI(artifactLicense.getUrl().trim());
                        license.setUrl(uri.toString());
                    } catch (URISyntaxException e) {
                        // throw it away
                    }
                }
                licenseChoice.addLicense(license);
            }
        }
        return licenseChoice;
    }

    private boolean resolveLicenseInfo(final LicenseChoice licenseChoice, final LicenseChoice licenseChoiceToResolve,
            final CycloneDxSchema.Version schemaVersion) {
        if (licenseChoiceToResolve != null) {
            if (licenseChoiceToResolve.getLicenses() != null && !licenseChoiceToResolve.getLicenses().isEmpty()) {
                licenseChoice.addLicense(licenseChoiceToResolve.getLicenses().get(0));
                return true;
            } else if (licenseChoiceToResolve.getExpression() != null && CycloneDxSchema.Version.VERSION_10 != schemaVersion) {
                licenseChoice.setExpression(licenseChoiceToResolve.getExpression());
                return true;
            }
        }
        return false;
    }

    private static boolean doesComponentHaveExternalReference(final org.cyclonedx.model.Component component,
            final ExternalReference.Type type) {
        if (component.getExternalReferences() != null && !component.getExternalReferences().isEmpty()) {
            for (final ExternalReference ref : component.getExternalReferences()) {
                if (type == ref.getType()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addExternalReference(final ExternalReference.Type referenceType, final String url,
            final org.cyclonedx.model.Component component) {
        if (url == null) {
            return;
        }
        try {
            final URI uri = new URI(url.trim());
            final ExternalReference ref = new ExternalReference();
            ref.setType(referenceType);
            ref.setUrl(uri.toString());
            component.addExternalReference(ref);
        } catch (URISyntaxException e) {
            // throw it away
        }
    }

    private void addToolInfo(Bom bom) {

        var tool = new Tool();
        tool.setName(mojoExecution.getArtifactId());
        tool.setVendor(mojoExecution.getGroupId());
        tool.setVersion(mojoExecution.getVersion());
        bom.getMetadata().setTools(List.of(tool));

        var toolLocation = getToolLocation();
        if (toolLocation == null) {
            return;
        }

        if (!Files.isDirectory(toolLocation)) {
            final byte[] bytes;
            try {
                bytes = Files.readAllBytes(toolLocation);
            } catch (IOException e) {
                getLog().warn("Failed to read the tool's binary", e);
                return;
            }

            final List<Hash> hashes = new ArrayList<>(HASH_ALGS.size());
            for (String alg : HASH_ALGS) {
                var hash = getHash(alg, bytes);
                if (hash != null) {
                    hashes.add(hash);
                }
            }
            if (!hashes.isEmpty()) {
                tool.setHashes(hashes);
            }
        } else {
            getLog().warn("skipping tool hashing because " + toolLocation + " appears to be a directory");
        }
    }

    private Hash getHash(String alg, byte[] content) {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance(alg);
        } catch (NoSuchAlgorithmException e) {
            getLog().warn("Failed to initialize a message digest with algorithm " + alg + ": " + e.getLocalizedMessage());
            return null;
        }
        return new Hash(md.getAlgorithm(), toHexString(md.digest(content)));
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String toHexString(byte[] b) {
        char[] c = new char[b.length * 2];

        for (int i = 0; i < b.length; ++i) {
            int v = b[i] & 255;
            c[i * 2] = HEX[v >>> 4];
            c[i * 2 + 1] = HEX[v & 15];
        }

        return new String(c);
    }

    private Path getToolLocation() {
        var cs = getClass().getProtectionDomain().getCodeSource();
        if (cs == null) {
            getLog().warn("Failed to determine code source of the tool");
            return null;
        }
        var url = cs.getLocation();
        if (url == null) {
            getLog().warn("Failed to determine code source URL of the tool");
            return null;
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            getLog().warn("Failed to translate " + url + " to a file system path", e);
            return null;
        }
    }
}
