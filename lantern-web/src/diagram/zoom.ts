// The arithmetic of diagram zoom and pan. A view maps a diagram point p to the screen point
// p * scale + (x, y), relative to the viewport's top left corner.

export interface View {
  scale: number;
  x: number;
  y: number;
}

export interface Size {
  width: number;
  height: number;
}

export const MIN_SCALE = 0.1;
export const MAX_SCALE = 4;
/** The smallest scale a diagram opens at, so its labels stay readable; larger ones are panned. */
export const READABLE_SCALE = 0.75;

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/** The largest scale, at most 1, at which the content fits the viewport's width. */
export function fitScale(content: Size, viewportWidth: number): number {
  if (content.width <= 0 || viewportWidth <= 0) {
    return 1;
  }
  return clamp(Math.min(1, viewportWidth / content.width), MIN_SCALE, 1);
}

/**
 * The view showing the whole content, as large as fits but never enlarged, centered in the
 * viewport.
 */
export function fit(content: Size, viewport: Size): View {
  const scale = clamp(
    Math.min(
      1,
      content.width > 0 ? viewport.width / content.width : 1,
      content.height > 0 ? viewport.height / content.height : 1,
    ),
    MIN_SCALE,
    MAX_SCALE,
  );
  return {
    scale,
    x: (viewport.width - content.width * scale) / 2,
    y: (viewport.height - content.height * scale) / 2,
  };
}

/** The scale a diagram opens at: as large as fits the width, but never enlarged or unreadable. */
export function initialScale(content: Size, viewportWidth: number): number {
  return Math.max(fitScale(content, viewportWidth), READABLE_SCALE);
}

/**
 * The view a diagram opens with, at its initial scale: centered where it fits, otherwise starting
 * at its top left corner.
 */
export function initialView(content: Size, viewport: Size): View {
  const scale = initialScale(content, viewport.width);
  const offset = (room: number, size: number) => (size * scale <= room ? (room - size * scale) / 2 : 0);
  return { scale, x: offset(viewport.width, content.width), y: offset(viewport.height, content.height) };
}

/** Zooms by the factor, keeping the diagram point under the given screen point in place. */
export function zoomAt(view: View, factor: number, at: { x: number; y: number }): View {
  const scale = clamp(view.scale * factor, MIN_SCALE, MAX_SCALE);
  const applied = scale / view.scale;
  return { scale, x: at.x - (at.x - view.x) * applied, y: at.y - (at.y - view.y) * applied };
}

export function pan(view: View, dx: number, dy: number): View {
  return { ...view, x: view.x + dx, y: view.y + dy };
}
