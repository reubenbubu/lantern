// The Architecture Explorer template: an app shell with a navigation tree on the left, the
// document in the middle and a context panel on the right with the selected entity's details and
// evidence. Three panes on wide screens, two on laptops (the context panel slides in), one on
// tablets and phones (both side panes slide in). See docs/adr/0014.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  buildLookup,
  DocumentView,
  entityHash,
  type Interaction,
  InteractionContext,
  LookupContext,
} from '../DocumentView.tsx';
import type { Document as LanternDocument } from '../generated/document.ts';
import { ContextPanel, type Selection } from './ContextPanel.tsx';
import { buildEntityTree, buildSearchIndex, entityPlacements, parseHash } from './model.ts';
import { NavPane } from './NavPane.tsx';
import { SearchBox } from './SearchBox.tsx';
import { applyTheme, storedTheme, systemTheme, type Theme } from './theme.ts';
import './explorer.css';

type Drawer = 'none' | 'nav' | 'context';

/** Reads the hash once: the selection it names, if any. */
function selectionFromHash(hash: string): Selection {
  const link = parseHash(hash);
  return link.kind === 'entity' ? { kind: 'entity', id: link.id } : { kind: 'none' };
}

function scrollToElement(id: string | undefined, behavior: ScrollBehavior = 'auto'): void {
  if (id !== undefined) {
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    document.getElementById(id)?.scrollIntoView({ behavior: reduced ? 'auto' : behavior, block: 'start' });
  }
}

