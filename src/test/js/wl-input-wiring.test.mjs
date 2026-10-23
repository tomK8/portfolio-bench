/*
 * A guard on how dashboard.js reaches the watchlist's editable cells.
 *
 * Run: node --test src/test/js/wl-input-wiring.test.mjs
 *
 * The rule it enforces: a lookup for one of the watchlist's three editable input classes may never
 * be dereferenced on the spot. Every one of them can legitimately miss, because the column picker
 * decides which of the four levels, the two order targets and the note this list puts on screen —
 * so `row.querySelector('.wl-thr-input[data-kind="high"]').value` is a TypeError on any layout that
 * has taken High % off, not a rare edge case.
 *
 * What that cost (2026-10-23): the default list showed `Below trigger` alone. The change handler
 * read all four levels that way, so three lookups returned null and every below-trigger edit threw
 * *before* the save was issued. Nothing persisted, so a refresh brought the old level back; and
 * because POST /watchlist/threshold was never reached, clearFiredFor never ran either, so the row
 * stayed coloured at a level the user had just moved out of reach. No error reached the screen —
 * the only evidence was the console.
 *
 * wl-edit-sync.test.mjs locks down what a save must then *carry* (a hidden box keeps its stored
 * value rather than posting a blank that clears it). This locks down that the lookup is guarded at
 * all, which is the half no pure-function test can see.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '../../main/resources/static/js/dashboard.js');
const src = fs.readFileSync(SRC, 'utf8');

// querySelector(...) whose selector names an editable watchlist input, followed immediately by a
// property access. querySelectorAll is deliberately not matched: it returns a list, never null.
const UNGUARDED = /querySelector\(\s*[^;\n]*wl-(?:thr|tgt|note)-input[^;\n]*?\)\s*\./g;

test('REGRESSION: no watchlist input lookup is dereferenced without a null check', () => {
    const hits = [];
    src.split('\n').forEach((line, i) => {
        UNGUARDED.lastIndex = 0;
        if (UNGUARDED.test(line)) hits.push((i + 1) + ': ' + line.trim());
    });
    assert.deepEqual(hits, [],
        'A hidden column makes these lookups null — assign, then guard:\n' + hits.join('\n'));
});

test('the guard would have caught the shipped bug', () => {
    // The exact line that broke, so a future rewrite of the pattern can be checked against it.
    const broken = `var high = row.querySelector('.wl-thr-input[data-kind="high"]').value.trim();`;
    UNGUARDED.lastIndex = 0;
    assert.ok(UNGUARDED.test(broken));

    // …and the shapes it must not flag: a guarded lookup, and the list form.
    const guarded = `var el = row.querySelector('.wl-thr-input[data-kind="' + kind + '"]');`;
    const all = `tr.querySelectorAll('.wl-thr-input').forEach(function (inp) {`;
    UNGUARDED.lastIndex = 0;
    assert.ok(!UNGUARDED.test(guarded));
    UNGUARDED.lastIndex = 0;
    assert.ok(!UNGUARDED.test(all));
});
