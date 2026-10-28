// Entry point: renders the document model embedded in the page.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { DocumentView } from './DocumentView.tsx';
import { readEmbeddedDocument } from './embedded.ts';
import { Explorer } from './explorer/Explorer.tsx';
import './styles.css';

const embedded = readEmbeddedDocument(document);
if (embedded.kind === 'document') {
  document.title = embedded.document.title;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {embedded.kind === 'document' ? (
      // One page serves every template; it picks the layout by the document's template name and
      // shows any other as a plain page (docs/adr/0014).
      embedded.document.template.name === 'architecture-explorer' ? (
        <Explorer document={embedded.document} />
      ) : (
        <DocumentView document={embedded.document} />
      )
    ) : (
      <p className="page-message">
        {embedded.kind === 'empty' ? 'This page holds no document.' : embedded.message}
      </p>
    )}
  </StrictMode>,
);
