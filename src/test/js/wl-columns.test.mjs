/*
 * Unit tests for the watchlist column-layout resolver.
 * Run: node --test src/test/js/wl-columns.test.mjs   (needs only node ≥ 18 — no deps)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const WlColumns = require(path.resolve(here, '../../main/resources/static/js/wl-columns.js'));

/** A stand-in registry: the defaults plus a couple of fundamentals keys. */
const REGISTRY = WlColumns.DEFAULTS.concat(['f:fcfMarginTtm', 'f:returnOnEquity', 'beta', 'gainPct']);

test('a list that was never customised gets the shipped default set', () => {
    assert.deepEqual(WlColumns.resolve([], REGISTRY), WlColumns.DEFAULTS);
    assert.deepEqual(WlColumns.resolve(null, REGISTRY), WlColumns.DEFAULTS);
    assert.deepEqual(WlColumns.resolve(undefined, REGISTRY), WlColumns.DEFAULTS);
});

test('a saved layout is honoured in its own order', () => {
    const saved = ['f:fcfMarginTtm', 'price', 'todayPct'];
    assert.deepEqual(WlColumns.resolve(saved, REGISTRY), saved);
});

test('a key this build no longer offers is dropped, not rendered as an empty column', () => {
    const saved = ['price', 'f:somethingRemoved', 'todayPct'];
    assert.deepEqual(WlColumns.resolve(saved, REGISTRY), ['price', 'todayPct']);
});

test('a layout of only unknown keys falls back to the defaults, never to no columns', () => {
    // Without this a single bad row in watchlist_column leaves the list blank for ever, with
    // no column picker reachable to fix it.
    assert.deepEqual(WlColumns.resolve(['f:gone', 'alsoGone'], REGISTRY), WlColumns.DEFAULTS);
});

test('Symbol and the actions cell are stripped — they are pinned, not part of a layout', () => {
    const out = WlColumns.resolve(['sym', 'price', 'actions', 'todayPct'], REGISTRY);
    assert.deepEqual(out, ['price', 'todayPct']);
});

test('a duplicated key appears once, at its first position', () => {
    assert.deepEqual(WlColumns.resolve(['price', 'todayPct', 'price'], REGISTRY), ['price', 'todayPct']);
});

test('blank and non-string entries are ignored', () => {
    assert.deepEqual(WlColumns.resolve(['price', '', '  ', null, 7, 'todayPct'], REGISTRY),
        ['price', 'todayPct']);
});

test('whitespace around a stored key is tolerated', () => {
    assert.deepEqual(WlColumns.resolve([' price ', 'todayPct'], REGISTRY), ['price', 'todayPct']);
});

test('with no registry supplied every saved key is kept', () => {
    // The picker has not loaded yet; rendering the stored layout beats rendering the defaults
    // and then jumping to the real layout a moment later.
    assert.deepEqual(WlColumns.resolve(['price', 'f:anything'], []), ['price', 'f:anything']);
});

test('move swaps with the neighbour and is a no-op at either end', () => {
    const keys = ['a', 'b', 'c'];
    assert.deepEqual(WlColumns.move(keys, 'b', true), ['b', 'a', 'c']);
    assert.deepEqual(WlColumns.move(keys, 'b', false), ['a', 'c', 'b']);
    assert.deepEqual(WlColumns.move(keys, 'a', true), ['a', 'b', 'c'], 'already first');
    assert.deepEqual(WlColumns.move(keys, 'c', false), ['a', 'b', 'c'], 'already last');
    assert.deepEqual(WlColumns.move(keys, 'zz', true), ['a', 'b', 'c'], 'not present');
    assert.deepEqual(keys, ['a', 'b', 'c'], 'the input is not mutated');
});

test('addFirst puts a new column right after Symbol, which is where a comparison wants it', () => {
    assert.deepEqual(WlColumns.addFirst(['price', 'todayPct'], 'f:returnOnEquity'),
        ['f:returnOnEquity', 'price', 'todayPct']);
});

test('addFirst moves a column already present rather than duplicating it', () => {
    assert.deepEqual(WlColumns.addFirst(['price', 'f:returnOnEquity', 'todayPct'], 'f:returnOnEquity'),
        ['f:returnOnEquity', 'price', 'todayPct']);
});

