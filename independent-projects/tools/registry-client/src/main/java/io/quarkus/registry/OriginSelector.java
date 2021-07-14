package io.quarkus.registry;

import java.util.ArrayList;
import java.util.List;

public class OriginSelector {

    private final List<ExtensionOrigins> extOrigins;
    private final List<OriginCombination> completeCombinations = new ArrayList<>();

    public OriginSelector(List<ExtensionOrigins> extOrigins) {
        this.extOrigins = extOrigins;
    }

    public void calculateCompatibleCombinations() {
        if (extOrigins.isEmpty()) {
            return;
        }
        select(0, new OriginCombination(extOrigins.size()));

        if (completeCombinations.isEmpty()) {
            System.out.println("Failed to resolve a compatible combination of platform and non-platform extensions");
        } else {
            for (int i = 0; i < completeCombinations.size(); ++i) {
                final OriginCombination s = completeCombinations.get(i);
                System.out.println("Selection #" + (i + 1));
                s.getSelectedOrigins()
                        .forEach(o -> System.out.println(" - " + o.getCatalog().getBom() + " " + o.getCatalog().isPlatform()));
            }
        }
    }

    public OriginCombination getRecommendedCombination() {
        if (completeCombinations.isEmpty()) {
            return null;
        }
        if (completeCombinations.size() == 1) {
            return completeCombinations.get(0);
        }
        // TODO
        // here we are going to be looking for the combination that include the most extensions
        // in the most preferred registry with the lowest total number of platforms BOMs to be imported
        for (OriginCombination s : completeCombinations) {

        }
        return completeCombinations.get(0);
    }

    private void select(int extIndex, OriginCombination combination) {
        if (extIndex >= extOrigins.size()) {
            throw new IllegalArgumentException(
                    "Extension index " + extIndex + " exceeded the total number of extensions " + extOrigins.size());
        }
        final ExtensionOrigins eo = extOrigins.get(extIndex);
        for (OriginWithPreference o : eo.getOrigins()) {
            final OriginCombination augmentedCombination = combination.add(eo.getExtensionKey(), o);
            if (augmentedCombination == null) {
                continue;
            }
            if (augmentedCombination.isComplete()) {
                completeCombinations.add(augmentedCombination);
                // we are collecting all possible combinations for now
                continue;
            }
            if (extIndex + 1 == extOrigins.size()) {
                return;
            }
            select(extIndex + 1, augmentedCombination);
        }
    }
}
