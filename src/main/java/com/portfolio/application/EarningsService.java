package com.portfolio.application;

import com.portfolio.domain.swing.EarningsLookup;
import com.portfolio.domain.swing.SwingConfig;
import com.portfolio.domain.swing.SwingSeries;
import com.portfolio.persistence.EarningsDateRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.port.EarningsCalendarProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * §D — composes the {@link EarningsCalendarProvider} (EDGAR filing-date proxy) with the
 * {@link EarningsDateRepository} cache, and turns cached dates into a per-{@link SwingSeries}
 * {@link EarningsLookup} the pure engines consume.
 *
 * <p><b>Verification is data-driven:</b> a symbol counts as verified iff we actually obtained ≥1
 * earnings date for it ({@link #isVerifiable} → {@code repo.hasAny}). Resolving a CIK is necessary
 * but not sufficient — a foreign filer like ASML resolves a CIK yet files 20-F/6-K, so it yields no
 * 10-Q/10-K dates and stays <em>unverified</em>, its events flagged rather than silently treated as
 * clean. Symbols that resolve no CIK (e.g. SEMI.L) are likewise unverified.
 *
 * <p>To avoid re-hitting SEC every report for names that legitimately produce nothing (ASML-type),
 * every attempted symbol is recorded in a {@link KeyValueStore} set ({@link #CHECKED_KEY}); a
 * non-forced {@link #ensureEarnings} skips already-checked symbols. A forced call (an explicit
 * Backfill) ignores that set and refreshes the provider so a genuinely new listing is retried.
 */
public class EarningsService {

    private static final Logger log = LoggerFactory.getLogger(EarningsService.class);

    /** KV set of symbols we've already attempted a fetch for (verified or not), to cap SEC traffic. */
    private static final String CHECKED_KEY = "earnings_checked";

    private final EarningsCalendarProvider provider;
    private final EarningsDateRepository repo;
    private final KeyValueStore kv;

    public EarningsService(EarningsCalendarProvider provider, EarningsDateRepository repo, KeyValueStore kv) {
        this.provider = provider;
        this.repo = repo;
        this.kv = kv;
    }

    /** Non-forced warm: skip symbols already fetched or previously attempted. */
    public List<String> ensureEarnings(Collection<String> symbols) {
        return ensureEarnings(symbols, false);
    }

    /**
     * Fetch + cache earnings dates for symbols with no cached rows yet. Best-effort: a per-symbol
     * failure is logged and skipped. When {@code force} is true the checked-set is ignored (and the
     * provider refreshed) so a manual Backfill retries names a passive warm would skip. Returns the
     * symbols freshly fetched (dates found).
     */
    public List<String> ensureEarnings(Collection<String> symbols, boolean force) {
        if (force) provider.refresh();
        Set<String> checked = kv == null ? new LinkedHashSet<>() : new LinkedHashSet<>(kv.getStringSet(CHECKED_KEY));
        List<String> fetched = new ArrayList<>();
        boolean checkedChanged = false;
        for (String raw : symbols) {
            if (raw == null) continue;
            String symbol = raw.trim().toUpperCase();
            if (symbol.isEmpty() || repo.hasAny(symbol)) continue;
            if (!force && checked.contains(symbol)) continue;
            if (!provider.isVerifiable(symbol)) {           // no CIK at all — record so we don't retry each report
                checkedChanged |= checked.add(symbol);
                continue;
            }
            try {
                List<LocalDate> dates = provider.earningsDates(symbol);
                if (!dates.isEmpty()) {
                    repo.saveDates(symbol, dates, provider.sourceName());
                    fetched.add(symbol);
                }
                checkedChanged |= checked.add(symbol);      // mark attempted even when empty (ASML-type)
            } catch (RuntimeException e) {
                log.warn("Earnings fetch failed for {}", symbol, e);
            }
        }
        if (checkedChanged && kv != null) kv.putStringSet(CHECKED_KEY, checked);
        return fetched;
    }

    /** Data-driven: verified iff we have cached earnings dates for the symbol. */
    public boolean isVerifiable(String symbol) {
        return repo.hasAny(symbol == null ? "" : symbol.trim().toUpperCase());
    }

    public String sourceName() {
        return provider.sourceName();
    }

    public List<LocalDate> datesFor(String symbol) {
        return repo.datesFor(symbol);
    }

    /**
     * Build an {@link EarningsLookup} bound to {@code series}' bar indexing. Unverifiable symbols get
     * {@link EarningsLookup#UNVERIFIED}; verifiable ones map their cached earnings dates to the
     * nearest bar and precompute the trading-session distance to the nearest earnings for every bar.
     */
    public EarningsLookup lookupFor(SwingSeries series, SwingConfig cfg) {
        List<LocalDate> dates = repo.datesFor(series.symbol());
        if (dates.isEmpty()) return EarningsLookup.UNVERIFIED;   // no dates obtained ⇒ unverified (incl. ASML-type)
        return buildLookup(series, dates, cfg.eventWindowSessions());
    }

    /** Package-visible for tests: build a verified lookup from an explicit date list. */
    static EarningsLookup buildLookup(SwingSeries series, List<LocalDate> earningsDates, int windowSessions) {
        int n = series.size();
        LocalDate[] dates = new LocalDate[n];
        for (int i = 0; i < n; i++) dates[i] = series.dateAt(i);

        // Map each earnings date to the nearest bar index, then dedup + sort those indices.
        int[] earningsIdx = new int[earningsDates.size()];
        int k = 0;
        for (LocalDate ed : earningsDates) {
            int idx = nearestBarIndex(dates, ed);
            if (idx >= 0) earningsIdx[k++] = idx;
        }
        int[] eIdx = Arrays.copyOf(earningsIdx, k);
        Arrays.sort(eIdx);

        // Precompute the trading-session distance from every bar to its nearest earnings bar.
        Integer[] dist = new Integer[n];
        if (k > 0) {
            for (int i = 0; i < n; i++) {
                int pos = Arrays.binarySearch(eIdx, i);
                if (pos >= 0) { dist[i] = 0; continue; }
                int ins = -pos - 1;
                int below = ins - 1 >= 0 ? i - eIdx[ins - 1] : Integer.MAX_VALUE;
                int above = ins < k ? eIdx[ins] - i : Integer.MAX_VALUE;
                dist[i] = Math.min(below, above);
            }
        }

        return new EarningsLookup() {
            @Override public Info at(int i) {
                Integer d = (i >= 0 && i < n) ? dist[i] : null;
                return new Info(d, d != null && d <= windowSessions);
            }
            @Override public boolean verified() { return true; }
        };
    }

    /** Index of the bar whose date is closest (in calendar days) to {@code target}; −1 if no bars. */
    private static int nearestBarIndex(LocalDate[] dates, LocalDate target) {
        int n = dates.length;
        if (n == 0) return -1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {                                   // binary search for the first bar ≥ target
            int mid = (lo + hi) >>> 1;
            if (dates[mid].isBefore(target)) lo = mid + 1;
            else hi = mid;
        }
        int ceil = lo;                                      // first index with date ≥ target (clamped)
        int floor = ceil - 1;
        if (floor < 0) return ceil;
        if (ceil >= n) return floor;
        long dCeil = Math.abs(dates[ceil].toEpochDay() - target.toEpochDay());
        long dFloor = Math.abs(target.toEpochDay() - dates[floor].toEpochDay());
        return dFloor <= dCeil ? floor : ceil;
    }
}
