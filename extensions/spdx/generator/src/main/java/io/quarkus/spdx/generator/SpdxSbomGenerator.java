package io.quarkus.spdx.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.maven.model.Model;
import org.jboss.logging.Logger;
import org.spdx.core.DefaultModelStore;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.jacksonstore.MultiFormatStore;
import org.spdx.jacksonstore.MultiFormatStore.Format;
import org.spdx.library.LicenseInfoFactory;
import org.spdx.library.ModelCopyManager;
import org.spdx.library.SpdxModelFactory;
import org.spdx.library.model.v2.Checksum;
import org.spdx.library.model.v2.Relationship;
import org.spdx.library.model.v2.SpdxCreatorInformation;
import org.spdx.library.model.v2.SpdxDocument;
import org.spdx.library.model.v2.SpdxPackage;
import org.spdx.library.model.v2.enumerations.ChecksumAlgorithm;
import org.spdx.library.model.v2.enumerations.RelationshipType;
import org.spdx.library.model.v2.license.AnyLicenseInfo;
import org.spdx.library.model.v2.license.SpdxNoAssertionLicense;
import org.spdx.storage.IModelStore;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.InMemSpdxStore;
import org.spdx.tagvaluestore.TagValueStore;

import io.quarkus.bootstrap.app.SbomResult;
import io.quarkus.bootstrap.resolver.maven.EffectiveModelResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.sbom.ApplicationComponent;
import io.quarkus.sbom.ApplicationManifest;

public class SpdxSbomGenerator {

    private static final Logger log = Logger.getLogger(SpdxSbomGenerator.class);

    private static final String SPDX_VERSION = "SPDX-2.3";
    private static final String DATA_LICENSE = "CC0-1.0";

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

    private static final String CLASSIFIER_SPDX = "spdx";
    private static final String FORMAT_ALL = "all";
    private static final String FORMAT_JSON = "json";
    private static final String FORMAT_TAG_VALUE = "tag-value";
    private static final String DEFAULT_FORMAT = FORMAT_JSON;
    private static final List<String> SUPPORTED_FORMATS = List.of(FORMAT_JSON, FORMAT_TAG_VALUE);

    public static SpdxSbomGenerator newInstance() {
        return new SpdxSbomGenerator();
    }

    private boolean generated;
    private ApplicationManifest manifest;
    private Path outputDir;
    private Path outputFile;
    private String schemaVersion;
    private String format;
    private EffectiveModelResolver modelResolver;
    private boolean includeLicenseText;

    private IModelStore modelStore;
    private ModelCopyManager copyManager;
    private String documentUri;
    private Map<String, String> componentToSpdxId;
    private int spdxIdCounter;

    private SpdxSbomGenerator() {
    }

    public SpdxSbomGenerator setManifest(ApplicationManifest manifest) {
        ensureNotGenerated();
        this.manifest = manifest;
        return this;
    }

    public SpdxSbomGenerator setOutputDirectory(Path outputDir) {
        ensureNotGenerated();
        this.outputDir = outputDir;
        return this;
    }

    public SpdxSbomGenerator setOutputFile(Path outputFile) {
        ensureNotGenerated();
        this.outputFile = outputFile;
        return this;
    }

    public SpdxSbomGenerator setFormat(String format) {
        ensureNotGenerated();
        this.format = format;
        return this;
    }

    public SpdxSbomGenerator setSchemaVersion(String schemaVersion) {
        ensureNotGenerated();
        this.schemaVersion = schemaVersion;
        return this;
    }

    public SpdxSbomGenerator setEffectiveModelResolver(EffectiveModelResolver modelResolver) {
        ensureNotGenerated();
        this.modelResolver = modelResolver;
        return this;
    }

    public SpdxSbomGenerator setIncludeLicenseText(boolean includeLicenseText) {
        ensureNotGenerated();
        this.includeLicenseText = includeLicenseText;
        return this;
    }

