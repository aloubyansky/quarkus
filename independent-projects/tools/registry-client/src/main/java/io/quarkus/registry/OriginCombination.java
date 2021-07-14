package io.quarkus.registry;

import io.quarkus.maven.ArtifactKey;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class OriginCombination {

    private final int extensionsToInclude;
    private final int includedExtensions;
    private final OriginWithPreference[] selectedOrigins;

    public OriginCombination(int extensionsToInclude) {
        this.extensionsToInclude = extensionsToInclude;
        includedExtensions = 0;
        selectedOrigins = new OriginWithPreference[0];
    }

    private OriginCombination(int targetSize, int includedExtensions, OriginWithPreference[] selectedOrigins) {
        this.extensionsToInclude = targetSize;
        this.includedExtensions = includedExtensions;
        this.selectedOrigins = selectedOrigins;
        if (isComplete()) {
            Arrays.sort(selectedOrigins, new Comparator<OriginWithPreference>() {
                @Override
                public int compare(OriginWithPreference o1, OriginWithPreference o2) {
                    return o1.getPreference().compareTo(o2.getPreference());
                }
            });
        }
    }

    OriginCombination add(ArtifactKey extKey, OriginWithPreference origin) {
        for (OriginWithPreference selectedOrigin : selectedOrigins) {
            if (selectedOrigin.isSameAs(origin)) {
                return new OriginCombination(extensionsToInclude, includedExtensions + 1, selectedOrigins);
            }
            if (!selectedOrigin.canBeCombinedWith(origin)) {
                return null;
            }
        }
        return new OriginCombination(extensionsToInclude, includedExtensions + 1, addLast(selectedOrigins, origin));
    }

    public List<OriginWithPreference> getSelectedOrigins() {
        return Arrays.asList(selectedOrigins);
    }

    public boolean isComplete() {
        return includedExtensions == extensionsToInclude;
    }

    private static <T> T[] addLast(T[] arr, T item) {
        final T[] copy = Arrays.copyOf(arr, arr.length + 1);
        copy[copy.length - 1] = item;
        return copy;
    }
}
