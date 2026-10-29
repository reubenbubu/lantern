package io.lantern.sandbox;

import io.lantern.core.repo.Git;
import io.lantern.core.repo.Repo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The order in which a snapshot's repositories are built, so a repository can use the artifacts of
 * libraries other repositories of the snapshot build (ADR 0041).
 *
 * <p>A repository depends on another when one of its POMs names, as a parent, dependency, plugin or
 * extension, the {@code groupId:artifactId} of a module the other builds, whatever the version, as
 * the Maven extractor decides what is internal (ADR 0006). The POMs are read as written at each
 * repository's commit, following {@code <modules>} from the top {@code pom.xml}, with properties
 * resolved within each POM only; a coordinate that does not resolve is ignored. Repositories that
 * depend on each other, directly or through others, form a cycle: none of them gets the others'
 * artifacts, and each is built as if alone.
 */
public final class BuildOrder {

  private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
  private static final int MAX_PROPERTY_DEPTH = 10;
  private static final int MAX_POMS = 1000;
  private static final long MAX_POM_BYTES = 4L * 1024 * 1024;

  private final List<String> order;
  private final Map<String, Set<String>> upstream;
  private final List<List<String>> cycles;

  private BuildOrder(
      List<String> order, Map<String, Set<String>> upstream, List<List<String>> cycles) {
    this.order = order;
    this.upstream = upstream;
    this.cycles = cycles;
  }

  /**
   * Orders the repositories, each at its commit.
   *
   * @param repos the repositories, by name
   * @param commits the commit each repository is built at, by name
   * @throws IllegalArgumentException if a repository has no commit
   */
  public static BuildOrder of(Map<String, Repo> repos, Map<String, String> commits) {
    Map<String, Coordinates> coordinates = new TreeMap<>();
    repos.forEach(
        (name, repo) -> {
          String commit = commits.get(name);
          if (commit == null) {
            throw new IllegalArgumentException("No commit for repository " + name);
          }
          coordinates.put(name, Coordinates.read(repo, commit));
        });
    return of(coordinates);
  }

  /** Orders repositories by the coordinates their POMs build and name. */
  static BuildOrder of(Map<String, Coordinates> coordinates) {
    Map<String, Set<String>> builtBy = new HashMap<>();
    coordinates.forEach(
        (name, c) ->
            c.builds().forEach(ga -> builtBy.computeIfAbsent(ga, k -> new TreeSet<>()).add(name)));
    Map<String, Set<String>> edges = new TreeMap<>();
    coordinates.forEach(
        (name, c) -> {
          Set<String> needs = new TreeSet<>();
          for (String ga : c.names()) {
            needs.addAll(builtBy.getOrDefault(ga, Set.of()));
          }
          needs.remove(name);
          edges.put(name, needs);
        });
    List<Set<String>> components = new Tarjan(edges).components();
    Map<String, Integer> componentOf = new HashMap<>();
    for (int i = 0; i < components.size(); i++) {
      for (String name : components.get(i)) {
        componentOf.put(name, i);
      }
    }
    List<List<String>> cycles = new ArrayList<>();
    for (Set<String> component : components) {
      if (component.size() > 1) {
        cycles.add(List.copyOf(component));
      }
    }
    // Tarjan's algorithm finds a component only after every component it reaches, so the list is
    // in build order already. A component's upstream repositories are those of the components it
    // reaches, all their members, a cycle's included, since one may need another's artifacts.
    List<String> order = new ArrayList<>();
    List<Set<String>> reached = new ArrayList<>();
    Map<String, Set<String>> upstream = new TreeMap<>();
    for (int i = 0; i < components.size(); i++) {
      Set<String> all = new TreeSet<>();
      for (String name : components.get(i)) {
        for (String next : edges.get(name)) {
          int other = componentOf.get(next);
          if (other != i) {
            all.addAll(components.get(other));
            all.addAll(reached.get(other));
          }
        }
      }
      reached.add(all);
      for (String name : components.get(i)) {
        upstream.put(name, Set.copyOf(all));
        order.add(name);
      }
    }
    return new BuildOrder(List.copyOf(order), upstream, List.copyOf(cycles));
  }

  /** Every repository, each after the repositories it depends on, else by name. */
  public List<String> order() {
    return order;
  }

