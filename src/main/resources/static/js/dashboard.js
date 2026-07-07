    (function () {
        function parseVal(cell, type) {
            var t = cell.textContent.trim();
            if (type === 'num') {
                if (t === '—' || t === '') return null;
                var s = t.replace(/[£$€p%\s]/g, '').replace(/,/g, '');
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
            ths.forEach(function (th, idx) {
                th.addEventListener('click', function () {
                    var type = th.dataset.sort;
                    sortAsc = sortCol === idx ? !sortAsc : true;
                    sortCol = idx;
                    ths.forEach(function (h) {
                        delete h.dataset.sortDir;
                    });
                    th.dataset.sortDir = sortAsc ? 'asc' : 'desc';
                    var tbody = table.querySelector('tbody');
                    var rows = Array.from(tbody.querySelectorAll('tr'));
                    rows.sort(function (a, b) {
                        var av = parseVal(a.cells[idx], type);
                        var bv = parseVal(b.cells[idx], type);
                        if (av === null && bv === null) return 0;
                        if (av === null) return 1;
                        if (bv === null) return -1;
                        var cmp = type === 'num' ? av - bv : av < bv ? -1 : av > bv ? 1 : 0;
                        return sortAsc ? cmp : -cmp;
                    });
                    rows.forEach(function (r) {
                        tbody.appendChild(r);
                    });
                });
            });
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

        document.body.addEventListener('htmx:afterSettle', function () {
            ['#portfolio-result', '#portfolio-cash-result'].forEach(function (sel) {
                document.querySelectorAll(sel + ' table').forEach(function (table) {
                    if (!table.dataset.sortAttached) {
                        table.dataset.sortAttached = '1';
                        attachSort(table);
                    }
                });
            });
        });

        var TAB_KEY = 'portfolio-bench.active-tab';
        var GROUP_KEY_PREFIX = 'portfolio-bench.last-sub.';

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

        function activatePanel(panelId, persist) {
            var group = PANEL_TO_GROUP[panelId];
            if (!group) return;

            document.querySelectorAll('#portfolio-groups .tab').forEach(function (b) {
                b.classList.toggle('active', b.getAttribute('data-group') === group);
            });
            document.querySelectorAll('.tabs-secondary').forEach(function (strip) {
                strip.classList.toggle('active', strip.getAttribute('data-group') === group);
            });
            document.querySelectorAll('.tabs-secondary .tab').forEach(function (b) {
                b.classList.toggle('active', b.getAttribute('data-panel') === panelId);
            });
            document.querySelectorAll('.tab-panel').forEach(function (p) {
                p.classList.toggle('active', p.id === panelId);
            });

            if (panelId === 'panel-contributions') loadContributionsChart();
            if (panelId === 'panel-value') loadValueChart();
            if (panelId === 'panel-allocation') setupAllocationPanel();
            if (panelId === 'panel-returns') { loadReturnsChart(); setupBenchmark(); }
            if (panelId === 'panel-risk') loadRisk();
            if (panelId === 'panel-dividends') loadDividends();
            if (panelId === 'panel-position') setupPosition();
            if (panelId === 'panel-correlations') setupCorrelations();
            if (panelId === 'panel-watchlist') setupWatchlist();
            if (panelId === 'panel-swing') setupSwing();
            if (panelId === 'panel-trend') setupTrend();
            if (panelId === 'panel-snapshots') setupSnapshots();
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
                } catch (e) {}
            }
        }

        document.querySelectorAll('.tabs-secondary .tab').forEach(function (btn) {
            btn.addEventListener('click', function () { activatePanel(btn.dataset.panel, true); });
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
        try {
            var savedTab = localStorage.getItem(TAB_KEY);
            if (savedTab && document.getElementById(savedTab)) {
                activatePanel(savedTab, false);
            }
        } catch (e) {}

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
            loadAllocation();
            if (allocPanelInitialized) return;
            allocPanelInitialized = true;
            bindSubviewToggle(document.getElementById('alloc-toggle'), {
                'alloc-view-breakdown': loadAllocation,
                'alloc-view-concentration': loadConcentration,
                'alloc-view-currency': loadCurrency
            });
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

        function loadAllocation() {
            if (allocLoaded) return;
            allocLoaded = true;
            loadTargetAllocation();
            document.getElementById('target-save').addEventListener('click', saveTargetAllocation);
            fetch('/allocation').then(function (r) { return r.json(); }).then(function (data) {
                allocData = data;
                if (!data.points || !data.points.length) return;
                renderAllocCashChart(data.points);
                renderAllocSymbolsChart(data.points);
                initAllocSnapshot(data.points);
                document.getElementById('alloc-cash-zoom-reset').addEventListener('click', function () {
                    if (allocCashChart) allocCashChart.resetZoom();
                });
                document.getElementById('alloc-symbols-zoom-reset').addEventListener('click', function () {
                    if (allocSymbolsChart) allocSymbolsChart.resetZoom();
                });
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
            var url = '/attribution?from=' + encodeURIComponent(from) +
                    (to ? '&to=' + encodeURIComponent(to) : '');
            fetch(url).then(function (r) {
                if (!r.ok) return r.text().then(function (t) { throw new Error(t || 'Request failed'); });
                return r.json();
            }).then(function (data) {
                renderAttribution(data);
                status.textContent = '';
            }).catch(function (err) {
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
            // Use abs(totalPnl) as the denominator so winners and losers both report a positive
            // share of the magnitude of the net P&L — easier to read than signed percentages.
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

        function setupFundamentals() {
            if (fundInitialized) return;
            fundInitialized = true;
            var sel = document.getElementById('fund-ticker');
            fetch('/fundamentals/tickers').then(function (r) { return r.json(); })
                    .then(function (tickers) {
                        (tickers || []).forEach(function (t) {
                            var opt = document.createElement('option');
                            opt.value = t;
                            opt.textContent = t;
                            sel.appendChild(opt);
                        });
                        // Default to GOOGL since the user's example used Google.
                        if (sel.querySelector('option[value="GOOGL"]')) sel.value = 'GOOGL';
                        runFundamentals();
                    });
            document.getElementById('fund-run').addEventListener('click', runFundamentals);
            document.getElementById('fund-ticker').addEventListener('change', runFundamentals);
            document.getElementById('fund-years').addEventListener('change', runFundamentals);
            document.getElementById('fund-backfill').addEventListener('click', backfillFundamentalsPrices);
            document.getElementById('fund-pe-zoom-reset').addEventListener('click', function () {
                if (fundPeChart) fundPeChart.resetZoom();
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
            var tbody = table.querySelector('tbody');
            var rows = Array.from(tbody.querySelectorAll('tr'));
            rows.sort(function (a, b) {
                var av = parseVal(a.cells[sortIdx], sortType);
                var bv = parseVal(b.cells[sortIdx], sortType);
                if (av === null && bv === null) return 0;
                if (av === null) return 1;
                if (bv === null) return -1;
                var cmp = sortType === 'num' ? av - bv : av < bv ? -1 : av > bv ? 1 : 0;
                return sortAsc ? cmp : -cmp;
            });
            rows.forEach(function (r) { tbody.appendChild(r); });
        }

        function td(text, cls) {
            var c = document.createElement('td');
            if (cls) c.className = cls;
            c.textContent = text == null ? '—' : text;
            if (text == null) c.classList.add('na');
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
        // Field tuple: [key, label, type, tooltip, noFinancial?, glossaryAnchor?].
        // Group: { title, fields, noFinancial? } — noFinancial skips the whole group for
        // banks/insurers where EV/Net Debt/EBITDA/FCF don't apply.
        function fundamentalsFieldGroups() {
            return [
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
                    var v = row.extra ? row.extra[key] : null;
                    if (v == null) return;
                    var anchorAttr = anchor ? ' data-anchor="' + anchor + '"' : '';
                    var labelHtml = tip
                        ? '<span class="snap-tip" data-tip="' + tip.replace(/"/g, '&quot;') + '"' + anchorAttr + '>' + label + '</span>'
                        : label;
                    inner += '<div class="snap-kv"><span class="k">' + labelHtml + '</span>' +
                            '<span class="v">' + fmtSnapValue(v, type, row.currency) + '</span></div>';
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

        function fmtSnapValue(v, type, ccy) {
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
            document.getElementById('risk-rf-apply')
                    .addEventListener('click', applyRiskFreeRate);
            fetchAndRenderRisk();
        }

        function fetchAndRenderRisk() {
            fetch('/risk').then(function (r) { return r.json(); }).then(renderRisk);
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
            fetch('/dividends').then(function (r) { return r.json(); }).then(renderDividends);
            fetch('/dividends/audit').then(function (r) { return r.json(); }).then(renderDividendAudit);
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
            fetch('/concentration').then(function (r) { return r.json(); })
                    .then(renderConcentration);
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
                        ' in the lookback window — needs ≥ 70% coverage. Excluded: <code>' +
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
            fetch('/currency').then(function (r) { return r.json(); })
                    .then(renderCurrency);
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

        var snapshotsInitialized = false;
        var snapshotsAll = [];

        function setupSnapshots() {
            if (snapshotsInitialized) return;
            snapshotsInitialized = true;
            document.getElementById('snap-apply').addEventListener('click', applySnapshotDelta);
            fetch('/snapshots').then(function (r) { return r.json(); }).then(function (snaps) {
                snapshotsAll = snaps || [];
                var fromSel = document.getElementById('snap-from');
                var toSel = document.getElementById('snap-to');
                if (!snapshotsAll.length) {
                    document.getElementById('snap-empty').textContent =
                            'No snapshots saved yet. Run "Aggregated Portfolio" on the Holdings tab to capture one.';
                    return;
                }
                snapshotsAll.forEach(function (s) {
                    var opt1 = document.createElement('option');
                    opt1.value = s.date;
                    opt1.textContent = s.date + '  (£' +
                            parseFloat(s.totalValueGbp).toLocaleString('en-GB',
                                    { maximumFractionDigits: 0 }) + ')';
                    var opt2 = opt1.cloneNode(true);
                    fromSel.appendChild(opt1);
                    toSel.appendChild(opt2);
                });
                // Defaults: earliest → latest.
                fromSel.value = snapshotsAll[0].date;
                toSel.value = snapshotsAll[snapshotsAll.length - 1].date;
                applySnapshotDelta();
            });
        }

        function applySnapshotDelta() {
            var from = document.getElementById('snap-from').value;
            var to = document.getElementById('snap-to').value;
            var status = document.getElementById('snap-status');
            status.textContent = 'Loading…';
            var url = '/snapshots/delta?from=' + encodeURIComponent(from) +
                    '&to=' + encodeURIComponent(to);
            fetch(url).then(function (r) {
                if (!r.ok) return r.text().then(function (m) { throw new Error(m); });
                return r.json();
            }).then(function (data) {
                status.textContent = '';
                document.getElementById('snap-empty').style.display = 'none';
                document.getElementById('snap-content').style.display = '';
                renderSnapshotDelta(data);
            }).catch(function (e) {
                status.textContent = 'Error: ' + e.message;
            });
        }

        function renderSnapshotDelta(data) {
            var d = data.delta || {};
            document.getElementById('snap-days').textContent = data.spanDays != null ? data.spanDays : '—';
            setSignedMoney('snap-value', d.totalValueGbp);
            // Percent change against the from-value for context.
            var fromV = parseFloat(data.from.totalValueGbp);
            var deltaV = parseFloat(d.totalValueGbp);
            var pct = fromV ? ((deltaV / fromV) * 100) : null;
            document.getElementById('snap-value-pct').textContent =
                    pct != null ? ((pct >= 0 ? '+' : '') + pct.toFixed(2) + '%') : '';
            setSignedMoney('snap-gain', d.totalGainGbp);
            setSignedMoney('snap-cash', d.totalCashGbp);
            var ret = document.getElementById('snap-return');
            if (d.returnPct != null) {
                var rp = parseFloat(d.returnPct) * 100;
                ret.textContent = (rp >= 0 ? '+' : '') + rp.toFixed(2) + 'pp';
                ret.className = 'value' + (rp >= 0 ? ' pos' : ' neg');
            } else { ret.textContent = '—'; ret.className = 'value na'; }
            setFxDelta('snap-usd', d.gbpusd);
            setFxDelta('snap-eur', d.gbpeur);

            var rows = [
                ['Total value £', data.from.totalValueGbp, data.to.totalValueGbp, d.totalValueGbp],
                ['Total gain £', data.from.totalGainGbp, data.to.totalGainGbp, d.totalGainGbp],
                ['Cash £', data.from.totalCashGbp, data.to.totalCashGbp, d.totalCashGbp],
                ['Return %', toPct(data.from.returnPct), toPct(data.to.returnPct), toPct(d.returnPct)],
                ['GBP/USD', data.from.gbpusd, data.to.gbpusd, d.gbpusd],
                ['GBP/EUR', data.from.gbpeur, data.to.gbpeur, d.gbpeur]
            ];
            var tbody = document.querySelector('#snap-table tbody');
            tbody.innerHTML = '';
            rows.forEach(function (r) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td class="txt">' + r[0] + '</td>' +
                    '<td>' + fmtAny(r[1]) + '</td>' +
                    '<td>' + fmtAny(r[2]) + '</td>' +
                    '<td>' + fmtAnyDelta(r[3]) + '</td>';
                tbody.appendChild(tr);
            });
        }

        function setFxDelta(id, v) {
            var el = document.getElementById(id);
            if (v == null) { el.textContent = '—'; el.className = 'value na'; return; }
            var n = parseFloat(v);
            el.textContent = (n >= 0 ? '+' : '') + n.toFixed(4);
            el.className = 'value';
        }

        function toPct(v) {
            return v == null ? null : parseFloat(v) * 100;
        }

        function fmtAny(v) {
            if (v == null) return '—';
            return parseFloat(v).toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 4 });
        }

        function fmtAnyDelta(v) {
            if (v == null) return '—';
            var n = parseFloat(v);
            var sign = n >= 0 ? '+' : '−';
            return sign + Math.abs(n).toLocaleString('en-GB',
                    { minimumFractionDigits: 2, maximumFractionDigits: 4 });
        }

        var reconLoaded = false;
        function loadReconciliation() {
            if (reconLoaded) return;
            reconLoaded = true;
            document.getElementById('price-save').addEventListener('click', saveManualPrice);
            fetch('/reconciliation').then(function (r) { return r.json(); }).then(renderRecon);
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
            td.colSpan = 7;
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
            ageTimer: null, sortAttached: false
        };

        function setupLive() {
            updateLiveStatus(false);
            if (!liveState.sortAttached) {
                attachSort(document.getElementById('live-table'));
                liveState.sortAttached = true;
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
            var changed = applyLiveRows(snap.rows || []);
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

        function applyLiveRows(rows) {
            var tbody = document.querySelector('#live-table tbody');
            if (!tbody) return;
            // If the user clicked a column header, respect their sort and don't re-order
            // existing rows on each snapshot — they can click again to re-sort.
            var userSorted = !!document.querySelector('#live-table thead th[data-sort-dir]');
            var existing = {};
            Array.from(tbody.querySelectorAll('tr')).forEach(function (tr) {
                existing[tr.dataset.symbol] = tr;
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
                    for (var i = 0; i < 12; i++) tr.appendChild(document.createElement('td'));
                    tr.cells[0].className = 'txt';
                    tr.cells[1].className = 'txt';
                    tr.cells[10].className = 'txt';
                    tr.cells[11].className = 'txt';
                    tbody.appendChild(tr);
                    existing[row.symbol] = tr;
                }
                if (renderLiveRow(tr, row, prevByKey, nextByKey)) changedAny = true;
                if (!userSorted && !isNew) tbody.appendChild(tr);
            });
            Object.keys(existing).forEach(function (sym) {
                if (!seen[sym]) existing[sym].remove();
            });
            liveState.lastValues = nextByKey;
            // First snapshot has no baseline, so we can't claim "changed". Subsequent
            // snapshots: changed = any cell value moved.
            return hasPrev && changedAny;
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
            if (setNumericCell(tr.cells[3], row.symbol + '|last', nLast, nLast == null ? '—' : fmtLiveNum(nLast, 4), prevByKey, nextByKey)) changed = true;
            applyMomClass(tr.cells[3], numOrNull(row.monthChangePct));
            if (setNumericCell(tr.cells[4], row.symbol + '|pct', nPct, nPct == null ? '—' : fmtLivePct(nPct), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[4], nPct);
            if (setNumericCell(tr.cells[5], row.symbol + '|dayGbp', nDay, nDay == null ? '—' : (nDay >= 0 ? '+' : '−') + fmtLiveNum(Math.abs(nDay), 2), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[5], nDay);
            var n5 = numOrNull(row.change5dPct);
            var n10 = numOrNull(row.change10dPct);
            var n30 = numOrNull(row.monthChangePct);
            if (setNumericCell(tr.cells[6], row.symbol + '|d5', n5, n5 == null ? '—' : fmtLivePct(n5), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[6], n5);
            if (setNumericCell(tr.cells[7], row.symbol + '|d10', n10, n10 == null ? '—' : fmtLivePct(n10), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[7], n10);
            if (setNumericCell(tr.cells[8], row.symbol + '|d30', n30, n30 == null ? '—' : fmtLivePct(n30), prevByKey, nextByKey)) changed = true;
            applyDeltaClass(tr.cells[8], n30);
            if (setNumericCell(tr.cells[9], row.symbol + '|pos', nPos, nPos == null ? '—' : fmtLiveNum(nPos, 2), prevByKey, nextByKey)) changed = true;
            tr.cells[10].textContent = row.accounts || '';
            renderQuoteTs(tr.cells[11], row.priceTs);
            return changed;
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

        function renderQuoteTs(td, iso) {
            td.classList.remove('live-ts-fresh', 'live-ts-stale', 'live-ts-old');
            if (!iso) { td.textContent = '—'; td.removeAttribute('title'); return; }
            var ts = new Date(iso);
            td.textContent = ts.toLocaleTimeString();
            td.title = ts.toISOString();
            var ageSec = (Date.now() - ts.getTime()) / 1000;
            if (ageSec < 300) td.classList.add('live-ts-fresh');         // < 5 min
            else if (ageSec < 1800) td.classList.add('live-ts-stale');   // < 30 min
            else td.classList.add('live-ts-old');                        // older — market closed for this listing
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
        var wlChart = null;
        var wlFundamentals = null;      // symbol -> quoteSummary, cached from /portfolio-fundamentals
        var wlSortAttached = false;
        var wlCurrentSymbol = null;
        var wlReturnsLoaded = false;
        var wlCurrentView = 'stats';    // 'stats' or 'returns'

        function setupWatchlist() {
            if (!watchlistInited) {
                watchlistInited = true;
                document.getElementById('wl-add-btn').addEventListener('click', addWatchlistSymbol);
                document.getElementById('wl-add-symbol').addEventListener('keydown', function (e) {
                    if (e.key === 'Enter') addWatchlistSymbol();
                });
                document.getElementById('wl-refresh-btn').addEventListener('click', loadWatchlist);
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
                document.getElementById('wl-view-stats').addEventListener('click', function () { switchWlView('stats'); });
                document.getElementById('wl-view-returns').addEventListener('click', function () { switchWlView('returns'); });
            }
            loadWatchlist();
            wlStartPolling();
        }

        function switchWlView(view) {
            wlCurrentView = view;
            var isStats = view === 'stats';
            document.getElementById('wl-view-stats').classList.toggle('active', isStats);
            document.getElementById('wl-view-returns').classList.toggle('active', !isStats);
            var table = document.getElementById('watchlist-table');
            var panel = document.getElementById('wl-returns-panel');
            if (isStats) {
                panel.style.display = 'none';
                table.style.display = '';
                return;
            }
            table.style.display = 'none';
            panel.style.display = '';
            if (!wlReturnsLoaded) {
                panel.innerHTML = '<p class="muted" style="padding:0.5rem">Loading…</p>';
                fetch('/watchlist/returns').then(function (r) { return r.json(); })
                    .then(function (data) { wlReturnsLoaded = true; renderWatchlistReturns(data); })
                    .catch(function () { panel.innerHTML = '<p class="muted">Could not load returns.</p>'; });
            }
        }

        function wlSetStatus(msg) {
            document.getElementById('wl-status').textContent = msg || '';
        }

        function loadWatchlist() {
            wlReturnsLoaded = false;
            wlSetStatus('Loading…');
            fetch('/watchlist').then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (view) {
                renderWatchlist(view.rows || []);          // sets wlLoadedOk
                wlSetStatus('');
            }).catch(function () {
                wlLoadedOk = false;                         // the poll will retry the full load
                wlSetStatus('Could not load watchlist.');
            });
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
            wlPollTimer = setInterval(wlPollTick, WL_POLL_MS);
        }

        function wlPollTick() {
            if (document.hidden) return;                   // browser tab backgrounded
            var panel = document.getElementById('panel-watchlist');
            if (!panel || !panel.classList.contains('active')) return;   // not the visible tab
            if (document.getElementById('wl-modal').classList.contains('open')) return;  // user in the popup
            // Self-heal: if the last full load failed (e.g. the server hot-restarted under the tab),
            // the quote poll has no rows to patch — retry the full load until it renders.
            if (!wlLoadedOk) { loadWatchlist(); return; }
            if (wlCurrentView !== 'stats') return;         // returns heatmap doesn't move intraday
            fetch('/watchlist/quotes').then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            }).then(function (view) {
                wlApplyQuotes(view.rows || []);
                if (document.getElementById('wl-status').textContent) wlSetStatus('');  // recovered
            }).catch(function () { /* transient; the next tick retries */ });
        }

        function wlApplyQuotes(rows) {
            var tbody = document.querySelector('#watchlist-table tbody');
            if (!tbody) return;
            rows.forEach(function (q) {
                var tr = tbody.querySelector('tr[data-sym="' + q.symbol + '"]');
                if (!tr) return;                           // symbol set changed since last full load
                if (document.activeElement && tr.contains(document.activeElement)) return;  // mid-edit
                var priceCell = tr.querySelector('td[data-f="price"]');
                if (priceCell) priceCell.textContent = q.currentPrice == null ? '—' : fmtPrice(q.currentPrice, q.currency);
                wlPatchPct(tr, 'todayPct', q.todayPct, true);
                wlPatchPct(tr, 'pct2d', q.pct2d, true);
                wlPatchPct(tr, 'pct3d', q.pct3d, true);
                wlPatchPct(tr, 'pct5d', q.pct5d, true);
                wlPatchPct(tr, 'pct30d', q.pct30d, true);
                wlPatchPct(tr, 'pctFromHigh', q.pctFromHigh, false);
                wlPatchPct(tr, 'pctFromLow', q.pctFromLow, false);
                wlPatchVol(tr, 'vol5d', q.vol5d);
                wlPatchVol(tr, 'vol10d', q.vol10d);
                tr.className = rowAlertClass(q);           // recolour by the fresh alert flags
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
            wlSetStatus('Adding ' + sym + ' — fetching prices…');
            fetch('/watchlist/add', { method: 'POST', body: body })
                .then(function (r) { if (!r.ok) throw new Error(); return r.json(); })
                .then(function (view) {
                    document.getElementById('wl-add-symbol').value = '';
                    document.getElementById('wl-add-high').value = '';
                    document.getElementById('wl-add-move').value = '';
                    document.getElementById('wl-add-above').value = '';
                    document.getElementById('wl-add-below').value = '';
                    renderWatchlist(view.rows || []);
                    wlSetStatus(sym + ' added — data fills in as the backfill completes.');
                    // Poll a couple of times so a freshly-added row picks up its backfilled prices.
                    setTimeout(loadWatchlist, 8000);
                    setTimeout(loadWatchlist, 30000);
                })
                .catch(function () { wlSetStatus('Could not add ' + sym + '.'); });
        }

        function removeWatchlistSymbol(sym) {
            var body = new URLSearchParams(); body.set('symbol', sym);
            fetch('/watchlist/remove', { method: 'POST', body: body })
                .then(function (r) { return r.json(); })
                .then(function (view) { renderWatchlist(view.rows || []); });
        }

        function moveWatchlistSymbol(sym, dir) {
            var body = new URLSearchParams(); body.set('symbol', sym); body.set('dir', dir);
            fetch('/watchlist/move', { method: 'POST', body: body })
                .then(function (r) { return r.json(); })
                .then(function (view) { renderWatchlist(view.rows || []); });
        }

        function saveWatchlistThreshold(sym, highPct, movePct, priceAbove, priceBelow) {
            var body = new URLSearchParams();
            body.set('symbol', sym);
            body.set('highPct', highPct);
            body.set('movePct', movePct);
            body.set('priceAbove', priceAbove == null ? '' : priceAbove);
            body.set('priceBelow', priceBelow == null ? '' : priceBelow);
            fetch('/watchlist/threshold', { method: 'POST', body: body })
                .then(function (r) { if (!r.ok) throw new Error(); return r.json(); })
                .then(function (view) { renderWatchlist(view.rows || []); })
                .catch(function () { wlSetStatus('Could not update thresholds for ' + sym + '.'); });
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

        function wlGbpCell(v, colour) {
            if (v == null) return '<td class="na">—</td>';
            var n = parseFloat(v);
            var cls = colour ? (n >= 0 ? ' class="wl-pos"' : ' class="wl-neg"') : '';
            var s = (n < 0 ? '-£' : '£') + Math.abs(n).toLocaleString(undefined, { maximumFractionDigits: 0 });
            return '<td' + cls + '>' + s + '</td>';
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

        function renderWatchlist(rows) {
            wlLoadedOk = true;                             // reaching a render means the fetch succeeded
            var table = document.getElementById('watchlist-table');
            var empty = document.getElementById('wl-empty');
            var toggle = document.getElementById('wl-view-toggle');
            var tbody = table.querySelector('tbody');
            tbody.innerHTML = '';
            if (!rows.length) {
                table.style.display = 'none';
                empty.style.display = 'block';
                toggle.style.display = 'none';
                return;
            }
            empty.style.display = 'none';
            toggle.style.display = '';
            // Only show the stats table when in stats view.
            if (wlCurrentView === 'stats') {
                table.style.display = '';
            }

            rows.forEach(function (r) {
                var tr = document.createElement('tr');
                tr.className = rowAlertClass(r);
                tr.dataset.sym = r.symbol;
                var price = r.currentPrice == null ? '—' : fmtPrice(r.currentPrice, r.currency);
                var pe = r.trailingPe == null ? '—' : parseFloat(r.trailingPe).toFixed(1);
                // Blank (empty input) means "no alert for this trigger" — a disabled threshold
                // is stored as 0, shown as an empty cell.
                var highV = (r.highThresholdPct == null || parseFloat(r.highThresholdPct) <= 0) ? '' : parseFloat(r.highThresholdPct);
                var moveV = (r.moveThresholdPct == null || parseFloat(r.moveThresholdPct) <= 0) ? '' : parseFloat(r.moveThresholdPct);
                // Price levels are already surfaced as null when disabled (server-side levelOrNull).
                var aboveV = (r.priceAbove == null) ? '' : parseFloat(r.priceAbove);
                var belowV = (r.priceBelow == null) ? '' : parseFloat(r.priceBelow);
                var symCell = '<td><b>' + r.symbol + '</b>' +
                    (r.missingData ? ' <span class="muted" style="font-size:0.75rem">(fetching…)</span>' : '') +
                    (r.held ? ' <span class="muted" title="You hold this" style="font-size:0.75rem">●</span>' : '') + '</td>';
                tr.innerHTML = symCell +
                    '<td data-f="price">' + price + '</td>' +
                    wlPctCell(r.todayPct, true, 'todayPct') +
                    wlPctCell(r.pct2d, true, 'pct2d') +
                    wlPctCell(r.pct3d, true, 'pct3d') +
                    wlPctCell(r.pct5d, true, 'pct5d') +
                    wlPctCell(r.pct30d, true, 'pct30d') +
                    wlPctCell(r.pctFromHigh, false, 'pctFromHigh') +
                    wlPctCell(r.pctFromLow, false, 'pctFromLow') +
                    wlVolCell(r.vol5d, 'vol5d') +
                    wlVolCell(r.vol10d, 'vol10d') +
                    wlVolCell(r.vol30d) +
                    wlVolCell(r.vol1y) +
                    '<td>' + pe + '</td>' +
                    wlGbpCell(r.held ? r.positionValueGbp : null, false) +
                    wlGbpCell(r.held ? r.gainGbp : null, true) +
                    '<td><input class="wl-thr-input" data-sym="' + r.symbol + '" data-kind="high" type="text" value="' + highV + '"/></td>' +
                    '<td><input class="wl-thr-input" data-sym="' + r.symbol + '" data-kind="move" type="text" value="' + moveV + '"/></td>' +
                    '<td><input class="wl-thr-input wl-price-input" data-sym="' + r.symbol + '" data-kind="above" type="text" value="' + aboveV + '"/></td>' +
                    '<td><input class="wl-thr-input wl-price-input" data-sym="' + r.symbol + '" data-kind="below" type="text" value="' + belowV + '"/></td>' +
                    '<td class="wl-actions">' +
                        '<button class="wl-move" data-sym="' + r.symbol + '" data-dir="up" title="Move up">↑</button>' +
                        '<button class="wl-move" data-sym="' + r.symbol + '" data-dir="down" title="Move down">↓</button>' +
                        '<button class="wl-remove" data-sym="' + r.symbol + '" title="Remove">×</button>' +
                    '</td>';

                // Row click opens the popup, except when interacting with inputs/buttons.
                tr.addEventListener('click', function (e) {
                    if (e.target.closest('input, button')) return;
                    openWlModal(r);
                });
                tbody.appendChild(tr);
            });

            tbody.querySelectorAll('.wl-remove').forEach(function (btn) {
                btn.addEventListener('click', function () { removeWatchlistSymbol(this.dataset.sym); });
            });
            tbody.querySelectorAll('.wl-move').forEach(function (btn) {
                btn.addEventListener('click', function () { moveWatchlistSymbol(this.dataset.sym, this.dataset.dir); });
            });
            tbody.querySelectorAll('.wl-thr-input').forEach(function (inp) {
                inp.addEventListener('change', function () {
                    var sym = this.dataset.sym;
                    var row = this.closest('tr');
                    var high = row.querySelector('.wl-thr-input[data-kind="high"]').value.trim();
                    var move = row.querySelector('.wl-thr-input[data-kind="move"]').value.trim();
                    var above = row.querySelector('.wl-thr-input[data-kind="above"]').value.trim();
                    var below = row.querySelector('.wl-thr-input[data-kind="below"]').value.trim();
                    saveWatchlistThreshold(sym, high, move, above, below);
                });
            });

            if (!wlSortAttached) { wlSortAttached = true; attachSort(table); }
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
            stats.appendChild(wlStat('% vs high', wlPctText(r.pctFromHigh)));
            if (r.priceAbove != null) stats.appendChild(wlStat('≥ target', fmtPrice(r.priceAbove, r.currency)));
            if (r.priceBelow != null) stats.appendChild(wlStat('≤ target', fmtPrice(r.priceBelow, r.currency)));
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
            if (wlChart) { wlChart.destroy(); wlChart = null; }
        }

        function loadWlSeries(symbol, window) {
            fetch('/watchlist/series?symbol=' + encodeURIComponent(symbol) + '&window=' + window)
                .then(function (r) { return r.json(); })
                .then(function (s) { renderWlChart(s); })
                .catch(function () { /* leave prior chart */ });
        }

        function wlParseTs(ts) {
            // Daily points are date-only ('YYYY-MM-DD'); anchor at local noon so the label
            // doesn't slip a day across time zones. Intraday points are full ISO instants.
            return /^\d{4}-\d{2}-\d{2}$/.test(ts) ? new Date(ts + 'T12:00:00') : new Date(ts);
        }

        function wlAxisLabel(ts, oneDay) {
            var d = wlParseTs(ts);
            return oneDay
                ? d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
                : d.toLocaleDateString([], { month: 'short', day: 'numeric' });
        }

        function renderWlChart(s) {
            var ctx = document.getElementById('wl-chart').getContext('2d');
            var pts = s.points || [];
            var labels = pts.map(function (p) { return p.t; });
            var data = pts.map(function (p) { return parseFloat(p.p); });
            var isIntraday = s.granularity === 'intraday';
            var oneDay = s.window === '1D';
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
                                callback: function (value, index) { return wlAxisLabel(labels[index], oneDay); } } },
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
            fetch('/portfolio-fundamentals').then(function (r) { return r.json(); })
                .then(function (snap) {
                    wlFundamentals = {};
                    (snap.rows || []).forEach(function (row) { wlFundamentals[row.symbol] = row; });
                    render();
                })
                .catch(function () { body.innerHTML = '<p class="muted">Could not load fundamentals.</p>'; });
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

    }());
