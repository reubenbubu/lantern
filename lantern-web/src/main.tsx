// Entry point: renders the document model embedded in the page.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { DocumentView } from './DocumentView.tsx';
import { readEmbeddedDocument } from './embedded.ts';
import './styles.css';

const embedded = readEmbeddedDocument(document);
if (embedded.kind === 'document') {
  document.title = embedded.document.title;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {embedded.kind === 'document' ? (
      <DocumentView document={embedded.document} />
    ) : (
      <p className="page-message">
        {embedded.kind === 'empty' ? 'This page holds no document.' : embedded.message}
      </p>
    )}
  </StrictMode>,
);
