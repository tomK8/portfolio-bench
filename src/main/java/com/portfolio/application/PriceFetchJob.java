package com.portfolio.application;

import com.portfolio.adapter.YahooPriceFetcher;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.domain.DividendYield;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.DividendHistoryRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains the local {@code price_history} table from Yahoo Finance. Driven by every symbol
 * ever traded (not just current holdings); gilts are skipped (broker price stays source of truth).
 * Idempotent: the first run backfills ~30 years per ticker, later runs re-fetch from the latest
 * stored day through today and upsert. Re-fetching the latest day matters because Yahoo's daily
 * bar for an in-progress session reports the current trading price as {@code close} — if the job
 * ran mid-session (e.g. a startup tick), the day's stored close is wrong until it's overwritten.
 * The 22:00 London cron run completes after US close, so today's close ends up correct by EoD.
 */
public class PriceFetchJob {

    private static final Logger log = LoggerFactory.getLogger(PriceFetchJob.class);

    /**
     * When the evening's closes become final. 16:00 New York is 21:00 London in both halves of
     * the year — the two clocks shift together — so 22:00 is an hour after the last US close
     * whatever the date, and a pass that completes after it has captured real closes rather than
     * Yahoo's mid-session snapshot of an in-progress day.
     */
    static final int CLOSES_FINAL_HOUR = 22;
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    /** When a pass last walked the whole universe, so a missed evening can be noticed. */
    static final String LAST_RUN_KEY = "price_fetch_last_completed";

    private static final int LOOKBACK_YEARS = 30;
    private static final long THROTTLE_MS = 500;   // be polite to Yahoo: ≤1 request / 500ms
    private static final int FRESH_LOOKBACK_DAYS = 4;   // history newer than this skips a re-add backfill
    private static final int SUGGESTION_COUNT = 6;      // Yahoo candidates kept when a ticker is rejected

    private final CashTransactionRepository cashRepo;
    private final PriceHistoryRepository priceRepo;
    private final YahooPriceFetcher fetcher;
    private final YahooTickerMap tickers;
    private final KeyValueStore kv;
    private final UnresolvedSymbols unresolved;
    /** Null in tests that only exercise price writes; every dividend write is guarded. */
    private final DividendHistoryRepository divRepo;

    /**
     * One pass at a time. The trigger asks every 30 minutes whether the evening is owed and the
     * marker is only stamped when a pass finishes, so a pass still walking the universe — which
     * on 2026-11-18 took eleven hours against a Yahoo that was timing out — would otherwise have
     * the next tick start a second one on top of it, and a third after that. Each walks the same
     * ~120 tickers, so the loser skipping is right: there is nothing for it to do that the pass
     * already in flight is not doing, and doubling up would break the politeness throttle that
     * keeps Yahoo serving us at all.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PriceFetchJob(CashTransactionRepository cashRepo, PriceHistoryRepository priceRepo,
                         YahooPriceFetcher fetcher, YahooTickerMap tickers, KeyValueStore kv) {
        this(cashRepo, priceRepo, fetcher, tickers, kv, null);
    }

    public PriceFetchJob(CashTransactionRepository cashRepo, PriceHistoryRepository priceRepo,
                         YahooPriceFetcher fetcher, YahooTickerMap tickers, KeyValueStore kv,
                         DividendHistoryRepository divRepo) {
        this.cashRepo = cashRepo;
        this.priceRepo = priceRepo;
        this.fetcher = fetcher;
        this.tickers = tickers;
        this.kv = kv;
        this.unresolved = new UnresolvedSymbols(kv);
        this.divRepo = divRepo;
    }

    /**
     * Persists the dividend events that rode along with a price fetch.
     *
     * <p>{@code fullWindow} is the whole point: only a fetch that asked for a symbol's entire
     * history may record a scan marker, because only then does "no dividends" mean the instrument
     * pays none. The nightly incremental run covers the gap since the last stored bar and sees no
     * dividends on almost every night, so a marker from there would libel every payer as an
     * accumulator. It still writes what it finds, so a newly-declared dividend lands the same night.
     */
    private void captureDividends(String ticker, YahooPriceFetcher.DailySeries series,
                                  LocalDate windowStart, boolean fullWindow) {
        if (divRepo == null || series == null) return;
        List<DividendYield.Payment> payments = new ArrayList<>();
        String currency = null;
        for (YahooPriceFetcher.DividendEvent e : series.dividends()) {
            payments.add(new DividendYield.Payment(e.exDate(), e.amount()));
            if (currency == null) currency = e.currency();
        }
        if (!payments.isEmpty()) divRepo.saveDividends(ticker, payments, currency);
        if (fullWindow && !series.bars().isEmpty()) {
            divRepo.markFullScan(ticker, windowStart, payments.size());
        }
    }

