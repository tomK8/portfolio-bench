/*
 * The returns heatmap's two global line switches.
 *
 * Run: node --test src/test/js/wl-ret-lines.test.mjs
 *
 * Each symbol in the heatmap has two lines it can show: its daily returns, and the Σ line that
 * compounds them. A row click toggles one symbol's Σ line; two checkboxes answer for the whole
 * table — "show cumulative for all rows" (Σ *beside* every daily line) and "show cumulative
 * only" (Σ *instead of* every daily line).
 *
 * The failure mode worth a guard is the empty table: "only" hides the daily lines, so if it ever
 * stopped implying the Σ ones the panel would render as nothing but column headers — the same
 * shape of bug as a watchlist column layout resolving to no columns. wlRetLines is the whole rule
 * in one expression; this exercises all four settings of the two switches and asserts a symbol
 * never loses both lines.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '../../main/resources/static/js/dashboard.js');
const src = fs.readFileSync(SRC, 'utf8');

// dashboard.js is one big IIFE, so the rule is lifted out of the shipped source rather than
// imported: the test then can't pass against a copy that is no longer what the browser runs.
function loadRule() {
    const m = src.match(/function wlRetLines\(cumAll, cumOnly\)\s*\{[\s\S]*?\n\s{8}\}/);
    assert.ok(m, 'wlRetLines is the one place the two switches are resolved');
    return new Function(m[0] + '; return wlRetLines;')();
}

const COMBOS = [
    [false, false, 'neither switch'],
    [true, false, 'Σ for all rows'],
    [false, true, 'Σ only'],
    [true, true, 'both ticked'],
];

test('a symbol never loses both lines, whatever the two switches say', () => {
    const wlRetLines = loadRule();
    for (const [all, only, what] of COMBOS) {
        const r = wlRetLines(all, only);
        assert.ok(r.daily || r.cum, `${what} leaves the row blank`);
    }
});

test('default: the daily line alone', () => {
    const r = loadRule()(false, false);
    assert.deepEqual(r, { daily: true, cum: false });
});

test('for all rows: both lines, each symbol doubles up', () => {
    const r = loadRule()(true, false);
    assert.deepEqual(r, { daily: true, cum: true });
});

test('only: the Σ line in place of the daily one, not beside it', () => {
    const r = loadRule()(false, true);
    assert.deepEqual(r, { daily: false, cum: true });
});

test('only wins over for-all-rows rather than doubling up again', () => {
    // The UI disables the other box while "only" is on, but the rule must not depend on that:
    // the box keeps its own state so unticking "only" hands the table back to it.
    assert.deepEqual(loadRule()(true, true), { daily: false, cum: true });
});

test('both switches are wired, and to one apply that derives the table', () => {
    ['wl-cum-all', 'wl-cum-only'].forEach(id => {
        assert.ok(src.includes("document.getElementById('" + id + "').addEventListener('change'"),
            id + ' is not wired');
    });
    // Each handler records its switch and re-derives; neither reaches into the rows itself.
    const i = src.indexOf("document.getElementById('wl-cum-all').addEventListener('change'");
    const handlers = src.slice(i, src.indexOf('applyRetLines();\n        }', i));
    assert.equal((handlers.match(/applyRetLines\(\)/g) || []).length, 2,
        'both handlers go through applyRetLines');
    assert.ok(!/querySelectorAll/.test(handlers),
        'a handler walking the rows itself is a second rule that can disagree with wlRetLines');
});