  /**
   * The repositories whose artifacts the repository's build gets, by name: those it depends on,
   * directly or through others, outside any cycle it is part of.
   */
  public Set<String> upstream(String repo) {
    return new TreeSet<>(Objects.requireNonNull(upstream.get(repo), repo));
  }

  /** The repositories that depend on each other, each cycle's names sorted, in build order. */
  public List<List<String>> cycles() {
    return cycles;
  }

  /**
   * The {@code groupId:artifactId} coordinates a repository's POMs build and those they name.
   *
   * @param builds the coordinates of the modules
   * @param names the coordinates of the parents, dependencies, plugins and extensions they name
   */
  record Coordinates(Set<String> builds, Set<String> names) {

    Coordinates {
      builds = Set.copyOf(builds);
      names = Set.copyOf(names);
    }

    /** Reads the POMs at the commit; a POM that cannot be read adds nothing. */
    static Coordinates read(Repo repo, String commit) {
      Set<String> builds = new HashSet<>();
      Set<String> names = new HashSet<>();
      Deque<String> pending = new ArrayDeque<>(List.of("pom.xml"));
      Set<String> seen = new HashSet<>();
      while (!pending.isEmpty() && seen.size() < MAX_POMS) {
        String path = pending.removeFirst();
        if (!seen.add(path)) {
          continue;
        }
        Git.Result show = Git.run(repo.path(), "show", commit + ":" + path);
        if (!show.ok() || show.out().length() > MAX_POM_BYTES) {
          continue;
        }
        Element project;
        try {
          project =
              JdkDetector.parse(show.out().getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        } catch (IOException | SAXException | ParserConfigurationException e) {
          continue;
        }
        if (project == null || !"project".equals(name(project))) {
          continue;
        }
        Pom pom = new Pom(project);
        String own = pom.coordinates(project);
        if (own != null) {
          builds.add(own);
        }
        pom.parent().ifPresent(names::add);
        names.addAll(pom.named());
        String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (String module : pom.modules()) {
          String next = modulePom(directory, module);
          if (next != null) {
            pending.addLast(next);
          }
        }
      }
      return new Coordinates(builds, names);
    }

    /** The path of a module's POM in the repository, or null if it leads outside it. */
    private static String modulePom(String directory, String module) {
      String target = module.strip();
      if (target.isEmpty() || target.startsWith("/") || target.contains("\\")) {
        return null;
      }
      if (!target.endsWith(".xml")) {
        target = target.endsWith("/") ? target + "pom.xml" : target + "/pom.xml";
      }
      Deque<String> parts = new ArrayDeque<>();
      for (String part : (directory + target).split("/")) {
        if (part.isEmpty() || part.equals(".")) {
          continue;
        }
        if (part.equals("..")) {
          if (parts.isEmpty()) {
            return null;
          }
          parts.removeLast();
        } else {
          parts.addLast(part);
        }
      }
      return String.join("/", parts);
    }
  }

  /** One POM as written, with its properties. */
  private static final class Pom {

    private final Element project;
    private final Map<String, String> properties = new HashMap<>();

    Pom(Element project) {
      this.project = project;
      child(project, "properties")
          .ifPresent(props -> children(props).forEach(p -> properties.put(name(p), text(p))));
      String groupId = text(project, "groupId");
      String parentGroupId = child(project, "parent").map(p -> text(p, "groupId")).orElse(null);
      String version = text(project, "version");
      String parentVersion = child(project, "parent").map(p -> text(p, "version")).orElse(null);
      put("project.groupId", groupId != null ? groupId : parentGroupId);
      put("pom.groupId", groupId != null ? groupId : parentGroupId);
      put("groupId", groupId != null ? groupId : parentGroupId);
      put("project.parent.groupId", parentGroupId);
      put("project.artifactId", text(project, "artifactId"));
      put("project.version", version != null ? version : parentVersion);
      put("project.parent.version", parentVersion);
    }

    private void put(String key, String value) {
      if (value != null) {
        properties.putIfAbsent(key, value);
      }
    }

    /** The coordinates of an element with a group ID and artifact ID, the project's own group. */
    String coordinates(Element element) {
      String groupId = text(element, "groupId");
      if (groupId == null && element == project) {
        groupId = child(project, "parent").map(p -> text(p, "groupId")).orElse(null);
      }
      return coordinates(groupId, text(element, "artifactId"));
    }

