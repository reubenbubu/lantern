// Client-side search over sections, titled blocks and entities. "/" focuses it from anywhere;
// arrow keys move through the results, Enter opens one and Escape closes them.
import { type KeyboardEvent, useEffect, useId, useMemo, useRef, useState } from 'react';
import { search, type SearchItem, type SearchTarget } from './model.ts';

export function SearchBox({ index, onPick }: { index: SearchItem[]; onPick(target: SearchTarget): void }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const listId = useId();
  const results = useMemo(() => search(index, query), [index, query]);

  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const typing = target?.closest('input, textarea, select, [contenteditable="true"]');
      if (event.key === '/' && !typing && !event.ctrlKey && !event.metaKey && !event.altKey) {
        event.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const pick = (item: SearchItem) => {
    setOpen(false);
    input.current?.blur();
    onPick(item.target);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(a + 1, results.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (event.key === 'Enter' && results[active]) {
      event.preventDefault();
      pick(results[active]);
    } else if (event.key === 'Escape') {
      event.stopPropagation();
      setOpen(false);
    }
  };

  const showResults = open && query.trim() !== '';
  return (
    <div className="search" role="search">
      <input
        ref={input}
        type="search"
        placeholder="Search  /"
        aria-label="Search the document"
        role="combobox"
        aria-expanded={showResults}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={showResults && results[active] ? `${listId}-${active}` : undefined}
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setActive(0);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={onKeyDown}
      />
      {showResults && (
        <ul className="search-results" id={listId} role="listbox">
          {results.length === 0 && <li className="search-empty">Nothing matches.</li>}
          {results.map((item, i) => (
            <li
              key={`${item.target.kind}:${item.target.id}`}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              // Before the input's blur closes the list.
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => pick(item)}
              onMouseEnter={() => setActive(i)}
            >
              <span className="search-label">{item.label}</span>
              <span className="search-detail">{item.detail}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
