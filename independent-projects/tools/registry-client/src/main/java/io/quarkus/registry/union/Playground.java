package io.quarkus.registry.union;

import io.quarkus.registry.union.ElementCatalogBuilder.MemberBuilder;
import io.quarkus.registry.union.ElementCatalogBuilder.UnionBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class Playground {

    public static void main(String[] args) throws Exception {

        final ElementCatalogBuilder catalogBuilder = ElementCatalogBuilder.newInstance();

        UnionBuilder unionBuilder = catalogBuilder.getOrCreateUnion(1);
        MemberBuilder kogito = unionBuilder.getOrCreateMember("kogito", "1.1.1");
        kogito.addElement("kogito-e1");
        kogito.addElement("kogito-e2");

        MemberBuilder camel = unionBuilder.getOrCreateMember("camel", "1.2.2");
        camel.addElement("camel-e1");
        camel.addElement("camel-e2");

        unionBuilder = catalogBuilder.getOrCreateUnion(2);
        kogito = unionBuilder.getOrCreateMember("kogito", "2.1.1");
        kogito.addElement("kogito-e1");
        kogito.addElement("kogito-e2");

        camel = unionBuilder.getOrCreateMember("camel", "2.2.2");
        camel.addElement("camel-e1");
        camel.addElement("camel-e2");

        unionBuilder = catalogBuilder.getOrCreateUnion(3);
        kogito = unionBuilder.getOrCreateMember("kogito", "3.1.1");
        kogito.addElement("kogito-e1");
        kogito.addElement("kogito-e2");

        unionBuilder = catalogBuilder.getOrCreateUnion(4);
        kogito = unionBuilder.getOrCreateMember("kogito", "4.1.1");
        kogito.addElement("kogito-e1");
        kogito.addElement("kogito-e2");

        final ElementCatalog catalog = catalogBuilder.build();

        log(catalog);

        List<Member> members = membersFor(catalog, "kogito-e2");

        final StringBuilder buf = new StringBuilder();
        buf.append("Selected members: ");
        if (members.isEmpty()) {
            buf.append("NONE");
        } else {
            buf.append(members.get(0).key());
            for (int i = 1; i < members.size(); ++i) {
                buf.append(", ").append(members.get(i).key());
            }
        }
        System.out.println(buf);
    }

    private static List<Member> membersFor(ElementCatalog catalog, Object... elementKeys) {

        final List<Object> eKeyList = Arrays.asList(elementKeys);
        final Comparator<UnionVersion> comparator = UnionVersion::compareTo;
        final Map<UnionVersion, Map<String, Member>> unionVersions = new TreeMap<>(comparator.reversed());
        for (Object elementKey : elementKeys) {
            final Element e = catalog.get(elementKey);
            if (e == null) {
                throw new RuntimeException("Element " + elementKey + " not found in the catalog");
            }
            for (Member m : e.members()) {
                for (UnionVersion uv : m.unions()) {
                    unionVersions.computeIfAbsent(uv, v -> new HashMap<>()).put(m.key() + ":" + m.version(), m);
                }
            }
        }

        for (Map<String, Member> members : unionVersions.values()) {
            final Set<Object> memberElementKeys = new HashSet<>();
            final Iterator<Member> i = members.values().iterator();
            Member m = null;
            while (i.hasNext()) {
                m = i.next();
                memberElementKeys.addAll(m.elementKeys());
            }
            if (memberElementKeys.containsAll(eKeyList)) {
                return new ArrayList<>(members.values());
            }
        }
        return Collections.emptyList();
    }

    private static void log(ElementCatalog catalog) {
        catalog.elements().stream().map(Element::key).sorted().map(catalog::get).forEach(e -> {
            System.out.println("element: " + e.key());
            for (Member m : e.members()) {
                System.out.println("  member: " + m.key() + " @ union: " + m.initialUnion().verion());
            }
        });
    }
}
