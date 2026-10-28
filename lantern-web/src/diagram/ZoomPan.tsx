// A viewport that zooms and pans its content: buttons, Ctrl or Cmd with the mouse wheel (and a
// trackpad pinch, which browsers report the same way), dragging, a two-finger pinch on touch
// screens, and the keyboard (+, -, 0 and the arrow keys) once focused. Vertical swipes on touch
// screens still scroll the page.
import {
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { fit, initialScale, initialView, pan, type Size, type View, zoomAt } from './zoom.ts';

const MIN_HEIGHT = 160;
/** Share of the window's height a diagram may take before it has to be panned. */
const MAX_HEIGHT_SHARE = 0.7;
const STEP = 1.25;
/** How far a pointer must move before a press becomes a drag and not a click. */
const DRAG_THRESHOLD = 4;

interface Pointer {
  x: number;
  y: number;
}

export function ZoomPan({ content, label, children }: { content: Size; label: string; children: ReactNode }) {
  const viewport = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [view, setView] = useState<View | undefined>(undefined);
  /** Whether the reader has zoomed or panned; until then the view follows the viewport's size. */
  const moved = useRef(false);
  const pointers = useRef(new Map<number, Pointer>());
  const press = useRef<{ start: Pointer; dragging: boolean } | undefined>(undefined);
  const suppressClick = useRef(false);

  const [windowHeight, setWindowHeight] = useState(() => (typeof window === 'undefined' ? 860 : window.innerHeight));
  useEffect(() => {
    const onResize = () => setWindowHeight(window.innerHeight);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  const maxHeight = Math.max(MIN_HEIGHT, windowHeight * MAX_HEIGHT_SHARE);
  const height = Math.round(
    Math.min(maxHeight, Math.max(MIN_HEIGHT, content.height * initialScale(content, width || content.width))),
  );

  useLayoutEffect(() => {
    const element = viewport.current;
    if (!element) {
      return;
    }
    setWidth(element.clientWidth);
    const observer = new ResizeObserver(() => setWidth(element.clientWidth));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const showAll = useCallback(() => {
    moved.current = true;
    setView(fit(content, { width, height }));
  }, [content, width, height]);

  useEffect(() => {
    if (width > 0 && !moved.current) {
      setView(initialView(content, { width, height }));
    }
  }, [content, width, height]);

  const change = (next: (current: View) => View) => {
    moved.current = true;
    setView((current) => (current ? next(current) : current));
  };
  const center = () => ({ x: width / 2, y: height / 2 });

  // React registers wheel listeners as passive, so preventing the page zoom needs our own.
  useEffect(() => {
    const element = viewport.current;
    if (!element) {
      return;
    }
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) {
        return;
      }
      event.preventDefault();
      const box = element.getBoundingClientRect();
      const at = { x: event.clientX - box.left, y: event.clientY - box.top };
      moved.current = true;
      setView((current) => (current ? zoomAt(current, Math.exp(-event.deltaY / 300), at) : current));
    };
    element.addEventListener('wheel', onWheel, { passive: false });
    return () => element.removeEventListener('wheel', onWheel);
  }, []);

  const local = (event: PointerEvent): Pointer => {
    const box = viewport.current!.getBoundingClientRect();
    return { x: event.clientX - box.left, y: event.clientY - box.top };
  };

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === 'mouse' && event.button !== 0) {
      return;
    }
    const point = local(event);
    pointers.current.set(event.pointerId, point);
    if (pointers.current.size === 1) {
      press.current = { start: point, dragging: false };
      suppressClick.current = false;
    }
  };

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const previous = pointers.current.get(event.pointerId);
    if (!previous) {
      return;
    }
    const point = local(event);
    if (pointers.current.size >= 2) {
      const others = [...pointers.current.entries()].filter(([id]) => id !== event.pointerId);
      const other = others[0][1];
      const before = Math.hypot(previous.x - other.x, previous.y - other.y);
      const after = Math.hypot(point.x - other.x, point.y - other.y);
      const mid = { x: (point.x + other.x) / 2, y: (point.y + other.y) / 2 };
      pointers.current.set(event.pointerId, point);
      suppressClick.current = true;
      if (before > 0) {
        change((v) => pan(zoomAt(v, after / before, mid), (point.x - previous.x) / 2, (point.y - previous.y) / 2));
      }
      return;
    }
    const current = press.current;
    if (current && !current.dragging) {
      if (Math.hypot(point.x - current.start.x, point.y - current.start.y) < DRAG_THRESHOLD) {
        return;
      }
      // Captured only once it is a drag, so a plain click still reaches the node under it.
      current.dragging = true;
      suppressClick.current = true;
      viewport.current!.setPointerCapture(event.pointerId);
    }
    pointers.current.set(event.pointerId, point);
    change((v) => pan(v, point.x - previous.x, point.y - previous.y));
  };

  const onPointerEnd = (event: PointerEvent<HTMLDivElement>) => {
    pointers.current.delete(event.pointerId);
    if (pointers.current.size === 0) {
      press.current = undefined;
    }
  };

  const zoomIn = () => change((v) => zoomAt(v, STEP, center()));
  const zoomOut = () => change((v) => zoomAt(v, 1 / STEP, center()));

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const actions: Record<string, () => void> = {
      '+': zoomIn,
      '=': zoomIn,
      '-': zoomOut,
      '0': showAll,
      ArrowLeft: () => change((v) => pan(v, 40, 0)),
      ArrowRight: () => change((v) => pan(v, -40, 0)),
      ArrowUp: () => change((v) => pan(v, 0, 40)),
      ArrowDown: () => change((v) => pan(v, 0, -40)),
    };
    const action = actions[event.key];
    if (action && event.target === event.currentTarget) {
      event.preventDefault();
      action();
    }
  };

  return (
    <div className="zoom">
      <div className="zoom-toolbar">
        <span className="zoom-hint zoom-hint-fine">Drag to move, Ctrl + scroll to zoom</span>
        <span className="zoom-hint zoom-hint-coarse">Swipe sideways to move, pinch to zoom</span>
        <button type="button" title="Zoom in (+)" aria-label="Zoom in" onClick={zoomIn}>
          +
        </button>
        <button type="button" title="Zoom out (-)" aria-label="Zoom out" onClick={zoomOut}>
          −
        </button>
        <button type="button" title="Show the whole diagram (0)" aria-label="Show the whole diagram" onClick={showAll}>
          Fit
        </button>
      </div>
      <div
        ref={viewport}
        className="zoom-viewport"
        style={{ height }}
        tabIndex={0}
        role="group"
        aria-label={`${label}: drag to move, Ctrl or Cmd and scroll to zoom, or use the + , - and 0 keys`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerEnd}
        onPointerCancel={onPointerEnd}
        onClickCapture={(event) => {
          if (suppressClick.current) {
            event.stopPropagation();
            event.preventDefault();
            suppressClick.current = false;
          }
        }}
        onKeyDown={onKeyDown}
      >
        <div
          className="zoom-content"
          style={
            view
              ? { transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }
              : { visibility: 'hidden' }
          }
        >
          {children}
        </div>
      </div>
    </div>
  );
}
