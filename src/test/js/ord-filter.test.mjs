/*
 * Unit tests for the closed-orders filter.
 * Run: node --test src/test/js/ord-filter.test.mjs   (needs only node ≥ 18 — no deps)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const OrdFilter = require(path.resolve(here, '../../main/resources/static/js/ord-filter.js'));

/** A week of the real shape: one security traded at two brokers, plus unrelated rows. */
const ROWS = [
    {security: 'NVDA', side: 'Sell', state: 'Filled'},
    {security: 'NVDA', side: 'Sell', state: 'Cancelled'},
    {security: 'NVDA', side: 'Buy', state: 'No longer listed'},
    {security: 'AMD', side: 'Buy', state: 'Filled'},
    {security: 'LSEG', side: 'Buy', state: 'Expired'}
];

function filter(over) {
    return Object.assign(OrdFilter.blank(), over);
}

test('the empty filter constrains nothing', () => {
    assert.equal(OrdFilter.active(OrdFilter.blank()), false);
    assert.equal(OrdFilter.active(null), false);
    ROWS.forEach(r => assert.equal(OrdFilter.matches(r, OrdFilter.blank()), true));
    assert.deepEqual(OrdFilter.count(ROWS, OrdFilter.blank()), {shown: 5, total: 5});
});

test('one security, every order placed on it whatever became of it', () => {
    const f = filter({security: 'NVDA'});
    assert.equal(OrdFilter.active(f), true);
    assert.deepEqual(OrdFilter.count(ROWS, f), {shown: 3, total: 5});
    assert.equal(OrdFilter.matches({security: 'AMD', side: 'Buy', state: 'Filled'}, f), false);
});

test('the three fields compose — a security, a side and an outcome narrow together', () => {
    assert.deepEqual(OrdFilter.count(ROWS, filter({security: 'NVDA', side: 'Sell'})),
        {shown: 2, total: 5});
    assert.deepEqual(OrdFilter.count(ROWS, filter({security: 'NVDA', side: 'Sell', state: 'Filled'})),
        {shown: 1, total: 5});
    // Only the fills, across every security — "my transactions", as against my orders.
    assert.deepEqual(OrdFilter.count(ROWS, filter({state: 'Filled'})), {shown: 2, total: 5});
});

test('a filter matching nothing shows nothing, and says so rather than falling back to all', () => {
    assert.deepEqual(OrdFilter.count(ROWS, filter({security: 'NVDA', state: 'Expired'})),
        {shown: 0, total: 5});
    assert.equal(OrdFilter.label(ROWS, filter({security: 'NVDA', state: 'Expired'})), '(0 of 5)');
});

test('side is matched case-blind — a broker shouting SELL is not a second side of the market', () => {
    const rows = [{security: 'NVDA', side: 'SELL', state: 'Filled'},
                  {security: 'AMD', side: 'Sell', state: 'Filled'}];
    assert.deepEqual(OrdFilter.count(rows, filter({side: 'Sell'})), {shown: 2, total: 2});
    assert.deepEqual(OrdFilter.options(rows, OrdFilter.blank()).side, ['SELL']);
});

test('a security is matched verbatim — case is part of the identity, not house style', () => {
    const rows = [{security: 'NVDA', side: 'Buy', state: 'Filled'}];
    assert.equal(OrdFilter.matches(rows[0], filter({security: 'nvda'})), false);
});

test('surrounding space in a cell or a filter is noise', () => {
    assert.equal(OrdFilter.matches({security: ' NVDA ', side: 'Buy', state: 'Filled'},
        filter({security: 'NVDA'})), true);
    assert.equal(OrdFilter.active(filter({security: '   '})), false);
});

test('the dropdowns offer exactly what the rows hold, alphabetically', () => {
    const opts = OrdFilter.options(ROWS, OrdFilter.blank());
    assert.deepEqual(opts.security, ['AMD', 'LSEG', 'NVDA']);
    assert.deepEqual(opts.side, ['Buy', 'Sell']);
    assert.deepEqual(opts.state, ['Cancelled', 'Expired', 'Filled', 'No longer listed']);
});

test('an absent field is never offered as a choice, but its row still counts in the total', () => {
    const rows = [{security: 'NVDA', side: '—', state: 'Filled'},
                  {security: '—', side: 'Buy', state: 'Filled'},
                  {security: 'AMD', side: '', state: 'Filled'}];
    const opts = OrdFilter.options(rows, OrdFilter.blank());
    assert.deepEqual(opts.security, ['AMD', 'NVDA']);
    assert.deepEqual(opts.side, ['Buy']);
    assert.deepEqual(OrdFilter.count(rows, OrdFilter.blank()), {shown: 3, total: 3});
    // ...and it is not swept up by a filter on that field either.
    assert.deepEqual(OrdFilter.count(rows, filter({side: 'Buy'})), {shown: 1, total: 3});
});

test('a selection no row carries any more is kept, so the view cannot widen itself back out', () => {
    // The real case: the last "No longer listed" row is settled while filtered to that outcome.
    const settled = [{security: 'NVDA', side: 'Sell', state: 'Filled'}];
    const f = filter({state: 'No longer listed'});
    assert.deepEqual(OrdFilter.options(settled, f).state, ['Filled', 'No longer listed']);
    assert.deepEqual(OrdFilter.count(settled, f), {shown: 0, total: 1});
});

test('the summary count reads as a plain total until something is filtered', () => {
    assert.equal(OrdFilter.label(ROWS, OrdFilter.blank()), '(5)');
    assert.equal(OrdFilter.label(ROWS, filter({security: 'NVDA'})), '(3 of 5)');
    assert.equal(OrdFilter.label([], OrdFilter.blank()), '(0)');
});
