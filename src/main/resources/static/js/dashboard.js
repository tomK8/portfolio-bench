    (function () {
        function parseVal(cell, type) {
            // An explicit data-sort-value wins over the rendered text. Set it on cells whose
            // text is not purely the value — the Live tab's Last column carries a PRE/AH session
            // badge beside the price, and sorting must see the price alone.
            var dv = cell.dataset ? cell.dataset.sortValue : null;
            if (dv != null && dv !== '') {
                if (type !== 'num') return dv;
                var dn = parseFloat(dv);
                return isFinite(dn) ? dn : null;
            }
            var t = cell.textContent.trim();
            if (type === 'num') {
                if (t === '—' || t === '') return null;
                // U+2212 (typographic minus, used by the display formatters) → ASCII '-';
                // parseFloat returns NaN on it, which made every negative sort as null.
                var s = t.replace(/−/g, '-').replace(/[£$€p%~\s]/g, '').replace(/,/g, '');
                var suffix = s.slice(-1);
                var n = parseFloat(s);
                if (!isFinite(n)) return null;
                if (suffix === 'T') return n * 1e12;
                if (suffix === 'B') return n * 1e9;
                if (suffix === 'M') return n * 1e6;
                return n;
            }
            return t.toLowerCase();
        }

        function attachSort(table) {
            var ths = Array.from(table.querySelectorAll('thead th[data-sort]'));
            var sortCol = -1, sortAsc = true;
            // A marker already present means a re-render restored the user's ordering (see
            // restoreOrdersSort). Seed the toggle state from it, or the next click on that same
            // column would restart at ascending instead of flipping to descending.
            ths.forEach(function (h, i) {
                if (h.dataset.sortDir) { sortCol = i; sortAsc = h.dataset.sortDir === 'asc'; }
            });
            ths.forEach(function (th, idx) {
                th.addEventListener('click', function () {
                    var type = th.dataset.sort;
                    sortAsc = sortCol === idx ? !sortAsc : true;
                    sortCol = idx;
                    ths.forEach(function (h) {
                        delete h.dataset.sortDir;
                    });
                    th.dataset.sortDir = sortAsc ? 'asc' : 'desc';
                    sortBody(table, idx, type, sortAsc);
                });
            });
        }

        // Ordering by one column, for a flat table or a grouped one. The Cash-ledger table gains
        // group header rows when its "Group by" dropdown is set, and sorting the tbody as one
        // flat list would strand every header away from the members it summarises. So a grouped
        // table sorts its groups by the header's own cell and each group's members within their
        // group — one column, one direction, applied at both levels. Blank header cells (the
        // columns that have no group-level value) parse as null and sort last, exactly as a
        // blank member cell does.
        function sortBody(table, idx, type, asc) {
            var tbody = table.querySelector('tbody');
            if (!tbody) return;

            function cmp(a, b) {
                var av = parseVal(a.cells[idx], type);
                var bv = parseVal(b.cells[idx], type);
                if (av === null && bv === null) return 0;
                if (av === null) return 1;
                if (bv === null) return -1;
                var c = type === 'num' ? av - bv : av < bv ? -1 : av > bv ? 1 : 0;
                return asc ? c : -c;
            }

            var grouped = groupBlocks(tbody);
            if (!grouped) {
                var rows = Array.from(tbody.querySelectorAll('tr'));
                rows.sort(cmp);
                rows.forEach(function (r) { tbody.appendChild(r); });
                return;
            }
            // Leading rows are re-appended, not left alone: they are already out of place (a
            // member above every header), and dropping them here is what let them sit there
            // through every later sort.
            grouped.leading.forEach(function (r) { tbody.appendChild(r); });
            grouped.blocks.sort(function (a, b) { return cmp(a.head, b.head); });
            grouped.blocks.forEach(function (blk) {
                blk.members.sort(cmp);
                tbody.appendChild(blk.head);
                blk.members.forEach(function (m) { tbody.appendChild(m); });
            });
        }

        // {blocks: [{head, members}], leading: [rows above the first header]} for a grouped tbody,
        // or null when there are no headers in it — which is what keeps every other sortable table
        // on the page behaving exactly as before. `leading` should always be empty (the Cash-ledger
        // fragment renders header-then-member, and the Live table lays itself out that way); it is
        // returned rather than silently discarded because discarding it made a stranded row
        // invisible to every subsequent sort, which is precisely how such a row stayed stranded.
        function groupBlocks(tbody) {
            var current = null, blocks = [], leading = [];
            Array.from(tbody.children).forEach(function (row) {
                if (row.classList.contains('group-header')) {
                    current = {head: row, members: []};
                    blocks.push(current);
                } else if (current) {
                    current.members.push(row);
                } else {
                    leading.push(row);
                }
            });
            return blocks.length ? {blocks: blocks, leading: leading} : null;
        }

        // ── JS tooltip ──────────────────────────────────────────────────────────
        // Replaces the CSS ::after approach so the popup can contain a real link
        // to the glossary page. Activated by [data-tip] elements; [data-anchor]
        // adds a "→ Full explanation" link pointing to /glossary#{anchor}.
        (function () {
            var box = document.createElement('div');
            box.className = 'js-tip-box';
            document.body.appendChild(box);

            var hideTimer = null;

            function show(el) {
                clearTimeout(hideTimer);
                var tip = el.dataset.tip;
                var anchor = el.dataset.anchor;
                box.innerHTML = '<p>' + tip + '</p>' +
                    (anchor ? '<a class="tip-more" href="/glossary#' + anchor +
                              '" target="_blank">→ Full explanation with examples</a>' : '');
                box.style.display = 'block';
                // Position below the element, clamped to viewport width.
                var r = el.getBoundingClientRect();
                var top = r.bottom + 8;
                var left = r.left;
                box.style.top = top + 'px';
                box.style.left = left + 'px';
                // Clamp right edge.
                var bw = box.offsetWidth;
                if (left + bw > window.innerWidth - 12) {
                    box.style.left = Math.max(8, window.innerWidth - bw - 12) + 'px';
                }
                // Flip above if it would go below the viewport.
                if (top + box.offsetHeight > window.innerHeight - 8) {
                    box.style.top = (r.top - box.offsetHeight - 6) + 'px';
                }
            }

            function hide() { box.style.display = 'none'; }

            document.addEventListener('mouseover', function (e) {
                var el = e.target.closest('[data-tip]');
                if (el) show(el);
            });
            document.addEventListener('mouseout', function (e) {
                var el = e.target.closest('[data-tip]');
                if (!el) return;
                // Short delay so the user can move into the box to click the link.
                hideTimer = setTimeout(function () {
                    if (!box.matches(':hover')) hide();
                }, 180);
            });
            box.addEventListener('mouseleave', hide);
        }());
        // ────────────────────────────────────────────────────────────────────────

        // ── Open-orders sort, remembered across re-renders ──────────────────────
        // The orders fragment is swapped wholesale — on every tab click and on both model
        // checkboxes (drift, earnings variance) — which throws away the <thead> the sort marker
        // lives on, so the rebuilt table came back in the server's default broker order. Ticking
        // or unticking a checkbox is a request to re-price the numbers, never to re-order the
        // rows. The state is held in JS for the page session precisely because the DOM it used to
        // live in is what the swap destroys.
        var ordersSort = null;

        document.body.addEventListener('click', function (e) {
            var th = e.target.closest && e.target.closest('#orders-table thead th[data-sort]');
            if (!th) return;   // bubbled past attachSort's own handler, so the marker is already set
            var ths = Array.from(th.closest('thead').querySelectorAll('th[data-sort]'));
            ordersSort = th.dataset.sortDir ? {idx: ths.indexOf(th), dir: th.dataset.sortDir} : null;
        });

        // Put the marker back on the rebuilt thead and re-sort — before attachSort reads it, so
        // the seeded toggle state and the rendered order agree.
        function restoreOrdersSort() {
            var table = document.getElementById('orders-table');
            if (!ordersSort || !table) return;    // nothing stored, or no open orders to sort
            var th = Array.from(table.querySelectorAll('thead th[data-sort]'))[ordersSort.idx];
            if (!th) return;
            th.dataset.sortDir = ordersSort.dir;
            reapplyActiveSort(table);
        }

        document.body.addEventListener('htmx:afterSettle', function () {
            restoreOrdersSort();
            ['#portfolio-result', '#portfolio-cash-result', '#orders-result'].forEach(function (sel) {
                document.querySelectorAll(sel + ' table').forEach(function (table) {
                    if (!table.dataset.sortAttached) {
                        table.dataset.sortAttached = '1';
                        attachSort(table);
                    }
                });
            });
            syncLedgerExpandAll();
        });

        // ── Open-orders annotations ─────────────────────────────────────────────
        // Delegated from document.body because the orders fragment is htmx-swapped on every tab
        // click and on both model checkboxes — anything bound inside it would be lost on the first
        // swap and stacked on every re-bind. Saved on blur rather than per keystroke: a note is a
        // sentence, not a filter.
        document.body.addEventListener('focusout', function (e) {
            var input = e.target;
            if (!input.classList || !input.classList.contains('order-note')) return;
            var key = input.dataset.orderKey;
            if (!key) return;
            var value = input.value.trim();
            // The value it was rendered with is the baseline, so tabbing through a row full of
            // notes writes nothing. Updated after a successful save so a second blur is also quiet.
            if (value === (input.dataset.saved != null ? input.dataset.saved
                                                       : (input.defaultValue || ''))) return;
            input.classList.remove('order-note-saved', 'order-note-failed');
            mpPost('/orders/note', {orderKey: key, note: value})
                .then(function (res) {
                    if (res.ok) {
                        input.dataset.saved = value;
                        // The Note column sorts on the cell's data-sort-value (the input's own
                        // text is invisible to parseVal), so a note just written has to update it
                        // or a later sort would order the table by what was on screen at render.
                        var cell = input.closest('td');
                        if (cell) cell.dataset.sortValue = value;
                        input.classList.add('order-note-saved');
                        setTimeout(function () { input.classList.remove('order-note-saved'); }, 1200);
                    } else {
                        input.classList.add('order-note-failed');
                        input.title = res.message || 'Not saved';
                    }
                })
                .catch(function (err) {
                    input.classList.add('order-note-failed');
                    input.title = String(err.message || err);
                });
        });

        // Enter commits without waiting for focus to move, which is what a one-line field implies.
        document.body.addEventListener('keydown', function (e) {
            if (e.key !== 'Enter' || !e.target.classList
                || !e.target.classList.contains('order-note')) return;
            e.preventDefault();
            e.target.blur();
        });

        // ── Intended rungs: editable price and quantity ─────────────────────────
        // Only the rungs the user recorded here are editable — a rung that came from a broker's CSV
        // prints as text and carries no input at all, so nothing below can reach one.
        //
        // A commit posts every editable cell in the block and the server redraws the whole thing:
        // the two fixed horizons, the distance to market, the price axis and its marker, the totals,
        // and on a switch the yardstick, the split and the shortfall. It is deliberately not per
        // keystroke — a commit is a full re-load (three broker exports re-parsed, the price feed
        // re-read), and the box being typed in is inside the fragment that gets replaced.
        //
        // Delegated from document.body, like the note handlers above: both blocks are htmx-swapped
        // on every tab click and on every mutation, so anything bound inside them would be lost on
        // the first swap and stacked on every re-bind.
        var pendingRungFocus = null;

        function commitRungEdits(input) {
            var block = input.closest('.ladders');
            if (!block) return;
            // Each block carries its own hidden commit button, which knows the endpoint, the target
            // and which inputs to include. A button rather than hx-* on the input itself: htmx posts
            // the triggering element's own value *and* everything hx-include names, and the server
            // reads three index-aligned lists — one duplicated entry and every rung after it takes
            // the figure belonging to the one before.
            var commit = block.querySelector('.rung-commit');
            if (!commit) return;
            // Where to put the caret back after the swap. Without this, tuning a ladder means
            // finding the cell again after every commit.
            pendingRungFocus = {key: input.dataset.rungKey, field: input.dataset.field};
            commit.click();
        }

        document.body.addEventListener('change', function (e) {
            var input = e.target.closest ? e.target.closest('.rung-edit') : null;
            if (input) commitRungEdits(input);
        });

        // Enter commits without waiting for focus to move, which is what a one-line numeric field
        // implies. blur() is what fires `change`, and only when the value actually moved — so
        // Enter on an untouched cell is as quiet as tabbing through one.
        document.body.addEventListener('keydown', function (e) {
            if (e.key !== 'Enter' || !e.target.classList
                || !e.target.classList.contains('rung-edit')) return;
            e.preventDefault();
            e.target.blur();
        });

        // Matched by walking the inputs rather than by an attribute selector, so the lookup cannot
        // break on whatever a key turns out to contain.
        document.body.addEventListener('htmx:afterSettle', function () {
            if (!pendingRungFocus) return;
            var want = pendingRungFocus;
            pendingRungFocus = null;
            if (!want.key) return;
            var boxes = document.querySelectorAll('.rung-edit');
            for (var i = 0; i < boxes.length; i++) {
                if (boxes[i].dataset.rungKey === want.key && boxes[i].dataset.field === want.field) {
                    boxes[i].focus();
                    boxes[i].select();
                    return;
                }
            }
        });

        // ── Cash-ledger grouping: expand / collapse ─────────────────────────────
        // Groups render collapsed from the server and open on click. Both handlers are delegated
        // from document.body because the whole table is htmx-swapped on every re-sync — anything
        // bound inside the fragment would need re-attaching after each swap, and would stack a
        // duplicate per swap if it were re-attached naively.
        function ledgerGroupHeaders() {
            return Array.from(document.querySelectorAll('#portfolio-cash-result tr.group-header'));
        }

        function toggleLedgerGroup(head, expand) {
            var id = head.dataset.groupHead;
            head.classList.toggle('expanded', expand);
            // Scoped to the header's own tbody and matched on the group id rather than on
            // position, because sorting moves rows: a "next sibling until the following header"
            // walk would break the moment a group is re-ordered.
            Array.from(head.parentNode.querySelectorAll('tr.group-member')).forEach(function (r) {
                if (r.dataset.groupOf === id) r.classList.toggle('collapsed', !expand);
            });
        }

        // The button doubles as Expand all / Collapse all, and hides itself when the table is
        // ungrouped — a control that does nothing is worse than no control.
        function syncLedgerExpandAll() {
            var btn = document.getElementById('ledger-expand-all');
            if (!btn) return;
            var heads = ledgerGroupHeaders();
            btn.hidden = heads.length === 0;
            var allOpen = heads.length > 0 && heads.every(function (h) {
                return h.classList.contains('expanded');
            });
            btn.textContent = allOpen ? 'Collapse all' : 'Expand all';
            btn.dataset.action = allOpen ? 'collapse' : 'expand';
        }

        document.body.addEventListener('click', function (e) {
            if (!e.target || !e.target.closest) return;
            if (e.target.id === 'ledger-expand-all') {
                var expand = e.target.dataset.action !== 'collapse';
                ledgerGroupHeaders().forEach(function (h) { toggleLedgerGroup(h, expand); });
                syncLedgerExpandAll();
                return;
            }
            var head = e.target.closest('#portfolio-cash-result tr.group-header');
            if (!head) return;
            toggleLedgerGroup(head, !head.classList.contains('expanded'));
            syncLedgerExpandAll();
        });

        // ── Holdings / Cash-ledger row click → the shared price popup ───────────
        // The same modal the Live, Watchlist and Open-orders tables open, so a holding can be
        // charted from the table it is read in rather than by retyping its symbol elsewhere.
        // Delegated from the body because both tables are htmx-swapped on every re-sync and on
        // every change of the Group-by dropdown.
        //
        // Matching on tr[data-symbol] is what keeps the rows that name no instrument out: the
        // cash row (which the fragments deliberately leave without one) and a group header
        // (which names no *single* symbol and keeps the expand/collapse handler above — that one
        // returns first for those rows, and a header carries no data-symbol, so the two can never
        // both fire on one click). Scoped to a `table` inside the two result panels so the inline
        // mapping editors, which carry a data-symbol of their own on a div, are untouched.
        document.body.addEventListener('click', function (e) {
            if (!e.target || !e.target.closest) return;
            if (!e.target.closest('#portfolio-result table, #portfolio-cash-result table')) return;
            if (e.target.closest('input, button, a, select, label')) return;
            var tr = e.target.closest('tr[data-symbol]');
            if (tr) openSymbolModal(tr.dataset.symbol);
        });

        // Open orders — a cheap local disk read, so re-fetch on each activation to reflect any
        // freshly exported order files (the sticky-head table gets sort attached via afterSettle).
        // Open orders is the one panel whose loader used to be able to run *before* htmx had
        // initialised — it is reachable as the restored tab, and the restore fired during script
        // execution rather than after DOM ready (see restoreActiveTab). The bare `if (window.htmx)`
        // turned that into a silent no-op: the panel came up active and blank, and only clicking
        // away to another tab and back — by which point htmx was up — filled it. The restore is
        // deferred now, and this keeps the guard honest rather than swallowing the case: every
        // other panel reports a failed load and offers a Retry, and this one does too.
        function loadOrders() {
            clearPanelError('panel-orders');
            if (!window.htmx) {
                panelLoadFailed('panel-orders',
                        new Error('htmx has not loaded yet'), loadOrders);
                return;
            }
            var req = htmx.ajax('GET', '/orders', {target: '#orders-result', swap: 'innerHTML'});
            if (req && req.catch) {
                req.catch(function (e) { panelLoadFailed('panel-orders', e, loadOrders); });
            }
        }

        // The orders table is htmx-swapped on every tab click, so both of its interactions are
        // delegated from the body — anything bound inside the fragment would die on the next swap.
        (function ordersInteractions() {
            // Row click → the shared price popup, the same one the Live and Watchlist tables open.
            // A row the server could not resolve to a ticker carries no data-symbol and stays inert:
            // an unmapped AJ Bell row's "symbol" is its whole description, and charting it would
            // fetch nothing and read as a broken feature rather than as the missing mapping the
            // panel already offers to add.
            document.body.addEventListener('click', function (e) {
                // The Ladders and Share-class switches blocks are included on the same terms: their
                // rungs and legs carry data-symbol only when the listing resolved to a ticker, so an
                // unmapped one stays inert here for the same reason it does in the table.
                if (!e.target.closest('#orders-table, #ladders-result, #switches-result')) return;
                if (e.target.closest('input, button, a, select, label')) return;  // the days box lives in a cell
                var el = e.target.closest('tr[data-symbol], .ladder-symbol[data-symbol]');
                if (el) openSymbolModal(el.dataset.symbol);
            });

            // The probe's symbol opens the same price popup the table rows do. Its result is
            // htmx-swapped on every re-run, so this is delegated too.
            document.body.addEventListener('click', function (e) {
                var el = e.target.closest ? e.target.closest('#probe-result [data-symbol]') : null;
                if (el) openSymbolModal(el.dataset.symbol);
            });

            // Editable horizon box → re-price P(N) on the server (see GET /orders/touch: the
            // first-passage maths and the vol blend stay in one place rather than being mirrored
            // here). The box is in trading sessions, the same unit as P(5d) — type 5 and the cell
            // matches. A new horizon re-blends the forecast rather than rescaling one σ by √N,
            // which is why the row hands over four RV windows and not a single number.
            document.body.addEventListener('input', function (e) {
                var input = e.target.closest ? e.target.closest('#orders-table .ord-days') : null;
                if (!input) return;
                var cell = input.closest('td');
                clearTimeout(cell._ordTimer);
                cell._ordTimer = setTimeout(function () { repriceTouch(cell, input); }, 250);
            });
        }());

        /**
         * Re-price one row's P(N) cell over the horizon in its sessions box. Everything the estimate
         * needs — current price, limit and the four realized-vol windows — was rendered onto the
         * cell, so this is a pure loopback calculation: no re-parse of the broker exports, no FX
         * call, no database read.
         *
         * A per-cell sequence number latches the response: typing "1", "1 2", "1 2 0" fires three
         * debounced requests and a slow early one landing last would otherwise repaint the cell for
         * a horizon the box no longer shows — the same guard the Attribution presets use.
         */
        function repriceTouch(cell, input) {
            var out = cell.querySelector('.ord-touch');
            if (!out) return;
            var range = cell.querySelector('.ord-range');
            var sessions = parseInt(input.value, 10);
            var seq = cell._ordSeq = (cell._ordSeq || 0) + 1;
            if (!isFinite(sessions) || sessions < 1) {  // box cleared — say nothing rather than guess
                out.textContent = '—';
                out.title = '';
                out.className = 'ord-touch';
                setRange(range, null, null);
                cell.dataset.sortValue = '';
                return;
            }
            out.classList.add('pending');
            var q = 'current=' + encodeURIComponent(cell.dataset.current) +
                '&limit=' + encodeURIComponent(cell.dataset.limit) +
                '&sessions=' + sessions;
            ['rv10', 'rv20', 'rv30', 'rv250'].forEach(function (k) {
                if (cell.dataset[k]) q += '&' + k + '=' + encodeURIComponent(cell.dataset[k]);
            });
            // The symbol only resolves the asset class (a pure string predicate server-side, no
            // lookup), which is what the drift term keys off when drift is switched on. Without it
            // a re-priced cell would silently fall back to the driftless branch while the rest of
            // the column stayed drifted.
            var sym = cell.parentElement && cell.parentElement.dataset.symbol;
            if (sym) q += '&symbol=' + encodeURIComponent(sym);
            fetch('/orders/touch?' + q)
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                })
                .then(function (t) {
                    if (seq !== cell._ordSeq) return;
                    out.classList.remove('pending');
                    if (t.probability == null) {
                        out.textContent = '—';
                        out.title = 'No probability for this horizon.';
                        setRange(range, null, null);
                        cell.dataset.sortValue = '';
                        return;
                    }
                    out.textContent = (t.probability * 100).toFixed(1) + '%';
                    cell.dataset.sortValue = t.probability;
                    out.classList.toggle('long-horizon', !!t.longHorizon);
                    out.title = touchTooltip(t);
                    // The printed RV10/RV30 bracket is priced at the same horizon as the headline,
                    // so it has to move with it — left behind, it reads as the range for a horizon
                    // the cell is no longer showing.
                    setRange(range, t.probabilityLow, t.probabilityHigh);
                })
                .catch(function () {
                    if (seq !== cell._ordSeq) return;
                    out.classList.remove('pending');
                    out.textContent = '—';
                    out.title = 'Could not re-price this horizon.';
                    setRange(range, null, null);
                    cell.dataset.sortValue = '';
                });
        }

        /** Repaint (or blank) a P(N) cell's RV10/RV30 bracket. */
        function setRange(el, low, high) {
            if (!el) return;
            if (low == null || high == null) {
                el.textContent = '';
                return;
            }
            el.textContent = (low * 100).toFixed(0) + '–' + (high * 100).toFixed(0) + '%';
        }

        /**
         * The hover text for a re-priced P(N) cell: the horizon, the forecast that priced it, and
         * the RV10/RV30 range around the headline.
         *
         * The range is the part worth hovering for. The headline is one point estimate from one vol
         * model; if its low end already clears whatever you are deciding against, the call survives
         * the vol input being wrong, and if only the high end does, it does not.
         */
        function touchTooltip(t) {
            var parts = [t.sessions + ' trading sessions'];
            if (t.probabilityLow != null && t.probabilityHigh != null) {
                parts.push('Range on RV10/RV30: ' + (t.probabilityLow * 100).toFixed(1) + '% – ' +
                    (t.probabilityHigh * 100).toFixed(1) + '%');
            }
            if (t.sigmaEff != null) {
                parts.push('σ_eff ' + (t.sigmaEff * 100).toFixed(2) + '%/day' +
                    (t.decay != null ? ', ' + (t.decay * 100).toFixed(0) + '% weight on current state' : '') +
                    (t.volSource ? ' (' + t.volSource.toLowerCase().replace(/_/g, ' ') + ')' : ''));
            }
            if (t.longHorizon) {
                parts.push('This horizon runs well past the order deadlines the column is built ' +
                    'for, where the driftless assumption stops being a convention and becomes the ' +
                    'dominant one. Directional, not a number to read to the decimal.');
            }
            return parts.join('. ');
        }

        // ── Instrument mappings ─────────────────────────────────────────────────────
        // A new instrument needs a name mapping (broker description → symbol) and/or a ticker
        // mapping (symbol → Yahoo ticker) before anything can price it. Both are offered inline
        // wherever the gap shows: the Open orders panel and both sync tabs' unpriced-symbol panels.
        // Those fragments are htmx-swapped, so binding per fragment would mean re-attaching after
        // every swap — one delegated listener on the body handles every .map-row instead.

        function mapVal(row, sel) {
            var el = row.querySelector(sel);
            return el ? el.value.trim() : '';
        }

        function mapStatus(row, text, cls) {
            var el = row.querySelector('.map-status');
            if (!el) return;
            el.textContent = text;
            el.className = 'map-status' + (cls ? ' ' + cls : '');
        }

        // The listing's own name and currency are the point of showing a probe: a ticker that
        // resolves to the wrong live instrument prices the position wrongly rather than not at all,
        // and reading back "British Land ... USD" is the only thing that catches it at entry time.
        function mapProbeText(p) {
            if (!p || !p.found) return (p && p.message) || 'Yahoo returned nothing for that ticker.';
            var bits = [];
            if (p.name) bits.push(p.name);
            if (p.currency) bits.push(p.currency);
            if (p.exchange) bits.push(p.exchange);
            if (p.lastClose != null) bits.push('last ' + p.lastClose + (p.lastDate ? ' on ' + p.lastDate : ''));
            return '\u2713 ' + (bits.length ? bits.join(' \u00b7 ') : 'found');
        }

        function mapPost(url, params) {
            var body = new URLSearchParams();
            Object.keys(params).forEach(function (k) {
                if (params[k] !== null && params[k] !== undefined && params[k] !== '') body.append(k, params[k]);
            });
            return fetch(url, {method: 'POST', body: body}).then(function (r) {
                if (!r.ok) {
                    return r.text().then(function (t) {
                        throw new Error((t || '').slice(0, 300) || ('HTTP ' + r.status));
                    });
                }
                return r.json();
            });
        }

        function mapRenderList(container, view) {
            var html = '';
            if (view.aliases && view.aliases.length) {
                html += '<h5>Name mappings (yours)</h5><table class="map-table"><tr>'
                    + '<th class="lbl">Match text</th><th class="lbl">Symbol</th>'
                    + '<th class="lbl">From</th><th></th></tr>';
                view.aliases.forEach(function (a) {
                    html += '<tr><td class="lbl"><code>' + escapeHtml(a.keyword) + '</code></td>'
                        + '<td class="lbl">' + escapeHtml(a.symbol) + '</td>'
                        + '<td class="lbl muted">' + escapeHtml(a.rawName || '—') + '</td>'
                        + '<td><button type="button" class="map-delete" data-keyword="'
                        + escapeHtml(a.keyword) + '">Remove</button></td></tr>';
                });
                html += '</table>';
            }
            html += '<h5>Ticker mappings (yours)</h5>';
            if (view.tickers && view.tickers.length) {
                html += '<table class="map-table"><tr><th class="lbl">Symbol</th>'
                    + '<th class="lbl">Yahoo ticker</th><th class="lbl">Currency</th>'
                    + '<th class="lbl">Checked</th><th></th></tr>';
                view.tickers.forEach(function (t) {
                    html += '<tr><td class="lbl">' + escapeHtml(t.symbol) + '</td>'
                        + '<td class="lbl"><code>' + escapeHtml(t.yahooTicker) + '</code></td>'
                        + '<td class="lbl">' + escapeHtml(t.currency || '—') + '</td>'
                        + '<td class="lbl muted">' + (t.verifiedAt ? escapeHtml(t.verifiedAt.slice(0, 10)) : 'not checked') + '</td>'
                        + '<td><button type="button" class="map-delete" data-symbol="'
                        + escapeHtml(t.symbol) + '">Remove</button></td></tr>';
                });
                html += '</table>';
            } else {
                html += '<p class="muted">None yet — mappings you add from a warning panel appear here.</p>';
            }
            if (view.builtIn && view.builtIn.length) {
                html += '<h5>Built in (' + view.builtIn.length + ')</h5>'
                    + '<p class="muted">Shipped with the app. A mapping you add for the same symbol '
                    + 'overrides it.</p><p class="map-builtin">';
                html += view.builtIn.map(function (b) {
                    return '<code>' + escapeHtml(b.symbol) + ' \u2192 ' + escapeHtml(b.yahooTicker) + '</code>';
                }).join(' ');
                html += '</p>';
            }
            container.innerHTML = html;
        }

        function mapLoadList(container) {
            container.textContent = 'Loading\u2026';
            fetch('/mappings')
                .then(function (r) { return r.json(); })
                .then(function (view) { mapRenderList(container, view); })
                .catch(function (err) {
                    container.textContent = 'Could not load the mapping list — ' + err.message;
                });
        }

        // Complete the ticker box from the symbol as it is typed (XWHS → XWHS.L on a sterling
        // order). Only while the box is still ours: the moment the user edits it by hand the
        // auto-fill stands down, so a hand-typed ticker is never overwritten.
        document.body.addEventListener('input', function (ev) {
            var el = ev.target;
            if (!el.classList) return;
            var row = el.closest ? el.closest('.map-row') : null;
            if (!row) return;

            if (el.classList.contains('map-ticker')) {
                el.dataset.auto = '';
                return;
            }
            if (!el.classList.contains('map-symbol')) return;
            var tickerBox = row.querySelector('.map-ticker');
            if (!tickerBox || (tickerBox.value && tickerBox.dataset.auto !== '1')) return;
            var sym = el.value.trim().toUpperCase();
            tickerBox.value = sym ? sym + (sym.indexOf('.') >= 0 ? '' : (row.dataset.suffix || '')) : '';
            tickerBox.dataset.auto = '1';
        });

        document.body.addEventListener('click', function (ev) {
            if (!ev.target.closest) return;

            var summary = ev.target.closest('.map-list-toggle');
            if (summary) {
                var details = summary.parentElement;
                // Fires before the open state flips, so !open means it is being opened now.
                if (details && !details.open) {
                    var body = details.querySelector('.map-list-body');
                    if (body) setTimeout(function () { mapLoadList(body); }, 0);
                }
                return;
            }

            var del = ev.target.closest('.map-delete');
            if (del) {
                var listBody = del.closest('.map-list-body');
                del.disabled = true;
                mapPost('/mappings/delete', {symbol: del.dataset.symbol || '', keyword: del.dataset.keyword || ''})
                    .then(function (view) { if (listBody) mapRenderList(listBody, view); })
                    .catch(function (err) {
                        del.disabled = false;
                        if (listBody) listBody.insertAdjacentHTML('afterbegin',
                            '<p class="map-status map-bad">' + escapeHtml(err.message) + '</p>');
                    });
                return;
            }

            var btn = ev.target.closest('.map-verify, .map-save');
            if (!btn) return;
            var row = btn.closest('.map-row');
            if (!row) return;

            if (btn.classList.contains('map-verify')) {
                var probeTicker = mapVal(row, '.map-ticker');
                if (!probeTicker) { mapStatus(row, 'Enter a Yahoo ticker first.', 'map-bad'); return; }
                mapStatus(row, 'Checking ' + probeTicker + '\u2026', '');
                mapPost('/mappings/verify', {ticker: probeTicker})
                    .then(function (p) { mapStatus(row, mapProbeText(p), p.found ? 'map-good' : 'map-bad'); })
                    .catch(function (err) { mapStatus(row, err.message, 'map-bad'); });
                return;
            }

            var params = {
                symbol: mapVal(row, '.map-symbol') || row.dataset.symbol || '',
                ticker: mapVal(row, '.map-ticker'),
                keyword: mapVal(row, '.map-keyword'),
                rawName: row.dataset.name || '',
                force: btn.dataset.force === '1' ? 'true' : 'false'
            };
            if (!params.symbol) {
                mapStatus(row, 'Enter the symbol this app should use for the instrument.', 'map-bad');
                return;
            }
            btn.disabled = true;
            mapStatus(row, 'Saving\u2026', '');
            mapPost('/mappings/save', params)
                .then(function (res) {
                    btn.disabled = false;
                    if (!res.ok) {
                        mapStatus(row, res.message, 'map-bad');
                        var force = row.querySelector('.map-force');
                        if (force) force.hidden = false;
                        return;
                    }
                    mapStatus(row, res.message, 'map-good');
                    row.querySelectorAll('input, button').forEach(function (el) { el.disabled = true; });
                    // Prices are backfilled on a background thread, so give it a moment before
                    // repainting — otherwise the row comes back mapped but still priceless.
                    if (row.dataset.reload === 'orders') setTimeout(loadOrders, 3500);
                })
                .catch(function (err) {
                    btn.disabled = false;
                    mapStatus(row, err.message, 'map-bad');
                });
        });

        var TAB_KEY = 'portfolio-bench.active-tab';
        var GROUP_KEY_PREFIX = 'portfolio-bench.last-sub.';
        // Which named watchlist the one watchlist panel is showing. Kept apart from TAB_KEY
        // because the panel id alone no longer identifies the view: every list tab points at
        // panel-watchlist and is told apart by data-list.
        var WL_LIST_KEY = 'portfolio-bench.watchlist-list';

        // Derived from the secondary tab strips below. Single source of truth so renames
        // don't drift: each strip already lists its panels via [data-panel].
        var PANEL_TO_GROUP = {};
        var GROUP_DEFAULT_PANEL = {};
        document.querySelectorAll('.tabs-secondary').forEach(function (strip) {
            var g = strip.getAttribute('data-group');
            var first = null;
            strip.querySelectorAll('.tab[data-panel]').forEach(function (b) {
                var p = b.getAttribute('data-panel');
                PANEL_TO_GROUP[p] = g;
                if (!first) first = p;
            });
            GROUP_DEFAULT_PANEL[g] = first;
        });

        /**
         * `listId` is only meaningful for the watchlist panel, whose tabs all share one
         * data-panel and differ by data-list. Null means "whichever list was last open".
         */
        function activatePanel(panelId, persist, listId) {
            var group = PANEL_TO_GROUP[panelId];
            if (!group) return;
            if (panelId === 'panel-watchlist') listId = resolveWlList(listId);

            document.querySelectorAll('#portfolio-groups .tab').forEach(function (b) {
                b.classList.toggle('active', b.getAttribute('data-group') === group);
            });
            document.querySelectorAll('.tabs-secondary').forEach(function (strip) {
                strip.classList.toggle('active', strip.getAttribute('data-group') === group);
            });
            document.querySelectorAll('.tabs-secondary .tab').forEach(function (b) {
                var on = b.getAttribute('data-panel') === panelId;
                // Several tabs may name the same panel; the one that owns a list must also match
                // it, or every watchlist tab would light up at once.
                if (on && b.hasAttribute('data-list')) on = b.getAttribute('data-list') === String(listId);
                b.classList.toggle('active', on);
            });
            document.querySelectorAll('.tab-panel').forEach(function (p) {
                p.classList.toggle('active', p.id === panelId);
            });

            if (panelId === 'panel-orders') loadOrders();
            if (panelId === 'panel-contributions') loadContributionsChart();
            if (panelId === 'panel-value') loadValueChart();
            if (panelId === 'panel-allocation') setupAllocationPanel();
            if (panelId === 'panel-returns') { loadReturnsChart(); setupBenchmark(); }
            if (panelId === 'panel-risk') loadRisk();
            if (panelId === 'panel-dividends') loadDividends();
            if (panelId === 'panel-position') setupPosition();
            if (panelId === 'panel-correlations') setupCorrelations();
            if (panelId === 'panel-watchlist') setupWatchlist(listId);
            if (panelId === 'panel-swing') setupSwing();
            if (panelId === 'panel-trend') setupTrend();
            if (panelId === 'panel-momentum') setupMomentum();
            if (panelId === 'panel-mosig') setupMomentumSignals();
            if (panelId === 'panel-range') setupRange();
            if (panelId === 'panel-earnings') setupEarnings();
            if (panelId === 'panel-manual') setupManualPositions();
            if (panelId === 'panel-transfers') setupTransfers();
            if (panelId === 'panel-period') setupPeriodDiff();
            if (panelId === 'panel-recon') loadReconciliation();
            if (panelId === 'panel-attribution') setupAttribution();
            if (panelId === 'panel-fundamentals') setupFundamentalsPanel();
            if (panelId === 'panel-whatif') setupWhatIf();
            if (panelId === 'panel-scenario') setupScenario();
            if (panelId === 'panel-trades') setupJournal();
            if (panelId === 'panel-live') setupLive();
            else teardownLive();
            if (persist) {
                try {
                    localStorage.setItem(TAB_KEY, panelId);
                    localStorage.setItem(GROUP_KEY_PREFIX + group, panelId);
                    if (listId != null) localStorage.setItem(WL_LIST_KEY, String(listId));
                } catch (e) {}
            }
        }

        /**
         * Settle on a watchlist to show: the one asked for, else the one last open, else the
         * first tab. Falling back rather than erroring is what makes a stale remembered list —
         * deleted in another window, or gone with a restored database — land somewhere sensible.
         */
        function resolveWlList(listId) {
            var tabs = document.querySelectorAll('.wl-tab[data-list]');
            if (!tabs.length) return null;
            var known = {};
            tabs.forEach(function (t) { known[t.getAttribute('data-list')] = true; });
            if (listId != null && known[String(listId)]) return String(listId);
            var saved = null;
            try { saved = localStorage.getItem(WL_LIST_KEY); } catch (e) {}
            if (saved && known[saved]) return saved;
            return tabs[0].getAttribute('data-list');
        }

        document.querySelectorAll('.tabs-secondary .tab[data-panel]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                activatePanel(btn.dataset.panel, true, btn.dataset.list);
            });
        });

        document.querySelectorAll('#portfolio-groups .tab').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var g = btn.dataset.group;
                var last = null;
                try { last = localStorage.getItem(GROUP_KEY_PREFIX + g); } catch (e) {}
                var target = (last && PANEL_TO_GROUP[last] === g) ? last : GROUP_DEFAULT_PANEL[g];
                activatePanel(target, true);
            });
        });

        var THEME_KEY = 'portfolio-bench.theme';
        document.getElementById('theme-toggle').addEventListener('click', function () {
            var html = document.documentElement;
            var isDark = html.getAttribute('data-theme') === 'dark';
            if (isDark) {
                html.removeAttribute('data-theme');
                try { localStorage.setItem(THEME_KEY, 'light'); } catch (e) {}
            } else {
                html.setAttribute('data-theme', 'dark');
                try { localStorage.setItem(THEME_KEY, 'dark'); } catch (e) {}
            }
        });

        // Restore the last-visited tab. Silent fall-through if the saved id no longer matches
        // an existing panel (e.g. after a tab rename) — page-default Holdings stays active.
        //
        // Deferred to DOM ready so a restored panel loads under exactly the conditions a click on
        // its tab does. This script is the last element in <body>, so it runs while readyState is
        // still "loading": htmx has parsed but has not yet run its own DOMContentLoaded init, and
        // firing a panel loader from there is a different environment from every other call into
        // activatePanel. htmx registers its listener from <head>, before this one, so by the time
        // this runs htmx is fully up. Open orders — whose loader is a single htmx.ajax — is the
        // panel that showed it, coming back blank on a reload and filling only on a tab round-trip.
        function restoreActiveTab() {
            try {
                var savedTab = localStorage.getItem(TAB_KEY);
                if (savedTab && document.getElementById(savedTab)) {
                    activatePanel(savedTab, false);
                }
            } catch (e) {}
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', restoreActiveTab);
        } else {
            restoreActiveTab();
        }

        // Shared failure path for the load-once panels.
        //
        // Every load*/setup* below latches `xLoaded = true` *before* awaiting, so the fetch
        // runs once per page. Without a catch that latch also survived failure: one transient
        // error (a dead fetch, or Chart undefined when the charting library failed to load)
        // left the panel blank with no message, and re-clicking the tab was a silent no-op
        // until a full reload. Each caller now releases its own latch here and reports why.
        //
        // Half-built panels are torn down before the retry: Chart.js refuses to bind a canvas
        // that still has a live chart on it, so a loader that failed part-way through would
        // otherwise throw "Canvas is already in use" on every subsequent attempt.
        function panelLoadFailed(panelId, err, retry) {
            var panel = document.getElementById(panelId);
            if (!panel) return;

            if (typeof Chart !== 'undefined' && Chart.getChart) {
                panel.querySelectorAll('canvas').forEach(function (c) {
                    var existing = Chart.getChart(c);
                    if (existing) existing.destroy();
                });
            }

            var box = panel.querySelector('.panel-load-error');
            if (!box) {
                box = document.createElement('div');
                box.className = 'panel-load-error';
                panel.insertBefore(box, panel.firstChild);
            }
            // A missing Chart means the vendored libraries didn't load — a different problem
            // from a failed endpoint, and one the raw exception text describes badly.
            var detail = (typeof Chart === 'undefined')
                    ? 'The charting library did not load — check that <code>/js/vendor/chart.umd.js</code> is being served.'
                    : 'Error: <code>' + String((err && err.message) || err) + '</code>';
            box.innerHTML = '<strong>Could not load this panel.</strong> ' + detail;

            if (typeof retry === 'function') {
                var btn = document.createElement('button');
                btn.type = 'button';
                btn.textContent = 'Retry';
                btn.addEventListener('click', function () {
                    box.remove();
                    retry();
                });
                box.appendChild(btn);
            }
            if (window.console && console.error) console.error('panel ' + panelId + ' failed to load', err);
        }

        // Idempotent listener bind. The load-once panels can now re-run after a failed
        // attempt (see panelLoadFailed), so a bare addEventListener inside one would stack a
        // duplicate handler on every retry — e.g. a doubled POST on the next Save click.
        // Same dataset guard bindSubviewToggle/bindReturnsModeToggle already use.
        function bindOnce(el, event, handler) {
            if (!el || el.dataset['bound' + event]) return;
            el.dataset['bound' + event] = '1';
            el.addEventListener(event, handler);
        }

        // Drops a previous failure box so a successful retry doesn't leave a stale error
        // sitting above freshly rendered content.
        function clearPanelError(panelId) {
            var panel = document.getElementById(panelId);
            var box = panel && panel.querySelector('.panel-load-error');
            if (box) box.remove();
        }

        // Generic intra-panel subview toggle. Each .subview-toggle button carries a
        // data-subview attribute pointing to the .subview div to show. The loader callback
        // is invoked when its subview first becomes visible — the called function is
        // expected to be idempotent (the existing load*/setup* functions already guard).
        function bindSubviewToggle(toggleEl, loaders) {
            if (!toggleEl || toggleEl.dataset.bound === '1') return;
            toggleEl.dataset.bound = '1';
            toggleEl.querySelectorAll('button[data-subview]').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    var subviewId = btn.getAttribute('data-subview');
                    toggleEl.querySelectorAll('button').forEach(function (b) {
                        b.classList.toggle('active', b === btn);
                    });
                    var container = toggleEl.parentElement;
                    container.querySelectorAll(':scope > .subview').forEach(function (v) {
                        v.classList.toggle('active', v.id === subviewId);
                    });
                    if (loaders && loaders[subviewId]) loaders[subviewId]();
                });
            });
        }

        var allocPanelInitialized = false;
        function setupAllocationPanel() {
            loadBreakdownSubview();
            if (allocPanelInitialized) return;
            allocPanelInitialized = true;
            bindSubviewToggle(document.getElementById('alloc-toggle'), {
                'alloc-view-breakdown': loadBreakdownSubview,
                'alloc-view-concentration': loadConcentration,
                'alloc-view-currency': loadCurrency
            });
        }

        // The Breakdown sub-view holds three independent fetches. They keep separate latches so a
        // failure in one still leaves the others retryable on their own — a shared latch would let
        // a single endpoint blip take the whole sub-view down with it.
        function loadBreakdownSubview() {
            loadStyles();
            loadSectors();
            loadAllocation();
        }

        var fundPanelInitialized = false;
        function setupFundamentalsPanel() {
            setupFundamentals();
            if (fundPanelInitialized) return;
            fundPanelInitialized = true;
            bindSubviewToggle(document.getElementById('fund-toggle'), {
                'fund-view-pe': setupFundamentals,
                'fund-view-holdings': setupSnapshot
            });
        }

        var whatIfInitialized = false;
        var whatIfChart = null;
        var WHATIF_ROWS = 7;

        function setupWhatIf() {
            if (whatIfInitialized) return;
            whatIfInitialized = true;
            var form = document.getElementById('whatif-form');
            for (var i = 0; i < WHATIF_ROWS; i++) {
                var label = document.createElement('div');
                label.className = 'row-label';
                label.textContent = (i + 1) + '.';
                var symInput = document.createElement('input');
                symInput.type = 'text';
                symInput.name = 'symbols';
                symInput.placeholder = i === 0 ? 'e.g. GOOG' : '';
                symInput.autocomplete = 'off';
                var weightInput = document.createElement('input');
                weightInput.type = 'number';
                weightInput.name = 'weights';
                weightInput.min = '0';
                weightInput.max = '100';
                weightInput.step = '0.01';
                weightInput.placeholder = i === 0 ? 'e.g. 50' : '';
                weightInput.addEventListener('input', refreshWeightSum);
                form.appendChild(symInput);
                form.appendChild(weightInput);
                form.appendChild(label);
            }
            document.getElementById('whatif-run').addEventListener('click', runWhatIf);
            document.getElementById('whatif-reset').addEventListener('click', resetWhatIf);
            refreshWeightSum();
        }

        function resetWhatIf() {
            document.querySelectorAll('#whatif-form input').forEach(function (el) {
                el.value = '';
            });
            var errBox = document.getElementById('whatif-error');
            errBox.style.display = 'none';
            errBox.textContent = '';
            var warn = document.getElementById('whatif-warnings');
            warn.style.display = 'none';
            warn.innerHTML = '';
            var summary = document.getElementById('whatif-summary');
            summary.style.display = 'none';
            summary.innerHTML = '';
            document.getElementById('whatif-status').textContent = '';
            if (whatIfChart) {
                whatIfChart.destroy();
                whatIfChart = null;
            }
            refreshWeightSum();
        }

        function renderWhatIfSummary(actualData, syntheticData, contribData) {
            var box = document.getElementById('whatif-summary');
            var actualFinal = actualData.length ? actualData[actualData.length - 1].y : null;
            var basketFinal = syntheticData.length ? syntheticData[syntheticData.length - 1].y : null;
            var contribFinal = contribData.length ? contribData[contribData.length - 1].y : null;
            if (actualFinal === null && basketFinal === null) {
                box.style.display = 'none';
                return;
            }
            function fmtMoney(v) {
                if (v === null || v === undefined || isNaN(v)) return '—';
                return '£' + v.toLocaleString('en-GB',
                        { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }
            function fmtDelta(v) {
                if (v === null || v === undefined || isNaN(v)) return '—';
                var sign = v >= 0 ? '+' : '−';
                return sign + '£' + Math.abs(v).toLocaleString('en-GB',
                        { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            }
            var diff = (actualFinal !== null && basketFinal !== null) ? actualFinal - basketFinal : null;
            var diffClass = diff === null ? '' : (diff >= 0 ? 'pos' : 'neg');
            var rows = [
                '<div class="item"><span class="label">Actual portfolio (today)</span>' +
                    '<span class="value">' + fmtMoney(actualFinal) + '</span></div>',
                '<div class="item"><span class="label">What-if basket (today)</span>' +
                    '<span class="value">' + fmtMoney(basketFinal) + '</span></div>',
                '<div class="item"><span class="label">Actual − basket</span>' +
                    '<span class="value ' + diffClass + '">' + fmtDelta(diff) + '</span></div>'
            ];
            if (contribFinal !== null) {
                rows.push('<div class="item"><span class="label">Cumulative contributions</span>' +
                    '<span class="value">' + fmtMoney(contribFinal) + '</span></div>');
            }
            box.innerHTML = rows.join('');
            box.style.display = 'flex';
        }

        function refreshWeightSum() {
            var inputs = document.querySelectorAll('#whatif-form input[name="weights"]');
            var sum = 0;
            inputs.forEach(function (el) {
                var v = parseFloat(el.value);
                if (!isNaN(v)) sum += v;
            });
            var span = document.getElementById('whatif-sum');
            var label = 'Total: ' + sum.toFixed(2) + '%';
            // Under-allocation is allowed: surface the remainder as cash so the user sees it.
            // Only over-allocation is invalid (would synthesize money that wasn't contributed).
            if (sum > 0 && sum < 99.95) {
                label += ' (' + (100 - sum).toFixed(2) + '% cash)';
            }
            span.textContent = label;
            span.classList.toggle('bad', sum > 100.05);
        }

        function runWhatIf() {
            var symInputs = document.querySelectorAll('#whatif-form input[name="symbols"]');
            var weightInputs = document.querySelectorAll('#whatif-form input[name="weights"]');
            var params = new URLSearchParams();
            for (var i = 0; i < symInputs.length; i++) {
                var sym = symInputs[i].value.trim();
                var w = weightInputs[i].value.trim();
                if (sym === '' && w === '') continue;
                params.append('symbols', sym);
                params.append('weights', w);
            }
            var errBox = document.getElementById('whatif-error');
            errBox.style.display = 'none';
            errBox.textContent = '';
            var status = document.getElementById('whatif-status');
            status.textContent = 'Working…';

            postWhatIf(params, false).then(function (synthetic) {
                // If the basket has unknown tickers, show the in-page modal asking whether
                // to backfill from Yahoo and re-run. The modal returns a promise that
                // resolves true on OK, false on Cancel — same shape as the old confirm().
                if (synthetic.missingPrices && synthetic.missingPrices.length) {
                    var names = synthetic.missingPrices.map(function (m) { return m.symbol; }).join(', ');
                    return askBackfill(names).then(function (ok) {
                        if (ok) {
                            status.textContent = 'Backfilling ' + synthetic.missingPrices.length + ' ticker(s) from Yahoo…';
                            return postWhatIf(params, true).then(overlayWhatIf);
                        }
                        return overlayWhatIf(synthetic);
                    });
                }
                return overlayWhatIf(synthetic);
            }).then(function () {
                status.textContent = '';
            }).catch(function (err) {
                status.textContent = '';
                errBox.textContent = err.message || String(err);
                errBox.style.display = 'block';
            });
        }

        function postWhatIf(params, backfill) {
            var url = backfill ? '/whatif?backfill=true' : '/whatif';
            return fetch(url, { method: 'POST', body: params }).then(function (r) {
                if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Request failed'); });
                return r.json();
            });
        }

        // Promise<boolean> — resolves true on OK, false on Cancel/backdrop. One handler
        // attached per click so we don't leak listeners across runs.
        function askBackfill(tickerList) {
            return new Promise(function (resolve) {
                var modal = document.getElementById('whatif-backfill-modal');
                document.getElementById('whatif-backfill-list').textContent = tickerList;
                var okBtn = document.getElementById('whatif-backfill-ok');
                var cancelBtn = document.getElementById('whatif-backfill-cancel');
                function close(value) {
                    modal.classList.remove('open');
                    okBtn.removeEventListener('click', onOk);
                    cancelBtn.removeEventListener('click', onCancel);
                    modal.removeEventListener('click', onBackdrop);
                    resolve(value);
                }
                function onOk() { close(true); }
                function onCancel() { close(false); }
                function onBackdrop(e) { if (e.target === modal) close(false); }
                okBtn.addEventListener('click', onOk);
                cancelBtn.addEventListener('click', onCancel);
                modal.addEventListener('click', onBackdrop);
                modal.classList.add('open');
            });
        }

        // Fetch the actual-portfolio + contributions overlays and render the combined chart.
        // Pulled out so the run flow can call it once after either the initial sim or the
        // post-backfill re-run, without duplicating the Chart.js setup.
        function overlayWhatIf(synthetic) {
            return Promise.all([
                fetch('/portfolio-value').then(function (r) { return r.json(); }),
                fetch('/contributions').then(function (r) { return r.json(); })
            ]).then(function (results) {
                renderWhatIfChart(synthetic, results[0], results[1]);
            });
        }

        function renderWhatIfChart(synthetic, actual, contribTimeline) {
            var warn = document.getElementById('whatif-warnings');
            if (synthetic.missingPrices && synthetic.missingPrices.length) {
                var items = synthetic.missingPrices.map(function (m) {
                    return '<li><code>' + m.symbol + '</code> — no rows in <code>price_history</code>; ' +
                        'its allocation stayed as GBP cash.</li>';
                }).join('');
                warn.innerHTML = '<h4>Missing price data</h4>' +
                    '<p>The simulator could not value these basket symbols. Add prices and re-run.</p>' +
                    '<ul>' + items + '</ul>';
                warn.style.display = 'block';
            } else {
                warn.style.display = 'none';
            }

            function toXY(points, key) {
                return points.map(function (p) { return { x: p.date, y: parseFloat(p[key]) }; });
            }

            var syntheticData = toXY(synthetic.points, 'valueGbp');
            var actualData = toXY(actual.points, 'valueGbp');
            var totalLine = (contribTimeline.lines || []).find(function (l) { return l.label === 'Total'; });
            var contribData = totalLine ? toXY(totalLine.points, 'cumulativeGbp') : [];

            renderWhatIfSummary(actualData, syntheticData, contribData);

            var ctx = document.getElementById('whatif-chart').getContext('2d');
            if (whatIfChart) whatIfChart.destroy();
            whatIfChart = new Chart(ctx, {
                type: 'line',
                data: {
                    datasets: [{
                        label: 'What-if basket (£)',
                        data: syntheticData,
                        borderColor: '#2ca02c',
                        backgroundColor: 'rgba(44, 160, 44, 0.12)',
                        fill: true,
                        pointRadius: 0,
                        borderWidth: 1.5,
                        tension: 0.1,
                        order: 3
                    }, {
                        label: 'Actual portfolio (£)',
                        data: actualData,
                        borderColor: '#1f77b4',
                        backgroundColor: 'rgba(31, 119, 180, 0.0)',
                        fill: false,
                        pointRadius: 0,
                        borderWidth: 1.5,
                        tension: 0.1,
                        order: 2
                    }, {
                        label: 'Cumulative contributions (£)',
                        data: contribData,
                        borderColor: '#d62728',
                        backgroundColor: '#d62728',
                        borderDash: [6, 4],
                        fill: false,
                        pointRadius: 0,
                        stepped: 'before',
                        tension: 0,
                        order: 1
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    interaction: { mode: 'nearest', axis: 'x', intersect: false },
                    scales: {
                        x: { type: 'time', time: { unit: 'year' } },
                        y: {
                            title: { display: true, text: 'Value (£)' },
                            ticks: {
                                callback: function (v) {
                                    return '£' + v.toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                }
                            }
                        }
                    },
                    plugins: {
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    return ctx.dataset.label + ': £' +
                                        ctx.parsed.y.toLocaleString('en-GB',
                                            { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                }
                            }
                        },
                        legend: { position: 'bottom' }
                    }
                }
            });
        }

        var contributionsChart = null;
        var contributionsLoaded = false;

        var valueChart = null;
        var valueLoaded = false;

        function loadValueChart() {
            if (valueLoaded) return;
            valueLoaded = true;
            clearPanelError('panel-value');
            Promise.all([
                fetch('/portfolio-value').then(function (r) { return r.json(); }),
                fetch('/contributions').then(function (r) { return r.json(); })
            ]).then(function (results) {
                var timeline = results[0];
                var contribTimeline = results[1];
                var warn = document.getElementById('value-warnings');
                if (timeline.missingPrices && timeline.missingPrices.length) {
                    var items = timeline.missingPrices.map(function (m) {
                        return '<li><code>' + m.symbol + '</code> — held ' + m.from +
                            ' to ' + m.to + '</li>';
                    }).join('');
                    warn.innerHTML = '<h4>Missing price data</h4>' +
                        '<p>The following symbols were held but have no rows in <code>price_history</code>. ' +
                        'They contribute zero to the chart across their held range. Add prices to fix.</p>' +
                        '<ul>' + items + '</ul>';
                    warn.style.display = 'block';
                } else {
                    warn.style.display = 'none';
                }
                var valueData = timeline.points.map(function (p) {
                    return { x: p.date, y: parseFloat(p.valueGbp) };
                });
                var costData = timeline.points
                        .filter(function (p) { return p.costBasisGbp != null; })
                        .map(function (p) {
                            return { x: p.date, y: parseFloat(p.costBasisGbp) };
                        });
                var totalLine = (contribTimeline.lines || []).find(function (l) { return l.label === 'Total'; });
                var contribData = totalLine ? totalLine.points.map(function (p) {
                    return { x: p.date, y: parseFloat(p.cumulativeGbp) };
                }) : [];
                var ctx = document.getElementById('value-chart').getContext('2d');
                valueChart = new Chart(ctx, {
                    type: 'line',
                    data: {
                        datasets: [{
                            label: 'Portfolio value (£)',
                            data: valueData,
                            borderColor: '#1f77b4',
                            backgroundColor: 'rgba(31, 119, 180, 0.12)',
                            fill: true,
                            pointRadius: 0,
                            borderWidth: 1.5,
                            tension: 0.1,
                            order: 3
                        }, {
                            label: 'Invested capital — cost basis (£)',
                            data: costData,
                            borderColor: '#2ca02c',
                            backgroundColor: '#2ca02c',
                            fill: false,
                            pointRadius: 0,
                            borderWidth: 1.5,
                            tension: 0.1,
                            order: 2
                        }, {
                            label: 'Cumulative contributions (£)',
                            data: contribData,
                            borderColor: '#d62728',
                            backgroundColor: '#d62728',
                            borderDash: [6, 4],
                            fill: false,
                            pointRadius: 0,
                            stepped: 'before',
                            tension: 0,
                            order: 1
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: {
                                title: { display: true, text: 'Portfolio value (£)' },
                                ticks: {
                                    callback: function (v) {
                                        return '£' + v.toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                    }
                                }
                            }
                        },
                        plugins: {
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return '£' + ctx.parsed.y.toLocaleString('en-GB',
                                            { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                    }
                                }
                            },
                            legend: { position: 'bottom' },
                            zoom: {
                                pan: { enabled: true, mode: 'x' },
                                zoom: {
                                    mode: 'x',
                                    drag: { enabled: true, backgroundColor: 'rgba(31, 119, 180, 0.12)' },
                                    pinch: { enabled: true }
                                }
                            }
                        }
                    }
                });
                document.getElementById('value-zoom-reset').onclick = function () {
                    if (valueChart) valueChart.resetZoom();
                };
            }).catch(function (e) {
                valueLoaded = false;
                panelLoadFailed('panel-value', e, loadValueChart);
            });
        }

        var returnsLoaded = false;
        var returnsGrowthChart = null;
        var returnsContribChart = null;
        var returnsDrawdownChart = null;
        var returnsAnnualChart = null;
        var returnsZoomInitial = null;   // { min, max } captured at first render — restore here on reset

        function loadReturnsChart() {
            if (returnsLoaded) return;
            returnsLoaded = true;
            clearPanelError('panel-returns');
            bindReturnsModeToggle();
            fetch('/returns').then(function (r) { return r.json(); }).then(function (data) {
                renderReturnsStats(data.summary);
                var growthData = data.growthPoints.map(function (p) {
                    return { x: p.date, y: parseFloat(p.growth) };
                });
                var contribData = data.contributionPoints.map(function (p) {
                    return { x: p.date, y: parseFloat(p.cumulativeGbp) };
                });
                var drawdownData = (data.drawdownPoints || []).map(function (p) {
                    return { x: p.date, y: parseFloat(p.drawdown) * 100 };
                });
                // Lock all three x-axes to the same range so they line up visually even when the
                // top chart's first point is later (post-warmup) than the bottom chart's.
                var minDate = growthData.length ? growthData[0].x : null;
                var maxDate = growthData.length ? growthData[growthData.length - 1].x : null;
                returnsZoomInitial = { min: minDate, max: maxDate };

                var growthCtx = document.getElementById('returns-chart').getContext('2d');
                returnsGrowthChart = new Chart(growthCtx, {
                    type: 'line',
                    data: {
                        datasets: [{
                            label: 'Growth of £1 (TWR)',
                            data: growthData,
                            borderColor: '#1f77b4',
                            backgroundColor: 'rgba(31, 119, 180, 0.12)',
                            fill: true,
                            pointRadius: 0,
                            borderWidth: 1.5,
                            tension: 0.1
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' }, min: minDate, max: maxDate,
                                 ticks: { display: false } },
                            y: {
                                title: { display: true, text: 'Growth of £1' },
                                ticks: {
                                    callback: function (v) { return '×' + v.toFixed(2); }
                                }
                            }
                        },
                        plugins: {
                            legend: { display: false },
                            zoom: returnsZoomConfig(),
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        var g = ctx.parsed.y;
                                        var ret = (g - 1) * 100;
                                        return '×' + g.toFixed(4) + '  (' +
                                            (ret >= 0 ? '+' : '') + ret.toFixed(2) + '%)';
                                    }
                                }
                            }
                        }
                    }
                });

                var contribCtx = document.getElementById('returns-contrib-chart').getContext('2d');
                returnsContribChart = new Chart(contribCtx, {
                    type: 'line',
                    data: {
                        datasets: [{
                            label: 'Cumulative contributions (£)',
                            data: contribData,
                            borderColor: '#d62728',
                            backgroundColor: 'rgba(214, 39, 40, 0.10)',
                            fill: true,
                            pointRadius: 0,
                            borderWidth: 1.5,
                            stepped: 'before'
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' }, min: minDate, max: maxDate },
                            y: {
                                title: { display: true, text: 'Contributions (£)' },
                                ticks: {
                                    callback: function (v) {
                                        return '£' + v.toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                    }
                                }
                            }
                        },
                        plugins: {
                            legend: { display: false },
                            zoom: returnsZoomConfig(),
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return '£' + ctx.parsed.y.toLocaleString('en-GB',
                                            { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                    }
                                }
                            }
                        }
                    }
                });

                renderAnnualReturnsChart(data.annualReturns || []);

                var ddCtx = document.getElementById('returns-drawdown-chart').getContext('2d');
                returnsDrawdownChart = new Chart(ddCtx, {
                    type: 'line',
                    data: {
                        datasets: [{
                            label: 'Drawdown (%)',
                            data: drawdownData,
                            borderColor: '#c0392b',
                            backgroundColor: 'rgba(192, 57, 43, 0.18)',
                            fill: true,
                            pointRadius: 0,
                            borderWidth: 1.2,
                            tension: 0
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' }, min: minDate, max: maxDate },
                            y: {
                                title: { display: true, text: 'Underwater (%)' },
                                // Drawdown is always <= 0; pinning max=0 keeps the "underwater" line
                                // hugging the zero axis, which is the conventional rendering.
                                max: 0,
                                ticks: { callback: pctTickFormat }
                            }
                        },
                        plugins: {
                            legend: { display: false },
                            zoom: returnsZoomConfig(),
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return ctx.parsed.y.toFixed(2) + '%';
                                    }
                                }
                            }
                        }
                    }
                });
            }).catch(function (e) {
                returnsLoaded = false;
                panelLoadFailed('panel-returns', e, loadReturnsChart);
            });
        }

        /**
         * Percent-axis tick formatter that picks decimal places from the *step* between
         * adjacent ticks. {@code toFixed(0)} alone collapses sub-integer ticks (e.g. -0.5,
         * -1.5) to duplicate labels ("-1%", "-2%") when the user zooms in tight — this
         * promotes decimals just enough to make every tick render distinctly.
         */
        function pctTickFormat(value, index, ticks) {
            var step = (ticks && ticks.length > 1)
                ? Math.abs(ticks[1].value - ticks[0].value) : 1;
            var dp = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
            return value.toFixed(dp) + '%';
        }

        /**
         * Drag-to-zoom + pan on the x-axis; the onComplete callbacks broadcast the chosen
         * window to the other two charts in the trio so they all stay aligned. Without the
         * sync the user would zoom growth and the contrib/drawdown panes would scale
         * independently — confusing for a stacked layout where they share dates.
         */
        function returnsZoomConfig() {
            return {
                pan: { enabled: true, mode: 'x', onPanComplete: syncReturnsX },
                zoom: {
                    mode: 'x',
                    drag: { enabled: true, backgroundColor: 'rgba(31, 119, 180, 0.12)' },
                    pinch: { enabled: true },
                    onZoomComplete: syncReturnsX
                }
            };
        }

        function syncReturnsX(ctx) {
            var src = ctx.chart;
            var xs = src.scales.x;
            applyReturnsX(xs.min, xs.max, src);
        }

        function applyReturnsX(min, max, except) {
            [returnsGrowthChart, returnsContribChart, returnsDrawdownChart].forEach(function (c) {
                if (!c || c === except) return;
                c.options.scales.x.min = min;
                c.options.scales.x.max = max;
                c.update('none');
            });
        }

        function renderAnnualReturnsChart(years) {
            renderAnnualReturnsTable(years);
            var labels = years.map(function (y) { return String(y.year); });
            var totalValues = years.map(function (y) { return parseFloat(y.returnPct) * 100; });
            var investedValues = years.map(function (y) {
                return y.investedReturnPct != null ? parseFloat(y.investedReturnPct) * 100 : null;
            });
            // Cumulative TWR: compound the year-by-year portfolio (with-cash) returns into a
            // growth-of-£1 line on a second axis. Partial years are still folded in so the
            // last point reflects current YTD.
            var cumGrowth = [];
            var running = 1.0;
            years.forEach(function (y) {
                running = running * (1 + parseFloat(y.returnPct));
                cumGrowth.push(running);
            });

            // Partial-period bars (inception year, current YTD) render lighter so they visually
            // recede from full calendar-year bars. Blue = whole portfolio (cash drag included);
            // amber = invested-only (cash drag stripped) — the gap reads as opportunity cost.
            var totalColors = years.map(function (y) {
                return y.partial ? 'rgba(31, 119, 180, 0.45)' : 'rgba(31, 119, 180, 0.85)';
            });
            var investedColors = years.map(function (y) {
                return y.partial ? 'rgba(255, 127, 14, 0.45)' : 'rgba(255, 127, 14, 0.85)';
            });

            var ctx = document.getElementById('returns-annual-chart').getContext('2d');
            if (returnsAnnualChart) returnsAnnualChart.destroy();
            returnsAnnualChart = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [
                        { label: 'Portfolio (with cash)',
                          data: totalValues, backgroundColor: totalColors, borderWidth: 0,
                          yAxisID: 'yPct', order: 2 },
                        { label: 'Invested only (no cash drag)',
                          data: investedValues, backgroundColor: investedColors, borderWidth: 0,
                          yAxisID: 'yPct', order: 2 },
                        { type: 'line', label: 'Cumulative TWR (×£1)',
                          data: cumGrowth,
                          borderColor: '#2ca02c',
                          backgroundColor: '#2ca02c',
                          fill: false,
                          pointRadius: 3,
                          pointBackgroundColor: '#2ca02c',
                          borderWidth: 2,
                          tension: 0.15,
                          yAxisID: 'yCum',
                          order: 1 }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        x: { grid: { display: false } },
                        yPct: {
                            type: 'linear', position: 'left',
                            title: { display: true, text: 'Annual return (%)' },
                            ticks: {
                                callback: function (v) {
                                    return (v >= 0 ? '+' : '') + v.toFixed(0) + '%';
                                }
                            }
                        },
                        yCum: {
                            type: 'linear', position: 'right',
                            title: { display: true, text: 'Cumulative ×£1 (TWR)' },
                            grid: { drawOnChartArea: false },
                            ticks: {
                                callback: function (v) { return '×' + v.toFixed(2); }
                            }
                        }
                    },
                    plugins: {
                        legend: { position: 'bottom', labels: { boxWidth: 12 } },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    var v = ctx.parsed.y;
                                    if (v == null) return ctx.dataset.label + ': —';
                                    if (ctx.dataset.label === 'Cumulative TWR (×£1)') {
                                        return ctx.dataset.label + ': ×' + v.toFixed(4)
                                            + '  (' + ((v - 1) * 100 >= 0 ? '+' : '')
                                            + ((v - 1) * 100).toFixed(2) + '%)';
                                    }
                                    return ctx.dataset.label + ': ' +
                                        (v >= 0 ? '+' : '') + v.toFixed(2) + '%';
                                },
                                afterBody: function (items) {
                                    if (!items.length) return '';
                                    var y = years[items[0].dataIndex];
                                    var t = parseFloat(y.returnPct) * 100;
                                    var i = y.investedReturnPct != null
                                        ? parseFloat(y.investedReturnPct) * 100 : null;
                                    var drag = i != null ? (i - t) : null;
                                    var lines = [y.fromDate + ' → ' + y.toDate +
                                        (y.partial ? '  (partial)' : '')];
                                    if (drag != null) {
                                        lines.push('Cash drag: ' +
                                            (drag >= 0 ? '+' : '') + drag.toFixed(2) + '%');
                                    }
                                    return lines;
                                }
                            }
                        }
                    }
                }
            });
        }

        function renderAnnualReturnsTable(years) {
            var tbody = document.querySelector('#returns-annual-table tbody');
            tbody.innerHTML = '';
            function fmtPctSigned(v) {
                if (v == null) return '—';
                var n = parseFloat(v) * 100;
                return (n >= 0 ? '+' : '') + n.toFixed(2) + '%';
            }
            function classFor(v) {
                if (v == null) return '';
                var n = parseFloat(v);
                return n > 0 ? 'pos' : n < 0 ? 'neg' : '';
            }
            years.forEach(function (y) {
                var portfolio = parseFloat(y.returnPct);
                var invested = y.investedReturnPct != null ? parseFloat(y.investedReturnPct) : null;
                var drag = invested != null ? (invested - portfolio) : null;
                var dragStr = drag != null
                    ? (drag >= 0 ? '+' : '') + (drag * 100).toFixed(2) + '%'
                    : '—';
                var tr = document.createElement('tr');
                if (y.partial) tr.className = 'partial';
                tr.innerHTML =
                    '<td class="txt">' + y.year + (y.partial ? '*' : '') + '</td>' +
                    '<td class="' + classFor(y.returnPct) + '">' + fmtPctSigned(y.returnPct) + '</td>' +
                    '<td class="' + classFor(y.investedReturnPct) + '">' + fmtPctSigned(y.investedReturnPct) + '</td>' +
                    '<td class="' + classFor(drag) + '">' + dragStr + '</td>';
                tbody.appendChild(tr);
            });
        }

        function resetReturnsZoom() {
            [returnsGrowthChart, returnsContribChart, returnsDrawdownChart].forEach(function (c) {
                if (!c) return;
                c.resetZoom();
                if (returnsZoomInitial) {
                    c.options.scales.x.min = returnsZoomInitial.min;
                    c.options.scales.x.max = returnsZoomInitial.max;
                    c.update('none');
                }
            });
        }

        // The TWR Summary is cached when /returns loads so the TWR/MWR toggle can swap stats
        // without re-fetching. MWR is fetched once lazily on first toggle.
        var cachedTwrSummary = null;
        var cachedMwrSummary = null;
        var returnsMode = 'twr';

        function renderReturnsStats(summary) {
            cachedTwrSummary = summary;
            if (returnsMode === 'twr') showTwr();
            else showMwr();
        }

        // 1w–6m are simple period returns, intrinsic to the TWR series (a sub-year IRR would
        // annualise to a misleading number). Like Max drawdown, they're kept from the TWR
        // summary in both TWR and MWR modes.
        function paintShortWindows(s) {
            setStat('returns-1w', s && s.trailing1w);
            setStat('returns-1m', s && s.trailing1m);
            setStat('returns-3m', s && s.trailing3m);
            setStat('returns-6m', s && s.trailing6m);
        }

        function showTwr() {
            var s = cachedTwrSummary;
            paintShortWindows(s);
            setStat('returns-1y', s && s.trailing1y);
            setStat('returns-3y', s && s.trailing3y);
            setStat('returns-5y', s && s.trailing5y);
            setStat('returns-since', s && s.sinceInception);
            setStat('returns-maxdd', s && s.maxDrawdown);
            var dateSub = document.getElementById('returns-maxdd-date');
            dateSub.textContent = (s && s.maxDrawdownDate) ? 'on ' + s.maxDrawdownDate : '';
            document.getElementById('returns-mode-twr').classList.add('active');
            document.getElementById('returns-mode-mwr').classList.remove('active');
        }

        function showMwr() {
            if (cachedMwrSummary === null) {
                fetch('/returns/mwr').then(function (r) { return r.json(); }).then(function (m) {
                    cachedMwrSummary = m || {};
                    if (returnsMode === 'mwr') paintMwr();
                });
                // Show "loading" state while waiting.
                setStat('returns-1y', null);
                setStat('returns-3y', null);
                setStat('returns-5y', null);
                setStat('returns-since', null);
            } else {
                paintMwr();
            }
            // Max drawdown / drawdown date and the 1w–6m period returns are intrinsic to the
            // TWR series — keep them from the TWR summary in MWR mode.
            var s = cachedTwrSummary;
            paintShortWindows(s);
            setStat('returns-maxdd', s && s.maxDrawdown);
            document.getElementById('returns-maxdd-date').textContent =
                    (s && s.maxDrawdownDate) ? 'on ' + s.maxDrawdownDate : '';
            document.getElementById('returns-mode-twr').classList.remove('active');
            document.getElementById('returns-mode-mwr').classList.add('active');
        }

        function paintMwr() {
            var m = cachedMwrSummary || {};
            setStat('returns-1y', m.trailing1y);
            setStat('returns-3y', m.trailing3y);
            setStat('returns-5y', m.trailing5y);
            setStat('returns-since', m.sinceInception);
        }

        function bindReturnsModeToggle() {
            var t = document.getElementById('returns-mode-twr');
            var m = document.getElementById('returns-mode-mwr');
            if (!t || t.dataset.bound) return;
            t.dataset.bound = '1';
            t.addEventListener('click', function () {
                returnsMode = 'twr';
                showTwr();
            });
            m.addEventListener('click', function () {
                returnsMode = 'mwr';
                showMwr();
            });
        }

        function setStat(elId, fractional) {
            var el = document.getElementById(elId);
            if (fractional === null || fractional === undefined) {
                el.textContent = '—';
                el.className = 'value na';
                return;
            }
            var pct = parseFloat(fractional) * 100;
            el.textContent = (pct >= 0 ? '+' : '') + pct.toFixed(2) + '%';
            el.className = 'value ' + (pct >= 0 ? 'pos' : 'neg');
        }

        function loadContributionsChart() {
            if (contributionsLoaded) return;
            contributionsLoaded = true;
            clearPanelError('panel-contributions');
            fetch('/contributions').then(function (r) { return r.json(); }).then(function (timeline) {
                var palette = ['#1f77b4', '#2ca02c', '#ff7f0e', '#d62728'];
                var datasets = timeline.lines.map(function (line, i) {
                    return {
                        label: line.label,
                        data: line.points.map(function (p) {
                            return { x: p.date, y: parseFloat(p.cumulativeGbp) };
                        }),
                        borderColor: palette[i % palette.length],
                        backgroundColor: palette[i % palette.length],
                        stepped: line.label === 'Roth IRA' ? false : 'before',
                        borderDash: line.label === 'Total' ? [4, 4] : [],
                        pointRadius: 2,
                        tension: 0
                    };
                });
                var ctx = document.getElementById('contributions-chart').getContext('2d');
                contributionsChart = new Chart(ctx, {
                    type: 'line',
                    data: { datasets: datasets },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' }, title: { display: false } },
                            y: {
                                title: { display: true, text: 'Cumulative contributions (£)' },
                                ticks: {
                                    callback: function (v) {
                                        return '£' + v.toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                    }
                                }
                            }
                        },
                        plugins: {
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return ctx.dataset.label + ': £' +
                                            ctx.parsed.y.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                    }
                                }
                            },
                            legend: { position: 'bottom' }
                        }
                    }
                });
            }).catch(function (e) {
                contributionsLoaded = false;
                panelLoadFailed('panel-contributions', e, loadContributionsChart);
            });
        }

        // ---- Benchmark overlay -----------------------------------------------

        var benchmarkInitialized = false;

        function setupBenchmark() {
            if (benchmarkInitialized) return;
            benchmarkInitialized = true;
            fetch('/returns/symbols').then(function (r) { return r.json(); }).then(function (syms) {
                var sel = document.getElementById('benchmark-select');
                (syms || []).forEach(function (s) {
                    var opt = document.createElement('option');
                    opt.value = s;
                    opt.textContent = s;
                    sel.appendChild(opt);
                });
                var other = document.createElement('option');
                other.value = '__other__';
                other.textContent = 'Other (Yahoo ticker)…';
                sel.appendChild(other);
            });
            document.getElementById('benchmark-select').addEventListener('change', onSelectChange);
            document.getElementById('benchmark-apply').addEventListener('click', applyBenchmark);
            document.getElementById('benchmark-clear').addEventListener('click', clearBenchmark);
            document.getElementById('returns-zoom-reset').addEventListener('click', resetReturnsZoom);
        }

        function onSelectChange() {
            var sel = document.getElementById('benchmark-select');
            var input = document.getElementById('benchmark-ticker');
            input.style.display = sel.value === '__other__' ? 'inline-block' : 'none';
            if (sel.value === '__other__') input.focus();
        }

        function chosenSymbol() {
            var sel = document.getElementById('benchmark-select');
            if (!sel.value) return '';
            if (sel.value === '__other__') {
                return (document.getElementById('benchmark-ticker').value || '').trim().toUpperCase();
            }
            return sel.value;
        }

        function applyBenchmark() {
            var sym = chosenSymbol();
            var status = document.getElementById('benchmark-status');
            var err = document.getElementById('benchmark-error');
            err.style.display = 'none';
            err.textContent = '';
            if (!sym) {
                clearBenchmark();
                return;
            }
            status.textContent = 'Loading…';
            fetchBenchmark(sym).then(function (data) {
                if (data.missing) {
                    status.textContent = '';
                    askBenchmarkBackfill(sym).then(function (ok) {
                        if (!ok) return;
                        status.textContent = 'Downloading ' + sym + ' from Yahoo…';
                        fetch('/returns/benchmark/fetch?symbol=' + encodeURIComponent(sym), {
                            method: 'POST'
                        }).then(function (r) { return r.json(); }).then(function (res) {
                            if (!res.rows) {
                                err.textContent = 'Yahoo returned no rows for ' + sym +
                                    '. Check the ticker (e.g. EQQQ.L, QQQ, VWRL.L).';
                                err.style.display = 'block';
                                status.textContent = '';
                                return;
                            }
                            status.textContent = 'Loading…';
                            return fetchBenchmark(sym).then(applyBenchmarkData);
                        }).then(function () { status.textContent = ''; });
                    });
                    return;
                }
                applyBenchmarkData(data);
                status.textContent = '';
            }).catch(function (e) {
                status.textContent = '';
                err.textContent = e.message || String(e);
                err.style.display = 'block';
            });
        }

        function fetchBenchmark(sym) {
            return fetch('/returns/benchmark?symbol=' + encodeURIComponent(sym))
                    .then(function (r) {
                        if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Request failed'); });
                        return r.json();
                    });
        }

        function applyBenchmarkData(data) {
            if (!data || !data.growthPoints || !data.growthPoints.length) return;
            var pts = data.growthPoints.map(function (p) {
                return { x: p.date, y: parseFloat(p.growth) };
            });
            overlayBenchmark(data.symbol, pts);
            renderBenchmarkStats(data.symbol, data.summary);
        }

        function overlayBenchmark(sym, pts) {
            if (!returnsGrowthChart) return;
            var ds = returnsGrowthChart.data.datasets;
            var existing = ds.findIndex(function (d) { return d._benchmark; });
            var dataset = {
                label: sym + ' (total return)',
                data: pts,
                borderColor: '#ff7f0e',
                backgroundColor: 'rgba(255, 127, 14, 0.0)',
                fill: false,
                pointRadius: 0,
                borderWidth: 1.5,
                tension: 0.1,
                _benchmark: true
            };
            if (existing >= 0) ds[existing] = dataset;
            else ds.push(dataset);
            // Show the legend now that there's a second series.
            returnsGrowthChart.options.plugins.legend = { display: true, position: 'bottom' };
            returnsGrowthChart.update();
        }

        function renderBenchmarkStats(sym, summary) {
            var box = document.getElementById('benchmark-stats');
            box.style.display = 'flex';
            ['1w', '1m', '3m', '6m', '1y', '3y', '5y', 'since'].forEach(function (k) {
                document.getElementById('benchmark-label-' + k).textContent = sym;
            });
            setStat('bench-1w', summary && summary.trailing1w);
            setStat('bench-1m', summary && summary.trailing1m);
            setStat('bench-3m', summary && summary.trailing3m);
            setStat('bench-6m', summary && summary.trailing6m);
            setStat('bench-1y', summary && summary.trailing1y);
            setStat('bench-3y', summary && summary.trailing3y);
            setStat('bench-5y', summary && summary.trailing5y);
            setStat('bench-since', summary && summary.sinceInception);
        }

        function clearBenchmark() {
            document.getElementById('benchmark-select').value = '';
            document.getElementById('benchmark-ticker').value = '';
            document.getElementById('benchmark-ticker').style.display = 'none';
            document.getElementById('benchmark-status').textContent = '';
            var err = document.getElementById('benchmark-error');
            err.style.display = 'none';
            err.textContent = '';
            document.getElementById('benchmark-stats').style.display = 'none';
            if (returnsGrowthChart) {
                returnsGrowthChart.data.datasets = returnsGrowthChart.data.datasets
                        .filter(function (d) { return !d._benchmark; });
                returnsGrowthChart.options.plugins.legend = { display: false };
                returnsGrowthChart.update();
            }
        }

        function askBenchmarkBackfill(sym) {
            return new Promise(function (resolve) {
                var modal = document.getElementById('benchmark-backfill-modal');
                document.getElementById('benchmark-backfill-symbol').textContent = sym;
                var ok = document.getElementById('benchmark-backfill-ok');
                var cancel = document.getElementById('benchmark-backfill-cancel');
                function close(v) {
                    modal.classList.remove('open');
                    ok.removeEventListener('click', onOk);
                    cancel.removeEventListener('click', onCancel);
                    modal.removeEventListener('click', onBackdrop);
                    resolve(v);
                }
                function onOk() { close(true); }
                function onCancel() { close(false); }
                function onBackdrop(e) { if (e.target === modal) close(false); }
                ok.addEventListener('click', onOk);
                cancel.addEventListener('click', onCancel);
                modal.addEventListener('click', onBackdrop);
                modal.classList.add('open');
            });
        }

        // ---- Allocation ------------------------------------------------------

        var allocLoaded = false;
        var allocData = null;
        var allocCashChart = null;
        var allocSymbolsChart = null;
        var allocSnapshotChart = null;
        // 13-entry palette (12 top symbols + Other). Tableau-derived; distinct enough at
        // stacked-area thickness, with grey reserved for "Other" so it visually recedes.
        var ALLOC_COLORS = [
            '#1f77b4', '#ff7f0e', '#2ca02c', '#d62728', '#9467bd',
            '#8c564b', '#e377c2', '#17becf', '#bcbd22', '#7f7f7f',
            '#aec7e8', '#ffbb78', '#c5b0d5'
        ];
        var ALLOC_OTHER_COLOR = '#cccccc';
        var ALLOC_TOP_N = 12;

        // ---- investment-style block (very top of the Breakdown sub-view) ----------
        var stylesLoaded = false;
        var styleData = null;
        var styleDonut = null;
        var styleBasis = 'total';   // 'total' | 'invested'
        var styleEscBound = false;  // document-level, so it can't go through bindOnce
        // Bucket → colour, fixed per bucket so a bucket keeps its colour as rows reorder. Green
        // reads as value and blue as growth throughout the block (the quadrant cells use the same
        // two), purple for the cell that is both; greys are reserved for the structural buckets
        // and red for Unclassified, which is meant to draw the eye.
        var STYLE_COLORS = {
            VALUE: '#2ca02c', GROWTH: '#1f77b4', VALUE_GROWTH: '#9467bd', NEITHER: '#c7c7c7',
            FUND_VALUE: '#98df8a', FUND_BLEND: '#aec7e8', FUND_GROWTH: '#6baed6',
            FIXED_INCOME: '#7f7f7f', NON_EQUITY: '#c5b0d5', CASH: '#cccccc',
            UNKNOWN: '#d62728'
        };

        function loadStyles() {
            if (stylesLoaded) return;
            stylesLoaded = true;
            clearPanelError('style-block');
            fetch('/allocation/styles').then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (data) {
                styleData = data;
                bindOnce(document.getElementById('style-basis'), 'click', function (ev) {
                    var btn = ev.target.closest('button[data-basis]');
                    if (!btn) return;
                    styleBasis = btn.getAttribute('data-basis');
                    document.querySelectorAll('#style-basis button').forEach(function (b) {
                        b.classList.toggle('active', b === btn);
                    });
                    renderStyles();
                });
                // Delegated from the <table>: renderStyleTable replaces its innerHTML on every
                // basis toggle, so per-row listeners would not survive. The element itself does.
                bindOnce(document.getElementById('style-table'), 'click', function (ev) {
                    var tr = ev.target.closest('tr[data-bucket]');
                    if (tr) openStyleDetail(tr.getAttribute('data-bucket'));
                });
                bindOnce(document.getElementById('style-quadrant'), 'click', function (ev) {
                    var cell = ev.target.closest('[data-bucket]');
                    if (cell) openStyleDetail(cell.getAttribute('data-bucket'));
                });
                bindOnce(document.getElementById('style-detail-close'), 'click', closeStyleDetail);
                bindOnce(document.getElementById('style-detail-modal'), 'click', function (ev) {
                    if (ev.target.id === 'style-detail-modal') closeStyleDetail();
                });
                bindOnce(document.getElementById('style-income-tile'), 'click', openIncomeDetail);
                bindOnce(document.getElementById('income-detail-close'), 'click', closeIncomeDetail);
                bindOnce(document.getElementById('income-detail-modal'), 'click', function (ev) {
                    if (ev.target.id === 'income-detail-modal') closeIncomeDetail();
                });
                if (!styleEscBound) {
                    styleEscBound = true;
                    document.addEventListener('keydown', function (ev) {
                        if (ev.key !== 'Escape') return;
                        closeStyleDetail();
                        closeIncomeDetail();
                    });
                }
                renderStyles();
            }).catch(function (e) {
                stylesLoaded = false;
                panelLoadFailed('style-block', e, loadStyles);
            });
        }

        function stylePct(row) {
            return styleBasis === 'invested' ? row.pctOfInvested : row.pctOfTotal;
        }

        function renderStyles() {
            var d = styleData;
            if (!d) return;
            var warn = d.warnings || [];
            document.getElementById('style-warnings').innerHTML = warn.length
                ? '<div class="mo-banner">' + warn.map(escapeHtml).join('<br>') + '</div>' : '';

            var rows = d.rows || [];
            if (!rows.length) {
                document.getElementById('style-empty').textContent = 'Nothing to classify yet.';
                return;
            }
            document.getElementById('style-empty').style.display = 'none';
            document.getElementById('style-body').style.display = 'flex';
            document.getElementById('style-table-wrap').style.display = '';

            renderStyleIncomeTile(d);
            renderStyleQuadrant(d);
            renderStyleDonut(rows);
            renderStyleTable(d, rows);
            renderStyleUnknown(d);
        }

        /**
         * The headline income figure and the two yields under it.
         *
         * A single "portfolio yield" was misreadable in both directions: over the income base it
         * looked like the whole portfolio paid 7%, over the whole portfolio it looked like the
         * income holdings barely paid at all. So each percentage now states its own denominator,
         * and the drill-down carries the bridge between the two numerators.
         */
        function renderStyleIncomeTile(d) {
            var inc = d.income || {};
            var gbp = parseFloat(inc.gbp || 0);
            document.getElementById('style-income-value').textContent = fmtMoney(gbp);
            var pct = styleBasis === 'invested' ? inc.pctOfInvested : inc.pctOfTotal;
            document.getElementById('style-income-sub').textContent =
                fmtPct(pct) + (styleBasis === 'invested' ? ' of invested' : ' of total');

            var line = '<b>Portfolio yield</b> — ' + fmtPct(inc.yieldOnIncomeHoldings)
                + ' on income holdings · ' + fmtPct(inc.yieldOnTotal) + ' on total portfolio · '
                + fmtMoney(parseFloat(inc.annualIncomeGbp || 0)) + '/yr at current rates';
            if (inc.fundsExcluded) {
                line += '<br><span class="muted">Lower bound — funds pay distributions Yahoo '
                    + 'does not publish, so they are in neither the income nor the yield base.</span>';
            }
            document.getElementById('style-income-split').innerHTML = line;
        }

        /**
         * The 2x2 itself. Only the four scored equity cells appear: a grid that also had to hold
         * cash, gilts and funds would stop being a 2x2, and those are in the table below.
         */
        function renderStyleQuadrant(d) {
            var q = d.quadrant || {};
            var scored = parseFloat(q.scoredTotalGbp || 0);
            var cells = [
                { b: 'VALUE_GROWTH', cls: 'style-both', label: 'Cheap + growing', v: q.valueGrowthGbp },
                { b: 'GROWTH', cls: 'style-growth', label: 'Growing, not cheap', v: q.growthGbp },
                { b: 'VALUE', cls: 'style-value', label: 'Cheap, not growing', v: q.valueGbp },
                { b: 'NEITHER', cls: 'style-none', label: 'Neither', v: q.neitherGbp }
            ];
            document.getElementById('style-quadrant').innerHTML = cells.map(function (c) {
                var v = parseFloat(c.v || 0);
                var share = scored ? (v / scored * 100).toFixed(1) + '% of scored' : '—';
                return '<div class="style-cell ' + c.cls + '" data-bucket="' + c.b + '"'
                    + ' title="Click to see the holdings in this cell" style="cursor:pointer;">'
                    + '<div class="style-cell-label">' + escapeHtml(c.label) + '</div>'
                    + '<div class="style-cell-value">' + fmtMoney(v) + '</div>'
                    + '<div class="style-cell-sub">' + share + '</div></div>';
            }).join('');
        }

        function renderStyleDonut(rows) {
            var ctx = document.getElementById('style-donut').getContext('2d');
            if (styleDonut) styleDonut.destroy();
            var total = rows.reduce(function (a, r) { return a + parseFloat(r.gbp); }, 0);
            styleDonut = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels: rows.map(function (r) { return r.label; }),
                    datasets: [{
                        data: rows.map(function (r) { return parseFloat(r.gbp); }),
                        backgroundColor: rows.map(function (r) {
                            return STYLE_COLORS[r.bucket] || '#999';
                        }),
                        borderWidth: 0
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    onClick: function (evt, elements) {
                        if (elements && elements.length) {
                            var r = rows[elements[0].index];
                            if (r) openStyleDetail(r.bucket);
                        }
                    },
                    plugins: {
                        legend: { position: 'right', labels: { boxWidth: 12, font: { size: 10 } } },
                        tooltip: {
                            callbacks: {
                                label: function (c) {
                                    var v = c.parsed;
                                    var share = total ? (v / total * 100).toFixed(1) : '0.0';
                                    return c.label + ': ' + fmtMoney(v) + '  (' + share + '%)';
                                }
                            }
                        }
                    }
                }
            });
        }

        function renderStyleTable(d, rows) {
            var basisLabel = styleBasis === 'invested' ? '% of invested' : '% of total';
            var html = '<tr><th class="lbl">Style</th><th>Value</th><th>' + basisLabel + '</th>'
                + '<th class="lbl">Basis</th></tr>';
            rows.forEach(function (r) {
                html += '<tr class="style-clickable' + (r.scored ? '' : ' style-structural') + '"'
                    + ' data-bucket="' + escapeHtml(r.bucket) + '"'
                    + ' title="Click to see the holdings in ' + escapeHtml(r.label) + '">'
                    + '<td class="lbl"><span style="display:inline-block;width:9px;height:9px;'
                    + 'border-radius:2px;margin-right:6px;background:'
                    + (STYLE_COLORS[r.bucket] || '#999') + '"></span>' + escapeHtml(r.label) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(r.gbp)) + '</td>'
                    + '<td>' + fmtPct(stylePct(r)) + '</td>'
                    + '<td class="lbl muted">' + styleBasisLabel(r) + '</td></tr>';
            });
            html += '<tr style="font-weight:600;border-top:2px solid #ccc;">'
                + '<td class="lbl">Total</td>'
                + '<td>' + fmtMoney(parseFloat(d.totalGbp)) + '</td>'
                + '<td>' + (styleBasis === 'invested' ? '—' : '100.0%') + '</td>'
                + '<td class="muted" style="font-weight:400;">'
                + 'of which invested ' + fmtMoney(parseFloat(d.investedGbp)) + '</td></tr>';
            document.getElementById('style-table').innerHTML = html;
        }

        /**
         * What the Basis column says. UNKNOWN is neither scored nor structural — calling it
         * "structural" would imply it had been placed deliberately, when in fact it is the row
         * asking to be looked at.
         */
        function styleBasisLabel(r) {
            if (r.scored) return 'scored on both axes';
            return r.bucket === 'UNKNOWN' ? 'not scored — see below' : 'structural';
        }

        /**
         * Drill-down on the income tile: the headline split into its two halves.
         *
         * Fixed income and equities are kept as separate sections rather than one ranked list
         * because they are not the same kind of promise — a gilt's running yield is contractual
         * and its capital is pulling to par, while a dividend is a policy that can be cut. Mixing
         * them into one table would invite reading a 4.99% gilt and a 4.6% bank as the same thing.
         */
        function openIncomeDetail() {
            if (!styleData) return;
            var inc = styleData.income || {};
            var pct = styleBasis === 'invested' ? inc.pctOfInvested : inc.pctOfTotal;
            document.getElementById('income-detail-sub').textContent =
                fmtMoney(parseFloat(inc.gbp || 0)) + '  ·  ' + fmtPct(pct)
                + (styleBasis === 'invested' ? ' of invested' : ' of total')
                + '  ·  ' + fmtMoney(parseFloat(inc.incomeAnnualGbp || 0)) + '/yr';

            var html = '<tr><th class="lbl">Holding</th><th>Value</th><th>Yield</th>'
                + '<th>Income/yr</th><th class="lbl">Style</th></tr>';
            html += incomeSection(inc.fixedIncome) + incomeSection(inc.equity);
            document.getElementById('income-detail-table').innerHTML = html;

            // The bridge. The two percentages on the tile have different numerators, so without
            // this the £/yr figure looks inconsistent with the yield beside it.
            var other = parseFloat(inc.otherAnnualIncomeGbp || 0);
            var note = fmtMoney(parseFloat(inc.annualIncomeGbp || 0)) + '/yr portfolio income = '
                + fmtMoney(parseFloat(inc.incomeAnnualGbp || 0)) + ' from the income holdings above'
                + ' + ' + fmtMoney(other) + ' from holdings below the income bar.'
                + ' The tile\'s ' + fmtPct(inc.yieldOnIncomeHoldings) + ' divides the first by '
                + fmtMoney(parseFloat(inc.gbp || 0)) + '; its ' + fmtPct(inc.yieldOnTotal)
                + ' divides the total by the whole portfolio, cash and funds included.';
            if (inc.fundsExcluded) {
                note += ' Funds pay distributions Yahoo does not publish, so both are a lower bound.';
            }
            document.getElementById('income-detail-note').textContent = note;
            document.getElementById('income-detail-modal').classList.add('open');
        }

        function incomeSection(sec) {
            if (!sec) return '';
            var list = sec.contributors || [];
            if (!list.length) return '';
            var html = '<tr class="income-section"><td class="lbl">' + escapeHtml(sec.label) + '</td>'
                + '<td>' + fmtMoney(parseFloat(sec.gbp || 0)) + '</td>'
                + '<td>' + fmtPct(sec.yield) + '</td>'
                + '<td>' + fmtMoney(parseFloat(sec.annualIncomeGbp || 0)) + '</td>'
                + '<td class="lbl"></td></tr>';
            list.forEach(function (c) {
                // Where the yield came from travels with it: a figure derived from the name's own
                // payment history is a different kind of claim from one Yahoo publishes, and a
                // reader has to be able to tell which they are looking at.
                var src = c.yieldSource || '';
                var derived = src.indexOf('Derived') === 0;
                html += '<tr><td class="lbl">' + escapeHtml(c.symbol) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(c.gbp)) + '</td>'
                    + '<td' + (src ? ' title="' + escapeHtml(src) + '"' : '') + '>'
                    + fmtPct(c.dividendYield)
                    + (derived ? ' <span class="yield-derived">derived</span>' : '') + '</td>'
                    + '<td>' + (c.annualIncomeGbp == null
                        ? '<span class="muted">not counted</span>'
                        : fmtMoney(parseFloat(c.annualIncomeGbp))) + '</td>'
                    + '<td class="lbl muted">' + escapeHtml(c.note || c.bucket || '') + '</td></tr>';
            });
            return html;
        }

        function closeIncomeDetail() {
            var m = document.getElementById('income-detail-modal');
            if (m) m.classList.remove('open');
        }

        /**
         * The amber half of an un-placed list: positions whose fundamentals row simply hasn't been
         * fetched yet. A name bought since the four-hourly refresh has no row at all, so it is not
         * unclassifiable — it is un-fetched, and the server has already queued the fetch. Showing
         * it in the same red strip as a genuinely sector-less instrument read as a data problem
         * every time a new holding was bought.
         */
        function pendingDataBanner(list) {
            var pending = list.filter(function (u) {
                return u.state === 'AWAITING_DATA' || u.state === 'FETCH_FAILED';
            });
            if (!pending.length) return '';
            var failed = pending.some(function (u) { return u.state === 'FETCH_FAILED'; });
            return '<div class="value-warnings pending" style="display:block; margin-top:0.6rem;">'
                + '<h4>Waiting on fundamentals data</h4>'
                + '<p style="margin:0.2rem 0 0;">'
                + pending.map(function (u) {
                    return '<b>' + escapeHtml(u.symbol) + '</b> ('
                        + fmtMoney(parseFloat(u.gbp)) + ')';
                }).join(' · ')
                + '</p><p class="muted" style="margin:0.35rem 0 0; font-size:0.78rem;">'
                + (failed
                    ? 'Yahoo returned nothing on the last attempt. A retry is queued — '
                    : 'Held, but not yet in the fundamentals cache. A fetch is queued — ')
                + 'reload this tab in a few seconds. The scheduled refresh would pick these up '
                + 'within four hours regardless.</p></div>';
        }

        /** Only the positions that really are unplaceable, i.e. the ones worth a red strip. */
        function settledUnplaced(list) {
            return list.filter(function (u) {
                return u.state !== 'AWAITING_DATA' && u.state !== 'FETCH_FAILED';
            });
        }

        function renderStyleUnknown(d) {
            var list = d.unknown || [];
            var settled = settledUnplaced(list);
            document.getElementById('style-unknown').innerHTML = (settled.length
                ? '<div class="mo-banner" style="margin-top:0.6rem;"><b>Not scored — '
                + 'excluded from the value/growth split above:</b> '
                + settled.map(function (u) {
                    return escapeHtml(u.symbol) + ' (' + fmtMoney(parseFloat(u.gbp)) + ')';
                }).join(' · ') + '</div>'
                : '') + pendingDataBanner(list);
        }

        /**
         * Drill-down popup: every holding in one style bucket, with the scores and the individual
         * tests it passed. The flags are the point — a placement you can argue with beats one you
         * can only accept.
         */
        function openStyleDetail(bucket) {
            if (!styleData) return;
            var row = (styleData.rows || []).filter(function (r) { return r.bucket === bucket; })[0];
            if (!row) return;

            document.getElementById('style-detail-title').textContent = row.label;
            document.getElementById('style-detail-sub').textContent =
                fmtMoney(parseFloat(row.gbp)) + '  ·  ' + fmtPct(stylePct(row))
                + (styleBasis === 'invested' ? ' of invested' : ' of total');

            var list = row.constituents || [];
            var scored = row.scored;
            var html = '<tr><th class="lbl">Holding</th><th>Value</th><th>% of bucket</th>'
                + (scored ? '<th>Value score</th><th>Growth score</th><th>Yield</th>' : '')
                + '<th class="lbl">Why</th></tr>';
            if (!list.length) {
                html += '<tr><td colspan="' + (scored ? 7 : 4)
                    + '" class="muted">No detail available.</td></tr>';
            }
            list.forEach(function (c) {
                var why = (c.flags || []).map(function (f) {
                    return f.replace(/^V:/, '').replace(/^G:/, '');
                }).join(', ');
                html += '<tr><td class="lbl">' + escapeHtml(c.label) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(c.gbp)) + '</td>'
                    + '<td>' + fmtPct(c.pctOfBucket) + '</td>';
                if (scored) {
                    html += '<td>' + (c.valueScore == null ? '—' : fmtPct(c.valueScore)) + '</td>'
                        + '<td>' + (c.growthScore == null ? '—' : fmtPct(c.growthScore)) + '</td>'
                        + '<td>' + (c.dividendYield == null ? '—' : fmtPct(c.dividendYield)) + '</td>';
                }
                html += '<td class="lbl muted">' + escapeHtml(why || c.note || '—') + '</td></tr>';
            });
            document.getElementById('style-detail-table').innerHTML = html;
            document.getElementById('style-detail-modal').classList.add('open');
        }

        function closeStyleDetail() {
            var m = document.getElementById('style-detail-modal');
            if (m) m.classList.remove('open');
        }

        // ---- sector & AI/Tech block (top of the Breakdown sub-view) ---------------
        var sectorsLoaded = false;
        var sectorData = null;
        var sectorDonut = null;
        var sectorBasis = 'total';   // 'total' | 'invested'
        var sectorEscBound = false;  // document-level, so it can't go through bindOnce
        // Bucket → colour. Fixed per bucket rather than positional, so a bucket keeps its
        // colour as rows reorder; grey tones are reserved for the non-equity buckets and
        // red for Unclassified, which is meant to draw the eye.
        var SECTOR_COLORS = {
            TECH_AI: '#1f77b4', COMMS_MEDIA: '#17becf', FINANCIALS: '#2ca02c',
            HEALTHCARE: '#9467bd', CONSUMER_DISCRETIONARY: '#ff7f0e',
            CONSUMER_STAPLES: '#98df8a', INDUSTRIALS_DEFENCE: '#8c564b',
            ENERGY: '#bcbd22', MATERIALS_MINING: '#e377c2', UTILITIES_INFRA: '#aec7e8',
            REAL_ESTATE: '#ffbb78', FIXED_INCOME: '#7f7f7f', COMMODITIES: '#c5b0d5',
            CASH: '#cccccc', UNCLASSIFIED: '#d62728'
        };

        function loadSectors() {
            if (sectorsLoaded) return;
            sectorsLoaded = true;
            clearPanelError('sector-block');
            fetch('/allocation/sectors').then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (data) {
                sectorData = data;
                bindOnce(document.getElementById('sector-basis'), 'click', function (ev) {
                    var btn = ev.target.closest('button[data-basis]');
                    if (!btn) return;
                    sectorBasis = btn.getAttribute('data-basis');
                    document.querySelectorAll('#sector-basis button').forEach(function (b) {
                        b.classList.toggle('active', b === btn);
                    });
                    renderSectors();
                });
                // Delegated: renderSectorTable replaces the table's innerHTML on every basis
                // toggle, so per-row listeners would be lost. The <table> element itself persists.
                bindOnce(document.getElementById('sector-table'), 'click', function (ev) {
                    var tr = ev.target.closest('tr[data-bucket]');
                    if (tr) openSectorDetail(tr.getAttribute('data-bucket'));
                });
                bindOnce(document.getElementById('sector-detail-close'), 'click', closeSectorDetail);
                bindOnce(document.getElementById('sector-detail-modal'), 'click', function (ev) {
                    if (ev.target.id === 'sector-detail-modal') closeSectorDetail();
                });
                if (!sectorEscBound) {
                    sectorEscBound = true;
                    document.addEventListener('keydown', function (ev) {
                        if (ev.key === 'Escape') closeSectorDetail();
                    });
                }
                renderSectors();
            }).catch(function (e) {
                sectorsLoaded = false;
                panelLoadFailed('sector-block', e, loadSectors);
            });
        }

        function sectorPct(row) {
            return sectorBasis === 'invested' ? row.pctOfInvested : row.pctOfTotal;
        }

        function renderSectors() {
            var d = sectorData;
            if (!d) return;
            var warn = d.warnings || [];
            document.getElementById('sector-warnings').innerHTML = warn.length
                ? '<div class="mo-banner">' + warn.map(escapeHtml).join('<br>') + '</div>' : '';

            var rows = d.rows || [];
            if (!rows.length) {
                document.getElementById('sector-empty').textContent =
                    'Nothing to classify yet.';
                return;
            }
            document.getElementById('sector-empty').style.display = 'none';
            document.getElementById('sector-body').style.display = 'flex';
            document.getElementById('sector-table-wrap').style.display = '';

            renderSectorAiTile(d);
            renderSectorDonut(rows);
            renderSectorTable(d, rows);
            renderSectorAiContributors(d);
            renderSectorUnclassified(d);
            renderSectorFundNotes(d);
        }

        /**
         * Drill-down popup: every position feeding one bucket. The unit is the position, not the
         * underlying company — a fund shows as one row carrying its slice, because Yahoo names
         * only a fund's top 10 and several of ours resolve almost none of themselves. Listing
         * positions keeps the rows summing to the bucket exactly.
         */
        /**
         * Group a bucket's constituents, biggest group first, each with its own subtotal and share
         * of the bucket.
         *
         * The heading is the *maintained* group where sectors.properties names one ("Banks",
         * "Insurance"), otherwise Yahoo's own industry. Yahoo's taxonomy is finer than anyone reads
         * a portfolio at — Financials arrives as six industries, splitting four banks and three
         * insurers into groups of one and two — and the mapping collapses it. An unmapped industry
         * deliberately keeps its raw name and appears as its own group: that is the signal a
         * mapping is missing, which an "Other" bin would swallow.
         *
         * Two behaviours carried over unchanged. A bucket whose holdings all land in one group
         * comes back as a single unheaded group, so the popup is as it was where there is nothing
         * to split. And fund slices are pooled under one "via funds" heading rather than being
         * given an industry: Yahoo publishes sectorWeightings at sector level only, so a fund's
         * contribution genuinely spans the whole sector and labelling it would be invention.
         */
        function groupByIndustry(list) {
            var total = list.reduce(function (a, c) { return a + parseFloat(c.gbp); }, 0);
            var byName = {};
            var order = [];
            list.forEach(function (c) {
                var name = c.viaFund ? 'via funds — sector-level only'
                    : (c.industryGroup || c.industry || 'Industry not classified');
                if (!byName[name]) {
                    byName[name] = {name: name, gbp: 0, items: [], viaFund: !!c.viaFund};
                    order.push(name);
                }
                byName[name].gbp += parseFloat(c.gbp);
                byName[name].items.push(c);
            });
            var groups = order.map(function (n) { return byName[n]; });
            groups.sort(function (a, b) { return b.gbp - a.gbp; });
            var multiple = groups.length > 1;
            groups.forEach(function (g) {
                g.share = total > 0 ? g.gbp / total : 0;
                g.showHeading = multiple;
                g.items.sort(function (a, b) { return parseFloat(b.gbp) - parseFloat(a.gbp); });
            });
            return groups;
        }

        function openSectorDetail(bucket) {
            if (!sectorData) return;
            var row = (sectorData.rows || []).filter(function (r) { return r.bucket === bucket; })[0];
            if (!row) return;

            document.getElementById('sector-detail-title').textContent = row.label;
            var pct = sectorPct(row);
            document.getElementById('sector-detail-sub').textContent =
                fmtMoney(parseFloat(row.gbp)) + '  ·  ' + fmtPct(pct)
                + (sectorBasis === 'invested' ? ' of invested' : ' of total');

            var list = row.constituents || [];
            var html = '<tr><th class="lbl">Holding</th><th>Value</th><th>% of sector</th>'
                + '<th class="lbl" title="Yahoo\'s own industry, shipped verbatim. The group '
                + 'headings above collapse these; keeping the raw value here is what makes an '
                + 'upstream reclassification visible instead of silently overridden.">'
                + 'Yahoo industry</th>'
                + '<th class="lbl">How</th></tr>';
            if (!list.length) {
                html += '<tr><td colspan="5" class="muted">No detail available.</td></tr>';
            }
            var isCash = bucket === 'CASH';

            // Grouped with subtotals. A GICS sector can hold businesses with quite different
            // drivers — Financials is banks, insurers, payment networks and a data vendor in one
            // row — and the group is already on each holding, so the split costs nothing but a
            // sort. Groups only appear when there is more than one: a single-group sector reads
            // exactly as it did before.
            groupByIndustry(list).forEach(function (g) {
                if (g.showHeading) {
                    html += '<tr class="sector-industry"><td class="lbl">' + escapeHtml(g.name)
                        + '</td><td>' + fmtMoney(g.gbp) + '</td><td>' + fmtPct(g.share)
                        + '</td><td></td><td></td></tr>';
                }
                g.items.forEach(function (c) {
                    var how = isCash ? 'cash balance'
                        : c.viaFund
                            ? 'via fund' + (c.shareOfPosition != null
                                ? ' (' + fmtPct(c.shareOfPosition) + ' of ' + escapeHtml(c.label) + ')' : '')
                            : 'held directly';
                    // The raw feed value sits beside the group it was mapped into. A per-symbol
                    // override is marked, because it is the one layer that goes on asserting its
                    // group whatever Yahoo later says — so it is the one worth checking this
                    // column against.
                    var raw = c.viaFund || !c.industry ? '—' : escapeHtml(c.industry);
                    if (c.industryOverride) {
                        raw += ' <span class="industry-override" title="Grouped under &quot;'
                            + escapeHtml(c.industryGroup || '') + '&quot; by a per-symbol override '
                            + 'in sectors.properties, not by this industry.">override</span>';
                    }
                    html += '<tr' + (g.showHeading ? ' class="sector-industry-member"' : '') + '>'
                        + '<td class="lbl">' + escapeHtml(c.label) + '</td>'
                        + '<td>' + fmtMoney(parseFloat(c.gbp)) + '</td>'
                        + '<td>' + fmtPct(c.pctOfBucket) + '</td>'
                        + '<td class="lbl muted">' + raw + '</td>'
                        + '<td class="lbl muted' + (c.viaFund ? ' sector-viafund' : '') + '">' + how + '</td>'
                        + '</tr>';
                });
            });
            html += '<tr style="font-weight:600;border-top:2px solid #ccc;">'
                + '<td class="lbl">Total</td><td>' + fmtMoney(parseFloat(row.gbp)) + '</td>'
                + '<td>100.0%</td><td></td><td></td></tr>';
            document.getElementById('sector-detail-table').innerHTML = html;
            document.getElementById('sector-detail-modal').classList.add('open');
        }

        function closeSectorDetail() {
            document.getElementById('sector-detail-modal').classList.remove('open');
        }

        var SECTOR_AI_SOURCE = {
            TECHNOLOGY_SECTOR: 'Technology sector',
            TAGGED: 'Tagged',
            FUND: 'Fund look-through'
        };

        /**
         * Takes the AI/Tech figure apart, one row per contributing position. The Source column is
         * the payload: a TAGGED row names the sector it is actually filed under, which is the
         * entire explanation for why it isn't in the Technology bucket.
         */
        function renderSectorAiContributors(d) {
            var ai = d.aiTech || {};
            var list = ai.contributors || [];
            var el = document.getElementById('sector-ai-detail');
            if (!list.length) { el.style.display = 'none'; return; }
            el.style.display = '';

            document.getElementById('sector-ai-detail-head').innerHTML =
                'Held directly <b>' + fmtMoney(parseFloat(ai.directGbp || 0)) + '</b> · '
                + 'via funds <b>' + fmtMoney(parseFloat(ai.viaFundGbp || 0)) + '</b>. '
                + 'The <i>From Technology</i> column totals the Technology row of the table above; '
                + 'the <i>Cross-sector</i> column is everything the standard classification files '
                + 'somewhere else.';

            var html = '<tr><th class="lbl">Symbol</th><th>AI/Tech value</th>'
                + '<th class="lbl">Source</th><th>From Technology</th><th>Cross-sector</th></tr>';
            list.forEach(function (c) {
                var src = SECTOR_AI_SOURCE[c.source] || c.source;
                if (c.source === 'TAGGED' && c.sectorLabel) {
                    src += ' — ' + c.sectorLabel;
                }
                var ft = parseFloat(c.fromTechSectorGbp || 0);
                var cs = parseFloat(c.crossSectorGbp || 0);
                html += '<tr>'
                    + '<td class="lbl">' + escapeHtml(c.symbol) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(c.gbp)) + '</td>'
                    + '<td class="lbl muted">' + escapeHtml(src) + '</td>'
                    + '<td>' + (ft ? fmtMoney(ft) : '—') + '</td>'
                    + '<td class="sector-viafund">' + (cs ? fmtMoney(cs) : '—') + '</td>'
                    + '</tr>';
            });
            html += '<tr style="font-weight:600;border-top:2px solid #ccc;">'
                + '<td class="lbl">Total</td>'
                + '<td>' + fmtMoney(parseFloat(ai.gbp || 0)) + '</td>'
                + '<td></td>'
                + '<td>' + fmtMoney(parseFloat(ai.fromTechSectorGbp || 0)) + '</td>'
                + '<td>' + fmtMoney(parseFloat(ai.crossSectorGbp || 0)) + '</td></tr>';
            document.getElementById('sector-ai-table').innerHTML = html;
        }

        function renderSectorAiTile(d) {
            var ai = d.aiTech || {};
            var pct = sectorBasis === 'invested' ? ai.pctOfInvested : ai.pctOfTotal;
            // "≥" is load-bearing: Yahoo names only a fund's top 10 holdings, so qualifying
            // names in the unnamed tail cannot be counted and the figure understates.
            document.getElementById('sector-ai-value').textContent =
                (ai.lowerBound ? '≥ ' : '') + fmtPct(pct);
            document.getElementById('sector-ai-sub').textContent =
                fmtMoney(parseFloat(ai.gbp || 0))
                + (sectorBasis === 'invested' ? ' of invested' : ' of total');

            // The bridge, not the direct/fund split: this is the line that explains why the tile
            // and the Technology row differ, which is the question the panel kept prompting.
            var fromTech = parseFloat(ai.fromTechSectorGbp || 0);
            var cross = parseFloat(ai.crossSectorGbp || 0);
            document.getElementById('sector-ai-split').innerHTML =
                'Technology sector: <b>' + fmtMoney(fromTech) + '</b><br>'
                + '<span class="sector-viafund">+ AI names filed in other sectors: <b>'
                + fmtMoney(cross) + '</b></span>';

            document.getElementById('sector-ai-note').innerHTML = ai.lowerBound
                ? 'A lower bound. Technology weight inside each fund counts in full; named '
                + 'constituents outside Technology (Alphabet, Amazon, Meta…) are added only where '
                + 'the fund discloses them, and Yahoo publishes just the top 10.'
                : 'Counts holdings whose sector is Technology, plus the mega-cap internet names '
                + 'GICS files elsewhere.';
        }

        function renderSectorDonut(rows) {
            var ctx = document.getElementById('sector-donut').getContext('2d');
            if (sectorDonut) sectorDonut.destroy();
            var total = rows.reduce(function (a, r) { return a + parseFloat(r.gbp); }, 0);
            sectorDonut = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels: rows.map(function (r) { return r.label; }),
                    datasets: [{
                        data: rows.map(function (r) { return parseFloat(r.gbp); }),
                        backgroundColor: rows.map(function (r) {
                            return SECTOR_COLORS[r.bucket] || '#999';
                        }),
                        borderWidth: 0
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    // The dataset is built from `rows` in order, so the clicked element's index
                    // maps straight back to a bucket — clicking a slice is the same gesture as
                    // clicking its table row.
                    onClick: function (evt, elements) {
                        if (elements && elements.length) {
                            var r = rows[elements[0].index];
                            if (r) openSectorDetail(r.bucket);
                        }
                    },
                    plugins: {
                        legend: { position: 'right', labels: { boxWidth: 12, font: { size: 10 } } },
                        tooltip: {
                            callbacks: {
                                label: function (c) {
                                    var v = c.parsed;
                                    var share = total ? (v / total * 100).toFixed(1) : '0.0';
                                    return c.label + ': ' + fmtMoney(v) + '  (' + share + '%)';
                                }
                            }
                        }
                    }
                }
            });
        }

        function renderSectorTable(d, rows) {
            var basisLabel = sectorBasis === 'invested' ? '% of invested' : '% of total';
            var html = '<tr><th class="lbl">Sector</th><th>Value</th><th>' + basisLabel + '</th>'
                + '<th>Direct</th><th>Via fund</th></tr>';
            rows.forEach(function (r) {
                var viaFund = parseFloat(r.viaFundGbp || 0);
                html += '<tr class="sector-clickable" data-bucket="' + escapeHtml(r.bucket) + '"'
                    + ' title="Click to see what makes up ' + escapeHtml(r.label) + '">'
                    + '<td class="lbl"><span style="display:inline-block;width:9px;height:9px;'
                    + 'border-radius:2px;margin-right:6px;background:'
                    + (SECTOR_COLORS[r.bucket] || '#999') + '"></span>' + escapeHtml(r.label) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(r.gbp)) + '</td>'
                    + '<td>' + fmtPct(sectorPct(r)) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(r.directGbp || 0)) + '</td>'
                    + '<td class="sector-viafund">'
                    + (viaFund ? fmtMoney(viaFund) : '—') + '</td>'
                    + '</tr>';
            });
            // The Total row always states the portfolio total; the invested figure rides alongside
            // as context, because on the invested basis the buckets sum to something smaller than
            // this row's value and a bare "100%" would be wrong.
            html += '<tr style="font-weight:600;border-top:2px solid #ccc;">'
                + '<td class="lbl">Total</td>'
                + '<td>' + fmtMoney(parseFloat(d.totalGbp)) + '</td>'
                + '<td>' + (sectorBasis === 'invested' ? '—' : '100.0%') + '</td>'
                + '<td colspan="2" class="muted" style="font-weight:400;">'
                + 'of which invested ' + fmtMoney(parseFloat(d.investedGbp)) + '</td></tr>';
            document.getElementById('sector-table').innerHTML = html;
        }

        function renderSectorUnclassified(d) {
            var list = d.unclassified || [];
            var settled = settledUnplaced(list);
            document.getElementById('sector-unclassified').innerHTML = (settled.length
                ? '<div class="mo-banner" style="margin-top:0.6rem;"><b>Unclassified — '
                + 'excluded from every sector above:</b> '
                + settled.map(function (u) {
                    return escapeHtml(u.symbol) + ' (' + fmtMoney(parseFloat(u.gbp)) + ')';
                }).join(' · ') + '</div>'
                : '') + pendingDataBanner(list);
        }

        function renderSectorFundNotes(d) {
            var funds = d.lookThrough || [];
            var el = document.getElementById('sector-lookthrough');
            if (!funds.length) { el.style.display = 'none'; return; }
            el.style.display = '';
            var html = '<tr><th class="lbl">Fund</th><th>Value</th><th>Technology</th>'
                + '<th>Named holdings</th><th class="lbl">Weights fetched</th></tr>';
            funds.forEach(function (f) {
                html += '<tr>'
                    + '<td class="lbl">' + escapeHtml(f.symbol) + '</td>'
                    + '<td>' + fmtMoney(parseFloat(f.gbp)) + '</td>'
                    + '<td>' + fmtPct(f.techPct) + '</td>'
                    + '<td>' + fmtPct(f.nameCoveragePct) + '</td>'
                    + '<td class="lbl muted">'
                    + escapeHtml((f.fetchedAt || '—').replace('T', ' ').slice(0, 16)) + '</td>'
                    + '</tr>';
            });
            document.getElementById('sector-fund-table').innerHTML = html;
        }

        function loadAllocation() {
            if (allocLoaded) return;
            allocLoaded = true;
            clearPanelError('alloc-view-breakdown');
            loadTargetAllocation();
            bindOnce(document.getElementById('target-save'), 'click', saveTargetAllocation);
            fetch('/allocation').then(function (r) { return r.json(); }).then(function (data) {
                allocData = data;
                if (!data.points || !data.points.length) return;
                renderAllocCashChart(data.points);
                renderAllocSymbolsChart(data.points);
                initAllocSnapshot(data.points);
                bindOnce(document.getElementById('alloc-cash-zoom-reset'), 'click', function () {
                    if (allocCashChart) allocCashChart.resetZoom();
                });
                bindOnce(document.getElementById('alloc-symbols-zoom-reset'), 'click', function () {
                    if (allocSymbolsChart) allocSymbolsChart.resetZoom();
                });
            }).catch(function (e) {
                allocLoaded = false;
                panelLoadFailed('alloc-view-breakdown', e, loadAllocation);
            });
        }

        function renderAllocCashChart(points) {
            var ctx = document.getElementById('alloc-cash-chart').getContext('2d');
            var cashData = points.map(function (p) {
                return { x: p.date, y: parseFloat(p.cashGbp) };
            });
            var investedData = points.map(function (p) {
                return { x: p.date, y: parseFloat(p.investedGbp) };
            });
            allocCashChart = new Chart(ctx, {
                type: 'line',
                data: {
                    datasets: [
                        { label: 'Invested', data: investedData,
                          borderColor: '#1f77b4', backgroundColor: 'rgba(31, 119, 180, 0.55)',
                          fill: true, pointRadius: 0, borderWidth: 1, tension: 0.1 },
                        { label: 'Cash', data: cashData,
                          borderColor: '#7f7f7f', backgroundColor: 'rgba(127, 127, 127, 0.55)',
                          fill: true, pointRadius: 0, borderWidth: 1, tension: 0.1 }
                    ]
                },
                options: stackedAreaOptions('Value (£)')
            });
        }

        function renderAllocSymbolsChart(points) {
            // Rank by latest GBP value, take top N, bucket the rest as "Other". A position
            // recently exited still appears in the "Other" band for prior dates — its band
            // simply goes to zero on the date the qty went to 0.
            var latest = points[points.length - 1].symbolGbp || {};
            var ranked = Object.keys(latest).sort(function (a, b) {
                return parseFloat(latest[b]) - parseFloat(latest[a]);
            });
            var top = ranked.slice(0, ALLOC_TOP_N);
            var topSet = new Set(top);

            var datasets = top.map(function (sym, i) {
                return {
                    label: sym,
                    data: points.map(function (p) {
                        var v = (p.symbolGbp || {})[sym];
                        return { x: p.date, y: v != null ? parseFloat(v) : 0 };
                    }),
                    borderColor: ALLOC_COLORS[i % ALLOC_COLORS.length],
                    backgroundColor: ALLOC_COLORS[i % ALLOC_COLORS.length] + 'cc',
                    fill: true, pointRadius: 0, borderWidth: 0
                };
            });
            // Cash sits at the top of the stack — same hatched grey as the cash band above.
            datasets.push({
                label: 'Cash',
                data: points.map(function (p) { return { x: p.date, y: parseFloat(p.cashGbp) }; }),
                borderColor: '#999', backgroundColor: 'rgba(127, 127, 127, 0.45)',
                fill: true, pointRadius: 0, borderWidth: 0
            });
            // "Other" = invested − sum of top-N for each sample. Always non-negative.
            datasets.push({
                label: 'Other (' + Math.max(0, Object.keys(latest).length - top.length) + ')',
                data: points.map(function (p) {
                    var others = 0;
                    var syms = p.symbolGbp || {};
                    Object.keys(syms).forEach(function (k) {
                        if (!topSet.has(k)) others += parseFloat(syms[k]);
                    });
                    return { x: p.date, y: others };
                }),
                borderColor: ALLOC_OTHER_COLOR, backgroundColor: ALLOC_OTHER_COLOR + 'cc',
                fill: true, pointRadius: 0, borderWidth: 0
            });

            var ctx = document.getElementById('alloc-symbols-chart').getContext('2d');
            allocSymbolsChart = new Chart(ctx, {
                type: 'line',
                data: { datasets: datasets },
                options: stackedAreaOptions('Value (£)')
            });
        }

        function stackedAreaOptions(yLabel) {
            return {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'nearest', axis: 'x', intersect: false },
                scales: {
                    x: { type: 'time', time: { unit: 'year' } },
                    y: {
                        stacked: true,
                        title: { display: true, text: yLabel },
                        ticks: {
                            callback: function (v) {
                                return '£' + v.toLocaleString('en-GB', { maximumFractionDigits: 0 });
                            }
                        }
                    }
                },
                plugins: {
                    legend: { position: 'bottom', labels: { boxWidth: 12 } },
                    tooltip: {
                        callbacks: {
                            label: function (ctx) {
                                return ctx.dataset.label + ': £' +
                                    ctx.parsed.y.toLocaleString('en-GB',
                                        { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                            }
                        }
                    },
                    zoom: {
                        pan: { enabled: true, mode: 'x' },
                        zoom: {
                            mode: 'x',
                            drag: { enabled: true, backgroundColor: 'rgba(31, 119, 180, 0.12)' },
                            pinch: { enabled: true }
                        }
                    }
                }
            };
        }

        function loadTargetAllocation() {
            fetch('/allocation/targets').then(function (r) { return r.json(); })
                    .then(renderTargetAllocation);
        }

        function saveTargetAllocation() {
            var body = new URLSearchParams();
            body.set('targets', document.getElementById('target-input').value);
            var status = document.getElementById('target-status');
            var err = document.getElementById('target-error');
            status.textContent = 'Saving…';
            err.style.display = 'none';
            fetch('/allocation/targets', { method: 'POST', body: body })
                    .then(function (r) {
                        if (!r.ok) return r.text().then(function (msg) { throw new Error(msg || 'save failed'); });
                        status.textContent = 'Saved.';
                        return r.json().then(renderTargetAllocation);
                    })
                    .catch(function (e) {
                        status.textContent = '';
                        err.textContent = e.message;
                        err.style.display = '';
                    });
        }

        function renderTargetAllocation(report) {
            // Pre-fill the textarea with current targets so the user can edit, not retype.
            var input = document.getElementById('target-input');
            if (document.activeElement !== input) {
                var lines = (report.rows || [])
                        .filter(function (r) { return r.targeted; })
                        .map(function (r) {
                            return r.symbol + '=' +
                                    (parseFloat(r.targetWeight) * 100).toFixed(2);
                        });
                input.value = lines.join('\n');
            }
            var tbody = document.querySelector('#target-table tbody');
            tbody.innerHTML = '';
            (report.rows || []).forEach(function (r) {
                var tr = document.createElement('tr');
                if (!r.targeted) tr.style.opacity = '0.55';
                var actionClass = '';
                if (r.suggestedAction === 'trim') actionClass = 'neg';
                else if (r.suggestedAction === 'add') actionClass = 'pos';
                tr.innerHTML =
                    '<td class="txt"><b>' + r.symbol + '</b>' +
                        (r.targeted ? '' : ' <span class="muted">(no target)</span>') + '</td>' +
                    '<td>' + (r.targetWeight != null
                        ? (parseFloat(r.targetWeight) * 100).toFixed(2) + '%' : '—') + '</td>' +
                    '<td>' + (parseFloat(r.actualWeight) * 100).toFixed(2) + '%</td>' +
                    '<td>' + (r.targetGbp != null ? fmtMoney0(r.targetGbp) : '—') + '</td>' +
                    '<td>' + fmtMoney0(r.actualGbp) + '</td>' +
                    '<td class="' + (r.driftGbp != null && parseFloat(r.driftGbp) >= 0 ? 'pos' : 'neg') + '">' +
                        (r.driftGbp != null ? fmtSignedMoney(parseFloat(r.driftGbp)) : '—') + '</td>' +
                    '<td>' + (r.driftPct != null
                        ? (parseFloat(r.driftPct) >= 0 ? '+' : '') +
                          (parseFloat(r.driftPct) * 100).toFixed(1) + '%' : '—') + '</td>' +
                    '<td class="' + actionClass + '">' + (r.suggestedAction || '—') + '</td>';
                tbody.appendChild(tr);
            });
            attachSortOnce('target-table');

            var sum = parseFloat(report.targetSum || 0) * 100;
            var warn = document.getElementById('target-sum-warning');
            if (sum > 0 && Math.abs(sum - 100) > 0.5) {
                warn.textContent = 'Target weights sum to ' + sum.toFixed(2) +
                        '% — implicit ' + (100 - sum).toFixed(2) + '% in cash / untargeted.';
            } else {
                warn.textContent = '';
            }
        }

        function initAllocSnapshot(points) {
            var input = document.getElementById('alloc-snapshot-date');
            input.max = points[points.length - 1].date;
            input.min = points[0].date;
            input.value = points[points.length - 1].date;
            input.addEventListener('change', function () { renderAllocSnapshot(input.value); });
            renderAllocSnapshot(input.value);
        }

        function renderAllocSnapshot(targetDate) {
            if (!allocData || !allocData.points || !allocData.points.length) return;
            // Find the latest sample on or before targetDate.
            var picked = null;
            for (var i = 0; i < allocData.points.length; i++) {
                if (allocData.points[i].date <= targetDate) picked = allocData.points[i];
                else break;
            }
            if (!picked) picked = allocData.points[0];

            var status = document.getElementById('alloc-snapshot-status');
            status.textContent = 'Sample: ' + picked.date +
                ' · Total £' + parseFloat(picked.totalGbp).toLocaleString('en-GB',
                    { maximumFractionDigits: 0 });

            // Build sorted list: every held symbol + Cash. Larger entries on top.
            var entries = Object.keys(picked.symbolGbp || {}).map(function (k) {
                return { label: k, value: parseFloat(picked.symbolGbp[k]) };
            });
            entries.push({ label: 'Cash', value: parseFloat(picked.cashGbp), isCash: true });
            entries.sort(function (a, b) { return b.value - a.value; });

            var labels = entries.map(function (e) { return e.label; });
            var values = entries.map(function (e) { return e.value; });
            var colors = entries.map(function (e) {
                return e.isCash ? '#7f7f7f' : ALLOC_COLORS[0];
            });

            var ctx = document.getElementById('alloc-snapshot-chart').getContext('2d');
            if (allocSnapshotChart) allocSnapshotChart.destroy();
            allocSnapshotChart = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'Value (£)',
                        data: values,
                        backgroundColor: colors,
                        borderWidth: 0
                    }]
                },
                options: {
                    indexAxis: 'y',
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        x: {
                            title: { display: true, text: 'Value (£)' },
                            ticks: {
                                callback: function (v) {
                                    return '£' + v.toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                }
                            }
                        }
                    },
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) {
                                    var v = ctx.parsed.x;
                                    var total = parseFloat(picked.totalGbp);
                                    var pct = total > 0 ? (v / total * 100).toFixed(1) : '—';
                                    return '£' + v.toLocaleString('en-GB',
                                            { minimumFractionDigits: 2, maximumFractionDigits: 2 }) +
                                        '  (' + pct + '%)';
                                }
                            }
                        }
                    }
                }
            });
        }

        // ---- Attribution -----------------------------------------------------

        var attrInitialized = false;
        var attrChart = null;
        var attrSeq = 0;
        var attrTableSortAttached = false;

        function setupAttribution() {
            if (attrInitialized) return;
            attrInitialized = true;
            document.querySelectorAll('#attr-presets .attr-preset').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    document.querySelectorAll('#attr-presets .attr-preset').forEach(function (b) {
                        b.classList.remove('active');
                    });
                    btn.classList.add('active');
                    applyPreset(btn.dataset.preset);
                    runAttribution();
                });
            });
            document.getElementById('attr-run').addEventListener('click', function () {
                document.querySelectorAll('#attr-presets .attr-preset').forEach(function (b) {
                    b.classList.remove('active');
                });
                runAttribution();
            });
            applyPreset('ytd');
            runAttribution();
        }

        function isoDate(d) {
            var y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'),
                day = String(d.getDate()).padStart(2, '0');
            return y + '-' + m + '-' + day;
        }

        function applyPreset(preset) {
            var to = new Date();
            var from = new Date();
            switch (preset) {
                case '5d': from.setDate(from.getDate() - 5); break;
                case '10d': from.setDate(from.getDate() - 10); break;
                case '1m': from.setMonth(from.getMonth() - 1); break;
                case '3m': from.setMonth(from.getMonth() - 3); break;
                case '6m': from.setMonth(from.getMonth() - 6); break;
                case 'ytd': from = new Date(to.getFullYear(), 0, 1); break;
                case '1y': from.setFullYear(from.getFullYear() - 1); break;
                case '3y': from.setFullYear(from.getFullYear() - 3); break;
                case 'all': from = new Date(2000, 0, 1); break;
            }
            document.getElementById('attr-from').value = isoDate(from);
            document.getElementById('attr-to').value = isoDate(to);
        }

        function runAttribution() {
            var from = document.getElementById('attr-from').value;
            var to = document.getElementById('attr-to').value;
            if (!from) return;
            var status = document.getElementById('attr-status');
            status.textContent = 'Working…';
            // Clicking through the presets leaves several requests in flight at once, and they
            // don't come back in order — a slow earlier one landing last would repaint the tiles
            // and chart with a window the buttons no longer show. Only the newest may render.
            var seq = ++attrSeq;
            var url = '/attribution?from=' + encodeURIComponent(from) +
                    (to ? '&to=' + encodeURIComponent(to) : '');
            fetch(url).then(function (r) {
                if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Request failed'); });
                return r.json();
            }).then(function (data) {
                if (seq !== attrSeq) return;
                renderAttribution(data);
                status.textContent = '';
            }).catch(function (err) {
                if (seq !== attrSeq) return;
                status.textContent = 'Error: ' + (err.message || String(err));
            });
        }

        // ASCII minus (not the typographic '−') so the table sort's parseFloat works on these cells.
        function fmtMoney(v) {
            return (v < 0 ? '-£' : '£') + Math.abs(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        function fmtPct(frac) {
            if (frac === null || frac === undefined) return '—';
            var v = parseFloat(frac) * 100;
            return (v >= 0 ? '' : '-') + Math.abs(v).toFixed(1) + '%';
        }

        function setMoneyStat(elId, v) {
            var el = document.getElementById(elId);
            el.textContent = fmtMoney(v);
            el.className = 'value ' + (v > 0 ? 'pos' : v < 0 ? 'neg' : '');
        }

        function renderAttribution(data) {
            renderAttributionWarnings(data.excluded, data.from);
            document.getElementById('attr-stats').style.display = 'flex';
            setMoneyStat('attr-total-pnl', parseFloat(data.totalPnlGbp));
            setMoneyStat('attr-total-start', parseFloat(data.totalStartValueGbp));
            setMoneyStat('attr-total-end', parseFloat(data.totalEndValueGbp));
            setMoneyStat('attr-total-cash', parseFloat(data.totalCashFlowGbp));
            // Strip the colour class off these three — only P&L gets the win/loss colour.
            ['attr-total-start', 'attr-total-end', 'attr-total-cash'].forEach(function (id) {
                document.getElementById(id).className = 'value';
            });

            renderAttributionChart(data.rows);
            renderAttributionTable(data.rows, parseFloat(data.totalPnlGbp));
        }

        // Symbols the server couldn't value on a boundary. They're out of the rows and out of every
        // total, so say which and why — a silent zero here reads as a gain the size of the position.
        function renderAttributionWarnings(excluded, from) {
            var warn = document.getElementById('attr-warnings');
            if (!excluded || !excluded.length) {
                warn.style.display = 'none';
                warn.innerHTML = '';
                return;
            }
            var items = excluded.map(function (e) {
                return '<li><code>' + e.symbol + '</code> — ' + e.reason + '</li>';
            }).join('');
            warn.innerHTML = '<h4>' + excluded.length + ' symbol' + (excluded.length === 1 ? '' : 's') +
                ' excluded</h4>' +
                '<p>These could not be valued on a window boundary, so they are left out of the ' +
                'table and of every total above. The remaining figures are internally consistent.</p>' +
                '<ul>' + items + '</ul>';
            warn.style.display = 'block';
        }

        function renderAttributionChart(rows) {
            // Top 8 contributors + bottom 8 detractors, smallest in the middle so the bars
            // read winners-on-top / losers-on-bottom in a single horizontal bar chart.
            var top = rows.slice(0, 8);
            var bottom = rows.slice(-8).filter(function (r) {
                return parseFloat(r.pnlGbp) < 0;
            }).reverse();
            var combined = top.concat(bottom);
            var labels = combined.map(function (r) { return r.symbol; });
            var values = combined.map(function (r) { return parseFloat(r.pnlGbp); });
            var colors = values.map(function (v) {
                return v >= 0 ? 'rgba(44, 160, 44, 0.85)' : 'rgba(192, 57, 43, 0.85)';
            });

            var ctx = document.getElementById('attr-chart').getContext('2d');
            if (attrChart) attrChart.destroy();
            attrChart = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'P&L (£)',
                        data: values,
                        backgroundColor: colors,
                        borderWidth: 0
                    }]
                },
                options: {
                    indexAxis: 'y',
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        x: {
                            title: { display: true, text: 'P&L (£)' },
                            ticks: {
                                callback: function (v) {
                                    return (v < 0 ? '−£' : '£') +
                                        Math.abs(v).toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                }
                            }
                        }
                    },
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            callbacks: {
                                label: function (ctx) { return fmtMoney(ctx.parsed.x); }
                            }
                        }
                    }
                }
            });
        }

        function renderAttributionTable(rows, totalPnl) {
            var tbody = document.querySelector('#attr-table tbody');
            tbody.innerHTML = '';
            // Share of the *net* P&L, signed, so the column sums to 100%. Note the denominator
            // collapses when winners and losers cancel: a £5k loser against a £3.6k net reads as
            // −138%. That's arithmetically right and deliberately kept — the blow-up is the signal
            // that the net is small, not an error. Flagged in the header tooltip.
            var denom = Math.abs(totalPnl) > 0.005 ? Math.abs(totalPnl) : null;
            rows.forEach(function (r) {
                var tr = document.createElement('tr');
                var pnl = parseFloat(r.pnlGbp);
                tr.className = 'attr-row ' + (pnl > 0 ? 'pos' : pnl < 0 ? 'neg' : '');
                tr.innerHTML =
                    '<td class="txt">' + r.symbol + '</td>' +
                    '<td>' + fmtMoney(parseFloat(r.startValueGbp)) + '</td>' +
                    '<td>' + fmtMoney(parseFloat(r.endValueGbp)) + '</td>' +
                    '<td>' + fmtMoney(parseFloat(r.cashFlowGbp)) + '</td>' +
                    '<td class="attr-pnl">' + fmtMoney(pnl) + '</td>' +
                    '<td>' + (denom === null ? '—' :
                        (pnl / denom * 100).toFixed(1) + '%') + '</td>' +
                    '<td>' + fmtPct(r.periodReturn) + '</td>' +
                    '<td>' + fmtPct(r.annualizedReturn) + '</td>';
                tbody.appendChild(tr);
            });
            if (!attrTableSortAttached) {
                attrTableSortAttached = true;
                attachSort(document.getElementById('attr-table'));
            }
        }

        var fundInitialized = false;
        var fundPeChart = null;
        var fundGrowthChart = null;

        var fundTickersLoaded = false;
        function setupFundamentals() {
            if (!fundInitialized) {
                fundInitialized = true;
                document.getElementById('fund-run').addEventListener('click', runFundamentals);
                document.getElementById('fund-ticker').addEventListener('change', runFundamentals);
                document.getElementById('fund-years').addEventListener('change', runFundamentals);
                document.getElementById('fund-backfill').addEventListener('click', backfillFundamentalsPrices);
                document.getElementById('fund-pe-zoom-reset').addEventListener('click', function () {
                    if (fundPeChart) fundPeChart.resetZoom();
                });
            }
            // The ticker load retries on each tab entry until it succeeds — a single failed
            // fetch (server restarting under the tab) must not leave the tab dead for the
            // whole session. Listeners above still attach exactly once.
            if (fundTickersLoaded) return;
            var sel = document.getElementById('fund-ticker');
            fetch('/fundamentals/tickers').then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (tickers) {
                fundTickersLoaded = true;
                (tickers || []).forEach(function (t) {
                    var opt = document.createElement('option');
                    opt.value = t;
                    opt.textContent = t;
                    sel.appendChild(opt);
                });
                // Default to GOOGL since the user's example used Google.
                if (sel.querySelector('option[value="GOOGL"]')) sel.value = 'GOOGL';
                runFundamentals();
            }).catch(function () {
                document.getElementById('fund-status').textContent =
                    'Could not load tickers — click the Fundamentals tab again to retry.';
            });
        }

        function runFundamentals() {
            var sym = document.getElementById('fund-ticker').value;
            var years = document.getElementById('fund-years').value;
            if (!sym) return;
            var status = document.getElementById('fund-status');
            status.textContent = 'Loading…';
            var url = '/fundamentals?symbol=' + encodeURIComponent(sym) +
                    '&years=' + encodeURIComponent(years);
            fetch(url).then(function (r) { return r.json(); })
                    .then(function (data) {
                        status.textContent = '';
                        renderFundamentals(data);
                    })
                    .catch(function (e) {
                        status.textContent = 'Error: ' + e.message;
                    });
        }

        function backfillFundamentalsPrices() {
            var sym = document.getElementById('fund-ticker').value;
            if (!sym) return;
            var btn = document.getElementById('fund-backfill');
            btn.disabled = true;
            var status = document.getElementById('fund-status');
            status.textContent = 'Backfilling prices… (~2s)';
            var body = new URLSearchParams();
            body.set('symbol', sym);
            fetch('/returns/benchmark/fetch', { method: 'POST', body: body })
                    .then(function (r) { return r.json(); })
                    .then(function () {
                        btn.disabled = false;
                        runFundamentals();
                    })
                    .catch(function (e) {
                        btn.disabled = false;
                        status.textContent = 'Backfill error: ' + e.message;
                    });
        }

        function renderFundamentals(data) {
            var msg = document.getElementById('fund-message');
            var summary = document.getElementById('fund-summary');
            var explain = document.getElementById('fund-explain');
            var backfillBtn = document.getElementById('fund-backfill');

            if (data.message) {
                msg.textContent = data.message;
                msg.style.display = 'block';
            } else {
                msg.style.display = 'none';
            }
            backfillBtn.style.display = data.missingPrices ? 'inline-block' : 'none';

            if (!data.points || data.points.length === 0) {
                summary.style.display = 'none';
                explain.style.display = 'none';
                if (fundPeChart) { fundPeChart.destroy(); fundPeChart = null; }
                if (fundGrowthChart) { fundGrowthChart.destroy(); fundGrowthChart = null; }
                return;
            }

            var s = data.summary;
            if (s) {
                summary.style.display = '';
                document.getElementById('fund-window').textContent =
                        s.start.date + ' → ' + s.end.date + '  (' + s.spanDays + 'd)';
                document.getElementById('fund-price-mult').textContent = fmtMult(s.priceMult);
                document.getElementById('fund-eps-mult').textContent = fmtMult(s.epsMult);
                document.getElementById('fund-pe-mult').textContent = fmtMult(s.peMult);
                explain.style.display = '';
                // The identity priceMult ≡ epsMult × peMult lets the user read which side drove it.
                var priceMult = parseFloat(s.priceMult);
                var epsMult = parseFloat(s.epsMult);
                var peMult = parseFloat(s.peMult);
                var driver;
                if (Math.abs(epsMult - 1) > Math.abs(peMult - 1) * 1.5) {
                    driver = 'mostly <b>earnings growth</b> — multiple barely moved.';
                } else if (Math.abs(peMult - 1) > Math.abs(epsMult - 1) * 1.5) {
                    driver = 'mostly <b>multiple expansion</b> — earnings did little of the lifting.';
                } else {
                    driver = 'a roughly even mix of earnings growth and multiple expansion.';
                }
                explain.innerHTML = data.symbol + ': price ×' + fmtMult(s.priceMult) +
                        ' = EPS ×' + fmtMult(s.epsMult) + ' × multiple ×' + fmtMult(s.peMult) +
                        '. Drivers: ' + driver;
            } else {
                summary.style.display = 'none';
                explain.style.display = 'none';
            }

            renderFundPeChart(data.points, data.symbol);
            renderFundGrowthChart(data.points, data.symbol);
        }

        function fmtMult(s) {
            if (s == null) return '—';
            var n = parseFloat(s);
            return n.toFixed(2);
        }

        function quantile(sorted, q) {
            var idx = (sorted.length - 1) * q;
            var lo = Math.floor(idx), hi = Math.ceil(idx);
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
        }

        function renderFundPeChart(points, symbol) {
            // Cap the visible P/E range so a single near-zero-earnings quarter (e.g. AMZN's
            // 2022 Rivian writedown, where TTM EPS dipped to ~$0.01 and P/E went to ~14,000)
            // doesn't crush the rest of the line into a flat zero. Use Tukey's fence
            // (Q3 + 1.5×IQR) — the standard outlier threshold. It's robust to spikes that
            // cover up to ~25% of bars (which a 95th-percentile cap is not), and for clean
            // tickers like MSFT/AAPL the max value sits well inside the fence so nothing
            // gets clipped. Capped values render as gaps; we surface the count.
            var finitePe = points.map(function (p) { return p.pe == null ? null : parseFloat(p.pe); })
                    .filter(function (v) { return v != null && isFinite(v) && v > 0; })
                    .sort(function (a, b) { return a - b; });
            var maxY = null;
            if (finitePe.length > 4) {
                var q1 = quantile(finitePe, 0.25);
                var q3 = quantile(finitePe, 0.75);
                var fence = q3 + 1.5 * (q3 - q1);
                maxY = Math.max(50, Math.ceil(fence));
            }
            var clipped = 0;
            var data = points.map(function (p) {
                if (p.pe == null) return { x: p.date, y: null };
                var v = parseFloat(p.pe);
                if (maxY != null && v > maxY) { clipped++; return { x: p.date, y: null }; }
                return { x: p.date, y: v };
            });

            var status = document.getElementById('fund-status');
            if (clipped > 0) {
                status.textContent = clipped + ' point(s) clipped (P/E > ' + maxY +
                        ' — near-zero TTM earnings)';
            } else {
                status.textContent = '';
            }

            var ctx = document.getElementById('fund-pe-chart').getContext('2d');
            if (fundPeChart) fundPeChart.destroy();
            fundPeChart = new Chart(ctx, {
                type: 'line',
                data: { datasets: [{
                    label: symbol + ' P/E (TTM)',
                    data: data,
                    borderColor: '#1f77b4',
                    backgroundColor: 'rgba(31,119,180,0.15)',
                    borderWidth: 1,
                    pointRadius: 0,
                    spanGaps: false,
                    tension: 0
                }] },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    interaction: { mode: 'nearest', axis: 'x', intersect: false },
                    scales: {
                        x: { type: 'time' },
                        y: {
                            title: { display: true, text: 'Trailing P/E' },
                            beginAtZero: true,
                            max: maxY
                        }
                    },
                    plugins: {
                        legend: { display: false },
                        zoom: {
                            pan: { enabled: true, mode: 'x' },
                            zoom: {
                                mode: 'x',
                                drag: { enabled: true, backgroundColor: 'rgba(31, 119, 180, 0.12)' },
                                pinch: { enabled: true }
                            }
                        }
                    }
                }
            });
        }

        function renderFundGrowthChart(points, symbol) {
            var firstPrice = null, firstEps = null;
            for (var i = 0; i < points.length; i++) {
                if (parseFloat(points[i].ttmEps) > 0) {
                    firstPrice = parseFloat(points[i].price);
                    firstEps = parseFloat(points[i].ttmEps);
                    break;
                }
            }
            if (firstPrice == null) return;
            var priceData = points.map(function (p) {
                return { x: p.date, y: parseFloat(p.price) / firstPrice };
            });
            var epsData = points.map(function (p) {
                var e = parseFloat(p.ttmEps);
                return { x: p.date, y: e > 0 ? e / firstEps : null };
            });
            var ctx = document.getElementById('fund-growth-chart').getContext('2d');
            if (fundGrowthChart) fundGrowthChart.destroy();
            fundGrowthChart = new Chart(ctx, {
                type: 'line',
                data: { datasets: [
                    { label: 'Price', data: priceData, borderColor: '#1f77b4',
                      backgroundColor: '#1f77b4', pointRadius: 0, borderWidth: 1, tension: 0.1 },
                    { label: 'TTM EPS', data: epsData, borderColor: '#2ca02c',
                      backgroundColor: '#2ca02c', pointRadius: 0, borderWidth: 1, tension: 0.1, spanGaps: false }
                ] },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    interaction: { mode: 'nearest', axis: 'x', intersect: false },
                    scales: {
                        x: { type: 'time', time: { unit: 'month' } },
                        y: {
                            title: { display: true, text: 'Multiple of window start' },
                            ticks: { callback: function (v) { return '×' + v.toFixed(2); } }
                        }
                    },
                    plugins: { legend: { position: 'bottom' } }
                }
            });
        }

        var snapshotInitialized = false;
        var snapshotRowsCache = [];
        var snapshotExtra = [];          // user-added (non-held) symbols, for the remove control
        var snapshotRefreshTimer = null;

        function setupSnapshot() {
            if (snapshotInitialized) return;
            snapshotInitialized = true;
            document.getElementById('snapshot-reload').addEventListener('click', triggerSnapshotRefresh);
            document.getElementById('snapshot-add-btn').addEventListener('click', addSnapshotSymbol);
            document.getElementById('snapshot-add-symbol').addEventListener('keydown', function (e) {
                if (e.key === 'Enter') addSnapshotSymbol();
            });
            document.getElementById('snapshot-detail-close').addEventListener('click', closeSnapshotDetail);
            document.getElementById('snapshot-detail-modal').addEventListener('click', function (e) {
                if (e.target.id === 'snapshot-detail-modal') closeSnapshotDetail();
            });
            attachSort(document.getElementById('snapshot-table'));
            loadSnapshot();
        }

        function loadSnapshot() {
            var status = document.getElementById('snapshot-status');
            fetch('/portfolio-fundamentals').then(function (r) { return r.json(); })
                    .then(function (data) {
                        snapshotExtra = data.extraSymbols || [];
                        renderSnapshot(data.rows || []);
                        status.textContent = formatLastUpdated(data.lastUpdatedAt);
                    })
                    .catch(function (e) { status.textContent = 'Error: ' + e.message; });
        }

        function addSnapshotSymbol() {
            var input = document.getElementById('snapshot-add-symbol');
            var sym = input.value.trim().toUpperCase();
            if (!sym) return;
            var status = document.getElementById('snapshot-status');
            status.textContent = 'Adding ' + sym + ' — fetching fundamentals…';
            var body = new URLSearchParams(); body.set('symbol', sym);
            fetch('/portfolio-fundamentals/add', { method: 'POST', body: body })
                    .then(function (r) { if (!r.ok) return r.text().then(function (t) { throw new Error(t); }); return r.json(); })
                    .then(function (data) {
                        input.value = '';
                        snapshotExtra = data.extraSymbols || [];
                        renderSnapshot(data.rows || []);
                        status.textContent = formatLastUpdated(data.lastUpdatedAt);
                    })
                    .catch(function () { status.textContent = 'Could not add ' + sym + '.'; });
        }

        function removeSnapshotSymbol(sym) {
            var body = new URLSearchParams(); body.set('symbol', sym);
            fetch('/portfolio-fundamentals/remove', { method: 'POST', body: body })
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                        snapshotExtra = data.extraSymbols || [];
                        renderSnapshot(data.rows || []);
                    });
        }

        function triggerSnapshotRefresh() {
            var status = document.getElementById('snapshot-status');
            var btn = document.getElementById('snapshot-reload');
            btn.disabled = true;
            status.textContent = 'Refreshing in the background…';
            fetch('/portfolio-fundamentals/refresh', { method: 'POST' })
                    .then(function (r) { return r.json(); })
                    .then(function () {
                        // Background refresh takes ~30s for a ~50-ticker portfolio. Schedule a
                        // single refetch once the job is expected to be done — re-rendering
                        // mid-job clobbered the user's active sort. Manual re-trigger via the
                        // Refresh button if rows look stale.
                        if (snapshotRefreshTimer) clearTimeout(snapshotRefreshTimer);
                        snapshotRefreshTimer = setTimeout(function () {
                            loadSnapshot();
                            snapshotRefreshTimer = null;
                            btn.disabled = false;
                        }, 45000);
                    })
                    .catch(function (e) {
                        status.textContent = 'Error: ' + e.message;
                        btn.disabled = false;
                    });
        }

        function formatLastUpdated(iso) {
            if (!iso) return 'No data yet — background refresh starting';
            var when = new Date(iso);
            var mins = Math.round((Date.now() - when.getTime()) / 60000);
            if (mins < 1) return 'Last updated: just now';
            if (mins < 60) return 'Last updated: ' + mins + ' min ago';
            var hours = Math.round(mins / 60);
            if (hours < 24) return 'Last updated: ' + hours + 'h ago';
            return 'Last updated: ' + when.toLocaleString('en-GB');
        }

        function renderSnapshot(rows) {
            snapshotRowsCache = rows;
            var table = document.getElementById('snapshot-table');
            var tbody = table.querySelector('tbody');
            tbody.innerHTML = '';
            var empty = document.getElementById('snapshot-empty');
            if (rows.length === 0) {
                empty.style.display = '';
                return;
            }
            empty.style.display = 'none';
            rows.forEach(function (row, idx) {
                var tr = document.createElement('tr');
                tr.style.cursor = 'pointer';
                tr.dataset.idx = idx;
                if (snapshotExtra.indexOf(row.symbol) >= 0) {
                    // A user-added (not-held) row — carry a remove control in the symbol cell.
                    var stc = document.createElement('td');
                    stc.className = 'txt';
                    stc.innerHTML = row.symbol +
                            ' <button class="wl-remove snapshot-remove" data-sym="' + row.symbol + '" title="Remove">×</button>';
                    tr.appendChild(stc);
                } else {
                    tr.appendChild(td(row.symbol, 'txt'));
                }
                tr.appendChild(td(fmtPrice(row.price, row.currency)));
                tr.appendChild(td(fmtPrice(row.targetMeanPrice, row.currency)));
                tr.appendChild(td(fmtRatio(row.trailingPe)));
                tr.appendChild(td(fmtRatio(row.forwardPe)));
                tr.appendChild(td(fmtRatio(row.extra ? row.extra['enterpriseToEbitda'] : null)));
                tr.appendChild(td(fmtRatio(row.pegRatio)));
                tr.appendChild(td(fmtRatio(row.beta)));
                tr.appendChild(td(fmtMarketCap(row.marketCap, row.currency)));
                tr.appendChild(td(fmtPrice(row.week52Low, row.currency)));
                tr.appendChild(td(fmtPrice(row.week52High, row.currency)));
                tr.appendChild(earningsTd(row));
                tr.addEventListener('click', function () { openSnapshotDetail(idx); });
                tbody.appendChild(tr);
            });
            tbody.querySelectorAll('.snapshot-remove').forEach(function (btn) {
                btn.addEventListener('click', function (e) {
                    e.stopPropagation();               // don't open the detail modal
                    removeSnapshotSymbol(this.dataset.sym);
                });
            });
            reapplyActiveSort(table);
        }

        // Re-sort tbody rows according to the THEAD's current dataset.sortDir indicator.
        // attachSort writes that marker on user click; calling this after a re-render keeps
        // the user's chosen ordering across background refreshes.
        function reapplyActiveSort(table) {
            var ths = Array.from(table.querySelectorAll('thead th[data-sort]'));
            var sortIdx = -1, sortAsc = true, sortType = 'txt';
            for (var i = 0; i < ths.length; i++) {
                if (ths[i].dataset.sortDir) {
                    sortIdx = i;
                    sortAsc = ths[i].dataset.sortDir === 'asc';
                    sortType = ths[i].dataset.sort;
                    break;
                }
            }
            if (sortIdx < 0) return;
            // Shares sortBody with the click path, so a restored ordering is group-aware too
            // rather than being the one place that flattens a grouped table.
            sortBody(table, sortIdx, sortType, sortAsc);
        }

        function td(text, cls) {
            var c = document.createElement('td');
            if (cls) c.className = cls;
            c.textContent = text == null ? '—' : text;
            if (text == null) c.classList.add('na');
            return c;
        }

        /**
         * Next-earnings cell. td() sorts on its rendered text, which for a date would order
         * alphabetically by month name, so the cell carries the day's own epoch instead.
         */
        function earningsTd(row) {
            var iso = (row.labels || {}).earningsDate;
            if (!iso) return td(null);
            var c = td('');
            c.innerHTML = fmtEarningsDate(iso, row);   // a past date carries its own markup
            c.dataset.sortValue = Date.parse(iso);
            return c;
        }

        function fmtRatio(v) {
            if (v == null) return null;
            var n = parseFloat(v);
            if (!isFinite(n)) return null;
            return n.toFixed(2);
        }

        function fmtPrice(v, ccy) {
            if (v == null) return null;
            var n = parseFloat(v);
            if (!isFinite(n)) return null;
            var sym = currencySymbol(ccy);
            return sym + n.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        function fmtMarketCap(v, ccy) {
            if (v == null) return null;
            var n = parseFloat(v);
            if (!isFinite(n)) return null;
            var sym = currencySymbol(ccy);
            // Yahoo's marketCap is in the listing currency in absolute units. Compact view.
            if (n >= 1e12) return sym + (n / 1e12).toFixed(2) + 'T';
            if (n >= 1e9)  return sym + (n / 1e9).toFixed(2) + 'B';
            if (n >= 1e6)  return sym + (n / 1e6).toFixed(1) + 'M';
            return sym + n.toLocaleString('en-GB');
        }

        function currencySymbol(ccy) {
            if (ccy === 'USD') return '$';
            if (ccy === 'GBP') return '£';
            if (ccy === 'GBp') return 'p';       // London pence quote
            if (ccy === 'EUR') return '€';
            return '';
        }

        // Single home for the fundamentals field grouping (label, type, tooltip, anchor).
        // Field tuple: [key, label, type, tooltip, noFinancial?, glossaryAnchor?, source?].
        // Group: { title, fields, noFinancial? } — noFinancial skips the whole group for
        // banks/insurers where EV/Net Debt/EBITDA/FCF don't apply.
        // `source` names which bag on the quoteSummary row holds the value: the default is the
        // numeric `extra` map, 'labels' the string one (see fundFieldValue).
        function fundamentalsFieldGroups() {
            return [
                { title: 'Earnings', fields: [
                    ['earningsDate', 'Next earnings', 'date', 'The next scheduled results date, from Yahoo\'s company calendar. A trailing ~ means Yahoo is estimating it from the past reporting rhythm rather than repeating an announcement, so it can still move by weeks; two dates are a window Yahoo has not narrowed to a single day. A date in muted italics has already been reported: Yahoo has published no forward date and this is the most recent one it knows. Funds and bonds have none.', false, null, 'labels']
                ]},
                { title: 'Valuation', fields: [
                    ['enterpriseToEbitda', 'EV/EBITDA', 'ratio', 'Enterprise Value ÷ EBITDA (TTM). Measures how expensive the business is relative to cash earnings before interest, taxes, depreciation and amortisation. Lower = cheaper; negative means EBITDA is negative.', true, 'ev-ebitda'],
                    ['priceToBook', 'P/B', 'ratio', 'Price ÷ Book Value per share. Compares market cap to net assets on the balance sheet. Below 1 may indicate undervaluation; most meaningful for asset-heavy businesses and financials.', false, 'pb'],
                    ['priceToSalesTrailing12Months', 'P/S (TTM)', 'ratio', 'Price ÷ Revenue per share (trailing 12 months). Useful for loss-making companies where P/E is unavailable. Lower values mean you are paying less per unit of revenue.', false, 'ps'],
                    ['enterpriseValue', 'Enterprise Value', 'cap', 'Market cap + total debt − cash. Represents the theoretical takeover price of the whole business, independent of capital structure.', true, 'enterprise-value']
                ]},
                { title: 'Cash Flow (TTM)', noFinancial: true, fields: [
                    ['operatingCashflow', 'Operating Cash Flow', 'cap', 'Cash generated by core business operations over the trailing 12 months, before investing or financing activities. A more reliable earnings proxy than net income for many businesses.', false, 'operating-cash-flow'],
                    ['capexTtm', 'Capex', 'cap', 'Capital expenditures — cash spent on property, plant and equipment. Includes both maintenance of existing assets and growth investment.', false, 'free-cash-flow'],
                    ['freeCashflow', 'Free Cash Flow', 'cap', 'Operating Cash Flow − Capex. The cash left after maintaining and growing the asset base; available for dividends, buybacks or debt repayment.', false, 'free-cash-flow'],
                    ['fcfMarginTtm', 'FCF Margin', 'pct', 'Free Cash Flow ÷ Revenue. Shows how much of each pound/dollar of revenue converts to free cash. Higher margins signal a capital-light, profitable model.', false, 'free-cash-flow'],
                    ['fcfGrowthYoy', 'FCF Growth YoY', 'pct', 'Year-over-year change in Free Cash Flow.', false, 'free-cash-flow']
                ]},
                { title: 'Quality & Returns', fields: [
                    ['roic', 'ROIC', 'pct', 'Return on Invested Capital. Net operating profit after tax ÷ invested capital (equity + debt). Measures how efficiently capital is deployed. Sustained ROIC above the cost of capital creates shareholder value.', true, 'roic'],
                    ['returnOnEquity', 'Return on Equity', 'pct', 'Net income ÷ shareholders\' equity. Measures profitability relative to what shareholders have invested. Can be inflated by high leverage, so compare alongside Debt/Equity.', false, 'roe'],
                    ['returnOnAssets', 'Return on Assets', 'pct', 'Net income ÷ total assets. How efficiently the company uses all its assets to generate profit, regardless of how those assets are financed.', false, 'roa'],
                    ['profitMargins', 'Profit Margin', 'pct', 'Net income ÷ Revenue. Bottom-line profitability after all costs, interest and taxes.', false, 'margins'],
                    ['operatingMargins', 'Operating Margin', 'pct', 'Operating income ÷ Revenue. Profitability from core operations before interest and taxes; strips out financing decisions.', false, 'margins'],
                    ['grossMargins', 'Gross Margin', 'pct', 'Gross profit ÷ Revenue. Revenue minus direct production costs. Reflects pricing power and production efficiency before overhead.', false, 'margins']
                ]},
                { title: 'Balance Sheet / Leverage', noFinancial: true, fields: [
                    ['netDebtToEbitda', 'Net Debt / EBITDA', 'ratio', 'Net Debt ÷ EBITDA. Number of years of operating earnings needed to repay net debt. Below 2× is conservative; above 4× signals high leverage. Negative = the company holds more cash than debt.', false, 'net-debt-ebitda'],
                    ['netDebt', 'Net Debt', 'cap', 'Total debt minus cash and cash equivalents. Negative means the company has a net cash position.', false, 'net-debt'],
                    ['debtToEquity', 'Debt / Equity', 'ratio', 'Total debt ÷ shareholders\' equity (as a ratio). Higher values mean the company relies more on debt financing, increasing financial risk.', false, 'debt-equity'],
                    ['totalCash', 'Total Cash', 'cap', 'Cash plus short-term investments held on the balance sheet.'],
                    ['totalDebt', 'Total Debt', 'cap', 'Total short- and long-term debt obligations.']
                ]},
                { title: 'Income (TTM)', fields: [
                    ['totalRevenue', 'Revenue', 'cap', 'Total sales over the trailing 12 months.'],
                    ['ebitda', 'EBITDA', 'cap', 'Earnings Before Interest, Taxes, Depreciation and Amortisation — a proxy for operating cash generation that strips out capital structure and accounting choices.', true, 'ebitda'],
                    ['revenueGrowth', 'Revenue Growth', 'pct', 'Year-over-year revenue growth rate.'],
                    ['earningsGrowth', 'Earnings Growth', 'pct', 'Year-over-year earnings growth rate.']
                ]},
                { title: 'Dividends', fields: [
                    ['dividendYield', 'Dividend Yield', 'pct', 'Annual dividend per share ÷ current share price. The income return from dividends alone, before capital gains or losses.', false, 'dividend-yield'],
                    ['dividendRate', 'Dividend Rate', 'price', 'Annual dividend per share in the stock\'s native currency.'],
                    ['payoutRatio', 'Payout Ratio', 'pct', 'Dividends ÷ net income. Above 100% means the company is paying out more than it earns — may be unsustainable without debt or asset sales.', false, 'payout-ratio']
                ]},
                { title: 'Performance & Technicals', fields: [
                    ['52WeekChange', '52w Change', 'pct', 'Share price percentage change over the past 52 weeks.'],
                    ['SandP52WeekChange', 'S&P 52w Change', 'pct', 'S&P 500 percentage change over the same 52-week window — a benchmark for comparing this stock\'s relative performance.'],
                    ['fiftyDayAverage', '50d MA', 'price', '50-day moving average of the closing price. Short-term trend indicator; price above it is often read as near-term momentum.', false, 'moving-averages'],
                    ['twoHundredDayAverage', '200d MA', 'price', '200-day moving average of the closing price. Long-term trend indicator; price above the 200d MA is widely seen as a bullish signal.', false, 'moving-averages']
                ]},
                { title: 'Sentiment / Float', fields: [
                    ['shortPercentOfFloat', 'Short % of Float', 'pct', 'Freely tradeable shares currently sold short as a percentage of the float. High short interest signals bearish sentiment; a sharp price rise can force a short squeeze.', false, 'short-float'],
                    ['heldPercentInstitutions', '% Held by Institutions', 'pct', 'Proportion of shares owned by institutions (funds, pension managers, etc.). High institutional ownership may signal validation but also concentration risk if they sell together.', false, 'institutional-ownership'],
                    ['numberOfAnalystOpinions', '# Analyst Opinions', 'count', 'Number of analyst ratings contributing to the consensus recommendation shown in the subtitle.'],
                    ['sharesOutstanding', 'Shares Outstanding', 'cap', 'Total shares in existence, including restricted shares. Used to compute market cap and per-share metrics.']
                ]}
            ];
        }

        // Shared fundamentals renderer — grouped, human-labelled key/value HTML for one
        // quoteSummary row. Used by both the Snapshot-diff detail modal and the Watchlist popup
        // (the latter wraps the returned groups in a multi-column layout).
        function fundamentalsHtml(row, isFinancial) {
            var groups = fundamentalsFieldGroups();
            var html = '';
            groups.forEach(function (g) {
                if (isFinancial && g.noFinancial) return;
                var inner = '';
                g.fields.forEach(function (f) {
                    var key = f[0], label = f[1], type = f[2], tip = f[3], noFin = f[4], anchor = f[5];
                    if (isFinancial && noFin) return;
                    var v = fundFieldValue(row, key, f[6]);
                    if (v == null) return;
                    var anchorAttr = anchor ? ' data-anchor="' + anchor + '"' : '';
                    var labelHtml = tip
                        ? '<span class="snap-tip" data-tip="' + tip.replace(/"/g, '&quot;') + '"' + anchorAttr + '>' + label + '</span>'
                        : label;
                    inner += '<div class="snap-kv"><span class="k">' + labelHtml + '</span>' +
                            '<span class="v">' + fmtSnapValue(v, type, row.currency, row) + '</span></div>';
                });
                if (inner) {
                    html += '<div class="snap-group"><div class="snap-group-title">' + g.title +
                            '</div>' + inner + '</div>';
                }
            });
            return html || '<p class="muted">No secondary fields available for this ticker.</p>';
        }

        function openSnapshotDetail(idx) {
            var row = snapshotRowsCache[idx];
            if (!row) return;
            document.getElementById('snapshot-detail-title').textContent =
                    row.symbol + ' — fundamentals';
            var sector = (row.labels && row.labels.sector) || '';
            var industry = (row.labels && row.labels.industry) || '';
            var rec = (row.labels && row.labels.recommendationKey) || '';
            var subParts = [];
            if (sector) subParts.push(sector);
            if (industry) subParts.push(industry);
            if (rec) subParts.push('Analyst consensus: ' + rec);
            document.getElementById('snapshot-detail-sub').textContent = subParts.join(' · ');

            // Financial-sector companies (banks, insurers, asset managers) use debt as their
            // product and hold huge investment portfolios as assets, which breaks Yahoo's EV,
            // Net Debt, EBITDA and FCF calculations — suppress those groups/fields for them.
            var isFinancial = /financial|insurance|bank/i.test(sector);

            // Group secondary fields by theme so the modal reads as a compact summary, not a
            // wall of key=value pairs. Each group's rows are skipped entirely when every
            // field is null or suppressed — so an ETF without dividends or analyst coverage
            // doesn't get empty "—" boxes.
            // Field tuple: [key, label, type, tooltip, noFinancial?, glossaryAnchor?]
            // Group: { title, fields, noFinancial? } — noFinancial skips the whole group.
            document.getElementById('snapshot-detail-body').innerHTML = fundamentalsHtml(row, isFinancial);
            document.getElementById('snapshot-detail-modal').classList.add('open');
        }

        /**
         * One field's raw value off a quoteSummary row. Numbers live in the `extra` map; a field
         * the feed publishes as text — the earnings date — rides in `labels`, the same flat blob
         * sector and industry already use, so it needed neither a column of its own nor a
         * migration. Blank is treated as absent: a field Yahoo answered with "" is not a value.
         */
        function fundFieldValue(row, key, source) {
            if (!row) return null;
            var bag = source === 'labels' ? row.labels : row.extra;
            var v = bag ? bag[key] : null;
            return (v == null || v === '') ? null : v;
        }

        /** Today as an ISO day, in the viewer's own zone — the basis every stored day is judged on. */
        function todayIso() {
            var d = new Date();
            return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' +
                String(d.getDate()).padStart(2, '0');
        }

        /**
         * An ISO day as a readable date. Anchored at local noon — the same trick wlParseTs uses —
         * so a date-only value can't slip a day for a browser sitting west of UTC.
         */
        function fmtDayLabel(iso) {
            var d = new Date(iso + 'T12:00:00');
            return isNaN(d) ? iso
                : d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
        }

        /**
         * The next earnings date as one cell. Three things ride along with the day itself and all
         * three change what it means, so none is dropped:
         *  - a window Yahoo has not narrowed to a single day prints both ends;
         *  - a day Yahoo inferred from the past reporting rhythm, rather than one the company
         *    announced, carries a trailing '~' — the same "not confirmed" marker the popup's
         *    high/low cells use for a close-only extreme. An estimated date can move by weeks,
         *    and printing it as though it were fixed is the same mistake as reporting a figure
         *    we could not value;
         *  - a day that has already gone is set in muted italics. When Yahoo has no forward date
         *    it publishes the most recent one it knows — 7 of the 58 held names carrying a date
         *    on the day this shipped, UK reporters especially — and printing that under "Next
         *    earnings" in the same face as a forward date states something false. It is still
         *    stored exactly as the feed gave it: what Yahoo knows is the fact, what the column
         *    claims about it is ours. The date itself stays rather than being blanked, because
         *    "reported in August, nothing scheduled since" and "this name never reports" are two
         *    different facts and a blank cell would tell them apart from neither. The styling
         *    replaces the estimate marker rather than joining it: a date already behind us is not
         *    a date whose firmness is still the question.
         *
         * Returns HTML, not text — `earningsTd` sets it as innerHTML for that reason.
         */
        function fmtEarningsDate(iso, row) {
            var labels = (row && row.labels) || {};
            var end = labels.earningsDateEnd;
            var text;
            if (end && end !== iso) {
                // "24 Feb – 1 Mar 2026": the year is printed once when both ends share it, and
                // dropped from the start by formatting without it rather than by trimming the
                // rendered string, which is locale-dependent.
                var startDate = new Date(iso + 'T12:00:00');
                text = (end.slice(0, 4) === iso.slice(0, 4) && !isNaN(startDate)
                        ? startDate.toLocaleDateString([], { day: 'numeric', month: 'short' })
                        : fmtDayLabel(iso))
                    + ' – ' + fmtDayLabel(end);
            } else {
                text = fmtDayLabel(iso);
            }
            if ((end || iso) < todayIso()) {
                return '<span class="earn-past" title="Already reported — Yahoo has published no ' +
                    'forward date, so this is the most recent one it knows">' + text + '</span>';
            }
            return labels.earningsDateEstimate === 'true' ? text + '~' : text;
        }

        function fmtSnapValue(v, type, ccy, row) {
            // A date is not a number, so this branch has to precede the numeric guard below —
            // which would otherwise reject every ISO day as '—'.
            if (type === 'date') return fmtEarningsDate(v, row);
            var n = parseFloat(v);
            if (!isFinite(n)) return '—';
            if (type === 'pct') return (n * 100).toFixed(2) + '%';
            if (type === 'ratio') return n.toFixed(2);
            if (type === 'count') return n.toFixed(0);
            if (type === 'price') return fmtPrice(v, ccy);
            if (type === 'cap') return fmtMarketCap(v, ccy);
            return n.toString();
        }

        function closeSnapshotDetail() {
            document.getElementById('snapshot-detail-modal').classList.remove('open');
        }

        var riskLoaded = false;
        var riskVolChart = null, riskDdChart = null, riskHistChart = null;

        function loadRisk() {
            if (riskLoaded) return;
            riskLoaded = true;
            bindOnce(document.getElementById('risk-rf-apply'), 'click', applyRiskFreeRate);
            fetchAndRenderRisk();
        }

        function fetchAndRenderRisk() {
            clearPanelError('panel-risk');
            return fetch('/risk').then(function (r) { return r.json(); }).then(renderRisk)
                    .catch(function (e) {
                        riskLoaded = false;
                        panelLoadFailed('panel-risk', e, fetchAndRenderRisk);
                    });
        }

        function applyRiskFreeRate() {
            var pct = document.getElementById('risk-rf-input').value;
            var status = document.getElementById('risk-rf-status');
            status.textContent = 'Saving…';
            var body = new URLSearchParams();
            body.set('rate', pct);
            fetch('/risk/rate', { method: 'POST', body: body })
                    .then(function (r) {
                        if (!r.ok) return r.text().then(function (msg) {
                            throw new Error(msg || 'save failed');
                        });
                        status.textContent = 'Saved.';
                        fetchAndRenderRisk();
                    })
                    .catch(function (e) { status.textContent = 'Error: ' + e.message; });
        }

        function renderRisk(data) {
            var s = data.summary || {};
            var rfInput = document.getElementById('risk-rf-input');
            if (s.riskFreeRate != null && document.activeElement !== rfInput) {
                rfInput.value = (parseFloat(s.riskFreeRate) * 100).toFixed(2);
            }
            setRiskPct('risk-vol', s.volAnnualized);
            setRiskPct('risk-vol-1y', s.vol1y);
            setRiskRatio('risk-sharpe', s.sharpe);
            setRiskRatio('risk-sharpe-1y', s.sharpe1y);
            setRiskRatio('risk-sortino', s.sortino);
            setRiskPct('risk-maxdd', s.maxDrawdown);
            document.getElementById('risk-maxdd-date').textContent = s.maxDrawdownDate || '';
            var rec = document.getElementById('risk-recovery');
            if (s.recoveryDays != null) {
                rec.textContent = s.recoveryDays;
                rec.className = 'value';
            } else {
                rec.textContent = s.maxDrawdownDate ? 'ongoing' : '—';
                rec.className = 'value na';
            }
            setRiskRatio('risk-calmar', s.calmar);

            renderRiskVolChart(data.rollingVol || []);
            renderRiskDdChart(data.drawdownPoints || []);
            renderRiskHistChart(data.dailyReturnHistogram || []);
        }

        function setRiskPct(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            var n = parseFloat(v) * 100;
            el.textContent = (n >= 0 ? '' : '') + n.toFixed(2) + '%';
            el.className = 'value';
        }

        function setRiskRatio(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = parseFloat(v).toFixed(2);
            el.className = 'value';
        }

        function renderRiskVolChart(points) {
            if (riskVolChart) riskVolChart.destroy();
            var data = points.map(function (p) {
                return { x: p.date, y: parseFloat(p.annualizedVol) * 100 };
            });
            riskVolChart = new Chart(
                document.getElementById('risk-vol-chart').getContext('2d'), {
                    type: 'line',
                    data: { datasets: [{
                        label: 'Annualised vol (63d, weekday)',
                        data: data,
                        borderColor: '#9467bd',
                        backgroundColor: 'rgba(148,103,189,0.12)',
                        fill: true,
                        pointRadius: 0,
                        borderWidth: 1.5,
                        tension: 0.1
                    }]},
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: {
                                beginAtZero: true,
                                title: { display: true, text: 'Vol (%)' },
                                ticks: { callback: function (v) { return v.toFixed(1) + '%'; } }
                            }
                        },
                        plugins: {
                            legend: { display: false },
                            tooltip: { callbacks: { label: function (c) {
                                return c.parsed.y.toFixed(2) + '%';
                            }}}
                        }
                    }
                });
        }

        function renderRiskDdChart(points) {
            if (riskDdChart) riskDdChart.destroy();
            var data = points.map(function (p) {
                return { x: p.date, y: parseFloat(p.drawdown) * 100 };
            });
            riskDdChart = new Chart(
                document.getElementById('risk-dd-chart').getContext('2d'), {
                    type: 'line',
                    data: { datasets: [{
                        label: 'Drawdown',
                        data: data,
                        borderColor: '#d62728',
                        backgroundColor: 'rgba(214,39,40,0.18)',
                        fill: true,
                        pointRadius: 0,
                        borderWidth: 1.5,
                        tension: 0.1
                    }]},
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: {
                                suggestedMax: 0,
                                title: { display: true, text: 'Drawdown (%)' },
                                ticks: { callback: function (v) { return v.toFixed(0) + '%'; } }
                            }
                        },
                        plugins: {
                            legend: { display: false },
                            tooltip: { callbacks: { label: function (c) {
                                return c.parsed.y.toFixed(2) + '%';
                            }}}
                        }
                    }
                });
        }

        function renderRiskHistChart(buckets) {
            if (riskHistChart) riskHistChart.destroy();
            // Colour negatives red, zero-ish grey, positives green so the skew is visible.
            var colours = buckets.map(function (b) {
                var label = b.label;
                if (label.indexOf('-') === 0 || label === '≤-5%') return 'rgba(214,39,40,0.7)';
                if (label === '+0.0%') return 'rgba(140,140,140,0.7)';
                return 'rgba(44,160,44,0.7)';
            });
            riskHistChart = new Chart(
                document.getElementById('risk-hist-chart').getContext('2d'), {
                    type: 'bar',
                    data: {
                        labels: buckets.map(function (b) { return b.label; }),
                        datasets: [{
                            label: 'Trading days',
                            data: buckets.map(function (b) { return b.count; }),
                            backgroundColor: colours,
                            borderWidth: 0
                        }]
                    },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        scales: {
                            y: { beginAtZero: true,
                                 title: { display: true, text: 'Trading days' } },
                            x: { ticks: { maxRotation: 60, minRotation: 60, autoSkip: false,
                                          font: { size: 10 } } }
                        },
                        plugins: { legend: { display: false } }
                    }
                });
        }

        var dividendsLoaded = false;
        var divAnnualChart = null;

        function loadDividends() {
            if (dividendsLoaded) return;
            dividendsLoaded = true;
            clearPanelError('panel-dividends');
            fetch('/dividends').then(function (r) { return r.json(); }).then(renderDividends)
                    .catch(function (e) {
                        dividendsLoaded = false;
                        panelLoadFailed('panel-dividends', e, loadDividends);
                    });
            fetch('/dividends/audit').then(function (r) { return r.json(); }).then(renderDividendAudit)
                    .catch(function (e) {
                        dividendsLoaded = false;
                        panelLoadFailed('panel-dividends', e, loadDividends);
                    });
        }

        function renderDividendAudit(report) {
            var summary = document.getElementById('div-audit-summary');
            summary.textContent = report.symbols + ' symbols — raw £' +
                    parseFloat(report.totalRawGbp).toLocaleString('en-GB', { maximumFractionDigits: 0 }) +
                    ', attributed £' +
                    parseFloat(report.totalAttributedGbp).toLocaleString('en-GB', { maximumFractionDigits: 0 }) +
                    ', leak £' +
                    parseFloat(report.totalLeakGbp).toLocaleString('en-GB', { maximumFractionDigits: 0 }) +
                    (report.sharesMismatchCount > 0
                        ? ' — ⚠ ' + report.sharesMismatchCount + ' share-count mismatches'
                        : ' — share counts agree across all symbols');
            var tbody = document.querySelector('#div-audit-table tbody');
            tbody.innerHTML = '';
            (report.rows || []).forEach(function (r) {
                var tr = document.createElement('tr');
                var status = r.sharesMismatch
                        ? '<span class="neg">⚠ mismatch</span>'
                        : (parseFloat(r.leakGbp) > 0.01
                            ? '<span class="muted">leak from sells</span>'
                            : '<span class="pos">clean</span>');
                tr.innerHTML =
                    '<td class="txt"><b>' + r.symbol + '</b></td>' +
                    '<td>' + fmtMoney0(r.rawGbp) + '</td>' +
                    '<td>' + fmtMoney0(r.attributedGbp) + '</td>' +
                    '<td>' + fmtMoney0(r.leakGbp) + '</td>' +
                    '<td>' + fmtNumber(r.attributorShares, 4) + '</td>' +
                    '<td>' + fmtNumber(r.ledgerShares, 4) + '</td>' +
                    '<td class="txt">' + status + '</td>';
                tbody.appendChild(tr);
            });
            attachSortOnce('div-audit-table');
        }

        function renderDividends(data) {
            var s = data.summary || {};
            setMoney('div-lifetime', s.lifetimeIncomeGbp);
            setMoney('div-trailing', s.trailing12mIncomeGbp);
            setMoney('div-forward', s.forwardIncomeGbp);
            setDivPct('div-yoc', s.portfolioYieldOnCost);
            setDivPct('div-yield', s.portfolioTrailingYield);

            renderDivAnnualChart(data.annual || [], data.stackedSymbolOrder || []);
            renderDivTable(data.rows || []);
        }

        function setMoney(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            var n = parseFloat(v);
            el.textContent = '£' + n.toLocaleString('en-GB',
                    { minimumFractionDigits: 0, maximumFractionDigits: 0 });
            el.className = 'value';
        }

        function setDivPct(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = (parseFloat(v) * 100).toFixed(2) + '%';
            el.className = 'value';
        }

        // Palette mirrors Chart.js v4 defaults — readable, distinct, no two adjacent
        // bands the same colour. "Other" is grey so it never out-competes a named symbol.
        var DIV_PALETTE = [
            '#1f77b4', '#ff7f0e', '#2ca02c', '#d62728',
            '#9467bd', '#8c564b', '#e377c2', '#17becf'
        ];

        function divColour(label, idx) {
            if (label === 'Other') return 'rgba(140,140,140,0.7)';
            return DIV_PALETTE[idx % DIV_PALETTE.length];
        }

        function renderDivAnnualChart(annual, topSymbols) {
            if (divAnnualChart) divAnnualChart.destroy();
            // One Chart.js dataset per band: each top symbol + "Other". Pull each year's
            // per-symbol value out of annual[i].perSymbolGbp, defaulting to 0 so the bar
            // segment is flat-zero rather than missing.
            var labels = annual.map(function (a) { return String(a.year); });
            var bands = topSymbols.slice();
            var anyOther = annual.some(function (a) {
                return a.perSymbolGbp && a.perSymbolGbp['Other'] != null;
            });
            if (anyOther) bands.push('Other');
            var datasets = bands.map(function (sym, idx) {
                return {
                    label: sym,
                    data: annual.map(function (a) {
                        var v = a.perSymbolGbp && a.perSymbolGbp[sym];
                        return v == null ? 0 : parseFloat(v);
                    }),
                    backgroundColor: divColour(sym, idx),
                    stack: 's'
                };
            });
            divAnnualChart = new Chart(
                document.getElementById('div-annual-chart').getContext('2d'), {
                    type: 'bar',
                    data: { labels: labels, datasets: datasets },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        scales: {
                            x: { stacked: true },
                            y: { stacked: true,
                                 title: { display: true, text: 'Dividend income (£)' },
                                 ticks: { callback: function (v) { return '£' + v.toLocaleString('en-GB'); } } }
                        },
                        plugins: {
                            legend: { position: 'right' },
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return ctx.dataset.label + ': £' +
                                                ctx.parsed.y.toLocaleString('en-GB',
                                                        { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                    },
                                    footer: function (items) {
                                        var sum = items.reduce(function (a, b) { return a + b.parsed.y; }, 0);
                                        return 'Year total: £' + sum.toLocaleString('en-GB',
                                                { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                    }
                                }
                            }
                        }
                    }
                });
        }

        function renderDivTable(rows) {
            var tbody = document.querySelector('#div-table tbody');
            tbody.innerHTML = '';
            rows.forEach(function (r) {
                var tr = document.createElement('tr');
                if (!r.currentlyHeld) tr.style.opacity = '0.55';
                tr.innerHTML =
                    '<td class="txt"><b>' + r.symbol + '</b>' +
                        (r.currentlyHeld ? '' : ' <span class="muted">(closed)</span>') + '</td>' +
                    '<td>' + (r.currentlyHeld ? fmtShares(r.shares) : '—') + '</td>' +
                    '<td>' + (r.currentlyHeld ? fmtMoney0(r.costBasisGbp) : '—') + '</td>' +
                    '<td>' + fmtMoney0(r.marketValueGbp) + '</td>' +
                    '<td>' + fmtMoney0(r.lifetimeGbp) + '</td>' +
                    '<td>' + fmtMoney0(r.trailingIncomeGbp) + '</td>' +
                    '<td>' + fmtYield(r.yieldOnCost) + '</td>' +
                    '<td>' + fmtYield(r.trailingYield) + '</td>' +
                    '<td class="txt muted">' + (r.accounts || '') + '</td>';
                tbody.appendChild(tr);
            });
            // attachSort exists already on the page for the other sortable tables.
            if (!document.getElementById('div-table').dataset.sortAttached) {
                document.getElementById('div-table').dataset.sortAttached = '1';
                attachSort(document.getElementById('div-table'));
            }
        }

        function fmtShares(v) {
            if (v == null) return '—';
            var n = parseFloat(v);
            return n.toLocaleString('en-GB',
                    { minimumFractionDigits: 0, maximumFractionDigits: 4 });
        }

        function fmtMoney0(v) {
            if (v == null) return '—';
            return '£' + parseFloat(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 0, maximumFractionDigits: 0 });
        }

        function fmtYield(v) {
            if (v == null) return '—';
            return (parseFloat(v) * 100).toFixed(2) + '%';
        }

        var positionInitialized = false;
        var positionChart = null;

        function setupPosition() {
            if (positionInitialized) return;
            positionInitialized = true;
            // Populate dropdown from the same /returns/symbols endpoint the Returns tab uses.
            fetch('/returns/symbols').then(function (r) { return r.json(); })
                    .then(function (symbols) {
                        var sel = document.getElementById('position-select');
                        symbols.forEach(function (s) {
                            var opt = document.createElement('option');
                            opt.value = s;
                            opt.textContent = s;
                            sel.appendChild(opt);
                        });
                    });
            document.getElementById('position-select')
                    .addEventListener('change', function (ev) {
                        var sym = ev.target.value;
                        if (!sym) {
                            document.getElementById('position-empty').style.display = '';
                            document.getElementById('position-content').style.display = 'none';
                            return;
                        }
                        loadPosition(sym);
                    });
        }

        function loadPosition(symbol) {
            var status = document.getElementById('position-status');
            status.textContent = 'Loading…';
            fetch('/position?symbol=' + encodeURIComponent(symbol))
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                        status.textContent = '';
                        renderPosition(data);
                    })
                    .catch(function (e) { status.textContent = 'Error: ' + e.message; });
        }

        function renderPosition(d) {
            document.getElementById('position-empty').style.display = 'none';
            document.getElementById('position-content').style.display = '';
            var s = d.summary || {};
            document.getElementById('pos-shares').textContent =
                    s.shares != null ? parseFloat(s.shares).toLocaleString('en-GB',
                            { maximumFractionDigits: 4 }) : '—';
            setMoney('pos-cost', s.costBasisGbp);
            setMoney('pos-mv', s.marketValueGbp);
            setSignedMoney('pos-realized', s.realizedGbp);
            setSignedMoney('pos-unrealized', s.unrealizedGbp);
            setMoney('pos-divs', s.dividendsGbp);
            setSignedMoney('pos-total', s.totalReturnGbp);

            renderPositionChart(d);
            renderPositionTables(d);

            var warn = document.getElementById('position-missing-prices');
            if (d.missingPriceHistory) {
                warn.textContent = 'No price_history rows for this symbol — chart will be empty.';
                warn.style.display = '';
            } else {
                warn.style.display = 'none';
            }
        }

        function setSignedMoney(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            var n = parseFloat(v);
            var sign = n >= 0 ? '+' : '−';
            el.textContent = sign + '£' + Math.abs(n).toLocaleString('en-GB',
                    { minimumFractionDigits: 0, maximumFractionDigits: 0 });
            el.className = 'value' + (n >= 0 ? ' pos' : ' neg');
        }

        function renderPositionChart(d) {
            if (positionChart) positionChart.destroy();
            var priceSeries = (d.priceHistory || []).map(function (p) {
                return { x: p.date, y: parseFloat(p.price) };
            });
            // Split markers into 3 datasets so each gets its own colour + shape.
            var byKind = { buy: [], sell: [], div: [] };
            (d.markers || []).forEach(function (m) {
                byKind[m.kind] = byKind[m.kind] || [];
                byKind[m.kind].push({
                    x: m.date,
                    y: parseFloat(m.priceAtMarker),
                    shares: parseFloat(m.shares || 0),
                    gbp: parseFloat(m.gbpAmount || 0),
                    account: m.account
                });
            });
            var datasets = [
                {
                    label: 'Price',
                    data: priceSeries,
                    type: 'line',
                    borderColor: '#1f77b4',
                    backgroundColor: 'rgba(31,119,180,0.08)',
                    borderWidth: 1.4,
                    pointRadius: 0,
                    tension: 0.1,
                    fill: false,
                    order: 1
                },
                markerDataset('Buy', byKind.buy, '#2ca02c', 'triangle', 8, 2),
                markerDataset('Sell', byKind.sell, '#d62728', 'triangle', 8, 2),
                markerDataset('Dividend', byKind.div, '#1f77b4', 'circle', 5, 2)
            ];

            positionChart = new Chart(
                document.getElementById('position-chart').getContext('2d'), {
                    type: 'line',
                    data: { datasets: datasets },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: {
                                title: { display: true, text: 'Price (' + (d.summary.currentPriceCurrency || 'native') + ')' }
                            }
                        },
                        plugins: {
                            legend: { position: 'bottom' },
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        var ds = ctx.dataset.label;
                                        var raw = ctx.raw;
                                        if (ds === 'Price') {
                                            return 'Price: ' + ctx.parsed.y.toFixed(4);
                                        }
                                        // Marker datasets carry shares/gbp/account.
                                        var gbpStr = '£' + raw.gbp.toLocaleString('en-GB',
                                                { minimumFractionDigits: 2, maximumFractionDigits: 2 });
                                        if (ds === 'Dividend') {
                                            return ds + ': ' + gbpStr + ' (' + raw.account + ')';
                                        }
                                        return ds + ': ' + raw.shares.toLocaleString('en-GB',
                                                { maximumFractionDigits: 4 }) + ' shares for ' +
                                                gbpStr + ' (' + raw.account + ')';
                                    }
                                }
                            }
                        }
                    }
                });
        }

        // Sell triangles point down by rotating Chart.js's "triangle" 180°.
        function markerDataset(label, points, colour, style, radius, borderWidth) {
            return {
                label: label,
                data: points,
                type: 'scatter',
                pointStyle: style,
                pointRotation: label === 'Sell' ? 180 : 0,
                pointRadius: radius,
                pointHoverRadius: radius + 2,
                borderColor: colour,
                backgroundColor: colour,
                borderWidth: borderWidth,
                showLine: false,
                order: 0
            };
        }

        function renderPositionTables(d) {
            var openBody = document.querySelector('#position-open-table tbody');
            openBody.innerHTML = '';
            (d.openLots || []).forEach(function (l) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td class="txt">' + l.openDate + '</td>' +
                    '<td>' + fmtNumber(l.shares, 4) + '</td>' +
                    '<td>' + fmtNativePrice(l.costPerShareNative, l.costCurrency) + '</td>' +
                    '<td>' + fmtMoney2(l.costGbp) + '</td>' +
                    '<td class="txt muted">' + l.account + '</td>';
                openBody.appendChild(tr);
            });
            attachSortOnce('position-open-table');

            var closedBody = document.querySelector('#position-closed-table tbody');
            closedBody.innerHTML = '';
            (d.closedLots || []).forEach(function (l) {
                var tr = document.createElement('tr');
                var realized = parseFloat(l.realizedGbp);
                tr.innerHTML =
                    '<td class="txt">' + l.openDate + '</td>' +
                    '<td class="txt">' + l.closeDate + '</td>' +
                    '<td>' + fmtNumber(l.shares, 4) + '</td>' +
                    '<td>' + fmtMoney2(l.costGbp) + '</td>' +
                    '<td>' + fmtMoney2(l.proceedsGbp) + '</td>' +
                    '<td class="' + (realized >= 0 ? 'pos' : 'neg') + '">' + fmtSignedMoney(realized) + '</td>' +
                    '<td class="txt muted">' + l.account + '</td>';
                closedBody.appendChild(tr);
            });
            attachSortOnce('position-closed-table');

            var divBody = document.querySelector('#position-div-table tbody');
            divBody.innerHTML = '';
            (d.dividends || []).forEach(function (p) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td class="txt">' + p.date + '</td>' +
                    '<td>' + fmtMoney2(p.amountGbp) + '</td>' +
                    '<td class="txt muted">' + p.account + '</td>';
                divBody.appendChild(tr);
            });
            attachSortOnce('position-div-table');
        }

        function attachSortOnce(id) {
            var t = document.getElementById(id);
            if (t.dataset.sortAttached) return;
            t.dataset.sortAttached = '1';
            attachSort(t);
        }

        function fmtNumber(v, digits) {
            if (v == null) return '—';
            return parseFloat(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 0, maximumFractionDigits: digits });
        }

        function fmtMoney2(v) {
            if (v == null) return '—';
            return '£' + parseFloat(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        var NATIVE_CCY_SYMBOL = { GBP: '£', USD: '$', EUR: '€' };

        function fmtNativePrice(v, ccy) {
            if (v == null) return '—';
            var n = parseFloat(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 4 });
            var sym = NATIVE_CCY_SYMBOL[ccy];
            if (sym) return sym + n;
            return ccy ? (n + ' ' + ccy) : n;
        }

        function fmtSignedMoney(n) {
            if (n == null || isNaN(n)) return '—';
            var sign = n >= 0 ? '+' : '−';
            return sign + '£' + Math.abs(n).toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        var concentrationLoaded = false;
        var concSymbolChart = null, concAccountChart = null,
                concClassChart = null, concTrendChart = null;

        function loadConcentration() {
            if (concentrationLoaded) return;
            concentrationLoaded = true;
            clearPanelError('alloc-view-concentration');
            fetch('/concentration').then(function (r) { return r.json(); })
                    .then(renderConcentration)
                    .catch(function (e) {
                        concentrationLoaded = false;
                        panelLoadFailed('alloc-view-concentration', e, loadConcentration);
                    });
        }

        function renderConcentration(d) {
            var s = d.snapshot || {};
            setConcRatio('conc-hhi', s.hhi);
            setConcN('conc-effn', s.effectiveN);
            var countEl = document.getElementById('conc-count');
            countEl.textContent = s.positionCount != null ? s.positionCount : '—';
            countEl.className = 'value' + (s.positionCount ? '' : ' na');

            setDivPct('conc-top1', s.top1Share);
            setDivPct('conc-top3', s.top3Share);
            setDivPct('conc-top5', s.top5Share);
            setDivPct('conc-top10', s.top10Share);
            setDivPct('conc-cash', s.cashShare);

            renderConcSymbolChart(s.bySymbol || []);
            renderConcSliceChart('conc-account-chart', s.byAccount || [], 'account', false);
            renderConcSliceChart('conc-class-chart', s.byAssetClass || [], 'kind', true);
            renderConcTrendChart(d.trend || []);
        }

        function setConcRatio(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = parseFloat(v).toFixed(3);
            el.className = 'value';
        }

        function setConcN(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = parseFloat(v).toFixed(1);
            el.className = 'value';
        }

        // Palette mirrors the dividends tab — keep tabs consistent so users learn the
        // colour rotation once.
        var CONC_PALETTE = [
            '#1f77b4', '#ff7f0e', '#2ca02c', '#d62728',
            '#9467bd', '#8c564b', '#e377c2', '#17becf',
            '#bcbd22', '#7f7f7f'
        ];

        function renderConcSymbolChart(bySymbol) {
            if (concSymbolChart) concSymbolChart.destroy();
            // Dynamically size the canvas height so every bar gets ~24px regardless of
            // how many symbols there are. Without this a 50-position list squashes into
            // an unreadable strip.
            var wrap = document.getElementById('conc-symbol-wrap');
            var rowHeight = 22;
            var height = Math.max(160, 60 + bySymbol.length * rowHeight);
            wrap.style.height = height + 'px';

            var labels = bySymbol.map(function (s) { return s.symbol; });
            var weights = bySymbol.map(function (s) {
                return parseFloat(s.weight) * 100;
            });
            var gbpValues = bySymbol.map(function (s) { return parseFloat(s.gbp); });
            concSymbolChart = new Chart(
                document.getElementById('conc-symbol-chart').getContext('2d'), {
                    type: 'bar',
                    data: {
                        labels: labels,
                        datasets: [{
                            label: 'Weight (% of invested)',
                            data: weights,
                            backgroundColor: weights.map(function (w, i) {
                                // Hottest bars get the colour palette's lead; rest fade
                                // toward grey to draw the eye to the top concentrators.
                                if (i < 8) return CONC_PALETTE[i];
                                return 'rgba(140,140,140,0.55)';
                            })
                        }]
                    },
                    options: {
                        indexAxis: 'y',
                        responsive: true,
                        maintainAspectRatio: false,
                        scales: {
                            x: {
                                title: { display: true, text: 'Weight (%)' },
                                ticks: { callback: function (v) { return v.toFixed(1) + '%'; } }
                            },
                            y: { ticks: { font: { size: 11 }, autoSkip: false } }
                        },
                        plugins: {
                            legend: { display: false },
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        var gbp = gbpValues[ctx.dataIndex];
                                        return ctx.parsed.x.toFixed(2) + '%  (£' +
                                                gbp.toLocaleString('en-GB',
                                                        { maximumFractionDigits: 0 }) + ')';
                                    }
                                }
                            }
                        }
                    }
                });
        }

        function renderConcSliceChart(canvasId, slices, labelKey, fixedColours) {
            // Tiny doughnut + a separate inline legend table would be nicer, but a bar
            // chart with a fixed colour rotation is far simpler and matches the rest of
            // the dashboard's visual language.
            var labels = slices.map(function (s) { return s[labelKey]; });
            var weights = slices.map(function (s) {
                return parseFloat(s.weight) * 100;
            });
            var gbp = slices.map(function (s) { return parseFloat(s.gbp); });
            var colours;
            if (fixedColours) {
                // Asset class: equities green, bonds amber, cash grey — universal mental model.
                var byKind = { 'Equities': '#2ca02c', 'Bonds': '#ff9f40', 'Cash': '#9e9e9e' };
                colours = labels.map(function (l) { return byKind[l] || '#9467bd'; });
            } else {
                colours = labels.map(function (_, i) { return CONC_PALETTE[i % CONC_PALETTE.length]; });
            }

            var existing = chartByCanvas(canvasId);
            if (existing) existing.destroy();
            var chart = new Chart(
                document.getElementById(canvasId).getContext('2d'), {
                    type: 'doughnut',
                    data: {
                        labels: labels,
                        datasets: [{
                            data: weights,
                            backgroundColor: colours,
                            borderWidth: 0
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        plugins: {
                            legend: { position: 'right' },
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return ctx.label + ': ' +
                                                ctx.parsed.toFixed(1) + '%  (£' +
                                                gbp[ctx.dataIndex].toLocaleString('en-GB',
                                                        { maximumFractionDigits: 0 }) + ')';
                                    }
                                }
                            }
                        }
                    }
                });
            if (canvasId === 'conc-account-chart') concAccountChart = chart;
            else if (canvasId === 'conc-class-chart') concClassChart = chart;
        }

        function chartByCanvas(id) {
            if (id === 'conc-account-chart') return concAccountChart;
            if (id === 'conc-class-chart') return concClassChart;
            return null;
        }

        function renderConcTrendChart(points) {
            if (concTrendChart) concTrendChart.destroy();
            var effData = points.filter(function (p) { return p.effectiveN != null; })
                    .map(function (p) {
                        return { x: p.date, y: parseFloat(p.effectiveN) };
                    });
            var hhiData = points.filter(function (p) { return p.hhi != null; })
                    .map(function (p) {
                        return { x: p.date, y: parseFloat(p.hhi) };
                    });
            concTrendChart = new Chart(
                document.getElementById('conc-trend-chart').getContext('2d'), {
                    type: 'line',
                    data: {
                        datasets: [
                            {
                                label: 'Effective N',
                                data: effData,
                                borderColor: '#1f77b4',
                                backgroundColor: 'rgba(31,119,180,0.10)',
                                borderWidth: 1.5,
                                pointRadius: 0,
                                tension: 0.1,
                                yAxisID: 'yLeft'
                            },
                            {
                                label: 'HHI',
                                data: hhiData,
                                borderColor: '#ff7f0e',
                                backgroundColor: 'rgba(255,127,14,0.08)',
                                borderWidth: 1.5,
                                pointRadius: 0,
                                tension: 0.1,
                                yAxisID: 'yRight'
                            }
                        ]
                    },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            yLeft: {
                                type: 'linear', position: 'left',
                                beginAtZero: true,
                                title: { display: true, text: 'Effective N' }
                            },
                            yRight: {
                                type: 'linear', position: 'right',
                                beginAtZero: true, max: 1,
                                grid: { drawOnChartArea: false },
                                title: { display: true, text: 'HHI' }
                            }
                        },
                        plugins: { legend: { position: 'bottom' } }
                    }
                });
        }

        var currencyLoaded = false;
        var curDonutChart = null, curTrendChart = null;

        var CCY_COLOURS = {
            'GBP': '#1f77b4',
            'USD': '#2ca02c',
            'EUR': '#ff7f0e',
            'JPY': '#9467bd',
            'CHF': '#8c564b',
            'CAD': '#e377c2',
            'AUD': '#17becf'
        };

        function ccyColour(ccy, fallbackIdx) {
            return CCY_COLOURS[ccy]
                    || CONC_PALETTE[fallbackIdx % CONC_PALETTE.length];
        }

        // ---- Correlations tab ----

        var corrSetupDone = false;
        var corrVolChart = null, corrDrChart = null;

        function setupCorrelations() {
            if (!corrSetupDone) {
                corrSetupDone = true;
                document.getElementById('corr-window-select')
                        .addEventListener('change', fetchCorrelations);
            }
            fetchCorrelations();
        }

        function fetchCorrelations() {
            var w = document.getElementById('corr-window-select').value;
            var status = document.getElementById('corr-status');
            status.textContent = 'Loading…';
            fetch('/correlations?window=' + encodeURIComponent(w))
                    .then(function (r) { return r.json(); })
                    .then(function (d) {
                        status.textContent = '';
                        renderCorrelations(d);
                    })
                    .catch(function (e) {
                        status.textContent = 'Error: ' + e.message;
                    });
        }

        function renderCorrelations(d) {
            setCorrPct('corr-pvol', d.portfolioVol);
            setCorrPct('corr-wavg', d.weightedAvgVol);
            setCorrRatio('corr-dr', d.diversificationRatio);
            setCorrRatio('corr-effbets', d.effectiveBets);
            var nameCount = (d.symbols || []).filter(function (s) { return s !== 'Cash'; }).length;
            var c = document.getElementById('corr-count');
            c.textContent = nameCount || '—';
            c.className = nameCount ? 'value' : 'value na';

            var missing = document.getElementById('corr-missing');
            if (d.missingSymbols && d.missingSymbols.length) {
                missing.innerHTML = '<h4>Symbols excluded</h4><p>Insufficient price history' +
                        ' in the lookback window (needs ≥ 70% coverage), or no live FX rate to' +
                        ' value the position. Excluded: <code>' +
                        d.missingSymbols.join(', ') + '</code></p>';
                missing.style.display = 'block';
            } else {
                missing.style.display = 'none';
            }

            renderCorrHeatmap(d);
            renderCorrPairs('corr-top-pairs', d.topPairs);
            renderCorrPairs('corr-bottom-pairs', d.bottomPairs);
            renderCorrVolChart(d);
            renderCorrDrChart(d);
        }

        function renderCorrHeatmap(d) {
            var table = document.getElementById('corr-heatmap');
            table.innerHTML = '';
            if (!d.symbols || !d.symbols.length) return;
            var thead = document.createElement('thead');
            var headRow = document.createElement('tr');
            headRow.appendChild(document.createElement('th'));
            d.symbols.forEach(function (s) {
                var th = document.createElement('th');
                th.className = 'corr-axis';
                th.textContent = s;
                headRow.appendChild(th);
            });
            thead.appendChild(headRow);
            table.appendChild(thead);
            var tbody = document.createElement('tbody');
            d.matrix.forEach(function (row, i) {
                var tr = document.createElement('tr');
                var axisCell = document.createElement('th');
                axisCell.className = 'corr-axis';
                axisCell.textContent = d.symbols[i];
                tr.appendChild(axisCell);
                row.forEach(function (cell) {
                    var v = parseFloat(cell);
                    var td = document.createElement('td');
                    td.textContent = v.toFixed(2);
                    var bg = corrColour(v);
                    td.style.background = bg.bg;
                    td.style.color = bg.fg;
                    tr.appendChild(td);
                });
                tbody.appendChild(tr);
            });
            table.appendChild(tbody);
        }

        function corrColour(rho) {
            // Diverging palette: red for positive, blue for negative, white at 0.
            // Magnitude controls saturation; text flips to white once the cell is
            // dark enough to be hard to read in black.
            var mag = Math.min(Math.abs(rho), 1);
            var hue = rho >= 0 ? 0 : 220;
            var sat = Math.round(mag * 70);
            var light = Math.round(95 - mag * 45);
            return {
                bg: 'hsl(' + hue + ', ' + sat + '%, ' + light + '%)',
                fg: light < 55 ? '#fff' : '#111'
            };
        }

        function renderCorrPairs(id, pairs) {
            var body = document.querySelector('#' + id + ' tbody');
            body.innerHTML = '';
            (pairs || []).forEach(function (p) {
                var tr = document.createElement('tr');
                var rho = parseFloat(p.corr);
                tr.innerHTML =
                    '<td class="txt">' + p.a + '</td>' +
                    '<td class="txt">' + p.b + '</td>' +
                    '<td class="' + (rho >= 0 ? 'pos' : 'neg') + '">' + rho.toFixed(2) + '</td>';
                body.appendChild(tr);
            });
        }

        function renderCorrVolChart(d) {
            if (corrVolChart) corrVolChart.destroy();
            var pts = d.rollingVols || [];
            if (!pts.length) return;
            // Top 8 names by current weight, so the legend stays readable.
            var names = (d.symbols || [])
                    .filter(function (s) { return s !== 'Cash'; })
                    .slice()
                    .sort(function (a, b) {
                        return parseFloat(d.weights[b] || 0) - parseFloat(d.weights[a] || 0);
                    })
                    .slice(0, 8);
            var datasets = names.map(function (sym, i) {
                return {
                    label: sym,
                    data: pts.map(function (p) {
                        var v = p.values && p.values[sym];
                        return { x: p.date, y: v != null ? parseFloat(v) * 100 : null };
                    }).filter(function (pt) { return pt.y != null; }),
                    borderColor: CONC_PALETTE[i % CONC_PALETTE.length],
                    backgroundColor: 'transparent',
                    borderWidth: 1.4,
                    pointRadius: 0,
                    tension: 0.1
                };
            });
            corrVolChart = new Chart(
                document.getElementById('corr-vol-chart').getContext('2d'), {
                    type: 'line',
                    data: { datasets: datasets },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: {
                                beginAtZero: true,
                                title: { display: true, text: 'Annualised vol (%)' },
                                ticks: { callback: function (v) { return v.toFixed(0) + '%'; } }
                            }
                        },
                        plugins: {
                            legend: { position: 'bottom', labels: { boxWidth: 10 } },
                            tooltip: { callbacks: { label: function (c) {
                                return c.dataset.label + ': ' + c.parsed.y.toFixed(1) + '%';
                            }}}
                        }
                    }
                });
        }

        function renderCorrDrChart(d) {
            if (corrDrChart) corrDrChart.destroy();
            var pts = (d.divRatioTimeline || []).map(function (p) {
                return { x: p.date, y: parseFloat(p.dr) };
            });
            if (!pts.length) return;
            corrDrChart = new Chart(
                document.getElementById('corr-dr-chart').getContext('2d'), {
                    type: 'line',
                    data: { datasets: [{
                        label: 'Diversification ratio',
                        data: pts,
                        borderColor: '#2ca02c',
                        backgroundColor: 'rgba(44,160,44,0.12)',
                        fill: true,
                        pointRadius: 0,
                        borderWidth: 1.5,
                        tension: 0.1
                    }]},
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: {
                                beginAtZero: false,
                                title: { display: true, text: 'DR (× independent bets)' }
                            }
                        },
                        plugins: {
                            legend: { display: false },
                            tooltip: { callbacks: { label: function (c) {
                                return 'DR ' + c.parsed.y.toFixed(2);
                            }}}
                        }
                    }
                });
        }

        function setCorrPct(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = (parseFloat(v) * 100).toFixed(2) + '%';
            el.className = 'value';
        }

        function setCorrRatio(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = parseFloat(v).toFixed(2);
            el.className = 'value';
        }

        function loadCurrency() {
            if (currencyLoaded) return;
            currencyLoaded = true;
            clearPanelError('alloc-view-currency');
            fetch('/currency').then(function (r) { return r.json(); })
                    .then(renderCurrency)
                    .catch(function (e) {
                        currencyLoaded = false;
                        panelLoadFailed('alloc-view-currency', e, loadCurrency);
                    });
        }

        function renderCurrency(d) {
            var s = d.snapshot || {};
            setMoney('cur-total', s.totalGbp);
            setMoney('cur-gbp', s.gbpExposureGbp);
            setMoney('cur-nongbp', s.nonGbpExposureGbp);
            setDivPct('cur-nongbp-share', s.nonGbpShare);
            setSignedMoney('cur-fx-impact', s.fxImpact12mGbp);
            var countEl = document.getElementById('cur-count');
            countEl.textContent = s.currencyCount != null ? s.currencyCount : '—';
            countEl.className = 'value' + (s.currencyCount ? '' : ' na');

            renderCurDonut(s.rows || []);
            renderCurTable(s.rows || []);
            renderCurTrend(d.trend || []);
        }

        function renderCurDonut(rows) {
            if (curDonutChart) curDonutChart.destroy();
            var labels = rows.map(function (r) { return r.currency; });
            var weights = rows.map(function (r) { return parseFloat(r.weight) * 100; });
            var gbp = rows.map(function (r) { return parseFloat(r.totalGbp); });
            var colours = labels.map(function (c, i) { return ccyColour(c, i); });
            curDonutChart = new Chart(
                document.getElementById('cur-donut-chart').getContext('2d'), {
                    type: 'doughnut',
                    data: {
                        labels: labels,
                        datasets: [{ data: weights, backgroundColor: colours, borderWidth: 0 }]
                    },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        plugins: {
                            legend: { position: 'right' },
                            tooltip: {
                                callbacks: {
                                    label: function (ctx) {
                                        return ctx.label + ': ' + ctx.parsed.toFixed(1) +
                                                '%  (£' + gbp[ctx.dataIndex].toLocaleString('en-GB',
                                                        { maximumFractionDigits: 0 }) + ')';
                                    }
                                }
                            }
                        }
                    }
                });
        }

        function renderCurTable(rows) {
            var tbody = document.querySelector('#cur-table tbody');
            tbody.innerHTML = '';
            rows.forEach(function (r) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td class="txt"><b>' + r.currency + '</b></td>' +
                    '<td>' + fmtMoney0(r.investedGbp) + '</td>' +
                    '<td>' + fmtMoney0(r.cashGbp) + '</td>' +
                    '<td>' + fmtMoney0(r.totalGbp) + '</td>' +
                    '<td>' + (parseFloat(r.weight) * 100).toFixed(2) + '%</td>' +
                    '<td>' + r.positions + '</td>';
                tbody.appendChild(tr);
            });
            attachSortOnce('cur-table');
        }

        function renderCurTrend(points) {
            if (curTrendChart) curTrendChart.destroy();
            // Build datasets per currency. Collect every currency seen across all points so
            // an early sample with only GBP still gets a USD/EUR band at 0 — otherwise
            // Chart.js would render a jagged stack.
            var ccys = new Set();
            points.forEach(function (p) {
                Object.keys(p.weightByCurrency || {}).forEach(function (c) { ccys.add(c); });
            });
            // Preferred display order: GBP first, USD second, EUR third, rest alphabetical.
            var orderHint = ['GBP', 'USD', 'EUR'];
            var sorted = orderHint.filter(function (c) { return ccys.has(c); })
                    .concat(Array.from(ccys).filter(function (c) {
                        return orderHint.indexOf(c) < 0;
                    }).sort());
            var datasets = sorted.map(function (ccy, idx) {
                return {
                    label: ccy,
                    data: points.map(function (p) {
                        var w = (p.weightByCurrency || {})[ccy];
                        return { x: p.date, y: w == null ? 0 : parseFloat(w) * 100 };
                    }),
                    borderColor: ccyColour(ccy, idx),
                    backgroundColor: ccyColour(ccy, idx),
                    borderWidth: 1,
                    pointRadius: 0,
                    tension: 0.1,
                    fill: true,
                    stack: 'ccy'
                };
            });
            curTrendChart = new Chart(
                document.getElementById('cur-trend-chart').getContext('2d'), {
                    type: 'line',
                    data: { datasets: datasets },
                    options: {
                        responsive: true, maintainAspectRatio: false,
                        interaction: { mode: 'nearest', axis: 'x', intersect: false },
                        scales: {
                            x: { type: 'time', time: { unit: 'year' } },
                            y: { stacked: true, beginAtZero: true, max: 100,
                                 title: { display: true, text: 'Weight (%)' },
                                 ticks: { callback: function (v) { return v.toFixed(0) + '%'; } } }
                        },
                        plugins: { legend: { position: 'bottom' } }
                    }
                });
        }

        // Period diff: value the portfolio ourselves on two arbitrary dates (ledger replay +
        // stored closes + historical FX) rather than diffing two broker-written snapshot rows.
        var pdInitialized = false;
        var pdSeq = 0;

        function setupPeriodDiff() {
            if (pdInitialized) return;
            pdInitialized = true;
            document.querySelectorAll('#pd-presets .attr-preset').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    document.querySelectorAll('#pd-presets .attr-preset').forEach(function (b) {
                        b.classList.remove('active');
                    });
                    btn.classList.add('active');
                    pdApplyPreset(btn.dataset.preset);
                    runPeriodDiff();
                });
            });
            document.getElementById('pd-apply').addEventListener('click', function () {
                document.querySelectorAll('#pd-presets .attr-preset').forEach(function (b) {
                    b.classList.remove('active');
                });
                runPeriodDiff();
            });
            pdApplyPreset('ytd');
            runPeriodDiff();
        }

        function pdApplyPreset(preset) {
            var to = new Date();
            var from = new Date();
            switch (preset) {
                case '1m': from.setMonth(from.getMonth() - 1); break;
                case '3m': from.setMonth(from.getMonth() - 3); break;
                case '6m': from.setMonth(from.getMonth() - 6); break;
                case 'ytd': from = new Date(to.getFullYear(), 0, 1); break;
                case '1y': from.setFullYear(from.getFullYear() - 1); break;
                case '3y': from.setFullYear(from.getFullYear() - 3); break;
                case '5y': from.setFullYear(from.getFullYear() - 5); break;
                // The server clamps to the first ledger date and says so in a warning.
                case 'all': from = new Date(2000, 0, 1); break;
            }
            document.getElementById('pd-from').value = isoDate(from);
            document.getElementById('pd-to').value = isoDate(to);
        }

        function runPeriodDiff() {
            var from = document.getElementById('pd-from').value;
            var to = document.getElementById('pd-to').value;
            if (!from || !to) return;
            var status = document.getElementById('pd-status');
            status.textContent = 'Valuing…';
            // Same out-of-order guard as Attribution: clicking through the presets leaves
            // several valuations in flight and a slow early one must not repaint over a
            // later window.
            var seq = ++pdSeq;
            fetch('/period-diff?from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to))
                .then(function (r) {
                    if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Request failed'); });
                    return r.json();
                }).then(function (data) {
                    if (seq !== pdSeq) return;
                    status.textContent = '';
                    renderPeriodDiff(data);
                }).catch(function (err) {
                    if (seq !== pdSeq) return;
                    status.textContent = 'Error: ' + (err.message || String(err));
                });
        }

        function renderPeriodDiff(data) {
            renderPdWarnings(data.warnings);
            if (!data.start || !data.end) {
                document.getElementById('pd-content').style.display = 'none';
                return;
            }
            document.getElementById('pd-content').style.display = '';

            setPdPct('pd-twr', data.totalReturn);
            document.getElementById('pd-twr-ann').textContent =
                    data.annualisedReturn != null
                            ? pdPctText(data.annualisedReturn) + ' annualised'
                            : 'under 30 days';
            setSignedMoney('pd-gain', data.gainGbp);
            setPdMoney('pd-start', data.start.totalGbp);
            setPdMoney('pd-end', data.end.totalGbp);
            document.getElementById('pd-start-date').textContent = data.start.date;
            document.getElementById('pd-end-date').textContent = data.end.date;
            setSignedMoney('pd-change', data.valueChangeGbp);
            document.getElementById('pd-change-pct').textContent =
                    data.valueChangePct != null ? pdPctText(data.valueChangePct) : '';
            setPdMoney('pd-contrib', data.contributionsGbp);
            // Opening balances only exist for some windows; an empty tile reading £0 would
            // just be a question mark, so the tile is absent unless it applies.
            var bf = data.broughtForwardGbp == null ? 0 : parseFloat(data.broughtForwardGbp);
            document.getElementById('pd-bf-tile').style.display = bf === 0 ? 'none' : '';
            if (bf !== 0) setPdMoney('pd-bf', data.broughtForwardGbp);
            setPdPct('pd-invested', data.investedReturn);
            // The positions chain can start after the window does (a window that opened on
            // cash); say so rather than let the tile read as a same-window comparison.
            document.getElementById('pd-invested-sub').textContent =
                    (data.investedFrom && data.investedFrom !== data.from)
                            ? 'ex-cash drag, from ' + data.investedFrom
                            : 'ex-cash drag';
            document.getElementById('pd-days').textContent = data.spanDays;

            document.getElementById('pd-col-from').textContent = data.start.date;
            document.getElementById('pd-col-to').textContent = data.end.date;

            var rows = [
                ['Total value £', data.start.totalGbp, data.end.totalGbp, 2],
                ['Positions £', data.start.investedGbp, data.end.investedGbp, 2],
                ['Cash £', data.start.cashGbp, data.end.cashGbp, 2],
                ['GBP/USD', data.start.gbpusd, data.end.gbpusd, 4],
                ['GBP/EUR', data.start.gbpeur, data.end.gbpeur, 4]
            ];
            var tbody = document.querySelector('#pd-table tbody');
            tbody.innerHTML = '';
            rows.forEach(function (r) {
                var a = r[1] == null ? null : parseFloat(r[1]);
                var b = r[2] == null ? null : parseFloat(r[2]);
                var delta = (a == null || b == null) ? null : b - a;
                var tr = document.createElement('tr');
                tr.innerHTML =
                        '<td class="txt">' + r[0] + '</td>' +
                        '<td>' + pdNum(a, r[3]) + '</td>' +
                        '<td>' + pdNum(b, r[3]) + '</td>' +
                        '<td class="' + (delta == null ? '' : delta >= 0 ? 'pos' : 'neg') + '">' +
                        pdNumDelta(delta, r[3]) + '</td>';
                tbody.appendChild(tr);
            });
        }

        function renderPdWarnings(warnings) {
            var box = document.getElementById('pd-warnings');
            if (!warnings || !warnings.length) {
                box.style.display = 'none';
                box.innerHTML = '';
                return;
            }
            box.style.display = '';
            box.innerHTML = '<h4>Heads up</h4><ul>' + warnings.map(function (w) {
                return '<li>' + escapeHtml(w) + '</li>';
            }).join('') + '</ul>';
        }

        function setPdMoney(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            el.textContent = '£' + parseFloat(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 0, maximumFractionDigits: 0 });
            el.className = 'value';
        }

        function setPdPct(id, frac) {
            var el = document.getElementById(id);
            if (frac == null) { el.textContent = '—'; el.className = 'value na'; return; }
            var n = parseFloat(frac);
            el.textContent = pdPctText(n);
            el.className = 'value' + (n >= 0 ? ' pos' : ' neg');
        }

        function pdPctText(frac) {
            var n = parseFloat(frac) * 100;
            return (n >= 0 ? '+' : '−') + Math.abs(n).toFixed(2) + '%';
        }

        function pdNum(v, decimals) {
            if (v == null) return '—';
            return v.toLocaleString('en-GB',
                    { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
        }

        function pdNumDelta(v, decimals) {
            if (v == null) return '—';
            return (v >= 0 ? '+' : '−') + Math.abs(v).toLocaleString('en-GB',
                    { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
        }

        var reconLoaded = false;
        function loadReconciliation() {
            if (reconLoaded) return;
            reconLoaded = true;
            bindOnce(document.getElementById('price-save'), 'click', saveManualPrice);
            clearPanelError('panel-recon');
            fetch('/reconciliation').then(function (r) { return r.json(); }).then(renderRecon)
                    .catch(function (e) {
                        reconLoaded = false;
                        panelLoadFailed('panel-recon', e, loadReconciliation);
                    });
        }

        function saveManualPrice() {
            var body = new URLSearchParams();
            body.set('symbol', document.getElementById('price-symbol').value);
            body.set('date', document.getElementById('price-date').value);
            body.set('close', document.getElementById('price-close').value);
            body.set('currency', document.getElementById('price-currency').value);
            var status = document.getElementById('price-status');
            status.textContent = 'Saving…';
            fetch('/prices/manual', { method: 'POST', body: body })
                    .then(function (r) {
                        if (!r.ok) return r.text().then(function (m) { throw new Error(m); });
                        return r.json();
                    })
                    .then(function (data) {
                        status.textContent = '✓ ' + data.rowsAffected + ' row(s) written for ' +
                                data.symbol + ' on ' + data.date;
                    })
                    .catch(function (e) { status.textContent = '⚠ ' + e.message; });
        }

        function renderRecon(report) {
            var summary = document.getElementById('recon-summary');
            var n = (report.issues || []).length;
            if (n === 0) {
                summary.textContent = 'All ' + report.positionsChecked + ' held positions clean — no issues.';
            } else {
                var errors = report.issues.filter(function (i) { return i.severity === 'error'; }).length;
                var warns = report.issues.filter(function (i) { return i.severity === 'warning'; }).length;
                summary.textContent = report.positionsChecked + ' positions checked — ' +
                        errors + ' errors, ' + warns + ' warnings, ' +
                        (n - errors - warns) + ' info notes.';
            }
            var tbody = document.querySelector('#recon-table tbody');
            tbody.innerHTML = '';
            (report.issues || []).forEach(function (it) {
                var tr = document.createElement('tr');
                var sevClass = it.severity === 'error' ? 'neg'
                        : it.severity === 'warning' ? 'pos' : 'muted';
                var icon = it.severity === 'error' ? '🔴'
                        : it.severity === 'warning' ? '🟡' : 'ℹ️';
                tr.innerHTML =
                    '<td class="txt"><span class="' + sevClass + '">' + icon + ' ' + it.severity + '</span></td>' +
                    '<td class="txt"><b>' + it.symbol + '</b></td>' +
                    '<td class="txt"><code>' + it.code + '</code></td>' +
                    '<td class="txt">' + it.detail + '</td>';
                tbody.appendChild(tr);
            });
            attachSortOnce('recon-table');
        }

        // --- Trade journal ---------------------------------------------------
        // FOMO / fear / conviction / etc. — pulls /trades on first activation,
        // renders the table and inline editor; saves via POST /trades/{rowid}/note.

        var JOURNAL_TAGS = ['fomo', 'fear', 'greed', 'conviction', 'rebalance', 'hedge', 'news', 'thesis', 'correction', 'voltrading'];
        var journalInitialized = false;
        var journalData = null;
        var journalFilter = { text: '', tag: '', onlyNoted: false };

        function setupJournal() {
            if (journalInitialized) return;
            journalInitialized = true;
            document.getElementById('journal-search').addEventListener('input', function (e) {
                journalFilter.text = e.target.value.trim().toLowerCase();
                renderJournalRows();
            });
            document.getElementById('journal-tag-filter').addEventListener('change', function (e) {
                journalFilter.tag = e.target.value;
                renderJournalRows();
            });
            document.getElementById('journal-only-noted').addEventListener('change', function (e) {
                journalFilter.onlyNoted = e.target.checked;
                renderJournalRows();
            });
            loadJournal();
        }

        function loadJournal() {
            document.getElementById('journal-status').textContent = 'Loading…';
            fetch('/trades').then(function (r) { return r.json(); }).then(function (j) {
                journalData = j;
                document.getElementById('journal-status').textContent = '';
                renderJournalSummary();
                populateJournalTagOptions();
                renderJournalRows();
            }).catch(function () {
                document.getElementById('journal-status').textContent = 'Failed to load';
            });
        }

        function renderJournalSummary() {
            var s = journalData.summary;
            var box = document.getElementById('journal-summary');
            var parts = ['<span class="count">' + s.annotated + ' / ' + s.total + ' trades annotated</span>'];
            var tags = s.tagCounts || {};
            var entries = Object.keys(tags).sort(function (a, b) { return tags[b] - tags[a]; });
            entries.forEach(function (t) {
                parts.push('<span class="tag-pill" data-tag="' + t + '">' +
                    escapeHtml(t) + '<span class="n">' + tags[t] + '</span></span>');
            });
            if (entries.length === 0) {
                parts.push('<span class="muted" style="font-size:0.85rem;">No tags yet — start labelling trades to see frequencies build up.</span>');
            }
            box.innerHTML = parts.join(' ');
        }

        function populateJournalTagOptions() {
            var sel = document.getElementById('journal-tag-filter');
            var current = sel.value;
            var s = journalData.summary;
            var keys = Object.keys(s.tagCounts || {}).sort();
            sel.innerHTML = '<option value="">— any —</option>' +
                keys.map(function (k) {
                    return '<option value="' + escapeHtml(k) + '">' + escapeHtml(k) +
                        ' (' + s.tagCounts[k] + ')</option>';
                }).join('');
            sel.value = current;
        }

        function renderJournalRows() {
            var tbody = document.querySelector('#journal-table tbody');
            tbody.innerHTML = '';
            var trades = (journalData.trades || []).filter(function (t) {
                if (journalFilter.onlyNoted) {
                    if (!t.note && (!t.tags || t.tags.length === 0)) return false;
                }
                if (journalFilter.tag && (t.tags || []).indexOf(journalFilter.tag) < 0) return false;
                if (journalFilter.text) {
                    var hay = (t.symbol + ' ' + (t.note || '') + ' ' + (t.tags || []).join(' ') +
                            ' ' + (t.description || '')).toLowerCase();
                    if (hay.indexOf(journalFilter.text) < 0) return false;
                }
                return true;
            });
            document.getElementById('journal-empty').style.display = trades.length ? 'none' : 'block';
            trades.forEach(function (t) {
                tbody.appendChild(renderJournalTradeRow(t));
                tbody.appendChild(renderJournalEditorRow(t));
            });
        }

        function renderJournalTradeRow(t) {
            var tr = document.createElement('tr');
            tr.className = 'tr-trade';
            var isCorrection = (t.tags || []).indexOf('correction') >= 0;
            if (isCorrection) tr.classList.add('is-correction');
            tr.dataset.rowid = t.rowid;
            var sideClass = t.side === 'BUY' ? 'journal-side-buy' : 'journal-side-sell';
            var amtAbs = Math.abs(t.amountGbp);
            var amtStr = '£' + amtAbs.toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
            var qtyStr = t.quantity.toLocaleString('en-GB',
                    { maximumFractionDigits: 4 });
            // Effective price per share, in the trade's own currency — the ledger has no price
            // column, so the service derives it from the cash amount (and returns null for
            // split receipts and zero-quantity rows, where the quotient means nothing).
            // Shown native rather than GBP so it matches the price the broker quoted.
            var pxStr = '—';
            if (t.pricePerShare !== null && t.pricePerShare !== undefined) {
                var sym = t.currency === 'USD' ? '$' : (t.currency === 'GBP' ? '£' : '');
                pxStr = sym + t.pricePerShare.toLocaleString('en-GB',
                        { minimumFractionDigits: 2, maximumFractionDigits: 2 }) +
                        (sym ? '' : ' ' + escapeHtml(t.currency || ''));
            }
            var noteCell = '';
            if (t.tags && t.tags.length) {
                noteCell += t.tags.map(function (tag) {
                    return '<span class="tag-pill" data-tag="' + escapeHtml(tag) + '">' +
                        escapeHtml(tag) + '</span>';
                }).join(' ');
            }
            if (t.note) {
                var preview = t.note.length > 80 ? t.note.slice(0, 78) + '…' : t.note;
                noteCell += (noteCell ? ' ' : '') +
                    '<span class="indicator dot">•</span> ' + escapeHtml(preview);
            }
            if (!noteCell) noteCell = '<span class="indicator">click to add</span>';
            var descPreview = t.description
                    ? (t.description.length > 60 ? t.description.slice(0, 58) + '…' : t.description)
                    : '';
            tr.innerHTML =
                '<td class="txt">' + escapeHtml(t.date) +
                    (descPreview ? '<div class="desc-cell">' + escapeHtml(descPreview) + '</div>' : '') +
                '</td>' +
                '<td class="txt">' + escapeHtml(t.account) + '</td>' +
                '<td class="txt"><span class="' + sideClass + '">' + t.side + '</span></td>' +
                '<td class="txt"><b>' + escapeHtml(t.symbol) + '</b></td>' +
                '<td>' + qtyStr + '</td>' +
                '<td>' + pxStr + '</td>' +
                '<td>' + amtStr + '</td>' +
                '<td class="txt">' + noteCell + '</td>';
            tr.addEventListener('click', function (e) {
                if (e.target.tagName === 'A') return;
                toggleJournalEditor(t.rowid);
            });
            return tr;
        }

        function renderJournalEditorRow(t) {
            var tr = document.createElement('tr');
            tr.className = 'tr-editor';
            tr.dataset.editorFor = t.rowid;
            tr.hidden = true;
            var td = document.createElement('td');
            td.colSpan = 8;
            var tagButtons = JOURNAL_TAGS.map(function (tag) {
                var on = (t.tags || []).indexOf(tag) >= 0;
                return '<span class="tag-toggle' + (on ? ' on' : '') +
                    '" data-tag="' + tag + '">' + tag + '</span>';
            }).join(' ');
            td.innerHTML =
                '<div class="editor-row">' +
                '<div class="tags-row">' + tagButtons + '</div>' +
                '<textarea placeholder="Why did you make this trade? Conviction level? What were you reacting to?"></textarea>' +
                '<div class="editor-actions">' +
                '<button type="button" class="journal-save">Save</button>' +
                '<span class="state"></span>' +
                (t.updatedAt ? '<span style="margin-left:auto;">Last saved ' + escapeHtml(t.updatedAt) + '</span>' : '') +
                '</div>' +
                '</div>';
            tr.appendChild(td);
            var textarea = td.querySelector('textarea');
            textarea.value = t.note || '';
            var state = td.querySelector('.state');
            var saveBtn = td.querySelector('.journal-save');
            td.querySelectorAll('.tag-toggle').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    btn.classList.toggle('on');
                    markDirty(state);
                });
            });
            textarea.addEventListener('input', function () { markDirty(state); });
            textarea.addEventListener('blur', function () { saveJournalRow(t.rowid, tr, state); });
            saveBtn.addEventListener('click', function () { saveJournalRow(t.rowid, tr, state); });
            return tr;
        }

        function markDirty(stateEl) {
            stateEl.textContent = 'unsaved';
            stateEl.className = 'state dirty';
        }

        function toggleJournalEditor(rowid) {
            var editor = document.querySelector('tr.tr-editor[data-editor-for="' + rowid + '"]');
            var tradeRow = document.querySelector('tr.tr-trade[data-rowid="' + rowid + '"]');
            if (!editor) return;
            editor.hidden = !editor.hidden;
            tradeRow.classList.toggle('open', !editor.hidden);
            if (!editor.hidden) {
                var ta = editor.querySelector('textarea');
                if (ta) ta.focus();
            }
        }

        function saveJournalRow(rowid, editorTr, stateEl) {
            var textarea = editorTr.querySelector('textarea');
            var tags = Array.prototype.map.call(
                    editorTr.querySelectorAll('.tag-toggle.on'),
                    function (b) { return b.dataset.tag; });
            var params = new URLSearchParams();
            params.append('note', textarea.value);
            params.append('tags', tags.join(','));
            stateEl.textContent = 'saving…';
            stateEl.className = 'state';
            fetch('/trades/' + rowid + '/note', { method: 'POST', body: params })
                .then(function (r) {
                    if (!r.ok) throw new Error('Save failed');
                    return r.json();
                })
                .then(function (j) {
                    journalData = j;
                    stateEl.textContent = 'saved';
                    stateEl.className = 'state saved';
                    renderJournalSummary();
                    populateJournalTagOptions();
                    var openIds = Array.from(document.querySelectorAll('tr.tr-trade.open'))
                            .map(function (r) { return r.dataset.rowid; });
                    renderJournalRows();
                    openIds.forEach(function (id) {
                        var ed = document.querySelector('tr.tr-editor[data-editor-for="' + id + '"]');
                        var tr = document.querySelector('tr.tr-trade[data-rowid="' + id + '"]');
                        if (ed && tr) { ed.hidden = false; tr.classList.add('open'); }
                    });
                })
                .catch(function () {
                    stateEl.textContent = 'save failed';
                    stateEl.className = 'state dirty';
                });
        }

        function escapeHtml(s) {
            if (s == null) return '';
            return String(s)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
        }

        // Live tab: SSE stream of /live snapshots, with flash-on-change row updates.
        // EventSource opens on tab activation and closes on tab change so we don't keep a
        // server-side emitter around once the user has moved on.
        // lastValues caches per-(symbol,column) numbers so we can detect *real* changes
        // between snapshots. lastChangeAt only advances when a value actually moved —
        // pulse + age counter reflect actual data movement, not the SSE heartbeat.
        var liveState = {
            source: null, lastValues: {}, lastChangeAt: 0,
            ageTimer: null, sortAttached: false, clickAttached: false,
            // Grouping is a client-side choice over data every snapshot already carries.
            // `expanded` has to live here rather than in the DOM: a frame arrives every 15s and
            // rebuilds nothing but still re-orders rows, and a user who opened a group does not
            // expect it to shut on the next tick.
            grouping: 'none', expanded: {}, lastGroups: []
        };

        function setupLive() {
            updateLiveStatus(false);
            if (!liveState.sortAttached) {
                attachSort(document.getElementById('live-table'));
                liveState.sortAttached = true;
            }
            if (!liveState.clickAttached) {
                liveState.clickAttached = true;
                // Delegated from the tbody: rows are created, patched and re-ordered on every SSE
                // frame, so a per-row listener would be duplicated or lost. A member row opens the
                // same price popup the Watchlist tab uses (the position need not be on the
                // watchlist); a group header toggles instead, since it names no single symbol.
                document.querySelector('#live-table tbody').addEventListener('click', function (e) {
                    var tr = e.target.closest('tr');
                    if (!tr) return;
                    if (tr.dataset.groupHead) {
                        toggleLiveGroup(tr.dataset.groupHead, !liveState.expanded[tr.dataset.groupHead]);
                        return;
                    }
                    if (tr.dataset.symbol) openSymbolModal(tr.dataset.symbol);
                });
                var sel = document.getElementById('live-group-by');
                if (sel) {
                    // Repaints from the snapshot already in hand rather than waiting up to 15s
                    // for the next SSE frame — the grouping is a rendering choice over data the
                    // client already has.
                    sel.addEventListener('change', function () {
                        liveState.grouping = sel.value;
                        applyLiveRows(liveState.lastRows || [], liveState.lastGroups || []);
                    });
                    liveState.grouping = sel.value;
                }
                var expandBtn = document.getElementById('live-expand-all');
                if (expandBtn) {
                    expandBtn.addEventListener('click', function () {
                        var expand = expandBtn.dataset.action !== 'collapse';
                        (liveState.lastGroups || []).forEach(function (g) {
                            liveState.expanded[g.id] = expand;
                        });
                        applyLiveRows(liveState.lastRows || [], liveState.lastGroups || []);
                    });
                }
            }
            // Initial JSON fetch paints something before the first SSE frame arrives.
            fetch('/live')
                .then(function (r) { return r.json(); })
                .then(applyLiveSnapshot)
                .catch(function () {});
            openLiveStream();
            if (liveState.ageTimer) clearInterval(liveState.ageTimer);
            liveState.ageTimer = setInterval(renderLiveAge, 1000);
            renderLiveAge();
        }

        function teardownLive() {
            if (liveState.source) {
                try { liveState.source.close(); } catch (e) {}
                liveState.source = null;
            }
            if (liveState.ageTimer) {
                clearInterval(liveState.ageTimer);
                liveState.ageTimer = null;
            }
        }

        function renderLiveAge() {
            var el = document.getElementById('live-age');
            if (!el) return;
            if (!liveState.lastChangeAt) { el.textContent = 'awaiting data'; return; }
            var sec = Math.max(0, Math.round((Date.now() - liveState.lastChangeAt) / 1000));
            var pretty;
            if (sec < 5) pretty = 'just updated';
            else if (sec < 60) pretty = 'updated ' + sec + 's ago';
            else if (sec < 3600) pretty = 'no move for ' + Math.floor(sec / 60) + 'm';
            else pretty = 'no move for ' + Math.floor(sec / 3600) + 'h ' + Math.floor((sec % 3600) / 60) + 'm';
            el.textContent = pretty;
        }

        function openLiveStream() {
            if (liveState.source) return;
            var es = new EventSource('/live/stream');
            liveState.source = es;
            es.addEventListener('open', function () { updateLiveStatus(true); });
            es.addEventListener('error', function () { updateLiveStatus(false); });
            es.addEventListener('snapshot', function (ev) {
                var changed = false;
                try { changed = applyLiveSnapshot(JSON.parse(ev.data)); } catch (e) {}
                // First snapshot: no baseline to diff against, but we just received data
                // so reset the age counter. Subsequent snapshots only count as "change"
                // if at least one cell value moved.
                if (changed || liveState.lastChangeAt === 0) {
                    liveState.lastChangeAt = Date.now();
                }
                if (changed) pulseLiveDot();
                renderLiveAge();
            });
        }

        function pulseLiveDot() {
            var dot = document.querySelector('#live-status .live-dot');
            if (!dot) return;
            dot.classList.remove('tick');
            void dot.offsetWidth;
            dot.classList.add('tick');
        }

        function updateLiveStatus(connected) {
            var el = document.getElementById('live-status');
            if (!el) return;
            el.innerHTML = '<span class="live-dot' + (connected ? ' connected' : '') + '"></span>' +
                (connected ? 'Live' : 'Disconnected');
        }

        function applyLiveSnapshot(snap) {
            if (!snap) return false;
            applyLiveTotals(snap.totals);
            liveState.lastGroups = snap.groups || [];
            liveState.lastRows = snap.rows || [];
            var changed = applyLiveRows(liveState.lastRows, liveState.lastGroups);
            var upd = document.getElementById('live-updated');
            if (snap.latestPriceAt && upd) {
                upd.textContent = 'latest quote ' + new Date(snap.latestPriceAt).toLocaleTimeString();
            }
            var empty = document.getElementById('live-empty');
            if (empty) empty.style.display = (snap.rows && snap.rows.length) ? 'none' : '';
            return changed;
        }

        function applyLiveTotals(t) {
            if (!t) return;
            setText('live-total-gbp', '£' + fmtLiveNum(t.totalGbp, 2));
            setText('live-positions-gbp', '£' + fmtLiveNum(t.positionsGbp, 2));
            setText('live-cash-gbp', '£' + fmtLiveNum(t.cashGbp, 2));
            var dayEl = document.getElementById('live-day-gbp');
            var pctEl = document.getElementById('live-day-pct');
            var day = numOrNull(t.dayChangeGbp);
            if (dayEl) {
                dayEl.classList.remove('live-pos', 'live-neg');
                if (day != null) {
                    var sign = day >= 0 ? '+£' : '−£';
                    dayEl.textContent = sign + fmtLiveNum(Math.abs(day), 2);
                    dayEl.classList.add(day >= 0 ? 'live-pos' : 'live-neg');
                } else {
                    dayEl.textContent = '—';
                }
            }
            if (pctEl) {
                var pct = numOrNull(t.dayChangePct);
                pctEl.textContent = pct == null ? '' : fmtLivePct(pct);
            }
        }

        function applyLiveRows(rows, groups) {
            var tbody = document.querySelector('#live-table tbody');
            if (!tbody) return;
            // If the user clicked a column header, respect their sort and don't re-order
            // existing rows on each snapshot — they can click again to re-sort.
            var userSorted = !!document.querySelector('#live-table thead th[data-sort-dir]');
            groups = groups || [];
            var grouped = liveState.grouping === 'sector' && groups.length > 0;
            // Selected on [data-symbol] rather than 'tr': group headers are rows of the same
            // tbody, and keying them by an undefined symbol would collapse them all onto one
            // entry and then delete every one of them as unseen.
            var existing = {};
            Array.from(tbody.querySelectorAll('tr[data-symbol]')).forEach(function (tr) {
                existing[tr.dataset.symbol] = tr;
            });
            var groupOf = {};
            groups.forEach(function (g) {
                (g.symbols || []).forEach(function (sym) { groupOf[sym] = g.id; });
            });
            var seen = {};
            var prevByKey = liveState.lastValues;
            var nextByKey = {};
            var hasPrev = Object.keys(prevByKey).length > 0;
            var changedAny = false;
            rows.forEach(function (row) {
                seen[row.symbol] = true;
                var tr = existing[row.symbol];
                var isNew = !tr;
                if (isNew) {
                    tr = document.createElement('tr');
                    tr.dataset.symbol = row.symbol;
                    for (var i = 0; i < 14; i++) tr.appendChild(document.createElement('td'));
                    tr.cells[0].className = 'txt';
                    tr.cells[1].className = 'txt';
                    tr.cells[12].className = 'txt';
                    tr.cells[13].className = 'txt';
                    tbody.appendChild(tr);
                    existing[row.symbol] = tr;
                }
                if (renderLiveRow(tr, row, prevByKey, nextByKey)) changedAny = true;
                applyLiveMembership(tr, grouped ? groupOf[row.symbol] : null);
                if (!userSorted && !isNew && !grouped) tbody.appendChild(tr);
            });
            Object.keys(existing).forEach(function (sym) {
                if (!seen[sym]) existing[sym].remove();
            });
            if (renderLiveGroups(tbody, grouped ? groups : [], existing, userSorted,
                                 prevByKey, nextByKey)) changedAny = true;
            syncLiveExpandAll();
            liveState.lastValues = nextByKey;
            // First snapshot has no baseline, so we can't claim "changed". Subsequent
            // snapshots: changed = any cell value moved.
            return hasPrev && changedAny;
        }

        // Marks a row as a member of a group (or as a plain row again when grouping is off) and
        // applies the group's current open/shut state. Driven from liveState.expanded rather than
        // from the row's own class, so a frame landing mid-session can't reopen what the user shut.
        function applyLiveMembership(tr, groupId) {
            if (!groupId) {
                delete tr.dataset.groupOf;
                tr.classList.remove('group-member', 'collapsed');
                return;
            }
            tr.dataset.groupOf = groupId;
            tr.classList.add('group-member');
            tr.classList.toggle('collapsed', !liveState.expanded[groupId]);
        }

        // Header rows are patched in place like the member rows, so a group's Day Δ flashes on a
        // real move instead of being torn down and rebuilt every 15 seconds. Passing [] removes
        // them, which is how switching back to None works.
        function renderLiveGroups(tbody, groups, memberRows, userSorted, prevByKey, nextByKey) {
            var heads = {};
            Array.from(tbody.querySelectorAll('tr.group-header')).forEach(function (tr) {
                heads[tr.dataset.groupHead] = tr;
            });
            var changed = false;
            var seen = {};
            groups.forEach(function (g) {
                seen[g.id] = true;
                var tr = heads[g.id];
                if (!tr) {
                    tr = document.createElement('tr');
                    tr.className = 'group-header';
                    tr.dataset.groupHead = g.id;
                    for (var i = 0; i < 14; i++) tr.appendChild(document.createElement('td'));
                    tr.cells[0].className = 'txt group-label';
                    tr.cells[12].className = 'txt';
                    tr.cells[13].className = 'txt';
                    tbody.appendChild(tr);
                    heads[g.id] = tr;
                }
                if (renderLiveGroupRow(tr, g, prevByKey, nextByKey)) changed = true;
            });
            Object.keys(heads).forEach(function (id) {
                if (!seen[id]) heads[id].remove();
            });
            if (layoutLiveGroups(tbody, groups, heads, memberRows, userSorted) && userSorted) {
                // The layout moved rows, so the table no longer honours the marker still sitting
                // in its thead — re-sort rather than leave the groups in server order under a
                // column the user sorted. A steady frame moves nothing, so this doesn't fight the
                // "respect their sort, don't re-order on every snapshot" rule above.
                reapplyActiveSort(document.getElementById('live-table'));
            }
            return changed;
        }

        // Header-then-members, always — and this is the only thing that puts a member row under
        // its header. It used to be skipped once the user had sorted a column, on the grounds that
        // sortBody had already arranged the same blocks their way; that holds for blocks sortBody
        // can see, but headers created *after* the sort (switching Group by → Sector on an already
        // sorted table) are appended to the end of the tbody, below every member row. Collapsed
        // members are invisible, so the table looked right until a group was expanded and its
        // members appeared at the top — and no later sort repaired it, because a row above the
        // first header is not part of any block.
        // Group and member order come from the server (value desc) while the table is unsorted,
        // and from the current DOM once it isn't, so a relayout preserves the user's ordering.
        // Returns true when the DOM order actually changed.
        function layoutLiveGroups(tbody, groups, heads, memberRows, userSorted) {
            if (!groups.length) return false;      // ungrouped: the row loop owns the order
            var pos = {};
            Array.from(tbody.children).forEach(function (tr, i) {
                if (tr.dataset.groupHead) pos['h|' + tr.dataset.groupHead] = i;
                else if (tr.dataset.symbol) pos['s|' + tr.dataset.symbol] = i;
            });
            function at(key) { return pos[key] == null ? Number.MAX_SAFE_INTEGER : pos[key]; }
            var ordered = groups.slice();
            if (userSorted) ordered.sort(function (a, b) { return at('h|' + a.id) - at('h|' + b.id); });
            var want = [];
            ordered.forEach(function (g) {
                want.push(heads[g.id]);
                var syms = (g.symbols || []).filter(function (sym) { return memberRows[sym]; });
                if (userSorted) syms.sort(function (a, b) { return at('s|' + a) - at('s|' + b); });
                syms.forEach(function (sym) { want.push(memberRows[sym]); });
            });
            // A row in no group at all is an invariant violation (LiveGrouper places everything,
            // residual bucket included), but it is kept and placed last rather than dropped —
            // leaving it out would make `want` shorter than the tbody forever, so the comparison
            // below would report a move on every frame and re-sort the table every 15 seconds.
            var cur = Array.from(tbody.children);
            cur.forEach(function (tr) { if (want.indexOf(tr) < 0) want.push(tr); });
            if (cur.length === want.length && want.every(function (tr, i) { return cur[i] === tr; })) {
                return false;
            }
            want.forEach(function (tr) { tbody.appendChild(tr); });
            return true;
        }

        function renderLiveGroupRow(tr, g, prevByKey, nextByKey) {
            var changed = false;
            setGroupLabelCell(tr.cells[0], g);
            tr.classList.toggle('group-residual', !!g.residual);
            tr.classList.toggle('expanded', !!liveState.expanded[g.id]);
            tr.title = g.note || '';
            var nPct = numOrNull(g.dayChangePct);
            var nDay = numOrNull(g.dayChangeGbp);
            var nPos = numOrNull(g.positionGbp);
            var key = 'grp|' + g.id + '|';
            if (setNumericCell(tr.cells[4], key + 'pct', nPct, nPct == null ? '—' : fmtLivePct(nPct), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[4], nPct);
            tr.cells[4].title = nPct == null ? '' :
                'Weighted: total day move divided by what the group was worth at the previous ' +
                'close — not the average of the member percentages.';
            if (setNumericCell(tr.cells[5], key + 'day', nDay, nDay == null ? '—' : (nDay >= 0 ? '+' : '−') + fmtLiveNum(Math.abs(nDay), 2), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[5], nDay);
            [[6, 'd5', g.change5dPct], [7, 'd10', g.change10dPct], [8, 'd30', g.monthChangePct],
             [9, 'm3', g.change3mPct], [10, 'm6', g.change6mPct]].forEach(function (t) {
                var cell = tr.cells[t[0]], n = numOrNull(t[2]);
                if (setNumericCell(cell, key + t[1], n, n == null ? '—' : fmtLivePct(n), prevByKey, nextByKey)) changed = true;
                applyDeltaClass(cell, n);
                cell.title = n == null ? '' :
                    'Value-weighted price change of the group over this window, using today\'s ' +
                    'quantities — not a realised portfolio return (see Attribution for that).';
            });
            if (setNumericCell(tr.cells[11], key + 'pos', nPos, nPos == null ? '—' : fmtLiveNum(nPos, 2), prevByKey, nextByKey)) changed = true;
            // The stalest member's quote, not the freshest: a summary line is only as current as
            // the least current thing under it, and the newest would hide the lagging feed.
            renderQuoteTs(tr.cells[13], g.priceTs, 'UNKNOWN');
            if (g.priceTs) {
                tr.cells[13].title = 'Oldest quote in this group' +
                    (g.stalestSymbol ? ' (' + g.stalestSymbol + ')' : '') +
                    ' — the group is only as fresh as its stalest member.';
            }
            return changed;
        }

        function setGroupLabelCell(td, g) {
            if (!td.querySelector('.group-caret')) {
                td.textContent = '';
                td.appendChild(document.createElement('span')).className = 'group-caret';
                td.appendChild(document.createElement('span')).className = 'group-name';
                td.appendChild(document.createElement('span')).className = 'group-count';
                td.appendChild(document.createElement('span')).className = 'group-note-flag';
            }
            td.querySelector('.group-caret').textContent = '\u25B6';
            td.querySelector('.group-name').textContent = g.label;
            td.querySelector('.group-count').textContent = '(' + (g.symbols || []).length + ')';
            td.querySelector('.group-note-flag').textContent = g.note ? '\u24D8' : '';
            td.dataset.sortValue = String(g.label || '').toLowerCase();
        }

        // Toggling touches the DOM directly rather than repainting the whole table: an SSE frame
        // may be mid-flight, and a repaint would drop any flash animation currently playing.
        function toggleLiveGroup(id, expand) {
            liveState.expanded[id] = expand;
            var tbody = document.querySelector('#live-table tbody');
            if (!tbody) return;
            var head = tbody.querySelector('tr[data-group-head="' + id + '"]');
            if (head) head.classList.toggle('expanded', expand);
            Array.from(tbody.querySelectorAll('tr.group-member')).forEach(function (tr) {
                if (tr.dataset.groupOf === id) tr.classList.toggle('collapsed', !expand);
            });
            syncLiveExpandAll();
        }

        function syncLiveExpandAll() {
            var btn = document.getElementById('live-expand-all');
            if (!btn) return;
            var groups = (liveState.grouping === 'sector' && liveState.lastGroups) || [];
            btn.hidden = groups.length === 0;
            var allOpen = groups.length > 0 && groups.every(function (g) {
                return !!liveState.expanded[g.id];
            });
            btn.textContent = allOpen ? 'Collapse all' : 'Expand all';
            btn.dataset.action = allOpen ? 'collapse' : 'expand';
        }

        function renderLiveRow(tr, row, prevByKey, nextByKey) {
            tr.cells[0].textContent = row.symbol;
            tr.cells[1].textContent = row.currency || '';
            var nLast = numOrNull(row.lastPrice);
            var nPct = numOrNull(row.dayChangePct);
            var nDay = numOrNull(row.dayChangeGbp);
            var nPos = numOrNull(row.positionGbp);
            var changed = false;
            if (setNumericCell(tr.cells[2], row.symbol + '|qty', row.quantity, fmtLiveNum(row.quantity, 2), prevByKey, nextByKey)) changed = true;
            if (setPriceCell(tr.cells[3], row.symbol + '|last', nLast, nLast == null ? '—' : fmtLiveNum(nLast, 4), row.session, prevByKey, nextByKey)) changed = true;
            applyMomClass(tr.cells[3], numOrNull(row.monthChangePct));
            applyMarketState(tr, row);
            if (setNumericCell(tr.cells[4], row.symbol + '|pct', nPct, nPct == null ? '—' : fmtLivePct(nPct), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[4], nPct);
            if (setNumericCell(tr.cells[5], row.symbol + '|dayGbp', nDay, nDay == null ? '—' : (nDay >= 0 ? '+' : '−') + fmtLiveNum(Math.abs(nDay), 2), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[5], nDay);
            var trailing = [
                [6, 'd5', numOrNull(row.change5dPct)],
                [7, 'd10', numOrNull(row.change10dPct)],
                [8, 'd30', numOrNull(row.monthChangePct)],
                [9, 'm3', numOrNull(row.change3mPct)],
                [10, 'm6', numOrNull(row.change6mPct)]
            ];
            trailing.forEach(function (t) {
                var cell = tr.cells[t[0]], n = t[2];
                if (setNumericCell(cell, row.symbol + '|' + t[1], n, n == null ? '—' : fmtLivePct(n), prevByKey, nextByKey)) changed = true;
                applyDeltaClass(cell, n);
            });
            if (setNumericCell(tr.cells[11], row.symbol + '|pos', nPos, nPos == null ? '—' : fmtLiveNum(nPos, 2), prevByKey, nextByKey)) changed = true;
            tr.cells[12].textContent = row.accounts || '';
            renderQuoteTs(tr.cells[13], row.priceTs, row.marketState);
            return changed;
        }

        // Session badges. A pre/post-market print is a real, tradeable price and far more use at
        // 07:00 than last night's close — but it is thin, wide-spread and not the number any
        // statement will agree with, so it is always shown labelled rather than blended in.
        var LIVE_SESSION_BADGE = {
            PRE: { label: 'PRE', cls: 'live-sess-pre',
                   title: 'Pre-market price — the regular session has not opened yet. ' +
                          'Day Δ is measured against the previous regular close.' },
            POST: { label: 'AH', cls: 'live-sess-post',
                    title: 'After-hours price — the regular session has closed. ' +
                           'Day Δ is measured against the previous regular close.' }
        };

        var LIVE_STATE_TITLE = {
            OPEN: 'Regular session in progress',
            PRE: 'Pre-market session',
            POST: 'After-hours session',
            CLOSED: 'Market closed for this listing',
            UNKNOWN: 'Trading hours not known for this listing'
        };

        // The Last cell holds a number *and* an optional session badge, so it can't be written
        // with setNumericCell's textContent swap: the badge would be wiped on every frame and
        // the change-detection would compare "174.38PRE" against "174.38". Number and badge get
        // their own spans, the flash still plays on the cell, and data-sort-value keeps the
        // column sorting on the price alone.
        function setPriceCell(td, key, value, text, session, prevByKey, nextByKey) {
            var valEl = td.querySelector('.live-px');
            if (!valEl) {
                td.textContent = '';
                valEl = document.createElement('span');
                valEl.className = 'live-px';
                td.appendChild(valEl);
                td.appendChild(document.createElement('span')).className = 'live-sess';
            }
            var badgeEl = td.querySelector('.live-sess');
            if (valEl.textContent !== text) valEl.textContent = text;

            var badge = LIVE_SESSION_BADGE[session];
            badgeEl.className = 'live-sess' + (badge ? ' ' + badge.cls : '');
            badgeEl.textContent = badge ? badge.label : '';
            if (badge) badgeEl.title = badge.title; else badgeEl.removeAttribute('title');

            var nv = numOrNull(value);
            if (nv == null) td.removeAttribute('data-sort-value');
            else td.dataset.sortValue = String(nv);
            if (nextByKey) nextByKey[key] = nv;
            if (prevByKey && nv != null) {
                var pv = prevByKey[key];
                if (pv != null && pv !== nv) {
                    td.classList.remove('live-flash-up', 'live-flash-down');
                    void td.offsetWidth;
                    td.classList.add(nv > pv ? 'live-flash-up' : 'live-flash-down');
                    return true;
                }
            }
            return false;
        }

        // Row tint by what the listing's market is doing. Closed rows are dimmed so a US name
        // sitting on last night's close at 09:00 London reads as settled data rather than as a
        // live quote that has simply stopped moving.
        function applyMarketState(tr, row) {
            var st = row.marketState || 'UNKNOWN';
            tr.classList.remove('live-mkt-open', 'live-mkt-pre', 'live-mkt-post',
                                'live-mkt-closed', 'live-mkt-unknown');
            tr.classList.add('live-mkt-' + st.toLowerCase());
            tr.title = LIVE_STATE_TITLE[st] || '';
        }

        function setNumericCell(td, key, value, text, prevByKey, nextByKey) {
            var nv = numOrNull(value);
            if (td.textContent !== text) td.textContent = text;
            if (nextByKey) nextByKey[key] = nv;
            if (prevByKey && nv != null) {
                var pv = prevByKey[key];
                if (pv != null && pv !== nv) {
                    td.classList.remove('live-flash-up', 'live-flash-down');
                    void td.offsetWidth;  // restart the animation
                    td.classList.add(nv > pv ? 'live-flash-up' : 'live-flash-down');
                    return true;
                }
            }
            return false;
        }

        function renderQuoteTs(td, iso, marketState) {
            td.classList.remove('live-ts-fresh', 'live-ts-stale', 'live-ts-old');
            if (!iso) { td.textContent = '—'; td.removeAttribute('title'); return; }
            var ts = new Date(iso);
            td.textContent = ts.toLocaleTimeString();
            td.title = ts.toISOString() + (marketState ? ' · ' + (LIVE_STATE_TITLE[marketState] || '') : '');
            // Age only means "stale" while the market can actually print. Out of hours every
            // quote is hours old and none of them is a problem, so a closed listing gets the
            // muted treatment rather than an amber/red warning it can never clear.
            if (marketState === 'CLOSED') { td.classList.add('live-ts-old'); return; }
            var ageSec = (Date.now() - ts.getTime()) / 1000;
            if (ageSec < 300) td.classList.add('live-ts-fresh');         // < 5 min
            else if (ageSec < 1800) td.classList.add('live-ts-stale');   // < 30 min
            else td.classList.add('live-ts-old');                        // older — feed lagging
        }

        function applyDeltaClass(td, val) {
            td.classList.remove('live-pos', 'live-neg');
            if (val == null) return;
            if (val > 0) td.classList.add('live-pos');
            else if (val < 0) td.classList.add('live-neg');
        }

        // Background tint on the Last-price cell by trailing ~1-month change (fraction), matching
        // the holdings tables. Separate signal from the day's move — direction can differ.
        function applyMomClass(td, val) {
            td.classList.remove('mom-pos', 'mom-neg');
            if (val == null) { td.removeAttribute('title'); return; }
            if (val > 0) td.classList.add('mom-pos');
            else if (val < 0) td.classList.add('mom-neg');
            td.title = '1-month price change: ' + fmtLivePct(val);
        }

        function setText(id, value) {
            var el = document.getElementById(id);
            if (el) el.textContent = value;
        }

        function numOrNull(v) {
            if (v == null) return null;
            var n = typeof v === 'string' ? parseFloat(v) : v;
            return isNaN(n) ? null : n;
        }

        function fmtLiveNum(v, dp) {
            var n = numOrNull(v);
            if (n == null) return '—';
            return n.toLocaleString('en-GB', { minimumFractionDigits: dp, maximumFractionDigits: dp });
        }

        function fmtLivePct(v) {
            var n = numOrNull(v);
            if (n == null) return '—';
            return (n >= 0 ? '+' : '') + (n * 100).toFixed(2) + '%';
        }

        // ---- Scenario tab --------------------------------------------------
        var scenarioInitialized = false;
        var scenarioValueChart = null;
        var scenarioContribChart = null;
        var scenarioTableSortAttached = false;

        function setupScenario() {
            if (scenarioInitialized) return;
            scenarioInitialized = true;
            document.querySelectorAll('#scenario-presets .attr-preset').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    document.querySelectorAll('#scenario-presets .attr-preset').forEach(function (b) {
                        b.classList.remove('active');
                    });
                    btn.classList.add('active');
                    applyScenarioPreset(btn.dataset.preset);
                    runScenario();
                });
            });
            document.getElementById('scenario-run').addEventListener('click', function () {
                document.querySelectorAll('#scenario-presets .attr-preset').forEach(function (b) {
                    b.classList.remove('active');
                });
                runScenario();
            });
            document.getElementById('scenario-subs-save').addEventListener('click', saveScenarioSubs);
            applyScenarioPreset('2022');
            loadScenarioSubs();
            runScenario();
        }

        function applyScenarioPreset(preset) {
            var from, to;
            if (preset === 'last3y') {
                to = new Date();
                from = new Date();
                from.setFullYear(from.getFullYear() - 3);
            } else {
                var year = parseInt(preset, 10);
                from = new Date(year, 0, 1);
                to = new Date(year, 11, 31);
            }
            document.getElementById('scenario-from').value = isoDate(from);
            document.getElementById('scenario-to').value = isoDate(to);
        }

        function loadScenarioSubs() {
            fetch('/scenario/substitutes').then(function (r) { return r.json(); }).then(function (m) {
                var lines = Object.keys(m).map(function (k) { return k + '=' + m[k]; });
                document.getElementById('scenario-subs').value = lines.join('\n');
            }).catch(function () { /* leave textarea empty */ });
        }

        function saveScenarioSubs() {
            var body = document.getElementById('scenario-subs').value || '';
            var status = document.getElementById('scenario-subs-status');
            status.textContent = 'Saving…';
            var form = new URLSearchParams();
            form.set('body', body);
            fetch('/scenario/substitutes', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: form.toString()
            }).then(function (r) {
                if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Save failed'); });
                return r.json();
            }).then(function (m) {
                var lines = Object.keys(m).map(function (k) { return k + '=' + m[k]; });
                document.getElementById('scenario-subs').value = lines.join('\n');
                status.textContent = 'Saved (' + lines.length + ').';
            }).catch(function (err) {
                status.textContent = 'Error: ' + (err.message || String(err));
            });
        }

        function runScenario() {
            var from = document.getElementById('scenario-from').value;
            var to = document.getElementById('scenario-to').value;
            if (!from || !to) return;
            var gbpRate = document.getElementById('scenario-gbp-rate').value || '0';
            var usdRate = document.getElementById('scenario-usd-rate').value || '0';
            var status = document.getElementById('scenario-status');
            var errorBox = document.getElementById('scenario-error');
            errorBox.style.display = 'none';
            status.textContent = 'Working…';
            var url = '/scenario?from=' + encodeURIComponent(from) +
                    '&to=' + encodeURIComponent(to) +
                    '&gbpRate=' + encodeURIComponent(gbpRate) +
                    '&usdRate=' + encodeURIComponent(usdRate);
            fetch(url).then(function (r) {
                if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Request failed'); });
                return r.json();
            }).then(function (data) {
                renderScenario(data);
                status.textContent = '';
            }).catch(function (err) {
                status.textContent = '';
                errorBox.style.display = '';
                errorBox.textContent = err.message || String(err);
            });
        }

        function renderScenario(data) {
            document.getElementById('scenario-stats').style.display = 'flex';
            var pnl = parseFloat(data.pnlGbp);
            setMoneyStat('scenario-pnl', pnl);
            document.getElementById('scenario-return').textContent = fmtPct(data.periodReturn);
            document.getElementById('scenario-return').className = 'value ' +
                    (pnl > 0 ? 'pos' : pnl < 0 ? 'neg' : '');
            document.getElementById('scenario-start').textContent = fmtMoney(parseFloat(data.startTotalGbp));
            document.getElementById('scenario-start').className = 'value';
            document.getElementById('scenario-end').textContent = fmtMoney(parseFloat(data.endTotalGbp));
            document.getElementById('scenario-end').className = 'value';

            renderScenarioValueChart(data.timeline);
            renderScenarioContributorsChart(data.perSymbol);
            renderScenarioTable(data.perSymbol);
        }

        function renderScenarioValueChart(timeline) {
            var labels = timeline.map(function (p) { return p.date; });
            var values = timeline.map(function (p) { return parseFloat(p.totalValueGbp); });
            var ctx = document.getElementById('scenario-value-chart').getContext('2d');
            if (scenarioValueChart) scenarioValueChart.destroy();
            scenarioValueChart = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'Portfolio value (£)',
                        data: values,
                        borderColor: '#1f77b4',
                        backgroundColor: 'rgba(31, 119, 180, 0.10)',
                        borderWidth: 2,
                        fill: true,
                        pointRadius: 0,
                        tension: 0.1
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        x: { type: 'time', time: { unit: 'month' } },
                        y: {
                            title: { display: true, text: 'GBP' },
                            ticks: {
                                callback: function (v) {
                                    return '£' + Math.abs(v).toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                }
                            }
                        }
                    },
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            callbacks: { label: function (c) { return fmtMoney(c.parsed.y); } }
                        }
                    }
                }
            });
        }

        function renderScenarioContributorsChart(rows) {
            // perSymbol is already sorted by |P&L| desc. Top 12 keeps the chart readable.
            var top = rows.slice(0, 12);
            var labels = top.map(function (r) {
                return r.substituted ? (r.symbol + ' → ' + r.effectiveSymbol) : r.symbol;
            });
            var values = top.map(function (r) { return parseFloat(r.pnlGbp); });
            var colors = values.map(function (v) {
                return v >= 0 ? 'rgba(44, 160, 44, 0.85)' : 'rgba(192, 57, 43, 0.85)';
            });
            var ctx = document.getElementById('scenario-contributors-chart').getContext('2d');
            if (scenarioContribChart) scenarioContribChart.destroy();
            scenarioContribChart = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{ label: 'P&L (£)', data: values, backgroundColor: colors, borderWidth: 0 }]
                },
                options: {
                    indexAxis: 'y',
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        x: {
                            title: { display: true, text: 'P&L (£)' },
                            ticks: {
                                callback: function (v) {
                                    return (v < 0 ? '−£' : '£') +
                                        Math.abs(v).toLocaleString('en-GB', { maximumFractionDigits: 0 });
                                }
                            }
                        }
                    },
                    plugins: {
                        legend: { display: false },
                        tooltip: { callbacks: { label: function (c) { return fmtMoney(c.parsed.x); } } }
                    }
                }
            });
        }

        function renderScenarioTable(rows) {
            var tbody = document.querySelector('#scenario-table tbody');
            tbody.innerHTML = '';
            rows.forEach(function (r) {
                var pnl = parseFloat(r.pnlGbp);
                var applied = r.symbol;
                if (r.missing) applied = '— no data —';
                else if (r.substituted) {
                    applied = r.effectiveSymbol + (r.defaultSubstitute ? ' (default)' : ' (override)');
                }
                var tr = document.createElement('tr');
                tr.className = pnl > 0 ? 'pos' : pnl < 0 ? 'neg' : '';
                tr.innerHTML =
                    '<td class="txt">' + r.symbol + '</td>' +
                    '<td class="txt">' + applied + '</td>' +
                    '<td>' + fmtMoney(parseFloat(r.startValueGbp)) + '</td>' +
                    '<td>' + fmtMoney(parseFloat(r.endValueGbp)) + '</td>' +
                    '<td>' + fmtMoney(pnl) + '</td>' +
                    '<td>' + fmtPct(r.periodReturn) + '</td>';
                tbody.appendChild(tr);
            });
            if (!scenarioTableSortAttached) {
                scenarioTableSortAttached = true;
                attachSort(document.getElementById('scenario-table'));
            }
        }

        // ---- Watchlist (volatility-trading monitor) -------------------------

        var watchlistInited = false;
        var wlModalInited = false;      // popup listeners — bound by the Watchlist tab or the Live table
        var wlChart = null;
        var wlFundamentals = null;      // symbol -> quoteSummary, cached from /portfolio-fundamentals
        var wlCurrentSymbol = null;
        var wlCurrentCurrency = null;   // the open popup's price currency, for the range cells
        var wlSeriesToken = 0;          // newest /watchlist/series request; older replies are dropped
        var wlReturnsLoaded = false;
        var wlCurrentView = 'stats';    // 'stats' or 'returns'
        var wlListId = null;            // the named list on screen; every /watchlist call is scoped to it

        function setupWatchlist(listId) {
            ensureWlModalInited();
            if (!watchlistInited) {
                watchlistInited = true;
                document.getElementById('wl-add-btn').addEventListener('click', addWatchlistSymbol);
                document.getElementById('wl-add-symbol').addEventListener('keydown', function (e) {
                    if (e.key === 'Enter') addWatchlistSymbol();
                });
                document.getElementById('wl-refresh-btn').addEventListener('click', function () { loadWatchlist(); });
                document.getElementById('wl-view-stats').addEventListener('click', function () { switchWlView('stats'); });
                document.getElementById('wl-view-returns').addEventListener('click', function () { switchWlView('returns'); });
                setupWatchlistLists();
                setupWatchlistColumns();
                loadGiltHints();
            }
            var switched = listId != null && String(listId) !== String(wlListId);
            if (listId != null) wlListId = String(listId);
            if (switched) {
                // A different list is a different row set: drop the grid rather than reconcile
                // one list's symbols against another's, and re-arm the returns heatmap.
                wlResetGrid();
                // The heatmap only reloads itself when the view is switched into, so a list
                // change while it is already showing would otherwise leave the old list's grid up.
                if (wlCurrentView === 'returns') loadWlReturns(false);
            }
            loadWatchlist();
            wlStartPolling();
        }

        // ---- named lists ---------------------------------------------------
        //
        // The tab strip is server-rendered (PANEL_TO_GROUP is derived from the DOM before any
        // fetch could return), so these handlers keep the DOM and the server in step themselves
        // rather than reloading the page for every rename.

        function setupWatchlistLists() {
            // The strip arrives server-rendered, so seed the delete button's state from it.
            var initial = document.querySelectorAll('.wl-tab[data-list]');
            var del = document.getElementById('wl-list-delete');
            if (del && initial.length <= 1) {
                del.disabled = true;
                del.title = 'This is the only watchlist — rename it or empty it instead';
            }

            bindOnce(document.getElementById('wl-list-add'), 'click', function () {
                var name = window.prompt('Name the new watchlist (e.g. chips, banks):', '');
                if (name == null) return;                     // Cancel, as distinct from an empty name
                wlListAction('/watchlist/lists/create', { name: name }, function (lists) {
                    var created = wlNewestList(lists);
                    if (created) activatePanel('panel-watchlist', true, created.id);
                });
            });

            bindOnce(document.getElementById('wl-list-rename'), 'click', function () {
                if (wlListId == null) return;
                var name = window.prompt('Rename this watchlist:', wlListName(wlListId));
                if (name == null) return;
                wlListAction('/watchlist/lists/rename', { list: wlListId, name: name }, null);
            });

            bindOnce(document.getElementById('wl-list-delete'), 'click', function () {
                if (wlListId == null) return;
                // Say what is actually lost: membership always, the symbol itself only where no
                // other list holds it — and with it that symbol's alert settings.
                if (!window.confirm('Delete the "' + wlListName(wlListId) + '" watchlist?\n\n' +
                        'Symbols that are only on this list stop being watched and lose their alert ' +
                        'settings. Symbols on another list are unaffected.')) return;
                wlListAction('/watchlist/lists/delete', { list: wlListId }, function (lists) {
                    if (lists.length) activatePanel('panel-watchlist', true, lists[0].id);
                });
            });
        }

        /**
         * POST a list mutation, rebuild the tab strip from the response, then hand the new list
         * set to `after`. The server is the single source of the strip's contents, so a rejected
         * name (blank, duplicate, the last list) simply leaves the tabs as they were.
         */
        function wlListAction(url, params, after) {
            var body = new URLSearchParams();
            Object.keys(params).forEach(function (k) { body.set(k, params[k]); });
            wlSetStatus('');
            fetch(url, { method: 'POST', body: body })
                .then(function (r) {
                    if (!r.ok) return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    return r.json();
                })
                .then(function (lists) {
                    renderWlTabs(lists);
                    if (after) after(lists);
                })
                .catch(function (err) {
                    wlSetStatus(err && err.message ? err.message : 'Could not update the watchlists.');
                });
        }

        /** The one the server just made: highest id, since ids are monotonic. */
        function wlNewestList(lists) {
            var best = null;
            lists.forEach(function (l) { if (!best || l.id > best.id) best = l; });
            return best;
        }

        /**
         * Rebuild the list tabs in place, leaving every other tab in the strip alone. Buttons are
         * recreated rather than patched, so each one's click handler is bound exactly once here
         * — the page-load binding pass has long since run.
         */
        function renderWlTabs(lists) {
            var strip = document.querySelector('.tabs-secondary[data-group="group-tools"]');
            if (!strip) return;
            var addBtn = document.getElementById('wl-list-add');
            strip.querySelectorAll('.wl-tab[data-list]').forEach(function (t) { t.remove(); });
            lists.forEach(function (l) {
                var b = document.createElement('button');
                b.type = 'button';
                b.className = 'tab wl-tab';
                b.setAttribute('data-panel', 'panel-watchlist');
                b.setAttribute('data-list', l.id);
                b.textContent = l.name;
                b.addEventListener('click', function () {
                    activatePanel('panel-watchlist', true, l.id);
                });
                strip.insertBefore(b, addBtn);
            });
            // Only one list left means the delete would be refused; say so before it is clicked.
            var del = document.getElementById('wl-list-delete');
            if (del) {
                del.disabled = lists.length <= 1;
                del.title = del.disabled
                    ? 'This is the only watchlist — rename it or empty it instead'
                    : 'Delete this watchlist';
            }
        }

        // ---- column picker --------------------------------------------------
        //
        // Draft-then-commit: every change edits wlColsDraft, and only Save writes it. Cancel
        // therefore costs nothing and the grid never rebuilds mid-edit.

        var wlColsDraft = null;
        var wlColsGroup = null;         // the group whose fields are listed in the middle pane
        var wlColsEscBound = false;     // document-level, so it can't go through bindOnce

        function setupWatchlistColumns() {
            bindOnce(document.getElementById('wl-cols-btn'), 'click', openWlColsModal);
            bindOnce(document.getElementById('wl-cols-cancel'), 'click', closeWlColsModal);
            bindOnce(document.getElementById('wl-cols-save'), 'click', saveWlColumns);
            bindOnce(document.getElementById('wl-cols-reset'), 'click', function () {
                // An empty layout is stored as "no rows", which the resolver reads as the
                // default set — so reset and save are the same call, not a second verb.
                wlColsDraft = WlColumns.DEFAULTS.slice();
                renderWlColsChosen();
                renderWlColsFields();
            });
            bindOnce(document.getElementById('wl-cols-search'), 'input', renderWlColsFields);

            var modal = document.getElementById('wl-cols-modal');
            bindOnce(modal, 'click', function (e) {
                if (e.target.id === 'wl-cols-modal') closeWlColsModal();
            });
            if (!wlColsEscBound) {
                wlColsEscBound = true;
                document.addEventListener('keydown', function (e) {
                    if (e.key === 'Escape' && modal.classList.contains('open')) closeWlColsModal();
                });
            }
        }

        function openWlColsModal() {
            wlColsDraft = wlCols.slice();
            var reg = wlColumnRegistry();
            if (!wlColsGroup || !reg.groups.some(function (g) { return g.title === wlColsGroup; })) {
                wlColsGroup = reg.groups[0].title;
            }
            document.getElementById('wl-cols-listname').textContent =
                wlListId == null ? '' : ' — ' + wlListName(wlListId);
            document.getElementById('wl-cols-search').value = '';
            renderWlColsGroups();
            renderWlColsFields();
            renderWlColsChosen();
            document.getElementById('wl-cols-modal').classList.add('open');
            // Coverage marks need the snapshot; re-render once it lands rather than blocking the
            // modal on it.
            ensureWlFundamentals().then(function (ok) {
                if (ok && document.getElementById('wl-cols-modal').classList.contains('open')) {
                    renderWlColsFields();
                }
            }).catch(function () {});
        }

        /**
         * How many symbols on this list actually have a value for a fundamentals column.
         *
         * <p>Worth showing, because several metrics are empty for whole asset classes — an ETF
         * has no FCF margin, a bank no EV/EBITDA — and two (ROIC, FCF Growth YoY) are currently
         * empty for everything, since Yahoo stopped returning the quarterly statements they are
         * derived from. Picking one of those blind and finding a column of dashes is a wasted
         * trip; the count says so before the click. Null while the snapshot is still loading.
         */
        function wlColCoverage(key) {
            if (key.indexOf('f:') !== 0 || !wlFundamentals) return null;
            var tbody = document.querySelector('#watchlist-table tbody');
            if (!tbody || !tbody.children.length) return null;
            var have = 0, total = 0;
            Array.prototype.forEach.call(tbody.children, function (tr) {
                if (!tr.dataset.sym) return;
                total++;
                if (wlFundValue(tr.dataset.sym, key) != null) have++;
            });
            return total ? { have: have, total: total } : null;
        }

        function closeWlColsModal() {
            document.getElementById('wl-cols-modal').classList.remove('open');
            wlColsDraft = null;
        }

        function renderWlColsGroups() {
            var host = document.getElementById('wl-cols-groups');
            host.innerHTML = '';
            wlColumnRegistry().groups.forEach(function (g) {
                var b = document.createElement('button');
                b.type = 'button';
                b.textContent = g.title;
                b.className = g.title === wlColsGroup ? 'active' : '';
                b.addEventListener('click', function () {
                    wlColsGroup = g.title;
                    document.getElementById('wl-cols-search').value = '';
                    renderWlColsGroups();
                    renderWlColsFields();
                });
                host.appendChild(b);
            });
        }

        /** The middle pane: the active group's fields, or — while searching — every match. */
        function renderWlColsFields() {
            var reg = wlColumnRegistry();
            var host = document.getElementById('wl-cols-list');
            var q = (document.getElementById('wl-cols-search').value || '').trim().toLowerCase();
            host.innerHTML = '';
            var keys = [];
            if (q) {
                // Search spans every group — hunting for "FCF" should not need the right pane first.
                reg.groups.forEach(function (g) {
                    g.keys.forEach(function (k) {
                        if ((reg.byKey[k].label || '').toLowerCase().indexOf(q) >= 0) keys.push(k);
                    });
                });
            } else {
                var g = reg.groups.filter(function (x) { return x.title === wlColsGroup; })[0];
                keys = g ? g.keys : [];
            }
            if (!keys.length) {
                host.innerHTML = '<p class="muted" style="font-size:0.85rem">No matching column.</p>';
                return;
            }
            keys.forEach(function (k) {
                var m = reg.byKey[k];
                var label = document.createElement('label');
                if (m.title) label.title = m.title;
                var cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.checked = wlColsDraft.indexOf(k) >= 0;
                cb.addEventListener('change', function () {
                    wlColsDraft = cb.checked ? WlColumns.addFirst(wlColsDraft, k)
                                             : WlColumns.remove(wlColsDraft, k);
                    renderWlColsChosen();
                });
                label.appendChild(cb);
                label.appendChild(document.createTextNode(m.label));
                var cov = wlColCoverage(k);
                if (cov && cov.have < cov.total) {
                    var note = document.createElement('span');
                    note.className = 'muted';
                    note.style.fontSize = '0.75rem';
                    note.textContent = cov.have === 0
                        ? '  · no data for this list'
                        : '  · ' + cov.have + '/' + cov.total;
                    label.appendChild(note);
                }
                if (q) {
                    var grp = document.createElement('span');
                    grp.className = 'muted';
                    grp.style.fontSize = '0.75rem';
                    grp.textContent = '  · ' + m.group;
                    label.appendChild(grp);
                }
                host.appendChild(label);
            });
        }

        var wlColDragKey = null;        // column key being dragged in the picker, null when idle
        var wlColDropped = false;       // a real drop landed; without one the drag was cancelled

        /** The right pane: the chosen columns in order, between the two pinned ones. */
        function renderWlColsChosen() {
            var reg = wlColumnRegistry().byKey;
            var host = document.getElementById('wl-cols-chosen');
            host.innerHTML = '';
            if (!wlColsDraft.length) {
                host.innerHTML = '<p class="muted" style="font-size:0.8rem">No columns — saving now restores the default set.</p>';
                return;
            }
            wlColsDraft.forEach(function (k) {
                var m = reg[k] || { label: k };
                var chip = document.createElement('div');
                chip.className = 'wl-cols-chip';
                chip.draggable = true;                  // the whole chip: it holds buttons, no text input
                chip.dataset.key = k;
                var grip = document.createElement('span');
                grip.className = 'grip';
                grip.textContent = '\u283f';
                chip.appendChild(grip);
                var nm = document.createElement('span');
                nm.className = 'nm';
                nm.textContent = m.label;
                if (m.title) chip.title = m.title;
                chip.appendChild(nm);
                [['↑', true], ['↓', false]].forEach(function (d) {
                    var b = document.createElement('button');
                    b.type = 'button';
                    b.textContent = d[0];
                    b.title = d[1] ? 'Move left' : 'Move right';
                    b.addEventListener('click', function () {
                        wlColsDraft = WlColumns.move(wlColsDraft, k, d[1]);
                        renderWlColsChosen();
                    });
                    chip.appendChild(b);
                });
                var rm = document.createElement('button');
                rm.type = 'button';
                rm.className = 'rm';
                rm.textContent = '×';
                rm.title = 'Remove this column';
                rm.addEventListener('click', function () {
                    wlColsDraft = WlColumns.remove(wlColsDraft, k);
                    renderWlColsChosen();
                    renderWlColsFields();
                });
                chip.appendChild(rm);
                chip.addEventListener('dragstart', function (e) {
                    wlColDragKey = k;
                    wlColDropped = false;
                    chip.classList.add('wl-cols-dragging');
                    e.dataTransfer.effectAllowed = 'move';
                    e.dataTransfer.setData('text/plain', k);      // Firefox drags nothing without it
                });
                chip.addEventListener('dragend', function () {
                    chip.classList.remove('wl-cols-dragging');
                    if (!wlColDragKey) return;
                    // Where the chip ended up among its siblings is the new index. Nothing has
                    // been saved: this edits the draft, and Save is still the only write. A
                    // cancelled drag re-renders from the untouched draft, which is the undo.
                    var order = Array.prototype.indexOf.call(host.children, chip);
                    var dropped = wlColDropped;
                    wlColDragKey = null;
                    wlColDropped = false;
                    if (dropped) wlColsDraft = WlColumns.moveTo(wlColsDraft, k, order);
                    renderWlColsChosen();
                });
                host.appendChild(chip);
            });
            bindOnce(host, 'dragover', function (e) {
                if (!wlColDragKey) return;
                e.preventDefault();
                e.dataTransfer.dropEffect = 'move';
                var dragged = host.querySelector('.wl-cols-dragging');
                if (!dragged) return;
                var before = wlDropBefore(host, e.clientY, '.wl-cols-chip', 'wl-cols-dragging');
                if (before !== dragged) host.insertBefore(dragged, before);
            });
            bindOnce(host, 'drop', function (e) {
                if (!wlColDragKey) return;
                e.preventDefault();
                wlColDropped = true;
            });
        }

        function saveWlColumns() {
            var body = new URLSearchParams();
            wlAddList(body);
            wlColsDraft.forEach(function (k) { body.append('keys', k); });
            fetch('/watchlist/columns', { method: 'POST', body: body })
                .then(function (r) {
                    if (!r.ok) return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    return r.json();
                })
                .then(function (keys) {
                    wlApplyColumns(keys);
                    closeWlColsModal();
                    // Rebuild from the rows already in hand: the column set changed, so every
                    // cell is new and there is nothing to reconcile against.
                    wlResetGrid();
                    loadWatchlist();
                })
                .catch(function (err) {
                    wlSetStatus(err && err.message ? err.message : 'Could not save the column layout.');
                });
        }

        /** Empty the grid so the incoming list is built fresh instead of diffed against the old one. */
        function wlResetGrid() {
            var tbody = document.querySelector('#watchlist-table tbody');
            if (tbody) while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
            wlReturnsLoaded = false;
            wlLoadedOk = false;
            wlPending = {};             // an unsaved edit belongs to the row it was typed in
            wlSetStatus('');
        }

        /** `?list=` for every list-scoped call, so a poll can never patch another list's rows in. */
        function wlListParam(prefix) {
            return wlListId == null ? '' : (prefix || '?') + 'list=' + encodeURIComponent(wlListId);
        }

        /** Same list scoping for a form-encoded POST body. */
        function wlAddList(body) {
            if (wlListId != null) body.set('list', wlListId);
            return body;
        }

        function wlListName(id) {
            var tab = document.querySelector('.wl-tab[data-list="' + id + '"]');
            return tab ? tab.textContent.trim() : 'this watchlist';
        }

        // The popup's own listeners, bound independently of the Watchlist tab's: the Live table
        // opens the same modal, and a user who goes straight there would otherwise get a popup
        // whose Close button, Escape key and window toggle are all dead.
        function ensureWlModalInited() {
            if (wlModalInited) return;
            wlModalInited = true;
            document.getElementById('wl-modal-close').addEventListener('click', closeWlModal);
            document.getElementById('wl-modal').addEventListener('click', function (e) {
                if (e.target.id === 'wl-modal') closeWlModal();
            });
            document.addEventListener('keydown', function (e) {
                if (e.key === 'Escape' && document.getElementById('wl-modal').classList.contains('open')) {
                    closeWlModal();
                }
            });
            document.getElementById('wl-fund-toggle').addEventListener('click', function () {
                var body = document.getElementById('wl-fund-body');
                var open = body.style.display !== 'none';
                body.style.display = open ? 'none' : 'block';
                this.classList.toggle('open', !open);
            });
            document.getElementById('wl-window-toggle').addEventListener('click', function (e) {
                var btn = e.target.closest('button[data-window]');
                if (!btn || !wlCurrentSymbol) return;
                this.querySelectorAll('button').forEach(function (b) { b.classList.remove('active'); });
                btn.classList.add('active');
                loadWlSeries(wlCurrentSymbol, btn.dataset.window);
            });
        }

        /**
         * Open the price popup for a symbol from outside the Watchlist tab. The row is fetched on
         * demand (GET /watchlist/row) because those tables carry none of the popup's data — no
         * 52-week levels, vol term structure or fundamentals — and the symbol need not be on the
         * watchlist at all. The modal opens immediately with a loading line so a slow fetch doesn't
         * look like a dead click; wlCurrentSymbol doubles as the staleness latch, so a response
         * that lands after the user closed the popup or clicked another row is dropped.
         */
        function openSymbolModal(symbol) {
            ensureWlModalInited();
            wlCurrentSymbol = symbol;
            document.getElementById('wl-modal-title').textContent = symbol;
            document.getElementById('wl-modal-sub').textContent = 'Loading…';
            document.getElementById('wl-modal-stats').innerHTML = '';
            document.getElementById('wl-fund-body').style.display = 'none';
            document.getElementById('wl-fund-toggle').classList.remove('open');
            if (wlChart) { wlChart.destroy(); wlChart = null; }
            document.getElementById('wl-modal').classList.add('open');
            fetch('/watchlist/row?symbol=' + encodeURIComponent(symbol))
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                })
                .then(function (row) {
                    if (wlCurrentSymbol !== symbol) return;
                    openWlModal(row);
                    // A symbol with nothing cached renders as all dashes; say so rather than
                    // leaving the subtitle blank, which reads as a half-finished load.
                    if (row.missingData) {
                        document.getElementById('wl-modal-sub').textContent = row.missingReason
                            || ('No prices cached for ' + symbol + ' yet.');
                    }
                })
                .catch(function (err) {
                    if (wlCurrentSymbol !== symbol) return;
                    document.getElementById('wl-modal-sub').textContent =
                        'Could not load data for ' + symbol + ' (' + err.message + ').';
                });
        }

        /**
         * Populate the Symbol box's autocomplete with the conventional gilts currently priced
         * (GET /watchlist/gilts). Lets a gilt buy-limit alert be entered by picking a valid,
         * priced name (e.g. "GILT 4.75% 2043") — a mistyped coupon/year joins no price and would
         * silently never fire. Optional nicety, so failures are swallowed.
         */
        function loadGiltHints() {
            fetch('/watchlist/gilts').then(function (r) {
                return r.ok ? r.json() : [];
            }).then(function (gilts) {
                document.getElementById('wl-gilt-hints').innerHTML =
                    (gilts || []).map(function (g) {
                        return '<option value="' + escapeHtml(g) + '"></option>';
                    }).join('');
            }).catch(function () { /* autocomplete is optional */ });
        }

        function switchWlView(view) {
            wlCurrentView = view;
            var isStats = view === 'stats';
            document.getElementById('wl-view-stats').classList.toggle('active', isStats);
            document.getElementById('wl-view-returns').classList.toggle('active', !isStats);
            // The full-bleed wrapper is what shows and hides, not the table: hiding the table
            // alone would leave its breakout div holding open a viewport-wide empty band.
            var table = document.getElementById('watchlist-wrap');
            var panel = document.getElementById('wl-returns-panel');
            if (isStats) {
                panel.style.display = 'none';
                table.style.display = '';
                return;
            }
            table.style.display = 'none';
            panel.style.display = '';
            if (!wlReturnsLoaded) loadWlReturns(false);
        }

        var wlReturnsLoading = false;

        // Loads the returns heatmap. A fetch can die transiently (the dev server hot-restarts
        // under the tab — same failure mode the stats table self-heals from in wlPollTick), so
        // a first failure retries once after a short pause; after that the 30s poll keeps
        // retrying while the returns view is visible. wlReturnsLoaded is only set once a
        // render actually succeeded, so a failed attempt is always retryable.
        function loadWlReturns(isRetry) {
            if (wlReturnsLoading) return;
            wlReturnsLoading = true;
            var panel = document.getElementById('wl-returns-panel');
            panel.innerHTML = '<p class="muted" style="padding:0.5rem">Loading…</p>';
            fetch('/watchlist/returns' + wlListParam()).then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (data) {
                wlReturnsLoading = false;
                renderWatchlistReturns(data);
                wlReturnsLoaded = true;
            }).catch(function () {
                wlReturnsLoading = false;
                if (!isRetry) {
                    setTimeout(function () {
                        if (wlCurrentView === 'returns' && !wlReturnsLoaded) loadWlReturns(true);
                    }, 2500);
                } else {
                    panel.innerHTML = '<p class="muted" style="padding:0.5rem">Could not load returns — retrying shortly.</p>';
                }
            });
        }

        function wlSetStatus(msg) {
            document.getElementById('wl-status').textContent = msg || '';
        }

        // Optimistic overlay of just-saved threshold edits, keyed by symbol → {high,move,above,below}.
        // Guards against a slow, concurrently-issued /watchlist reload whose server read predates the
        // save commit reverting the input to a stale value (see wl-edit-sync.js for the full why).
        // The single writer of these fields is this client, so its own last save wins until it saves
        // again (or the save fails, or the page is reloaded).
        var wlPending = {};

        var wlFullLoadInFlight = false;
        function loadWatchlist() {
            if (wlFullLoadInFlight) return;                 // don't stack concurrent full loads
            wlFullLoadInFlight = true;                       // (Refresh spam + the 30s self-heal could
            wlReturnsLoaded = false;                         //  otherwise flap Loading…/could-not-load)
            if (wlCurrentView === 'returns') loadWlReturns(false);  // refresh the visible heatmap too
            wlSetStatus('Loading…');
            // Columns first, so the grid is built once in its final shape rather than painted in
            // the default layout and immediately rebuilt. Cached per list: only a list switch or
            // a save in the picker invalidates it.
            wlLoadColumns().then(function () {
            return fetch('/watchlist' + wlListParam()).then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (view) {
                renderWatchlist(view.rows || []);          // sets wlLoadedOk
                wlSetStatus('');
            });
            }).catch(function (err) {
                wlLoadedOk = false;                         // the poll will retry the full load
                var detail = err && err.message ? ' (' + err.message + ')' : '';
                wlSetStatus('Could not load watchlist' + detail + ' — retrying shortly.');
            }).finally(function () { wlFullLoadInFlight = false; });
        }

        var wlColsLoadedFor = null;     // the list id wlCols currently describes

        /**
         * Fetch this list's column layout, unless it is already the one in hand. A failure keeps
         * whatever layout is loaded rather than blanking the grid — the rows still matter.
         */
        function wlLoadColumns() {
            if (wlColsLoadedFor != null && String(wlColsLoadedFor) === String(wlListId)) {
                return Promise.resolve();
            }
            return fetch('/watchlist/columns' + wlListParam())
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                })
                .then(function (keys) { wlApplyColumns(keys); })
                .catch(function () { wlColsLoadedFor = wlListId; });
        }

        function wlApplyColumns(keys) {
            wlCols = WlColumns.resolve(keys, wlColumnRegistry().byKey);
            wlColsLoadedFor = wlListId;
            wlRenderedCols = null;                          // force the header to be rebuilt
        }

        // ── Near-real-time poll ─────────────────────────────────────────────────
        // Every WL_POLL_MS the watchlist patches only its fast-moving cells (price, momentum,
        // 5D/10D vol, distance-from-high/low, row alert colour) from the lightweight
        // /watchlist/quotes endpoint — no FX/ledger reads server-side, and cells are updated in
        // place so an active sort, a threshold edit, and scroll position all survive. The slower
        // position/fundamentals columns keep their last full-load values until the next full load
        // (manual refresh, tab re-entry, or an add/remove). The interval self-gates: it does nothing
        // unless the Watchlist tab is the visible one, in stats view, with the popup closed.
        var wlPollTimer = null;
        var wlLoadedOk = false;                            // true once a full /watchlist render succeeded
        var WL_POLL_MS = 30000;

        function wlStartPolling() {
            if (wlPollTimer) return;                       // idempotent — one interval per page
            // WlPoll.pollIntervalMs guards a runaway poll: this function can run during page-init —
            // when the watchlist is the restored active tab, the on-load activatePanel() calls
            // setupWatchlist() → wlStartPolling() *before* `var WL_POLL_MS = 30000` (declared below)
            // has been assigned, so the hoisted var reads `undefined`. setInterval(fn, undefined) is
            // treated as 0 → the browser clamps it to a ~4ms timer that hammers the server and makes
            // inline edits race and revert. pollIntervalMs() collapses undefined/0/NaN/tiny → 30000,
            // so the cadence is correct regardless of init order. (unit-tested: wl-poll.test.mjs)
            wlPollTimer = setInterval(wlPollTick, WlPoll.pollIntervalMs(WL_POLL_MS));
        }

        function wlPollTick() {
            if (document.hidden) return;                   // browser tab backgrounded
            if (wlDragSym) return;                         // a row is mid-drag; don't reload under it
            var panel = document.getElementById('panel-watchlist');
            if (!panel || !panel.classList.contains('active')) return;   // not the visible tab
            if (document.getElementById('wl-modal').classList.contains('open')) return;  // user in the popup
            // Self-heal: if the last full load failed (e.g. the server hot-restarted under the tab),
            // the quote poll has no rows to patch — retry the full load until it renders.
            if (!wlLoadedOk) { loadWatchlist(); return; }
            if (wlCurrentView !== 'stats') {               // returns heatmap doesn't move intraday,
                if (!wlReturnsLoaded) loadWlReturns(true); // but self-heal it if its load failed
                return;
            }
            fetch('/watchlist/quotes' + wlListParam()).then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (view) {
                wlApplyQuotes(view.rows || []);
                if (document.getElementById('wl-status').textContent) wlSetStatus('');  // recovered
            }).catch(function () { /* transient; the next tick retries */ });
        }

        // Build a symbol → <tr> index by iterating rows (never querySelector: watchlist symbols
        // contain '%' and spaces for gilts, a minefield to escape in a selector). Shared by the
        // quote poll, the full reconcile and the optimistic remove.
        function wlRowMap(tbody) {
            var map = {};
            var trs = tbody.children;
            for (var i = 0; i < trs.length; i++) map[trs[i].dataset.sym] = trs[i];
            return map;
        }

        // Patch only the fast-moving (intraday) cells of a row in place: price, momentum, 5D/10D
        // vol, distance-from-high/low, and the row alert colour. Touches no <input> and no slow
        // column, so it's safe over a row the user is editing, and is shared by the 30s quote poll
        // and the full reconcile. Reads only fields common to both Row and QuoteRow.
        function wlPatchQuoteCells(tr, r) {
            var priceCell = tr.querySelector('td[data-f="price"]');
            if (priceCell) priceCell.textContent = r.currentPrice == null ? '—' : fmtPrice(r.currentPrice, r.currency);
            wlPatchPct(tr, 'todayPct', r.todayPct, true);
            wlPatchPct(tr, 'pct2d', r.pct2d, true);
            wlPatchPct(tr, 'pct3d', r.pct3d, true);
            wlPatchPct(tr, 'pct5d', r.pct5d, true);
            wlPatchPct(tr, 'pct30d', r.pct30d, true);
            wlPatchPct(tr, 'pctFromHigh', r.pctFromHigh, false);
            wlPatchPct(tr, 'pctFromLow', r.pctFromLow, false);
            wlPatchVol(tr, 'vol5d', r.vol5d);
            wlPatchVol(tr, 'vol10d', r.vol10d);
            tr.className = rowAlertClass(r);               // recolour by the fresh alert flags
        }

        function wlApplyQuotes(rows) {
            var tbody = document.querySelector('#watchlist-table tbody');
            if (!tbody) return;
            var map = wlRowMap(tbody);
            rows.forEach(function (q) {
                var tr = map[q.symbol];                     // absent → symbol set changed since last full load
                if (tr) wlPatchQuoteCells(tr, q);
            });
        }

        function wlPatchPct(tr, field, frac, colour) {
            var td = tr.querySelector('td[data-f="' + field + '"]');
            if (!td) return;
            if (frac == null) { td.className = 'na'; td.textContent = '—'; return; }
            var n = parseFloat(frac) * 100;
            td.className = colour ? (n >= 0 ? 'wl-pos' : 'wl-neg') : '';
            td.textContent = (n >= 0 ? '+' : '') + n.toFixed(1) + '%';
        }

        function wlPatchVol(tr, field, frac) {
            var td = tr.querySelector('td[data-f="' + field + '"]');
            if (!td) return;
            td.className = frac == null ? 'na' : '';
            td.textContent = wlVolText(frac);
        }

        // Heads-up when a just-set target is already satisfied by the live price — e.g. an Above
        // level that sits at/below the current price (or a Below level at/above it). Such a target
        // is valid (it fires on the next tick, like a marketable limit order), but it's also the
        // tell-tale of a value typed into the wrong column, so we flag it rather than block it.
        function wlTargetHeadsUp(view, sym) {
            var rows = (view && view.rows) || [];
            var r = null;
            for (var i = 0; i < rows.length; i++) { if (rows[i].symbol === sym) { r = rows[i]; break; } }
            if (!r || r.currentPrice == null) return null;
            var cp = parseFloat(r.currentPrice);
            if (r.priceAbove != null && cp >= parseFloat(r.priceAbove)) {
                return '⚠ Above ' + fmtPrice(r.priceAbove, r.currency) + ' is already true — price ' +
                    fmtPrice(r.currentPrice, r.currency) + '. Alerts on next tick. Did you mean Below?';
            }
            if (r.priceBelow != null && cp <= parseFloat(r.priceBelow)) {
                return '⚠ Below ' + fmtPrice(r.priceBelow, r.currency) + ' is already true — price ' +
                    fmtPrice(r.currentPrice, r.currency) + '. Alerts on next tick. Did you mean Above?';
            }
            return null;
        }

        // Update just one row's alert colour from a freshly-returned view, WITHOUT rebuilding the
        // table. An inline threshold save fires on blur — often while the user has already clicked
        // into the next field — so re-rendering the tbody here would destroy the focused <input>
        // and drop their keystrokes (the "my edit didn't save" bug). Patch the class only; every
        // input keeps its value, focus and change-listener.
        function wlPatchRowState(view, sym) {
            var rows = (view && view.rows) || [];
            var r = null;
            for (var i = 0; i < rows.length; i++) { if (rows[i].symbol === sym) { r = rows[i]; break; } }
            if (!r) return null;
            var tbody = document.querySelector('#watchlist-table tbody');
            if (tbody) {
                var trs = tbody.querySelectorAll('tr');
                for (var j = 0; j < trs.length; j++) {
                    if (trs[j].dataset.sym === sym) { trs[j].className = rowAlertClass(r); break; }
                }
            }
            return r;
        }

        function addWatchlistSymbol() {
            var sym = document.getElementById('wl-add-symbol').value.trim().toUpperCase();
            if (!sym) return;
            var high = document.getElementById('wl-add-high').value.trim();
            var move = document.getElementById('wl-add-move').value.trim();
            var above = document.getElementById('wl-add-above').value.trim();
            var below = document.getElementById('wl-add-below').value.trim();
            var body = new URLSearchParams();
            body.set('symbol', sym);
            if (high) body.set('highPct', high);
            if (move) body.set('movePct', move);
            if (above) body.set('priceAbove', above);
            if (below) body.set('priceBelow', below);
            wlAddList(body);
            wlSetStatus('Adding ' + sym + ' — fetching prices…');
            fetch('/watchlist/add', { method: 'POST', body: body })
                .then(function (r) {
                    if (!r.ok) {
                        // 400/500 bodies are plain-text messages (GlobalExceptionAdvice) —
                        // surface them instead of a generic failure.
                        return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    }
                    return r.json();
                })
                .then(function (view) {
                    document.getElementById('wl-add-symbol').value = '';
                    document.getElementById('wl-add-high').value = '';
                    document.getElementById('wl-add-move').value = '';
                    document.getElementById('wl-add-above').value = '';
                    document.getElementById('wl-add-below').value = '';
                    renderWatchlist(view.rows || []);
                    wlSetStatus(wlTargetHeadsUp(view, sym) ||
                        (sym + ' added — data fills in as the backfill completes.'));
                    // Poll a couple of times so a freshly-added row picks up its backfilled prices.
                    setTimeout(loadWatchlist, 8000);
                    setTimeout(loadWatchlist, 30000);
                })
                .catch(function (err) {
                    var detail = err && err.message ? ' — ' + err.message : ' (is the app still running?)';
                    wlSetStatus('Could not add ' + sym + detail);
                });
        }

        // Surface a failed mutation instead of swallowing it. A transient SQLITE_BUSY (or any 4xx/5xx)
        // returns a plain-text body (GlobalExceptionAdvice); show it so the user can retry rather than
        // seeing a dead button. The existing rows keep their wired listeners (no re-render on failure),
        // so a retry works as soon as the DB is free.
        function removeWatchlistSymbol(sym) {
            // Scoped to this list: × takes the name off the list you are looking at, and the
            // server drops it from the watchlist entirely only if no other list still holds it.
            var body = new URLSearchParams(); body.set('symbol', sym);
            wlAddList(body);
            delete wlPending[sym];                          // gone — no overlay to defend
            // Optimistically drop the row now so × feels instant; the reconcile confirms on success,
            // and a failure re-syncs from the server so a wrongly-removed row comes straight back.
            var tbody = document.querySelector('#watchlist-table tbody');
            if (tbody) { var m = wlRowMap(tbody); if (m[sym]) m[sym].remove(); }
            fetch('/watchlist/remove', { method: 'POST', body: body })
                .then(function (r) {
                    if (!r.ok) return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    return r.json();
                })
                .then(function (view) { renderWatchlist(view.rows || []); })
                .catch(function (err) {
                    var detail = err && err.message ? ' — ' + err.message : '';
                    wlSetStatus('Could not remove ' + sym + detail + ' — restoring.');
                    loadWatchlist();
                });
        }

        function moveWatchlistSymbol(sym, dir) {
            var body = new URLSearchParams(); body.set('symbol', sym); body.set('dir', dir);
            wlAddList(body);
            fetch('/watchlist/move', { method: 'POST', body: body })
                .then(function (r) {
                    if (!r.ok) return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    return r.json();
                })
                .then(function (view) { renderWatchlist(view.rows || []); })
                .catch(function (err) {
                    var detail = err && err.message ? ' — ' + err.message : '';
                    wlSetStatus('Could not reorder ' + sym + detail + ' — try again.');
                });
        }

        // ---- drag to reorder ---------------------------------------------------
        //
        // The ↑↓ buttons stay: they are the keyboard path, the touch path (HTML5 drag events
        // never fire on a touch screen), and a one-place nudge is still quicker by button. Drag is
        // the addition for a name that has to travel.
        //
        // The drop posts the order *as displayed*. If a column sort is active the table is not in
        // stored order, and posting the visible order is what keeps the screen and the database
        // from disagreeing — dragging under a sort is how that arrangement becomes the manual one.

        var wlDragSym = null;              // symbol being dragged, null when no drag is in flight
        var wlDragRow = null;              // its <tr>, held so dragover doesn't re-index on every pixel
        var wlDragOrderAtStart = null;     // visible order when the drag began — nothing to save if unchanged
        var wlDropCommitted = false;       // a real drop landed; without one the drag was cancelled

        /** The symbols in the order the table is showing, which is what a drop saves. */
        function wlVisibleOrder() {
            var tbody = document.querySelector('#watchlist-table tbody');
            if (!tbody) return [];
            return Array.prototype.map.call(tbody.children, function (tr) { return tr.dataset.sym; })
                .filter(function (sym) { return !!sym; });
        }

        /**
         * The element the dragged one should be inserted before for a pointer at `y`, or null to
         * append. Judged by each candidate's midpoint, so the list opens up under the cursor
         * rather than waiting for the pointer to clear a whole row. Shared by the table and the
         * column picker's chips — both are a vertical list of equal-status items.
         */
        function wlDropBefore(container, y, selector, draggingClass) {
            var best = null, bestOffset = Number.NEGATIVE_INFINITY;
            Array.prototype.forEach.call(container.querySelectorAll(selector), function (el) {
                if (el.classList.contains(draggingClass)) return;
                var box = el.getBoundingClientRect();
                var offset = y - box.top - box.height / 2;
                if (offset < 0 && offset > bestOffset) { bestOffset = offset; best = el; }
            });
            return best;
        }

        // Bound on the <tbody>, not per row: rows come and go with every reconcile, and the tbody
        // node itself is never replaced. bindOnce keeps a re-render from stacking listeners.
        function wlEnsureRowDnd(tbody) {
            bindOnce(tbody, 'dragover', function (e) {
                if (!wlDragSym) return;                    // some other drag passing through
                e.preventDefault();                        // without this the drop never fires
                e.dataTransfer.dropEffect = 'move';
                if (!wlDragRow) return;
                var before = wlDropBefore(tbody, e.clientY, 'tr[data-sym]', 'wl-dragging');
                if (before !== wlDragRow) tbody.insertBefore(wlDragRow, before);   // live reflow; the drop commits
            });
            bindOnce(tbody, 'drop', function (e) {
                if (!wlDragSym) return;
                e.preventDefault();
                wlDropCommitted = true;
            });
            // dragend fires on the source (and bubbles here) whether the drop landed or not, so
            // the save and the cleanup live here and nowhere else. dragover has already reflowed
            // the rows, so a drag cancelled with Escape has to put them back: no drop, no save.
            bindOnce(tbody, 'dragend', function () {
                if (!wlDragSym) return;
                if (wlDragRow) wlDragRow.classList.remove('wl-dragging');
                var before = wlDragOrderAtStart;
                var dropped = wlDropCommitted;
                wlDragSym = null;
                wlDragRow = null;
                wlDragOrderAtStart = null;
                wlDropCommitted = false;
                if (!dropped) { wlApplyOrder(tbody, before); return; }
                var now = wlVisibleOrder();
                if (before && now.join('\u0001') !== before.join('\u0001')) saveWatchlistOrder(now);
            });
        }

        /** Put the rows back in a known order — the undo for a drag the user cancelled. */
        function wlApplyOrder(tbody, order) {
            if (!order) return;
            var map = wlRowMap(tbody);
            order.forEach(function (sym) { if (map[sym]) tbody.appendChild(map[sym]); });
        }

        function saveWatchlistOrder(order) {
            var body = new URLSearchParams();
            order.forEach(function (sym) { body.append('symbols', sym); });
            wlAddList(body);
            fetch('/watchlist/reorder', { method: 'POST', body: body })
                .then(function (r) {
                    if (!r.ok) return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    return r.json();
                })
                .then(function (view) { renderWatchlist(view.rows || []); })
                .catch(function (err) {
                    var detail = err && err.message ? ' — ' + err.message : '';
                    wlSetStatus('Could not save the new order' + detail + ' — restoring.');
                    loadWatchlist();                       // the server is the truth; put the rows back
                });
        }

        function saveWatchlistThreshold(sym, highPct, movePct, priceAbove, priceBelow) {
            var body = new URLSearchParams();
            body.set('symbol', sym);
            body.set('highPct', highPct);
            body.set('movePct', movePct);
            body.set('priceAbove', priceAbove == null ? '' : priceAbove);
            body.set('priceBelow', priceBelow == null ? '' : priceBelow);
            wlAddList(body);
            // Record the optimistic truth BEFORE the request goes out. Until a render carries these
            // values back from the server, any reload is treated as a stale read and must not revert
            // the inputs (see wl-edit-sync.js resolve). Replaced by the next save; cleared on failure.
            wlPending[sym] = WlEditSync.savedThresholds(highPct, movePct, priceAbove, priceBelow);
            fetch('/watchlist/threshold', { method: 'POST', body: body })
                .then(function (r) {
                    // Surface the real server message (GlobalExceptionAdvice sends plain text) rather
                    // than a generic failure, so a genuine persistence error isn't silently swallowed.
                    if (!r.ok) return r.text().then(function (t) { throw new Error((t || '').slice(0, 300)); });
                    return r.json();
                })
                .then(function (view) {
                    wlPatchRowState(view, sym);                 // recolour in place; never rebuild here
                    wlSetStatus(wlTargetHeadsUp(view, sym) || '');
                })
                .catch(function (err) {
                    // The save genuinely failed — drop the optimistic overlay so we stop showing a
                    // value that didn't persist, and pull the real server state back onto the screen.
                    delete wlPending[sym];
                    var detail = err && err.message ? ' — ' + err.message : '';
                    wlSetStatus('Could not update ' + sym + detail + ' — try again.');
                    loadWatchlist();
                });
        }

        // Optional `field` tags the cell with data-f so the near-real-time poll can patch it in place.
        function wlPctCell(frac, colour, field) {
            var fa = field ? ' data-f="' + field + '"' : '';
            if (frac == null) return '<td class="na"' + fa + '>—</td>';
            var n = parseFloat(frac) * 100;
            var cls = colour ? (n >= 0 ? ' class="wl-pos"' : ' class="wl-neg"') : '';
            return '<td' + cls + fa + '>' + (n >= 0 ? '+' : '') + n.toFixed(1) + '%</td>';
        }

        // Realized-vol cell — always positive, so no leading sign (matches the popup term structure).
        function wlVolCell(frac, field) {
            var fa = field ? ' data-f="' + field + '"' : '';
            return frac == null ? '<td class="na"' + fa + '>—</td>' : '<td' + fa + '>' + wlVolText(frac) + '</td>';
        }

        function wlGbpCell(v, colour, field) {
            var fa = field ? ' data-f="' + field + '"' : '';
            if (v == null) return '<td class="na"' + fa + '>—</td>';
            var n = parseFloat(v);
            var cls = colour ? (n >= 0 ? ' class="wl-pos"' : ' class="wl-neg"') : '';
            var s = (n < 0 ? '-£' : '£') + Math.abs(n).toLocaleString(undefined, { maximumFractionDigits: 0 });
            return '<td' + cls + fa + '>' + s + '</td>';
        }

        function renderWatchlistReturns(data) {
            var panel = document.getElementById('wl-returns-panel');
            if (!data || !data.dates || !data.dates.length) {
                panel.innerHTML = '<p class="muted" style="padding:0.5rem">No price history yet — data will appear once the daily price fetch has run.</p>';
                return;
            }
            var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
            var html = '<label style="display:inline-flex;align-items:center;gap:0.35rem;padding:0.25rem 0.5rem;font-size:0.8rem;cursor:pointer">' +
                '<input type="checkbox" id="wl-cum-all"/> Show cumulative (Σ) for all rows</label>';
            html += '<table><thead><tr>' +
                '<th style="min-width:80px">Symbol</th>';
            data.dates.forEach(function (d) {
                // "6/6" (day/month) — parse as UTC to avoid timezone shifts
                var parts = d.split('-');
                var label = +parts[2] + '/' + +parts[1];
                html += '<th style="min-width:46px">' + label + '</th>';
            });
            html += '</tr></thead><tbody>';

            // Shade a single return cell. `pct` is a percentage (null → blank). Shared by the
            // daily rows and the click-to-expand cumulative rows. `compact` drops the +/- sign
            // and rounds to a whole percent so the larger compounded numbers still fit the cell
            // — direction is already carried by the hue.
            function returnCell(pct, compact) {
                if (pct == null) return '<td style="color:#999">—</td>';
                var absP = Math.abs(pct);
                // Smooth, unbounded ramp: every return gets a distinct shade instead of
                // saturating at a hard cap. 1-e^(-|%|/4) rises fast near 0 (small moves
                // still visible) and keeps deepening for the big ±10-16% days.
                var intensity = 1 - Math.exp(-absP / 4);
                var bg, fg;
                var hue = pct >= 0 ? 142 : 4;
                if (isDark) {
                    // Dark mode: lightness 16–52% so cells read on dark backgrounds
                    var light = Math.round(16 + intensity * 36);
                    var sat = Math.round(38 + intensity * 57);
                    bg = 'hsl(' + hue + ',' + sat + '%,' + light + '%)';
                    fg = intensity > 0.28 ? '#fff' : '#aaa';
                } else {
                    // Light mode: lightness 93–38% (near-white → deep)
                    var light = Math.round(93 - intensity * 55);
                    var sat = Math.round(28 + intensity * 67);
                    bg = 'hsl(' + hue + ',' + sat + '%,' + light + '%)';
                    fg = intensity > 0.55 ? '#fff' : 'inherit';
                }
                var text = compact ? Math.round(pct).toString() : (pct >= 0 ? '+' : '') + pct.toFixed(1);
                return '<td style="background:' + bg + ';color:' + fg + '">' + text + '</td>';
            }

            data.rows.forEach(function (row, i) {
                html += '<tr class="wl-ret-row" data-idx="' + i + '" title="Click for cumulative returns"><td>' + row.symbol + '</td>';
                row.returns.forEach(function (r) {
                    html += returnCell(r == null ? null : parseFloat(r) * 100);
                });
                html += '</tr>';
            });
            html += '</tbody></table>';
            panel.innerHTML = html;

            var tbody = panel.querySelector('tbody');

            // The cumulative row already sitting above `tr`, or null.
            function cumRowFor(tr) {
                var prev = tr.previousElementSibling;
                return (prev && prev.classList.contains('wl-cum-row') &&
                    prev.getAttribute('data-for') === tr.getAttribute('data-idx')) ? prev : null;
            }
            function removeCumFor(tr) {
                var existing = cumRowFor(tr);
                if (existing) existing.remove();
            }
            function insertCumFor(tr) {
                if (cumRowFor(tr)) return;
                var row = data.rows[+tr.getAttribute('data-idx')];
                // Cumulative return through each column: compound the daily returns
                // (skipping missing days), so cell i = ∏(1+r_k) for k≤i, minus 1.
                var cum = document.createElement('tr');
                cum.className = 'wl-cum-row';
                cum.setAttribute('data-for', tr.getAttribute('data-idx'));
                var factor = 1, seen = false;
                var cells = '<td style="font-style:italic" title="Cumulative return">' + row.symbol + ' Σ</td>';
                row.returns.forEach(function (r) {
                    if (r == null) {
                        cells += returnCell(seen ? (factor - 1) * 100 : null, true);
                    } else {
                        factor *= 1 + parseFloat(r);
                        seen = true;
                        cells += returnCell((factor - 1) * 100, true);
                    }
                });
                cum.innerHTML = cells;
                tbody.insertBefore(cum, tr);
            }

            panel.querySelectorAll('.wl-ret-row').forEach(function (tr) {
                tr.addEventListener('click', function () {
                    // Toggle this row's cumulative line.
                    if (cumRowFor(tr)) removeCumFor(tr); else insertCumFor(tr);
                });
            });

            // Master switch: show/hide the Σ row for every ticker at once.
            document.getElementById('wl-cum-all').addEventListener('change', function () {
                var on = this.checked;
                panel.querySelectorAll('.wl-ret-row').forEach(function (tr) {
                    if (on) insertCumFor(tr); else removeCumFor(tr);
                });
            });
        }

        // ---- Full-row build + in-place patch (keyed reconciliation) ----------------
        // The grid is never torn down. Each symbol's <tr> — and critically its four threshold
        // <input>s — is created once by wlBuildRow and thereafter patched in place by wlPatchRow.
        // Inputs are never recreated and a focused input is never overwritten, so an in-progress
        // edit can't lose focus, caret or keystrokes and a stale reload can't revert a saved value.
        // This makes the old focus snapshot/restore dance (and its querySelector escaping) obsolete.

        // Inner HTML of the symbol cell (name + fetch state + held ● markers). A row with no prices
        // is one of two things and they must not look alike: missingReason set means Yahoo has
        // already rejected the ticker and waiting will never fill the row, so it says so and hangs
        // the diagnosis (and the ticker to map to) off the title. Only the unexplained case is
        // "(fetching…)", which is now a claim that something really is in flight.
        function wlSymHtml(r) {
            var state = '';
            if (r.missingData && r.missingReason) {
                state = ' <span class="wl-unresolved" title="' + escapeHtml(r.missingReason) +
                    '">(no Yahoo data)</span>';
            } else if (r.missingData) {
                state = ' <span class="muted" style="font-size:0.75rem">(fetching…)</span>';
            }
            return '<b>' + r.symbol + '</b>' + state +
                (r.held ? ' <span class="muted" title="You hold this" style="font-size:0.75rem">●</span>' : '');
        }

        // Full cell markup for a brand-new row. data-f tags let wlPatchRow find each cell later.
        // Threshold inputs seed from resolve() so the optimistic overlay wins over a stale read.
        // ---- the column registry --------------------------------------------------
        //
        // One entry per column the picker can offer: its label, its sort type, and how to render
        // a cell from the server Row. Built-ins read the Row directly; an `f:` column reads the
        // per-session fundamentals cache instead, which is why its cell can be built before that
        // cache has landed and patched afterwards (wlPatchFundCells).
        //
        // Every cell carries data-f, so wlPatchRow finds cells by name rather than by index and
        // the whole reconcile/poll machinery is indifferent to the column order.

        var WL_BUILTIN_GROUPS = [
            { title: 'Price & momentum', fields: [
                ['price', 'Price', 'Latest price in the instrument\'s own currency'],
                ['todayPct', 'Today %', 'Live move vs the previous session\'s close (1 trading session)'],
                ['pct2d', '2d %', 'Return over the last 2 trading sessions (today + the previous session)'],
                ['pct3d', '3d %', 'Return over the last 3 trading sessions'],
                ['pct5d', '5d %', 'Return over the last 5 trading sessions'],
                ['pct10d', '10d %', 'Return over the last 10 trading sessions'],
                ['pct30d', '30d %', 'Return over the last 30 trading sessions']
            ]},
            { title: '52-week range', fields: [
                ['pctFromHigh', '% vs High', 'Distance from 52-week high (negative = below)'],
                ['pctFromLow', '% vs Low', 'Distance above 52-week low']
            ]},
            { title: 'Realized volatility', fields: [
                ['vol5d', 'Vol 5D', 'Annualised realized volatility, 5-day (from 1-minute intraday returns)'],
                ['vol10d', 'Vol 10D', 'Annualised realized volatility, 10-day (from 1-minute intraday returns)'],
                ['vol30d', 'Vol 30D', 'Annualised realized volatility, 30-day (from daily closes)'],
                ['vol1y', 'Vol 1Y', 'Annualised realized volatility, 1-year (from daily closes)']
            ]},
            { title: 'Your position', fields: [
                ['trailingPe', 'P/E', 'Trailing twelve-month price/earnings, from the fundamentals cache'],
                ['beta', 'Beta', 'Sensitivity to the wider market, from the fundamentals cache'],
                ['position', 'Pos £', 'Market value of your holding, GBP (blank if not held)'],
                ['gain', 'Gain', 'Unrealized gain on the holding, GBP'],
                ['gainPct', 'Gain %', 'Unrealized gain on the holding, per cent']
            ]},
            { title: 'Alert triggers', fields: [
                ['thrHigh', 'Near-hi %', 'Alert when within this % of the 52-week high'],
                ['thrMove', 'Move %', 'Alert when |today\'s move| exceeds this %'],
                ['thrAbove', 'Above', 'Alert when the price rises to or above this level (price\'s own currency)'],
                ['thrBelow', 'Below', 'Alert when the price falls to or below this level (price\'s own currency)']
            ]}
        ];

        /**
         * The quoteSummary fields that sit at the top level of the record rather than inside its
         * `extra` map. Price, P/E and beta are deliberately absent: the Row already carries them,
         * so they are built-ins and offering a second copy here would be two columns of one fact.
         */
        var WL_FUND_TOP = {
            marketCap: ['Market cap', 'cap', 'Share price × shares outstanding.'],
            forwardPe: ['Fwd P/E', 'ratio', 'Price ÷ next year\'s consensus earnings per share.'],
            pegRatio: ['PEG', 'ratio', 'Forward P/E ÷ expected earnings growth. Below 1 is often read as growth being under-priced.'],
            targetMeanPrice: ['Target', 'price', 'Mean of analysts\' 12-month price targets, in the instrument\'s own currency.'],
            week52High: ['52w high', 'price', 'Highest price over the past 52 weeks.'],
            week52Low: ['52w low', 'price', 'Lowest price over the past 52 weeks.']
        };

        var wlRegistryCache = null;

        /**
         * key -> { label, title, group, type, builtin }. Fundamentals entries are derived from
         * fundamentalsFieldGroups() — the same single home the Fundamentals tab and the popup
         * read — so a metric added there appears in this picker with its label, format and
         * tooltip already written.
         */
        function wlColumnRegistry() {
            if (wlRegistryCache) return wlRegistryCache;
            var reg = {};
            var groups = [];
            WL_BUILTIN_GROUPS.forEach(function (g) {
                var keys = [];
                g.fields.forEach(function (f) {
                    reg[f[0]] = { label: f[1], title: f[2], group: g.title, builtin: true };
                    keys.push(f[0]);
                });
                groups.push({ title: g.title, keys: keys });
            });

            var topKeys = [];
            Object.keys(WL_FUND_TOP).forEach(function (k) {
                var d = WL_FUND_TOP[k];
                var key = 'f:' + k;
                reg[key] = { label: d[0], title: d[2], group: 'Fundamentals — headline', type: d[1], top: true };
                topKeys.push(key);
            });
            groups.push({ title: 'Fundamentals — headline', keys: topKeys });

            fundamentalsFieldGroups().forEach(function (g) {
                var keys = [];
                g.fields.forEach(function (f) {
                    var key = 'f:' + f[0];
                    reg[key] = { label: f[1], title: f[3], group: g.title, type: f[2], anchor: f[5],
                                 source: f[6] };
                    keys.push(key);
                });
                groups.push({ title: g.title, keys: keys });
            });
            wlRegistryCache = { byKey: reg, groups: groups };
            return wlRegistryCache;
        }

        /** The columns this list is showing, between the pinned Symbol and actions cells. */
        var wlCols = WlColumns.DEFAULTS.slice();
        var wlRenderedCols = null;      // what the <thead> currently holds, to avoid needless rebuilds

        /**
         * One fundamentals value for a symbol, or null. Null is rendered '—' rather than 0 —
         * an ETF has no FCF margin, and a nil is not a zero.
         */
        function wlFundValue(symbol, key) {
            var q = wlFundRow(symbol);
            if (!q) return null;
            var bare = key.slice(2);
            if (WL_FUND_TOP[bare]) return q[bare] == null ? null : q[bare];
            return fundFieldValue(q, bare, (wlColumnRegistry().byKey[key] || {}).source);
        }

        /** The cached fundamentals row behind a watchlist symbol, or null while it is unusable. */
        function wlFundRow(symbol) {
            var q = wlFundamentals && wlFundamentals[symbol];
            return (!q || q.missing) ? null : q;
        }

        /** A fundamentals cell: formatted text plus the raw number for the sorter. */
        function wlFundCell(r, key) {
            var meta = wlColumnRegistry().byKey[key] || {};
            var v = wlFundValue(r.symbol, key);
            if (v == null) return '<td class="na" data-f="' + key + '">—</td>';
            var type = meta.type || 'ratio';
            // The rendered text carries %, £ and T/B/M suffixes; parseVal copes, but handing the
            // sorter the raw number is exact and free. A date sorts on its own epoch: parseFloat
            // on an ISO day reads the year alone, which would shuffle a whole quarter together.
            var n = type === 'date' ? Date.parse(v) : parseFloat(v);
            return '<td data-f="' + key + '"' +
                (isFinite(n) ? ' data-sort-value="' + n + '"' : '') + '>' +
                fmtSnapValue(v, type, r.currency, wlFundRow(r.symbol)) + '</td>';
        }

        function wlThrCell(r, kind, extraCls) {
            var thr = WlEditSync.resolve(r, wlPending[r.symbol]).values;
            return '<td><input class="wl-thr-input' + (extraCls || '') + '" data-sym="' + r.symbol +
                '" data-kind="' + kind + '" type="text" value="' + thr[kind] + '"/></td>';
        }

        /** Render one column's <td> for one row. */
        function wlCellHtml(r, key) {
            if (key.indexOf('f:') === 0) return wlFundCell(r, key);
            switch (key) {
                case 'price':
                    return '<td data-f="price">' +
                        (r.currentPrice == null ? '—' : fmtPrice(r.currentPrice, r.currency)) + '</td>';
                case 'todayPct': return wlPctCell(r.todayPct, true, 'todayPct');
                case 'pct2d': return wlPctCell(r.pct2d, true, 'pct2d');
                case 'pct3d': return wlPctCell(r.pct3d, true, 'pct3d');
                case 'pct5d': return wlPctCell(r.pct5d, true, 'pct5d');
                case 'pct10d': return wlPctCell(r.pct10d, true, 'pct10d');
                case 'pct30d': return wlPctCell(r.pct30d, true, 'pct30d');
                case 'pctFromHigh': return wlPctCell(r.pctFromHigh, false, 'pctFromHigh');
                case 'pctFromLow': return wlPctCell(r.pctFromLow, false, 'pctFromLow');
                case 'vol5d': return wlVolCell(r.vol5d, 'vol5d');
                case 'vol10d': return wlVolCell(r.vol10d, 'vol10d');
                case 'vol30d': return wlVolCell(r.vol30d, 'vol30d');
                case 'vol1y': return wlVolCell(r.vol1y, 'vol1y');
                case 'trailingPe':
                    return '<td data-f="pe">' +
                        (r.trailingPe == null ? '—' : parseFloat(r.trailingPe).toFixed(1)) + '</td>';
                case 'beta':
                    return '<td data-f="beta">' +
                        (r.beta == null ? '—' : parseFloat(r.beta).toFixed(2)) + '</td>';
                case 'position': return wlGbpCell(r.held ? r.positionValueGbp : null, false, 'position');
                case 'gain': return wlGbpCell(r.held ? r.gainGbp : null, true, 'gain');
                case 'gainPct': return wlPctCell(r.held ? r.gainPct : null, true, 'gainPct');
                case 'thrHigh': return wlThrCell(r, 'high');
                case 'thrMove': return wlThrCell(r, 'move');
                case 'thrAbove': return wlThrCell(r, 'above', ' wl-price-input');
                case 'thrBelow': return wlThrCell(r, 'below', ' wl-price-input');
                default: return '<td class="na">—</td>';
            }
        }

        function wlRowCellsHtml(r) {
            // The grip sits in the Symbol cell, not out at the actions end: Symbol is the sticky
            // column, so the handle stays reachable however far right the table is scrolled. The
            // name lives in its own span because wlPatchRow rewrites it on every poll tick, and
            // rewriting the whole cell would throw away the grip's dragstart listener with it.
            var html = '<td data-f="sym">' +
                '<button class="wl-grip" draggable="true" title="Drag to reorder">⠿</button>' +
                '<span class="wl-sym-name">' + wlSymHtml(r) + '</span></td>';
            wlCols.forEach(function (key) { html += wlCellHtml(r, key); });
            return html +
                '<td class="wl-actions">' +
                    '<button class="wl-move" data-sym="' + r.symbol + '" data-dir="up" title="Move up">↑</button>' +
                    '<button class="wl-move" data-sym="' + r.symbol + '" data-dir="down" title="Move down">↓</button>' +
                    '<button class="wl-remove" data-sym="' + r.symbol + '" title="Remove">×</button>' +
                '</td>';
        }

        /**
         * Rebuild the header to match wlCols, but only when it has actually changed — a rebuild
         * discards the <th> nodes and with them the sort listeners and the active sort marker.
         */
        function wlSyncHeader() {
            if (wlRenderedCols && wlRenderedCols.join('\u0001') === wlCols.join('\u0001')) return;
            var table = document.getElementById('watchlist-table');
            if (!table) return;
            var reg = wlColumnRegistry().byKey;
            var html = '<tr><th data-sort="text">Symbol</th>';
            wlCols.forEach(function (key) {
                var m = reg[key] || { label: key };
                // The four alert inputs are not values, so they opt out of sorting the same way
                // they always have — by carrying no data-sort.
                var sortable = key.indexOf('thr') !== 0;
                // The label sits in a span of its own so it can wrap inside a width the CSS
                // caps — a <th> in an auto-layout table ignores max-width, and without the cap a
                // label like '% Held by Institutions' sets the whole column's width.
                html += '<th' + (sortable ? ' data-sort="num"' : '') +
                    (m.title ? ' title="' + escapeHtml(m.title) + '"' : '') + '>' +
                    '<span class="wl-th">' + escapeHtml(m.label) + '</span></th>';
            });
            html += '<th></th></tr>';
            table.querySelector('thead').innerHTML = html;
            wlRenderedCols = wlCols.slice();
            // New <th> nodes carry no listeners, so this re-attaches rather than double-binds.
            attachSort(table);
        }

        // Create one fully-wired row. Its click/change/keydown/button listeners are attached once
        // here and survive every later patch (the node is never replaced). The row keeps its latest
        // server data on tr._wlRow so the popup opens with fresh values after any number of patches.
        function wlBuildRow(r) {
            var tr = document.createElement('tr');
            tr.dataset.sym = r.symbol;
            tr._wlRow = r;
            tr.className = rowAlertClass(r);
            tr.innerHTML = wlRowCellsHtml(r);
            // Row click opens the popup, except when interacting with inputs/buttons.
            tr.addEventListener('click', function (e) {
                if (e.target.closest('input, button')) return;
                openWlModal(tr._wlRow);
            });
            tr.querySelector('.wl-remove').addEventListener('click', function () {
                removeWatchlistSymbol(this.dataset.sym);
            });
            tr.querySelectorAll('.wl-move').forEach(function (btn) {
                btn.addEventListener('click', function () { moveWatchlistSymbol(this.dataset.sym, this.dataset.dir); });
            });
            // Only the grip is draggable, never the <tr>: a draggable ancestor stops text being
            // selected inside the threshold inputs in WebKit, and the row's own click opens the
            // popup. The drag image is set to the row so what moves under the cursor is the row.
            tr.querySelector('.wl-grip').addEventListener('dragstart', function (e) {
                wlDragSym = tr.dataset.sym;
                wlDragRow = tr;
                wlDropCommitted = false;
                wlDragOrderAtStart = wlVisibleOrder();
                tr.classList.add('wl-dragging');
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', tr.dataset.sym);   // Firefox drags nothing without it
                if (e.dataTransfer.setDragImage) e.dataTransfer.setDragImage(tr, 12, 10);
            });
            tr.querySelectorAll('.wl-thr-input').forEach(function (inp) {
                inp.addEventListener('change', function () {
                    var row = this.closest('tr');
                    var high = row.querySelector('.wl-thr-input[data-kind="high"]').value.trim();
                    var move = row.querySelector('.wl-thr-input[data-kind="move"]').value.trim();
                    var above = row.querySelector('.wl-thr-input[data-kind="above"]').value.trim();
                    var below = row.querySelector('.wl-thr-input[data-kind="below"]').value.trim();
                    saveWatchlistThreshold(this.dataset.sym, high, move, above, below);
                });
                // Enter commits: the inputs aren't in a <form>, so blur (which fires change) is the save.
                inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') this.blur(); });
            });
            return tr;
        }

        function wlPatchPe(tr, r) {
            var td = tr.querySelector('td[data-f="pe"]');
            if (td) td.textContent = r.trailingPe == null ? '—' : parseFloat(r.trailingPe).toFixed(1);
        }

        function wlPatchGbpCell(tr, field, v, colour) {
            var td = tr.querySelector('td[data-f="' + field + '"]');
            if (!td) return;
            if (v == null) { td.className = 'na'; td.textContent = '—'; return; }
            var n = parseFloat(v);
            td.className = colour ? (n >= 0 ? 'wl-pos' : 'wl-neg') : '';
            td.textContent = (n < 0 ? '-£' : '£') + Math.abs(n).toLocaleString(undefined, { maximumFractionDigits: 0 });
        }

        // Update the four threshold inputs from the server row, EXCEPT one the user is editing right
        // now (never clobber a focused input). resolve() keeps a just-saved value on screen until the
        // server echoes it back, so even a stale read can't revert it.
        function wlPatchInputs(tr, r) {
            var thr = WlEditSync.resolve(r, wlPending[r.symbol]).values;
            WlEditSync.KINDS.forEach(function (kind) {
                var inp = tr.querySelector('.wl-thr-input[data-kind="' + kind + '"]');
                if (!inp || document.activeElement === inp) return;
                inp.value = thr[kind];
            });
        }

        // Patch every cell of an existing row in place: fast cells + slow columns + inputs.
        function wlPatchRow(tr, r) {
            tr._wlRow = r;
            var symName = tr.querySelector('td[data-f="sym"] .wl-sym-name');
            if (symName) symName.innerHTML = wlSymHtml(r);   // not the whole cell: the grip stays
            wlPatchQuoteCells(tr, r);                       // price, momentum, 5D/10D vol, colour
            wlPatchVol(tr, 'vol30d', r.vol30d);
            wlPatchVol(tr, 'vol1y', r.vol1y);
            wlPatchPe(tr, r);
            wlPatchGbpCell(tr, 'position', r.held ? r.positionValueGbp : null, false);
            wlPatchGbpCell(tr, 'gain', r.held ? r.gainGbp : null, true);
            wlPatchInputs(tr, r);
        }

        // Keyed reconciliation — the single render path for full loads, adds, removes and reorders.
        // Existing rows are patched in place, new symbols are built and inserted, gone symbols are
        // dropped, and order is fixed by moving existing nodes. The tbody is NEVER wiped, so inputs,
        // focus, caret, listeners and the intraday cells all survive. Only a row whose position
        // actually changed is moved, so a focused input isn't blurred by a needless reorder.
        function renderWatchlist(rows) {
            wlLoadedOk = true;                             // reaching a render means the fetch succeeded
            var table = document.getElementById('watchlist-table');
            var wrap = document.getElementById('watchlist-wrap');
            var empty = document.getElementById('wl-empty');
            var toggle = document.getElementById('wl-view-toggle');
            var tbody = table.querySelector('tbody');
            wlEnsureRowDnd(tbody);                         // idempotent; the tbody node is never replaced

            if (!rows.length) {
                while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
                wrap.style.display = 'none';
                empty.style.display = 'block';
                toggle.style.display = 'none';
                return;
            }
            empty.style.display = 'none';
            toggle.style.display = '';
            if (wlCurrentView === 'stats') wrap.style.display = '';    // only shown in stats view

            wlSyncHeader();
            // Any f: column needs the snapshot; ask for it now and patch those cells when it
            // arrives, so the grid is never held up waiting for it.
            if (wlCols.some(function (k) { return k.indexOf('f:') === 0; })) {
                ensureWlFundamentals().then(function (ok) { if (ok) wlPatchFundCells(); }).catch(function () {});
            }
            var existing = wlRowMap(tbody);
            var seen = {};
            rows.forEach(function (r, i) {
                var tr = existing[r.symbol];
                if (tr) wlPatchRow(tr, r); else tr = wlBuildRow(r);
                seen[r.symbol] = true;
                // A render landing mid-drag must not yank the row out from under the cursor, so
                // while a drag is in flight only a row that isn't in the table yet is placed;
                // the drop's own save is what settles the order a moment later.
                if (tbody.children[i] !== tr && (!wlDragSym || tr.parentNode !== tbody)) {
                    tbody.insertBefore(tr, tbody.children[i] || null);
                }
            });
            Object.keys(existing).forEach(function (sym) {
                if (!seen[sym]) existing[sym].remove();     // symbol no longer on the list
            });

            // Sorting is bound by wlSyncHeader, which owns the <th> nodes: a one-shot latch here
            // would have left the listeners on header cells a column change had already discarded.
        }

        function rowAlertClass(r) {
            var up = r.moveDirection >= 0;
            if (r.nearHigh && r.bigMove) return up ? 'wl-both-up' : 'wl-both-down';
            if (r.bigMove) return up ? 'wl-move-up' : 'wl-move-down';
            // Explicit price-target hits rank above the passive near-high highlight.
            if (r.aboveLevel) return 'wl-price-above';
            if (r.belowLevel) return 'wl-price-below';
            if (r.nearHigh) return 'wl-near-high';
            return '';
        }

        function openWlModal(r) {
            wlCurrentSymbol = r.symbol;
            wlCurrentCurrency = r.currency;
            document.getElementById('wl-modal-title').textContent = r.symbol +
                (r.currency ? ' · ' + r.currency : '');
            var today = r.todayPct == null ? '—' : (parseFloat(r.todayPct) * 100).toFixed(1) + '%';
            document.getElementById('wl-modal-sub').textContent =
                (r.currentPrice == null ? '' : fmtPrice(r.currentPrice, r.currency) + '  ·  today ' + today);

            var stats = document.getElementById('wl-modal-stats');
            stats.innerHTML = '';
            stats.appendChild(wlStat('Today', wlPctText(r.todayPct)));
            stats.appendChild(wlStat('2d', wlPctText(r.pct2d)));
            stats.appendChild(wlStat('3d', wlPctText(r.pct3d)));
            stats.appendChild(wlStat('5d', wlPctText(r.pct5d)));
            stats.appendChild(wlStat('10d', wlPctText(r.pct10d)));
            stats.appendChild(wlStat('30d', wlPctText(r.pct30d)));
            stats.appendChild(wlStat('52wk high', r.week52High == null ? '—' : fmtPrice(r.week52High, r.currency)));
            stats.appendChild(wlStat('52wk low', r.week52Low == null ? '—' : fmtPrice(r.week52Low, r.currency)));
            // Placeholders for the selected window's own high/low — filled by applyWlRange when
            // the series lands, and relabelled every time the window changes. They sit next to
            // the fixed 52-week pair on purpose: the 52-week levels are the alert's yardstick and
            // stay put, these two follow whatever the chart is showing.
            stats.appendChild(wlRangeStat('high'));
            stats.appendChild(wlRangeStat('low'));
            stats.appendChild(wlStat('% vs high', wlPctText(r.pctFromHigh)));
            if (r.priceAbove != null) stats.appendChild(wlStat('Above', fmtPrice(r.priceAbove, r.currency)));
            if (r.priceBelow != null) stats.appendChild(wlStat('Below', fmtPrice(r.priceBelow, r.currency)));
            stats.appendChild(wlStat('P/E', r.trailingPe == null ? '—' : parseFloat(r.trailingPe).toFixed(1)));
            stats.appendChild(wlStat('Beta', r.beta == null ? '—' : parseFloat(r.beta).toFixed(2)));
            if (r.held) {
                stats.appendChild(wlStat('Position', r.positionValueGbp == null ? '—' : fmtPrice(r.positionValueGbp, 'GBP')));
                stats.appendChild(wlStat('Gain', r.gainGbp == null ? '—' : fmtPrice(r.gainGbp, 'GBP')));
            }
            loadWlVol(r.symbol, stats);

            // Reset fundamentals section collapsed.
            var fbody = document.getElementById('wl-fund-body');
            fbody.style.display = 'none';
            document.getElementById('wl-fund-toggle').classList.remove('open');
            renderWlFundamentals(r.symbol);

            // Default window 5D.
            document.querySelectorAll('#wl-window-toggle button').forEach(function (b) {
                b.classList.toggle('active', b.dataset.window === '5D');
            });
            document.getElementById('wl-modal').classList.add('open');
            loadWlSeries(r.symbol, '5D');
        }

        // Realized-vol term structure (5D/10D intraday-derived, 30D/1Y daily). Appended
        // asynchronously; guarded against a stale response landing after the user switched rows.
        function loadWlVol(symbol, statsEl) {
            fetch('/watchlist/vol?symbol=' + encodeURIComponent(symbol))
                .then(function (r) { return r.json(); })
                .then(function (v) {
                    if (wlCurrentSymbol !== symbol) return;
                    statsEl.appendChild(wlStat('Vol 5D', wlVolText(v.vol5d)));
                    statsEl.appendChild(wlStat('Vol 10D', wlVolText(v.vol10d)));
                    statsEl.appendChild(wlStat('Vol 30D', wlVolText(v.vol30d)));
                    statsEl.appendChild(wlStat('Vol 1Y', wlVolText(v.vol1y)));
                })
                .catch(function () { /* leave stats without vol */ });
        }

        function wlStat(label, value) {
            var d = document.createElement('div');
            d.className = 'snap-kv';
            d.innerHTML = '<span class="k">' + label + '</span><span class="v">' + value + '</span>';
            return d;
        }

        function wlPctText(frac) {
            if (frac == null) return '—';
            var n = parseFloat(frac) * 100;
            return (n >= 0 ? '+' : '') + n.toFixed(1) + '%';
        }

        // Volatility is always positive — no leading sign.
        function wlVolText(frac) {
            return frac == null ? '—' : (parseFloat(frac) * 100).toFixed(1) + '%';
        }

        function closeWlModal() {
            document.getElementById('wl-modal').classList.remove('open');
            wlCurrentSymbol = null;
            wlCurrentCurrency = null;
            if (wlChart) { wlChart.destroy(); wlChart = null; }
        }

        function loadWlSeries(symbol, window) {
            // (x || 0) + 1, not ++x: a loader that runs before this scope's var initialiser has
            // executed would otherwise latch NaN and discard every reply from then on.
            var token = wlSeriesToken = (wlSeriesToken || 0) + 1;
            fetch('/watchlist/series?symbol=' + encodeURIComponent(symbol) + '&window=' + window)
                .then(function (r) { return r.json(); })
                .then(function (s) {
                    // Drop a reply overtaken by a later window click or another row: clicking
                    // 5D then 1Y must not repaint the 5D chart, nor label its high '1Y high'.
                    if (token !== wlSeriesToken || wlCurrentSymbol !== symbol) return;
                    renderWlChart(s);
                    applyWlRange(s);
                })
                .catch(function () { /* leave prior chart */ });
        }

        // An empty range cell, labelled once the series says which window it belongs to.
        function wlRangeStat(kind) {
            var d = wlStat('', '—');
            d.id = 'wl-range-' + kind;
            return d;
        }

        /**
         * Fill the two window high/low cells from the series just loaded. The figures are the
         * highest and lowest price actually traded in the window — read from stored daily OHLC,
         * not from the drawn line — so the high can sit slightly above the chart's visible peak.
         * A range built from end-of-day closes alone (a gilt: the HL scrape stores no intraday
         * range) is marked with an asterisk and says so on hover rather than passing itself off
         * as the real high.
         */
        function applyWlRange(s) {
            var range = s.range || {};
            [['high', range.high, range.highDate, range.highCloseOnly],
             ['low', range.low, range.lowDate, range.lowCloseOnly]]
                .forEach(function (spec) {
                    var el = document.getElementById('wl-range-' + spec[0]);
                    if (!el) return;
                    var kind = spec[0], value = spec[1], on = spec[2], closeOnly = spec[3];
                    el.querySelector('.k').textContent = s.window + ' ' + kind;
                    el.querySelector('.v').textContent = value == null ? '—'
                        : fmtPrice(value, wlCurrentCurrency) + (closeOnly ? '*' : '');
                    el.title = value == null
                        ? 'No prices stored for this window.'
                        : (closeOnly
                            ? 'A closing price — nothing is stored about how that day traded, so '
                              + 'the real ' + kind + ' may lie past it.'
                            : 'The ' + kind + 'est price actually traded in the window, auctions '
                              + 'included — not just a closing price, so it can sit outside the '
                              + 'charted line.')
                          + (on ? ' Set ' + wlRangeDate(on) + '.' : '');
                });
        }

        function wlRangeDate(iso) {
            var d = wlParseTs(iso);
            return isNaN(d) ? iso : d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
        }

        function wlParseTs(ts) {
            // Daily points are date-only ('YYYY-MM-DD'); anchor at local noon so the label
            // doesn't slip a day across time zones. Intraday points are full ISO instants.
            return /^\d{4}-\d{2}-\d{2}$/.test(ts) ? new Date(ts + 'T12:00:00') : new Date(ts);
        }

        // Axis-label mode by popup window: 'time' (1D), 'monthday' (multi-day/-month),
        // or 'monthyear' (≥1Y — a month/day-only tick is ambiguous across years).
        var WL_LONG_WINDOWS = { '1Y': 1, '2Y': 1, '3Y': 1, '5Y': 1, '10Y': 1, 'MAX': 1 };
        function wlLabelMode(window) {
            if (window === '1D') return 'time';
            return WL_LONG_WINDOWS[window] ? 'monthyear' : 'monthday';
        }

        function wlAxisLabel(ts, mode) {
            var d = wlParseTs(ts);
            if (mode === 'time') return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
            if (mode === 'monthyear') return d.toLocaleDateString([], { month: 'short', year: '2-digit' });
            return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
        }

        function renderWlChart(s) {
            var ctx = document.getElementById('wl-chart').getContext('2d');
            var pts = s.points || [];
            var labels = pts.map(function (p) { return p.t; });
            var data = pts.map(function (p) { return parseFloat(p.p); });
            var isIntraday = s.granularity === 'intraday';
            var labelMode = wlLabelMode(s.window);
            if (wlChart) { wlChart.destroy(); }
            // Category (evenly-spaced) axis rather than a time axis: this compresses out the
            // overnight/weekend gaps so multi-day charts have no long diagonal lines between
            // sessions — the standard finance-chart look.
            wlChart = new Chart(ctx, {
                type: 'line',
                data: { labels: labels, datasets: [{
                    data: data, borderColor: '#1f77b4', borderWidth: 1.2,
                    pointRadius: 0, fill: false, tension: 0
                }] },
                options: {
                    responsive: true, maintainAspectRatio: false, animation: false,
                    scales: {
                        x: { type: 'category',
                            ticks: { maxTicksLimit: 8, autoSkip: true, maxRotation: 0,
                                callback: function (value, index) { return wlAxisLabel(labels[index], labelMode); } } },
                        y: { ticks: { maxTicksLimit: 6 } }
                    },
                    plugins: { legend: { display: false },
                        tooltip: { callbacks: { title: function (items) {
                            var ts = labels[items[0].dataIndex];
                            var d = wlParseTs(ts);
                            return isIntraday
                                ? d.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
                                : d.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
                        } } } }
                }
            });
        }

        function renderWlFundamentals(symbol) {
            var body = document.getElementById('wl-fund-body');
            function render() {
                var q = wlFundamentals ? wlFundamentals[symbol] : null;
                if (!q || q.missing) {
                    body.innerHTML = '<p class="muted">No fundamentals cached for ' + symbol +
                        ' yet — the background refresh may still be running, or Yahoo has no data for this listing.</p>';
                    return;
                }
                var sector = (q.labels && q.labels.sector) || '';
                var isFinancial = /financial|insurance|bank/i.test(sector);
                // Core valuation not already in the stats strip, then the shared grouped
                // renderer (same one the Snapshot-diff modal uses), laid out in columns.
                var core = '<div class="snap-group"><div class="snap-group-title">Key</div>' +
                        wlKv('Forward P/E', fmtNumOrDash(q.forwardPe, 1)) +
                        wlKv('PEG', fmtNumOrDash(q.pegRatio, 2)) +
                        wlKv('Market cap', q.marketCap == null ? '—' : fmtMarketCap(q.marketCap, q.currency)) +
                        wlKv('Target (mean)', q.targetMeanPrice == null ? '—' : fmtPrice(q.targetMeanPrice, q.currency)) +
                        '</div>';
                body.innerHTML = '<div class="wl-fund-cols">' + core + fundamentalsHtml(q, isFinancial) + '</div>';
            }
            if (wlFundamentals) { render(); return; }
            body.innerHTML = '<p class="muted">Loading fundamentals…</p>';
            ensureWlFundamentals().then(function (ok) {
                if (!ok) {
                    body.innerHTML = '<p class="muted">No fundamentals available yet — reopen the popup to retry.</p>';
                    return;
                }
                render();
            }).catch(function () {
                body.innerHTML = '<p class="muted">Could not load fundamentals — reopen the popup to retry.</p>';
            });
        }

        var wlFundPromise = null;

        /**
         * Load the whole fundamentals snapshot once per page and keep it by symbol. Shared by the
         * popup's disclosure and by any `f:` column in the grid, so a tab that shows FCF Margin
         * and a popup opened from it cost one request between them.
         *
         * <p>An empty response is <b>not</b> cached: a degraded read would otherwise poison the
         * session and every later popup would say "no fundamentals" without ever retrying.
         */
        function ensureWlFundamentals() {
            if (wlFundamentals) return Promise.resolve(true);
            if (wlFundPromise) return wlFundPromise;
            wlFundPromise = fetch('/portfolio-fundamentals').then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (snap) {
                var rows = snap.rows || [];
                if (!rows.length) { wlFundPromise = null; return false; }
                wlFundamentals = {};
                rows.forEach(function (row) { wlFundamentals[row.symbol] = row; });
                return true;
            }).catch(function (e) {
                wlFundPromise = null;                       // let the next caller retry
                throw e;
            });
            return wlFundPromise;
        }

        /**
         * Fill in the `f:` cells once the snapshot lands. The grid is rendered from the server Row
         * alone, which carries no fundamentals, so those cells start as '—' and are patched here
         * rather than triggering a whole re-render — an in-progress threshold edit keeps its focus.
         */
        function wlPatchFundCells() {
            var fundCols = wlCols.filter(function (k) { return k.indexOf('f:') === 0; });
            if (!fundCols.length) return;
            var tbody = document.querySelector('#watchlist-table tbody');
            if (!tbody) return;
            Array.prototype.forEach.call(tbody.children, function (tr) {
                var r = tr._wlRow;
                if (!r) return;
                fundCols.forEach(function (key) {
                    var td = tr.querySelector('[data-f="' + key + '"]');
                    if (!td) return;
                    var tmp = document.createElement('tr');
                    tmp.innerHTML = wlFundCell(r, key);
                    tr.replaceChild(tmp.firstChild, td);
                });
            });
        }

        function wlKv(label, value) {
            return '<div class="snap-kv"><span class="k">' + label + '</span><span class="v">' + value + '</span></div>';
        }

        function fmtNumOrDash(v, dp) {
            return v == null ? '—' : parseFloat(v).toFixed(dp);
        }

        // ================= Swing stats tab =================
        var swReport = null;
        var swCorrChart = null;
        var swCorrMode = '30';
        var swVariant = 'all';          // 'all' | 'ex' — which §D earnings variant the §5/§6 tables show
        var swEventLogs = {};           // id → { rows, name } for the §A CSV export
        var swEventLogSeq = 0;
        var swInit = false;

        function setupSwing() {
            if (swInit) return;
            swInit = true;
            document.getElementById('sw-run').addEventListener('click', runSwing);
            document.getElementById('sw-save').addEventListener('click', saveSwingList);
            document.getElementById('sw-backfill').addEventListener('click', backfillSwing);
            document.getElementById('sw-detail-symbol').addEventListener('change', renderSwingDetail);
            document.querySelectorAll('#sw-corr-toggle button').forEach(function (b) {
                b.addEventListener('click', function () {
                    swCorrMode = b.dataset.corr;
                    document.querySelectorAll('#sw-corr-toggle button').forEach(function (x) {
                        x.classList.toggle('active', x === b);
                    });
                    renderSwingCorr();
                });
            });
            document.querySelectorAll('#sw-variant-toggle button').forEach(function (b) {
                b.addEventListener('click', function () {
                    swVariant = b.dataset.variant;
                    document.querySelectorAll('#sw-variant-toggle button').forEach(function (x) {
                        x.classList.toggle('active', x === b);
                    });
                    renderSwingDetail();
                });
            });
            // §A CSV export — delegated because the buttons live in re-rendered HTML.
            document.getElementById('sw-detail').addEventListener('click', function (e) {
                var btn = e.target.closest('.sw-csv-btn');
                if (!btn) return;
                e.preventDefault();
                downloadSwingCsv(btn.dataset.logId);
            });
        }

        function swVal(id) { return (document.getElementById(id).value || '').trim(); }
        function swSet(id, v) { document.getElementById(id).value = v; }
        function swStatus(msg) { document.getElementById('sw-status').textContent = msg || ''; }

        function buildSwingQuery() {
            var map = {
                symbols: swVal('sw-symbols'), cluster: swVal('sw-cluster'), years: swVal('sw-years'),
                sigmas: swVal('sw-sigmas'), dropPcts: swVal('sw-drops'), flushPcts: swVal('sw-flushpct'),
                flushDays: swVal('sw-flushdays'), horizons: swVal('sw-horizons'), tp: swVal('sw-tp'),
                sl: swVal('sw-sl'), timeStops: swVal('sw-timestops'),
                barrierDays: swVal('sw-barrierdays'), barrierPct: swVal('sw-barrierpct'),
                flushSigmas: swVal('sw-flushsigmas'), barrierSigmaK: swVal('sw-barriersigmak'),
                maWindow: swVal('sw-mawindow'), eventWindow: swVal('sw-eventwindow'),
                eventLogTimeStop: swVal('sw-eventlogstop')
            };
            var parts = [];
            Object.keys(map).forEach(function (k) {
                if (map[k] !== '') parts.push(k + '=' + encodeURIComponent(map[k]));
            });
            return parts.join('&');
        }

        function runSwing() {
            swStatus('Computing…');
            fetch('/swing?' + buildSwingQuery())
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (rep) {
                    swReport = rep;
                    fillSwingInputs(rep.config);
                    document.getElementById('sw-empty').style.display = 'none';
                    renderSwingVol();
                    renderSwingEarnings();
                    populateSwingDetailSelect();
                    renderSwingDetail();
                    renderSwingCorr();
                    document.getElementById('sw-vol-wrap').style.display = 'block';
                    document.getElementById('sw-earnings-wrap').style.display = 'block';
                    document.getElementById('sw-detail-wrap').style.display = 'block';
                    document.getElementById('sw-corr-wrap').style.display = 'block';
                    swStatus('Updated ' + new Date().toLocaleTimeString());
                })
                .catch(function (e) { swStatus('Error: ' + e.message); });
        }

        function saveSwingList() {
            var body = 'symbols=' + encodeURIComponent(swVal('sw-symbols')) +
                       '&cluster=' + encodeURIComponent(swVal('sw-cluster'));
            swStatus('Saving…');
            fetch('/swing/symbols', {
                method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body
            }).then(function (r) { return r.json(); })
              .then(function (res) {
                  swSet('sw-symbols', (res.symbols || []).join(','));
                  swSet('sw-cluster', (res.cluster || []).join(','));
                  swStatus('Saved. Backfilling new names in the background…');
              }).catch(function (e) { swStatus('Save failed: ' + e.message); });
        }

        function backfillSwing() {
            swStatus('Backfill started (this can take a minute per new name)…');
            fetch('/swing/backfill', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function () { swStatus('Backfill running. Re-run once prices land.'); })
                .catch(function (e) { swStatus('Backfill failed: ' + e.message); });
        }

        function fillSwingInputs(cfg) {
            var pctList = function (arr) { return arr.map(function (x) { return +(x * 100).toFixed(4); }).join(','); };
            swSet('sw-symbols', cfg.symbols.join(','));
            swSet('sw-cluster', cfg.cluster.join(','));
            swSet('sw-years', cfg.years);
            swSet('sw-sigmas', cfg.sigmaMultiples.join(','));
            swSet('sw-drops', pctList(cfg.absDrops));
            swSet('sw-flushpct', pctList(cfg.flushThresholds));
            swSet('sw-flushdays', cfg.flushDays.join(','));
            swSet('sw-horizons', cfg.horizons.join(','));
            swSet('sw-tp', +(cfg.takeProfit * 100).toFixed(4));
            swSet('sw-sl', +(cfg.stopLoss * 100).toFixed(4));
            swSet('sw-timestops', cfg.timeStops.join(','));
            swSet('sw-barrierdays', cfg.barrierFlushDays);
            swSet('sw-barrierpct', +(cfg.barrierFlushDrop * 100).toFixed(4));
            swSet('sw-flushsigmas', cfg.flushSigmaMultiples.join(','));
            swSet('sw-barriersigmak', cfg.barrierSigmaK);
            swSet('sw-mawindow', cfg.maWindow);
            swSet('sw-eventwindow', cfg.eventWindowSessions);
            swSet('sw-eventlogstop', cfg.eventLogTimeStop);
        }

        function renderSwingEarnings() {
            var h = swReport.earningsHygiene;
            if (!h) return;
            document.getElementById('sw-earnings-note').textContent =
                h.proxyNote + ' Source: ' + h.source + '.';
            var head = '<thead><tr><th data-sort="str">Symbol</th><th data-sort="str">Verified</th>' +
                '<th data-sort="num">Dates</th><th data-sort="str">From</th><th data-sort="str">To</th></tr></thead>';
            var body = '<tbody>';
            (h.coverage || []).forEach(function (c) {
                var v = c.verified ? '<span class="swing-pos">yes</span>'
                                   : '<span class="swing-unverified">unverified</span>';
                body += '<tr><td class="lbl"><b>' + c.symbol + '</b></td><td>' + v + '</td><td>' +
                        c.dateCount + '</td><td>' + (c.from || '—') + '</td><td>' + (c.to || '—') + '</td></tr>';
            });
            body += '</tbody>';
            var t = document.getElementById('sw-earnings-table');
            t.innerHTML = head + body;
            attachSort(t);
        }

        // ---- formatting helpers ----
        function swPctCell(frac, dp) {
            if (frac == null) return '—';
            var v = parseFloat(frac) * 100;
            var cls = v > 0 ? 'swing-pos' : (v < 0 ? 'swing-neg' : '');
            return '<span class="' + cls + '">' + (v > 0 ? '+' : '') + v.toFixed(dp == null ? 2 : dp) + '%</span>';
        }
        function swPct(frac, dp) { return frac == null ? '—' : (parseFloat(frac) * 100).toFixed(dp == null ? 1 : dp) + '%'; }
        function swNum(v, dp) { return v == null ? '—' : parseFloat(v).toFixed(dp == null ? 2 : dp); }

        function renderSwingVol() {
            var floor = swReport.config.smallNFloor;
            var windows = (swReport.perSymbol.find(function (s) { return !s.missingData; }) || {}).realizedVol
                || swReport.pooled.realizedVol || [];
            var head = '<thead><tr><th data-sort="str">Symbol</th>';
            windows.forEach(function (w) {
                head += '<th data-sort="num">' + w.window + 'd daily</th><th data-sort="num">' + w.window + 'd ann</th>';
            });
            head += '</tr></thead>';
            var body = '<tbody>';
            var rows = swReport.perSymbol.concat([swReport.pooled]);
            rows.forEach(function (s) {
                body += '<tr><td class="lbl"><b>' + s.symbol + '</b></td>';
                if (s.missingData) {
                    body += '<td colspan="' + (windows.length * 2) + '" class="swing-smalln">no price history — Backfill</td></tr>';
                    return;
                }
                s.realizedVol.forEach(function (v) {
                    var flag = v.smallN ? ' swing-smalln-cell' : '';
                    body += '<td class="' + flag.trim() + '">' + swPct(v.dailySigma, 2) + '</td>';
                    body += '<td class="' + flag.trim() + '">' + swPct(v.annualSigma, 1) + '</td>';
                });
                body += '</tr>';
            });
            body += '</tbody>';
            var table = document.getElementById('sw-vol-table');
            table.innerHTML = head + body;
            // innerHTML replaces the thead too, so the header cells (and their arrows)
            // are fresh every render — re-attach the sort handler each time.
            attachSort(table);
        }

        function populateSwingDetailSelect() {
            var sel = document.getElementById('sw-detail-symbol');
            var prev = sel.value;
            var opts = '<option value="__POOL__">CLUSTER (pooled)</option>';
            swReport.perSymbol.forEach(function (s) {
                if (s.missingData) return;
                opts += '<option value="' + s.symbol + '">' + s.symbol + '</option>';
            });
            sel.innerHTML = opts;
            sel.value = prev && sel.querySelector('option[value="' + prev.replace(/"/g, '') + '"]') ? prev : '__POOL__';
        }

        function renderSwingDetail() {
            if (!swReport) return;
            var sel = document.getElementById('sw-detail-symbol').value;
            var pooled = sel === '__POOL__';
            var rep = pooled ? swReport.pooled
                : swReport.perSymbol.find(function (s) { return s.symbol === sel; });
            var host = document.getElementById('sw-detail');
            if (!rep || rep.missingData) { host.innerHTML = '<p class="muted">No data.</p>'; return; }
            swEventLogs = {};               // re-registered fresh on every render (ids would otherwise leak)
            swEventLogSeq = 0;
            host.innerHTML = swingSpanHeader(rep, pooled) +
                '<div class="swing-regimes">' +
                swingRegimeBlock('Full history', rep.full, pooled) +
                swingRegimeBlock('Trailing 6 months', rep.trailing6m, pooled) +
                '</div>';
        }

        // Per-symbol data-span line + a short-history warning (§9 transparency: what "full history" means).
        function swingSpanHeader(rep, pooled) {
            if (pooled) {
                return '<div class="swing-span">Pooled <b>CLUSTER</b> — ' + rep.barCount +
                    ' contributing member' + (rep.barCount === 1 ? '' : 's') +
                    '. Pooled cells show <b>events / episodes</b> (distinct event dates); cells with ' +
                    'fewer than ' + swReport.config.episodeFloor + ' episodes are ' +
                    '<span class="swing-smalln">dimmed</span>.</div>';
            }
            var yrs = (rep.yearsAvailable || 0).toFixed(1);
            var span = '<div class="swing-span">Data: <b>' + rep.symbol + '</b> ' + rep.from + ' → ' +
                rep.to + ' (' + yrs + ' yrs, ' + rep.barCount + ' bars).';
            if (rep.shortHistory) {
                span += ' <span class="swing-warn">⚠ less than the requested ' + rep.requestedYears +
                    'y — "full history" here is only ' + yrs + 'y (recent listing/spin-off).</span>';
            }
            return span + '</div>';
        }

        function swingRegimeBlock(label, regime, pooled) {
            if (!regime) return '';
            var floor = swReport.config.smallNFloor;
            var head = '<div class="swing-regime-head">' + label +
                '<span class="muted swing-sub"> · window from ' + (regime.cutoff || 'series start') + '</span></div>';
            var html = '<div class="swing-block swing-regime-col">' + head +
                   swingForwardTable(regime.forward, floor, pooled) +
                   swingContinuationTable(regime.forward, floor, pooled);
            if (regime.familyOverlapCount != null) {
                html += '<div class="swing-sub">§C family overlap: <b>' + regime.familyOverlapCount +
                    '</b> date(s) fire both the absolute and σ barrier triggers (not double-pooled).</div>';
            }
            (regime.families || []).forEach(function (fam) {
                html += swingFamilyBlock(fam, label, floor, pooled);
            });
            return html + '</div>';
        }

        // One trigger family (§C): its selected earnings variant's §5/§6, then §A/§B/§E (variant-independent).
        function swingFamilyBlock(fam, regimeLabel, floor, pooled) {
            var wantName = swVariant === 'ex' ? 'ex-earnings' : 'all';
            var variant = (fam.variants || []).find(function (v) { return v.name === wantName; })
                || (fam.variants || [])[0];
            var badge = fam.earningsVerified ? '' :
                ' <span class="swing-unverified" title="some symbols have no earnings source — the ex-earnings variant is unreliable">unverified</span>';
            var vlabel = swVariant === 'ex' ? 'ex-earnings' : 'all events';
            var html = '<div class="swing-family-head">' + fam.label + ' family ' +
                '<span class="swing-sub">· §5/§6 showing ' + vlabel + badge + '</span></div>';
            if (variant) {
                html += swingFlushTable(variant.flushes, floor, pooled);
                html += swingBarrierSection(variant.barriers, floor, pooled, fam.kind);
            }
            html += swingEventLog(fam, regimeLabel, pooled);
            html += swingAnnualTable(fam.annual, pooled);
            html += swingMaSplitTable(fam.maSplit, pooled);
            return html;
        }

        function currentSwingSymbol() {
            var sel = document.getElementById('sw-detail-symbol');
            return sel ? (sel.value === '__POOL__' ? 'CLUSTER' : sel.value) : '';
        }

        // §A event log — collapsible, with a CSV export. Rows are registered so the delegated
        // click handler can rebuild the file without re-deriving anything.
        function swingEventLog(fam, regimeLabel, pooled) {
            var rows = fam.eventLog || [];
            var id = 'log' + (swEventLogSeq++);
            swEventLogs[id] = { rows: rows,
                name: 'swing_events_' + currentSwingSymbol() + '_' + regimeLabel.replace(/\s+/g, '') + '_' + fam.kind };
            var cols = ['Date', 'Sym', 'Def', 'cum%', 'σ', 'σ×', 'MA', 'MA%', 'entry', 'outcome',
                'exit', 'held', 'ret%', 'cons%', 'DD10%', '→tgt', 'earn±', 'earnD'];
            var thead = '<thead><tr>' + cols.map(function (h) { return '<th>' + h + '</th>'; }).join('') + '</tr></thead>';
            var tbody = '<tbody>';
            rows.forEach(function (r) { tbody += eventLogRow(r); });
            tbody += '</tbody>';
            return '<details class="swing-eventlog"><summary>§A event log — ' + rows.length +
                ' event' + (rows.length === 1 ? '' : 's') +
                '<button type="button" class="sw-csv-btn" data-log-id="' + id + '">Download CSV</button></summary>' +
                '<table class="swing-tbl">' + thead + tbody + '</table></details>';
        }

        function eventLogRow(r) {
            var maFlag = r.aboveMa == null ? '—' : (r.aboveMa ? 'above' : 'below');
            var earn = r.earningsWithinWindow ? '<span class="swing-warn">yes</span>'
                : (r.earningsVerified ? 'no' : '<span class="swing-unverified">?</span>');
            var earnD = r.tradingDaysToEarnings == null ? '—' : r.tradingDaysToEarnings;
            var outCls = r.outcome === 'TP' ? 'swing-pos' : (r.outcome === 'SL' ? 'swing-neg' : '');
            var toTgt = r.sessionsToTarget == null ? (r.targetCensored ? 'cens' : '—') : r.sessionsToTarget;
            return '<tr><td class="lbl">' + r.date + '</td><td>' + r.symbol + '</td><td>' + r.definition + '</td>' +
                '<td>' + swPct(r.cumReturn, 1) + '</td>' +
                '<td>' + swPct(r.sigma, 1) + '</td>' +
                '<td>' + swNum(r.sigmaMultiple, 1) + '</td>' +
                '<td>' + maFlag + '</td>' +
                '<td>' + swPct(r.maDistancePct, 0) + '</td>' +
                '<td>' + swNum(r.entryClose, 2) + '</td>' +
                '<td class="' + outCls + '">' + r.outcome + '</td>' +
                '<td>' + (r.exitDate || '—') + '</td>' +
                '<td>' + (r.sessionsHeld == null ? '—' : r.sessionsHeld) + '</td>' +
                '<td>' + swPctCell(r.tradeReturn, 1) + '</td>' +
                '<td>' + swPctCell(r.conservativeReturn, 1) + '</td>' +
                '<td>' + swPct(r.furtherDrawdown, 1) + '</td>' +
                '<td>' + toTgt + '</td>' +
                '<td>' + earn + '</td>' +
                '<td>' + earnD + '</td></tr>';
        }

        // §B per-year breakdown.
        function swingAnnualTable(annual, pooled) {
            if (!annual || !annual.length) return '';
            var html = '<div class="swing-sub">§B per-year — events by outcome, mean/median trade return, ' +
                'vs the name\'s own calendar-year total return' + (pooled ? ' (cluster mean)' : '') +
                '. <b>events = TP+SL+Time+Amb+Open</b>.</div>';
            html += '<table class="swing-tbl"><thead><tr><th class="lbl">Year</th><th>events</th>' +
                '<th>TP</th><th>SL</th><th>Time</th><th>Amb</th><th>Open</th>' +
                '<th>mean ret</th><th>median</th>' +
                '<th title="the stock\'s own total return that year">stock yr</th></tr></thead><tbody>';
            annual.forEach(function (a) {
                var dim = a.smallN ? ' class="swing-smalln-cell"' : '';
                var reconciles = (a.tp + a.sl + a.time + a.ambiguous + a.open) === a.events;
                html += '<tr' + dim + '><td class="lbl">' + a.year + '</td><td>' + a.events +
                    (reconciles ? '' : ' <span class="swing-warn">!</span>') + '</td>' +
                    '<td>' + a.tp + '</td><td>' + a.sl + '</td><td>' + a.time + '</td>' +
                    '<td>' + a.ambiguous + '</td><td>' + a.open + '</td>' +
                    '<td>' + swPctCell(a.meanReturn, 2) + '</td><td>' + swPctCell(a.medianReturn, 2) + '</td>' +
                    '<td>' + swPctCell(a.calendarReturn, 1) + '</td></tr>';
            });
            return html + '</tbody></table>';
        }

        // §E 200d-MA split.
        function swingMaSplitTable(ms, pooled) {
            if (!ms) return '';
            var html = '<div class="swing-sub">§E 200d-MA split (N=' + ms.timeStop + ') — does the edge exist ' +
                'below the MA, or is it strictly buy-dips-in-uptrends? <i>boot p</i> = flush mean-return ' +
                'percentile within the within-state bootstrap.</div>';
            html += '<table class="swing-tbl"><thead><tr><th class="lbl">State · source</th><th>events</th>' +
                '<th>TP</th><th>SL</th><th>Time</th><th>Amb</th><th>Open</th><th>%TP</th><th>mean ret</th>' +
                '<th>boot p</th></tr></thead><tbody>';
            ['above', 'below'].forEach(function (key) {
                var st = ms[key];
                if (st) html += maStateRows(st, key);
            });
            return html + '</tbody></table>';
        }

        function maStateRows(st, key) {
            var label = key === 'above' ? '≥MA' : '<MA';
            var bootCell = '—';
            if (st.bootstrap && st.bootstrap.retPercentileRank != null) {
                var rank = st.bootstrap.retPercentileRank;
                var inside = rank >= 0.25 && rank <= 0.75;
                bootCell = '<span class="' + (inside ? 'swing-smalln' : 'swing-rank-hit') + '">p' +
                    Math.round(rank * 100) + '</span>';
            } else if (st.underpowered) {
                bootCell = '<span class="swing-smalln">underpwr</span>';
            }
            return maRow(label + ' flush', st.flush, false, bootCell) +
                   maRow(label + ' placebo', st.placebo, true, '');
        }

        function maRow(label, b, isPlacebo, bootCell) {
            if (!b) return '';
            var cls = isPlacebo ? ' class="swing-placebo-row"' : '';
            var reconciles = (b.tpFirst + b.slFirst + b.timeCount + b.ambiguous + b.open) === b.events;
            return '<tr' + cls + '><td class="lbl">' + label + '</td>' +
                '<td>' + b.events + (reconciles ? '' : ' <span class="swing-warn">!</span>') + '</td>' +
                '<td>' + b.tpFirst + '</td><td>' + b.slFirst + '</td><td>' + b.timeCount + '</td>' +
                '<td>' + b.ambiguous + '</td><td>' + b.open + '</td>' +
                '<td>' + swPct(barPctTp(b), 0) + '</td>' +
                '<td>' + swPctCell(b.meanReturn, 2) + '</td>' +
                '<td>' + bootCell + '</td></tr>';
        }

        // ---- §A CSV export ----
        // Per-column formatters so the file opens human-readable in Excel: fractions become rounded
        // percents, prices/multiples round to 2dp, and the ≤/− in the definition label are ASCII-ised
        // (belt-and-suspenders — the file is also written with a UTF-8 BOM so Excel decodes it right).
        function csvPct(v) { return v == null ? '' : (v * 100).toFixed(2) + '%'; }
        function csvNum(v) { return v == null ? '' : (+v).toFixed(2); }
        function csvRaw(v) { return v == null ? '' : v; }
        function csvDef(v) {
            return v == null ? '' : String(v)
                .replace(/≤/g, '<=').replace(/≥/g, '>=')
                .replace(/[−–—]/g, '-').replace(/σ/g, 'sigma');
        }

        function downloadSwingCsv(id) {
            var entry = swEventLogs[id];
            if (!entry) return;
            // [field, formatter]
            var cols = [
                ['date', csvRaw], ['symbol', csvRaw], ['definition', csvDef], ['kind', csvRaw],
                ['cumReturn', csvPct], ['sigma', csvPct], ['sigmaMultiple', csvNum],
                ['aboveMa', csvRaw], ['maDistancePct', csvPct], ['entryClose', csvNum],
                ['outcome', csvRaw], ['exitDate', csvRaw], ['sessionsHeld', csvRaw],
                ['tradeReturn', csvPct], ['conservativeReturn', csvPct], ['furtherDrawdown', csvPct],
                ['sessionsToTarget', csvRaw], ['targetCensored', csvRaw],
                ['earningsVerified', csvRaw], ['tradingDaysToEarnings', csvRaw], ['earningsWithinWindow', csvRaw]
            ];
            var lines = [cols.map(function (c) { return c[0]; }).join(',')];
            entry.rows.forEach(function (r) {
                lines.push(cols.map(function (c) { return csvCell(c[1](r[c[0]])); }).join(','));
            });
            downloadCsv(entry.name + '.csv', lines.join('\n'));
        }

        function csvCell(v) {
            if (v == null) return '';
            var s = String(v);
            return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
        }

        function downloadCsv(filename, text) {
            // Lead with a UTF-8 BOM so Excel decodes accented/maths glyphs instead of mojibake.
            var blob = new Blob(['\uFEFF' + text], { type: 'text/csv;charset=utf-8;' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }

        function smallNTag(n, floor) {
            return n < floor ? '<span class="swing-smalln"> (n=' + n + ')</span>' : '';
        }

        // "events / episodes" for pooled cells; plain count otherwise. Dims when episodes < floor.
        function epCell(count, episodes, thinEpisodes, pooled) {
            if (!pooled) return '' + count;
            var cls = thinEpisodes ? ' class="swing-smalln"' : '';
            return count + '<span' + cls + '> / ' + episodes + ' ep</span>';
        }

        function swingForwardTable(fwd, floor, pooled) {
            var hs = fwd.baseline.map(function (b) { return b.horizon; });
            var html = '<div class="swing-sub">§2 baseline vs §3 down-day conditional — cell = mean fwd return, (％ positive); median on hover</div>';
            html += '<table class="swing-tbl"><thead><tr><th class="lbl">Trigger</th><th>' +
                    (pooled ? 'n / ep' : 'n') + '</th>';
            hs.forEach(function (h) { html += '<th>+' + h + 'd</th>'; });
            html += '</tr></thead><tbody>';
            // baseline row
            html += '<tr class="swing-baseline"><td class="lbl">baseline (all days)</td><td>' + fwd.baselineDays + '</td>';
            fwd.baseline.forEach(function (st) { html += forwardCell(st); });
            html += '</tr>';
            fwd.conditional.forEach(function (blk) {
                var dim = blk.smallN ? ' class="swing-smalln-cell"' : '';
                html += '<tr' + dim + '><td class="lbl">' + blk.label + smallNTag(blk.triggerDays, floor) +
                        '</td><td>' + epCell(blk.triggerDays, blk.episodes, blk.thinEpisodes, pooled) + '</td>';
                blk.horizons.forEach(function (st) { html += forwardCell(st); });
                html += '</tr>';
            });
            html += '</tbody></table>';
            return html;
        }

        function forwardCell(st) {
            if (!st || st.n === 0) return '<td>—</td>';
            var title = 'median ' + swPct(st.median, 2) + ', %pos ' + swPct(st.pctPositive, 0) + ', n=' + st.n;
            return '<td title="' + title + '">' + swPctCell(st.mean, 2) +
                   ' <span class="muted">(' + swPct(st.pctPositive, 0) + ')</span></td>';
        }

        function swingContinuationTable(fwd, floor, pooled) {
            var html = '<div class="swing-sub">§4 continuation — does the first red day keep falling?</div>';
            html += '<table class="swing-tbl"><thead><tr><th class="lbl">Trigger</th><th>' +
                    (pooled ? 'n / ep' : 'n') + '</th>' +
                    '<th>P(next ≤ 0)</th><th>P(next ≤ −3%)</th></tr></thead><tbody>';
            html += '<tr class="swing-baseline"><td class="lbl">baseline (all days)</td><td>' + fwd.baselineDays +
                    '</td><td>' + swPct(fwd.basePDown, 0) + '</td><td>' + swPct(fwd.basePBig, 0) + '</td></tr>';
            fwd.continuation.forEach(function (c) {
                var dim = c.smallN ? ' class="swing-smalln-cell"' : '';
                html += '<tr' + dim + '><td class="lbl">' + c.label + smallNTag(c.triggerDays, floor) + '</td><td>' +
                        epCell(c.triggerDays, c.episodes, c.thinEpisodes, pooled) +
                        '</td><td>' + swPct(c.pDown, 0) + '</td><td>' + swPct(c.pBig, 0) + '</td></tr>';
            });
            html += '</tbody></table>';
            return html;
        }

        function swingFlushTable(flushes, floor, pooled) {
            var cooldown = swReport.config.flushCooldown;
            var html = '<div class="swing-sub">§5 flush events — drawdown after the flush, time to +target, and which barrier hits first ' +
                    'over the ' + swReport.config.followSessions + '-session follow window. ' +
                    'Events deduped by a ' + cooldown + '-session cooldown; ' +
                    '<b>events = TP + SL + neither + ambiguous + open</b>.</div>';
            html += '<table class="swing-tbl"><thead><tr><th class="lbl">Flush</th><th>' +
                    (pooled ? 'events / ep' : 'events') + '</th>' +
                    '<th>further DD (med)</th><th>DD q1…q3</th><th>days→target (med)</th>' +
                    '<th title="high traded ≥ +target first">TP</th>' +
                    '<th title="low traded ≤ −stop first">SL</th>' +
                    '<th title="full window, neither barrier touched">neither</th>' +
                    '<th title="both barriers touched in the same session">ambig</th>' +
                    '<th title="follow window runs past the last data date — open/censored">open</th></tr></thead><tbody>';
            flushes.forEach(function (f) {
                var dim = f.smallN ? ' class="swing-smalln-cell"' : '';
                var reconciles = (f.tpFirst + f.slFirst + f.neither + f.ambiguous + f.open) === f.eventCount;
                var evtCell = epCell(f.eventCount, f.episodes, f.thinEpisodes, pooled) +
                    (reconciles ? '' : ' <span class="swing-warn">!</span>');
                var daysTip = f.targetCensored > 0 ? ' title="' + f.targetCensored + ' full-window events never reached +target"' : '';
                html += '<tr' + dim + '><td class="lbl">' + f.label + smallNTag(f.eventCount, floor) + '</td><td>' + evtCell + '</td>' +
                        '<td>' + swPct(distMed(f.furtherDrawdown), 1) + '</td>' +
                        '<td>' + swPct(f.furtherDrawdown.q1, 1) + '…' + swPct(f.furtherDrawdown.q3, 1) + '</td>' +
                        '<td' + daysTip + '>' + swNum(distMed(f.daysToTarget), 1) + '</td>' +
                        '<td>' + f.tpFirst + '</td><td>' + f.slFirst + '</td><td>' + f.neither + '</td>' +
                        '<td>' + f.ambiguous + '</td><td>' + f.open + '</td></tr>';
            });
            html += '</tbody></table>';
            return html;
        }

        function distMed(d) { return d && d.n > 0 ? d.median : null; }

        // §6 — barrier accounting (flush vs all-days placebo) + matched-random bootstrap, plus the
        // conventions the numbers depend on. `comparisons` is a list of {timeStop, flush, placebo, bootstrap}.
        function swingBarrierSection(comparisons, floor, pooled, kind) {
            if (!comparisons || !comparisons.length) return '';
            var cfg = swReport.config;
            var tp = (cfg.takeProfit * 100).toFixed(0);
            var sl = (cfg.stopLoss * 100).toFixed(0);
            var seed = kind === 'sigma'
                ? cfg.barrierFlushDays + 'd ≤ −' + cfg.barrierSigmaK + 'σ'
                : cfg.barrierFlushDays + 'd ≤ −' + (cfg.barrierFlushDrop * 100).toFixed(0) + '%';
            var note = '<div class="swing-sub">§6 barrier / time-stop simulation — entry at each ' +
                seed + ' flush close.' +
                ' <b>Touch convention</b>: intraday OHLC — TP if session high ≥ +' + tp + '%, SL if low ≤ −' + sl +
                '%; fills at the barrier, time-stop exits at the close. <b>Ambiguous</b> = both barriers touched ' +
                'in one session (excluded from mean ret; the <i>cons ret</i> column books each as a stop = a ' +
                'pessimistic bound). <b>Open</b> = the time-stop window runs past the last data date (censored, ' +
                'never counted as an outcome). <b>Overlap</b>: non-overlapping — the next entry waits until the ' +
                'prior trade resolves. <b>events = TP + SL + Time + Ambiguous + Open</b>.</div>';

            // Accounting table: flush row-group then placebo row-group.
            var head = '<table class="swing-tbl"><thead><tr><th class="lbl">Source · N-stop</th><th>' +
                (pooled ? 'events / ep' : 'events') + '</th>' +
                '<th>TP</th><th>SL</th><th>Time</th><th>Amb</th><th>Open</th>' +
                '<th title="TP ÷ closed">%TP</th><th>mean ret</th>' +
                '<th title="ambiguous booked as SL">cons ret</th><th>mean days</th><th>ret q1…q3</th></tr></thead><tbody>';
            var body = '';
            comparisons.forEach(function (c) {
                body += barrierAccountingRow('flush ' + c.timeStop + 'd', c.flush, floor, pooled, false);
            });
            comparisons.forEach(function (c) {
                body += barrierAccountingRow('placebo ' + c.timeStop + 'd (all days)', c.placebo, floor, pooled, true);
            });
            var acct = head + body + '</tbody></table>';

            return note + acct + swingBootstrapTable(comparisons, cfg);
        }

        // %TP is over the CLOSED set (events minus open); computed here since it isn't a serialized field.
        function barPctTp(b) {
            var closed = b.tpFirst + b.slFirst + b.timeCount + b.ambiguous;
            return closed === 0 ? null : b.tpFirst / closed;
        }

        function barrierAccountingRow(label, b, floor, pooled, isPlacebo) {
            var reconciles = (b.tpFirst + b.slFirst + b.timeCount + b.ambiguous + b.open) === b.events;
            var cls = isPlacebo ? ' class="swing-placebo-row"' : (b.smallN ? ' class="swing-smalln-cell"' : '');
            var evt = (isPlacebo ? '' + b.events : epCell(b.events, b.episodes, b.thinEpisodes, pooled)) +
                (reconciles ? '' : ' <span class="swing-warn">!</span>');
            return '<tr' + cls + '><td class="lbl">' + label + (isPlacebo ? '' : smallNTag(b.events, floor)) + '</td>' +
                '<td>' + evt + '</td>' +
                '<td>' + b.tpFirst + '</td><td>' + b.slFirst + '</td><td>' + b.timeCount + '</td>' +
                '<td>' + b.ambiguous + '</td><td>' + b.open + '</td>' +
                '<td>' + swPct(barPctTp(b), 0) + '</td>' +
                '<td>' + swPctCell(b.meanReturn, 2) + '</td>' +
                '<td>' + swPctCell(b.meanReturnConservative, 2) + '</td>' +
                '<td>' + swNum(b.meanHoldingDays, 1) + '</td>' +
                '<td>' + swPct(b.returnDist.q1, 1) + '…' + swPct(b.returnDist.q3, 1) + '</td></tr>';
        }

        // Bootstrap: is the flush result distinguishable from random timing of the same count?
        function swingBootstrapTable(comparisons, cfg) {
            var iters = cfg.bootstrapIterations;
            var html = '<div class="swing-sub" style="margin-top:0.5rem;">§6 control — matched random bootstrap (' +
                iters + '× per stop): draw the same number of entries at random dates, run the identical sim. ' +
                'A flush figure inside the bootstrap IQR (percentile 25–75, <span class="swing-smalln">dimmed</span>) ' +
                'is <b>indistinguishable from regime drift</b>; outside it (highlighted) the trigger adds signal.</div>';
            html += '<table class="swing-tbl"><thead><tr><th class="lbl">N-stop</th><th>pool</th><th>draws</th>' +
                '<th>%TP median</th><th>%TP 5–95</th><th>flush %TP (pctile)</th>' +
                '<th>ret median</th><th>ret 5–95</th><th>flush ret (pctile)</th></tr></thead><tbody>';
            comparisons.forEach(function (c) {
                var bs = c.bootstrap;
                var dim = bs.smallN ? ' class="swing-smalln-cell"' : '';
                html += '<tr' + dim + '><td class="lbl">' + bs.timeStop + 'd</td>' +
                    '<td>' + bs.poolSize + '</td><td>' + bs.drawN + '</td>' +
                    '<td>' + swPct(bs.tpMedian, 0) + '</td>' +
                    '<td>' + swPct(bs.tpP05, 0) + '…' + swPct(bs.tpP95, 0) + '</td>' +
                    '<td>' + rankBadge(swPct(bs.actualTpPct, 0), bs.tpPercentileRank) + '</td>' +
                    '<td>' + swPctCell(bs.retMedian, 2) + '</td>' +
                    '<td>' + swPct(bs.retP05, 1) + '…' + swPct(bs.retP95, 1) + '</td>' +
                    '<td>' + rankBadge(swPctCellStr(bs.actualMeanReturn), bs.retPercentileRank) + '</td></tr>';
            });
            html += '</tbody></table>';
            return html;
        }

        function swPctCellStr(frac) { return frac == null ? '—' : swPctCell(frac, 2); }

        // Value + its bootstrap percentile rank; dim when inside the IQR (adds nothing), highlight outside.
        function rankBadge(valueHtml, rank) {
            if (rank == null) return valueHtml;
            var pctile = Math.round(rank * 100);
            var inside = rank >= 0.25 && rank <= 0.75;
            var cls = inside ? 'swing-smalln' : 'swing-rank-hit';
            return valueHtml + ' <span class="' + cls + '" title="percentile rank within the bootstrap">(p' + pctile + ')</span>';
        }

        // ---- §7 correlation ----
        function renderSwingCorr() {
            if (!swReport) return;
            var c = swReport.correlation;
            var syms = c.symbols || [];
            var matrix = swCorrMode === '60' ? c.matrix60 : (swCorrMode === 'down' ? c.downDayMatrix : c.matrix30);
            var caption = swCorrMode === 'down'
                ? 'Tail correlation: ' + c.downDayCount + ' days where the cluster mean return ≤ −' + (c.downDayThreshold * 100).toFixed(0) + '%'
                : 'Latest rolling ' + (swCorrMode === '60' ? c.window60 : c.window30) + '-day pairwise correlation';
            document.getElementById('sw-corr-caption').textContent = caption;

            var table = document.getElementById('sw-corr-heatmap');
            table.innerHTML = '';
            if (!syms.length || !matrix) { table.innerHTML = '<tbody><tr><td class="muted">No cluster data.</td></tr></tbody>'; }
            else {
                var thead = '<thead><tr><th class="corr-axis"></th>';
                syms.forEach(function (s) { thead += '<th class="corr-axis">' + s + '</th>'; });
                thead += '</tr></thead>';
                var tbody = '<tbody>';
                matrix.forEach(function (row, i) {
                    tbody += '<tr><th class="corr-axis">' + syms[i] + '</th>';
                    row.forEach(function (cell) {
                        if (cell == null) { tbody += '<td class="muted">—</td>'; return; }
                        var v = parseFloat(cell);
                        var col = corrColour(v);
                        tbody += '<td style="background:' + col.bg + ';color:' + col.fg + '">' + v.toFixed(2) + '</td>';
                    });
                    tbody += '</tr>';
                });
                tbody += '</tbody>';
                table.innerHTML = thead + tbody;
            }
            renderSwingCorrChart(c);
        }

        function renderSwingCorrChart(c) {
            if (swCorrChart) swCorrChart.destroy();
            var pts = c.avgPairwise || [];
            if (!pts.length) return;
            var ds = function (key, colour) {
                return {
                    label: key === 'avg30' ? c.window30 + 'd' : c.window60 + 'd',
                    data: pts.filter(function (p) { return p[key] != null; })
                             .map(function (p) { return { x: p.date, y: parseFloat(p[key]) }; }),
                    borderColor: colour, backgroundColor: 'transparent',
                    borderWidth: 1.4, pointRadius: 0, tension: 0.1
                };
            };
            swCorrChart = new Chart(document.getElementById('sw-corr-chart').getContext('2d'), {
                type: 'line',
                data: { datasets: [ds('avg30', '#1f77b4'), ds('avg60', '#ff7f0e')] },
                options: {
                    responsive: true, maintainAspectRatio: false,
                    interaction: { mode: 'nearest', axis: 'x', intersect: false },
                    scales: {
                        x: { type: 'time', time: { unit: 'month' } },
                        y: { suggestedMin: 0, suggestedMax: 1, title: { display: true, text: 'avg pairwise ρ' } }
                    },
                    plugins: { legend: { position: 'bottom', labels: { boxWidth: 10 } } }
                }
            });
        }


        // ================= Trend filter tab =================
        var trReport = null;
        var trInit = false;

        function setupTrend() {
            if (trInit) return;
            trInit = true;
            document.getElementById('tr-run').addEventListener('click', runTrend);
            document.getElementById('tr-backfill').addEventListener('click', backfillTrend);
            document.getElementById('tr-detail-variant').addEventListener('change', renderTrendDetail);
            document.getElementById('tr-tradelog-csv').addEventListener('click', function (e) {
                e.preventDefault();
                downloadTrendCsv();
            });
        }

        function trVal(id) { return (document.getElementById(id).value || '').trim(); }
        function trSet(id, v) { document.getElementById(id).value = v; }
        function trStatus(msg) { document.getElementById('tr-status').textContent = msg || ''; }

        function buildTrendQuery() {
            var map = {
                primary: trVal('tr-primary'), extension: trVal('tr-extension'), cash: trVal('tr-cash'),
                flatCash: trVal('tr-flatcash'), signalSource: trVal('tr-signalsource'),
                maLengths: trVal('tr-malengths'), monthlyMa: trVal('tr-monthlyma'),
                anchorEvery: trVal('tr-anchorevery'), anchorOffset: trVal('tr-anchoroffset'),
                bufferPcts: trVal('tr-bufferpcts'), bufferNs: trVal('tr-bufferns'),
                costs: trVal('tr-costs'), headlineCost: trVal('tr-headlinecost'),
                halfFraction: trVal('tr-halffraction'), bootstrap: trVal('tr-bootstrap')
            };
            var parts = [];
            Object.keys(map).forEach(function (k) {
                // extension/cash may be intentionally blank (disables) — always send them.
                if (map[k] !== '' || k === 'extension' || k === 'cash') {
                    parts.push(k + '=' + encodeURIComponent(map[k]));
                }
            });
            return parts.join('&');
        }

        function runTrend() {
            trStatus('Computing (~1000 bootstrap runs per cell)…');
            fetch('/trend?' + buildTrendQuery())
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (rep) {
                    trReport = rep;
                    fillTrendInputs(rep.config);
                    document.getElementById('tr-empty').style.display = 'none';
                    renderTrendData();
                    renderTrendLive();
                    renderTrendGrid();
                    renderTrendSens();
                    renderTrendAnchor();
                    renderTrendBoot();
                    renderTrendWhip();
                    populateTrendDetail();
                    renderTrendDetail();
                    ['tr-data-wrap', 'tr-live-wrap', 'tr-grid-wrap', 'tr-sens-wrap', 'tr-anchor-wrap',
                     'tr-boot-wrap', 'tr-whip-wrap', 'tr-detail-wrap'].forEach(function (id) {
                        document.getElementById(id).style.display = 'block';
                    });
                    trStatus('Updated ' + new Date().toLocaleTimeString());
                })
                .catch(function (e) { trStatus('Error: ' + e.message); });
        }

        function backfillTrend() {
            trStatus('Backfill started (45y per new symbol; ^NDX takes ~a minute)…');
            fetch('/trend/backfill', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function () { trStatus('Backfill running. Re-run once prices land.'); })
                .catch(function (e) { trStatus('Backfill failed: ' + e.message); });
        }

        function fillTrendInputs(cfg) {
            trSet('tr-primary', cfg.primarySymbol);
            trSet('tr-extension', cfg.extensionSymbol || '');
            trSet('tr-cash', cfg.cashSymbol || '');
            trSet('tr-flatcash', cfg.flatCashRatePct);
            document.getElementById('tr-signalsource').value = cfg.signalSource;
            trSet('tr-malengths', cfg.maLengths.join(','));
            trSet('tr-monthlyma', cfg.monthlyMaLengths.join(','));
            trSet('tr-anchorevery', cfg.anchorEvery);
            trSet('tr-anchoroffset', cfg.anchorOffset);
            trSet('tr-bufferpcts', cfg.bufferPcts.map(function (x) { return +(x * 100).toFixed(4); }).join(','));
            trSet('tr-bufferns', cfg.bufferNs.join(','));
            trSet('tr-costs', cfg.costsBps.join(','));
            trSet('tr-headlinecost', cfg.headlineCostBps);
            trSet('tr-halffraction', cfg.halfSwitchFraction);
            trSet('tr-bootstrap', cfg.bootstrapIterations);
        }

        function trMaLabel(v) {
            return v.maLength != null ? v.maLength + 'd' : v.key.replace('monthly-', '') + 'mo';
        }

        function renderTrendData() {
            var d = trReport.data;
            var parts = [];
            parts.push('<b>' + d.primarySymbol + '</b> ' + (d.from || '—') + ' → ' + (d.to || '—') +
                       (d.years ? ' (' + d.years.toFixed(1) + 'y)' : ''));
            if (d.spliced) {
                parts.push('spliced with <b>' + d.extensionSymbol + '</b> at ' + d.spliceDate +
                           ' — <span class="swing-warn">price-only proxy ' + d.proxiedFrom + ' → ' + d.proxiedTo + '</span>');
            } else if (d.extensionSymbol) {
                parts.push('no splice in effect');
            }
            parts.push(d.cashSymbol
                ? 'cash leg <b>' + d.cashSymbol + '</b> from ' + d.cashFrom +
                  (d.cashFallbackUntil ? ' (flat ' + d.flatCashRatePct + '% until ' + d.cashFallbackUntil + ')' : '')
                : 'cash leg flat ' + d.flatCashRatePct + '%');
            if (d.analysisStart) {
                parts.push('analysis window from <b>' + d.analysisStart + '</b> (' + d.analysisBars +
                           ' bars; MA warm-up consumes the difference)');
            }
            parts.push(d.dotComInSample
                ? '<span class="swing-pos">2000–02 bust in-sample</span>'
                : '<span class="swing-warn">2000–02 bust NOT in-sample</span>');
            document.getElementById('tr-data').innerHTML = parts.join(' · ');
            document.getElementById('tr-warnings').innerHTML = (d.warnings || []).map(function (w) {
                return '<div class="swing-warn" style="margin-top:0.3rem;">⚠ ' + w + '</div>';
            }).join('');
        }

        function renderTrendLive() {
            var ls = trReport.liveState;
            if (!ls) return;
            var readout = function (rows) {
                var h = '<thead><tr><th class="lbl">MA</th><th>Close</th><th>MA level</th><th>Dist</th><th>State</th></tr></thead><tbody>';
                rows.forEach(function (r) {
                    h += '<tr><td class="lbl">' + r.length + 'd</td><td>' + swNum(r.close) + '</td><td>' +
                         swNum(r.ma) + '</td><td>' + swPctCell(r.distancePct) + '</td><td>' +
                         (r.above ? '<span class="swing-pos">above</span>' : '<span class="swing-neg">below</span>') +
                         '</td></tr>';
                });
                return h + '</tbody>';
            };
            document.getElementById('tr-live-primary-head').textContent =
                ls.primarySymbol + ' (as of ' + ls.asOf + ')';
            document.getElementById('tr-live-primary').innerHTML = readout(ls.primary || []);
            var holdingHead = document.getElementById('tr-live-holding-head');
            if ((ls.holding || []).length > 0) {
                holdingHead.textContent = ls.holdingSymbol + ' (as of ' + ls.holdingAsOf + ')';
                document.getElementById('tr-live-holding').innerHTML = readout(ls.holding);
            } else {
                holdingHead.textContent = ls.holdingSymbol + ': no local history';
                document.getElementById('tr-live-holding').innerHTML = '';
            }
            var h = '<thead><tr><th class="lbl">Variant</th><th>MA</th><th>State</th><th>Pending</th><th class="lbl">Today</th></tr></thead><tbody>';
            (ls.variants || []).forEach(function (v) {
                h += '<tr><td class="lbl">' + v.label + '</td><td>' + trMaLabel(v) + '</td><td>' +
                     (v.invested ? '<span class="swing-pos">in</span>' : '<span class="swing-neg">out</span>') +
                     '</td><td>' + (v.pendingSwitch ? '<b class="swing-warn">switch at next open</b>' : '—') +
                     '</td><td class="lbl">' + v.note + '</td></tr>';
            });
            document.getElementById('tr-live-variants').innerHTML = h + '</tbody>';
        }

        function trStatsCells(st) {
            if (!st) return '<td colspan="8">—</td>';
            return '<td>' + swPctCell(st.cagr) + '</td><td>' + swPct(st.vol) + '</td><td>' +
                   swPctCell(st.maxDrawdown) + '</td><td>' + swNum(st.longestDrawdownMonths, 1) + '</td><td>' +
                   swPct(st.timeInvested, 0) + '</td><td>' + st.roundTrips + '</td><td>' + st.switches +
                   '</td><td>' + swNum(st.sharpe) + '</td>';
        }

        function renderTrendGrid() {
            document.getElementById('tr-grid-sub').textContent =
                'full switch, next-open fills, ' + trReport.config.headlineCostBps +
                ' bps/side, historical cash — every cell over the same window';
            var h = '<thead><tr><th class="lbl" data-sort="str">Variant</th><th data-sort="str">MA</th>' +
                    '<th data-sort="num">CAGR</th><th data-sort="num">Vol</th><th data-sort="num">MaxDD</th>' +
                    '<th data-sort="num">DD mo</th><th data-sort="num">Time in</th><th data-sort="num">Trips</th>' +
                    '<th data-sort="num">Switches</th><th data-sort="num">Sharpe</th></tr></thead><tbody>';
            var bh = trReport.buyHold;
            h += '<tr class="swing-baseline"><td class="lbl"><b>Buy &amp; hold</b></td><td>—</td>' +
                 trStatsCells(bh) + '</tr>';
            (trReport.variants || []).forEach(function (v) {
                h += '<tr><td class="lbl">' + v.label + '</td><td>' + trMaLabel(v) + '</td>' +
                     trStatsCells(v.headline) + '</tr>';
            });
            var t = document.getElementById('tr-grid');
            t.innerHTML = h + '</tbody>';
            attachSort(t);
        }

        function renderTrendSens() {
            var costs = (trReport.variants[0] || {}).costs || [];
            var h = '<thead><tr><th class="lbl" data-sort="str">Variant</th><th data-sort="str">MA</th>';
            costs.forEach(function (c) { h += '<th data-sort="num">@' + c.bps + 'bp</th>'; });
            h += '<th data-sort="num">Close-fill</th><th data-sort="num">Half CAGR</th>' +
                 '<th data-sort="num">Half MaxDD</th><th data-sort="num">0% cash</th></tr></thead><tbody>';
            (trReport.variants || []).forEach(function (v) {
                h += '<tr><td class="lbl">' + v.label + '</td><td>' + trMaLabel(v) + '</td>';
                (v.costs || []).forEach(function (c) { h += '<td>' + swPctCell(c.stats.cagr) + '</td>'; });
                h += '<td>' + swPctCell(v.closeFill && v.closeFill.cagr) + '</td>' +
                     '<td>' + swPctCell(v.halfSwitch && v.halfSwitch.cagr) + '</td>' +
                     '<td>' + swPctCell(v.halfSwitch && v.halfSwitch.maxDrawdown) + '</td>' +
                     '<td>' + swPctCell(v.cashZero && v.cashZero.cagr) + '</td></tr>';
            });
            var t = document.getElementById('tr-sens');
            t.innerHTML = h + '</tbody>';
            attachSort(t);
        }

        function renderTrendAnchor() {
            var h = '<thead><tr><th class="lbl">MA</th><th>CAGR min</th><th>q1</th><th>median</th><th>q3</th>' +
                    '<th>max</th><th>spread</th><th>MaxDD min</th><th>median</th><th>max</th></tr></thead><tbody>';
            (trReport.anchorSensitivity || []).forEach(function (a) {
                h += '<tr><td class="lbl">' + a.maLength + 'd</td><td>' + swPctCell(a.cagr.min) + '</td><td>' +
                     swPctCell(a.cagr.q1) + '</td><td><b>' + swPctCell(a.cagr.median) + '</b></td><td>' +
                     swPctCell(a.cagr.q3) + '</td><td>' + swPctCell(a.cagr.max) + '</td><td>' +
                     swPct(a.cagr.max - a.cagr.min) + '</td><td>' + swPctCell(a.maxDd.min) + '</td><td>' +
                     swPctCell(a.maxDd.median) + '</td><td>' + swPctCell(a.maxDd.max) + '</td></tr>';
            });
            document.getElementById('tr-anchor').innerHTML = h + '</tbody>';
        }

        function trPctileCell(p) {
            if (p == null) return '—';
            var cls = p >= 75 ? 'swing-pos' : (p <= 25 ? 'swing-neg' : 'swing-rank-hit');
            var note = (p > 25 && p < 75) ? ' (inside IQR)' : '';
            return '<span class="' + cls + '">' + p.toFixed(0) + note + '</span>';
        }

        function renderTrendBoot() {
            var h = '<thead><tr><th class="lbl" data-sort="str">Variant</th><th data-sort="str">MA</th>' +
                    '<th data-sort="num">Trips</th><th data-sort="num">CAGR pctile</th><th>Boot CAGR q1/med/q3</th>' +
                    '<th data-sort="num">MaxDD pctile</th><th>Boot MaxDD q1/med/q3</th></tr></thead><tbody>';
            (trReport.variants || []).forEach(function (v) {
                var b = v.bootstrap;
                h += '<tr><td class="lbl">' + v.label + '</td><td>' + trMaLabel(v) + '</td>';
                if (!b) {
                    h += '<td>0</td><td colspan="4" class="swing-smalln">never exited — nothing to scatter</td></tr>';
                    return;
                }
                h += '<td>' + v.headline.roundTrips + '</td><td>' + trPctileCell(b.cagrPercentile) + '</td><td>' +
                     swPct(b.cagrDist.q1) + ' / ' + swPct(b.cagrDist.median) + ' / ' + swPct(b.cagrDist.q3) +
                     '</td><td>' + trPctileCell(b.maxDdPercentile) + '</td><td>' +
                     swPct(b.maxDdDist.q1) + ' / ' + swPct(b.maxDdDist.median) + ' / ' + swPct(b.maxDdDist.q3) +
                     '</td></tr>';
            });
            var t = document.getElementById('tr-boot');
            t.innerHTML = h + '</tbody>';
            attachSort(t);
        }

        function renderTrendWhip() {
            var h = '<thead><tr><th class="lbl" data-sort="str">Variant</th><th data-sort="str">MA</th>' +
                    '<th data-sort="num">Trips</th><th data-sort="num">Bought back higher</th>' +
                    '<th data-sort="num">Whipsaw drag</th><th data-sort="num">Net timing effect</th></tr></thead><tbody>';
            (trReport.variants || []).forEach(function (v) {
                var w = v.whipsaw || {};
                h += '<tr><td class="lbl">' + v.label + '</td><td>' + trMaLabel(v) + '</td><td>' +
                     (w.roundTrips || 0) + '</td><td>' + (w.boughtBackHigher || 0) + '</td><td>' +
                     swPctCell(w.whipsawDrag) + '</td><td>' + swPctCell(w.netTimingEffect) + '</td></tr>';
            });
            var t = document.getElementById('tr-whip');
            t.innerHTML = h + '</tbody>';
            attachSort(t);
        }

        function populateTrendDetail() {
            var sel = document.getElementById('tr-detail-variant');
            var prev = sel.value;
            sel.innerHTML = '';
            (trReport.variants || []).forEach(function (v, i) {
                var o = document.createElement('option');
                o.value = i;
                o.textContent = v.label + ' · ' + trMaLabel(v);
                sel.appendChild(o);
            });
            if (prev && prev < trReport.variants.length) sel.value = prev;
        }

        function renderTrendDetail() {
            var v = (trReport.variants || [])[+document.getElementById('tr-detail-variant').value || 0];
            if (!v) return;

            var h = '<thead><tr><th class="lbl">Episode</th><th>Window</th><th>Covered</th><th>Strategy</th>' +
                    '<th>B&amp;H</th><th>Strat MaxDD</th><th>B&amp;H MaxDD</th><th>Switches</th><th>Days out</th></tr></thead><tbody>';
            (v.episodes || []).forEach(function (e) {
                h += '<tr><td class="lbl"><b>' + e.name + '</b></td><td>' + e.from + ' → ' + e.to + '</td><td>' +
                     (e.covered ? 'yes' : '<span class="swing-warn">' +
                        (e.strategyReturn == null ? 'no data' : 'partial') + '</span>') + '</td><td>' +
                     swPctCell(e.strategyReturn) + '</td><td>' + swPctCell(e.bhReturn) + '</td><td>' +
                     swPctCell(e.strategyMaxDd) + '</td><td>' + swPctCell(e.bhMaxDd) + '</td><td>' +
                     (e.switches == null ? '—' : e.switches) + '</td><td>' +
                     (e.daysOut == null ? '—' : e.daysOut) + '</td></tr>';
            });
            document.getElementById('tr-episodes').innerHTML = h + '</tbody>';

            h = '<thead><tr><th class="lbl" data-sort="num">Year</th><th data-sort="num">Strategy</th>' +
                '<th data-sort="num">B&amp;H</th><th data-sort="num">Diff</th><th data-sort="num">Time in</th>' +
                '<th data-sort="num">Switches</th></tr></thead><tbody>';
            (v.annual || []).forEach(function (y) {
                h += '<tr><td class="lbl">' + y.year + (y.partial ? '*' : '') + '</td><td>' +
                     swPctCell(y.strategyReturn) + '</td><td>' + swPctCell(y.bhReturn) + '</td><td>' +
                     swPctCell(y.strategyReturn - y.bhReturn) + '</td><td>' + swPct(y.timeInvested, 0) +
                     '</td><td>' + y.switches + '</td></tr>';
            });
            var annualTable = document.getElementById('tr-annual');
            annualTable.innerHTML = h + '</tbody>';
            attachSort(annualTable);

            h = '<thead><tr><th class="lbl">Decade</th><th>Trips</th><th>Bought higher</th>' +
                '<th>Whipsaw drag</th><th>Net effect</th></tr></thead><tbody>';
            ((v.whipsaw || {}).perDecade || []).forEach(function (d) {
                h += '<tr><td class="lbl">' + d.decade + '</td><td>' + d.trips + '</td><td>' +
                     d.boughtBackHigher + '</td><td>' + swPctCell(d.whipsawDrag) + '</td><td>' +
                     swPctCell(d.netTimingEffect) + '</td></tr>';
            });
            document.getElementById('tr-decades').innerHTML = h + '</tbody>';

            document.getElementById('tr-tradelog-count').textContent =
                '(' + (v.trades || []).length + ' rows — exit → re-entry pairs)';
            h = '<thead><tr><th>Exit</th><th>Price</th><th>MA</th><th>Dist</th><th>Re-entry</th><th>Price</th>' +
                '<th>MA</th><th>Dist</th><th>Days out</th><th>Re-entry vs exit</th><th>Trip effect</th></tr></thead><tbody>';
            (v.trades || []).forEach(function (r) {
                h += '<tr><td>' + (r.exitDate || '—') + '</td><td>' + swNum(r.exitPrice) + '</td><td>' +
                     swNum(r.exitMa) + '</td><td>' + swPctCell(r.exitDistancePct) + '</td><td>' +
                     (r.reentryDate || '<span class="swing-warn">open</span>') + '</td><td>' +
                     swNum(r.reentryPrice) + '</td><td>' + swNum(r.reentryMa) + '</td><td>' +
                     swPctCell(r.reentryDistancePct) + '</td><td>' + (r.daysOut == null ? '—' : r.daysOut) +
                     '</td><td>' + swPctCell(r.reentryVsExitPct) + '</td><td>' +
                     swPctCell(r.tripEffectPct) + '</td></tr>';
            });
            document.getElementById('tr-tradelog').innerHTML = h + '</tbody>';
        }

        function downloadTrendCsv() {
            var v = (trReport.variants || [])[+document.getElementById('tr-detail-variant').value || 0];
            if (!v) return;
            var cols = [
                ['exitDate', csvRaw], ['exitPrice', csvNum], ['exitMa', csvNum], ['exitDistancePct', csvPct],
                ['reentryDate', csvRaw], ['reentryPrice', csvNum], ['reentryMa', csvNum],
                ['reentryDistancePct', csvPct], ['daysOut', csvRaw],
                ['reentryVsExitPct', csvPct], ['tripEffectPct', csvPct]
            ];
            var lines = [cols.map(function (c) { return c[0]; }).join(',')];
            (v.trades || []).forEach(function (r) {
                lines.push(cols.map(function (c) { return csvCell(c[1](r[c[0]])); }).join(','));
            });
            downloadCsv('trend-trades-' + v.key + '.csv', lines.join('\n'));
        }

        /* ==================== Momentum tab ==================== */

        var moInit = false, moReport = null;

        function setupMomentum() {
            if (moInit) return;
            moInit = true;
            document.getElementById('mo-run').addEventListener('click', runMomentum);
            document.getElementById('mo-backfill').addEventListener('click', backfillMomentum);
            document.getElementById('mo-save').addEventListener('click', saveMomentumUniverse);
            document.getElementById('mo-grid-variant').addEventListener('change', renderMomentumGrid);
            document.getElementById('mo-log-variant').addEventListener('change', renderMomentumLog);
            document.getElementById('mo-log-csv').addEventListener('click', function (e) {
                e.preventDefault();
                downloadMomentumCsv();
            });
            loadMomentumSymbols();
        }

        /** Prefill the Universe input with the stored list and fill the saved-lists datalist. */
        function loadMomentumSymbols() {
            fetch('/momentum/symbols')
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (res) {
                    var input = document.getElementById('mo-universe');
                    if (!input.value.trim() && res.universe) input.value = res.universe.join(',');
                    renderMomentumHints(res.history);
                })
                .catch(function () { /* hints are a convenience — never block the tab */ });
        }

        /** Saved lists as <option>s, newest first so the most recent list tops the dropdown. */
        function renderMomentumHints(history) {
            if (!history) return;
            document.getElementById('mo-universe-hints').innerHTML =
                history.slice().reverse().map(function (h) {
                    return '<option value="' + escapeHtml(h) + '"></option>';
                }).join('');
        }

        function moVal(id) { return (document.getElementById(id).value || '').trim(); }
        function moStatus(msg) { document.getElementById('mo-status').textContent = msg || ''; }
        function moNum(v, dp) {
            if (v === null || v === undefined || (typeof v === 'number' && !isFinite(v))) return '—';
            return (+v).toFixed(dp === undefined ? 2 : dp);
        }
        function moCls(v) { return v > 0 ? 'swing-pos' : (v < 0 ? 'swing-neg' : ''); }

        function buildMomentumQuery() {
            var map = {
                universe: moVal('mo-universe'), topNs: moVal('mo-topns'), lookback: moVal('mo-lookback'),
                skip: moVal('mo-skip'), cost: moVal('mo-cost'), rf: moVal('mo-rf'),
                holdoutMonths: moVal('mo-holdout'), bootstrap: moVal('mo-bootstrap'),
                seed: moVal('mo-seed'), benchmarks: moVal('mo-benchmarks'),
                regimeSymbol: moVal('mo-regime'), crashDrawdown: moVal('mo-crashdd')
            };
            var parts = [];
            Object.keys(map).forEach(function (k) {
                if (map[k] !== '') parts.push(k + '=' + encodeURIComponent(map[k]));
            });
            return parts.join('&');
        }

        function runMomentum() {
            moStatus('Computing (bootstrap per variant)…');
            fetch('/momentum?' + buildMomentumQuery())
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (rep) {
                    moReport = rep;
                    fillMomentumInputs(rep.config);
                    document.getElementById('mo-empty').style.display = 'none';
                    renderMomentumHeader();
                    renderMomentumVerdicts();
                    renderMomentumFit();
                    renderMomentumRegime();
                    renderMomentumCrash();
                    populateMomentumVariantSelects();
                    renderMomentumGrid();
                    renderMomentumHoldout();
                    renderMomentumLog();
                    ['mo-header-wrap', 'mo-fit-wrap', 'mo-grid-wrap', 'mo-holdout-wrap', 'mo-log-wrap']
                        .forEach(function (id) { document.getElementById(id).style.display = 'block'; });
                    moStatus('Updated ' + new Date().toLocaleTimeString());
                })
                .catch(function (e) { moStatus('Error: ' + e.message); });
        }

        function backfillMomentum() {
            moStatus('Backfill started (deep daily history per new symbol)…');
            fetch('/momentum/backfill', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function () { moStatus('Backfill running. Re-run once prices land.'); })
                .catch(function (e) { moStatus('Backfill failed: ' + e.message); });
        }

        function saveMomentumUniverse() {
            var body = 'universe=' + encodeURIComponent(moVal('mo-universe'));
            fetch('/momentum/symbols', {
                method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body
            }).then(function (r) { return r.json(); })
                .then(function (res) {
                    document.getElementById('mo-universe').value = (res.universe || []).join(',');
                    renderMomentumHints(res.history);
                    moStatus('Universe saved (' + (res.universe || []).length + ' names); backfilling new ones.');
                })
                .catch(function (e) { moStatus('Save failed: ' + e.message); });
        }

        function fillMomentumInputs(cfg) {
            if (!cfg) return;
            document.getElementById('mo-universe').value = (cfg.universe || []).join(',');
            document.getElementById('mo-topns').value = (cfg.topNs || []).join(',');
            document.getElementById('mo-lookback').value = cfg.lookbackSessions;
            document.getElementById('mo-skip').value = cfg.skipSessions;
            document.getElementById('mo-cost').value = cfg.costPerSideBps;
            document.getElementById('mo-rf').value = cfg.rfAnnualPct;
            document.getElementById('mo-holdout').value = cfg.holdoutMonths;
            document.getElementById('mo-bootstrap').value = cfg.bootstrapIterations;
            document.getElementById('mo-seed').value = cfg.randomSeed;
            document.getElementById('mo-benchmarks').value = (cfg.benchmarkSymbols || []).join(',');
            document.getElementById('mo-regime').value = cfg.regimeSymbol;
            document.getElementById('mo-crashdd').value = cfg.crashDrawdownPct;
        }

        function renderMomentumHeader() {
            var r = moReport;
            var banner = document.getElementById('mo-banner');
            banner.textContent = '⚠ ' + r.survivorshipCaveat;
            banner.style.display = 'block';

            var spans = (r.symbolSpans || []).map(function (s) {
                return s.symbol + (s.ranked ? '' : ' (bench)') + ' ' + s.from + '→' + s.to + ' (' + s.bars + ')';
            }).join(' · ');
            var html = '';
            if (r.analysisStart) {
                html += '<div><b>Analysis start:</b> ' + r.analysisStart + ' — ' + r.analysisStartReason + '</div>';
                html += '<div><b>Fit:</b> ' + r.fitFrom + ' → ' + r.fitTo
                    + (r.holdoutAvailable ? ' &nbsp;|&nbsp; <b style="color:#b0862f;">Holdout:</b> '
                        + r.holdoutFrom + ' → ' + r.holdoutTo : ' &nbsp;|&nbsp; <i>no holdout window</i>')
                    + ' &nbsp;|&nbsp; <b>seed</b> ' + r.bootstrapSeed + '</div>';
            }
            if ((r.missingSymbols || []).length) {
                html += '<div class="swing-warn">No price history (excluded): ' + r.missingSymbols.join(', ')
                    + ' — run Backfill.</div>';
            }
            if ((r.gaps || []).length) {
                html += '<div class="muted" style="font-size:0.78rem;">Forward-fill gaps (ineligible over the stretch): '
                    + r.gaps.map(function (g) { return g.symbol + ' ' + g.from + '→' + g.to + ' (' + g.sessions + 'd)'; }).join(' · ')
                    + '</div>';
            }
            html += '<div class="muted" style="font-size:0.75rem; margin-top:0.2rem;">' + spans + '</div>';
            document.getElementById('mo-header').innerHTML = html;

            var warns = (r.warnings || []).map(function (w) {
                return '<div class="swing-warn">' + w + '</div>';
            }).join('');
            document.getElementById('mo-warnings').innerHTML = warns;
        }

        function renderMomentumVerdicts() {
            var html = (moReport.variants || []).map(function (v) {
                var boot = v.fit.bootstrap;
                var tagged = v.verdict;
                if (boot) {
                    var cls = boot.cagrInsideIqr ? 'mo-tag-random' : 'mo-tag-signal';
                    tagged = tagged.replace(/\((≈random|signal)\)/, '<span class="' + cls + '">($1)</span>');
                }
                return '<div class="mo-verdict">• ' + tagged + '</div>';
            }).join('');
            document.getElementById('mo-verdicts').innerHTML = html;
        }

        // Rows: each top-N variant, then EW baseline + benchmarks as controls.
        function renderMomentumFit() {
            var r = moReport;
            var head = ['Strategy', 'CAGR', 'Vol', 'Sharpe', 'MaxDD', 'Calmar', 'Hit% vs EW',
                'Turnover', 'Cost drag', 'Boot pct', 'Rev bot-N'];
            var rows = [];
            (r.variants || []).forEach(function (v) {
                rows.push(fitRow(v.label, v.fit, v.bottomNFitCagr, false));
            });
            if (r.baselineFit) rows.push(fitRow('EW baseline', r.baselineFit, null, true));
            (r.benchmarks || []).forEach(function (b) {
                rows.push(fitRow(b.symbol + ' (B&H)', { stats: b.fit }, null, true));
            });
            document.getElementById('mo-fit').innerHTML = swingTable(head, rows);
            document.getElementById('mo-fit-sub').textContent =
                r.fitFrom + ' → ' + r.fitTo + ' · controls in the lower rows';
        }

        function fitRow(label, section, revCagr, isControl) {
            var s = section.stats;
            var ddTitle = s.maxDrawdownPeak ? s.maxDrawdownPeak + ' → ' + s.maxDrawdownTrough : '';
            var boot = section.bootstrap;
            var bootCell = { t: '—' };
            if (boot) {
                bootCell = {
                    t: Math.round(boot.cagrPercentile * 100) + 'th',
                    cls: boot.cagrInsideIqr ? 'mo-dim' : (boot.cagrPercentile > 0.75 ? 'swing-pos' : '')
                };
            }
            return {
                cls: isControl ? 'swing-baseline' : '',
                cells: [
                    { t: label, lbl: true },
                    { t: fmtPct(s.cagr), cls: moCls(s.cagr) },
                    { t: fmtPct(s.annVol) },
                    { t: moNum(s.sharpe) },
                    { t: fmtPct(s.maxDrawdown), cls: 'swing-neg', title: ddTitle },
                    { t: moNum(s.calmar) },
                    { t: isControl && !section.bootstrap && !revCagr && label.indexOf('EW') === 0
                        ? '—' : (s.hitRateVsBaseline == null ? '—' : Math.round(s.hitRateVsBaseline * 100) + '%') },
                    { t: section.meanMonthlyTurnover == null ? '—' : fmtPct(section.meanMonthlyTurnover) },
                    { t: section.costDragCagrPct == null ? '—' : moNum(section.costDragCagrPct, 2) + ' pp' },
                    bootCell,
                    { t: revCagr == null ? '—' : fmtPct(revCagr), cls: moCls(revCagr) }
                ]
            };
        }

        function renderMomentumRegime() {
            var head = ['Strategy', 'Above ann', 'Above Sharpe', 'Above n', 'Below ann', 'Below Sharpe',
                'Below n', 'Unknown n'];
            var rows = [];
            function regRow(label, section, isControl) {
                var g = section.regime;
                if (!g) return;
                function cell(b, field, isPct) {
                    if (!b || b.months === 0) return { t: '—', cls: 'mo-dim' };
                    var v = b[field];
                    return { t: isPct ? fmtPct(v) : moNum(v), cls: (b.thin ? 'mo-dim ' : '') + (isPct ? moCls(v) : '') };
                }
                rows.push({
                    cls: isControl ? 'swing-baseline' : '',
                    cells: [
                        { t: label, lbl: true },
                        cell(g.above, 'annReturn', true), cell(g.above, 'sharpe', false),
                        { t: g.above ? g.above.months : 0, cls: g.above && g.above.thin ? 'mo-dim' : '' },
                        cell(g.below, 'annReturn', true), cell(g.below, 'sharpe', false),
                        { t: g.below ? g.below.months : 0, cls: g.below && g.below.thin ? 'mo-dim' : '' },
                        { t: g.unknown ? g.unknown.months : 0 }
                    ]
                });
            }
            (moReport.variants || []).forEach(function (v) { regRow(v.label, v.fit, false); });
            if (moReport.baselineFit) regRow('EW baseline', moReport.baselineFit, true);
            document.getElementById('mo-regime-tbl').innerHTML = swingTable(head, rows);
        }

        function renderMomentumCrash() {
            var head = ['Strategy', 'Worst 3 months (strategy · QQQ)', 'Crash setups', 'Episodes',
                'Post-crash mean', 'Post-crash median', 'Censored'];
            var rows = [];
            function crashRow(label, section, isControl) {
                var c = section.crash;
                if (!c) return;
                var worst = (c.worstMonths || []).map(function (w) {
                    return w.month + ' ' + fmtPct(w.strategyReturn)
                        + ' (' + (w.indexReturn == null ? '—' : fmtPct(w.indexReturn)) + ')';
                }).join('; ');
                var wr = c.windowReturns || {};
                rows.push({
                    cls: isControl ? 'swing-baseline' : '',
                    cells: [
                        { t: label, lbl: true },
                        { t: worst || '—', lbl: true },
                        { t: c.setupMonths },
                        { t: c.episodes },
                        { t: wr.n ? fmtPct(wr.mean) : '—', cls: wr.n ? moCls(wr.mean) : '' },
                        { t: wr.n ? fmtPct(wr.median) : '—', cls: wr.n ? moCls(wr.median) : '' },
                        { t: c.censored }
                    ]
                });
            }
            (moReport.variants || []).forEach(function (v) { crashRow(v.label, v.fit, false); });
            if (moReport.baselineFit) crashRow('EW baseline', moReport.baselineFit, true);
            document.getElementById('mo-crash-tbl').innerHTML = swingTable(head, rows);
        }

        function populateMomentumVariantSelects() {
            var opts = (moReport.variants || []).map(function (v, i) {
                return '<option value="' + i + '">' + v.label + '</option>';
            }).join('');
            document.getElementById('mo-grid-variant').innerHTML = opts;
            document.getElementById('mo-log-variant').innerHTML = opts;
        }

        function moSelectedVariant(selectId) {
            var i = +document.getElementById(selectId).value || 0;
            return (moReport.variants || [])[i];
        }

        function renderMomentumGrid() {
            var v = moSelectedVariant('mo-grid-variant');
            if (!v) return;
            var months = {};
            Object.assign(months, v.fit.stats.months);
            var holdoutKeys = {};
            if (v.holdout && v.holdout.months) {
                Object.keys(v.holdout.months).forEach(function (k) {
                    months[k] = v.holdout.months[k];
                    holdoutKeys[k] = true;
                });
            }
            var keys = Object.keys(months).sort();
            if (!keys.length) { document.getElementById('mo-grid').innerHTML = ''; return; }
            var years = [];
            var byYear = {};
            keys.forEach(function (k) {
                var y = k.slice(0, 4), m = +k.slice(5, 7);
                if (!byYear[y]) { byYear[y] = {}; years.push(y); }
                byYear[y][m] = { v: months[k], holdout: holdoutKeys[k] };
            });
            var mn = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
            var html = '<tr><th class="lbl">Year</th>';
            mn.forEach(function (m) { html += '<th>' + m + '</th>'; });
            html += '<th>Year</th></tr>';
            years.forEach(function (y) {
                html += '<tr><td class="lbl">' + y + '</td>';
                var yf = 1, any = false;
                for (var m = 1; m <= 12; m++) {
                    var cell = byYear[y][m];
                    if (!cell) { html += '<td></td>'; continue; }
                    any = true;
                    yf *= 1 + cell.v;
                    html += '<td style="' + moGridStyle(cell.v, cell.holdout) + '" title="' + y + '-'
                        + ('0' + m).slice(-2) + (cell.holdout ? ' (holdout)' : '') + '">'
                        + (cell.v * 100).toFixed(1) + '</td>';
                }
                html += '<td class="' + moCls(yf - 1) + '"><b>' + (any ? (yf - 1) * 100 : 0).toFixed(1) + '</b></td></tr>';
            });
            document.getElementById('mo-grid').innerHTML = html;
        }

        function moGridStyle(v, holdout) {
            var a = Math.min(Math.abs(v) / 0.15, 1) * 0.55;
            var bg = v >= 0 ? 'rgba(44,160,44,' + a + ')' : 'rgba(192,57,43,' + a + ')';
            return 'background:' + bg + (holdout ? ';outline:2px solid #b0862f;outline-offset:-2px' : '');
        }

        function renderMomentumHoldout() {
            var r = moReport;
            var wrap = document.getElementById('mo-holdout-wrap');
            if (!r.holdoutAvailable) {
                document.getElementById('mo-holdout').innerHTML = '';
                document.getElementById('mo-holdout-sub').textContent = '— no holdout window for this history/config';
                return;
            }
            document.getElementById('mo-holdout-sub').textContent = r.holdoutFrom + ' → ' + r.holdoutTo
                + ' · same parameters, never seen by the fit sections or the bootstrap';
            var head = ['Strategy', 'CAGR', 'Vol', 'Sharpe', 'MaxDD', 'Hit% vs EW', 'Turnover', 'Rev bot-N'];
            var rows = [];
            function hrow(label, stats, turnover, rev, isControl) {
                if (!stats) return;
                rows.push({
                    cls: isControl ? 'swing-baseline' : '',
                    cells: [
                        { t: label, lbl: true },
                        { t: fmtPct(stats.cagr), cls: moCls(stats.cagr) },
                        { t: fmtPct(stats.annVol) },
                        { t: moNum(stats.sharpe) },
                        { t: fmtPct(stats.maxDrawdown), cls: 'swing-neg' },
                        { t: stats.hitRateVsBaseline == null ? '—' : Math.round(stats.hitRateVsBaseline * 100) + '%' },
                        { t: turnover == null ? '—' : fmtPct(turnover) },
                        { t: rev == null ? '—' : fmtPct(rev), cls: moCls(rev) }
                    ]
                });
            }
            (r.variants || []).forEach(function (v) {
                hrow(v.label, v.holdout, v.holdoutMeanTurnover, v.bottomNHoldoutCagr, false);
            });
            hrow('EW baseline', r.baselineHoldout, null, null, true);
            (r.benchmarks || []).forEach(function (b) { hrow(b.symbol + ' (B&H)', b.holdout, null, null, true); });
            document.getElementById('mo-holdout').innerHTML = swingTable(head, rows);
        }

        function renderMomentumLog() {
            var v = moSelectedVariant('mo-log-variant');
            if (!v) return;
            var head = ['Date', 'Sec', 'Elig', 'In', 'Out', 'Holdings (rank:score)', 'Turnover', 'Cost', 'Cash'];
            var rows = (v.log || []).map(function (e) {
                return {
                    cls: e.section === 'holdout' ? 'swing-baseline' : '',
                    cells: [
                        { t: e.date, lbl: true },
                        { t: e.section },
                        { t: e.eligible },
                        { t: (e.in || []).join(' ') || '—', lbl: true },
                        { t: (e.out || []).join(' ') || '—', lbl: true },
                        { t: (e.heldRanks || []).join(' '), lbl: true },
                        { t: fmtPct(e.turnover) },
                        { t: fmtPct(e.cost) },
                        { t: fmtPct(e.cashWeight) }
                    ]
                };
            });
            document.getElementById('mo-log').innerHTML = swingTable(head, rows);
        }

        function downloadMomentumCsv() {
            var v = moSelectedVariant('mo-log-variant');
            if (!v) return;
            var lines = ['date,section,eligible,in,out,holdings,turnover,cost,cash'];
            (v.log || []).forEach(function (e) {
                lines.push([e.date, e.section, e.eligible,
                    '"' + (e.in || []).join(' ') + '"', '"' + (e.out || []).join(' ') + '"',
                    '"' + (e.heldRanks || []).join(' ') + '"',
                    e.turnover, e.cost, e.cashWeight].join(','));
            });
            downloadCsv('momentum-' + v.label.replace(/\s+/g, '') + '.csv', lines.join('\n'));
        }

        /* ==================== Momentum signals tab ==================== */

        var msInit = false, msReport = null;

        function setupMomentumSignals() {
            if (msInit) return;
            msInit = true;
            document.getElementById('ms-run').addEventListener('click', runMosig);
            document.getElementById('ms-backfill').addEventListener('click', backfillMosig);
            document.getElementById('ms-diag-n').addEventListener('change', renderMosigDiag);
            document.getElementById('ms-grid-variant').addEventListener('change', renderMosigGrid);
            document.getElementById('ms-log-variant').addEventListener('change', renderMosigLog);
            document.getElementById('ms-log-csv').addEventListener('click', function (e) {
                e.preventDefault();
                downloadMosigCsv();
            });
            document.getElementById('ms-stops-csv').addEventListener('click', function (e) {
                e.preventDefault();
                downloadMosigStopsCsv();
            });
        }

        function msVal(id) { return (document.getElementById(id).value || '').trim(); }
        function msStatus(msg) { document.getElementById('ms-status').textContent = msg || ''; }

        function runMosig() {
            msStatus('Computing (five variants × N, one bootstrap per N)…');
            var map = {
                topNs: msVal('ms-topns'), cost: msVal('ms-cost'), rf: msVal('ms-rf'),
                holdoutMonths: msVal('ms-holdout'), bootstrap: msVal('ms-bootstrap'),
                seed: msVal('ms-seed'), episodeN: msVal('ms-episoden')
            };
            var parts = [];
            Object.keys(map).forEach(function (k) {
                if (map[k] !== '') parts.push(k + '=' + encodeURIComponent(map[k]));
            });
            fetch('/momentum-signals?' + parts.join('&'))
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (rep) {
                    msReport = rep;
                    document.getElementById('ms-empty').style.display = 'none';
                    renderMosigHeader();
                    renderMosigPredictions();
                    renderMosigHeadToHead();
                    renderMosigEpisodes();
                    populateMosigSelects();
                    renderMosigDiag();
                    renderMosigGrid();
                    renderMosigLog();
                    ['ms-header-wrap', 'ms-pred-wrap', 'ms-h2h-wrap', 'ms-episodes-wrap',
                        'ms-diag-wrap', 'ms-grid-wrap', 'ms-log-wrap']
                        .forEach(function (id) { document.getElementById(id).style.display = 'block'; });
                    msStatus('Updated ' + new Date().toLocaleTimeString());
                })
                .catch(function (e) { msStatus('Error: ' + e.message); });
        }

        function backfillMosig() {
            msStatus('Backfill started…');
            fetch('/momentum-signals/backfill', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function () { msStatus('Backfill running. Re-run once prices land.'); })
                .catch(function (e) { msStatus('Backfill failed: ' + e.message); });
        }

        function renderMosigHeader() {
            var r = msReport;
            document.getElementById('ms-context').innerHTML =
                '<div><b>Context.</b> ' + escapeHtml(r.contextHeader) + '</div>';
            document.getElementById('ms-prereg').innerHTML = '<div class="swing-span" style="border-left: 3px solid #2c6fb0;">'
                + '<b>Pre-registration (verbatim):</b>'
                + (r.preRegistration || []).map(function (p) {
                    return '<div style="margin-top:0.3rem;">• ' + escapeHtml(p) + '</div>';
                }).join('') + '</div>';
            document.getElementById('ms-plain').innerHTML =
                (r.plainEnglish || []).map(function (sent, i) {
                    return '<div style="margin-top:0.2rem;">' + (i + 1) + '. ' + escapeHtml(sent) + '</div>';
                }).join('');

            var banner = document.getElementById('ms-banner');
            banner.textContent = '⚠ ' + r.survivorshipCaveat;
            banner.style.display = 'block';

            var html = '';
            if (r.analysisStart) {
                html += '<div><b>Analysis start:</b> ' + r.analysisStart + ' — ' + r.analysisStartReason + '</div>';
                html += '<div><b>Fit:</b> ' + r.fitFrom + ' → ' + r.fitTo
                    + (r.holdoutAvailable ? ' &nbsp;|&nbsp; <b style="color:#b0862f;">Holdout:</b> '
                        + r.holdoutFrom + ' → ' + r.holdoutTo
                        + ' <i>(partially spent — see contamination disclosure)</i>'
                        : ' &nbsp;|&nbsp; <i>no holdout window</i>')
                    + ' &nbsp;|&nbsp; <b>seed</b> ' + r.bootstrapSeed + '</div>';
            }
            if ((r.missingSymbols || []).length) {
                html += '<div class="swing-warn">No price history (excluded): ' + r.missingSymbols.join(', ')
                    + ' — run Backfill.</div>';
            }
            if ((r.gaps || []).length) {
                html += '<div class="muted" style="font-size:0.78rem;">Forward-fill gaps: '
                    + r.gaps.map(function (g) { return g.symbol + ' ' + g.from + '→' + g.to + ' (' + g.sessions + 'd)'; }).join(' · ')
                    + '</div>';
            }
            html += '<div class="muted" style="font-size:0.75rem; margin-top:0.2rem;">'
                + (r.symbolSpans || []).map(function (s) {
                    return s.symbol + (s.ranked ? '' : ' (bench)') + ' ' + s.from + '→' + s.to + ' (' + s.bars + ')';
                }).join(' · ') + '</div>';
            document.getElementById('ms-header').innerHTML = html;
            document.getElementById('ms-warnings').innerHTML = (r.warnings || []).map(function (w) {
                return '<div class="swing-warn">' + escapeHtml(w) + '</div>';
            }).join('');
        }

        function renderMosigPredictions() {
            var groups = msReport.groups || [];
            var head = ['Prediction'].concat(groups.map(function (g) { return 'N=' + g.n; }));
            var rows = (msReport.predictions || []).map(function (p) {
                var cells = [{ t: escapeHtml(p.text), lbl: true }];
                (p.marks || []).forEach(function (m) {
                    cells.push({
                        t: m.pass ? '✓ PASS' : '✗ FAIL',
                        cls: m.pass ? 'swing-pos' : 'swing-neg',
                        title: (m.detail || '').replace(/"/g, '&quot;')
                    });
                });
                return { cells: cells };
            });
            document.getElementById('ms-pred').innerHTML = swingTable(head, rows);
        }

        function renderMosigHeadToHead() {
            var r = msReport;
            var html = '';
            (r.groups || []).forEach(function (g) {
                html += '<h3 class="swing-h">Head-to-head · Top ' + g.n
                    + ' <span class="muted swing-sub">fit ' + r.fitFrom + '→' + r.fitTo
                    + (r.holdoutAvailable ? ' · holdout ' + r.holdoutFrom + '→' + r.holdoutTo : '')
                    + ' · challengers judged only by the pre-registered rule</span></h3>';
                var head = ['Variant', 'CAGR', 'Vol', 'Sharpe', 'MaxDD', 'Calmar', 'Turnover',
                    'Cost drag', 'Boot pct', 'HO CAGR', 'HO Sharpe', 'HO MaxDD', 'HO Calmar',
                    'HO turn', '2026 YTD'];
                var rows = (g.variants || []).map(function (v) { return mosigH2hRow(v); });
                (r.benchmarks || []).forEach(function (b) {
                    rows.push(mosigBenchRow(b));
                });
                html += '<div class="full-bleed"><table class="swing-tbl">'
                    + swingTable(head, rows) + '</table></div>';
                (g.variants || []).forEach(function (v) {
                    if (!v.verdict) return;
                    var txt = escapeHtml(v.verdict)
                        .replace(/^ADOPT/, '<span style="color:#2ca02c;font-weight:bold;">ADOPT</span>')
                        .replace(/^REJECT/, '<span style="font-weight:bold;">REJECT</span>')
                        .replace(/POST-HOC/, '<span style="color:#b0862f;font-weight:bold;">POST-HOC</span>');
                    html += '<div class="mo-verdict">• <b>' + escapeHtml(v.label) + '</b> — ' + txt + '</div>';
                });
            });
            document.getElementById('ms-h2h-wrap').innerHTML = html;
        }

        function mosigH2hRow(v) {
            var f = v.fit, h = v.holdout;
            var boot = v.bootstrap;
            var bootCell = { t: '—' };
            if (boot) {
                bootCell = {
                    t: Math.round(boot.cagrPercentile * 100) + 'th',
                    cls: boot.cagrInsideIqr ? 'mo-dim' : (boot.cagrPercentile > 0.75 ? 'swing-pos' : '')
                };
            }
            var ddTitle = f.maxDrawdownPeak ? f.maxDrawdownPeak + ' → ' + f.maxDrawdownTrough : '';
            return {
                cls: v.key === 'V1' ? 'swing-baseline' : '',
                cells: [
                    { t: escapeHtml(v.label), lbl: true, title: (v.description || '').replace(/"/g, '&quot;') },
                    { t: fmtPct(f.cagr), cls: moCls(f.cagr) },
                    { t: fmtPct(f.annVol) },
                    { t: moNum(f.sharpe) },
                    { t: fmtPct(f.maxDrawdown), cls: 'swing-neg', title: ddTitle },
                    { t: moNum(f.calmar) },
                    { t: fmtPct(v.fitTurnover) },
                    { t: v.costDragCagrPct == null ? '—' : moNum(v.costDragCagrPct, 2) + ' pp' },
                    bootCell,
                    { t: h ? fmtPct(h.cagr) : '—', cls: h ? moCls(h.cagr) : '' },
                    { t: h ? moNum(h.sharpe) : '—' },
                    { t: h ? fmtPct(h.maxDrawdown) : '—', cls: h ? 'swing-neg' : '' },
                    { t: h ? moNum(h.calmar) : '—' },
                    { t: h ? fmtPct(v.holdoutTurnover) : '—' },
                    { t: v.ytd2026 == null ? '—' : fmtPct(v.ytd2026), cls: moCls(v.ytd2026) }
                ]
            };
        }

        function mosigBenchRow(b) {
            var f = b.fit, h = b.holdout;
            return {
                cls: 'swing-baseline',
                cells: [
                    { t: b.symbol + ' (B&H)', lbl: true },
                    { t: fmtPct(f.cagr), cls: moCls(f.cagr) },
                    { t: fmtPct(f.annVol) },
                    { t: moNum(f.sharpe) },
                    { t: fmtPct(f.maxDrawdown), cls: 'swing-neg' },
                    { t: moNum(f.calmar) },
                    { t: '—' }, { t: '—' }, { t: '—' },
                    { t: h ? fmtPct(h.cagr) : '—', cls: h ? moCls(h.cagr) : '' },
                    { t: h ? moNum(h.sharpe) : '—' },
                    { t: h ? fmtPct(h.maxDrawdown) : '—', cls: h ? 'swing-neg' : '' },
                    { t: h ? moNum(h.calmar) : '—' },
                    { t: '—' }, { t: '—' }
                ]
            };
        }

        function renderMosigEpisodes() {
            var r = msReport;
            document.getElementById('ms-episodes-sub').textContent =
                'NON-DECISIONAL — zero weight in the verdict · shown at N=' + r.episodeN;
            var head = ['Episode', 'Variant', 'Window ret', 'GLD', 'Holdings (positioning + each in-window rebalance)'];
            var lastEp = null;
            var rows = (r.episodes || []).map(function (e) {
                var epCell = e.episode === lastEp ? '' : escapeHtml(e.episode);
                lastEp = e.episode;
                return {
                    cells: [
                        { t: epCell, lbl: true },
                        { t: e.variantKey, lbl: true },
                        { t: e.windowReturn == null ? '—' : fmtPct(e.windowReturn), cls: moCls(e.windowReturn) },
                        { t: (e.gldNotes || []).map(escapeHtml).join('; ') || '—', lbl: true },
                        { t: '<span style="font-size:0.72rem;">'
                            + (e.holdings || []).map(escapeHtml).join('<br>') + '</span>' }
                    ]
                };
            });
            document.getElementById('ms-episodes').innerHTML = swingTable(head, rows);
        }

        function populateMosigSelects() {
            var groups = msReport.groups || [];
            document.getElementById('ms-diag-n').innerHTML = groups.map(function (g, gi) {
                return '<option value="' + gi + '">Top ' + g.n + '</option>';
            }).join('');
            var opts = '';
            groups.forEach(function (g, gi) {
                (g.variants || []).forEach(function (v, vi) {
                    opts += '<option value="' + gi + ':' + vi + '">Top ' + g.n + ' · ' + v.key + '</option>';
                });
            });
            document.getElementById('ms-grid-variant').innerHTML = opts;
            document.getElementById('ms-log-variant').innerHTML = opts;
        }

        function mosigSelectedVariant(selectId) {
            var val = document.getElementById(selectId).value || '0:0';
            var parts = val.split(':');
            var g = (msReport.groups || [])[+parts[0]];
            return g ? (g.variants || [])[+parts[1]] : null;
        }

        function renderMosigDiag() {
            var gi = +document.getElementById('ms-diag-n').value || 0;
            var g = (msReport.groups || [])[gi];
            if (!g) return;
            var v4 = null, v5 = null;
            (g.variants || []).forEach(function (v) {
                if (v.key === 'V4') v4 = v;
                if (v.key === 'V5') v5 = v;
            });

            var gateHtml = '<b>V4 gate binding</b> <span class="muted">(slot-months the rf gate kept in cash)</span>: ';
            if (v4 && v4.gateBindsByYear && Object.keys(v4.gateBindsByYear).length) {
                gateHtml += Object.keys(v4.gateBindsByYear).sort().map(function (y) {
                    return y + ': <b>' + v4.gateBindsByYear[y] + '</b>';
                }).join(' · ') + ' &nbsp;→&nbsp; total <b>' + v4.gateBindsTotal + '</b> slot-months';
            } else {
                gateHtml += 'never bound';
            }
            document.getElementById('ms-gate').innerHTML = gateHtml;

            var stopHtml = '<b>V5 stops per year</b>: ';
            if (v5 && v5.stopsByYear && Object.keys(v5.stopsByYear).length) {
                stopHtml += Object.keys(v5.stopsByYear).sort().map(function (y) {
                    return y + ': <b>' + v5.stopsByYear[y] + '</b>';
                }).join(' · ') + ' &nbsp;→&nbsp; total <b>' + (v5.stops || []).length
                    + '</b> stops, <b>' + (v5.whipsaws || []).length + '</b> whipsaws';
            } else {
                stopHtml += 'none fired';
            }
            document.getElementById('ms-stopyears').innerHTML = stopHtml;

            var head = ['Stop date', 'Symbol', 'Stop DD', 'Re-entered', 'Txn cost', 'TR gap stop→re-entry'];
            var rows = (v5 ? v5.whipsaws || [] : []).map(function (w) {
                return {
                    cells: [
                        { t: w.stopDate, lbl: true },
                        { t: w.symbol, lbl: true },
                        { t: fmtPct(w.stopDrawdownPct), cls: 'swing-neg' },
                        { t: w.reentryDate },
                        { t: moNum(w.txnCostBps, 1) + ' bps' },
                        { t: w.trGapPct == null ? '—' : fmtPct(w.trGapPct),
                          cls: w.trGapPct > 0 ? 'swing-neg' : 'swing-pos',
                          title: 'positive = bought back higher than the stop close (whipsaw cost)' }
                    ]
                };
            });
            document.getElementById('ms-whipsaw').innerHTML = rows.length
                ? swingTable(head, rows)
                : '<tr><td class="lbl muted">no whipsaws — no stop was followed by re-entry within 2 rebalances</td></tr>';
        }

        function renderMosigGrid() {
            var v = mosigSelectedVariant('ms-grid-variant');
            if (!v) return;
            var months = {};
            Object.assign(months, v.fit.months);
            var holdoutKeys = {};
            if (v.holdout && v.holdout.months) {
                Object.keys(v.holdout.months).forEach(function (k) {
                    months[k] = v.holdout.months[k];
                    holdoutKeys[k] = true;
                });
            }
            var keys = Object.keys(months).sort();
            if (!keys.length) { document.getElementById('ms-grid').innerHTML = ''; return; }
            var years = [];
            var byYear = {};
            keys.forEach(function (k) {
                var y = k.slice(0, 4), m = +k.slice(5, 7);
                if (!byYear[y]) { byYear[y] = {}; years.push(y); }
                byYear[y][m] = { v: months[k], holdout: holdoutKeys[k] };
            });
            var mn = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
            var html = '<tr><th class="lbl">Year</th>';
            mn.forEach(function (m) { html += '<th>' + m + '</th>'; });
            html += '<th>Year</th></tr>';
            years.forEach(function (y) {
                html += '<tr><td class="lbl">' + y + '</td>';
                var yf = 1, any = false;
                for (var m = 1; m <= 12; m++) {
                    var cell = byYear[y][m];
                    if (!cell) { html += '<td></td>'; continue; }
                    any = true;
                    yf *= 1 + cell.v;
                    html += '<td style="' + moGridStyle(cell.v, cell.holdout) + '" title="' + y + '-'
                        + ('0' + m).slice(-2) + (cell.holdout ? ' (holdout)' : '') + '">'
                        + (cell.v * 100).toFixed(1) + '</td>';
                }
                html += '<td class="' + moCls(yf - 1) + '"><b>' + (any ? (yf - 1) * 100 : 0).toFixed(1) + '</b></td></tr>';
            });
            document.getElementById('ms-grid').innerHTML = html;
        }

        function renderMosigLog() {
            var v = mosigSelectedVariant('ms-log-variant');
            if (!v) return;
            var head = ['Date', 'Sec', 'Elig', 'In', 'Out', 'Holdings (rank:score)', 'Turnover', 'Cost', 'Cash'];
            var rows = (v.log || []).map(function (e) {
                return {
                    cls: e.section === 'holdout' ? 'swing-baseline' : '',
                    cells: [
                        { t: e.date, lbl: true },
                        { t: e.section },
                        { t: e.eligible },
                        { t: (e.in || []).join(' ') || '—', lbl: true },
                        { t: (e.out || []).join(' ') || '—', lbl: true },
                        { t: (e.heldRanks || []).join(' '), lbl: true },
                        { t: fmtPct(e.turnover) },
                        { t: fmtPct(e.cost) },
                        { t: fmtPct(e.cashWeight) }
                    ]
                };
            });
            document.getElementById('ms-log').innerHTML = swingTable(head, rows);
        }

        function downloadMosigCsv() {
            var v = mosigSelectedVariant('ms-log-variant');
            if (!v) return;
            var lines = ['date,section,eligible,in,out,holdings,turnover,cost,cash'];
            (v.log || []).forEach(function (e) {
                lines.push([e.date, e.section, e.eligible,
                    '"' + (e.in || []).join(' ') + '"', '"' + (e.out || []).join(' ') + '"',
                    '"' + (e.heldRanks || []).join(' ') + '"',
                    e.turnover, e.cost, e.cashWeight].join(','));
            });
            downloadCsv('momentum-signals-' + v.key + '.csv', lines.join('\n'));
        }

        function downloadMosigStopsCsv() {
            var gi = +document.getElementById('ms-diag-n').value || 0;
            var g = (msReport.groups || [])[gi];
            if (!g) return;
            var v5 = null;
            (g.variants || []).forEach(function (v) { if (v.key === 'V5') v5 = v; });
            if (!v5) return;
            var lines = ['date,section,symbol,weightSold,cost,drawdownPct'];
            (v5.stops || []).forEach(function (e) {
                lines.push([e.date, e.section, e.symbol, e.weightSold, e.cost, e.drawdownPct].join(','));
            });
            downloadCsv('momentum-signals-stops-top' + g.n + '.csv', lines.join('\n'));
        }

        /* ==================== Range floor tab ==================== */

        var rgInit = false, rgReport = null, rgFeaturedChart = null;

        function setupRange() {
            if (rgInit) return;
            rgInit = true;
            document.getElementById('rg-run').addEventListener('click', runRange);
            document.getElementById('rg-backfill').addEventListener('click', backfillRange);
            document.getElementById('rg-log-symbol').addEventListener('change', renderRangeLog);
            document.getElementById('rg-log-csv').addEventListener('click', function (e) {
                e.preventDefault();
                downloadRangeCsv();
            });
        }

        function rgVal(id) { return (document.getElementById(id).value || '').trim(); }
        function rgStatus(msg) { document.getElementById('rg-status').textContent = msg || ''; }
        function rgPct(v, dp) {
            if (v === null || v === undefined || !isFinite(v)) return '—';
            return (v * 100).toFixed(dp === undefined ? 1 : dp) + '%';
        }
        function rgNum(v, dp) {
            if (v === null || v === undefined || !isFinite(v)) return '—';
            return (+v).toFixed(dp === undefined ? 2 : dp);
        }
        function rgMoney(v) {
            if (v === null || v === undefined || !isFinite(v)) return '—';
            return '£' + Math.round(v).toLocaleString();
        }

        function buildRangeQuery() {
            var map = {
                familyA: rgVal('rg-familya'), familyB: rgVal('rg-familyb'),
                entryPct: rgVal('rg-entrypct'), tp: rgVal('rg-tp'), sl: rgVal('rg-sl'),
                exitPct: rgVal('rg-exitpct'), window: rgVal('rg-window'),
                aboveRun: rgVal('rg-aboverun'), rf: rgVal('rg-rf'),
                notional: rgVal('rg-notional'), commissionBps: rgVal('rg-cost'),
                trailingMonths: rgVal('rg-trailing'), dedupDays: rgVal('rg-dedup'),
                bootstrap: rgVal('rg-bootstrap'), seed: rgVal('rg-seed')
            };
            var parts = [];
            Object.keys(map).forEach(function (k) {
                if (map[k] !== '') parts.push(k + '=' + encodeURIComponent(map[k]));
            });
            return parts.join('&');
        }

        function runRange() {
            rgStatus('Computing (bootstraps per scope + grid cell)…');
            fetch('/range?' + buildRangeQuery())
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (rep) {
                    rgReport = rep;
                    document.getElementById('rg-empty').style.display = 'none';
                    renderRangePrereg();
                    renderRangeHeader();
                    renderRangeData();
                    renderRangeArms();
                    renderRangeTrades();
                    renderRangeGrid();
                    renderRangeFeatured();
                    populateRangeLogSelect();
                    renderRangeLog();
                    ['rg-header-wrap', 'rg-data-wrap', 'rg-arms-wrap', 'rg-trades-wrap',
                        'rg-grid-wrap', 'rg-featured-wrap', 'rg-log-wrap']
                        .forEach(function (id) { document.getElementById(id).style.display = 'block'; });
                    rgStatus('Updated ' + new Date().toLocaleTimeString()
                        + ' · seed ' + rep.config.randomSeed);
                })
                .catch(function (e) { rgStatus('Error: ' + e.message); });
        }

        function backfillRange() {
            rgStatus('Backfill started (daily history per missing symbol)…');
            fetch('/range/backfill', { method: 'POST' })
                .then(function (r) { return r.json(); })
                .then(function () { rgStatus('Backfill running. Re-run once prices land.'); })
                .catch(function (e) { rgStatus('Backfill failed: ' + e.message); });
        }

        function renderRangePrereg() {
            var el = document.getElementById('rg-prereg');
            el.innerHTML = '<b>Pre-registration</b><br>'
                + (rgReport.preRegistration || []).map(function (l) { return escapeHtml(l); }).join('<br>');
        }

        function renderRangeHeader() {
            var banner = document.getElementById('rg-exploratory');
            if (rgReport.exploratory) {
                banner.textContent = 'EXPLORATORY RUN — parameters differ from the pre-registered '
                    + 'defaults: ' + rgReport.exploratoryChanges.join(', ')
                    + '. Conclusions from this run are not pre-registered.';
                banner.style.display = 'block';
            } else {
                banner.style.display = 'none';
            }
            var w = document.getElementById('rg-warnings');
            w.innerHTML = (rgReport.warnings || []).map(function (m) {
                return '<div class="swing-warn">' + escapeHtml(m) + '</div>';
            }).join('');
            var v = document.getElementById('rg-verdicts');
            v.innerHTML = (rgReport.verdicts || []).map(function (m) {
                var bad = m.indexOf('added nothing') >= 0;
                return '<div class="swing-span" style="border-left:3px solid '
                    + (bad ? '#c0392b' : '#2ca02c') + ';">' + escapeHtml(m) + '</div>';
            }).join('');
            document.getElementById('rg-fxnote').textContent = rgReport.fxNote || '';
        }

        function renderRangeData() {
            var rows = (rgReport.data || []).map(function (d) {
                var flags = [];
                if (d.missing) flags.push('NO DATA');
                if (d.shortHistory) flags.push('< 10y');
                (d.dataErrors || []).forEach(function (e) {
                    flags.push(e.date + ' ' + rgPct(e.movePct));
                });
                return { cells: [
                    { t: d.symbol, lbl: true }, { t: d.family }, { t: d.currency || '—' },
                    { t: d.from || '—' }, { t: d.to || '—' },
                    { t: d.years == null ? '—' : d.years.toFixed(1) }, { t: d.bars },
                    { t: flags.length ? flags.join('; ') : 'ok',
                      cls: flags.length ? 'swing-warn' : '' }
                ] };
            });
            document.getElementById('rg-data').innerHTML = swingTable(
                ['Symbol', 'Fam', 'Ccy', 'From', 'To', 'Years', 'Bars', 'Flags'], rows);
        }

        function rgArmCells(a) {
            if (!a) return [{ t: '—' }, { t: '—' }, { t: '—' }];
            return [
                { t: rgPct(a.returnOnAllocatedAnn), cls: moCls(a.returnOnAllocatedAnn) },
                { t: rgPct(a.returnOnDeployed), cls: moCls(a.returnOnDeployed) },
                { t: a.purchases + (a.openPositions ? ' (' + a.openPositions + ' open)' : '') }
            ];
        }

        function renderRangeArms() {
            var rows = [];
            (rgReport.scopes || []).forEach(function (sc) {
                ['full', 'trailing'].forEach(function (wk) {
                    var w = sc[wk];
                    if (!w) return;
                    var dim = w.thin ? 'swing-smalln-cell' : '';
                    var c = w.armC;
                    var pctlA = c ? c.actualPctlAllocatedAnn : null;
                    var pctlD = c ? c.actualPctlDeployed : null;
                    var cells = [
                        { t: escapeHtml(sc.scope), lbl: true },
                        { t: w.label }, { t: sc.currencyBasis },
                        { t: w.episodes + (w.thin ? ' ⚠' : '') }
                    ]
                        .concat(rgArmCells(w.armA))
                        .concat(rgArmCells(w.armB))
                        .concat([
                            { t: c && c.roAllocatedAnn ? rgPct(c.roAllocatedAnn.median) : '—' },
                            { t: pctlA == null ? '—' : rgNum(pctlA, 0),
                              cls: pctlA != null && pctlA >= 25 && pctlA <= 75 ? 'swing-smalln-cell' : '' },
                            { t: c && c.roDeployed ? rgPct(c.roDeployed.median) : '—' },
                            { t: pctlD == null ? '—' : rgNum(pctlD, 0),
                              cls: pctlD != null && pctlD >= 25 && pctlD <= 75 ? 'swing-smalln-cell' : '' }
                        ])
                        .concat(w.armD ? [{ t: rgPct(w.armD.returnOnAllocatedAnn),
                            cls: moCls(w.armD.returnOnAllocatedAnn) }] : [{ t: '—' }])
                        .concat([
                            { t: w.armA ? rgMoney(w.armA.stampPaid) : '—' },
                            { t: w.armA ? rgMoney(w.armA.commissionPaid) : '—' },
                            { t: w.armsNote ? escapeHtml(w.armsNote) : '' }
                        ]);
                    rows.push({ cells: cells, cls: dim });
                });
            });
            document.getElementById('rg-arms').innerHTML = swingTable(
                ['Scope', 'Window', 'Ccy', 'Episodes',
                 'A RoA/yr', 'A RoD', 'A buys',
                 'B RoA/yr', 'B RoD', 'B buys',
                 'C med RoA/yr', 'A pctl', 'C med RoD', 'A pctl',
                 'D RoA/yr', 'Stamp', 'Comm', 'Note'], rows);
        }

        function rgStatsCells(st, label) {
            if (!st) return null;
            return [
                { t: label }, { t: st.events }, { t: st.tp }, { t: st.pctl }, { t: st.sl },
                { t: st.ambiguous }, { t: st.open },
                { t: rgPct(st.meanTrRet), cls: moCls(st.meanTrRet) },
                { t: rgPct(st.meanTrRetConservative), cls: moCls(st.meanTrRetConservative) },
                { t: st.maeDist ? rgPct(st.maeDist.median) : '—' },
                { t: st.maeDist ? rgPct(st.maeDist.min) : '—', cls: 'swing-neg' },
                { t: st.underwaterSessionsDist ? rgNum(st.underwaterSessionsDist.median, 0) : '—' },
                { t: st.daysHeldDist ? rgNum(st.daysHeldDist.median, 0) : '—' }
            ];
        }

        function renderRangeTrades() {
            var rows = [];
            (rgReport.scopes || []).forEach(function (sc) {
                ['full', 'trailing'].forEach(function (wk) {
                    var w = sc[wk];
                    if (!w || !w.accumulation || !w.accumulation.events) return;
                    var dim = w.thin ? 'swing-smalln-cell' : '';
                    [['accumulation', w.accumulation],
                     ['acc, P75 exit', w.pctlExitVariant],
                     ['trading (SL)', w.trading]].forEach(function (pair, i) {
                        var cells = rgStatsCells(pair[1], pair[0]);
                        if (!cells) return;
                        rows.push({ cls: dim, cells: [
                            { t: i === 0 ? escapeHtml(sc.scope) : '', lbl: true },
                            { t: i === 0 ? w.label : '' }
                        ].concat(cells) });
                    });
                });
            });
            document.getElementById('rg-trades').innerHTML = swingTable(
                ['Scope', 'Window', 'Mode', 'Events', 'TP', 'Pctl', 'SL', 'Amb', 'Open',
                 'Mean TR ret', 'Cons', 'MAE med', 'MAE worst', 'UW sess med', 'Days med'], rows);
        }

        function renderRangeGrid() {
            var cells = rgReport.sensitivity || [];
            var host = document.getElementById('rg-grid');
            if (!cells.length) {
                host.innerHTML = '<p class="muted">Grid unavailable this run.</p>';
                return;
            }
            var scopes = [], modes = [];
            cells.forEach(function (c) {
                if (scopes.indexOf(c.scope) < 0) scopes.push(c.scope);
                if (modes.indexOf(c.mode) < 0) modes.push(c.mode);
            });
            var html = '';
            scopes.forEach(function (scope) {
                modes.forEach(function (mode) {
                    var sub = cells.filter(function (c) { return c.scope === scope && c.mode === mode; });
                    if (!sub.length) return;
                    var ps = [], tps = [];
                    sub.forEach(function (c) {
                        if (ps.indexOf(c.entryPct) < 0) ps.push(c.entryPct);
                        if (tps.indexOf(c.tp) < 0) tps.push(c.tp);
                    });
                    ps.sort(function (a, b) { return a - b; });
                    tps.sort(function (a, b) { return a - b; });
                    html += '<h4 class="swing-h" style="margin-top:0.6rem;">' + escapeHtml(scope)
                        + ' — ' + mode + '</h4><table class="swing-tbl"><tr><th class="lbl">Entry \\ TP</th>';
                    tps.forEach(function (tp) { html += '<th>' + rgPct(tp, 0) + '</th>'; });
                    html += '</tr>';
                    ps.forEach(function (p) {
                        html += '<tr><td class="lbl">P' + Math.round(p * 100) + '</td>';
                        tps.forEach(function (tp) {
                            var c = sub.filter(function (x) { return x.entryPct === p && x.tp === tp; })[0];
                            if (!c) { html += '<td>—</td>'; return; }
                            var cls = (c.insideIqr || c.thin) ? 'swing-smalln-cell' : '';
                            var style = c.preRegistered ? ' style="border:2px solid #b0862f;"' : '';
                            var txt = c.pctlDeployed == null ? '—' : rgNum(c.pctlDeployed, 0);
                            html += '<td class="' + cls + '"' + style + ' title="events ' + c.events
                                + ', episodes ' + c.episodes + ', A RoD ' + rgPct(c.armARoD)
                                + ', C med ' + rgPct(c.cMedianRoD) + '">'
                                + txt + '<span class="muted" style="font-size:0.68rem;"> ('
                                + c.episodes + 'ep)</span></td>';
                        });
                        html += '</tr>';
                    });
                    html += '</table>';
                });
            });
            host.innerHTML = html;
        }

        function renderRangeFeatured() {
            var f = rgReport.featured;
            var wrap = document.getElementById('rg-featured-wrap');
            if (!f) { wrap.style.display = 'none'; return; }
            document.getElementById('rg-featured-title').textContent =
                'Featured: ' + f.symbol + ' — entries, underwater stretches, exits'
                + (f.currency ? ' (' + f.currency + ')' : '');
            var labels = f.dates || [];
            var markerData = { entry: [], tp: [], pctl: [], sl: [], open: [], ambiguous: [] };
            Object.keys(markerData).forEach(function (k) {
                markerData[k] = labels.map(function () { return null; });
            });
            (f.markers || []).forEach(function (m) {
                var i = nearestLabelIdx(labels, m.date);
                if (i >= 0 && markerData[m.kind]) markerData[m.kind][i] = m.price;
            });
            var ds = [{
                label: f.symbol, data: f.closes, borderColor: '#1f77b4',
                borderWidth: 1.2, pointRadius: 0, tension: 0, fill: false
            }];
            var markerStyles = {
                entry: ['#2ca02c', 'triangle', 'entry'],
                tp: ['#1f77b4', 'rectRot', 'TP exit'],
                pctl: ['#9467bd', 'rectRot', 'P75 exit'],
                sl: ['#c0392b', 'rectRot', 'stop'],
                open: ['#e8a33d', 'circle', 'still open (censored)'],
                ambiguous: ['#888', 'crossRot', 'ambiguous']
            };
            Object.keys(markerStyles).forEach(function (k) {
                if (!markerData[k].some(function (v) { return v != null; })) return;
                ds.push({
                    label: markerStyles[k][2], data: markerData[k], showLine: false,
                    pointStyle: markerStyles[k][1], pointRadius: 6, pointHoverRadius: 8,
                    borderColor: markerStyles[k][0], backgroundColor: markerStyles[k][0]
                });
            });
            if (rgFeaturedChart) rgFeaturedChart.destroy();
            rgFeaturedChart = new Chart(document.getElementById('rg-featured-chart'), {
                type: 'line',
                data: { labels: labels, datasets: ds },
                options: {
                    animation: false, spanGaps: true,
                    scales: { x: { ticks: { maxTicksLimit: 14 } } },
                    plugins: { legend: { position: 'bottom' } }
                }
            });

            var trades = (rgReport.events || []).filter(function (e) {
                return e.symbol === f.symbol && e.mode === 'accumulation';
            });
            var rows = trades.map(function (t) {
                return { cells: [
                    { t: t.entryDate, lbl: true }, { t: rgNum(t.fill) },
                    { t: 'P' + (t.pctlRank == null ? '—' : Math.round(t.pctlRank * 100)) },
                    { t: t.exitType, cls: t.exitType === 'OPEN' ? 'swing-warn' : '' },
                    { t: t.exitDate }, { t: t.exitFill == null ? '—' : rgNum(t.exitFill) },
                    { t: rgPct(t.trRet), cls: moCls(t.trRet) },
                    { t: rgPct(t.mae), cls: 'swing-neg' },
                    { t: t.underwaterSessions }, { t: t.daysHeld }, { t: rgMoney(t.costsPaidGbp) }
                ] };
            });
            document.getElementById('rg-featured-trades').innerHTML = swingTable(
                ['Entry', 'Fill', 'Rank', 'Exit', 'Exit date', 'Exit fill', 'TR ret',
                 'MAE', 'UW sess', 'Days', 'Costs'], rows);
        }

        function nearestLabelIdx(labels, date) {
            for (var i = 0; i < labels.length; i++) {
                if (labels[i] >= date) return i;
            }
            return labels.length ? labels.length - 1 : -1;
        }

        function populateRangeLogSelect() {
            var sel = document.getElementById('rg-log-symbol');
            var symbols = [];
            (rgReport.events || []).forEach(function (e) {
                if (symbols.indexOf(e.symbol) < 0) symbols.push(e.symbol);
            });
            sel.innerHTML = '<option value="">All symbols</option>'
                + symbols.map(function (s) { return '<option>' + s + '</option>'; }).join('');
        }

        function rangeLogRows() {
            var sym = document.getElementById('rg-log-symbol').value;
            return (rgReport.events || []).filter(function (e) {
                return !sym || e.symbol === sym;
            });
        }

        function renderRangeLog() {
            var rows = rangeLogRows().map(function (e) {
                return { cells: [
                    { t: e.symbol, lbl: true }, { t: e.mode }, { t: e.entryDate },
                    { t: rgNum(e.level) },
                    { t: e.pctlRank == null ? '—' : Math.round(e.pctlRank * 100) },
                    { t: rgNum(e.fill) },
                    { t: e.exitType, cls: e.exitType === 'OPEN' ? 'swing-warn' : '' },
                    { t: e.exitDate }, { t: e.exitFill == null ? '—' : rgNum(e.exitFill) },
                    { t: rgPct(e.trRet), cls: moCls(e.trRet) },
                    { t: rgPct(e.mae), cls: 'swing-neg' },
                    { t: e.underwaterSessions }, { t: e.daysHeld }, { t: rgMoney(e.costsPaidGbp) }
                ] };
            });
            document.getElementById('rg-log').innerHTML = swingTable(
                ['Symbol', 'Mode', 'Entry', 'Level', 'Rank', 'Fill', 'Exit', 'Exit date',
                 'Exit fill', 'TR ret', 'MAE', 'UW sess', 'Days', 'Costs £'], rows);
        }

        function downloadRangeCsv() {
            var lines = ['symbol,mode,entry_date,level,pctl_rank,fill,exit_type,exit_date,'
                + 'exit_fill,price_ret,tr_ret,mae,underwater_sessions,days_held,costs_gbp'];
            rangeLogRows().forEach(function (e) {
                lines.push([e.symbol, e.mode, e.entryDate, e.level, e.pctlRank, e.fill,
                    e.exitType, e.exitDate, e.exitFill, e.priceRet, e.trRet, e.mae,
                    e.underwaterSessions, e.daysHeld, e.costsPaidGbp].map(function (v) {
                    return v == null ? '' : v;
                }).join(','));
            });
            downloadCsv('range-events.csv', lines.join('\n'));
        }

        // Small table builder shared by the momentum renderers.
        function swingTable(head, rows) {
            var html = '<tr>';
            head.forEach(function (h, i) { html += '<th' + (i === 0 ? ' class="lbl"' : '') + '>' + h + '</th>'; });
            html += '</tr>';
            rows.forEach(function (row) {
                if (!row) return;
                html += '<tr' + (row.cls ? ' class="' + row.cls + '"' : '') + '>';
                row.cells.forEach(function (c) {
                    var cls = (c.lbl ? 'lbl ' : '') + (c.cls || '');
                    html += '<td' + (cls.trim() ? ' class="' + cls.trim() + '"' : '')
                        + (c.title ? ' title="' + c.title + '"' : '') + '>' + c.t + '</td>';
                });
                html += '</tr>';
            });
            return html;
        }

        // ---- Earnings calendar tab ----------------------------------------
        // ---------------------------------------------------------------- Manual positions
        // Accounts with no downloadable export. Every write goes through the same POST helper and
        // then reloads the whole view, so the table can never show a position the ledger doesn't
        // hold — the server is the only thing that decides what is stored.
        var mpInit = false, mpBrokers = [];

        function setupManualPositions() {
            if (mpInit) return;
            mpInit = true;
            var today = new Date().toISOString().slice(0, 10);
            ['mp-buy-date', 'mp-sell-date', 'mp-wd-date'].forEach(function (id) {
                var el = document.getElementById(id);
                if (el && !el.value) el.value = today;
            });
            bindOnce(document.getElementById('mp-buy-save'), 'click', function () {
                mpSubmit('/positions/manual/buy', 'mp-buy-status', {
                    broker: mpVal('mp-buy-broker'), symbol: mpVal('mp-buy-symbol'),
                    quantity: mpVal('mp-buy-qty'), cost: mpVal('mp-buy-cost'),
                    date: mpVal('mp-buy-date'), note: mpVal('mp-buy-note')
                }, ['mp-buy-symbol', 'mp-buy-qty', 'mp-buy-cost', 'mp-buy-note']);
            });
            bindOnce(document.getElementById('mp-sell-save'), 'click', function () {
                mpSubmit('/positions/manual/sell', 'mp-sell-status', {
                    broker: mpVal('mp-sell-broker'), symbol: mpVal('mp-sell-symbol'),
                    quantity: mpVal('mp-sell-qty'), proceeds: mpVal('mp-sell-proceeds'),
                    date: mpVal('mp-sell-date'), note: mpVal('mp-sell-note')
                }, ['mp-sell-symbol', 'mp-sell-qty', 'mp-sell-proceeds', 'mp-sell-note']);
            });
            bindOnce(document.getElementById('mp-wd-save'), 'click', function () {
                mpSubmit('/positions/manual/withdraw', 'mp-wd-status', {
                    broker: mpVal('mp-wd-broker'), amount: mpVal('mp-wd-amount'),
                    date: mpVal('mp-wd-date'), note: mpVal('mp-wd-note')
                }, ['mp-wd-amount', 'mp-wd-note']);
            });
            // Delegated: the rows table is replaced on every reload.
            bindOnce(document.getElementById('mp-rows'), 'click', function (ev) {
                var btn = ev.target.closest('button[data-rowid]');
                if (!btn) return;
                if (!window.confirm('Delete this row? The account\'s running balance is recomputed.')) return;
                mpPost('/positions/manual/delete',
                    {broker: btn.getAttribute('data-broker'), rowid: btn.getAttribute('data-rowid')})
                    .then(loadManualPositions)
                    .catch(function (e) { window.alert(e.message); });
            });
            loadManualPositions();
        }

        function mpVal(id) {
            var el = document.getElementById(id);
            return el ? (el.value || '').trim() : '';
        }

        function mpPost(url, params) {
            var body = new URLSearchParams();
            Object.keys(params).forEach(function (k) {
                if (params[k] !== null && params[k] !== undefined && params[k] !== '') body.append(k, params[k]);
            });
            return fetch(url, {method: 'POST', body: body}).then(function (r) {
                if (!r.ok) {
                    return r.text().then(function (t) {
                        throw new Error((t || '').slice(0, 300) || ('HTTP ' + r.status));
                    });
                }
                return r.json();
            });
        }

        function mpSubmit(url, statusId, params, clearIds) {
            var status = document.getElementById(statusId);
            if (status) { status.textContent = 'Saving…'; status.style.color = ''; }
            mpPost(url, params).then(function () {
                if (status) { status.textContent = 'Saved.'; status.style.color = ''; }
                clearIds.forEach(function (id) {
                    var el = document.getElementById(id);
                    if (el) el.value = '';
                });
                return loadManualPositions();
            }).catch(function (e) {
                if (status) { status.textContent = e.message; status.style.color = '#c0392b'; }
            });
        }

        function loadManualPositions() {
            clearPanelError('panel-manual');
            return fetch('/positions/manual')
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(renderManualPositions)
                .catch(function (err) {
                    // The latch stays set on purpose — the retry button re-runs the fetch, and
                    // re-running setup would stack a second set of handlers on the forms.
                    panelLoadFailed('panel-manual', err, loadManualPositions);
                });
        }

        function renderManualPositions(view) {
            mpBrokers = view.brokers || [];
            ['mp-buy-broker', 'mp-sell-broker', 'mp-wd-broker'].forEach(function (id) {
                var sel = document.getElementById(id);
                if (!sel) return;
                var keep = sel.value;
                sel.innerHTML = mpBrokers.map(function (b) {
                    return '<option value="' + escapeHtml(b) + '">' + escapeHtml(b) + '</option>';
                }).join('');
                if (keep && mpBrokers.indexOf(keep) >= 0) sel.value = keep;
            });

            var warn = document.getElementById('mp-warnings');
            var warnings = view.warnings || [];
            warn.innerHTML = warnings.length
                ? '<div class="value-warnings pending"><ul style="margin:0;padding-left:1.1rem">'
                    + warnings.map(function (w) { return '<li>' + escapeHtml(w) + '</li>'; }).join('')
                    + '</ul></div>'
                : '';

            var positions = view.positions || [];
            var box = document.getElementById('mp-positions');
            if (!positions.length) {
                box.innerHTML = '<p class="muted">Nothing entered yet.</p>';
            } else {
                var rows = positions.map(function (p) {
                    return '<tr>'
                        + '<td class="txt">' + escapeHtml(p.symbol) + '</td>'
                        + '<td class="txt">' + escapeHtml(p.broker) + '</td>'
                        + '<td>' + mpNum(p.quantity, 0) + '</td>'
                        + '<td>' + mpMoney(p.costGbp) + '</td>'
                        + '<td>' + (p.price == null ? '—' : mpNum(p.price, 4)) + '</td>'
                        + '<td>' + mpMoney(p.marketValueGbp) + '</td>'
                        + '<td class="' + mpSign(p.gainGbp) + '">' + mpMoney(p.gainGbp) + '</td>'
                        + '<td class="' + mpSign(p.gainPct) + '">'
                        + (p.gainPct == null ? '—' : (p.gainPct * 100).toFixed(2) + '%') + '</td>'
                        + '<td class="txt muted">' + escapeHtml(p.priceSource || '—') + '</td>'
                        + '</tr>';
                }).join('');
                box.innerHTML = '<table class="swing-tbl"><thead><tr>'
                    + '<th>Symbol</th><th>Broker</th><th>Shares</th><th>Cost £</th>'
                    + '<th>Price</th><th>Value £</th><th>Gain £</th><th>Gain %</th>'
                    + '<th title="which stored feed priced this row">Priced from</th>'
                    + '</tr></thead><tbody>' + rows + '</tbody></table>';
            }

            var ledger = view.rows || [];
            var rowsBox = document.getElementById('mp-rows');
            if (!ledger.length) {
                rowsBox.innerHTML = '<p class="muted">No stored rows.</p>';
                return;
            }
            rowsBox.innerHTML = '<table class="swing-tbl"><thead><tr>'
                + '<th>Date</th><th>Broker</th><th>Type</th><th>Symbol</th><th>Shares</th>'
                + '<th>Amount £</th><th>Note</th><th></th></tr></thead><tbody>'
                + ledger.map(function (r) {
                    return '<tr>'
                        + '<td class="txt">' + escapeHtml(r.date) + '</td>'
                        + '<td class="txt">' + escapeHtml(r.broker) + '</td>'
                        + '<td class="txt">' + escapeHtml(r.type) + '</td>'
                        + '<td class="txt">' + escapeHtml(r.symbol) + '</td>'
                        + '<td>' + (Number(r.quantity) ? mpNum(r.quantity, 0) : '—') + '</td>'
                        + '<td class="' + mpSign(r.amount) + '">' + mpMoney(r.amount) + '</td>'
                        + '<td class="txt muted">' + escapeHtml(r.description || '') + '</td>'
                        + '<td><button type="button" data-rowid="' + r.rowid + '" data-broker="'
                        + escapeHtml(r.broker) + '">Delete</button></td>'
                        + '</tr>';
                }).join('')
                + '</tbody></table>';
        }

        // ── In-specie transfers ─────────────────────────────────────────────────
        // Reuses the manual-positions helpers (mpVal/mpPost/mpNum/mpMoney) rather than growing a
        // parallel set: both screens are the same shape — a form that writes ledger rows and a
        // table that lists what it wrote.
        var trInit = false, trReport = null;

        function setupTransfers() {
            if (trInit) return;
            trInit = true;
            var d = document.getElementById('tr-date');
            if (d && !d.value) d.value = new Date().toISOString().slice(0, 10);

            // Changing the source re-filters the positions, since only what that account actually
            // holds can move out of it.
            bindOnce(document.getElementById('tr-from'), 'change', renderTransferPositions);
            bindOnce(document.getElementById('tr-symbol'), 'change', renderTransferPreview);
            bindOnce(document.getElementById('tr-to'), 'change', renderTransferPreview);

            bindOnce(document.getElementById('tr-save'), 'click', function () {
                var status = document.getElementById('tr-status');
                var symbol = mpVal('tr-symbol');
                if (!symbol) {
                    status.textContent = 'Nothing to transfer from that account.';
                    status.style.color = '#c0392b';
                    return;
                }
                status.textContent = 'Recording…';
                status.style.color = '';
                mpPost('/transfers/record', {
                    date: mpVal('tr-date'), from: mpVal('tr-from'),
                    to: mpVal('tr-to'), symbol: symbol
                }).then(function (res) {
                    status.textContent = res.message;
                    status.style.color = res.ok ? '#2ca02c' : '#c0392b';
                    if (res.ok) loadTransfers();
                }).catch(function (err) {
                    status.textContent = String(err.message || err);
                    status.style.color = '#c0392b';
                });
            });

            // Delegated: the recorded table is replaced on every reload.
            bindOnce(document.getElementById('tr-recorded'), 'click', function (ev) {
                var btn = ev.target.closest('button[data-out]');
                if (!btn) return;
                if (!window.confirm('Undo this transfer? The position goes back to where it was.')) return;
                mpPost('/transfers/delete', {outRowid: btn.dataset.out, inRowid: btn.dataset.in})
                    .then(loadTransfers)
                    .catch(function (err) { window.alert(String(err.message || err)); });
            });

            loadTransfers();
        }

        function loadTransfers() {
            clearPanelError('panel-transfers');
            return fetch('/transfers')
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(renderTransfers)
                .catch(function (err) {
                    // Latch stays set: the retry re-runs the fetch, and re-running setup would
                    // stack a second set of handlers on the form.
                    panelLoadFailed('panel-transfers', err, loadTransfers);
                });
        }

        function renderTransfers(report) {
            trReport = report;
            var accounts = report.accounts || [];
            ['tr-from', 'tr-to'].forEach(function (id) {
                var sel = document.getElementById(id);
                if (!sel) return;
                var keep = sel.value;
                sel.innerHTML = accounts.map(function (a) {
                    return '<option value="' + escapeHtml(a.dbValue) + '">' + escapeHtml(a.label) + '</option>';
                }).join('');
                if (keep) sel.value = keep;
            });
            // Both selects would otherwise land on the first account and the form would open
            // reading "choose two different accounts" — a complaint about a state the user did
            // not choose. Nudge the destination off the source once, on first paint only.
            var from = document.getElementById('tr-from'), to = document.getElementById('tr-to');
            if (from && to && from.value === to.value) {
                var other = accounts.filter(function (a) { return a.dbValue !== from.value; })[0];
                if (other) to.value = other.dbValue;
            }
            renderTransferPositions();
            renderTransferRecorded();
        }

        function renderTransferPositions() {
            var sel = document.getElementById('tr-symbol');
            if (!sel || !trReport) return;
            var from = mpVal('tr-from');
            var held = (trReport.transferable || []).filter(function (p) {
                return p.accountDbValue === from;
            });
            var keep = sel.value;
            sel.innerHTML = held.length
                ? held.map(function (p) {
                    return '<option value="' + escapeHtml(p.symbol) + '">' + escapeHtml(p.symbol)
                        + ' · ' + p.quantity + ' sh</option>';
                  }).join('')
                : '<option value="">— nothing held here —</option>';
            if (keep && held.some(function (p) { return p.symbol === keep; })) sel.value = keep;
            renderTransferPreview();
        }

        // States exactly what will be written before it is written. The cost figure is the whole
        // reason the screen can be trusted: it is the ledger's own FIFO cost for that position, so
        // the reader can see that the move carries the basis rather than re-pricing it.
        function renderTransferPreview() {
            var box = document.getElementById('tr-preview');
            if (!box || !trReport) return;
            var from = mpVal('tr-from'), to = mpVal('tr-to'), symbol = mpVal('tr-symbol');
            var p = (trReport.transferable || []).find(function (x) {
                return x.accountDbValue === from && x.symbol === symbol;
            });
            if (!p) { box.textContent = ''; return; }
            if (from === to) {
                box.innerHTML = '<span style="color:#c0392b">Choose two different accounts.</span>';
                return;
            }
            var toLabel = (trReport.accounts || []).filter(function (a) { return a.dbValue === to; })
                .map(function (a) { return a.label; })[0] || to;
            box.innerHTML = 'Will move <b>' + escapeHtml(p.quantity) + ' ' + escapeHtml(p.symbol)
                + '</b> from ' + escapeHtml(p.accountLabel) + ' to ' + escapeHtml(toLabel)
                + ', carrying <b>£' + mpMoney(p.costGbp) + '</b> of cost basis. '
                + 'No cash moves and your gain does not change.';
        }

        function renderTransferRecorded() {
            var box = document.getElementById('tr-recorded');
            var list = (trReport && trReport.recorded) || [];
            if (!list.length) {
                box.innerHTML = '<p class="muted">None recorded yet.</p>';
                return;
            }
            var rows = list.map(function (t) {
                // A lone leg means a position left one account and arrived nowhere. Flagged
                // rather than hidden, and not offered an undo, since undoing takes a pair.
                var broken = !t.paired;
                return '<tr' + (broken ? ' class="order-through"' : '') + '>'
                    + '<td class="txt">' + escapeHtml(t.date) + '</td>'
                    + '<td class="txt">' + escapeHtml(t.symbol) + '</td>'
                    + '<td>' + escapeHtml(t.quantity) + '</td>'
                    + '<td class="txt">' + escapeHtml(t.fromLabel || '—') + '</td>'
                    + '<td class="txt">' + escapeHtml(t.toLabel || '—') + '</td>'
                    + '<td>' + mpMoney(t.costGbp) + '</td>'
                    + '<td class="txt">' + (broken
                        ? '<span title="Only one leg is stored — the position left an account and arrived nowhere.">incomplete</span>'
                        : '<button type="button" data-out="' + t.outRowid + '" data-in="' + t.inRowid + '">Undo</button>')
                    + '</td></tr>';
            }).join('');
            box.innerHTML = '<table class="swing-tbl"><thead><tr>'
                + '<th>Date</th><th>Symbol</th><th>Shares</th><th>From</th><th>To</th>'
                + '<th title="cost basis carried with the shares — not cash, not market value">Cost £</th>'
                + '<th></th></tr></thead><tbody>' + rows + '</tbody></table>';
        }

        function mpNum(v, dp) {
            if (v == null) return '—';
            return Number(v).toLocaleString(undefined, {minimumFractionDigits: dp, maximumFractionDigits: dp});
        }

        function mpMoney(v) {
            if (v == null) return '—';
            return Number(v).toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2});
        }

        function mpSign(v) {
            if (v == null || Number(v) === 0) return '';
            return Number(v) > 0 ? 'pos' : 'neg';
        }

        var earnInit = false, earnReport = null;

        function setupEarnings() {
            if (earnInit) return;
            earnInit = true;
            document.getElementById('ea-run').addEventListener('click', function () { runEarnings(false); });
            document.getElementById('ea-refresh').addEventListener('click', function () { runEarnings(true); });
            document.getElementById('ea-mincap').addEventListener('change', function () { runEarnings(false); });
            document.getElementById('ea-mine').addEventListener('change', renderEarnings);
            document.getElementById('ea-holdings-forward')
                .addEventListener('change', function () { runEarnings(false); });
            runEarnings(false); // auto-load on first open
        }

        function eaVal(id) { return (document.getElementById(id).value || '').trim(); }
        function eaStatus(m) { document.getElementById('ea-status').textContent = m || ''; }

        function runEarnings(refresh) {
            eaStatus(refresh ? 'Refreshing from Nasdaq…' : 'Loading…');
            var q = 'back=' + encodeURIComponent(eaVal('ea-back') || '2')
                + '&forward=' + encodeURIComponent(eaVal('ea-forward') || '3')
                + '&holdingsForward=' + encodeURIComponent(eaVal('ea-holdings-forward') || '21');
            var cap = eaVal('ea-mincap');
            if (cap) q += '&minCapB=' + encodeURIComponent(cap);
            if (refresh) q += '&refresh=true';
            fetch('/earnings?' + q)
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (rep) {
                    earnReport = rep;
                    document.getElementById('ea-empty').style.display = 'none';
                    renderEarnings();
                    eaStatus('Updated ' + new Date().toLocaleTimeString());
                })
                .catch(function (e) { eaStatus('Error: ' + e.message); });
        }

        function eaCap(v) {
            if (v == null) return '—';
            var a = Math.abs(v);
            if (a >= 1e12) return '$' + (v / 1e12).toFixed(2) + 'T';
            if (a >= 1e9) return '$' + (v / 1e9).toFixed(1) + 'B';
            if (a >= 1e6) return '$' + (v / 1e6).toFixed(0) + 'M';
            return '$' + v.toLocaleString();
        }
        function eaEps(v) {
            if (v == null) return '—';
            return v < 0 ? '-$' + Math.abs(v).toFixed(2) : '$' + v.toFixed(2);
        }
        function eaTimeBadge(t) {
            var cls = t === 'BMO' ? 'ea-bmo' : (t === 'AMC' ? 'ea-amc' : 'ea-tbd');
            return '<span class="ea-badge ' + cls + '">' + escapeHtml(t || 'TBD') + '</span>';
        }

        /**
         * The holdings section: every position ranked by size, with which source dated it made
         * plain. Two groups share one table, so one weight ranking runs down the whole thing:
         *
         *  - **Reporting** — dated by the market calendar, inside the look-ahead, with a session
         *    and a consensus estimate. These are the prints that land next.
         *  - **Expected** — dated only by the cached fundamentals snapshot. Nearly always past the
         *    look-ahead and carrying no estimate, so the weaker columns stay blank rather than
         *    borrowing the stronger group's geometry to look like something they are not.
         *
         * A UK/EU listing the US-only feed cannot see is still labelled "not covered" rather than
         * silently omitted — but only when the snapshot has nothing either, which is now the rare
         * case. Where the snapshot's date has already gone, the row says when it last reported:
         * Yahoo leaves that date in place until it learns the next one, and "last reported in July"
         * is a different silence from "we have never had a date".
         */
        function renderEarningsHoldings(rep) {
            var rows = rep.holdings || [];
            var reporting = rows.filter(function (r) { return r.state === 'REPORTING'; });
            var expected = rows.filter(function (r) { return r.state === 'EXPECTED'; });
            var notCovered = rows.filter(function (r) { return r.state === 'NOT_COVERED'; });
            var quiet = rows.filter(function (r) { return r.state === 'NONE_SCHEDULED'; });

            var html = '';
            if (reporting.length || expected.length) {
                html += '<tr><th class="lbl">Symbol</th><th class="lbl">Company</th><th>Weight</th>'
                    + '<th>Value</th><th>Date</th><th>When</th><th>EPS est</th><th>LY EPS</th></tr>';
            }
            if (reporting.length) {
                html += eaHoldingsHeader('Reporting in the next ' + (rep.holdingsForwardDays || 21)
                    + ' days', 'From the market calendar, with session and consensus estimate.');
                reporting.forEach(function (r) {
                    html += eaHoldingRow(r,
                        '<td>' + escapeHtml(r.date || '—') + '</td>'
                        + '<td>' + eaTimeBadge(r.time) + '</td>'
                        + '<td>' + eaEps(r.epsForecast) + '</td>'
                        + '<td>' + eaEps(r.lastYearEps) + '</td>');
                });
            }
            if (expected.length) {
                html += eaHoldingsHeader('Expected later',
                    'From the cached company calendar on the fundamentals snapshot — a date only, '
                    + 'usually past the window above, with no session and no consensus estimate. '
                    + 'A trailing ~ is Yahoo estimating the day rather than repeating an '
                    + 'announcement.');
                expected.forEach(function (r) {
                    html += eaHoldingRow(r,
                        '<td>' + escapeHtml(r.expectedDate)
                            + (r.expectedDateEstimate === true ? '~' : '') + '</td>'
                        + '<td class="muted">—</td><td class="muted">—</td><td class="muted">—</td>');
                });
            }
            if (!html) {
                html = '<tr><td class="muted">No date for any holding — neither the calendar nor '
                    + 'the fundamentals snapshot has one.</td></tr>';
            }
            document.getElementById('ea-holdings-table').innerHTML = html;

            var other = '';
            if (notCovered.length) {
                other += '<div class="mo-banner"><b>No date from either source</b> — non-US '
                    + 'listings this feed never carries, with nothing cached for them either, so a '
                    + 'blank here means "unknown", not "nothing due": '
                    + notCovered.map(eaSilentName).join(' · ') + '</div>';
            }
            if (quiet.length) {
                other += '<div class="muted" style="font-size:0.78rem;margin-top:0.35rem;">'
                    + 'Covered, nothing scheduled and no cached date: '
                    + quiet.map(eaSilentName).join(' · ')
                    + '</div>';
            }
            document.getElementById('ea-holdings-other').innerHTML = other;
        }

        /** A full-width divider naming one group of the holdings table and what dated it. */
        function eaHoldingsHeader(title, tip) {
            return '<tr class="ea-group"><td colspan="8" title="' + escapeHtml(tip) + '">'
                + escapeHtml(title) + '</td></tr>';
        }

        /** The five columns both groups share; `tail` is the four the source decides. */
        function eaHoldingRow(r, tail) {
            // Anything at or above 2% of the book is a position whose print actually moves the
            // portfolio — worth making findable at a glance.
            var big = r.pctOfPortfolio != null && parseFloat(r.pctOfPortfolio) >= 0.02;
            return '<tr' + (big ? ' style="font-weight:600;"' : '') + '>'
                + '<td class="lbl">' + escapeHtml(r.symbol) + '</td>'
                + '<td class="lbl">' + escapeHtml(r.companyName || '—') + '</td>'
                + '<td>' + fmtPct(r.pctOfPortfolio) + '</td>'
                + '<td>' + (r.gbpValue == null ? '—' : fmtMoney(parseFloat(r.gbpValue))) + '</td>'
                + tail + '</tr>';
        }

        /**
         * A name in one of the two "no forward date" strips. A snapshot date that has passed is
         * shown as the last report we know of — the one thing separating a company whose rhythm we
         * can see from one we have never had a date for at all.
         */
        function eaSilentName(r) {
            return escapeHtml(r.symbol) + ' <span class="muted">(' + fmtPct(r.pctOfPortfolio)
                + (r.expectedDate ? ', last ' + escapeHtml(r.expectedDate) : '') + ')</span>';
        }

        function renderEarnings() {
            var rep = earnReport;
            if (!rep) return;
            var held = {};
            (rep.heldSymbols || []).forEach(function (s) { held[s] = true; });
            var mineOnly = document.getElementById('ea-mine').checked;

            renderEarningsHoldings(rep);
            document.getElementById('ea-mine-wrap').style.display = 'block';

            var warn = rep.warnings || [];
            document.getElementById('ea-warnings').innerHTML = warn.length
                ? '<div class="mo-banner">' + warn.map(escapeHtml).join('<br>') + '</div>' : '';

            var html = '';
            (rep.days || []).forEach(function (day) {
                var rows = (day.events || []).filter(function (e) {
                    return !mineOnly || held[(e.symbol || '').toUpperCase()];
                });
                var isToday = day.label === 'Today';
                html += '<div class="ea-day' + (isToday ? ' ea-today' : '') + '">';
                html += '<div class="ea-day-head"><span>' + escapeHtml(day.weekday) + ' ' + day.date + '</span>'
                    + '<span class="ea-rel">' + escapeHtml(day.label) + '</span>'
                    + '<span class="ea-count">' + rows.length + (rows.length === 1 ? ' report' : ' reports') + '</span></div>';
                if (!rows.length) {
                    html += '<div class="ea-none">' + (mineOnly ? 'none of your holdings' : 'no scheduled reports') + '</div>';
                } else {
                    html += '<div class="full-bleed"><table class="swing-tbl">';
                    html += '<tr><th class="lbl">Symbol</th><th class="lbl">Company</th><th>Time</th>'
                        + '<th>Mkt cap</th><th>EPS est</th><th>#Est</th><th>LY EPS</th></tr>';
                    rows.forEach(function (e) {
                        var isHeld = held[(e.symbol || '').toUpperCase()];
                        html += '<tr' + (isHeld ? ' class="ea-held"' : '') + '>';
                        html += '<td class="lbl">' + (isHeld ? '★ ' : '') + escapeHtml(e.symbol) + '</td>';
                        html += '<td class="lbl">' + escapeHtml(e.companyName || '—') + '</td>';
                        html += '<td>' + eaTimeBadge(e.time) + '</td>';
                        html += '<td>' + eaCap(e.marketCap) + '</td>';
                        html += '<td>' + eaEps(e.epsForecast) + '</td>';
                        html += '<td>' + (e.numEstimates == null ? '—' : e.numEstimates) + '</td>';
                        html += '<td>' + eaEps(e.lastYearEps) + '</td>';
                        html += '</tr>';
                    });
                    html += '</table></div>';
                }
                html += '</div>';
            });
            document.getElementById('ea-days').innerHTML = html;
            document.getElementById('ea-source').textContent =
                'Source: ' + (rep.source || 'Nasdaq')
                + ' for the calendar below and the "reporting" group above; estimates are '
                + 'consensus, BMO=before open, AMC=after close, TBD=unspecified. The "expected" '
                + 'group is the cached Yahoo company calendar from the fundamentals snapshot — a '
                + 'date only, refreshed every 4 hours.';
        }

    }());
