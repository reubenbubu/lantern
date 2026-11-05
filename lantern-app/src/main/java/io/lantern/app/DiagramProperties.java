package io.lantern.app;

import io.lantern.compose.DiagramLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much a container or component diagram may hold, under {@code lantern.diagrams} (#836,
 * docs/design.md, Readable diagrams). Each value left unset keeps {@link DiagramLimits#DEFAULT}'s,
 * read from {@value DiagramLimits#DEFAULTS}.
 *
 * @param target the elements a view aims to stay within
 * @param tolerance how many elements past the target a view may still show
 * @param linesPerElement the most lines per element a view draws
 * @param scale the least scale a drawn view may be shrunk to so it fits the page
 * @param crossings the most line crossings of a drawn view before it is called hard to read
 * @param smallestGroup the fewest members a group needs in a view to be drawn as one box
 * @param pocketSize the most units a pocket of units that work closely together holds
 */
@ConfigurationProperties("lantern.diagrams")
public record DiagramProperties(
    Integer target,
    Integer tolerance,
    Integer linesPerElement,
    Double scale,
    Integer crossings,
    Integer smallestGroup,
    Integer pocketSize) {

  /** The limits, each unset value the default's. */
  public DiagramLimits limits() {
    DiagramLimits d = DiagramLimits.DEFAULT;
    return new DiagramLimits(
        target == null ? d.target() : target,
        tolerance == null ? d.tolerance() : tolerance,
        linesPerElement == null ? d.linesPerElement() : linesPerElement,
        scale == null ? d.scale() : scale,
        crossings == null ? d.crossings() : crossings,
        smallestGroup == null ? d.smallestGroup() : smallestGroup,
        pocketSize == null ? d.pocketSize() : pocketSize);
  }
}
