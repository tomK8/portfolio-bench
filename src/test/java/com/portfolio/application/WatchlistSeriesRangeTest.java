package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.WatchlistService.Series;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the high/low the price popup reports for whichever window is selected
 * ({@link WatchlistService#series}). The figure is deliberately <em>not</em> the extreme of the
 * drawn line: the line is closes, and the window's real high is in the stored OHLC. What these
 * tests pin down is the handling of everything that isn't a clean bar — a zeroed OHLC row, a
 * close printed outside its own day's range, a gilt with no range at all, and the session in
 * progress, which has no daily bar yet.
 */
class WatchlistSeriesRangeTest {

    @TempDir
    Path dbDir;

    private PriceHistoryRepository priceRepo;
    private IntradayPriceRepository intradayRepo;

    private static PriceBar ohlc(String sym, LocalDate date, Double high, Double low, double close) {
        return new PriceBar(sym, date, null, high, low, close, close, 1.0, null, "GBP");
    }

    private WatchlistService service() {
        var cf = new JdbcConnectionFactory(dbDir);
        var kv = new KeyValueStore(dbDir);
        priceRepo = new PriceHistoryRepository(cf);
        intradayRepo = new IntradayPriceRepository(cf);
        return new WatchlistService(new WatchlistRepository(cf, kv), priceRepo, intradayRepo,
                new FundamentalsRepository(cf), new CashTransactionRepository(cf, kv),
                () -> Map.of(), new YahooTickerMap(), new UnresolvedSymbols(kv));
    }

    /**
     * The headline case, and the reason this isn't just {@code Math.max} over the plotted points:
     * a day that closed at 100 but traded up to 130 has a 130 high, and the chart — which plots
     * closes — never shows it.
     */
    @Test
    void theWindowHighIsTheTradedHighNotTheHighestClose() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("AAPL", today.minusDays(3), 130.0, 95.0, 100.0),
                ohlc("AAPL", today.minusDays(2), 115.0, 99.0, 110.0),
                ohlc("AAPL", today.minusDays(1), 118.0, 90.0, 112.0)));

        Series s = svc.series("AAPL", "30D");

        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("130")),
                "high must come from the day's range, not its close");
        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("90")));
        assertEquals(today.minusDays(3).toString(), s.range().highDate());
        assertEquals(today.minusDays(1).toString(), s.range().lowDate());
        assertFalse(s.range().highCloseOnly(), "these bars carry a real traded range");
    }

    /**
     * Yahoo intermittently writes a whole OHLC row as {@code 0.0} (24 rows across 16 {@code .L}
     * listings on one 2026 date). Taken at face value that prints the window's low as a crash to
     * zero — the "absent is never zero" rule, in the one place a user would read it as a price.
     */
    @Test
    void aZeroedOhlcRowContributesItsCloseNotAZeroLow() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("EQQQ.L", today.minusDays(2), 560.0, 540.0, 550.0),
                ohlc("EQQQ.L", today.minusDays(1), 0.0, 0.0, 538.0)));   // the glitch row

        Series s = svc.series("EQQQ.L", "30D");

        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("538")),
                "a zeroed row must fall back to its close, never report a low of 0");
        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("560")));
    }

    /**
     * ~0.4% of LSE ETF bars close just outside their own reported range — the closing auction
     * prints against a different book than the intraday range. Reporting the high alone would
     * draw a chart line poking above its own stated high, which reads as a bug.
     */
    @Test
    void aCloseOutsideItsOwnDaysRangeStillWidensTheBand() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("SGBX.L", today.minusDays(2), 29062.0, 28896.0, 29133.0),   // close above high
                ohlc("SGBX.L", today.minusDays(1), 29100.0, 28900.0, 28800.0))); // close below low

        Series s = svc.series("SGBX.L", "30D");

        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("29133")),
                "the band must contain every plotted close");
        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("28800")));
    }

    /**
     * Gilts are scraped from HL as a daily close with no OHLC. The range is then a close-to-close
     * one and can sit inside the real high/low, so it is flagged rather than passed off as the
     * traded range.
     */
    @Test
    void anInstrumentWithNoStoredRangeSaysItsFigureIsCloseBased() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("GILT 4.25% 2032", today.minusDays(2), null, null, 96.10),
                ohlc("GILT 4.25% 2032", today.minusDays(1), null, null, 97.40)));

        Series s = svc.series("GILT 4.25% 2032", "1Y");

        assertTrue(s.range().highCloseOnly(), "no OHLC anywhere in the window");
        assertTrue(s.range().lowCloseOnly());
        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("97.4")));
        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("96.1")));
    }

    /**
     * An intraday window's range comes from the completed sessions' daily bars <em>plus</em> the
     * session in progress, which has no daily bar until the evening fetch. Miss the second half
     * and the 1D/5D high would freeze at yesterday's while the user watches today's print past it.
     */
    @Test
    void anIntradayWindowCombinesStoredSessionsWithTodaysLiveBars() {
        var svc = service();
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        priceRepo.savePriceBars(List.of(
                ohlc("AAPL", today.minusDays(1), 205.0, 195.0, 200.0)));         // yesterday, complete
        intradayRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", now.minusSeconds(7200), 201.0, null, "USD"),
                new IntradayBar("AAPL", now.minusSeconds(3600), 212.0, null, "USD"),  // today's high
                new IntradayBar("AAPL", now.minusSeconds(60), 208.0, null, "USD")));

        Series s = svc.series("AAPL", "3D");

        assertEquals("intraday", s.granularity());
        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("212")),
                "today's live high must beat the stored session high");
        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("195")),
                "yesterday's traded low must survive — minute closes never saw it");
        assertFalse(s.range().lowCloseOnly(), "yesterday's low came from a stored session range");
    }

    /**
     * The regression the first draft had: the intraday span was read off the bars that came back
     * rather than the window asked for, so a symbol whose 1-minute backfill started this morning
     * reported a "5D high" measured over one session. A window's label and the span its high is
     * measured over have to be the same thing.
     */
    @Test
    void anIntradayWindowSpansWhatItsLabelClaimsNotWhatIntradayCovers() {
        var svc = service();
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        priceRepo.savePriceBars(List.of(
                ohlc("AAPL", today.minusDays(4), 260.0, 250.0, 255.0),   // inside 5D, no intraday
                ohlc("AAPL", today.minusDays(1), 205.0, 195.0, 200.0)));
        intradayRepo.saveIntradayBars(List.of(                            // today only
                new IntradayBar("AAPL", now.minusSeconds(60), 208.0, null, "USD")));

        Series s = svc.series("AAPL", "5D");

        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("260")),
                "a session inside the window counts even with no intraday bars of its own");
        assertEquals(today.minusDays(4).toString(), s.range().highDate());
    }

    /** 1D is the latest session, so its range is that session's — not the last 24 hours'. */
    @Test
    void oneDayIsScopedToTheLatestSessionAlone() {
        var svc = service();
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        priceRepo.savePriceBars(List.of(
                ohlc("AAPL", today.minusDays(1), 305.0, 95.0, 200.0)));   // yesterday ranged wildly
        intradayRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", now.minusSeconds(3600), 201.0, null, "USD"),
                new IntradayBar("AAPL", now.minusSeconds(60), 207.0, null, "USD")));

        Series s = svc.series("AAPL", "1D");

        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("207")),
                "yesterday's range must not leak into today's");
        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("201")));
    }

    /**
     * A failed HL scrape has written a {@code 0.0} close (6 rows across the {@code GILT 0%}
     * strips). Read as a price that is a crash to nothing, and it is the window's low every time
     * — the same phantom the zeroed-OHLC row makes, from the other column.
     */
    @Test
    void aZeroCloseIsAbsentNotAPriceOfZero() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("GILT 0% 2027", today.minusDays(3), null, null, 84.40),
                ohlc("GILT 0% 2027", today.minusDays(2), null, null, 0.0),     // failed scrape
                ohlc("GILT 0% 2027", today.minusDays(1), null, null, 83.10)));

        Series s = svc.series("GILT 0% 2027", "1Y");

        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("83.1")),
                "a zero close must not become the window's low");
        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("84.4")));
    }

    /**
     * ^IRX is a yield, not a price, and really did print below zero in March 2020 — so only an
     * exact zero counts as absent. A blanket "reject non-positive" would delete real history.
     */
    @Test
    void aNegativePriceIsRealDataAndSurvives() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("^IRX", today.minusDays(2), null, null, 0.05),
                ohlc("^IRX", today.minusDays(1), null, null, -0.03)));

        Series s = svc.series("^IRX", "1Y");

        assertEquals(0, s.range().low().compareTo(new java.math.BigDecimal("-0.03")));
    }

    /**
     * The flag means "end-of-day closes were all we had". Today's daily bar does not exist until
     * the evening fetch, but a few hundred 1-minute samples are a sound range — asterisking every
     * 1D view during market hours would train the reader to ignore the gilt case that matters.
     */
    @Test
    void aLiveSessionBuiltFromMinuteBarsIsNotFlaggedCloseBased() {
        var svc = service();
        Instant now = Instant.now();
        intradayRepo.saveIntradayBars(List.of(
                new IntradayBar("AAPL", now.minusSeconds(3600), 201.0, null, "USD"),
                new IntradayBar("AAPL", now.minusSeconds(60), 207.0, null, "USD")));

        Series s = svc.series("AAPL", "1D");

        assertFalse(s.range().highCloseOnly(), "minute samples are intra-session evidence");
        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("207")));
    }

    /**
     * Windows have to nest: a high made this morning belongs to 5D <em>and</em> 30D. The daily
     * windows read daily bars, and today's does not exist until the evening fetch — so without
     * folding the live session in, "30D high" sits below "5D high" on the same screen.
     */
    @Test
    void aDailyWindowSeesTheSessionInProgressToo() {
        var svc = service();
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        priceRepo.savePriceBars(List.of(
                ohlc("AAPL", today.minusDays(5), 205.0, 195.0, 200.0)));
        intradayRepo.saveIntradayBars(List.of(                            // today prints a new high
                new IntradayBar("AAPL", now.minusSeconds(120), 240.0, null, "USD")));

        java.math.BigDecimal shortWindow = svc.series("AAPL", "5D").range().high();
        java.math.BigDecimal longWindow = svc.series("AAPL", "30D").range().high();

        assertEquals(0, longWindow.compareTo(new java.math.BigDecimal("240")),
                "the 30D high must see today's print");
        assertTrue(longWindow.compareTo(shortWindow) >= 0,
                "a longer window can never report a lower high than a shorter one inside it");
    }

    /**
     * The mark is judged at the bar that set the extreme, not over the window. Folding the live
     * session into every window (so they nest) meant a gilt with one day of scraped intraday
     * stopped flagging its 1Y high — one day of sampling vouching for the other 364.
     */
    @Test
    void oneLiveSessionDoesNotVouchForAWindowOfCloses() {
        var svc = service();
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        priceRepo.savePriceBars(List.of(                                  // closes only, all year
                ohlc("GILT 0.875% 2033", today.minusDays(200), null, null, 79.92),
                ohlc("GILT 0.875% 2033", today.minusDays(100), null, null, 75.44)));
        intradayRepo.saveIntradayBars(List.of(                            // today's HL scrape
                new IntradayBar("GILT 0.875% 2033", now.minusSeconds(120), 77.10, null, "GBP")));

        Series s = svc.series("GILT 0.875% 2033", "1Y");

        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("79.92")));
        assertTrue(s.range().highCloseOnly(), "the high is a close, whatever today was sampled at");
        assertTrue(s.range().lowCloseOnly());
    }

    /**
     * ~0.4% of LSE ETF bars close outside their own reported range. That close is a real print on
     * a day we do know the range of — marking it "close only" would asterisk a sound figure.
     */
    @Test
    void aCloseOnAnOhlcBackedDayIsNotMarkedCloseOnly() {
        var svc = service();
        LocalDate today = LocalDate.now();
        priceRepo.savePriceBars(List.of(
                ohlc("SGBX.L", today.minusDays(1), 29062.0, 28896.0, 29133.0)));   // close above high

        Series s = svc.series("SGBX.L", "30D");

        assertEquals(0, s.range().high().compareTo(new java.math.BigDecimal("29133")));
        assertFalse(s.range().highCloseOnly(), "the day's range is known — the close just exceeded it");
    }

    /** A symbol with nothing stored reports no range at all — not a zero one. */
    @Test
    void anEmptyWindowHasNoRange() {
        var svc = service();

        Series s = svc.series("NOTHING", "5D");

        assertNull(s.range().high());
        assertNull(s.range().low());
        assertFalse(s.range().highCloseOnly(), "nothing known is not a close-based figure");
    }
}
