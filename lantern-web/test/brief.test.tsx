import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { Brief, hashTarget } from '../src/brief/Brief.tsx';
import { DocumentView } from '../src/DocumentView.tsx';
import type { Callout, Section } from '../src/generated/document.ts';
import { briefOutput, schemaFixture } from './fixtures.ts';

function allSections(sections: Section[]): Section[] {
  return sections.flatMap((s) => [s, ...allSections(s.sections)]);
}

describe('the Executive Brief', () => {
  const document = briefOutput();
  const html = renderToStaticMarkup(<Brief document={document} />);

  test('is composer output for the brief', () => {
    expect(document.template.name).toBe('executive-brief');
    expect(document.sections.map((s) => s.id)).toEqual(['overview', 'headlines', 'risks', 'context', 'containers']);
  });

  test('opens with the title and links every section from its header', () => {
    expect(html).toContain('Executive brief');
    expect(html).toContain(`Snapshot ${document.source.snapshot}`);
    for (const section of document.sections) {
      expect(html).toContain(`<a href="#${section.id}">${section.title}</a>`);
      expect(html).toContain(`id="${section.id}"`);
    }
  });

  test('shows every headline number and block', () => {
    for (const block of allSections(document.sections).flatMap((s) => s.blocks)) {
      expect(html).toContain(`id="${block.id}"`);
      if (block.kind === 'metrics') {
        for (const metric of block.items) {
          expect(html).toContain(`<dt>${metric.label}</dt><dd>${metric.value}</dd>`);
        }
      }
    }
  });

  test('renders every block kind of the schema fixture', () => {
    const fixture = { ...schemaFixture(), template: { name: 'executive-brief', version: '1' } };
    const page = renderToStaticMarkup(<Brief document={fixture} />);
    for (const block of allSections(fixture.sections).flatMap((s) => s.blocks)) {
      expect(page).toContain(`id="${block.id}"`);
    }
  });

  test('reads deep links', () => {
    expect(hashTarget('#risks')).toBe('risks');
    expect(hashTarget('#a%20b')).toBe('a b');
    expect(hashTarget('#')).toBeUndefined();
    expect(hashTarget('')).toBeUndefined();
    expect(hashTarget('#%E0%A4%A')).toBeUndefined();
  });
});

describe('risk severity', () => {
  test('is shown as a badge and colors the callout, in every template', () => {
    const fixture = schemaFixture();
    const risk = allSections(fixture.sections)
      .flatMap((s) => s.blocks)
      .find((b): b is Callout => b.kind === 'callout' && b.variant === 'risk')!;
    expect(risk.severity).toBe('high');
    const html = renderToStaticMarkup(<DocumentView document={fixture} />);
    expect(html).toContain(`class="callout callout-risk callout-severity-high"`);
    expect(html).toContain('<span class="severity severity-high">High</span>');
    expect(html).not.toContain('callout-severity-undefined');
  });
});
