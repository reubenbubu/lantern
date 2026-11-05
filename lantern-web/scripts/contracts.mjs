// The response contracts of the LLM roles on the scorecard (docs/adr/0118-response-contracts.md):
// each size rule of prompts/<role>.contract.json split three ways (within the requested range,
// accepted outside it, rejected), too short and too long apart, and each judged text's 0 to 10 quality score (prompts/quality.json),
// built from weighted yes/no checks with where its points were lost.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
/** The prompts folder the contracts are read from. */
export const PROMPTS = join(REPO, 'prompts');

/** The default share of each bound of a requested range an answer may pass it by (Contract.java). */
export const DEFAULT_TOLERANCE = 0.15;

/**
 * The accepted range for a requested range [min, max]: each bound moved out by the tolerance's
 * share of it, rounded, at least one unit, and the minimum never below the floor (one word for a
 * text, none for a count), as the checks compute it.
 */
export function accepted([min, max], tolerance = DEFAULT_TOLERANCE, floor = 1) {
  const margin = (bound) => Math.max(1, Math.round(bound * tolerance));
  return [Math.max(floor, min - margin(min)), max + margin(max)];
}

/** Every role's contract, with each size rule's accepted range worked out, by role. */
export function readContracts(dir = PROMPTS) {
  const contracts = {};
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.contract.json')).sort()) {
    const c = JSON.parse(readFileSync(join(dir, file), 'utf8'));
    const tolerance = c.tolerance ?? DEFAULT_TOLERANCE;
    for (const f of c.fields ?? []) {
      for (const rule of [f.words, f.sentences]) {
        if (rule && rule.accepted === undefined && Array.isArray(rule.requested)) {
          rule.accepted = accepted(rule.requested, tolerance);
        }
      }
    }
    for (const count of c.counts ?? []) {
      if (count.accepted === undefined && Array.isArray(count.requested)) {
        count.accepted = accepted(count.requested, tolerance, 0);
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
  // A merged line's intent is told from a link's by its object, the line's key (#810).
  if (sel.object && fact.object && !new RegExp(sel.object).test(fact.object.value ?? '')) return false;
  return !sel.subject || new RegExp(sel.subject).test(fact.subject ?? '');
}

/**
 * The answer field, word count and side of a length rejection: "interactions.R2: Has 9 words; the
 * accepted range is 4 to 8 words, ...", or before ranges "...; the limit is 8.", which was always
 * too long.
 */
function rejectedField(reason) {
  const m = /^([^:\s]+):\s*Has (\d+) words; the (?:limit is (\d+)\.|accepted range is (\d+) to (\d+) words)/.exec(reason ?? '');
  if (!m) return null;
  const n = Number(m[2]);
  return { field: m[1], words: n, short: m[4] !== undefined && n < Number(m[4]) };
}

/**
 * Each size rule split over a run, too short and too long apart: the stored texts within the
 * requested range; those outside it but accepted, short or long; those outside even the accepted
 * range, which only a rule that is measured and not enforced keeps (or a run before the rule); and
 * the answers the checks rejected for their length, short or long. Rejections are counted apart from
 * the stored texts, a first answer and its repair separately, since a rejected answer that is
 * repaired is also stored and a rule such as Resolve's reason stores no texts at all. A sentence
 * rule is measured only: it has stored texts and no rejections.
 *
 * @param llm the run's facts of origin llm (llm.jsonl.gz), or null
 * @param rejections every rejected answer and dropped item (rejections.jsonl.gz), or null
 */
export function sizeRows(contracts, llm, rejections) {
  if (!llm && !rejections) return null;
  const rows = [];
  for (const c of Object.values(contracts)) {
    for (const f of c.fields ?? []) {
      for (const unit of ['words', 'sentences']) {
        const rule = f[unit];
        if (!rule) continue;
        const count = unit === 'words' ? words : sentences;
        const [rMin, rMax] = rule.requested ?? [0, Infinity];
        const [aMin, aMax] = rule.accepted ?? [0, Infinity];
        const counts = {
          within: 0,
          acceptedShort: 0,
          acceptedLong: 0,
          outsideShort: 0,
          outsideLong: 0,
          rejectedShort: 0,
          rejectedLong: 0,
          rejectedShortAgain: 0,
          rejectedLongAgain: 0,
        };
        for (const fact of llm ?? []) {
          if (!storedIn(f, fact)) continue;
          for (const text of textsAt(fact, f.facts.attribute)) {
            const n = count(text);
            if (n >= rMin && n <= rMax) counts.within++;
            else if (n < aMin) counts.outsideShort++;
            else if (n > aMax) counts.outsideLong++;
            else if (n < rMin) counts.acceptedShort++;
            else counts.acceptedLong++;
          }
        }
        const answer = f.answer ? new RegExp(f.answer) : null;
        for (const r of unit === 'words' ? (rejections ?? []) : []) {
          if (!(c.harnessRoles ?? []).includes(r.role) || !answer || (r.kind && r.kind !== 'rejected')) continue;
          for (const reason of r.reasons ?? []) {
            const hit = rejectedField(reason);
            if (!hit || !answer.test(hit.field)) continue;
            const side = hit.short ? 'Short' : 'Long';
            counts[`rejected${side}${(r.attempt ?? 1) > 1 ? 'Again' : ''}`]++;
          }
        }
        const total = counts.within + counts.acceptedShort + counts.acceptedLong + counts.outsideShort + counts.outsideLong;
        rows.push({
          role: c.role,
          field: f.name,
          unit,
          requested: rule.requested ?? null,
          accepted: rule.accepted ?? null,
          enforced: unit === 'words' && rule.enforced !== false,
          total,
          ...counts,
        });
      }
    }
  }
  return rows;
}

/**
 * The contract field a judged text of the wording judge belongs to, or null when none or more than
 * one could hold it (fields stored in one fact's attributes cannot be told apart by the verdict).
 */
function fieldOfJudged(contracts, j) {
  const found = [];
  for (const c of Object.values(contracts)) {
    if (!(c.harnessRoles ?? []).includes(j.role)) continue;
    for (const f of c.fields ?? []) {
      if (storedIn(f, { predicate: j.predicate, subject: j.subject, attributes: {} })) found.push({ contract: c, field: f });
    }
  }
  return found.length === 1 ? found[0] : null;
}

/** Whether the text passes the deterministic check of the given id. */
function deterministic(id, text, field, quality, weakOpenings) {
  const lower = text.trim().toLowerCase();
  const range = field.words?.requested ?? field.words?.accepted;
  switch (id) {
    case 'length': {
      if (!range) return true;
      const n = words(text);
      return n >= range[0] && n <= range[1];
    }
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
      'Each size rule of `prompts/<role>.contract.json`, too short and too long apart. Of the texts stored: the share within the requested range; accepted outside it, short and long; and outside even the accepted range (only a rule that is measured, not enforced, keeps those). Then how many answers the checks rejected for their length, short and long, each as first answers and repairs (a repaired answer is also stored). Sentence rules are measured only.',
      '',
      '| Role | Field | Unit | Requested | Accepted | Texts | Within requested | Accepted short | Accepted long | Outside accepted (short, long) | Rejected short (first, repair) | Rejected long (first, repair) |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    );
    const range = (r) => (r ? `${r[0]} to ${r[1]}` : '-');
    const rejected = (s) => s.rejectedShort + s.rejectedLong + s.rejectedShortAgain + s.rejectedLongAgain;
    const shown = (s) => s.total + rejected(s) > 0;
    for (const r of sizes.filter(shown)) {
      lines.push(
        `| ${r.role} | ${r.field} | ${r.unit} | ${range(r.requested)} | ${range(r.accepted)}${r.enforced ? '' : ' (measured)'} | ${r.total} | ${pct(r.within, r.total)} | ${pct(r.acceptedShort, r.total)} | ${pct(r.acceptedLong, r.total)} | ${r.outsideShort}, ${r.outsideLong} | ${r.rejectedShort}, ${r.rejectedShortAgain} | ${r.rejectedLong}, ${r.rejectedLongAgain} |`,
      );
    }
    const silent = sizes.filter((s) => !shown(s)).map((s) => `${s.role} ${s.field} (${s.unit})`);
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