    public List<SbomResult> generate() {
        ensureNotGenerated();
        Objects.requireNonNull(manifest, "Manifest is null");
        if (outputFile == null && outputDir == null) {
            throw new IllegalArgumentException("Either outputDir or outputFile must be provided");
        }
        generated = true;

        try {
            SpdxModelFactory.init();

            if (FORMAT_ALL.equalsIgnoreCase(format)) {
                if (outputFile != null) {
                    throw new IllegalArgumentException("Can't use output file " + outputFile + " with format '"
                            + FORMAT_ALL + "', since it implies generating multiple files");
                }
                final List<SbomResult> result = new ArrayList<>(SUPPORTED_FORMATS.size());
                for (String fmt : SUPPORTED_FORMATS) {
                    result.add(generateForFormat(fmt, getOutputFile(fmt)));
                }
                return result;
            }
            var outFile = getOutputFile(format == null ? DEFAULT_FORMAT : format);
            return List.of(generateForFormat(getFormat(outFile), outFile));
        } catch (InvalidSPDXAnalysisException e) {
            throw new RuntimeException("Failed to generate SPDX SBOM", e);
        }
    }

    private SbomResult generateForFormat(String format, Path sbomFile) throws InvalidSPDXAnalysisException {
        ISerializableModelStore serializableStore;
        if (format.equalsIgnoreCase(FORMAT_JSON)) {
            serializableStore = new MultiFormatStore(new InMemSpdxStore(), Format.JSON_PRETTY);
        } else if (format.equalsIgnoreCase(FORMAT_TAG_VALUE)) {
            serializableStore = new TagValueStore(new InMemSpdxStore());
        } else {
            throw new RuntimeException(
                    "Unsupported SBOM format " + format + ", supported formats are json and tag-value");
        }

        modelStore = serializableStore;
        copyManager = new ModelCopyManager();
        componentToSpdxId = new HashMap<>();
        spdxIdCounter = 0;

        var mainComponent = manifest.getMainComponent();
        String appName = getComponentName(mainComponent);
        documentUri = "https://spdx.org/spdxdocs/" + appName + "-" + System.currentTimeMillis();

        DefaultModelStore.initialize(modelStore, documentUri, copyManager);

        var document = createSpdxDocument(appName);

        var mainPackage = createPackage(mainComponent, true);
        document.getDocumentDescribes().add(mainPackage);

        for (var component : manifest.getComponents()) {
            var pkg = createPackage(component, false);
            document.getDocumentDescribes().add(pkg);
        }

        addRelationships(document, mainComponent, manifest.getComponents());

        return persistSbom(serializableStore, sbomFile, format);
    }

    private SpdxDocument createSpdxDocument(String name) throws InvalidSPDXAnalysisException {
        var creationInfo = new SpdxCreatorInformation(
                modelStore, documentUri, modelStore.getNextId(IModelStore.IdType.Anonymous),
                copyManager, true);
        creationInfo.getCreators().add("Tool: Quarkus SPDX Generator");
        creationInfo.setCreated(ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));

        var document = new SpdxDocument(modelStore, documentUri, copyManager, true);
        document.setSpecVersion(getEffectiveSchemaVersion());
        document.setName(name);
        document.setCreationInfo(creationInfo);

        var dataLicense = LicenseInfoFactory.parseSPDXLicenseStringCompatV2(DATA_LICENSE);
        document.setDataLicense(dataLicense);

