// Light and dark themes. Until the reader picks one the page follows the system setting; a pick is
// remembered in the browser's local storage where that is allowed (it may not be for a file).
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
