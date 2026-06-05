package com.portfolio.application;

import com.portfolio.adapter.GiltPriceFetcher;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Hourly gilt-price refresh. One HTTP request returns every TG and TR row on dividenddata, which
 * we store in {@code price_intraday} alongside Yahoo intraday closes. Symbols match the
 * {@code "GILT {coupon}% {year}"} form produced by {@code AJBellSippParser.extractBondId}, so
 * the dashboard's RT-value lookup joins without any ticker translation.
 *
 * <p>Hourly is intentional — gilt prices move slowly, and the page itself is not updated
 * in real time. Retention is owned by {@link IntradayPriceFetchJob}, which prunes shared
 * rows from {@code price_intraday} after each of its ticks.
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
        int saved = intradayRepo.saveIntradayBars(bars);
        int rolled = priceHistoryRepo.upsertPriceBars(toDailyBars(bars));
        log.info("Gilt prices — {} rows ({} new intraday, {} daily upsert)", bars.size(), saved, rolled);
    }

    private static List<PriceBar> toDailyBars(List<IntradayBar> bars) {
        return bars.stream().map(b -> {
            LocalDate today = b.ts().atZone(LONDON).toLocalDate();
            return new PriceBar(b.symbol(), today, null, null, null,
                    b.close(), b.close(), null, b.currency());
        }).toList();
    }
}
