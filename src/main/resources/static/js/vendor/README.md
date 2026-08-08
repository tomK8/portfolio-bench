# Vendored front-end libraries

These were loaded from jsdelivr/unpkg until 2026-08-08. A CDN blip left `Chart`
undefined, which made every chart panel render silently blank (no error shown) —
and because each loader latches `xLoaded = true` before awaiting, the tab stayed
blank until a full page reload. This is a loopback-only single-user tool, so the
usual CDN argument (shared browser cache across sites) buys nothing here.

Re-download with the exact URLs below to upgrade; keep the files pristine
(no hand-edits) so a re-download is always a clean swap.

| File | Version | Source |
|------|---------|--------|
| `htmx.min.js` | 1.9.12 | `https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js` |
| `chart.umd.js` | 4.4.0 | `https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.js` |
| `chartjs-adapter-date-fns.bundle.min.js` | 3.0.0 | `https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js` |
| `hammer.min.js` | 2.0.8 | `https://cdn.jsdelivr.net/npm/hammerjs@2.0.8/hammer.min.js` |
| `chartjs-plugin-zoom.min.js` | 2.0.1 | `https://cdn.jsdelivr.net/npm/chartjs-plugin-zoom@2.0.1/dist/chartjs-plugin-zoom.min.js` |

The date-fns adapter URL was previously **unpinned** (floating latest); it is now
pinned to 3.0.0, the version that URL resolved to.

Load order matters: `hammer.min.js` must precede `chartjs-plugin-zoom.min.js`
(the zoom plugin's pan/pinch needs Hammer), and `chart.umd.js` must precede both
the adapter and the zoom plugin.
