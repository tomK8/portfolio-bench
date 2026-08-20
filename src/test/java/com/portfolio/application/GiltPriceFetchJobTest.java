package com.portfolio.application;

import com.portfolio.adapter.GiltPriceFetcher;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiltPriceFetchJobTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final String SYMBOL = "GILT 4.25% 2032";

    @TempDir
    Path dbDir;

    /** Returns one canned bar stamped at whatever instant the test asks for. */
    private static class FakeFetcher extends GiltPriceFetcher {
        private final Instant ts;

        FakeFetcher(Instant ts) {
            this.ts = ts;
        }

        @Override
        public List<IntradayBar> fetch() {
            return List.of(new IntradayBar(SYMBOL, ts, 97.21, null, "GBP"));
        }
    }

    private List<PriceBar> runAt(Instant ts) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        PriceHistoryRepository prices = new PriceHistoryRepository(cf);
        new GiltPriceFetchJob(new FakeFetcher(ts), new IntradayPriceRepository(cf), prices).run();
        return prices.getPriceHistory(SYMBOL, LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"));
    }

    private static Instant londonNoon(String isoDate) {
        return LocalDateTime.of(LocalDate.parse(isoDate), java.time.LocalTime.NOON)
                .atZone(LONDON).toInstant();
    }

    @Test
    void weekdayScrapeIsRolledIntoTheDailySeries() {
        // 2026-07-03 is a Friday.
        List<PriceBar> bars = runAt(londonNoon("2026-07-03"));
        assertEquals(1, bars.size());
        assertEquals(LocalDate.parse("2026-07-03"), bars.get(0).date());
    }

    @Test
    void saturdayScrapeIsNotRolledIntoTheDailySeries() {
        // HL serves Friday's prices all weekend, but a gilt has no Saturday close — storing one
        // put weekend columns across the whole Returns grid, since its date axis is the union of
        // every symbol's stored dates.
        assertTrue(runAt(londonNoon("2026-07-04")).isEmpty());
    }

    @Test
    void sundayScrapeIsNotRolledIntoTheDailySeries() {
        assertTrue(runAt(londonNoon("2026-07-05")).isEmpty());
    }

    @Test
    void theWeekendGuardJudgesTheBarsOwnDateNotTheClock() {
        // A tick just before midnight on a Sunday belongs to Sunday, not to the Monday it is
        // about to become.
        Instant lateSunday = LocalDateTime.of(LocalDate.parse("2026-07-05"), java.time.LocalTime.of(23, 55))
                .atZone(LONDON).toInstant();
        assertTrue(runAt(lateSunday).isEmpty());
    }

    @Test
    void isTradingDayCoversTheWholeWeek() {
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-06")));   // Mon
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-07")));   // Tue
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-08")));   // Wed
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-09")));   // Thu
        assertTrue(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-10")));   // Fri
        assertFalse(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-11")));  // Sat
        assertFalse(GiltPriceFetchJob.isTradingDay(LocalDate.parse("2026-07-12")));  // Sun
    }

    @Test
    void theIntradayWriteStillHappensAtWeekends() {
        // Deliberately unchanged: the RT lookup wants the newest gilt price whatever day it is,
        // and those rows are pruned after 15 days regardless.
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        IntradayPriceRepository intraday = new IntradayPriceRepository(cf);
        new GiltPriceFetchJob(new FakeFetcher(londonNoon("2026-07-04")), intraday,
                new PriceHistoryRepository(cf)).run();
        assertFalse(intraday.loadLatestIntradayPrices(List.of(SYMBOL)).isEmpty());
    }
}
