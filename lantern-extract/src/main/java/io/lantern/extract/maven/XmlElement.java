package io.lantern.extract.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * An XML element with the lines it spans, enough to read a POM and point at where something is
 * declared. POMs come from untrusted repositories, so they are parsed with DTDs and external
 * entities disabled.
 *
 * @param name the element's local name
 * @param text the element's text, stripped; empty if it has none
 * @param children the child elements in document order
 * @param startLine the 1-based line of the start tag's end, as the parser reports it
 * @param endLine the 1-based line of the end tag
 */
record XmlElement(String name, String text, List<XmlElement> children, int startLine, int endLine) {

  XmlElement {
    children = List.copyOf(children);
  }

  /** The first child with the given name. */
  Optional<XmlElement> child(String name) {
    return children.stream().filter(c -> c.name.equals(name)).findFirst();
  }

  /** The children with the given name. */
  List<XmlElement> children(String name) {
    return children.stream().filter(c -> c.name.equals(name)).toList();
  }

  /** The children of the first child with the given name: {@code <modules><module>...}. */
  List<XmlElement> list(String container, String item) {
    return child(container).map(c -> c.children(item)).orElse(List.of());
  }

  /** The stripped text of the first child with the given name, or null if there is none. */
  String text(String name) {
    return child(name).map(XmlElement::text).orElse(null);
  }

  /**
   * Parses an XML document.
   *
   * @throws IOException if it is not well-formed XML or declares a DTD
   */
  static XmlElement parse(byte[] xml) throws IOException {
    Handler handler = new Handler();
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setXIncludeAware(false);
      SAXParser parser = factory.newSAXParser();
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      parser.parse(new InputSource(new ByteArrayInputStream(xml)), handler);
    } catch (SAXException | ParserConfigurationException e) {
      throw new IOException("Not a readable XML document: " + e.getMessage(), e);
    }
    if (handler.root == null) {
      throw new IOException("Not a readable XML document: no root element");
    }
    return handler.root;
  }

  private static final class Handler extends DefaultHandler {

    private record Open(
        String name, int startLine, StringBuilder text, List<XmlElement> children) {}

    private final Deque<Open> open = new ArrayDeque<>();
    private Locator locator;
    private XmlElement root;

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      String name = localName == null || localName.isEmpty() ? qName : localName;
      open.push(new Open(name, line(), new StringBuilder(), new ArrayList<>()));
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (!open.isEmpty()) {
        open.peek().text().append(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      Open element = open.pop();
      XmlElement done =
          new XmlElement(
              element.name(),
              element.text().toString().strip(),
              element.children(),
              element.startLine(),
              line());
      if (open.isEmpty()) {
        root = done;
      } else {
        open.peek().children().add(done);
      }
    }

    private int line() {
      return locator == null ? 1 : Math.max(1, locator.getLineNumber());
    }
  }
}
