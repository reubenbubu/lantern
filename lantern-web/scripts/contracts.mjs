// The response contracts of the LLM roles on the scorecard (docs/adr/0118-response-contracts.md):
// each size rule of prompts/<role>.contract.json split three ways (within the requested range,
// accepted beyond it, rejected), and each judged text's 0 to 10 quality score (prompts/quality.json),
// built from weighted yes/no checks with where its points were lost.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
/** The prompts folder the contracts are read from. */
export const PROMPTS = join(REPO, 'prompts');

/** The default share of a requested maximum an answer may exceed it by (Contract.java). */
export const DEFAULT_TOLERANCE = 0.15;

/** The most units accepted for a requested maximum: plus the tolerance, at least one unit. */
export function accepted(requested, tolerance = DEFAULT_TOLERANCE) {
  return requested + Math.max(1, Math.round(requested * tolerance));
}

/** Every role's contract, with each word rule's accepted size worked out, by role. */
export function readContracts(dir = PROMPTS) {
  const contracts = {};
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.contract.json')).sort()) {
    const c = JSON.parse(readFileSync(join(dir, file), 'utf8'));
    const tolerance = c.tolerance ?? DEFAULT_TOLERANCE;
    for (const f of c.fields ?? []) {
      if (f.words && f.words.accepted === undefined && f.words.requested !== undefined) {
        f.words.accepted = accepted(f.words.requested, tolerance);
      }
    }
    contracts[c.role] = c;
  }
  return contracts;
}

/** The quality score's definition, prompts/quality.json. */
export function readQuality(dir = PROMPTS) {
  const path = join(dir, 'quality.json');
  return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : null;
}

/** The weak openings of the Generic check, as the harness reads them. */
export function readWeakOpenings() {
  const path = join(REPO, 'lantern-harness', 'src', 'main', 'resources', 'checks', 'weak-openings.txt');
  if (!existsSync(path)) return [];
  return readFileSync(path, 'utf8')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '' && !l.startsWith('#'))
    .map((l) => new RegExp(`^(?:${l})`, 'i'));
}

/** The number of words of a text, counted as the Form check counts them. */
export function words(text) {
  const t = (text ?? '').trim();
  return t === '' ? 0 : t.split(/\s+/).length;
}

