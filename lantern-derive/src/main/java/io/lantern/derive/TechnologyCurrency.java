package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.attributes.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Technology currency (ADR 0039, #619): the runtime and framework versions a repository's code runs
 * on, each with the end of its line's free support as of the snapshot's date, and the sentences
 * that say so. The composer draws these as tables and callouts; what they say is decided here, from
 * the model, so the composer reads and does not work it out (knowledge-build epic, section 12).
 */
public final class TechnologyCurrency {

  /** How long before its end a line's support counts as ending soon. */
  public static final int SOON_MONTHS = 6;

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  private static final String SPRING_GROUP = "org.springframework:spring-";

  private TechnologyCurrency() {}

  /**
   * A runtime or framework version in use, by whom, and the facts saying so.
   *
   * @param product the runtime or framework
   * @param version the version, or {@code null} when it is not known
   * @param support the end of the line's free support, or what is known instead
   * @param usedBy the units (or modules, when no unit is in scope) that use it
   * @param facts the facts saying the version is in use
   */
  public record Version(
      EndOfLife.Product product,
      String version,
      EndOfLife.Support support,
      SortedSet<EntityId> usedBy,
      List<Fact> facts) {}

  /**
   * The versions in use: of the units' technology, else of the modules' Java release, as the table
   * judges them. Sorted by product, then version.
   *
   * @param model the snapshot's model
   * @param table the end-of-life table the versions are judged against
   * @param units the deployable units in scope
   * @param modules the modules in scope
   */
  public static List<Version> versions(
      ArchitectureModel model,
      EndOfLife table,
      SortedSet<EntityId> units,
      SortedSet<EntityId> modules) {
    Map<String, Version> found = new TreeMap<>();
    for (EntityId unit : units) {
      for (Fact fact : model.attributes(unit)) {
        if (!fact.predicate().equals(Types.TECHNOLOGY)) {
          continue;
        }
        if ("Spring Boot".equals(Elements.attribute(fact, "framework", null))) {
          version(
              table,
              found,
              EndOfLife.Product.SPRING_BOOT,
              Elements.attribute(fact, "frameworkVersion", null),
              unit,
              fact);
        }
        String java = Elements.attribute(fact, "java", null);
        if (java != null) {
          version(table, found, EndOfLife.Product.JAVA, java, unit, fact);
        }
      }
    }
    SortedSet<EntityId> code = new TreeSet<>(ArchitectureModel.BY_ID);
    code.addAll(modules);
    units.forEach(u -> code.addAll(Elements.codeModules(model, u)));
    for (EntityId module : code) {
      for (Fact fact : model.relations(module, Types.BUILD_DEPENDS_ON)) {
        EntityId target = ArchitectureModel.object(fact);
        String version = Elements.attribute(fact, "version", null);
        if (target.type().equals(Types.ARTIFACT)
            && target.key().startsWith(SPRING_GROUP)
            && version != null
            && !isTestScope(fact)) {
          version(
              table,
              found,
              EndOfLife.Product.SPRING_FRAMEWORK,
              version,
              model.builderOf(module),
              fact);
        }
      }
    }
    if (units.isEmpty()) {
      for (EntityId module : modules) {
        for (Fact fact : model.attributes(module)) {
          // A pom module (a parent or aggregator) builds no code.
          if (fact.predicate().equals(Types.JAVA_RELEASE)
              && !model.attribute(module, Types.PACKAGING).orElse("jar").equals("pom")) {
            version(
                table,
                found,
                EndOfLife.Product.JAVA,
                fact.object().value(),
                model.builderOf(module),
                fact);
          }
        }
      }
    }
    List<Version> result = new ArrayList<>(found.values());
    result.sort(
        Comparator.comparing((Version v) -> v.product())
            .thenComparing(
                v -> EndOfLife.line(v.product(), v.version()).orElse(null),
                Comparator.nullsLast(EndOfLife.BY_VERSION))
            .thenComparing(v -> v.version() == null ? "" : v.version()));
    return List.copyOf(result);
  }

  /** Whether a version's support has ended by the date. */
  public static boolean ended(Version v, LocalDate asOf) {
    return switch (v.support()) {
      case EndOfLife.Support.Until u -> u.end().isBefore(asOf);
      case EndOfLife.Support.Older o -> true;
      case EndOfLife.Support.EndedBy b -> b.by().isBefore(asOf);
      case EndOfLife.Support.Unknown u -> false;
    };
  }

  /** Whether the table tells whether a version's support has ended by the date. */
  public static boolean known(Version v, LocalDate asOf) {
    return v.version() != null
        && switch (v.support()) {
          case EndOfLife.Support.Unknown u -> false;
          case EndOfLife.Support.EndedBy b -> ended(v, asOf);
          default -> true;
        };
  }

  /** Whether a version's support ends within {@link #SOON_MONTHS} months of the date. */
  public static boolean endsSoon(Version v, LocalDate asOf) {
    return v.support() instanceof EndOfLife.Support.Until u
        && !u.end().isBefore(asOf)
        && !u.end().isAfter(asOf.plusMonths(SOON_MONTHS));
  }

  /** A date as the documents write it, such as {@code 3 Nov 2026}. */
  public static String date(LocalDate date) {
    return DATE.format(date);
  }

  /** The support column of a version: its end, or what is known instead of it. */
  public static String supportText(Version v, LocalDate asOf) {
    if (v.version() == null) {
      return "Version not known";
    }
    return switch (v.support()) {
      case EndOfLife.Support.Until u ->
          u.end().isBefore(asOf)
              ? "Ended " + DATE.format(u.end())
              : endsSoon(v, asOf)
                  ? "Ends " + DATE.format(u.end()) + ", within six months"
                  : DATE.format(u.end());
      case EndOfLife.Support.Older o -> "Ended";
      case EndOfLife.Support.EndedBy b ->
          ended(v, asOf) ? "Ended by " + DATE.format(b.by()) : "Not in Lantern's table";
      case EndOfLife.Support.Unknown u ->
          EndOfLife.line(v.product(), v.version()).isEmpty()
              ? "Version could not be read"
              : "Not in Lantern's table";
    };
  }

  /** When a version's support ended or ends, in the middle of a sentence. */
  public static String riskSupportText(Version v, LocalDate asOf) {
    return switch (v.support()) {
      case EndOfLife.Support.Until u ->
          (u.end().isBefore(asOf) ? "ended " : "ends ") + DATE.format(u.end());
      case EndOfLife.Support.EndedBy b -> "ended by " + DATE.format(b.by());
      default -> "ended";
    };
  }

  /**
   * The sentence above the versions table: how many of the versions in use are past the end of
   * their free support, and how many reach it within six months.
   *
   * @param all every version in use, as {@link #versions} returns them
   * @param asOf the snapshot's date
   */
  public static String summary(List<Version> all, LocalDate asOf) {
    List<Version> judged = all.stream().filter(v -> known(v, asOf)).toList();
    long ended = judged.stream().filter(v -> ended(v, asOf)).count();
    long soon = judged.stream().filter(v -> endsSoon(v, asOf)).count();
    int unknown = all.size() - judged.size();
    StringBuilder text =
        new StringBuilder("As of the snapshot's date, " + DATE.format(asOf) + ", ");
    if (judged.isEmpty()) {
      text.append("Lantern does not know whether ")
          .append(all.size() == 1 ? "the one version" : "any of the " + all.size() + " versions")
          .append(" in use is past the end of free support.");
      return text.toString();
    }
    String other = unknown > 0 ? " other" : "";
    if (judged.size() == 1) {
      text.append("the one").append(other).append(" version in use is ");
      text.append(ended == 0 ? "not " : "");
    } else if (ended == 0) {
      text.append("none of the ").append(judged.size()).append(other);
      text.append(" versions in use is ");
    } else {
      text.append(ended).append(" of the ").append(judged.size()).append(other);
      text.append(" versions in use ").append(ended == 1 ? "is " : "are ");
    }
    text.append("past the end of free support");
    if (soon > 0) {
      text.append(
          judged.size() == 1
              ? ", but reaches it"
              : ", and " + soon + (soon == 1 ? " reaches it" : " reach it"));
      text.append(" within six months");
    }
    text.append('.');
    if (unknown > 0) {
      text.append(" Lantern does not know the support of ")
          .append(unknown == 1 ? "1 version" : unknown + " versions")
          .append('.');
    }
    return text.toString();
  }

  /** The facts the stored end-of-life findings rest on (ADR 0069, #619). */
  public static Set<Fact> raisedEndOfLife(ArchitectureModel model) {
    Set<Fact> facts = new HashSet<>();
    for (Fact finding : Findings.of(model, Findings.END_OF_LIFE)) {
      facts.addAll(Findings.cited(model, finding));
    }
    return facts;
  }

  /** Whether a dependency is for tests only, not in the code the unit runs. */
  static boolean isTestScope(Fact fact) {
    return Elements.attribute(fact, "scope", "compile").equals("test");
  }

  private static void version(
      EndOfLife table,
      Map<String, Version> found,
      EndOfLife.Product product,
      String version,
      EntityId user,
      Fact fact) {
    // A Java release reads as its line: 1.8 and 8 are one row, shown as 8.
    if (product == EndOfLife.Product.JAVA) {
      version = EndOfLife.line(product, version).orElse(version);
    }
    String shown = version;
    String key = product.ordinal() + " " + (version == null ? "" : version);
    Version v =
        found.computeIfAbsent(
            key,
            k ->
                new Version(
                    product,
                    shown,
                    table.support(product, shown),
                    new TreeSet<>(ArchitectureModel.BY_ID),
                    new ArrayList<>()));
    v.usedBy().add(user);
    v.facts().add(fact);
  }
}