export function Explorer({ document: doc }: { document: LanternDocument }) {
  const lookup = useMemo(() => buildLookup(doc), [doc]);
  const tree = useMemo(() => buildEntityTree(doc), [doc]);
  const placements = useMemo(() => entityPlacements(doc), [doc]);
  const searchIndex = useMemo(() => buildSearchIndex(doc), [doc]);

  const [selection, setSelection] = useState<Selection>(() =>
    typeof window === 'undefined' ? { kind: 'none' } : selectionFromHash(window.location.hash),
  );
  // A deep link to an entity opens with its details showing, also where they are a drawer.
  const [drawer, setDrawer] = useState<Drawer>(() => (selection.kind === 'entity' ? 'context' : 'none'));
  const [theme, setTheme] = useState<Theme | undefined>(() =>
    typeof window === 'undefined' ? undefined : storedTheme(),
  );
  const content = useRef<HTMLElement>(null);
  const contextPane = useRef<HTMLElement>(null);
  /**
   * The element a deep link asked for when the page opened. Diagrams are laid out after the first
   * render and change the page's height, so it is scrolled to again after each, until the reader
   * scrolls or clicks.
   */
  const pendingScroll = useRef<string | undefined>(undefined);

  // Each selection's details start at the top.
  useEffect(() => {
    contextPane.current?.scrollTo?.({ top: 0 });
  }, [selection]);

  useEffect(() => {
    if (theme !== undefined) {
      applyTheme(theme, false);
    }
  }, [theme]);

  // Deep link on opening the page.
  useEffect(() => {
    const link = parseHash(window.location.hash);
    const target =
      link.kind === 'element' ? link.id : link.kind === 'entity' ? lookup.sectionByEntity.get(link.id) : undefined;
    pendingScroll.current = target;
    scrollToElement(target);
    const stop = () => {
      pendingScroll.current = undefined;
    };
    const pane = content.current;
    const events = ['wheel', 'touchstart', 'keydown', 'pointerdown'] as const;
    events.forEach((e) => pane?.addEventListener(e, stop, { passive: true }));
    return () => events.forEach((e) => pane?.removeEventListener(e, stop));
  }, [lookup]);

  // Back and forward, and links to other parts of the page, move the selection with the address.
  useEffect(() => {
    const sync = () => setSelection(selectionFromHash(window.location.hash));
    window.addEventListener('hashchange', sync);
    window.addEventListener('popstate', sync);
    return () => {
      window.removeEventListener('hashchange', sync);
      window.removeEventListener('popstate', sync);
    };
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setDrawer('none');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const selectEntity = useCallback(
    (id: string, options: { scroll?: boolean } = {}) => {
      setSelection({ kind: 'entity', id });
      if (window.location.hash !== entityHash(id)) {
        window.history.pushState(null, '', entityHash(id));
      }
      if (options.scroll) {
        pendingScroll.current = undefined;
        scrollToElement(lookup.sectionByEntity.get(id), 'smooth');
      }
      setDrawer('context');
    },
    [lookup],
  );

  const diagramLaidOut = useCallback(() => scrollToElement(pendingScroll.current), []);
  const interaction: Interaction = useMemo(
    () => ({
      selectEntity: (id) => selectEntity(id),
      selectFacts: (ids) => {
        setSelection({ kind: 'facts', ids });
        setDrawer('context');
      },
      selectedEntity: selection.kind === 'entity' ? selection.id : undefined,
      diagramLaidOut,
    }),
    [selectEntity, selection, diagramLaidOut],
  );

  const goToElement = (id: string) => {
    pendingScroll.current = undefined;
    setDrawer('none');
    window.history.pushState(null, '', `#${id}`);
    setSelection({ kind: 'none' });
    scrollToElement(id, 'smooth');
  };

  const effectiveTheme = theme ?? (typeof window === 'undefined' ? 'light' : systemTheme());
  const toggleTheme = () => {
    const next: Theme = effectiveTheme === 'dark' ? 'light' : 'dark';
    applyTheme(next, true);
    setTheme(next);
  };

  return (
    <LookupContext.Provider value={lookup}>
      <InteractionContext.Provider value={interaction}>
        <div className="explorer-root">
          <div className={`explorer drawer-${drawer}`}>
            <header className="explorer-header">
              <button
                type="button"
                className="icon-button menu-button"
                aria-label="Show navigation"
                aria-expanded={drawer === 'nav'}
                onClick={() => setDrawer(drawer === 'nav' ? 'none' : 'nav')}
              >
                ☰
              </button>
              <span className="explorer-title" title={doc.title}>
                {doc.title}
              </span>
              <SearchBox
                index={searchIndex}
                onPick={(target) =>
                  target.kind === 'entity' ? selectEntity(target.id, { scroll: true }) : goToElement(target.id)
                }
              />
              <button
                type="button"
                className="icon-button"
                aria-label={`Switch to ${effectiveTheme === 'dark' ? 'light' : 'dark'} theme`}
                title={`Switch to ${effectiveTheme === 'dark' ? 'light' : 'dark'} theme`}
                onClick={toggleTheme}
              >
                {effectiveTheme === 'dark' ? '☀' : '☾'}
              </button>
              <button
                type="button"
                className="icon-button details-button"
                aria-label="Show details"
                aria-expanded={drawer === 'context'}
                onClick={() => setDrawer(drawer === 'context' ? 'none' : 'context')}
              >
                ⓘ
              </button>
            </header>
            <nav className="explorer-nav pane" aria-label="Navigation">
              <NavPane
                document={doc}
                tree={tree}
                selected={selection.kind === 'entity' ? selection.id : undefined}
                onSection={goToElement}
                onEntity={(id) => selectEntity(id, { scroll: true })}
                onClose={() => setDrawer('none')}
              />
            </nav>
            <main className="explorer-content" ref={content}>
              <DocumentView document={doc} />
            </main>
            <aside className="explorer-context pane" aria-label="Details" ref={contextPane}>
              <ContextPanel
                document={doc}
                selection={selection}
                tree={tree}
                placements={placements}
                onGoTo={goToElement}
                onClose={() => setDrawer('none')}
              />
            </aside>
            <div className="scrim" aria-hidden="true" onClick={() => setDrawer('none')} />
          </div>
        </div>
      </InteractionContext.Provider>
    </LookupContext.Provider>
  );
}
