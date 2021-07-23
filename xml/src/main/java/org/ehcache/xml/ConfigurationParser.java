/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.xml;

import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.Configuration;
import org.ehcache.config.FluentConfigurationBuilder;
import org.ehcache.config.ResourcePools;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.core.util.ClassLoading;
import org.ehcache.xml.exceptions.XmlConfigurationException;
import org.ehcache.xml.model.BaseCacheType;
import org.ehcache.xml.model.CacheDefinition;
import org.ehcache.xml.model.CacheEntryType;
import org.ehcache.xml.model.CacheTemplate;
import org.ehcache.xml.model.CacheTemplateType;
import org.ehcache.xml.model.CacheType;
import org.ehcache.xml.model.ConfigType;
import org.ehcache.xml.model.ObjectFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.helpers.DefaultValidationEventHandler;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static java.util.Spliterators.spliterator;
import static java.util.function.Function.identity;
import static java.util.regex.Pattern.quote;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.ConfigurationBuilder.newConfigurationBuilder;
import static org.ehcache.config.builders.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.core.util.ClassLoading.servicesOfType;
import static org.ehcache.xml.XmlConfiguration.CORE_SCHEMA_URL;
import static org.ehcache.xml.XmlConfiguration.getClassForName;

/**
 * Provides support for parsing a cache configuration expressed in XML.
 */
public class ConfigurationParser {

  public static Schema newSchema(Source... schemas) throws SAXException {
    SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    try {
      /*
       * Our schema is accidentally not XSD 1.1 compatible. Since Saxon incorrectly (imho) defaults to XSD 1.1 for
       * `XMLConstants.W3C_XML_SCHEMA_NS_URI` we force it back to 1.0.
       */
      schemaFactory.setProperty("http://saxon.sf.net/feature/xsd-version", "1.0");
    } catch (SAXNotRecognizedException e) {
      //not saxon
    }
    schemaFactory.setErrorHandler(new FatalErrorHandler());
    return schemaFactory.newSchema(schemas);
  }
  private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();

  private static final QName CORE_SCHEMA_ROOT_NAME;
  static {
    ObjectFactory objectFactory = new ObjectFactory();
    CORE_SCHEMA_ROOT_NAME = objectFactory.createConfig(objectFactory.createConfigType()).getName();
  }

  static final CoreCacheConfigurationParser CORE_CACHE_CONFIGURATION_PARSER = new CoreCacheConfigurationParser();

  private final Schema schema;
  private final JAXBContext jaxbContext = JAXBContext.newInstance(ConfigType.class);
  private final DocumentBuilder documentBuilder;

  private final ServiceCreationConfigurationParser serviceCreationConfigurationParser;
  private final ServiceConfigurationParser serviceConfigurationParser;
  private final ResourceConfigurationParser resourceConfigurationParser;

  @SuppressWarnings("unchecked")
  private static <T> Stream<T> stream(Iterable<? super T> iterable) {
    return StreamSupport.stream(spliterator((Iterator<T>) iterable.iterator(), Long.MAX_VALUE, 0), false);
  }

  ConfigurationParser() throws IOException, SAXException, JAXBException, ParserConfigurationException {
    serviceCreationConfigurationParser = ConfigurationParser.<CacheManagerServiceConfigurationParser<?, ?>>stream(
      namespaceUniqueParsersOfType(CacheManagerServiceConfigurationParser.class))
      .collect(collectingAndThen(toMap(CacheManagerServiceConfigurationParser::getServiceType, identity(),
        (a, b) -> a.getClass().isInstance(b) ? b : a), ServiceCreationConfigurationParser::new));

    serviceConfigurationParser = ConfigurationParser.<CacheServiceConfigurationParser<?, ?>>stream(
      namespaceUniqueParsersOfType(CacheServiceConfigurationParser.class))
      .collect(collectingAndThen(toMap(CacheServiceConfigurationParser::getServiceType, identity(),
        (a, b) -> a.getClass().isInstance(b) ? b : a), ServiceConfigurationParser::new));

    resourceConfigurationParser = stream(servicesOfType(CacheResourceConfigurationParser.class))
      .flatMap(p -> p.getResourceTypes().stream().map(t -> new AbstractMap.SimpleImmutableEntry<>(t, p)))
      .collect(collectingAndThen(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a.getClass().isInstance(b) ? b : a),
        m -> new ResourceConfigurationParser(new HashSet<>(m.values()))));

