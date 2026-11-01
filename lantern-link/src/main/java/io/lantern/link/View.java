package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.extract.attributes.ProfileGateAttributes;
import io.lantern.extract.spring.SpringExtractor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A view a link rule derives in: the default one or a Spring profile (knowledge-build epic, section
 * 8, Views). The linker runs a rule that derives per view once in each view and merges what it
 * finds into one claim per view (ADR 0095, amendment for #590). Deployment environments are views
 * of value resolution, which resolves them itself (ADR 0094, amendment for #588).
 *
 * @param name the view's name: {@code default}, or the profile's
 */
public record View(String name) {

  /** The default view: no profile active. */
  public static final View DEFAULT = new View("default");

  public View {
    Objects.requireNonNull(name, "name");
  }

  /** Whether this is the default view. */
  public boolean isDefault() {
    return equals(DEFAULT);
  }

  /** The profile the view makes active, or null for the default view. */
  public String profile() {
    return isDefault() ? null : name;
  }

  /** The view of a profile, or the default view for null. */
  static View of(String profile) {
    return profile == null ? DEFAULT : new View(profile);
  }

  /**
   * The views to link in: the default view, then each profile the facts name, sorted: in a fact's
   * {@code profile} or in the expression of a {@code profile-gate}.
   */
  static List<View> of(Collection<Fact> facts) {
    List<View> views = new ArrayList<>();
    views.add(DEFAULT);
    profiles(facts).forEach(p -> views.add(new View(p)));
    return List.copyOf(views);
  }

  /** The profiles the facts name, sorted, without {@code default}. */
  static List<String> profiles(Collection<Fact> facts) {
    Set<String> names = new TreeSet<>();
    for (Fact f : facts) {
      String profile = Claims.profile(f);
      if (profile != null) {
        names.add(profile);
      }
      if (f.predicate().equals(SpringExtractor.PROFILE_GATE)) {
        List<String> named = Claims.of(f, ProfileGateAttributes.class).profiles();
        if (named != null) {
          // As written, so "!local" or "a & b": the profiles are the names in the expression.
          for (String p : named) {
            for (String name : p.split("[!&|()\\s]+")) {
              if (!name.isEmpty()) {
                names.add(name);
              }
            }
          }
        }
      }
    }
    names.remove("default");
    return List.copyOf(names);
  }
}
