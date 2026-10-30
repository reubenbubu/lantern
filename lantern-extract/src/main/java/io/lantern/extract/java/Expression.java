package io.lantern.extract.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An expression in the code, as far as the analyzers follow it, so that extractors can follow a
 * value one hop into the call that uses it (a URL built from a {@code @Value} field, a client built
 * from a constructor argument). Both analyzers make the same expressions from the same code.
 *
 * <p>Constants are folded, string concatenations are flattened, and a local variable declared in a
 * block and never assigned again is replaced by its initializer. Anything else is {@link Unknown},
 * with its source text.
 */
public sealed interface Expression {

  /** The longest source text an {@link Unknown} keeps. */
  int MAX_SOURCE = 120;

  /** A constant: a literal or a constant variable's value, as Java prints it. */
  record Literal(String value) implements Expression {

    public Literal {
      Objects.requireNonNull(value, "value");
    }
  }

  /** A string concatenation, its parts in order; adjacent literals are joined. */
  record Concat(List<Expression> parts) implements Expression {

    public Concat {
      parts = List.copyOf(parts);
    }
  }

  /**
   * A field that is not a constant, read without an object ({@code name}), from {@code this} or
   * from a type ({@code Type.NAME}), including an enum constant.
   *
   * @param owner the binary name of the type that declares it
   * @param name the field's name
   */
  record FieldRef(String owner, String name) implements Expression {}

  /**
   * A parameter of the method or constructor whose code the expression is in.
   *
   * @param index its position, from 0
   */
  record Param(int index) implements Expression {}

  /**
   * A type named as the receiver of a static call ({@code WebClient.create(...)}), or by a class
   * literal ({@code PaymentsClient.class}).
   */
  record TypeRef(String binaryName) implements Expression {}

  /**
   * A method call or, with method {@code <init>} and a {@link TypeRef} receiver, a constructor
   * call.
   *
   * @param receiver what it is called on, or null for a call without one ({@code foo()})
   * @param method the method's name
   * @param arguments the arguments, in order
   * @param parameterTypes the erased parameter types of the method or constructor called, as in a
   *     member ID, when the compiler resolved it; empty when parsed, so a reader that needs to tell
   *     overloads apart falls back to the number of arguments
   */
  record Invoke(
      Expression receiver, String method, List<Expression> arguments, List<String> parameterTypes)
      implements Expression {

    public Invoke {
      Objects.requireNonNull(method, "method");
      arguments = List.copyOf(arguments);
      parameterTypes = List.copyOf(parameterTypes);
    }

    /** A call whose callee's parameter types are not known. */
    public Invoke(Expression receiver, String method, List<Expression> arguments) {
      this(receiver, method, arguments, List.of());
    }

    /** This call and the calls it is made on, the innermost first. */
    public List<Invoke> chain() {
      List<Invoke> chain = new ArrayList<>();
      for (Expression e = this; e instanceof Invoke i; e = i.receiver()) {
        chain.addFirst(i);
      }
      return chain;
    }

    /** What the innermost call of the chain is made on, or null if it has no receiver. */
    public Expression root() {
      return chain().getFirst().receiver();
    }
  }

  /**
   * A lambda, with its source text as {@link Unknown} keeps it, so a reader that does not look
   * inside sees what it saw before lambdas were read, and its body: the expression of an expression
   * lambda, or of the one {@code return} of a block lambda that has nothing else, else {@link
   * Unknown}. Its own parameters are {@link Unknown} in the body, as their names.
   *
   * @param source the lambda's source, whitespace collapsed and cut to {@link #MAX_SOURCE}
   * @param body what it returns
   * @param start the 1-based line it starts on, 0 if not known
   * @param end the 1-based line it ends on, 0 if not known
   */
  record Lambda(String source, Expression body, int start, int end) implements Expression {

    public Lambda {
      source = new Unknown(source).source();
      Objects.requireNonNull(body, "body");
    }
  }

  /** Anything else, with its source text, whitespace collapsed and cut to {@link #MAX_SOURCE}. */
  record Unknown(String source) implements Expression {

    public Unknown {
      source = source.strip().replaceAll("\\s+", " ");
      if (source.length() > MAX_SOURCE) {
        source = source.substring(0, MAX_SOURCE);
      }
    }
  }

  /** A concatenation of parts, flattened, with adjacent literals joined; a single part as is. */
  static Expression concat(List<Expression> parts) {
    List<Expression> flat = new ArrayList<>();
    for (Expression p : parts) {
      List<Expression> items = p instanceof Concat c ? c.parts() : List.of(p);
      for (Expression item : items) {
        if (item instanceof Literal l
            && !flat.isEmpty()
            && flat.getLast() instanceof Literal last) {
          flat.set(flat.size() - 1, new Literal(last.value() + l.value()));
        } else {
          flat.add(item);
        }
      }
    }
    return flat.size() == 1 ? flat.getFirst() : new Concat(flat);
  }
}
