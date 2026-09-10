package io.github.michalmela;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped Plexus descriptor is how Maven finds the wagon, and it points two role-hints at one
 * implementation class.
 *
 * <p>Sisu binds the first occurrence of an implementation to the class itself and generates a cloned
 * type for every further one. A second hint on a {@code final} class therefore cannot be
 * constructed: the lookup fails with {@code TypeNotPresentException: Type S3Wagon$__sisu1 not
 * present} while the hint declared first keeps working. That asymmetry is why it went unnoticed -
 * {@code verify:maven} only ever asked for {@code s3p://}, so {@code s3://} was broken in every
 * published release with a green build behind it.
 */
class S3WagonComponentDescriptorTest {

    private static final String DESCRIPTOR = "META-INF/plexus/components.xml";

    @Test
    void declaresEveryDocumentedScheme() {
        Set<String> hints = componentsByImplementation().values().stream()
                .flatMap(Set::stream)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        // Both are promised by the README, and dropping either would silently break user builds
        // rather than fail anything.
        assertTrue(hints.contains("s3p"), () -> "s3p is not registered; " + DESCRIPTOR + " declares " + hints);
        assertTrue(hints.contains("s3"), () -> "s3 is not registered; " + DESCRIPTOR + " declares " + hints);
    }

    @Test
    void implementationsSharedBySeveralHintsCanBeCloned() throws Exception {
        for (Map.Entry<String, Set<String>> component : componentsByImplementation().entrySet()) {
            Set<String> hints = component.getValue();
            if (hints.size() < 2) {
                continue;
            }
            Class<?> implementation = Class.forName(component.getKey());

            assertFalse(
                    Modifier.isFinal(implementation.getModifiers()),
                    () -> implementation.getName() + " is final but " + DESCRIPTOR + " registers it under "
                            + hints + "; Sisu has to subclass it for every hint after the first, so all "
                            + "but " + hints.iterator().next() + " would fail to construct");
        }
    }

    /** Role-hints declared in the shipped descriptor, keyed by the implementation they point at. */
    private static Map<String, Set<String>> componentsByImplementation() {
        Map<String, Set<String>> components = new LinkedHashMap<>();
        for (Element component : elements(descriptor(), "component")) {
            String implementation = text(component, "implementation");
            String hint = text(component, "role-hint");
            assertNotNull(implementation, () -> "a component in " + DESCRIPTOR + " declares no implementation");
            components.computeIfAbsent(implementation, key -> new LinkedHashSet<>()).add(hint);
        }
        assertFalse(components.isEmpty(), () -> DESCRIPTOR + " declares no components");
        return components;
    }

    private static Document descriptor() {
        try (InputStream source =
                S3WagonComponentDescriptorTest.class.getClassLoader().getResourceAsStream(DESCRIPTOR)) {
            assertNotNull(source, () -> DESCRIPTOR + " is not on the classpath");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(source);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + DESCRIPTOR, e);
        }
    }

    private static Iterable<Element> elements(Document document, String tag) {
        NodeList found = document.getElementsByTagName(tag);
        Set<Element> elements = new LinkedHashSet<>();
        for (int i = 0; i < found.getLength(); i++) {
            elements.add((Element) found.item(i));
        }
        return elements;
    }

    private static String text(Element parent, String tag) {
        NodeList found = parent.getElementsByTagName(tag);
        return found.getLength() == 0 ? null : found.item(0).getTextContent().trim();
    }
}
