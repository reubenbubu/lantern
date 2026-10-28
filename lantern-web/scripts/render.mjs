// Writes a document model into the single-file template that `npm run build` produces, giving
// one self-contained HTML file. Usage: node scripts/render.mjs <document.json> <out.html> [template]
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** The empty element in the template that receives the document model JSON. */
export const PLACEHOLDER = '<script id="lantern-document" type="application/json"></script>';

export const defaultTemplate = fileURLToPath(
  new URL('../target/classes/io/lantern/web/index.html', import.meta.url),
);

/**
 * Returns the template with the document written into its placeholder. Every `<` in the JSON is
 * escaped as <, so the text can never close the script element or open a comment.
 */
export function embedDocument(template, documentJson) {
  const parts = template.split(PLACEHOLDER);
  if (parts.length !== 2) {
    throw new Error(`The template must hold the placeholder exactly once, found ${parts.length - 1}`);
  }
  const json = JSON.stringify(JSON.parse(documentJson)).replaceAll('<', '\\u003c');
  return parts[0] + PLACEHOLDER.replace('></script>', `>${json}</script>`) + parts[1];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [documentPath, outPath, templatePath = defaultTemplate] = process.argv.slice(2);
  if (!documentPath || !outPath) {
    console.error('Usage: node scripts/render.mjs <document.json> <out.html> [template]');
    process.exit(2);
  }
  const html = embedDocument(readFileSync(templatePath, 'utf8'), readFileSync(documentPath, 'utf8'));
  writeFileSync(outPath, html);
}
