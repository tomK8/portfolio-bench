/*
 * Unit tests for the watchlist inline-edit sync logic (wl-edit-sync.js).
 *
 * Run: node --test src/test/js/wl-edit-sync.test.mjs   (needs only node ≥ 18 — no deps)
 *
 * These lock down the fix for "my watchlist threshold edit won't save": a slow /watchlist reload
 * issued around the same instant as a fast inline save would land with a pre-save (stale) value and
 * revert the input. resolve() is the pure decision that keeps the just-saved value on screen until a
 * strictly-later read supersedes it. The scenarios below mirror the Playwright reproduction.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const WL = require(path.resolve(here, '../../main/resources/static/js/wl-edit-sync.js'));

// ---- thrDisplay: raw input → canonical cell string --------------------------------------------
test('thrDisplay canonicalises prices and strips currency/formatting', () => {
    assert.equal(WL.thrDisplay('below', '99.7'), '99.7');
    assert.equal(WL.thrDisplay('below', '£99.70'), '99.7');   // currency + trailing zero
    assert.equal(WL.thrDisplay('below', ' 100.02 '), '100.02');
    assert.equal(WL.thrDisplay('below', '1,234.5'), '1234.5'); // thousands separator
    assert.equal(WL.thrDisplay('below', 100.02), '100.02');    // numeric input, not a string
});

test('thrDisplay treats blank / non-numeric / non-positive as disabled ("")', () => {
    assert.equal(WL.thrDisplay('below', ''), '');
    assert.equal(WL.thrDisplay('below', null), '');
    assert.equal(WL.thrDisplay('below', undefined), '');
    assert.equal(WL.thrDisplay('below', 'abc'), '');
    assert.equal(WL.thrDisplay('above', '0'), '');
    assert.equal(WL.thrDisplay('high', '-3'), '');
    assert.equal(WL.thrDisplay('high', '3%'), '3');
});

// ---- rowThresholds: server row → the four cell strings ----------------------------------------
test('rowThresholds reads a server row into canonical strings', () => {
    const row = { highThresholdPct: 0, moveThresholdPct: 0, priceAbove: null, priceBelow: 100.02 };
    assert.deepEqual(WL.rowThresholds(row), { high: '', move: '', above: '', below: '100.02' });

    const row2 = { highThresholdPct: 3, moveThresholdPct: 10, priceAbove: 232, priceBelow: 0 };
    // priceBelow 0 is disabled; server usually sends null, but 0 must also render blank.
    assert.deepEqual(WL.rowThresholds(row2), { high: '3', move: '10', above: '232', below: '' });
});

test('equal compares the four kinds', () => {
    assert.ok(WL.equal({ high: '', move: '', above: '', below: '99.5' },
                       { high: '', move: '', above: '', below: '99.5' }));
    assert.ok(!WL.equal({ high: '', move: '', above: '', below: '99.5' },
                        { high: '', move: '', above: '', below: '99.7' }));
});

// ---- resolve: the race-resolution core --------------------------------------------------------

test('REGRESSION: a stale reload (server has NOT echoed the save) keeps the just-saved value', () => {
    // The user set below=99.5. A /watchlist reload returns the pre-save row (below=99.7) because its
    // server read beat the commit. The overlay must win until the server actually carries 99.5 back.
    const staleRow = { symbol: 'GILT 4.375% 2030', highThresholdPct: 0, moveThresholdPct: 0,
                       priceAbove: null, priceBelow: 99.7 };
    const pending = WL.savedThresholds('', '', '', '99.5');
    const res = WL.resolve(staleRow, pending);
    assert.equal(res.values.below, '99.5', 'must show the saved value, not the stale 99.7');
    assert.equal(res.confirmed, false, 'server has not echoed the save yet');
});

test('REGRESSION: a stale reload that ARRIVES AFTER the save request still cannot revert it', () => {
    // This is the case that broke the first attempt: the reload was issued a hair after the save but
    // read pre-commit. Issue order is irrelevant — only whether the server echoes the value matters.
    const staleRow = { symbol: 'X', highThresholdPct: 0, moveThresholdPct: 0, priceAbove: null, priceBelow: 100.01 };
    const pending = WL.savedThresholds('', '', '', '99.5');
    const res = WL.resolve(staleRow, pending);
    assert.equal(res.values.below, '99.5');
    assert.equal(res.confirmed, false);
});

test('once the server echoes the saved value the overlay is confirmed', () => {
    const freshRow = { symbol: 'X', highThresholdPct: 0, moveThresholdPct: 0, priceAbove: null, priceBelow: 99.5 };
    const pending = WL.savedThresholds('', '', '', '99.5');
    const res = WL.resolve(freshRow, pending);
    assert.equal(res.values.below, '99.5');
    assert.equal(res.confirmed, true, 'server row now carries the saved value');
});

test('no pending overlay → server values pass straight through', () => {
    const row = { symbol: 'X', highThresholdPct: 0, moveThresholdPct: 0, priceAbove: null, priceBelow: 100.3 };
    const res = WL.resolve(row, undefined);
    assert.equal(res.values.below, '100.3');
    assert.equal(res.confirmed, false);
});

test('clearing a level (blank) is defended against a stale reload that still has the old level', () => {
    // User blanked Below to disable it; a stale reload still carries the old 100.02.
    const staleRow = { symbol: 'X', highThresholdPct: 0, moveThresholdPct: 0, priceAbove: null, priceBelow: 100.02 };
    const pending = WL.savedThresholds('', '', '', '');
    const res = WL.resolve(staleRow, pending);
    assert.equal(res.values.below, '', 'the disable must stick, not revert to 100.02');
    assert.equal(res.confirmed, false);
});

test('resolve preserves all four fields, not just the one edited', () => {
    const staleRow = { symbol: 'X', highThresholdPct: 3, moveThresholdPct: 5, priceAbove: 250, priceBelow: 99.7 };
    const pending = WL.savedThresholds('3', '5', '250', '99.5');
    const res = WL.resolve(staleRow, pending);
    assert.deepEqual(res.values, { high: '3', move: '5', above: '250', below: '99.5' });
});

// ---- the note: the same overlay, on text rather than a number --------------------------------
test('noteDisplay folds null, undefined and blank into one absence', () => {
    assert.equal(WL.noteDisplay(null), '');
    assert.equal(WL.noteDisplay(undefined), '');
    assert.equal(WL.noteDisplay('   '), '');
    assert.equal(WL.noteDisplay('  Trim 25% in the SIPP  '), 'Trim 25% in the SIPP');
});

test('resolveNote shows the server note when nothing is pending', () => {
    assert.deepEqual(WL.resolveNote({ note: 'Trim 25%' }, null),
        { value: 'Trim 25%', confirmed: false });
    assert.deepEqual(WL.resolveNote({ note: null }, undefined),
        { value: '', confirmed: false });
    assert.deepEqual(WL.resolveNote(null, null), { value: '', confirmed: false });
});

test('a just-saved note survives a render that has not caught up to it', () => {
    // The stale-reload race, in text form: the reload's server-side read predates the note commit,
    // so its row still carries the old plan. The overlay wins until a render echoes the new one.
    const stale = { note: 'Trim 25%' };
    assert.deepEqual(WL.resolveNote(stale, 'Trim half'), { value: 'Trim half', confirmed: false });

    const echoed = { note: 'Trim half' };
    assert.deepEqual(WL.resolveNote(echoed, 'Trim half'), { value: 'Trim half', confirmed: true });
});

test('clearing a note is itself a pending value, not an absence of one', () => {
    // Saving "" must not read as "no overlay" — otherwise the server's old text comes straight back.
    assert.deepEqual(WL.resolveNote({ note: 'Trim 25%' }, ''), { value: '', confirmed: false });
    assert.deepEqual(WL.resolveNote({ note: null }, ''), { value: '', confirmed: true });
});

test('the note overlay is independent of the threshold overlay', () => {
    // Two separate saves to two separate endpoints (a note edit deliberately re-arms no alerts), so
    // neither resolver may read the other's field: one record for all five would have each write
    // blank the other's pending value.
    const row = { note: 'Trim 25%', highThresholdPct: 5, moveThresholdPct: 3,
                  priceAbove: 105, priceBelow: null };
    assert.deepEqual(WL.resolve(row, null).values,
        { high: '5', move: '3', above: '105', below: '' });
    assert.equal(WL.resolveNote(row, null).value, 'Trim 25%');
    // A pending threshold save says nothing about the note, and vice versa.
    assert.equal(WL.resolveNote(row, undefined).value, 'Trim 25%');
    assert.deepEqual(WL.resolve(row, { high: '6', move: '3', above: '105', below: '' }).values,
        { high: '6', move: '3', above: '105', below: '' });
});