test('a pinned key cannot be added to a layout', () => {
    assert.deepEqual(WlColumns.addFirst(['price'], 'sym'), ['price']);
    assert.deepEqual(WlColumns.addFirst(['price'], 'actions'), ['price']);
});

test('remove drops the key and leaves the rest in order', () => {
    assert.deepEqual(WlColumns.remove(['a', 'b', 'c'], 'b'), ['a', 'c']);
    assert.deepEqual(WlColumns.remove(['a', 'b'], 'zz'), ['a', 'b']);
});

test('moveTo drops a key at an absolute position, counted without the key itself', () => {
    // Dragging 'a' onto 'c' puts it where 'c' was: index 2 is read against ['b','c','d'].
    assert.deepEqual(WlColumns.moveTo(['a', 'b', 'c', 'd'], 'a', 2), ['b', 'c', 'a', 'd']);
    // The same target index from below lands in the same place — which is the whole point of
    // removing first: a drop means "here", not "here, unless you came from above".
    assert.deepEqual(WlColumns.moveTo(['b', 'c', 'a', 'd'], 'a', 2), ['b', 'c', 'a', 'd']);
    assert.deepEqual(WlColumns.moveTo(['a', 'b', 'c'], 'c', 0), ['c', 'a', 'b']);
});

test('moveTo clamps an out-of-range index to an end rather than losing the key', () => {
    assert.deepEqual(WlColumns.moveTo(['a', 'b', 'c'], 'a', 99), ['b', 'c', 'a']);
    assert.deepEqual(WlColumns.moveTo(['a', 'b', 'c'], 'c', -4), ['c', 'a', 'b']);
    assert.deepEqual(WlColumns.moveTo(['a', 'b', 'c'], 'b', NaN), ['b', 'a', 'c']);
    assert.deepEqual(WlColumns.moveTo(['a', 'b', 'c'], 'b', undefined), ['b', 'a', 'c']);
});

test('moveTo is a no-op for a key the layout does not hold, and never mutates its input', () => {
    const src = ['a', 'b', 'c'];
    assert.deepEqual(WlColumns.moveTo(src, 'zz', 0), ['a', 'b', 'c']);
    assert.deepEqual(WlColumns.moveTo(src, 'c', 0), ['c', 'a', 'b']);
    assert.deepEqual(src, ['a', 'b', 'c'], 'the caller keeps its own copy until it assigns');
    assert.deepEqual(WlColumns.moveTo(null, 'a', 0), []);
});

test('isDefault recognises the shipped set and nothing else', () => {
    assert.equal(WlColumns.isDefault(WlColumns.DEFAULTS.slice()), true);
    assert.equal(WlColumns.isDefault(WlColumns.DEFAULTS.slice().reverse()), false);
    assert.equal(WlColumns.isDefault(['price']), false);
    assert.equal(WlColumns.isDefault([]), false);
});

test('the default set is the columns the watchlist shipped with, plus the later additions', () => {
    // Guards the promise that turning on multi-list changed nobody's existing table. Two later
    // additions: the note, which joins at the end because it belongs to the row, and each price
    // level's distance + probability, which sit immediately after the level they read. Both
    // reached the layouts saved before them through a one-shot backfill in the repository.
    assert.deepEqual(WlColumns.DEFAULTS, [
        'price', 'todayPct', 'pct2d', 'pct3d', 'pct5d', 'pct30d',
        'pctFromHigh', 'pctFromLow',
        'vol5d', 'vol10d', 'vol30d', 'vol1y',
        'trailingPe', 'position', 'gain',
        'thrHigh', 'thrMove',
        'thrAbove', 'thrAbovePct', 'thrAboveProb',
        'thrBelow', 'thrBelowPct', 'thrBelowProb',
        'note'
    ]);
});

test("a level's readings sit directly after it, so the three read as one block", () => {
    const i = (k) => WlColumns.DEFAULTS.indexOf(k);
    assert.equal(i('thrAbovePct'), i('thrAbove') + 1);
    assert.equal(i('thrAboveProb'), i('thrAbove') + 2);
    assert.equal(i('thrBelowPct'), i('thrBelow') + 1);
    assert.equal(i('thrBelowProb'), i('thrBelow') + 2);
});
