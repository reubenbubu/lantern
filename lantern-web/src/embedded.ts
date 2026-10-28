// Reads the document model the renderer wrote into the page (see scripts/render.mjs).
import type { Document as LanternDocument } from './generated/document.ts';

/** ID of the script element that holds the document model JSON. */
export const DOCUMENT_ELEMENT_ID = 'lantern-document';

export type EmbeddedDocument =
  | { kind: 'document'; document: LanternDocument }
  | { kind: 'empty' }
  | { kind: 'error'; message: string };

export function readEmbeddedDocument(page: Document): EmbeddedDocument {
  const text = page.getElementById(DOCUMENT_ELEMENT_ID)?.textContent?.trim();
  if (!text) {
    return { kind: 'empty' };
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (e) {
    return { kind: 'error', message: `The embedded document is not valid JSON: ${String(e)}` };
  }
  const version = (value as { schemaVersion?: unknown } | null)?.schemaVersion;
  if (version !== 1) {
    return {
      kind: 'error',
      message: `Unsupported document schema version ${String(version)}; this page reads version 1.`,
    };
  }
  return { kind: 'document', document: value as LanternDocument };
}