/** The number of sentences of a text: ends of sentence followed by a space or the end. */
export function sentences(text) {
  const t = (text ?? '').trim();
  if (t === '') return 0;
  return t.split(/(?<=[.!?])\s+(?=[A-Z0-9"'(`])/).filter((s) => s.trim() !== '').length;
}

/** The texts a fact holds at an attribute path such as "title" or "sentences[].text". */
function textsAt(fact, attribute) {
  const a = fact.attributes ?? {};
  if (!attribute) return typeof a.text === 'string' ? [a.text] : [];
  const list = attribute.match(/^(\w+)\[\]\.(\w+)$/);
  if (list) {
    const items = Array.isArray(a[list[1]]) ? a[list[1]] : [];
    return items.map((i) => i?.[list[2]]).filter((t) => typeof t === 'string');
  }
  return typeof a[attribute] === 'string' ? [a[attribute]] : [];
}

/** Whether a fact is one the field's texts are stored in. */
function storedIn(field, fact) {
  const sel = field.facts ?? {};
  if (!sel.predicate) return false;
  if (!new RegExp(`^(?:${sel.predicate})$`).test(fact.predicate)) return false;
  return !sel.subject || new RegExp(sel.subject).test(fact.subject ?? '');
}

/** The answer field of a rejection reason, "interactions.R2: Has 9 words; ...". */
function rejectedField(reason) {
  const m = /^([^:\s]+):\s*Has (\d+) words; the limit is (\d+)\./.exec(reason ?? '');
  return m ? { field: m[1], words: Number(m[2]) } : null;
}

/**
 * Each size rule split three ways over a run: the stored texts within the requested range, those
 * beyond it but accepted, and the answers the checks rejected for their length. A rule without a
 * requested size counts every stored text as requested; a rule that is only measured counts a text
 * over its accepted size as over, since nothing rejected it.
 *
 * @param llm the run's facts of origin llm (llm.jsonl.gz), or null
 * @param rejections every rejected answer and dropped item (rejections.jsonl.gz), or null
 */
export function sizeRows(contracts, llm, rejections) {
  if (!llm && !rejections) return null;
  const rows = [];
  for (const c of Object.values(contracts)) {
    for (const f of c.fields ?? []) {
      if (!f.words) continue;
      const counts = { within: 0, beyond: 0, over: 0, rejected: 0 };
      for (const fact of llm ?? []) {
        if (!storedIn(f, fact)) continue;
        for (const text of textsAt(fact, f.facts.attribute)) {
          const n = words(text);
          if (f.words.requested === undefined || n <= f.words.requested) counts.within++;
          else if (n <= f.words.accepted) counts.beyond++;
          else counts.over++;
        }
      }
      const answer = f.answer ? new RegExp(f.answer) : null;
      for (const r of rejections ?? []) {
        if (!(c.harnessRoles ?? []).includes(r.role) || !answer || (r.kind && r.kind !== 'rejected')) continue;
        for (const reason of r.reasons ?? []) {
          const hit = rejectedField(reason);
          if (hit && answer.test(hit.field)) counts.rejected++;
        }
      }
      const total = counts.within + counts.beyond + counts.over + counts.rejected;
      rows.push({
        role: c.role,
        field: f.name,
        requested: f.words.requested ?? null,
        accepted: f.words.accepted ?? null,
        enforced: f.words.enforced !== false,
        total,
        ...counts,
      });
    }
  }
  return rows;
}

/** The contract field a judged text of the wording judge belongs to, or null. */
function fieldOfJudged(contracts, j) {
  for (const c of Object.values(contracts)) {
    if (!(c.harnessRoles ?? []).includes(j.role)) continue;
    for (const f of c.fields ?? []) {
      if (storedIn(f, { predicate: j.predicate, subject: j.subject, attributes: {} })) return { contract: c, field: f };
    }
  }
  return null;
}

/** Whether the text passes the deterministic check of the given id. */
function deterministic(id, text, field, quality, weakOpenings) {
  const lower = text.trim().toLowerCase();
  const limit = field.words?.requested ?? field.words?.accepted;
  switch (id) {
    case 'length':
      return limit === undefined || words(text) <= limit;
    case 'sentences': {
      const range = field.sentences?.requested;
      if (!range) return true;
      const n = sentences(text);
      return n >= range[0] && n <= range[1];
    }
    case 'opening':
      return !weakOpenings.some((p) => p.test(text.trim()));
    case 'filler':
      return !quality.filler.some((f) => new RegExp(`\\b${f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i').test(lower));
    case 'protocol':
      return !quality.protocols.some((p) => new RegExp(`\\b${p}\\b`, 'i').test(text));
    case 'verb': {
      if (quality.bannedOpenings.some((b) => lower === b || lower.startsWith(`${b} `))) return false;
      const first = lower.split(/\s+/)[0] ?? '';
      // A verb that says what is done: "Verifies", "Sends", "Notify"; not an article or pronoun.
      return /^[a-z]+$/.test(first) && !['a', 'an', 'the', 'this', 'it', 'its', 'to', 'for', 'and'].includes(first);
    }
    default:
      return true;
  }
}

/**
 * The 0 to 10 quality score of every text the wording judge judged on both of its questions, by
 * the weighted yes/no checks of its kind (prompts/quality.json): the weights of the checks passed,
 * at most the gate cap when a gate check fails. Per role, the mean score and the points lost per
 * check, most first.
 *
 * @param wording the wording judge's verdicts (wording.jsonl.gz), or null
 */
export function qualityRows(contracts, quality, wording, weakOpenings = readWeakOpenings()) {
  if (!wording || !quality) return null;
  const texts = new Map();
  for (const j of wording) {
    if (j.state !== 'judged') continue;
    const key = `${j.fact}\u0000${j.text}`;
    const t = texts.get(key) ?? { j, verdicts: {} };
    t.verdicts[j.dimension] = j.verdict;
    texts.set(key, t);
  }
  const byRole = new Map();
  for (const { j, verdicts } of texts.values()) {
    const found = fieldOfJudged(contracts, j);
    if (!found) continue;
    const checks = quality.kinds[found.field.kind];
    if (!checks) continue;
    if (checks.some((c) => c.judged && !(verdicts[c.judged] === 'pass' || verdicts[c.judged] === 'fail'))) continue;
    let score = 0;
    let gateFailed = false;
    const lost = [];
    for (const c of checks) {
      const pass = c.judged ? verdicts[c.judged] === 'pass' : deterministic(c.id, j.text, found.field, quality, weakOpenings);
      if (pass) score += c.weight;
      else {
        lost.push(c);
        if (c.gate) gateFailed = true;
      }
    }
    if (gateFailed) score = Math.min(score, quality.gateCap);
    const role = found.contract.role;
    const row = byRole.get(role) ?? { role, texts: 0, total: 0, capped: 0, lost: {} };
    row.texts++;
    row.total += score;
    if (gateFailed) row.capped++;
    for (const c of lost) {
      const l = row.lost[c.id] ?? { id: c.id, name: c.name, points: 0, texts: 0 };
      l.points += c.weight;
      l.texts++;
      row.lost[c.id] = l;
    }
    byRole.set(role, row);
  }
  return [...byRole.values()].map((r) => ({
    role: r.role,
    texts: r.texts,
    mean: Math.round((r.total / r.texts) * 10) / 10,
    capped: r.capped,
    lost: Object.values(r.lost)
      .map((l) => ({ ...l, perText: Math.round((l.points / r.texts) * 100) / 100 }))
      .sort((a, b) => b.points - a.points),
  }));
}

/** "82%" of a part, or "-" when there is nothing to count. */
function pct(part, total) {
  return total === 0 ? '-' : `${Math.round((100 * part) / total)}%`;
}

/** The scorecard's section on the response contracts, as Markdown lines. */
export function contractLines(sizes, quality, tolerancePoints) {
  const lines = ['', '## Response contracts', ''];
  if (!sizes) {
    lines.push('Not measured: the run left no llm.jsonl.gz or rejections.jsonl.gz beside the report.');
  } else {
    lines.push(
      'Each size rule of `prompts/<role>.contract.json`: of the texts stored and the answers rejected for their length, the share within the requested size, beyond it but accepted, and rejected.',
      '',
      '| Role | Field | Requested | Accepted | Texts | Within requested | Accepted beyond | Rejected |',
      '| --- | --- | --- | --- | --- | --- | --- | --- |',
    );
    for (const r of sizes.filter((s) => s.total > 0)) {
      const beyond = r.beyond + r.over;
      lines.push(
        `| ${r.role} | ${r.field} | ${r.requested ?? '-'} | ${r.accepted ?? '-'}${r.enforced ? '' : ' (measured)'} | ${r.total} | ${pct(r.within, r.total)} | ${pct(beyond, r.total)}${r.over > 0 ? ` (${r.over} over)` : ''} | ${pct(r.rejected, r.total)} |`,
      );
    }
    const silent = sizes.filter((s) => s.total === 0).map((s) => `${s.role} ${s.field}`);
    if (silent.length > 0) lines.push('', `No texts this run: ${silent.join(', ')}.`);
  }
  lines.push('', '### Quality score', '');
  if (!quality || quality.length === 0) {
    lines.push('Not measured: the run was not judged by the wording judge (`--judge-wording`).');
  } else {
    lines.push(
      `0 to 10 per text, from weighted yes/no checks (\`prompts/quality.json\`); a difference of under ${tolerancePoints / 10} between two runs is within the paired rule's tolerance of ${tolerancePoints} points in 100.`,
      '',
      '| Role | Texts judged | Mean | Capped by the gate | Most points lost |',
      '| --- | --- | --- | --- | --- |',
    );
    for (const r of quality) {
      const top = r.lost
        .slice(0, 3)
        .map((l) => `${l.name} (${l.perText < 0.01 ? 'under 0.01' : l.perText} a text)`)
        .join('; ');
      lines.push(`| ${r.role} | ${r.texts} | ${r.mean}/10 | ${r.capped} | ${top || 'none'} |`);
    }
  }
  return lines;
}
