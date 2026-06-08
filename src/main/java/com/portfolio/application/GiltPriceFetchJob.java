package com.portfolio.application;

import com.portfolio.adapter.GiltPriceFetcher;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Hourly gilt-price refresh. One HTTP request returns every TG and TR row on dividenddata, which
 * we store in {@code price_intraday} alongside Yahoo intraday closes. Symbols match the
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
 * <p>Each tick also <em>upserts</em> today's row in {@code price_history} (one row per gilt) so
 * the daily series accumulates from intraday data even when the app isn't running all day —
 * last-write-wins, and the manual Tradeweb batch import can overwrite either with authoritative
 * close prices later.
 */
public class GiltPriceFetchJob {

    private static final Logger log = LoggerFactory.getLogger(GiltPriceFetchJob.class);
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final GiltPriceFetcher fetcher;
    private final IntradayPriceRepository intradayRepo;
    private final PriceHistoryRepository priceHistoryRepo;

    public GiltPriceFetchJob(GiltPriceFetcher fetcher, IntradayPriceRepository intradayRepo,
                             PriceHistoryRepository priceHistoryRepo) {
        this.fetcher = fetcher;
        this.intradayRepo = intradayRepo;
        this.priceHistoryRepo = priceHistoryRepo;
    }

    public void run() {
        List<IntradayBar> bars = fetcher.fetch();
        List<IntradayBar> changed = dropUnchangedSameDay(bars);
        int saved = intradayRepo.saveIntradayBars(changed);
        int rolled = priceHistoryRepo.upsertPriceBars(toDailyBars(bars));
        log.info("Gilt prices — {} fetched, {} unchanged today, {} new intraday, {} daily upsert",
                bars.size(), bars.size() - changed.size(), saved, rolled);
    }

    /**
     * Dividenddata refreshes infrequently — most hourly ticks return prices identical to what's
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

    private static List<PriceBar> toDailyBars(List<IntradayBar> bars) {
        return bars.stream().map(b -> {
            LocalDate today = b.ts().atZone(LONDON).toLocalDate();
            return new PriceBar(b.symbol(), today, null, null, null,
                    b.close(), b.close(), 1.0, null, b.currency());
        }).toList();
    }
}
