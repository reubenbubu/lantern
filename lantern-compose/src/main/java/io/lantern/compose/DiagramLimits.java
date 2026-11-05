package io.lantern.compose;

/**
 * How much a container or component diagram may hold and still read well: the one setting the
 * composer, its quality report, the report template and the scorer all read (#836, docs/design.md,
 * Readable diagrams). The composer writes it into each report, so the template and the scorer judge
 * a report by the limits it was composed with.
 *
 * <p>The element target is soft: a view up to {@link #mostElements()}, the target plus its
 * tolerance, is fine; a larger one has what can be merged merged, or is split (#764).
 *
 * @param target the elements a view aims to stay within
 * @param tolerance how many elements past the target a view may still show
 * @param linesPerElement the most lines per element a view draws
 * @param scale the least scale a drawn view may be shrunk to so it fits the page, its labels still
 *     legible
 * @param crossings the most line crossings of a drawn view before it is called hard to read
 * @param smallestGroup the fewest members a domain or feature area needs in a view to be drawn as
 *     one box standing for them
 */
public record DiagramLimits(
    int target,
    int tolerance,
    int linesPerElement,
    double scale,
    int crossings,
    int smallestGroup) {

  /** The file of the defaults, beside this class. */
  public static final String DEFAULTS = "diagram-limits.properties";

  /**
   * The limits Lantern uses unless set otherwise: those of {@value #DEFAULTS} (setting {@code
   * lantern.diagrams}; docs/design.md, Readable diagrams; docs/research/diagram-element-limit.md).
   */
  public static final DiagramLimits DEFAULT = load();

  private static DiagramLimits load() {
    java.util.Properties defaults = new java.util.Properties();
    try (java.io.InputStream in = DiagramLimits.class.getResourceAsStream(DEFAULTS)) {
      if (in == null) {
        throw new IllegalStateException(DEFAULTS + " is missing beside " + DiagramLimits.class);
      }
      defaults.load(in);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return new DiagramLimits(
        Integer.parseInt(value(defaults, "target")),
        Integer.parseInt(value(defaults, "tolerance")),
        Integer.parseInt(value(defaults, "lines-per-element")),
        Double.parseDouble(value(defaults, "scale")),
        Integer.parseInt(value(defaults, "crossings")),
        Integer.parseInt(value(defaults, "smallest-group")));
  }

  private static String value(java.util.Properties defaults, String name) {
    String value = defaults.getProperty("lantern.diagrams." + name);
    if (value == null) {
      throw new IllegalStateException(DEFAULTS + " has no lantern.diagrams." + name);
    }
    return value.strip();
  }

  public DiagramLimits {
    if (target < 1) {
      throw new IllegalArgumentException("The element target must be at least 1: " + target);
    }
    if (tolerance < 0 || linesPerElement < 1 || crossings < 0 || smallestGroup < 2) {
      throw new IllegalArgumentException(
          "The tolerance and crossings must not be negative, the lines per element at least 1, and"
              + " the smallest group at least 2");
    }
    if (!(scale > 0 && scale <= 1)) {
      throw new IllegalArgumentException("The scale must be above 0 and at most 1: " + scale);
    }
  }

  /** The most elements a view shows before what can be merged is merged: target plus tolerance. */
  public int mostElements() {
    return target + tolerance;
  }

  /** Whether a view of these elements and lines is within the limits measured before layout. */
  public boolean fits(int elements, int lines) {
    return elements <= mostElements() && lines <= linesPerElement * elements;
  }
}
