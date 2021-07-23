package org.ehcache.clustered.client.internal.config.xml;

import org.ehcache.xml.BaseConfigParser;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Collections.singletonMap;

public abstract class ClusteringParser<T> extends BaseConfigParser<T> {

  protected static final String NAMESPACE = "http://www.ehcache.org/v3/clustered";
  protected static final String TC_CLUSTERED_NAMESPACE_PREFIX = "tc:";

  @Override
  public Map<URI, Supplier<Source>> getSchema() {
    return singletonMap(URI.create(NAMESPACE), () -> new StreamSource(getClass().getResourceAsStream("/ehcache-clustered-ext.xsd")));
  }

  protected static Optional<Element> childElementOf(Element fragment, Predicate<Element> predicate) {
    Collection<Element> elements = new ArrayList<>();
    NodeList children = fragment.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element && predicate.test((Element) child)) {
        elements.add((Element) child);
      }
    }
    switch (elements.size()) {
      case 0:
        return Optional.empty();
      case 1:
        return Optional.of(elements.iterator().next());
      default:
        throw new AssertionError("Validation Leak! {" + elements + "}");
    }
  }

  protected static <T extends Node> Optional<T> optionalSingleton(Class<T> nodeKlass, NodeList nodes) {
    switch (nodes.getLength()) {
      case 0:
        return Optional.empty();
      case 1:
        return Optional.of(nodeKlass.cast(nodes.item(0)));
      default:
        throw new AssertionError("Validation Leak! {" + nodes + "}");
    }
  }

}
