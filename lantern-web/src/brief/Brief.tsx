// The Executive Brief template: one scroll for leadership, led by the narrative, with headline
// numbers, risks and a few large diagrams. It shares the plain page's components and theme tokens
// and adds a slim header with the sections and the theme switch. See docs/adr/0015.
import { useEffect, useMemo, useRef } from 'react';
import { buildLookup, LookupContext, Pinned, SectionView } from '../DocumentView.tsx';
import { Breakable } from '../explorer/Breakable.tsx';
import type { Document as LanternDocument } from '../generated/document.ts';
import { ThemeButton, useTheme } from '../theme.tsx';
import './brief.css';

/** The element a page address's fragment names, if any. */
export function hashTarget(hash: string): string | undefined {
  if (!hash.startsWith('#') || hash.length < 2) {
    return undefined;
  }
  try {
    return decodeURIComponent(hash.slice(1));
  } catch {
    return undefined;
  }
}

export function Brief({ document: doc }: { document: LanternDocument }) {
  const lookup = useMemo(() => buildLookup(doc), [doc]);
  const { theme, toggle } = useTheme();
  const page = useRef<HTMLDivElement>(null);

  // A deep link: diagrams are laid out after the first render and change the page's height, so
  // the target is scrolled to again whenever the page resizes, until the reader scrolls or clicks.
  useEffect(() => {
    const target = hashTarget(window.location.hash);
    const root = page.current;
    if (target === undefined || root === null || typeof ResizeObserver === 'undefined') {
      return;
    }
    const scroll = () => window.document.getElementById(target)?.scrollIntoView({ block: 'start' });
    scroll();
    const observer = new ResizeObserver(scroll);
    observer.observe(root);
    const stop = () => observer.disconnect();
    const events = ['wheel', 'touchstart', 'keydown', 'pointerdown'] as const;
    events.forEach((e) => window.addEventListener(e, stop, { passive: true, once: true }));
    return () => {
      stop();
      events.forEach((e) => window.removeEventListener(e, stop));
    };
  }, []);

  // A diagram wider than its frame starts scrolled to its middle, where the subject usually is.
  useEffect(() => {
    const root = page.current;
    if (root === null || typeof ResizeObserver === 'undefined') {
      return;
    }
    const centred = new WeakSet<Element>();
    const centre = () => {
      root.querySelectorAll<HTMLElement>('.diagram').forEach((figure) => {
        const canvas = figure.querySelector('svg');
        if (canvas !== null && !centred.has(canvas) && figure.scrollWidth > figure.clientWidth) {
          centred.add(canvas);
          figure.scrollLeft = (figure.scrollWidth - figure.clientWidth) / 2;
        }
      });
    };
    const observer = new ResizeObserver(centre);
    observer.observe(root);
    return () => observer.disconnect();
  }, []);

  return (
    <LookupContext.Provider value={lookup}>
      <div className="brief-page" ref={page}>
        <header className="brief-bar">
          <a className="brief-bar-title" href="#brief-top" title={doc.title}>
            {doc.title}
          </a>
          <nav className="brief-nav" aria-label="Sections">
            {doc.sections.map((section) => (
              <a key={section.id} href={`#${section.id}`}>
                {section.title}
              </a>
            ))}
          </nav>
          <ThemeButton theme={theme} onToggle={toggle} />
        </header>
        <main className="brief">
          <header className="brief-hero" id="brief-top">
            <p className="brief-kicker">Executive brief</p>
            <h1>
              <Breakable text={doc.title} />
            </h1>
            <Pinned document={doc} className="brief-source" />
            <p className="brief-source">
              Snapshot {doc.source.snapshot} · Lantern {doc.source.toolVersion}
            </p>
          </header>
          {doc.sections.map((section) => (
            <SectionView key={section.id} section={section} depth={0} />
          ))}
        </main>
      </div>
    </LookupContext.Provider>
  );
}
