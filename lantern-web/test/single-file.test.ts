// Builds the single-file template, writes a document into it and checks the result stands alone.
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
// @ts-expect-error The render script is plain JavaScript without type declarations.
import { embedDocument, PLACEHOLDER } from '../scripts/render.mjs';
import { composerOutputJson } from './fixtures.ts';

const root = fileURLToPath(new URL('..', import.meta.url));
let outDir: string;
let template: string;

beforeAll(async () => {
  outDir = mkdtempSync(join(tmpdir(), 'lantern-web-'));
  // Vitest sets NODE_ENV to test, which would make Vite bundle React's development build.
  const nodeEnv = process.env.NODE_ENV;
  process.env.NODE_ENV = 'production';
  try {
    await build({ root, mode: 'production', logLevel: 'silent', build: { outDir, emptyOutDir: true } });
  } finally {
    process.env.NODE_ENV = nodeEnv;
  }
  template = readFileSync(join(outDir, 'index.html'), 'utf8');
}, 60_000);

afterAll(() => rmSync(outDir, { recursive: true, force: true }));

describe('the single-file build', () => {
  test('is one HTML file', () => {
    expect(readdirSync(outDir)).toEqual(['index.html']);
  });

  test('inlines its scripts and styles and loads nothing from elsewhere', () => {
    // The page's own markup, without the inlined code and styles.
    const markup = template.replace(/(<(script|style)\b[^>]*>)[\s\S]*?(<\/\2>)/gi, '$1$3');
    expect(markup).not.toMatch(/\s(src|href)=/i);
    expect(markup).toMatch(/<script type="module"[^>]*><\/script>/);
    expect(markup).toMatch(/<style[^>]*><\/style>/);
    expect(template).toMatch(/<style[^>]*>[^<]*--color-bg/);
    expect(template).not.toMatch(/\burl\(\s*['"]?(https?:)?\/\//i);
    expect(template).not.toContain('react.dev/link/invalid-hook-call');
  });

  test('holds the document placeholder exactly once', () => {
    expect(template.split(PLACEHOLDER)).toHaveLength(2);
  });

  test('carries a document written into it', () => {
    const html: string = embedDocument(template, composerOutputJson);
    const match = /<script id="lantern-document" type="application\/json">([\s\S]*?)<\/script>/.exec(html);
    expect(JSON.parse(match![1])).toEqual(JSON.parse(composerOutputJson));
  });
});

describe('embedDocument', () => {
  test('escapes text that would end the script element', () => {
    const html: string = embedDocument(PLACEHOLDER, JSON.stringify({ title: '</script><!--' }));
    expect(html).toBe(`${PLACEHOLDER.replace('></script>', '>')}{"title":"\\u003c/script>\\u003c!--"}</script>`);
    expect(JSON.parse(html.slice(PLACEHOLDER.indexOf('></script>') + 1, -'</script>'.length))).toEqual({
      title: '</script><!--',
    });
  });

  test('refuses a template without exactly one placeholder', () => {
    expect(() => embedDocument('<html></html>', '{}')).toThrow(/exactly once/);
    expect(() => embedDocument(PLACEHOLDER + PLACEHOLDER, '{}')).toThrow(/exactly once/);
  });
});