    schema = discoverSchema(new StreamSource(CORE_SCHEMA_URL.openStream()));
    documentBuilder = documentBuilder(schema);
  }

  <K, V> CacheConfigurationBuilder<K, V> parseServiceConfigurations(Document document, CacheConfigurationBuilder<K, V> cacheBuilder,
                                                                    ClassLoader cacheClassLoader, CacheTemplate cacheDefinition)
    throws ClassNotFoundException, IllegalAccessException, InstantiationException {
    cacheBuilder = CORE_CACHE_CONFIGURATION_PARSER.parse(cacheDefinition, cacheClassLoader, cacheBuilder);
    return serviceConfigurationParser.parse(document, cacheDefinition, cacheClassLoader, cacheBuilder);
  }

  private static Iterable<CacheDefinition> getCacheElements(ConfigType configType) {
    List<CacheDefinition> cacheCfgs = new ArrayList<>();
    final List<BaseCacheType> cacheOrCacheTemplate = configType.getCacheOrCacheTemplate();
    for (BaseCacheType baseCacheType : cacheOrCacheTemplate) {
      if(baseCacheType instanceof CacheType) {
        final CacheType cacheType = (CacheType)baseCacheType;

        final BaseCacheType[] sources;
        if(cacheType.getUsesTemplate() != null) {
          sources = new BaseCacheType[2];
          sources[0] = cacheType;
          sources[1] = (BaseCacheType) cacheType.getUsesTemplate();
        } else {
          sources = new BaseCacheType[1];
          sources[0] = cacheType;
        }

        cacheCfgs.add(new CacheDefinition(cacheType.getAlias(), sources));
      }
    }

    return Collections.unmodifiableList(cacheCfgs);
  }

  private Map<String, XmlConfiguration.Template> getTemplates(Document document, ConfigType configType) {
    final Map<String, XmlConfiguration.Template> templates = new HashMap<>();
    final List<BaseCacheType> cacheOrCacheTemplate = configType.getCacheOrCacheTemplate();
    for (BaseCacheType baseCacheType : cacheOrCacheTemplate) {
      if (baseCacheType instanceof CacheTemplateType) {
        final CacheTemplate cacheTemplate = new CacheTemplate.Impl(((CacheTemplateType) baseCacheType));
        templates.put(cacheTemplate.id(), parseTemplate(document, cacheTemplate));
      }
    }
    return Collections.unmodifiableMap(templates);
  }

  private XmlConfiguration.Template parseTemplate(Document document, CacheTemplate template) {
    return new XmlConfiguration.Template() {
      @Override
      public <K, V> CacheConfigurationBuilder<K, V> builderFor(ClassLoader classLoader, Class<K> keyType, Class<V> valueType, ResourcePools resources) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        checkTemplateTypeConsistency("key", classLoader, keyType, template);
        checkTemplateTypeConsistency("value", classLoader, valueType, template);

        if ((resources == null || resources.getResourceTypeSet().isEmpty()) && template.getHeap() == null && template.getResources().isEmpty()) {
          throw new IllegalStateException("Template defines no resources, and none were provided");
        }

        if (resources == null) {
          resources = resourceConfigurationParser.parse(template, newResourcePoolsBuilder(), classLoader);
        }

        return parseServiceConfigurations(document, newCacheConfigurationBuilder(keyType, valueType, resources), classLoader, template);
      }
    };
  }

  private static <T> void checkTemplateTypeConsistency(String type, ClassLoader classLoader, Class<T> providedType, CacheTemplate template) throws ClassNotFoundException {
    Class<?> templateType;
    if (type.equals("key")) {
      templateType = getClassForName(template.keyType(), classLoader);
    } else {
      templateType = getClassForName(template.valueType(), classLoader);
    }

    if(providedType == null || !templateType.isAssignableFrom(providedType)) {
      throw new IllegalArgumentException("CacheTemplate '" + template.id() + "' declares " + type + " type of " + templateType.getName() + ". Provided: " + providedType);
    }
  }

  public Document uriToDocument(URI uri) throws IOException, SAXException {
    return documentBuilder.parse(uri.toString());
  }

  public XmlConfigurationWrapper documentToConfig(Document document, ClassLoader classLoader, Map<String, ClassLoader> cacheClassLoaders) throws JAXBException, ClassNotFoundException, InstantiationException, IllegalAccessException {
    Document annotatedDocument = stampExternalConfigurations(copyAndValidate(document));
    Element root = annotatedDocument.getDocumentElement();

    QName rootName = new QName(root.getNamespaceURI(), root.getLocalName());
    if (!CORE_SCHEMA_ROOT_NAME.equals(rootName)) {
      throw new XmlConfigurationException("Expecting " + CORE_SCHEMA_ROOT_NAME + " element; found " + rootName);
    }

    Class<ConfigType> configTypeClass = ConfigType.class;
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    unmarshaller.setEventHandler(new DefaultValidationEventHandler());
    ConfigType jaxbModel = unmarshaller.unmarshal(annotatedDocument, configTypeClass).getValue();

    FluentConfigurationBuilder<?> managerBuilder = newConfigurationBuilder().withClassLoader(classLoader);
    managerBuilder = serviceCreationConfigurationParser.parse(annotatedDocument, jaxbModel, classLoader, managerBuilder);

    for (CacheDefinition cacheDefinition : getCacheElements(jaxbModel)) {
      String alias = cacheDefinition.id();
      if(managerBuilder.getCache(alias) != null) {
        throw new XmlConfigurationException("Two caches defined with the same alias: " + alias);
      }

      ClassLoader cacheClassLoader = cacheClassLoaders.get(alias);
      boolean classLoaderConfigured = cacheClassLoader != null;

      if (cacheClassLoader == null) {
        if (classLoader != null) {
          cacheClassLoader = classLoader;
        } else {
          cacheClassLoader = ClassLoading.getDefaultClassLoader();
        }
      }

      Class<?> keyType = getClassForName(cacheDefinition.keyType(), cacheClassLoader);
      Class<?> valueType = getClassForName(cacheDefinition.valueType(), cacheClassLoader);

      ResourcePools resourcePools = resourceConfigurationParser.parse(cacheDefinition, newResourcePoolsBuilder(), classLoader);

      CacheConfigurationBuilder<?, ?> cacheBuilder = newCacheConfigurationBuilder(keyType, valueType, resourcePools);
      if (classLoaderConfigured) {
        cacheBuilder = cacheBuilder.withClassLoader(cacheClassLoader);
      }

      cacheBuilder = parseServiceConfigurations(annotatedDocument, cacheBuilder, cacheClassLoader, cacheDefinition);
      managerBuilder = managerBuilder.withCache(alias, cacheBuilder.build());
    }

    Map<String, XmlConfiguration.Template> templates = getTemplates(annotatedDocument, jaxbModel);

    return new XmlConfigurationWrapper(managerBuilder.build(), templates);
  }

  private Document copyAndValidate(Document document) {
    try {
      Validator validator = schema.newValidator();
      Document newDocument = documentBuilder.newDocument();
      newDocument.setStrictErrorChecking(false);
      validator.validate(new DOMSource(document), new DOMResult(newDocument));
      return newDocument;
    } catch (SAXException | IOException e) {
      throw new AssertionError(e);
    }
  }

  public Document configToDocument(Configuration configuration) throws JAXBException {
    ConfigType configType = new ConfigType();
    Document document = documentBuilder.newDocument();

    configType = serviceCreationConfigurationParser.unparse(document, configuration, configType);

    for (Map.Entry<String, CacheConfiguration<?, ?>> cacheConfigurationEntry : configuration.getCacheConfigurations().entrySet()) {
      CacheConfiguration<?, ?> cacheConfiguration = cacheConfigurationEntry.getValue();

      CacheType cacheType = new CacheType().withAlias(cacheConfigurationEntry.getKey())
        .withKeyType(new CacheEntryType().withValue(cacheConfiguration.getKeyType().getName()))
        .withValueType(new CacheEntryType().withValue(cacheConfiguration.getValueType().getName()));

      cacheType = resourceConfigurationParser.unparse(document, cacheConfiguration.getResourcePools(), cacheType);
      cacheType = CORE_CACHE_CONFIGURATION_PARSER.unparse(cacheConfiguration, cacheType);
      cacheType = serviceConfigurationParser.unparse(document, cacheConfiguration, cacheType);
      configType = configType.withCacheOrCacheTemplate(cacheType);
    }

    JAXBElement<ConfigType> root = new ObjectFactory().createConfig(configType);

    Marshaller marshaller = jaxbContext.createMarshaller();
    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
    marshaller.setSchema(schema);

    marshaller.marshal(root, document);
    return document;
  }

  public static class FatalErrorHandler implements ErrorHandler {

    private static final Collection<Pattern> ABSTRACT_TYPE_FAILURES;
    static {
      ObjectFactory objectFactory = new ObjectFactory();
      List<QName> abstractTypes = asList(
        objectFactory.createServiceCreationConfiguration(null).getName(),
        objectFactory.createServiceConfiguration(null).getName(),
        objectFactory.createResource(null).getName());

      ABSTRACT_TYPE_FAILURES = asList(
        //Xerces
        abstractTypes.stream().map(element -> quote(format("\"%s\":%s", element.getNamespaceURI(), element.getLocalPart())))
          .collect(collectingAndThen(joining("|", "^\\Qcvc-complex-type.2.4.a\\E.*'\\{.*(?:", ").*\\}'.*$"), Pattern::compile)),
        //Saxon
        abstractTypes.stream().map(element -> quote(element.getLocalPart()))
          .collect(collectingAndThen(joining("|", "^.*\\QThe content model does not allow element\\E.*(?:", ").*"), Pattern::compile)));
    }

    @Override
    public void warning(SAXParseException exception) throws SAXException {
      fatalError(exception);
    }

    @Override
    public void error(SAXParseException exception) throws SAXException {
      fatalError(exception);
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
      if (ABSTRACT_TYPE_FAILURES.stream().anyMatch(pattern -> pattern.matcher(exception.getMessage()).matches())) {
        throw new XmlConfigurationException(
          "Cannot confirm XML sub-type correctness. You might be missing client side libraries.", exception);
      } else {
        throw exception;
      }
    }
  }

  public static class XmlConfigurationWrapper {
    private final Configuration configuration;
    private final Map<String, XmlConfiguration.Template> templates;

    public XmlConfigurationWrapper(Configuration configuration, Map<String, XmlConfiguration.Template> templates) {
      this.configuration = configuration;
      this.templates = templates;
    }

    public Configuration getConfiguration() {
      return configuration;
    }

    public Map<String, XmlConfiguration.Template> getTemplates() {
      return templates;
    }
  }

  public static String documentToText(Document xml) throws IOException, TransformerException {
    try (StringWriter writer = new StringWriter()) {
      transformer().transform(new DOMSource(xml), new StreamResult(writer));
      return writer.toString();
    }
  }

  private static Transformer transformer() throws TransformerConfigurationException {
    Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
    transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    return transformer;
  }

  public static String urlToText(URL url, String encoding) throws IOException {
    Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), charset))) {
      return reader.lines().collect(joining(System.lineSeparator()));
    }
  }

  public static DocumentBuilder documentBuilder(Schema schema) throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setIgnoringComments(true);
    factory.setIgnoringElementContentWhitespace(true);
    factory.setSchema(schema);
    DocumentBuilder documentBuilder = factory.newDocumentBuilder();
    documentBuilder.setErrorHandler(new FatalErrorHandler());
    return documentBuilder;
  }

  public static Schema discoverSchema(Source ... fixedSources) throws SAXException {
    Collection<List<URI>> neededNamespaces = new ArrayList<>();
    Map<URI, Supplier<Source>> sources = new HashMap<>();

    for (CacheManagerServiceConfigurationParser<?, ?> p : namespaceUniqueParsersOfType(CacheManagerServiceConfigurationParser.class)) {
      List<URI> ordering = new ArrayList<>();
      for (Map.Entry<URI, Supplier<Source>> element : p.getSchema().entrySet()) {
        sources.putIfAbsent(element.getKey(), element.getValue());
        ordering.add(element.getKey());
      }
      neededNamespaces.add(ordering);
    }

    for (CacheServiceConfigurationParser<?, ?> p : namespaceUniqueParsersOfType(CacheServiceConfigurationParser.class)) {
      List<URI> ordering = new ArrayList<>();
      for (Map.Entry<URI, Supplier<Source>> element : p.getSchema().entrySet()) {
        sources.putIfAbsent(element.getKey(), element.getValue());
        ordering.add(element.getKey());
      }
      neededNamespaces.add(ordering);
    }

    /*
     * Resource parsers are allowed to share namespaces.
     */
    for (CacheResourceConfigurationParser p : servicesOfType(CacheResourceConfigurationParser.class)) {
      List<URI> ordering = new ArrayList<>();
      for (Map.Entry<URI, Supplier<Source>> element : p.getSchema().entrySet()) {
        sources.putIfAbsent(element.getKey(), element.getValue());
        ordering.add(element.getKey());
      }
      neededNamespaces.add(ordering);
    }

    List<URI> fullOrdering = mergePartialOrderings(neededNamespaces);

    List<Source> schemaSources = new ArrayList<>(asList(fixedSources));
    schemaSources.addAll(fullOrdering.stream().map(sources::get).map(Supplier::get).collect(Collectors.toList()));
    return newSchema(schemaSources.toArray(new Source[0]));
  }

  public static <T> List<T> mergePartialOrderings(Collection<List<T>> orderings) {
    Collection<List<T>> workingCopy = orderings.stream().map(ArrayList::new).collect(toCollection(ArrayList::new));
    List<T> fullOrdering = new ArrayList<>();

    while (!workingCopy.isEmpty()) {
      boolean progress = false;
      for (Iterator<List<T>> partials = workingCopy.iterator(); partials.hasNext(); ) {
        List<T> partial = partials.next();

        if (partial.isEmpty()) {
          progress = true;
          partials.remove();
        } else {
          Supplier<Stream<List<T>>> otherOrderings = () -> workingCopy.stream().filter(o -> o != partial);

          for (Iterator<T> it = partial.iterator(); it.hasNext(); it.remove()) {
            T next = it.next();

            if (otherOrderings.get().anyMatch(o -> o.indexOf(next) > 0)) {
              break;
            } else {
              progress = true;
              fullOrdering.add(next);
              otherOrderings.get().forEach(o -> o.remove(next));
            }
          }
        }
      }
      if (!progress) {
        throw new IllegalStateException("Incompatible partial orderings: " + orderings);
      }
    }

    return fullOrdering;
  }

  public static <T extends Parser<?>> Iterable<T> namespaceUniqueParsersOfType(Class<T> parserType) {
    List<T> parsers = new ArrayList<>();
    for (T parser : servicesOfType(parserType)) {
      if (allowedInParserSet(parsers, parser)) {
        parsers.add(parser);
      }
    }
    return unmodifiableList(parsers);
  }

  private static <T extends Parser<?>> boolean allowedInParserSet(List<T> parsers, T parser) {
    Set<URI> parserTargetNamespaces = parser.getTargetNamespaces();
    for (Iterator<T> it = parsers.iterator(); it.hasNext(); ) {
      T other = it.next();
      Set<URI> otherTargetNamespaces = other.getTargetNamespaces();
      if (parserTargetNamespaces.equals(otherTargetNamespaces)) {
        throw new IllegalArgumentException("Parsers competing for identical namespace set: " + parser + " :: " + other);
      } else if (parserTargetNamespaces.containsAll(otherTargetNamespaces)) {
        it.remove();
      } else if (otherTargetNamespaces.containsAll(parserTargetNamespaces)) {
        return false;
      } else {
        Set<URI> intersection = new HashSet<>(parserTargetNamespaces);
        intersection.retainAll(otherTargetNamespaces);
        if (!intersection.isEmpty()) {
          throw new IllegalArgumentException("Parsers competing for namespace set: " + intersection + " (neither dominates the other): " + parser + " :: " + other);
        }
      }
    }
    return true;
  }

  private static final String EXTERNAL_IDENTIFIER_ATTRIBUTE_NAME = "external-identifier";

  private static Document stampExternalConfigurations(Document document) {
    NodeList elements = document.getElementsByTagName("*");
    for (int i = 0, identifier = 0; i < elements.getLength(); i++) {
      Element element = (Element) elements.item(i);
      if (!element.getNamespaceURI().equals(CORE_SCHEMA_ROOT_NAME.getNamespaceURI())) {
        element.setAttributeNS(CORE_SCHEMA_ROOT_NAME.getNamespaceURI(), EXTERNAL_IDENTIFIER_ATTRIBUTE_NAME, valueOf(identifier++));
      }
    }
    return document;
  }

  private static Element cleanExternalConfigurations(Element fragment) {
    NodeList elements = fragment.getElementsByTagName("*");
    for (int i = 0; i < elements.getLength(); i++) {
      Element element = (Element) elements.item(i);
      element.removeAttributeNS(CORE_SCHEMA_ROOT_NAME.getNamespaceURI(), EXTERNAL_IDENTIFIER_ATTRIBUTE_NAME);
    }
    return fragment;
  }

  public static Element findMatchingNodeInDocument(Document document, Element lookup) {
    String identifier = lookup.getAttributeNS(CORE_SCHEMA_ROOT_NAME.getNamespaceURI(), EXTERNAL_IDENTIFIER_ATTRIBUTE_NAME);
    if (identifier.isEmpty()) {
      throw new IllegalArgumentException("Cannot lookup unstamped element: " + lookup);
    } else {
      NodeList elements = document.getElementsByTagName("*");

      for (int i = 0; i < elements.getLength(); i++) {
        Element element = (Element) elements.item(i);
        if (identifier.equals(element.getAttributeNS(CORE_SCHEMA_ROOT_NAME.getNamespaceURI(), EXTERNAL_IDENTIFIER_ATTRIBUTE_NAME))) {
          if (lookup.getLocalName().equals(element.getLocalName()) && lookup.getNamespaceURI().equals(element.getNamespaceURI())) {
            return cleanExternalConfigurations(element);
          } else {
            throw new IllegalStateException("Lookup of: " + lookup + " found mismatched element: " + element);
          }
        }
      }
      throw new IllegalArgumentException("No element found for: " + lookup);
    }
  }
}
