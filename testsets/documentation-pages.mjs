// The hand-made references' documentation pages (lantern#249): C4 pages hold only C4 content, so
// the sources, the glossary and the decisions, risks and unknowns leave the context page, and the
// deployment table the first container page, where they sat as a stopgap, for pages of level
// `documentation` after the sequence pages. Used by each set's reference/build-data.mjs.

/** The documentation pages, each with the IDs of the sections it takes from the C4 pages. */
const PAGES = [
  {
    id: 'doc-findings',
    title: 'Decisions, risks and unknowns',
    sections: ['adrs', 'no-adrs', 'decisions', 'risks', 'unknowns'],
  },
  { id: 'doc-deployment', title: 'Deployment', sections: ['deployment'] },
  { id: 'doc-glossary', title: 'Glossary', sections: ['glossary'] },
  { id: 'doc-sources', title: 'Sources', sections: ['sources'] },
];

/** The pages with those sections moved from the C4 pages to documentation pages at the end. */
export function withDocumentationPages(pages) {
  const taken = new Map();
  const c4 = pages.map((page) => !['context', 'containers'].includes(page.level) ? page : ({
    ...page,
    sections: page.sections.filter((section) => {
      const doc = PAGES.find((d) => d.sections.includes(section.id));
      if (doc && taken.has(section.id)) throw new Error(`two C4 pages have a section ${section.id}`);
      if (doc) taken.set(section.id, section);
      return !doc;
    }),
  }));
  const docs = PAGES.map((page) => ({
    id: page.id,
    level: 'documentation',
    title: page.title,
    sections: page.sections.filter((id) => taken.has(id)).map((id) => taken.get(id)),
  })).filter((page) => page.sections.length > 0);
  return [...c4, ...docs];
}
