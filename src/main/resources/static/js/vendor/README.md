# Vendored front-end libraries

These were loaded from jsdelivr/unpkg until 2026-08-08. A CDN blip left `Chart`
undefined, which made every chart panel render silently blank (no error shown) —
and because each loader latches `xLoaded = true` before awaiting, the tab stayed
blank until a full page reload. This is a loopback-only single-user tool, so the
usual CDN argument (shared browser cache across sites) buys nothing here.

**These files never update on their own.** They are committed here and baked into
the jar at `mvn package`; they change only when someone runs the update script.
Four of the five were already pinned to exact versions on the CDN, so that is not
a change in practice — only the date-fns adapter used to float, which meant it
could change under us between two page loads with nothing in the repo recording
which version was running. It is now pinned like the rest.

## Versions

`scripts/vendor-manifest.txt` is the single source of truth: one line per file,
`<filename> <upstream-url>`, where the URL *is* the version pin. It is not
duplicated here — two lists would drift.

## Updating

```bash
scripts/update-vendor.sh --check   # report drift against the manifest, write nothing
scripts/update-vendor.sh           # re-download all, install only if every file validates
scripts/update-vendor.sh --only chart.umd.js
```

To upgrade a library, edit the version in its manifest URL and run the script.
Downloads are staged and installed all-or-nothing (a half-updated directory can
mix incompatible versions — e.g. a new zoom plugin against the old Chart.js —
and that surfaces as a blank panel in the browser, far from the cause). Anything
that 404s, arrives suspiciously small, looks like an HTML error page, or fails
`node --check` is rejected before it can overwrite a working library.

After updating: `mvn package -DskipTests`, restart, hard-refresh, and click
through the chart tabs — a library upgrade can break rendering in ways no Java
test covers.

## Load order

Fixed in `dashboard.html`, not in the manifest: `chart.umd.js` must precede the
adapter and the zoom plugin, and `hammer.min.js` must precede the zoom plugin.

Keep the files pristine (no hand-edits) so a re-download is always a clean swap.
