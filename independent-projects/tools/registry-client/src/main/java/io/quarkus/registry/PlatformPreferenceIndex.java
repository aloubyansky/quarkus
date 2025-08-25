package io.quarkus.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PlatformPreferenceIndex {

    private final Map<Integer, List<PlatformReleasePreferenceIndex>> releaseIndeces = new HashMap<>();

    PlatformReleasePreferenceIndex getReleaseIndex(int registryIndex, String platformKey) {
        var list = releaseIndeces.computeIfAbsent(registryIndex, k -> new ArrayList<>(1));
        for (int i = 0; i < list.size(); ++i) {
            final PlatformReleasePreferenceIndex candidate = list.get(i);
            if (candidate.getPlatformKey().equals(platformKey)) {
                return candidate;
            }
        }
        final PlatformReleasePreferenceIndex result = new PlatformReleasePreferenceIndex(platformKey, list.size());
        list.add(result);
        return result;
    }
}
