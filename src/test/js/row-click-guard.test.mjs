/*
 * A guard on the click that opens the shared price popup.
 *
 * Run: node --test src/test/js/row-click-guard.test.mjs
 *
 * Four tables open the popup by clicking a row — Holdings, the cash ledger, Open orders (with the
 * Ladders and Switches blocks) and the Watchlist — and each of them has cells you click *into*
 * rather than through. So each needs the same question answered: was this a control, or the row?
 *
 * What spelling it out four times cost (2026-10-28): the Watchlist's copy read `input, button`,
 * which was true until the Note column arrived as a <textarea> — an <input> can only ever scroll
 * one line of a plan. Clicking into a note opened the popup over the box being aimed at, and the
 * popup shows that same note read-only, so the only way to edit it was to dismiss what the click
 * had just raised. One `clickedControl` now answers for all of them, and the vocabulary can only
 * drift in one place.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '../../main/resources/static/js/dashboard.js');
const src = fs.readFileSync(SRC, 'utf8');

test('clickedControl names every element you click into, textarea included', () => {
    const m = src.match(/function clickedControl\(target\)\s*\{[\s\S]*?\n\s*\}/);
    assert.ok(m, 'clickedControl is the one place the control vocabulary is written down');
    const sel = m[0].match(/closest\('([^']+)'\)/);
    assert.ok(sel, 'it asks closest() with a selector list');
    const tags = sel[1].split(',').map(s => s.trim());
    // textarea is the one the Watchlist's Note cell needs and the shipped bug was missing.
    ['input', 'textarea', 'select', 'button', 'a', 'label'].forEach(tag => {
        assert.ok(tags.includes(tag), `${tag} is missing from the control list: ${sel[1]}`);
    });
});

test('REGRESSION: no popup handler spells the control list out for itself', () => {
    // The shape of every copy that existed before: a closest() listing the tags inline. A new one
    // is a fifth vocabulary that can fall behind the cells, which is exactly what happened.
    const SPELLED = /closest\(\s*['"][^'"]*\b(?:input|button)\b[^'"]*,[^'"]*\b(?:input|button|a|select|label)\b/g;
    const hits = [];
    // Blanked rather than skipped by name: clickedControl's own selector spans two lines, and the
    // one on the line below carries no clue that it belongs to the function that is allowed it.
    const scanned = src.replace(/function clickedControl\(target\)\s*\{[\s\S]*?\n\s*\}/,
        m => m.replace(/[^\n]/g, ' '));
    scanned.split('\n').forEach((line, i) => {
        SPELLED.lastIndex = 0;
        if (SPELLED.test(line)) hits.push((i + 1) + ': ' + line.trim());
    });
    assert.deepEqual(hits, [], 'Call clickedControl(e.target) instead:\n' + hits.join('\n'));
});

test('the watchlist row asks before it opens, and excludes an editable cell whole', () => {
    const i = src.indexOf("tr.addEventListener('click'");
    assert.ok(i > 0, 'wlBuildRow wires the row click');
    const body = src.slice(i, src.indexOf('openWlModal(tr._wlRow)', i));
    assert.match(body, /clickedControl\(e\.target\)/,
        'the control guard runs before the popup opens');
    // The boxes are all fixed-width and an expanded Note sits at the top of a tall row, so the
    // cell is wider than its editor: landing in the gap must not open the popup either.
    assert.match(body, /WL_INPUT_COLS\[cell\.dataset\.f\]/,
        'an editable cell is excluded by the same list that declares it editable');
});
