/*
 * The popup's Gilt terms block — what a bond shows where an equity's fundamentals go.
 *
 * Run: node --test src/test/js/wl-gilt-terms.test.mjs
 *
 * It replaced "No fundamentals cached for GILT 4.875% 2036 yet — the background refresh may
 * still be running, or Yahoo has no data for this listing", which was wrong twice over: the
 * fundamentals refresh skips bonds deliberately, so nothing was running, and no amount of
 * waiting produces earnings for a gilt. A dead end dressed as a wait, the same shape as the
 * unpriced row that read "(fetching…)" for ever.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '../../main/resources/static/js/dashboard.js');
const src = fs.readFileSync(SRC, 'utf8');

function lift(name, arity) {
    const re = new RegExp('function ' + name + '\\(' + arity + '\\)\\s*\\{[\\s\\S]*?\\n\\s{8}\\}');
    const m = src.match(re);
    assert.ok(m, name + ' is lifted out of the shipped source, not a copy of it');
    return m[0];
}

function loadValue() {
    return new Function(lift('giltFactValue', 'key, g') + '; return giltFactValue;')();
}

test('each figure is formatted as the thing it is', () => {
    const v = loadValue();
    const g = { couponPct: 4.875, maturity: '31 Jul 2036', yearsToMaturity: 9.7,
                redemptionYieldPct: 5.34, runningYieldPct: 5.05,
                isin: 'GB00BWBR1N39', sedol: 'BWBR1N3' };
    assert.equal(v('couponPct', g), '4.875%');
    assert.equal(v('maturity', g), '31 Jul 2036');
    assert.equal(v('yearsToMaturity', g), '9.7 yrs');
    assert.equal(v('redemptionYieldPct', g), '5.34%');
    assert.equal(v('runningYieldPct', g), '5.05%');
    assert.equal(v('isin', g), 'GB00BWBR1N39');
});

test('a coupon keeps its own precision rather than being padded to a column width', () => {
    const v = loadValue();
    // The coupon is part of the gilt's name: 4% is 4%, and 0.125% needs all three places.
    assert.equal(v('couponPct', { couponPct: 4 }), '4%');
    assert.equal(v('couponPct', { couponPct: 0.125 }), '0.125%');
});

test('an absent figure is a dash, and never a zero', () => {
    const v = loadValue();
    // A gilt the feed has not published a yield for has not yielded nothing.
    ['couponPct', 'maturity', 'yearsToMaturity', 'redemptionYieldPct', 'runningYieldPct',
     'isin', 'sedol'].forEach(k => {
        assert.equal(v(k, {}), '—', k + ' missing');
        assert.equal(v(k, { [k]: null }), '—', k + ' null');
    });
    // Zero years is a real answer — a gilt maturing today — and must print as one.
    assert.equal(v('yearsToMaturity', { yearsToMaturity: 0 }), '0.0 yrs');
});

test('every gilt field declares its own explanation, like every other label in this popup', () => {
    const m = src.match(/var WL_GILT_FIELDS = \[[\s\S]*?\n\s{8}\];/);
    assert.ok(m, 'WL_GILT_FIELDS is the one declaration of the block');
    const fields = new Function(m[0] + '; return WL_GILT_FIELDS;')();
    assert.ok(fields.length >= 6, 'coupon, maturity, both yields and the identifiers');
    fields.forEach(f => {
        assert.equal(f.length, 3, 'key, label, tip: ' + f[0]);
        assert.ok(f[1] && f[1].length > 1, 'a label for ' + f[0]);
        assert.ok(f[2] && f[2].length > 40, 'a real explanation for ' + f[0]);
    });
    // The redemption yield is the figure a reader is most likely to misread as net of tax.
    const ytm = fields.find(f => f[0] === 'redemptionYieldPct');
    assert.match(ytm[2], /gross/i, 'it must say which of the feed\'s four tax columns this is');
});

test('a bond never reaches the Yahoo snapshot at all', () => {
    // Loading it only to report its absence is what produced the old message. The guard has to
    // come first, before anything reads wlFundamentals.
    const fn = src.match(/function renderWlFundamentals\(symbol, gilt\)\s*\{[\s\S]*?\n\s{8}\}/);
    assert.ok(fn, 'renderWlFundamentals takes the gilt block alongside the symbol');
    const guard = fn[0].indexOf('if (gilt)');
    const firstRead = fn[0].indexOf('wlFundamentals');
    assert.ok(guard > -1, 'it branches on the gilt block');
    assert.ok(guard < firstRead, 'and does so before reading the fundamentals cache');
});

test('the disclosure is renamed for a gilt, so the heading is not a promise either', () => {
    assert.match(src, /wl-fund-toggle'\)\.textContent\s*=\s*\n?\s*r\.gilt \? 'Gilt terms' : 'Fundamentals'/);
});
