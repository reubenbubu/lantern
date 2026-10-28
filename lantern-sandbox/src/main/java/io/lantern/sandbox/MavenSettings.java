package io.lantern.sandbox;

import java.util.List;

/**
 * Writes the Maven settings of builds in the sandbox: all downloads go through the egress proxy,
 * and the configured Maven repositories are added to every build, releases only (ADR 0023).
 */
final class MavenSettings {

  private MavenSettings() {}

  /**
   * The settings file.
   *
   * @param localRepository where the shared Maven cache is mounted
   * @param proxyHost the egress proxy's name on the build's network
   * @param proxyPort the egress proxy's port
   * @param repositories the Maven repositories, in the order Maven should try them
   */
  static String xml(
      String localRepository, String proxyHost, int proxyPort, List<MavenRepository> repositories) {
    StringBuilder xml = new StringBuilder();
    xml.append(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- Maven settings for builds in the sandbox, written by Lantern. -->
        <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 \
        https://maven.apache.org/xsd/settings-1.2.0.xsd">
        """);
    xml.append("  <localRepository>")
        .append(escape(localRepository))
        .append("</localRepository>\n");
    xml.append("  <interactiveMode>false</interactiveMode>\n");
    xml.append("  <proxies>\n");
    for (String protocol : List.of("https", "http")) {
      xml.append("    <proxy>\n")
          .append("      <id>lantern-egress-")
          .append(protocol)
          .append("</id>\n")
          .append("      <active>true</active>\n")
          .append("      <protocol>")
          .append(protocol)
          .append("</protocol>\n")
          .append("      <host>")
          .append(escape(proxyHost))
          .append("</host>\n")
          .append("      <port>")
          .append(proxyPort)
          .append("</port>\n")
          .append("    </proxy>\n");
    }
    xml.append("  </proxies>\n");
    xml.append("  <profiles>\n");
    xml.append("    <profile>\n");
    xml.append("      <id>lantern-repositories</id>\n");
    for (String kind : List.of("repositories", "pluginRepositories")) {
      String element = kind.equals("repositories") ? "repository" : "pluginRepository";
      xml.append("      <").append(kind).append(">\n");
      for (MavenRepository repository : repositories) {
        xml.append("        <")
            .append(element)
            .append(">\n")
            .append("          <id>")
            .append(escape(repository.id()))
            .append("</id>\n")
            .append("          <url>")
            .append(escape(repository.url().toString()))
            .append("</url>\n")
            .append("          <releases><enabled>true</enabled></releases>\n")
            .append("          <snapshots><enabled>false</enabled></snapshots>\n")
            .append("        </")
            .append(element)
            .append(">\n");
      }
      xml.append("      </").append(kind).append(">\n");
    }
    xml.append("    </profile>\n");
    xml.append("  </profiles>\n");
    xml.append("  <activeProfiles>\n");
    xml.append("    <activeProfile>lantern-repositories</activeProfile>\n");
    xml.append("  </activeProfiles>\n");
    xml.append("</settings>\n");
    return xml.toString();
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
