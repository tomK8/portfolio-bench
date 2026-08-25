package com.portfolio.application;

import com.portfolio.adapter.GiltPriceFetcher;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hourly gilt-price refresh. One HTTP request returns every conventional gilt on HL's public
 * gilt-prices page, which we store in {@code price_intraday} alongside Yahoo intraday closes. Symbols match the
 * {@code "GILT {coupon}% {year}"} form produced by {@code AJBellSippParser.extractBondId}, so
 * the dashboard's RT-value lookup joins without any ticker translation.
 *
 * <p>Hourly is intentional — gilt prices move slowly, and the page itself is not updated
 * in real time. Most ticks return prices identical to the prior tick, so we drop bars whose
 * latest stored close on the same London date hasn't changed; first bar of a new date is
 * always kept so the 7-day prune can't leave a symbol with nothing. Retention is owned by
 * {@link IntradayPriceFetchJob}, which prunes shared rows from {@code price_intraday} after
 * each of its ticks.
 *
 * <p>Each tick also publishes the page's SEDOL → gilt-id mapping to the {@code gilt_sedols} KV
 * entry, which is what lets {@code IICashStatementParser} recognise a gilt in a cash statement
 * that names it only by a broker code and a SEDOL. The map is <b>merged</b>, never replaced: a
 * gilt drops off HL's page the day it matures, and a cash file re-imported after that must still
 * resolve. It costs no extra HTTP — the SEDOLs are in the response this job already makes.
 *
 * <p>Each tick also <em>upserts</em> today's row in {@code price_history} (one row per gilt) so
 * the daily series accumulates from intraday data even when the app isn't running all day —
 * last-write-wins, and the manual Tradeweb batch import can overwrite either with authoritative
 * close prices later. That rollup is skipped at weekends ({@link #toDailyBars}); the
 * {@code price_intraday} write is not, because the RT lookup legitimately wants the newest gilt
 * price whatever day it is, and those rows are pruned after 15 days anyway.
 */
public class GiltPriceFetchJob {

    private static final Logger log = LoggerFactory.getLogger(GiltPriceFetchJob.class);
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** KV entry holding the merged SEDOL → {@code "GILT {coupon}% {year}"} mapping. */
    public static final String SEDOL_MAP_KEY = "gilt_sedols";

    private final GiltPriceFetcher fetcher;
    private final IntradayPriceRepository intradayRepo;
    private final PriceHistoryRepository priceHistoryRepo;
    private final KeyValueStore kv;

    public GiltPriceFetchJob(GiltPriceFetcher fetcher, IntradayPriceRepository intradayRepo,
                             PriceHistoryRepository priceHistoryRepo) {
        this(fetcher, intradayRepo, priceHistoryRepo, null);
    }

    /** @param kv where the SEDOL map is published; null means "don't publish it". */
    public GiltPriceFetchJob(GiltPriceFetcher fetcher, IntradayPriceRepository intradayRepo,
                             PriceHistoryRepository priceHistoryRepo, KeyValueStore kv) {
        this.fetcher = fetcher;
        this.intradayRepo = intradayRepo;
        this.priceHistoryRepo = priceHistoryRepo;
        this.kv = kv;
    }

    public void run() {
        GiltPriceFetcher.Snapshot snapshot = fetcher.fetch();
        List<IntradayBar> bars = snapshot.bars();
        List<IntradayBar> changed = dropUnchangedSameDay(bars);
        int saved = intradayRepo.saveIntradayBars(changed);
        int rolled = priceHistoryRepo.upsertPriceBars(toDailyBars(bars));
        int sedols = publishSedols(snapshot.sedolToSymbol());
        log.info("Gilt prices — {} fetched, {} unchanged today, {} new intraday, {} daily upsert, "
                        + "{} SEDOLs known",
                bars.size(), bars.size() - changed.size(), saved, rolled, sedols);
    }

    /**
     * Merges this scrape's SEDOLs into the stored map and returns the resulting size. A matured
     * gilt stops appearing on the page, so entries are only ever added or updated — dropping one
     * would silently un-resolve a cash statement that is still importable.
     */
    private int publishSedols(Map<String, String> scraped) {
        if (kv == null) return 0;
        Map<String, String> merged = new LinkedHashMap<>(kv.getStringMap(SEDOL_MAP_KEY));
        if (scraped.isEmpty() || merged.entrySet().containsAll(scraped.entrySet())) return merged.size();
        merged.putAll(scraped);
        kv.putStringMap(SEDOL_MAP_KEY, merged);
        return merged.size();
    }

    /**
     * The source page refreshes infrequently — most hourly ticks return prices identical to what's
     * already stored. Keep one row per (symbol, London date) when the price hasn't moved, and
     * only add another for the same day if the close has actually changed.
     */
    private List<IntradayBar> dropUnchangedSameDay(List<IntradayBar> bars) {
        if (bars.isEmpty()) return bars;
        Map<String, IntradayPrice> latest = intradayRepo.loadLatestIntradayPrices(
                bars.stream().map(IntradayBar::symbol).toList());
        return bars.stream().filter(b -> {
            IntradayPrice prev = latest.get(b.symbol());
            if (prev == null) return true;
            LocalDate prevDate = prev.ts().atZone(LONDON).toLocalDate();
            LocalDate barDate = b.ts().atZone(LONDON).toLocalDate();
            if (!prevDate.equals(barDate)) return true;
            return Double.compare(prev.close(), b.close()) != 0;
        }).toList();
    }

    /**
     * Today's scrape as a daily bar — <b>trading days only</b>.
     *
     * <p>The job runs hourly, seven days a week, and HL's page still serves Friday's prices all
     * weekend. Without this filter each Saturday and Sunday the app happened to be running minted
     * a full set of gilt rows in {@code price_history} carrying a stale close, which is not a
     * price that exists: the LSE is shut and a gilt has no Saturday close. 717 such rows across
     * 15 weekend dates had accumulated by 2026-08-20, and because the Returns grid draws its date
     * axis from the union of every symbol's stored dates, three held gilts were enough to put
     * weekend columns across the whole panel — every equity showing "—" in them.
     *
     * <p>The date comes from each bar's own timestamp rather than the clock, so a tick either
     * side of midnight is judged on the day it actually belongs to.
     *
     * <p>Known gap: UK bank holidays are still written, since neither HL's table nor our parse of
     * it carries a price date to key off, and a hand-maintained holiday calendar is exactly the
     * kind of file that goes stale unnoticed. That is ~8 duplicate rows a year against ~104, and
     * a Tradeweb import overwrites any of them authoritatively.
     */
    private static List<PriceBar> toDailyBars(List<IntradayBar> bars) {
        return bars.stream()
                .filter(b -> isTradingDay(b.ts().atZone(LONDON).toLocalDate()))
                .map(b -> {
                    LocalDate day = b.ts().atZone(LONDON).toLocalDate();
                    return new PriceBar(b.symbol(), day, null, null, null,
                            b.close(), b.close(), 1.0, null, b.currency());
                }).toList();
    }

    /** Gilts trade Monday–Friday on the LSE. See the caveat on {@link #toDailyBars}. */
    static boolean isTradingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }
}
