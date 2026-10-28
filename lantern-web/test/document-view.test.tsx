import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { citationLabel, DocumentView } from '../src/DocumentView.tsx';
import { DiagramSvg } from '../src/diagram/DiagramView.tsx';
import { layoutDiagram } from '../src/diagram/layout.ts';
import type { Block, Section } from '../src/generated/document.ts';
import { composerOutput, diagrams, schemaFixture } from './fixtures.ts';

function allSections(sections: Section[]): Section[] {
  return sections.flatMap((s) => [s, ...allSections(s.sections)]);
}

describe('DocumentView', () => {
  const document = schemaFixture();
  const html = renderToStaticMarkup(<DocumentView document={document} />);
  const blocks: Block[] = allSections(document.sections).flatMap((s) => s.blocks);

  test('the fixture has every kind of block', () => {
    expect(new Set(blocks.map((b) => b.kind))).toEqual(
      new Set(['paragraph', 'list', 'callout', 'table', 'diagram', 'tree', 'metrics']),
    );
  });

  test('renders the title and every section and block under its ID, for deep links', () => {
    expect(html).toContain(`<h1>${document.title}</h1>`);
    for (const section of allSections(document.sections)) {
      expect(html).toContain(`id="${section.id}"`);
      expect(html).toContain(section.title);
    }
    for (const block of blocks) {
      expect(html).toContain(`id="${block.id}"`);
    }
  });

  test('renders cross-references as links and citations as fact numbers', () => {
    expect(html).toMatch(/<a href="#[^"]+">/);
    expect(html).toMatch(/<sup class="citation" title="[^"]+">\[\d+(, \d+)*\]<\/sup>/);
  });

  test('renders a reference that cites a summary as a marker that names it only in its tooltip', () => {
    expect(html).toContain(
      'packages its library.<sup class="citation" title="'
    );
    expect(html).toMatch(
      /\]<\/sup><sup class="citation"><(a href="#[^"]+"|span) class="entity-ref" title="Summary of orders-lib" aria-label="Summary of orders-lib">\[\u2197\]<\/(a|span)><\/sup><\/p>/,
    );
  });

  test('shows at most four fact numbers in a citation and counts the rest', () => {
    expect(citationLabel([3])).toBe('[3]');
    expect(citationLabel([1, 2, 5, 9])).toBe('[1, 2, 5, 9]');
    expect(citationLabel([1, 2, 5, 9, 12, 14])).toBe('[1, 2, 5 +3]');
  });

  test('renders composer output', () => {
    const output = composerOutput();
    const page = renderToStaticMarkup(<DocumentView document={output} />);
    for (const section of allSections(output.sections)) {
      expect(page).toContain(`id="${section.id}"`);
    }
  });
});

describe('DiagramSvg', () => {
  test('draws every node and edge', async () => {
    for (const diagram of diagrams(composerOutput())) {
      const svg = renderToStaticMarkup(<DiagramSvg diagram={diagram} layout={await layoutDiagram(diagram)} />);
      for (const node of diagram.nodes) {
        expect(svg).toContain(`data-entity="${node.id}"`);
      }
      for (const edge of diagram.edges) {
        expect(svg).toContain(`data-edge="${edge.id}"`);
      }
    }
  });
});

describe('DiagramSvg colors', () => {
  test('uses a #rrggbb color and ignores anything else', async () => {
    const [diagram] = diagrams(composerOutput());
    const colored = (color: string) => ({
      ...diagram,
      nodes: diagram.nodes.map((n) => ({ ...n, style: { color } })),
    });
    const render = async (color: string) =>
      renderToStaticMarkup(<DiagramSvg diagram={colored(color)} layout={await layoutDiagram(colored(color))} />);
    expect(await render('#ff8000')).toContain('fill:#ff8000');
    expect(await render('url(https://example.com/x)')).not.toContain('example.com');
  });
});
