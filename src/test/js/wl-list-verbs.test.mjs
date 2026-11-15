/*
 * A guard on reachability: every watchlist-list verb the server exposes must have a caller.
 *
 * Run: node --test src/test/js/wl-list-verbs.test.mjs
 *
 * What it cost (2026-11-15): rename, delete and move had all shipped on the server, and rename
 * and delete had working handlers in dashboard.js — but their buttons sat at the end of the row
 * of controls that adds a symbol, twelve controls along from the box you type a ticker into, and
 * `move` had no caller at all. From the tab strip, where the lists are, the only thing you could
 * do to a watchlist was make another one. The lists were effectively immutable for weeks, which
 * no test noticed because every layer was individually fine.
 *
 * So this asserts the two halves meet: each POST mapping under /watchlist/lists/ is fetched by
 * the dashboard, and each such URL the dashboard fetches is a mapping that exists. The first
 * direction catches a verb shipped with no way to reach it; the second catches a button wired to
 * an endpoint that was renamed or never written.
 *
 * It cannot see whether a control is *findable* — placement is a judgement no assertion makes.
 * What it can see is that the verb is wired at all, which is the half that was missing.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const js = fs.readFileSync(path.join(root, 'src/main/resources/static/js/dashboard.js'), 'utf8');
const controller = fs.readFileSync(
    path.join(root, 'src/main/java/com/portfolio/web/WatchlistController.java'), 'utf8');

const served = [...controller.matchAll(/@PostMapping\("(\/watchlist\/lists\/[a-z]+)"\)/g)]
    .map(m => m[1]).sort();
const called = [...js.matchAll(/'(\/watchlist\/lists\/[a-z]+)'/g)]
    .map(m => m[1]).sort();
const unique = a => [...new Set(a)];

test('the controller still serves the four list verbs', () => {
    assert.deepEqual(unique(served),
        ['/watchlist/lists/create', '/watchlist/lists/delete',
         '/watchlist/lists/move', '/watchlist/lists/rename', '/watchlist/lists/reorder'].sort(),
        'a verb added or removed here is a change to what the tab strip can do — update the menu too');
});

test('REGRESSION: every list verb the server exposes has a caller in the dashboard', () => {
    const unreachable = unique(served).filter(url => !called.includes(url));
    assert.deepEqual(unreachable, [],
        'these POST endpoints exist but nothing on the page posts to them, so the user cannot ' +
        'reach them at all:\n' + unreachable.join('\n'));
});

test('every list URL the dashboard posts to is a mapping that exists', () => {
    const missing = unique(called).filter(url => !served.includes(url));
    assert.deepEqual(missing, [],
        'the page posts to endpoints the controller does not serve:\n' + missing.join('\n'));
});
