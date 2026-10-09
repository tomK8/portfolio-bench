/*
 * Unit tests for the P/E-history driver sentence.
 * Run: node --test src/test/js/pe-drivers.test.mjs   (needs only node ≥ 18 — no deps)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const PeDrivers = require(path.resolve(here, '../../main/resources/static/js/pe-drivers.js'));

/** The window that produced the wrong sentence: GOOG 2019-02-05 → 2026-10-09. */
const GOOG = { priceMult: '6.065445', epsMult: '9.116300', peMult: '0.665356' };

/** Its operating split, as the live panel computes it from the filed figures. */
const GOOG_SPLIT = {
    opEpsMult: '6.154161',
    mixMult: '1.481323',
    netToOperatingStart: '1.116697',
    netToOperatingEnd: '1.654221',
    opMultipleMult: '0.985584'
};

test('a 33% de-rating is never reported as a multiple that barely moved', () => {
    const s = PeDrivers.drivers(GOOG);
    assert.ok(s.includes('earnings growth'), s);
    assert.ok(!s.includes('barely moved'), s);
    assert.ok(s.includes('33% de-rating'), s);
});

test('the comparison is symmetric in logs: ×2 and ×0.5 are the same size move', () => {
    // Under the old |m - 1| rule these two were 1.0 against 0.5 and the first always "won".
    const up = PeDrivers.drivers({ epsMult: 2, peMult: 1 });
    const down = PeDrivers.drivers({ epsMult: 0.5, peMult: 1 });
    assert.ok(up.includes('earnings growth'), up);
    assert.ok(down.includes('earnings'), down);

    // Equal and opposite: neither term may be called the driver.
    const offsetting = PeDrivers.drivers({ epsMult: 2, peMult: 0.5 });
    assert.ok(!offsetting.startsWith('mostly'), offsetting);
    assert.ok(offsetting.includes('largely offset'), offsetting);
});

test('"barely moved" is said only when the factor actually barely moved', () => {
    const flat = PeDrivers.drivers({ epsMult: 3.0, peMult: 1.02 });
    assert.ok(flat.includes('the multiple barely moved'), flat);

    const notFlat = PeDrivers.drivers({ epsMult: 3.0, peMult: 1.4 });
    assert.ok(!notFlat.includes('barely'), notFlat);
    assert.ok(notFlat.includes('adding a further 40%'), notFlat);
});

test('the multiple can be the driver, and the earnings clause says what earnings did', () => {
    const s = PeDrivers.drivers({ epsMult: 1.02, peMult: 2.5 });
    assert.ok(s.includes('multiple expansion'), s);
    assert.ok(s.includes('earnings barely moved'), s);

    const falling = PeDrivers.drivers({ epsMult: 0.7, peMult: 3.0 });
    assert.ok(falling.includes('multiple expansion'), falling);
    assert.ok(falling.includes('30% fall in earnings'), falling);
});

test('both terms down, both up, and one of each all read differently', () => {
    assert.ok(PeDrivers.drivers({ epsMult: 1.5, peMult: 1.4 })
        .includes('roughly even mix'));
    assert.ok(PeDrivers.drivers({ epsMult: 0.7, peMult: 0.75 })
        .includes('fell in roughly equal measure'));
    assert.ok(PeDrivers.drivers({ epsMult: 0.7, peMult: 1.35 })
        .includes('re-rating'));
});

test('a term that is not a usable multiple yields no claim at all', () => {
    assert.equal(PeDrivers.drivers(null), null);
    assert.equal(PeDrivers.drivers({ epsMult: null, peMult: 1.2 }), null);
    assert.equal(PeDrivers.drivers({ epsMult: 0, peMult: 1.2 }), null);
    assert.equal(PeDrivers.drivers({ epsMult: -1.4, peMult: 1.2 }), null);
});

test('the operating split names both ratios and restates the multiple without the gains', () => {
    const s = PeDrivers.operating(GOOG, GOOG_SPLIT);
    assert.ok(s.includes('operating income per share ×6.15'), s);
    assert.ok(s.includes('×1.48'), s);
    assert.ok(s.includes('Net income was 1.12× operating income'), s);
    assert.ok(s.includes('1.65× over its latest'), s);
    assert.ok(s.includes('the multiple is ×0.99, not ×0.67'), s);
    // Pre-tax is disclosed wherever the operating multiple is quoted.
    assert.ok(s.includes('pre-tax'), s);
});

test('the mix term is never called non-operating income on its own', () => {
    // Tax sits between the operating line and the bottom line too, and the filings do not
    // separate the two into a per-share figure without a modelled rate.
    const s = PeDrivers.operating(GOOG, GOOG_SPLIT);
    assert.ok(s.includes('non-operating items and tax, which no filing separates'), s);
});

test('a filer whose ratio is below 1 reads correctly, tax being the larger term', () => {
    // AAPL: operating income exceeds net income, so "operating is 120% of net" would read as
    // an error. Net ÷ operating is 0.76 → 0.83 and reads as what it is.
    const s = PeDrivers.operating(
        { priceMult: 11.54, epsMult: 4.07, peMult: 2.84 },
        {
            opEpsMult: 3.72, mixMult: 1.095,
            netToOperatingStart: 0.758, netToOperatingEnd: 0.830,
            opMultipleMult: 3.11
        });
    assert.ok(s.includes('Net income was 0.76× operating income'), s);
    assert.ok(s.includes('0.83× over its latest'), s);
    assert.ok(!s.includes('%'), s);
});

test('a steady mix says so, and claims no restated multiple', () => {
    const s = PeDrivers.operating(
        { priceMult: 2.2, epsMult: 2.1, peMult: 1.05 },
        {
            opEpsMult: 2.08, mixMult: 1.01,
            netToOperatingStart: 0.80, netToOperatingEnd: 0.808,
            opMultipleMult: 1.06
        });
    assert.ok(s.includes('the decomposition above is the operating business'), s);
    assert.ok(!s.includes('not ×'), s);
});

test('no split, no sentence — the caller shows the refusal instead', () => {
    assert.equal(PeDrivers.operating(GOOG, null), null);
    assert.equal(PeDrivers.operating(null, GOOG_SPLIT), null);
    assert.equal(PeDrivers.operating(GOOG, { opEpsMult: null, mixMult: 1.4 }), null);
});

/** MSFT's FY2016 base: the quarters are pre-ASC-606 and the filer has restated the year. */
const MSFT = { priceMult: '9.220021', epsMult: '8.593301', peMult: '1.072931' };
const MSFT_RESTATED = {
    periodEnd: '2016-06-30',
    assembled: '2.0900',
    filedAnnual: '2.5600',
    epsMult: '7.015625',
    peMult: '1.314243'
};

test('a restated base window is disclosed, with the decomposition both ways', () => {
    const s = PeDrivers.restated('MSFT', MSFT, MSFT_RESTATED);
    assert.ok(s.includes('twelve months to 2016-06-30'), s);
    assert.ok(s.includes('$2.09'), s);
    assert.ok(s.includes('$2.56'), s);
    assert.ok(s.includes('EPS ×7.02 × multiple ×1.31'), s);
    // And it names what the cards above it say, so the two are never read as one claim.
    assert.ok(s.includes('against the ×1.07 above'), s);
});

test('nothing is claimed when the base window stands', () => {
    assert.equal(PeDrivers.restated('MSFT', MSFT, null), null);
    assert.equal(PeDrivers.restated('MSFT', null, MSFT_RESTATED), null);
    assert.equal(PeDrivers.restated('MSFT', MSFT, { ...MSFT_RESTATED, epsMult: '0' }), null);
});