    Optional<String> parent() {
      return child(project, "parent")
          .map(p -> coordinates(text(p, "groupId"), text(p, "artifactId")));
    }

    /**
     * The coordinates of every dependency, plugin and extension anywhere in the POM. A plugin
     * without a group ID is one of Maven's own.
     */
    Set<String> named() {
      Set<String> named = new HashSet<>();
      collect(project, named);
      return named;
    }

    private void collect(Element element, Set<String> named) {
      for (Element child : children(element)) {
        String kind = name(child);
        if (kind.equals("dependency") || kind.equals("plugin") || kind.equals("extension")) {
          String groupId = text(child, "groupId");
          String ga =
              coordinates(
                  groupId == null && kind.equals("plugin") ? "org.apache.maven.plugins" : groupId,
                  text(child, "artifactId"));
          if (ga != null) {
            named.add(ga);
          }
        }
        if (!kind.equals("configuration")) {
          collect(child, named);
        }
      }
    }

    /** The modules, of the project and of its profiles. */
    List<String> modules() {
      List<String> modules = new ArrayList<>();
      child(project, "modules").ifPresent(m -> modules.addAll(moduleNames(m)));
      child(project, "profiles")
          .ifPresent(
              profiles ->
                  children(profiles)
                      .forEach(
                          profile ->
                              child(profile, "modules")
                                  .ifPresent(m -> modules.addAll(moduleNames(m)))));
      return modules;
    }

    private static List<String> moduleNames(Element modules) {
      return children(modules).stream()
          .filter(e -> name(e).equals("module"))
          .map(Element::getTextContent)
          .toList();
    }

    private String coordinates(String groupId, String artifactId) {
      String g = resolve(groupId);
      String a = resolve(artifactId);
      return g == null || a == null || g.isEmpty() || a.isEmpty() ? null : g + ":" + a;
    }

    /** Replaces {@code ${name}} with the POM's properties, or null if one is unknown. */
    private String resolve(String value) {
      if (value == null) {
        return null;
      }
      String current = value;
      for (int depth = 0; depth < MAX_PROPERTY_DEPTH; depth++) {
        Matcher m = PROPERTY.matcher(current);
        if (!m.find()) {
          return current;
        }
        String replacement = properties.get(m.group(1));
        if (replacement == null) {
          return null;
        }
        current = current.substring(0, m.start()) + replacement + current.substring(m.end());
      }
      return null;
    }
  }

  /** Tarjan's strongly connected components, each sorted, every one after those it reaches. */
  private static final class Tarjan {

    private final Map<String, Set<String>> edges;
    private final Map<String, Integer> index = new HashMap<>();
    private final Map<String, Integer> low = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final Set<String> onStack = new HashSet<>();
    private final List<Set<String>> components = new ArrayList<>();

    Tarjan(Map<String, Set<String>> edges) {
      this.edges = edges;
    }

    List<Set<String>> components() {
      for (String name : edges.keySet()) {
        if (!index.containsKey(name)) {
          visit(name);
        }
      }
      return components;
    }

    private void visit(String name) {
      index.put(name, index.size());
      low.put(name, index.get(name));
      stack.push(name);
      onStack.add(name);
      for (String next : edges.get(name)) {
        if (!index.containsKey(next)) {
          visit(next);
          low.put(name, Math.min(low.get(name), low.get(next)));
        } else if (onStack.contains(next)) {
          low.put(name, Math.min(low.get(name), index.get(next)));
        }
      }
      if (low.get(name).equals(index.get(name))) {
        Set<String> component = new TreeSet<>();
        String member;
        do {
          member = stack.pop();
          onStack.remove(member);
          component.add(member);
        } while (!member.equals(name));
        components.add(component);
      }
    }
  }

  private static Optional<Element> child(Element parent, String name) {
    return children(parent).stream().filter(e -> name.equals(name(e))).findFirst();
  }

  private static List<Element> children(Element parent) {
    List<Element> elements = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        elements.add(e);
      }
    }
    return elements;
  }

  private static String text(Element parent, String name) {
    return child(parent, name).map(BuildOrder::text).orElse(null);
  }

  private static String text(Element element) {
    return element.getTextContent().strip();
  }

  private static String name(Element e) {
    return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
  }
}