        return document;
    }

    private SpdxPackage createPackage(ApplicationComponent component, boolean isMain) throws InvalidSPDXAnalysisException {
        String spdxId = generateSpdxId(component);
        var dep = component.getResolvedDependency();

        String name;
        String version = "";
        String downloadLocation = "NOASSERTION";

        if (dep != null) {
            name = dep.getGroupId() + ":" + dep.getArtifactId();
            version = dep.getVersion();
            downloadLocation = getMavenDownloadLocation(dep);
        } else if (component.getDistributionPath() != null) {
            name = component.getDistributionPath();
        } else if (component.getPath() != null) {
            name = component.getPath().getFileName().toString();
        } else {
            throw new RuntimeException("Component is not associated with any file system path");
        }

        var pkg = new SpdxPackage(modelStore, documentUri, spdxId, copyManager, true);
        pkg.setName(name);
        if (!version.isEmpty()) {
            pkg.setVersionInfo(version);
        }
        pkg.setDownloadLocation(downloadLocation);
        pkg.setFilesAnalyzed(false);

        AnyLicenseInfo concludedLicense = new SpdxNoAssertionLicense();
        AnyLicenseInfo declaredLicense = new SpdxNoAssertionLicense();

        if (dep != null) {
            var model = modelResolver == null ? null : modelResolver.resolveEffectiveModel(dep);
            if (model != null) {
                extractPackageMetadata(model, pkg);
                declaredLicense = extractLicense(model);
            }
        }

        pkg.setLicenseConcluded(concludedLicense);
        pkg.setLicenseDeclared(declaredLicense);
        pkg.setCopyrightText("NOASSERTION");

        if (component.getPath() != null && Files.exists(component.getPath())) {
            addChecksums(pkg, component.getPath());
        }

        if (component.getDistributionPath() != null) {
            pkg.setComment("Distribution path: " + component.getDistributionPath());
        }

        return pkg;
    }

    private String generateSpdxId(ApplicationComponent component) {
        var dep = component.getResolvedDependency();
        String key;
        if (dep != null) {
            key = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
        } else if (component.getDistributionPath() != null) {
            key = component.getDistributionPath();
        } else if (component.getPath() != null) {
            key = component.getPath().toString();
        } else {
            key = "component-" + (++spdxIdCounter);
        }

        String spdxId = "SPDXRef-Package-" + sanitizeForSpdxId(key);
        componentToSpdxId.put(key, spdxId);
        return spdxId;
    }

    private String getComponentKey(ApplicationComponent component) {
        var dep = component.getResolvedDependency();
        if (dep != null) {
            return dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
        } else if (component.getDistributionPath() != null) {
            return component.getDistributionPath();
        } else if (component.getPath() != null) {
            return component.getPath().toString();
        }
        return null;
    }

    private String sanitizeForSpdxId(String input) {
        return input.replaceAll("[^a-zA-Z0-9.-]", "-");
    }

    private String getMavenDownloadLocation(ArtifactCoords coords) {
        return String.format("https://repo1.maven.org/maven2/%s/%s/%s/%s-%s.%s",
                coords.getGroupId().replace('.', '/'),
                coords.getArtifactId(),
                coords.getVersion(),
                coords.getArtifactId(),
                coords.getVersion(),
                coords.getType());
    }

    private void extractPackageMetadata(Model model, SpdxPackage pkg) throws InvalidSPDXAnalysisException {
        if (model.getDescription() != null) {
            pkg.setDescription(model.getDescription());
        }
        if (model.getOrganization() != null && model.getOrganization().getName() != null) {
            pkg.setSupplier("Organization: " + model.getOrganization().getName());
        }
        if (model.getUrl() != null) {
            pkg.setHomepage(model.getUrl());
        }
    }

    private AnyLicenseInfo extractLicense(Model model) throws InvalidSPDXAnalysisException {
        if (model.getLicenses() == null || model.getLicenses().isEmpty()) {
            return new SpdxNoAssertionLicense();
        }

        var license = model.getLicenses().get(0);
        String licenseName = license.getName();
        if (licenseName == null || licenseName.trim().isEmpty()) {
            return new SpdxNoAssertionLicense();
        }

        try {
            return LicenseInfoFactory.parseSPDXLicenseStringCompatV2(mapToSpdxLicenseId(licenseName));
        } catch (InvalidSPDXAnalysisException e) {
            log.debugf("Could not parse license '%s' as SPDX license, using NOASSERTION", licenseName);
            return new SpdxNoAssertionLicense();
        }
    }

    private String mapToSpdxLicenseId(String licenseName) {
        if (licenseName == null) {
            return "NOASSERTION";
        }
        String normalized = licenseName.toLowerCase().trim();

        if (normalized.contains("apache") && normalized.contains("2")) {
            return "Apache-2.0";
        }
        if (normalized.contains("mit")) {
            return "MIT";
        }
        if (normalized.contains("bsd") && normalized.contains("3")) {
            return "BSD-3-Clause";
        }
        if (normalized.contains("bsd") && normalized.contains("2")) {
            return "BSD-2-Clause";
        }
        if (normalized.contains("lgpl") && normalized.contains("2.1")) {
            return "LGPL-2.1-only";
        }
        if (normalized.contains("lgpl") && normalized.contains("3")) {
            return "LGPL-3.0-only";
        }
        if (normalized.contains("gpl") && normalized.contains("2")) {
            return "GPL-2.0-only";
        }
        if (normalized.contains("gpl") && normalized.contains("3")) {
            return "GPL-3.0-only";
        }
        if (normalized.contains("eclipse") && normalized.contains("1.0")) {
            return "EPL-1.0";
        }
        if (normalized.contains("eclipse") && normalized.contains("2.0")) {
            return "EPL-2.0";
        }
        if (normalized.contains("mpl") && normalized.contains("2")) {
            return "MPL-2.0";
        }
        if (normalized.contains("cc0") || normalized.equals("public domain")) {
            return "CC0-1.0";
        }

        return licenseName;
    }

    private void addChecksums(SpdxPackage pkg, Path filePath) throws InvalidSPDXAnalysisException {
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);

            String sha256 = calculateHash(fileBytes, "SHA-256");
            var sha256Checksum = new Checksum(modelStore, documentUri,
                    modelStore.getNextId(IModelStore.IdType.Anonymous), copyManager, true);
            sha256Checksum.setAlgorithm(ChecksumAlgorithm.SHA256);
            sha256Checksum.setValue(sha256);
            pkg.getChecksums().add(sha256Checksum);

            String sha1 = calculateHash(fileBytes, "SHA-1");
            var sha1Checksum = new Checksum(modelStore, documentUri,
                    modelStore.getNextId(IModelStore.IdType.Anonymous), copyManager, true);
            sha1Checksum.setAlgorithm(ChecksumAlgorithm.SHA1);
            sha1Checksum.setValue(sha1);
            pkg.getChecksums().add(sha1Checksum);

            String md5 = calculateHash(fileBytes, "MD5");
            var md5Checksum = new Checksum(modelStore, documentUri,
                    modelStore.getNextId(IModelStore.IdType.Anonymous), copyManager, true);
            md5Checksum.setAlgorithm(ChecksumAlgorithm.MD5);
            md5Checksum.setValue(md5);
            pkg.getChecksums().add(md5Checksum);

        } catch (IOException e) {
            log.warnf("Failed to calculate checksums for %s: %s", filePath, e.getMessage());
        }
    }

    private String calculateHash(byte[] data, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not available: " + algorithm, e);
        }
    }

    private void addRelationships(SpdxDocument document, ApplicationComponent mainComponent,
            Collection<ApplicationComponent> components) throws InvalidSPDXAnalysisException {

        Map<String, ApplicationComponent> componentMap = new HashMap<>();
        componentMap.put(getComponentKey(mainComponent), mainComponent);
        for (var comp : components) {
            String key = getComponentKey(comp);
            if (key != null) {
                componentMap.put(key, comp);
            }
        }

        addDependencyRelationships(mainComponent);
        for (var comp : components) {
            addDependencyRelationships(comp);
        }
    }

    private void addDependencyRelationships(ApplicationComponent component) throws InvalidSPDXAnalysisException {
        var dependencies = component.getDependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            return;
        }

        String sourceSpdxId = componentToSpdxId.get(getComponentKey(component));
        if (sourceSpdxId == null) {
            return;
        }

        var sourceElement = new SpdxPackage(modelStore, documentUri, sourceSpdxId, copyManager, false);

        for (var depCoords : sortAlphabetically(dependencies)) {
            String depKey = depCoords.getGroupId() + ":" + depCoords.getArtifactId() + ":" + depCoords.getVersion();
            String targetSpdxId = componentToSpdxId.get(depKey);
            if (targetSpdxId == null) {
                continue;
            }

            var targetElement = new SpdxPackage(modelStore, documentUri, targetSpdxId, copyManager, false);

            var relationship = new Relationship(modelStore, documentUri,
                    modelStore.getNextId(IModelStore.IdType.Anonymous), copyManager, true);
            relationship.setRelatedSpdxElement(targetElement);
            relationship.setRelationshipType(RelationshipType.DEPENDS_ON);
            sourceElement.getRelationships().add(relationship);
        }
    }

    private static List<ArtifactCoords> sortAlphabetically(Collection<ArtifactCoords> col) {
        var list = new ArrayList<>(col);
        list.sort(ARTIFACT_COORDS_COMPARATOR);
        return list;
    }

    private SbomResult persistSbom(ISerializableModelStore serializableStore, Path sbomFile, String format) {
        var outDir = sbomFile.getParent();
        if (outDir != null) {
            try {
                Files.createDirectories(outDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        try (var outputStream = Files.newOutputStream(sbomFile)) {
            serializableStore.serialize(outputStream);
            if (log.isDebugEnabled()) {
                log.debug("Generated SPDX SBOM at: " + sbomFile);
            }
        } catch (IOException | InvalidSPDXAnalysisException e) {
            throw new RuntimeException("Failed to write SPDX SBOM to " + sbomFile, e);
        }

        return new SbomResult(sbomFile, "SPDX", getEffectiveSchemaVersion(), format, CLASSIFIER_SPDX,
                manifest.getRunnerPath());
    }

    private Path getOutputFile(String defaultFormat) {
        if (outputFile == null) {
            String ext = FORMAT_TAG_VALUE.equals(defaultFormat) ? "spdx" : defaultFormat;
            var fileName = toSbomFileName(manifest.getRunnerPath().getFileName().toString(), ext);
            return outputDir == null ? Path.of(fileName) : outputDir.resolve(fileName);
        }
        return outputFile;
    }

    private String toSbomFileName(String deliverableName, String extension) {
        return stripExtension(deliverableName) + "-" + CLASSIFIER_SPDX + "." + extension;
    }

    private static String stripExtension(String fileName) {
        var lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return fileName;
        }
        var lastDash = fileName.lastIndexOf('-');
        return lastDot < lastDash ? fileName : fileName.substring(0, lastDot);
    }

    private String getFormat(Path outputFile) {
        if (format == null || FORMAT_ALL.equalsIgnoreCase(format)) {
            var name = outputFile.getFileName().toString();
            var lastDot = name.lastIndexOf('.');
            if (lastDot < 0 || lastDot == name.length() - 1) {
                throw new IllegalArgumentException("Failed to determine file extension of " + outputFile);
            }
            String ext = name.substring(lastDot + 1);
            return "spdx".equals(ext) ? FORMAT_TAG_VALUE : ext;
        }
        return format;
    }

    private String getEffectiveSchemaVersion() {
        return schemaVersion != null ? schemaVersion : SPDX_VERSION;
    }

    private String getComponentName(ApplicationComponent component) {
        var dep = component.getResolvedDependency();
        if (dep != null) {
            return dep.getArtifactId();
        }
        if (component.getPath() != null) {
            return stripExtension(component.getPath().getFileName().toString());
        }
        return "application";
    }

    private void ensureNotGenerated() {
        if (generated) {
            throw new RuntimeException("This instance has already been used to generate an SBOM");
        }
    }
}
