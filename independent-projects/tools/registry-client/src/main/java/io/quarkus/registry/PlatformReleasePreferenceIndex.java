package io.quarkus.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class PlatformReleasePreferenceIndex {

    private final String platformKey;
    private final int platformIndex;
    private final List<String> releaseVersions = new ArrayList<>(1);

    public PlatformReleasePreferenceIndex(String platformKey, int platformIndex) {
        this.platformKey = Objects.requireNonNull(platformKey, "Platform key is null");
        this.platformIndex = platformIndex;
    }

    String getPlatformKey() {
        return platformKey;
    }

    int getPlatformIndex() {
        return platformIndex;
    }

    int getReleaseIndex(String version) {
        int i = releaseVersions.indexOf(version);
        if (i < 0) {
            i = releaseVersions.size();
            releaseVersions.add(version);
        }
        return i;
    }
}
