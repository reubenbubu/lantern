// Light and dark themes. Until the reader picks one the page follows the system setting; a pick is
// remembered in the browser's local storage where that is allowed (it may not be for a file).
import { useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';

const KEY = 'lantern.theme';

export function storedTheme(): Theme | undefined {
  try {
    const value = window.localStorage.getItem(KEY);
    return value === 'light' || value === 'dark' ? value : undefined;
  } catch {
    return undefined;
  }
}

export function systemTheme(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Applies the theme to the page and remembers it. */
export function applyTheme(theme: Theme, remember: boolean): void {
  document.documentElement.dataset.theme = theme;
  if (remember) {
    try {
      window.localStorage.setItem(KEY, theme);
    } catch {
      // Storage is not available; the choice lasts until the page is closed.
    }
  }
}

/** The theme in effect and a function that switches to the other one, as every template offers. */
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [picked, setPicked] = useState<Theme | undefined>(() =>
    typeof window === 'undefined' ? undefined : storedTheme(),
  );
  const [system, setSystem] = useState<Theme>(() => (typeof window === 'undefined' ? 'light' : systemTheme()));
  useEffect(() => {
    const query = window.matchMedia?.('(prefers-color-scheme: dark)');
    const onChange = () => setSystem(systemTheme());
    query?.addEventListener('change', onChange);
    return () => query?.removeEventListener('change', onChange);
  }, []);
  useEffect(() => {
    if (picked !== undefined) {
      applyTheme(picked, false);
    }
  }, [picked]);
  const theme = picked ?? system;
  const toggle = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    applyTheme(next, true);
    setPicked(next);
  };
  return { theme, toggle };
}

/** The button that switches between the light and dark theme. */
export function ThemeButton({ theme, onToggle }: { theme: Theme; onToggle: () => void }) {
  const label = `Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`;
  return (
    <button type="button" className="icon-button" aria-label={label} title={label} onClick={onToggle}>
      {theme === 'dark' ? '☀' : '☾'}
    </button>
  );
}
