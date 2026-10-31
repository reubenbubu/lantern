package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.extract.attributes.ConfigValueAttributes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.Resolution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Values written in code, such as {@code http://${payments.host}/api}, resolved against the config
 * of the code's module as the module's {@code config-value} facts give it, in the default view and
 * each profile: what the extractors resolved for themselves until they kept values as written
 * (#519, #556). The facts give each key's value as written per view, so placeholders nest as Spring
 * nests them.
 *
 * <p>A value a secret was substituted into is kept as written and masked: a key the config
 * extractor names a secret, or the identity extractor finds holding a JWT secret, or a key whose
 * own value the config extractor masked whole (which also covers values masked for holding a
 * credential or a cipher, so it masks a little more than the extractors did).
 *
 * <p>A profile's facts hold a key where its value, as resolved or as written, differs from the
 * default view's, and every item of a list the profile sets, so the profile's view is the default's
 * overlaid with the profile's facts, a list the profile sets replacing the default's whole. A
 * module that has config files but no values has no facts, so its code takes its repository's only
 * other config.
 *
 * <p>A config value the config extractor recorded with what looked like a URL's credentials
 * removed, rather than masked whole, is used as recorded, so a text using it lacks that part, where
 * the extractors' own resolution had it (an e-mail address such as {@code noreply@example.com}
 * reads as user info).
 *
 * <p>Deployment environments as views and {@code RESOLVES_TO} claims are #522's.
 */
public final class CodeValues {

  /**
   * A value from code, resolved or kept as written.
   *
   * @param value the value with its placeholders resolved, or as written when masked
   * @param written the value as written in the code
   * @param unresolved the keys of the placeholders no config resolved; none when masked
   * @param masked whether a secret was substituted into it
   */
  public record Value(String value, String written, SortedSet<String> unresolved, boolean masked) {

    public Value {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(written, "written");
      unresolved = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(unresolved));
    }
  }

  /**
   * One key's value in one view, as written, whether the config extractor masked it, and its fact.
   */
  private record Entry(String written, boolean masked, Fact fact) {}

  /** A module's own config: each view's entries by key, the default view under null. */
  private record Config(Map<String, Map<String, Entry>> views, SortedSet<String> profiles) {}

  private final Map<EntityId, Config> modules = new HashMap<>();
  private final Map<String, List<EntityId>> modulesOfRepo = new HashMap<>();
  private final Set<String> known;

  private CodeValues(List<Fact> facts) {
    Set<String> secretKeys = ConfigExtractor.jwtSecretKeys(facts);
    this.known = ConfigExtractor.normalizedKeys(secretKeys);
    Map<EntityId, Map<String, Map<String, Entry>>> views = new HashMap<>();
    for (Fact f : facts) {
      if (!f.predicate().equals(ConfigExtractor.CONFIG_VALUE)) {
        continue;
      }
      ConfigValueAttributes config = Claims.of(f, ConfigValueAttributes.class);
      if (config.source() != null || config.key() == null) {
        // A config repository's files and code's defaults are not the module's own config.
        continue;
      }
      String profile = config.profile();
      String key = config.key();
      // A value masked whole is a secret; one that only lost credentials in a URL is not.
      boolean masked = f.object().value().equals(ConfigExtractor.MASK);
      String written = config.written();
      Entry entry = new Entry(written != null ? written : f.object().value(), masked, f);
      views
          .computeIfAbsent(f.subject(), m -> new HashMap<>())
          .computeIfAbsent(profile, p -> new HashMap<>())
          .putIfAbsent(key, entry);
      List<EntityId> ofRepo =
          modulesOfRepo.computeIfAbsent(f.evidence().repo(), r -> new ArrayList<>());
      if (!ofRepo.contains(f.subject())) {
        ofRepo.add(f.subject());
      }
    }
    views.forEach(
        (module, byView) -> {
          SortedSet<String> profiles = new TreeSet<>();
          byView.keySet().stream().filter(Objects::nonNull).forEach(profiles::add);
          modules.put(module, new Config(byView, profiles));
        });
  }

  /**
   * The values of a snapshot's code.
   *
   * @param facts the snapshot's facts; only {@code config-value} facts and the identity extractor's
   *     JWT key facts are read
   */
  public static CodeValues of(List<Fact> facts) {
    return new CodeValues(Objects.requireNonNull(facts, "facts"));
  }

  /** The config that applies to code of one module. */
  public final class View {

    private final Config config;

    private View(Config config) {
      this.config = config;
    }

    /**
     * The views a value may differ in: the default view (null) and each profile whose config
     * differs from it, in order.
     */
    public List<String> views() {
      List<String> all = new ArrayList<>();
      all.add(null);
      if (config != null) {
        all.addAll(config.profiles());
      }
      return all;
    }

    /**
     * The module's {@code config-value} facts that hold with a profile active (null for the default
     * view), by key: the default view's overlaid with the profile's, a list the profile sets
     * replacing the default's whole.
     */
    public java.util.SortedMap<String, Fact> facts(String profile) {
      java.util.SortedMap<String, Fact> facts = new java.util.TreeMap<>();
      if (config == null) {
        return facts;
      }
      Map<String, Entry> inProfile =
          profile == null ? Map.of() : config.views().getOrDefault(profile, Map.of());
      config
          .views()
          .getOrDefault(null, Map.of())
          .forEach(
              (key, e) -> {
                if (!inProfile.containsKey(key) && !replaced(inProfile, key)) {
                  facts.put(key, e.fact());
                }
              });
      inProfile.forEach((key, e) -> facts.put(key, e.fact()));
      return facts;
    }

    /**
     * A value written in code, resolved in a view.
     *
     * @param profile the profile, or null for the default view
     * @param written the value as written
     */
    public Value value(String profile, String written) {
      Objects.requireNonNull(written, "written");
      if (config == null) {
        Resolution r = ConfigExtractor.resolveWithoutConfig(written);
        return of(r, written, Set.of());
      }
      Map<String, Entry> byDefault = config.views().getOrDefault(null, Map.of());
      Map<String, Entry> inProfile =
          profile == null ? Map.of() : config.views().getOrDefault(profile, Map.of());
      Set<String> maskedUsed = new LinkedHashSet<>();
      Resolution r =
          ConfigExtractor.resolve(
              written,
              key -> {
                Entry e =
                    inProfile.containsKey(key)
                        ? inProfile.get(key)
                        : replaced(inProfile, key) ? null : byDefault.get(key);
                if (e == null) {
                  return null;
                }
                if (e.masked()) {
                  maskedUsed.add(key);
                }
                return e.written();
              });
      return of(r, written, maskedUsed);
    }

    /**
     * Whether a profile sets the list a key is an item of, which Spring then replaces whole: the
     * default's items are not merged with the profile's index by index.
     */
    private static boolean replaced(Map<String, Entry> inProfile, String key) {
      String list = listOf(key);
      return list != null && inProfile.keySet().stream().anyMatch(k -> list.equals(listOf(k)));
    }

    private Value of(Resolution r, String written, Set<String> maskedUsed) {
      if (!maskedUsed.isEmpty()
          || r.used().stream().anyMatch(k -> ConfigExtractor.isSecret(k, known))) {
        return new Value(written, written, new TreeSet<>(), true);
      }
      return new Value(r.value(), written, r.unresolved(), false);
    }
  }

  /** The modules that have config of their own, in the order of their IDs. */
  public List<EntityId> modules() {
    return modules.keySet().stream().sorted(LinkIndex.BY_TEXT).toList();
  }

  /** The repository of a module's own config, or null if it has none. */
  public String repoOf(EntityId module) {
    for (Map.Entry<String, List<EntityId>> e : modulesOfRepo.entrySet()) {
      if (e.getValue().contains(module)) {
        return e.getKey();
      }
    }
    return null;
  }

  /**
   * The config that applies to code of a module: the module's own, else that of the only module of
   * its repository that has config, else none, when only placeholders' defaults apply.
   *
   * @param module the code's module
   * @param repo the name of the module's repository
   */
  public View forModule(EntityId module, String repo) {
    Config own = modules.get(module);
    if (own != null) {
      return new View(own);
    }
    List<EntityId> ofRepo = modulesOfRepo.getOrDefault(repo, List.of());
    return new View(ofRepo.size() == 1 ? modules.get(ofRepo.getFirst()) : null);
  }

  /**
   * The list a key is an item of ({@code a.b} for {@code a.b[0].c}), or null; a key in brackets
   * that is not a number is a map's, which Spring merges.
   */
  private static String listOf(String key) {
    java.util.regex.Matcher index = LIST_INDEX.matcher(key);
    return index.find() && index.start() > 0 ? key.substring(0, index.start()) : null;
  }

  private static final java.util.regex.Pattern LIST_INDEX =
      java.util.regex.Pattern.compile("\\[\\d+]");
}
