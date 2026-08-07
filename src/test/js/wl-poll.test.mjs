import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const WlPoll = require('../../main/resources/static/js/wl-poll.js');

// Regression guard for the runaway-poll bug: when the watchlist is the restored active tab, the
// on-load activatePanel() → setupWatchlist() → wlStartPolling() runs DURING the dashboard IIFE's
// top-level execution, before `var WL_POLL_MS = 30000` is assigned — so wlStartPolling reads it as
// `undefined`. setInterval(fn, undefined) is treated as 0 and the browser clamps it to a ~4ms timer,
// i.e. a poll firing hundreds of times a second that hammered the server and made inline edits
// revert. pollIntervalMs() must collapse that (and any other unusable value) to the 30s default.

test('undefined (the init-order/hoisting value) → 30s default, never a runaway period', () => {
    assert.equal(WlPoll.pollIntervalMs(undefined), 30000);
});

test('0 / NaN / null → 30s default (all coerce to the runaway ~4ms period otherwise)', () => {
    assert.equal(WlPoll.pollIntervalMs(0), 30000);
    assert.equal(WlPoll.pollIntervalMs(NaN), 30000);
    assert.equal(WlPoll.pollIntervalMs(null), 30000);
});

test('non-numeric junk → 30s default', () => {
    assert.equal(WlPoll.pollIntervalMs('abc'), 30000);
    assert.equal(WlPoll.pollIntervalMs({}), 30000);
});

test('implausibly small periods (< 1s) are treated as a mistake → 30s default', () => {
    assert.equal(WlPoll.pollIntervalMs(4), 30000);      // the exact ~4ms clamp value
    assert.equal(WlPoll.pollIntervalMs(30), 30000);     // a fat-fingered "30" (meant 30000)
    assert.equal(WlPoll.pollIntervalMs(999), 30000);
});

test('valid periods (≥ 1s) pass through unchanged', () => {
    assert.equal(WlPoll.pollIntervalMs(1000), 1000);
    assert.equal(WlPoll.pollIntervalMs(30000), 30000);  // the real configured cadence
    assert.equal(WlPoll.pollIntervalMs(60000), 60000);
    assert.equal(WlPoll.pollIntervalMs('30000'), 30000); // numeric string coerces
});
