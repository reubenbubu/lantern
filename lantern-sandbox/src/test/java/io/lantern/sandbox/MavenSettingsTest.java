package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class MavenSettingsTest {

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
  }

  private static List<String> values(Document document, String path) throws Exception {
    NodeList nodes =
        (NodeList)
            XPathFactory.newInstance().newXPath().evaluate(path, document, XPathConstants.NODESET);
    List<String> values = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      values.add(nodes.item(i).getTextContent());
    }
    return values;
  }

  @Test
  void defaultSettingsNameOnlyCentralAndSendEverythingThroughTheProxy() throws Exception {
    Document settings =
        parse(
            MavenSettings.xml(
                "/var/lantern/m2", "lantern-proxy", 3128, List.of(MavenRepository.CENTRAL), null));

    assertThat(values(settings, "/settings/localRepository")).containsExactly("/var/lantern/m2");
    assertThat(values(settings, "/settings/proxies/proxy/protocol"))
        .containsExactly("https", "http");
    assertThat(values(settings, "/settings/proxies/proxy/host"))
        .containsExactly("lantern-proxy", "lantern-proxy");
    assertThat(values(settings, "/settings/proxies/proxy/port")).containsExactly("3128", "3128");
    assertThat(values(settings, "//repositories/repository/id")).containsExactly("central");
    assertThat(values(settings, "//repositories/repository/url"))
        .containsExactly("https://repo.maven.apache.org/maven2");
    assertThat(values(settings, "//pluginRepositories")).isEmpty();
    assertThat(values(settings, "/settings/activeProfiles/activeProfile"))
        .containsExactly("lantern-repositories");
    assertThat(values(settings, "/settings/profiles/profile/id"))
        .containsExactly("lantern-repositories");
    assertThat(values(settings, "//snapshots/enabled")).containsOnly("false");
    assertThat(values(settings, "//servers")).isEmpty();
    assertThat(values(settings, "//mirrors")).isEmpty();
  }

  @Test
  void addsConfiguredRepositoriesInOrder() throws Exception {
    MavenRepository releases =
        new MavenRepository(
            "releases", URI.create("https://nexus.example.com/repository/releases"));

    Document settings =
        parse(
            MavenSettings.xml(
                "/var/lantern/m2",
                "lantern-proxy",
                3128,
                List.of(MavenRepository.CENTRAL, releases),
                null));

    assertThat(values(settings, "//repositories/repository/id"))
        .containsExactly("central", "releases");
    assertThat(values(settings, "//pluginRepositories")).isEmpty();
    assertThat(values(settings, "//repositories/repository/url"))
        .containsExactly(
            "https://repo.maven.apache.org/maven2",
            "https://nexus.example.com/repository/releases");
  }

  @Test
  void aMirrorStandsInForEveryRepositoryAndPluginRepository() throws Exception {
    MavenMirror mirror =
        new MavenMirror("lantern-mirror", URI.create("http://10.1.2.3:8081/repository/all"));

    Document settings =
        parse(
            MavenSettings.xml(
                "/var/lantern/m2",
                "lantern-proxy",
                3128,
                List.of(MavenRepository.CENTRAL),
                mirror));

    assertThat(values(settings, "/settings/mirrors/mirror/id")).containsExactly("lantern-mirror");
    assertThat(values(settings, "/settings/mirrors/mirror/mirrorOf")).containsExactly("external:*");
    assertThat(values(settings, "/settings/mirrors/mirror/url"))
        .containsExactly("http://10.1.2.3:8081/repository/all");
    assertThat(values(settings, "//mirror/blocked")).isEmpty();
    assertThat(values(settings, "/settings/proxies/proxy/protocol"))
        .containsExactly("https", "http");
    assertThat(values(settings, "//repositories/repository/id")).containsExactly("central");
  }
}