    /**
     * Run only if this evening's closes have not been captured yet — the trigger this job should
     * have had all along.
     *
     * <p>A {@code cron} at 22:00 assumes the laptop is awake and the app running at 22:00, and
     * this one usually is not: on 2026-11-22 the job had last completed on the 18th while the app
     * ran throughout, leaving 113 of ~190 tickers four to six days stale and the Live tab reading
     * a four-session move as the day's. The same reasoning already moved the backup and the
     * reminders off calendar triggers; this asks the equivalent question — <em>is the most recent
     * 22:00 still uncovered?</em> — which a machine that was asleep at 22:00 answers yes to the
     * moment it wakes, and answers once.
     *
     * <p>Over-calling is cheap by construction and under-calling is not, which is why the gate
     * errs open: the pass is idempotent, re-fetches only from each ticker's latest stored bar, and
     * skips whatever is already current. A weekend evening therefore costs one pass that writes
     * almost nothing, and the one day it is wrong about is the day it would otherwise skip.
     */
    public void runIfDue() {
        if (!isDue(Instant.now())) return;
        run();
    }

    /** True while a pass is walking the universe. */
    public boolean isRunning() {
        return running.get();
    }

    /** True when no complete pass has finished since the most recent {@link #CLOSES_FINAL_HOUR}. */
    boolean isDue(Instant now) {
        Instant last = kv.getInstant(LAST_RUN_KEY);
        return last == null || last.isBefore(lastClosingTime(now));
    }

