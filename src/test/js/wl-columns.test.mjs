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
const REGISTRY = WlColumns.DEFAULTS.concat(['f:fcfMarginTtm', 'f:roic', 'beta', 'gainPct']);

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
    assert.deepEqual(WlColumns.addFirst(['price', 'todayPct'], 'f:roic'),
        ['f:roic', 'price', 'todayPct']);
});

test('addFirst moves a column already present rather than duplicating it', () => {
    assert.deepEqual(WlColumns.addFirst(['price', 'f:roic', 'todayPct'], 'f:roic'),
        ['f:roic', 'price', 'todayPct']);
});

test('a pinned key cannot be added to a layout', () => {
    assert.deepEqual(WlColumns.addFirst(['price'], 'sym'), ['price']);
    assert.deepEqual(WlColumns.addFirst(['price'], 'actions'), ['price']);
});

test('remove drops the key and leaves the rest in order', () => {
    assert.deepEqual(WlColumns.remove(['a', 'b', 'c'], 'b'), ['a', 'c']);
    assert.deepEqual(WlColumns.remove(['a', 'b'], 'zz'), ['a', 'b']);
});

test('isDefault recognises the shipped set and nothing else', () => {
    assert.equal(WlColumns.isDefault(WlColumns.DEFAULTS.slice()), true);
    assert.equal(WlColumns.isDefault(WlColumns.DEFAULTS.slice().reverse()), false);
    assert.equal(WlColumns.isDefault(['price']), false);
    assert.equal(WlColumns.isDefault([]), false);
});

test('the default set is exactly the columns the watchlist shipped with', () => {
    // Guards the promise that turning on multi-list changed nobody's existing table.
    assert.deepEqual(WlColumns.DEFAULTS, [
        'price', 'todayPct', 'pct2d', 'pct3d', 'pct5d', 'pct30d',
        'pctFromHigh', 'pctFromLow',
        'vol5d', 'vol10d', 'vol30d', 'vol1y',
        'trailingPe', 'position', 'gain',
        'thrHigh', 'thrMove', 'thrAbove', 'thrBelow'
    ]);
});
