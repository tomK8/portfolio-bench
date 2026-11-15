/*
 * The Watchlist add box's gilt picker, browser side.
 *
 * Run: node --test src/test/js/wl-gilt-hints.test.mjs
 *
 * The id a gilt is stored and alerted under is the one name for it that nobody outside this app
 * uses. A holder who arrives from the LSE's quote page for T36N — 4⅞% Treasury Gilt 31/07/36,
 * ISIN GB00BWBR1N39 — is looking for an entry called "GILT 4.875% 2036": it was in the list all
 * along, and searching for the code they had in hand matched nothing. So each option's label
 * carries the identifiers the price scrape publishes beside the price, because the browser
 * matches typed text against an option's label as well as its value.
 *
 * A separator left behind by a missing field is the failure this guards: "31 Jul 2036 · · " reads
 * as a field that failed to load rather than one the source never published, and it also matches
 * a stray "·" typed into the box.
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
function loadLabel() {
    const m = src.match(/function giltHintLabel\(g\)\s*\{[\s\S]*?\n\s{8}\}/);
    assert.ok(m, 'giltHintLabel is the one place the picker label is composed');
    return new Function(m[0] + '; return giltHintLabel;')();
}

test('a label carries maturity, ISIN and SEDOL — the identifiers a holder arrives with', () => {
    const giltHintLabel = loadLabel();
    assert.equal(
        giltHintLabel({ symbol: 'GILT 4.875% 2036', maturity: '31 Jul 2036', isin: 'GB00BWBR1N39', sedol: 'BWBR1N3' }),
        '31 Jul 2036 · GB00BWBR1N39 · BWBR1N3');
});

test('a missing identifier is absent, not an empty slot between separators', () => {
    const giltHintLabel = loadLabel();
    assert.equal(giltHintLabel({ maturity: '31 Jul 2036', isin: null, sedol: 'BWBR1N3' }),
        '31 Jul 2036 · BWBR1N3');
    assert.equal(giltHintLabel({ maturity: null, isin: 'GB00BWBR1N39', sedol: null }),
        'GB00BWBR1N39');
    assert.equal(giltHintLabel({ symbol: 'GILT 4.25% 2036' }), '',
        'a gilt the scrape never identified gets an empty label, never a row of separators');
});

test('the option takes its value from the symbol and its label from giltHintLabel', () => {
    // The value is what Add posts, so it has to stay the id. The label is the whole point of the
    // change: without it the list is 70 names spelled by coupon and year and nothing else.
    const m = src.match(/function loadGiltHints\(\)\s*\{[\s\S]*?\n\s{8}\}/);
    assert.ok(m, 'loadGiltHints builds the datalist');
    assert.match(m[0], /value="'\s*\+\s*escapeHtml\(g\.symbol\)/,
        'the posted value must be the symbol itself');
    assert.match(m[0], /label="'[\s\S]*?escapeHtml\(giltHintLabel\(g\)\)/,
        'the label must go through giltHintLabel, escaped like every other interpolation');
});