    /**
     * The most recent {@link #CLOSES_FINAL_HOUR} London strictly before {@code now} — the instant
     * after which the day's closes are the day's closes. Derived rather than stored so a run on
     * any schedule, from any trigger, settles the same question.
     */
    static Instant lastClosingTime(Instant now) {
        ZonedDateTime london = now.atZone(LONDON);
        ZonedDateTime closing = london.toLocalDate().atTime(CLOSES_FINAL_HOUR, 0).atZone(LONDON);
        if (!closing.toInstant().isBefore(now)) closing = closing.minusDays(1);
        return closing.toInstant();
    }

    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.info("Daily price fetch already in progress — skipping");
            return;
        }
        try {
            fetchAll();
        } finally {
            running.set(false);
        }
    }

    private void fetchAll() {
        LocalDate today = LocalDate.now();
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);

        for (String ticker : tickerSet) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Daily price fetch interrupted — stopping early");
                return;
            }
            LocalDate latest = priceRepo.getLatestPriceDate(ticker);
            // First run backfills; subsequent runs include the latest stored day so its close
            // gets refreshed if it was captured mid-session.
            LocalDate from = (latest == null) ? today.minusYears(LOOKBACK_YEARS) : latest;
            if (from.isAfter(today)) {
                log.info("Skipped {} — already up to date", ticker);
                continue;
            }
            YahooPriceFetcher.DailySeries series = fetcher.fetchSeries(ticker, from, today);
            List<PriceBar> bars = series.bars();
            int touched = priceRepo.upsertPriceBars(bars);
            // A first-ever fetch spans the whole lookback, so its empty dividend list is evidence;
            // an incremental one covers days, so it may only add to what is already stored.
            captureDividends(ticker, series, from, latest == null);
            log.info("Fetched {} rows for {} ({} written)", bars.size(), ticker, touched);
            // Only on the first-ever fetch for a ticker: an empty response means the ticker is
            // wrong, and a populated one still deserves a note if we reached it by identity.
            if (latest == null) {
                // Only an identity-resolved ticker is also the internal symbol; for a mapped
                // one the job never sees the left-hand side, so it reports the ticker alone.
                if (bars.isEmpty()) PriceFetchSupport.warnNoPriceData(log,
                        tickers.resolvedByIdentity(ticker) ? ticker : null, ticker);
                else if (tickers.resolvedByIdentity(ticker)) PriceFetchSupport.noteIdentityTicker(log, ticker);
            }
            PriceFetchSupport.sleep(THROTTLE_MS);
        }
        // Only here, where the whole universe has been walked. The interrupt above returns
        // without marking, because a half-finished pass leaves exactly the gap this marker is
        // there to notice — and an unmarked evening is retried, which costs a pass, while a
        // wrongly marked one is skipped until tomorrow, which costs a day of closes.
        kv.putInstant(LAST_RUN_KEY, Instant.now());
    }

    /**
     * Re-fetches the full {@value #LOOKBACK_YEARS}-year window for every ticker in the universe
     * and upserts. Each Yahoo response is reparsed end-to-end, which is the only way to refresh
     * historical {@code adj_close} after a new dividend or split — Yahoo's bundled adjclose
     * field is unreliable for UK listings, so the fetcher derives total-return adj_close from the
     * event stream on every call. Triggered manually via the dashboard; not on a cron schedule.
     *
     * @return number of tickers actually refreshed
     */
    public int runFullRebuild() {
        LocalDate today = LocalDate.now();
        LocalDate defaultFrom = today.minusYears(LOOKBACK_YEARS);
        Set<String> tickerSet = PriceFetchSupport.tickersToFetch(cashRepo, tickers, kv);
        int refreshed = 0;
        for (String ticker : tickerSet) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Price rebuild interrupted — stopping early");
                return refreshed;
            }
            // Cover anything already stored. If we have rows older than the lookback cutoff
            // (early portfolio history that the first backfill caught), we must refetch them
            // too — otherwise their stale adj_close survives the rebuild and pollutes
            // total-return math at the start of the timeline.
            LocalDate earliest = priceRepo.getEarliestPriceDate(ticker);
            LocalDate from = (earliest != null && earliest.isBefore(defaultFrom)) ? earliest : defaultFrom;
            YahooPriceFetcher.DailySeries series = fetcher.fetchSeries(ticker, from, today);
            List<PriceBar> bars = series.bars();
            int touched = priceRepo.upsertPriceBars(bars);
            captureDividends(ticker, series, from, true);
            log.info("Rebuild: {} from {} → {} rows ({} written)", ticker, from, bars.size(), touched);
            if (!bars.isEmpty()) refreshed++;
            PriceFetchSupport.sleep(THROTTLE_MS);
        }
        return refreshed;
    }

    /**
     * Backfills the full {@value #LOOKBACK_YEARS}-year window for one specific symbol — used by
     * the what-if simulator when a basket symbol has no rows in {@code price_history} yet, so the
     * simulation can value it instead of leaving the allocation as GBP cash. {@code symbol} is
     * the internal symbol (not the Yahoo ticker) — gilts are no-ops here since they aren't on
     * Yahoo. Returns the row count written; 0 means Yahoo had nothing or rejected the ticker.
     */
    public int fetchSingle(String symbol) {
        return fetchSingle(symbol, LOOKBACK_YEARS);
    }

    /**
     * Drop any recorded "Yahoo rejected this" verdict for {@code symbol}. Called when its mapping
     * is removed: the verdict names the ticker that failed, and once the mapping behind it is gone
     * that sentence is about a ticker the app no longer resolves to. Better no diagnosis than a
     * stale one — the next fetch writes a fresh one.
     */
    public void forgetUnresolved(String symbol) {
        unresolved.clear(symbol);
    }

    /**
     * Watchlist-add helper: only backfill when the symbol's daily history is missing or stale.
     * A name that's already covered — held, or removed-then-re-added the same day — skips the
     * ~{@value #LOOKBACK_YEARS}-year re-fetch entirely, so the add's single small SQLite write
     * doesn't contend with a redundant multi-thousand-row upsert on the one writer (the source of
     * the "add once, then everything blocks until refresh" contention). "Fresh" = a stored close
     * within {@value #FRESH_LOOKBACK_DAYS} days (spans a Fri→Mon weekend plus a holiday). Bonds are
     * no-ops (not on Yahoo). Returns the row count written; 0 means skipped or nothing to fetch.
     */
    public int fetchSingleIfStale(String symbol) {
        if (com.portfolio.domain.Instruments.isBond(symbol)) return 0;
        String ticker = tickers.tickerFor(symbol);
        LocalDate latest = priceRepo.getLatestPriceDate(ticker);
        if (latest != null && !latest.isBefore(LocalDate.now().minusDays(FRESH_LOOKBACK_DAYS))) {
            log.info("fetchSingle skipped {} — history already fresh (latest {})", symbol, latest);
            return 0;
        }
        return fetchSingle(symbol);
    }

    /**
     * Same as {@link #fetchSingle(String)} but with an explicit lookback depth — the trend-filter
     * backfill needs ~45 years for {@code ^NDX}/{@code ^IRX} (Yahoo serves the Nasdaq-100 index
     * from 1985 and the 13-week T-bill yield from the 1960s), deeper than the standard window.
     * Once the rows exist, the nightly incremental run maintains them from the latest stored day,
     * and {@code runFullRebuild} already refetches from the earliest stored row when it predates
     * the default window.
     */
    public int fetchSingle(String symbol, int lookbackYears) {
        if (com.portfolio.domain.Instruments.isBond(symbol)) {
            log.info("fetchSingle skipped {} — bonds are not on Yahoo", symbol);
            return 0;
        }
        String ticker = tickers.tickerFor(symbol);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusYears(lookbackYears);
        YahooPriceFetcher.DailySeries series = fetcher.fetchSeries(ticker, from, today);
        List<PriceBar> bars = series.bars();
        int touched = priceRepo.upsertPriceBars(bars);
        captureDividends(ticker, series, from, true);
        log.info("fetchSingle {} → {}: {} rows ({} written)", symbol, ticker, bars.size(), touched);
        if (bars.isEmpty() && priceRepo.getLatestPriceDate(ticker) == null) {
            PriceFetchSupport.warnNoPriceData(log, symbol, ticker);
            // Leave a verdict behind. Without one the caller cannot distinguish this from a
            // backfill still running, and the watchlist row and the limit probe both said "wait"
            // indefinitely. The search costs one request and only on a failure, and it is what
            // turns the report from a dead end into the mapping to make (EQQU → EQQU.L).
            unresolved.record(symbol, ticker, fetcher.search(symbol, SUGGESTION_COUNT));
        } else if (!bars.isEmpty()) {
            unresolved.clear(symbol);   // it prices now — a corrected mapping, or a new listing
        }
        return touched;
    }
}
