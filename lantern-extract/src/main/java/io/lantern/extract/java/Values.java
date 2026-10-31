package io.lantern.extract.java;

import io.lantern.core.fact.EntityId;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Assignment;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Parameter;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Following values in code, the one place every extractor does it (#283; {@code docs/design.md},
 * Static analysis, "Following values"). The analyzers already fold constants and flatten string
 * concatenations ({@link Expression}); this follows the rest one hop: a field or parameter with
 * {@code @Value} gives the annotation's text as written, placeholders and all, and a field without
 * one gives what its type's constructors and initializer give it, when every value given to it
 * reads the same. Placeholders are not resolved here: that is the linker's.
 *
 * <p>Made once per code model ({@link JavaAnalysis#values()}) and safe to share between threads.
 */
public final class Values {

  /** Spring's {@code @Value}. */
  public static final String VALUE = "org.springframework.beans.factory.annotation.Value";

  /** One part of a value, in the order written. */
  public sealed interface Part {

    /** A constant, as written. */
    record Written(String text) implements Part {

      public Written {
        Objects.requireNonNull(text, "text");
      }
    }

    /** The text of a {@code @Value} on the field or parameter it is read from. */
    record Annotated(String text) implements Part {

      public Annotated {
        Objects.requireNonNull(text, "text");
      }
    }

    /** An expression not followed: a parameter or field without a value, a call, anything else. */
    record Open(Expression expression) implements Part {

      public Open {
        Objects.requireNonNull(expression, "expression");
      }
    }
  }

  /**
   * A value as followed.
   *
   * @param parts its parts in order
   * @param via the fields and the code whose parameters it was read from, in the order followed
   * @param openParams the code whose parameters stayed open, in this value or in a field's value it
   *     tried to follow
   */
  public record Value(List<Part> parts, Set<EntityId> via, Set<EntityId> openParams) {

    public Value {
      parts = List.copyOf(parts);
      via = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(via));
      openParams = Set.copyOf(openParams);
    }

    /** Whether every part was followed to a text. */
    public boolean resolved() {
      return parts.stream().noneMatch(p -> p instanceof Part.Open);
    }

    /** Whether some part is a {@code @Value}'s text. */
    public boolean annotated() {
      return parts.stream().anyMatch(p -> p instanceof Part.Annotated);
    }

    /** The parts' texts joined, or null if one stayed open. */
    public String text() {
      if (!resolved()) {
        return null;
      }
      StringBuilder b = new StringBuilder();
      for (Part p : parts) {
        b.append(
            switch (p) {
              case Part.Written w -> w.text();
              case Part.Annotated a -> a.text();
              case Part.Open o -> throw new IllegalStateException();
            });
      }
      return b.toString();
    }

    /** The one part, if the value has exactly one, else null. */
    public Part single() {
      return parts.size() == 1 ? parts.getFirst() : null;
    }
  }

  private final JavaAnalysis code;
  private final Map<EntityId, Member> members = new HashMap<>();
  private final Map<EntityId, List<Assignment>> assignments = new HashMap<>();

  Values(JavaAnalysis code) {
    this.code = code;
    for (JavaAnalysis.Module module : code.modules()) {
      for (TypeDecl type : module.structure().types()) {
        for (Member member : type.members()) {
          members.putIfAbsent(member.id(), member);
        }
      }
      for (Assignment a : module.structure().assignments()) {
        assignments.computeIfAbsent(a.field(), f -> new ArrayList<>()).add(a);
      }
    }
    assignments.replaceAll((field, given) -> List.copyOf(given));
  }

  /**
   * An expression followed in the code it is in.
   *
   * @param e the expression
   * @param in the method or constructor whose code it is (its {@link Expression.Param}s are that
   *     code's), or the type for code outside methods
   * @param hop whether a field without {@code @Value} is followed to what its type's code gives it
   */
  public Value follow(Expression e, EntityId in, boolean hop) {
    List<Part> parts = new ArrayList<>();
    Set<EntityId> via = new LinkedHashSet<>();
    Set<EntityId> open = new LinkedHashSet<>();
    follow(e, in, hop, parts, via, open);
    return new Value(parts, via, open);
  }

  private void follow(
      Expression e,
      EntityId in,
      boolean hop,
      List<Part> parts,
      Set<EntityId> via,
      Set<EntityId> open) {
    switch (e) {
      case Expression.Literal l -> parts.add(new Part.Written(l.value()));
      case Expression.Concat c -> {
        for (Expression part : c.parts()) {
          follow(part, in, hop, parts, via, open);
        }
      }
      case Expression.Param p -> {
        String value = annotated(parameter(in, p.index()));
        if (value != null) {
          parts.add(new Part.Annotated(value));
          via.add(in);
        } else {
          parts.add(new Part.Open(p));
          open.add(in);
        }
      }
      case Expression.FieldRef f -> {
        EntityId id = fieldId(f);
        Member field = id == null ? null : members.get(id);
        String value = field == null ? null : annotated(field.annotations());
        if (value != null) {
          parts.add(new Part.Annotated(value));
          via.add(id);
          return;
        }
        Value given = hop && id != null ? given(id, open) : null;
        if (given != null) {
          parts.addAll(given.parts());
          via.add(id);
          via.addAll(given.via());
        } else {
          parts.add(new Part.Open(f));
        }
      }
      default -> parts.add(new Part.Open(e));
    }
  }

  /**
   * What a field's type's code gives it, followed without another hop: the value when every value
   * given to it reads the same, else null.
   */
  private Value given(EntityId field, Set<EntityId> open) {
    List<Assignment> all = assignments.getOrDefault(field, List.of());
    if (all.isEmpty()) {
      return null;
    }
    List<Part> parts = null;
    Set<EntityId> via = new LinkedHashSet<>();
    boolean agree = true;
    for (Assignment a : all) {
      Value v = follow(a.value(), a.in(), false);
      open.addAll(v.openParams());
      if (!v.resolved() || (parts != null && !parts.equals(v.parts()))) {
        agree = false;
        continue;
      }
      parts = v.parts();
      via.addAll(v.via());
    }
    return agree ? new Value(parts, via, Set.of()) : null;
  }

  /** The member with the ID, the first read if several modules declare its type, or null. */
  public Member member(EntityId id) {
    return members.get(id);
  }

  /** The field an expression reads, or null if the analyzed sources do not declare it. */
  public Member field(Expression.FieldRef f) {
    EntityId id = fieldId(f);
    return id == null ? null : members.get(id);
  }

  /** The values the field's type's constructors and initializer give it, in the order read. */
  public List<Assignment> assignments(Expression.FieldRef f) {
    EntityId id = fieldId(f);
    return id == null ? List.of() : assignments.getOrDefault(id, List.of());
  }

  /** A method's or constructor's parameter, or null if it has none at the index. */
  public Parameter parameter(EntityId method, int index) {
    Member m = members.get(method);
    return m == null || index < 0 || index >= m.parameters().size()
        ? null
        : m.parameters().get(index);
  }

  /** The text of the {@code @Value} on a parameter, or null. */
  public String annotated(Parameter parameter) {
    return parameter == null ? null : annotated(parameter.annotations());
  }

  /**
   * The text of a {@code @Value} among the annotations written, directly or through a
   * meta-annotation, or null.
   */
  public String annotated(List<Annotation> annotations) {
    List<Annotation> path = code.annotationPath(annotations, VALUE);
    if (path == null || path.getLast().values("value").isEmpty()) {
      return null;
    }
    return path.getLast().values("value").getFirst();
  }

  /** A field's entity ID, or null if its owner or name cannot make one. */
  public static EntityId fieldId(Expression.FieldRef f) {
    try {
      return new EntityId(ModuleStructure.FIELD, f.owner() + "#" + f.name());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
