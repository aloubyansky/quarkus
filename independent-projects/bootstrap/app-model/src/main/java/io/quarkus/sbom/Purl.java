package io.quarkus.sbom;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lightweight, immutable Package URL (PURL) representation following the
 * <a href="https://github.com/package-url/purl-spec">purl-spec</a>.
 * <p>
 * Format: {@code pkg:<type>/<namespace>/<name>@<version>?<qualifiers>#<subpath>}
 */
public final class Purl {

    private static final Pattern PURL_PATTERN = Pattern.compile(
            "^pkg:([^/]+)/([^@?#]+)(?:@([^?#]*))?(?:\\?([^#]*))?(?:#(.*))?$");

    public static final String TYPE_MAVEN = "maven";
    public static final String TYPE_NPM = "npm";
    public static final String TYPE_GENERIC = "generic";

    /**
     * Creates a Maven PURL with {@code type=jar} qualifier.
     *
     * @param groupId Maven groupId (used as namespace)
     * @param artifactId Maven artifactId (used as name)
     * @param version artifact version
     * @return a Maven PURL
     */
    public static Purl maven(String groupId, String artifactId, String version) {
        return maven(groupId, artifactId, version, "jar", null);
    }

    /**
     * Creates a Maven PURL with explicit type and classifier qualifiers.
     *
     * @param groupId Maven groupId (used as namespace)
     * @param artifactId Maven artifactId (used as name)
     * @param version artifact version
     * @param artifactType Maven artifact type (e.g., "jar", "war")
     * @param classifier Maven classifier, or null
     * @return a Maven PURL
     */
    public static Purl maven(String groupId, String artifactId, String version,
            String artifactType, String classifier) {
        Map<String, String> qualifiers = new TreeMap<String, String>();
        qualifiers.put("type", artifactType != null ? artifactType : "jar");
        if (classifier != null && !classifier.isEmpty()) {
            qualifiers.put("classifier", classifier);
        }
        return new Purl(TYPE_MAVEN, groupId, artifactId, version, qualifiers, null);
    }

    /**
     * Creates an npm PURL.
     *
     * @param namespace the npm scope (e.g., "@babel"), or null for unscoped packages
     * @param name the package name
     * @param version the package version
     * @return an npm PURL
     */
    public static Purl npm(String namespace, String name, String version) {
        return new Purl(TYPE_NPM, namespace, name, version, Collections.emptyMap(), null);
    }

    /**
     * Creates a generic PURL with no namespace or qualifiers.
     *
     * @param name the component name
     * @param version the component version
     * @return a generic PURL
     */
    public static Purl generic(String name, String version) {
        return new Purl(TYPE_GENERIC, null, name, version, Collections.emptyMap(), null);
    }

    /**
     * Creates a PURL with the specified type.
     *
     * @param type the package ecosystem type
     * @param namespace the namespace, or null
     * @param name the package name
     * @param version the version, or null
     * @return a PURL
     */
    public static Purl of(String type, String namespace, String name, String version) {
        return new Purl(type, namespace, name, version, Collections.emptyMap(), null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses a canonical PURL string.
     *
     * @param purlString a PURL string starting with "pkg:"
     * @return the parsed Purl
     * @throws IllegalArgumentException if the string is not a valid PURL
     */
    public static Purl parse(String purlString) {
        Objects.requireNonNull(purlString, "purlString is null");
        Matcher m = PURL_PATTERN.matcher(purlString);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid PURL: " + purlString);
        }

        String type = m.group(1).toLowerCase();
        String namespaceName = m.group(2);
        String versionRaw = m.group(3);
        String qualifiersRaw = m.group(4);
        String subpathRaw = m.group(5);

        String namespace = null;
        String name;
        int lastSlashIdx = namespaceName.lastIndexOf('/');
        if (lastSlashIdx < 0) {
            name = percentDecode(namespaceName);
        } else {
            name = percentDecode(namespaceName.substring(lastSlashIdx + 1));
            namespace = decodePath(namespaceName.substring(0, lastSlashIdx));
        }

        String version = versionRaw != null ? percentDecode(versionRaw) : null;

        Map<String, String> qualifiers = null;
        if (qualifiersRaw != null) {
            String[] qualifierArray = qualifiersRaw.split("&");
            for (String pair : qualifierArray) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx > 0) {
                    String key = pair.substring(0, eqIdx).toLowerCase();
                    String value = percentDecode(pair.substring(eqIdx + 1));
                    if (qualifiers == null) {
                        if (qualifierArray.length == 1) {
                            qualifiers = Map.of(key, value);
                        } else {
                            qualifiers = new TreeMap<>();
                            qualifiers.put(key, value);
                        }
                    } else {
                        qualifiers.put(key, value);
                    }
                } else if (eqIdx < 0) {
                    throw new IllegalArgumentException(
                            "Invalid PURL qualifier '" + pair + "': missing '=' in " + purlString);
                } else {
                    throw new IllegalArgumentException(
                            "Invalid PURL qualifier '" + pair + "': empty key in " + purlString);
                }
            }
        }

        String subpath = subpathRaw != null ? decodePath(subpathRaw) : null;
        if (subpath != null && subpath.isEmpty()) {
            subpath = null;
        }

        return new Purl(type, namespace, name, version, qualifiers, subpath);
    }

    private final String type;
    private final String namespace;
    private final String name;
    private final String version;
    private final Map<String, String> qualifiers;
    private final String subpath;
    private String canonical;

    private Purl(String type, String namespace, String name, String version,
            Map<String, String> qualifiers, String subpath) {
        this.type = Objects.requireNonNull(type, "type is required").toLowerCase();
        this.namespace = namespace == null || namespace.isEmpty() ? null : namespace;
        this.name = Objects.requireNonNull(name, "name is required");
        this.version = version;
        this.qualifiers = qualifiers == null || qualifiers.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(qualifiers));
        this.subpath = subpath;
    }

    public String getType() {
        return type;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getQualifiers() {
        return qualifiers;
    }

    public String getSubpath() {
        return subpath;
    }

    /**
     * Returns the canonical PURL string representation.
     */
    @Override
    public String toString() {
        if (canonical == null) {
            canonical = canonicalize();
        }
        return canonical;
    }

    private String canonicalize() {
        StringBuilder sb = new StringBuilder("pkg:");
        sb.append(type).append('/');
        if (namespace != null) {
            sb.append(encodePath(namespace)).append('/');
        }
        sb.append(percentEncode(name));
        if (version != null) {
            sb.append('@').append(percentEncode(version));
        }
        if (!qualifiers.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : qualifiers.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(entry.getKey().toLowerCase())
                        .append('=')
                        .append(percentEncodeQualifierValue(entry.getValue()));
                first = false;
            }
        }
        if (subpath != null) {
            sb.append('#').append(encodePath(subpath));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Purl other)) {
            return false;
        }
        return type.equals(other.type)
                && name.equals(other.name)
                && Objects.equals(namespace, other.namespace)
                && Objects.equals(version, other.version)
                && Objects.equals(subpath, other.subpath)
                && qualifiers.equals(other.qualifiers);
    }

    @Override
    public int hashCode() {
        int h = type.hashCode();
        h = 31 * h + (namespace != null ? namespace.hashCode() : 0);
        h = 31 * h + name.hashCode();
        h = 31 * h + (version != null ? version.hashCode() : 0);
        h = 31 * h + qualifiers.hashCode();
        h = 31 * h + (subpath != null ? subpath.hashCode() : 0);
        return h;
    }

    /**
     * Percent-encodes a string per RFC 3986. Only unreserved characters
     * ({@code A-Z a-z 0-9 . - _ ~}) are left unencoded; everything else
     * (including {@code : / @}) is percent-encoded.
     * <p>
     * Used for type, namespace segments, name, and version components.
     * For qualifier values use {@link #percentEncodeQualifierValue(String)}.
     */
    static String percentEncode(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        int i = 0;
        // As an optimization, scan characters looking for one that requires encoding.
        // If none is found the original string is returned as-is.
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c > 0x7F || !isUnreserved(c)) {
                break;
            }
            i++;
        }
        if (i == input.length()) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length() + 2);
        sb.append(input, 0, i);
        for (byte b : input.substring(i).getBytes(StandardCharsets.UTF_8)) {
            if (isUnreserved(b)) {
                sb.append((char) b);
            } else {
                appendPercentEncoded(sb, b);
            }
        }
        return sb.toString();
    }

    /**
     * Decodes percent-encoded triplets ({@code %XX}) back to characters.
     * Handles both encoded and unencoded input, so it correctly parses
     * qualifier values regardless of whether the producer encoded
     * {@code /} and {@code :} (Java implementations) or left them
     * unencoded (Python implementations, purl spec test suite).
     */
    static String percentDecode(String input) {
        // no percent signs means nothing to decode
        if (input == null || input.indexOf('%') < 0) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        int pos = 0;
        int pct;
        while ((pct = input.indexOf('%', pos)) >= 0) {
            sb.append(input, pos, pct);
            // collect consecutive %XX triplets into a byte[] for proper multi-byte UTF-8 decoding
            // (e.g. %C3%A9 -> two bytes -> one character é)
            int tripletStart = pct;
            int byteCount = 0;
            byte[] bytes = null;
            while (pct + 2 < input.length() && input.charAt(pct) == '%') {
                int hi = Character.digit(input.charAt(pct + 1), 16);
                int lo = Character.digit(input.charAt(pct + 2), 16);
                if (hi < 0 || lo < 0) {
                    break;
                }
                if (bytes == null) {
                    // each %XX triplet is 3 chars, so the max number of decoded bytes is the remaining length / 3
                    bytes = new byte[(input.length() - tripletStart) / 3];
                }
                bytes[byteCount++] = (byte) ((hi << 4) | lo);
                pct += 3;
            }
            if (byteCount > 0) {
                sb.append(new String(bytes, 0, byteCount, StandardCharsets.UTF_8));
                pos = pct;
            } else {
                // lone % that isn't a valid triplet, keep it as-is
                sb.append('%');
                pos = tripletStart + 1;
            }
        }
        sb.append(input, pos, input.length());
        return sb.toString();
    }

    private static String encodePath(String path) {
        int slash = path.indexOf('/');
        if (slash < 0) {
            return percentEncode(path);
        }
        StringBuilder sb = new StringBuilder(path.length());
        int pos = 0;
        while (slash >= 0) {
            sb.append(percentEncode(path.substring(pos, slash))).append('/');
            pos = slash + 1;
            slash = path.indexOf('/', pos);
        }
        sb.append(percentEncode(path.substring(pos)));
        return sb.toString();
    }

    private static String decodePath(String path) {
        int slash = path.indexOf('/');
        if (slash < 0) {
            return percentDecode(path);
        }
        StringBuilder sb = new StringBuilder(path.length());
        int pos = 0;
        while (slash >= 0) {
            sb.append(percentDecode(path.substring(pos, slash))).append('/');
            pos = slash + 1;
            slash = path.indexOf('/', pos);
        }
        sb.append(percentDecode(path.substring(pos)));
        return sb.toString();
    }

    /**
     * Percent-encodes a qualifier value per the purl spec.
     * <p>
     * In addition to the RFC 3986 unreserved set ({@code A-Z a-z 0-9 . - _ ~}),
     * {@code /} and {@code :} are left unencoded:
     * <ul>
     * <li>{@code :} — the spec states it must not be encoded "whether as a
     * separator or otherwise"</li>
     * <li>{@code /} — the official test suite expects unencoded slashes in
     * qualifier values (e.g. {@code repository_url=repo.acme.org/release})</li>
     * </ul>
     * <p>
     * {@code @} is still encoded because the spec only exempts it when used as
     * the name/version separator, not inside qualifier values.
     */
    static String percentEncodeQualifierValue(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        int i = 0;
        // As an optimization, scan characters looking for one that requires encoding first.
        // If none is found the original string is returned as-is.
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c > 0x7F || !(isUnreserved(c) || c == '/' || c == ':')) {
                break;
            }
            i++;
        }
        if (i == input.length()) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length() + 2);
        sb.append(input, 0, i);
        for (byte b : input.substring(i).getBytes(StandardCharsets.UTF_8)) {
            if (isUnreserved(b) || b == '/' || b == ':') {
                sb.append((char) b);
            } else {
                appendPercentEncoded(sb, b);
            }
        }
        return sb.toString();
    }

    private static void appendPercentEncoded(StringBuilder sb, byte b) {
        sb.append('%');
        int unsigned = b & 0xFF;
        sb.append(HEX_DIGITS[unsigned >> 4]);
        sb.append(HEX_DIGITS[unsigned & 0x0F]);
    }

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private static boolean isUnreserved(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    public static class Builder {

        private String type;
        private String namespace;
        private String name;
        private String version;
        private TreeMap<String, String> qualifiers;
        private String subpath;

        private Builder() {
        }

        public Builder setType(String type) {
            this.type = type;
            return this;
        }

        public Builder setNamespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setVersion(String version) {
            this.version = version;
            return this;
        }

        public Builder addQualifier(String key, String value) {
            if (qualifiers == null) {
                qualifiers = new TreeMap<>();
            }
            qualifiers.put(key, value);
            return this;
        }

        public Builder setQualifiers(Map<String, String> qualifiers) {
            this.qualifiers = qualifiers == null ? null : new TreeMap<>(qualifiers);
            return this;
        }

        public Builder setSubpath(String subpath) {
            this.subpath = subpath;
            return this;
        }

        public Purl build() {
            return new Purl(type, namespace, name, version, qualifiers, subpath);
        }
    }
}
