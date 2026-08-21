package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.OpenOrdersService.OrdersReport;
import com.portfolio.domain.PriceStats;
import com.portfolio.domain.VolForecast;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.parser.AJBellOrdersParser;
import com.portfolio.parser.IIOrdersParser;
import com.portfolio.parser.RothOrdersParser;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OpenOrdersServiceTest {

    // 1 GBP = 1.25 USD (units of foreign ccy per GBP, the app convention → native ÷ rate = GBP).
    private static final FxRateProvider FX =
            () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));

    @TempDir
    Path outDir;

    private OpenOrdersService service(Path inputDir) {
        // A temp (empty) DB — the live-price enrichment finds no quotes and leaves those columns null,
        // which is all these tests need. The repos create their tables in their constructors.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(inputDir.resolve("db"));
        return new OpenOrdersService(new HoldingFileLocator(inputDir),
                List.of(new AJBellOrdersParser(), new IIOrdersParser(), new RothOrdersParser()),
                FX, new ExcelReportWriter(), outDir,
                new IntradayPriceRepository(connections), new PriceHistoryRepository(connections),
                new YahooTickerMap(), new KeyValueStore(inputDir.resolve("db")));
    }

    @Test
    void theHorizonIsInSessions_theSameUnitAsTheFixedTouchColumn() {
        // P(5d) is trading sessions, σ scales by √sessions, and the daily vol comes from bars that
        // only exist on trading days — so N is sessions too. Set the horizon to 5 and it must
        // reproduce the row's P(5d) exactly, which is the check a reader can make on screen. Note
        // the σ that goes in is the *forecast at that horizon*, so this only holds because both
        // columns resolve their σ the same way: it is the guard against a second vol model.
        OpenOrdersService svc = new OpenOrdersService(new HoldingFileLocator(outDir), List.of(),
                FX, new ExcelReportWriter(), outDir, null, null, new YahooTickerMap(), null);
        BigDecimal cur = new BigDecimal("523.96"), lim = new BigDecimal("560");
        VolForecast.Inputs vols = new VolForecast.Inputs(new BigDecimal("0.0120"),
                new BigDecimal("0.01095087"), new BigDecimal("0.0105"), new BigDecimal("0.0090"));
        for (int n : new int[]{5, 20}) {
            BigDecimal sigmaEff = VolForecast.resolve(vols, n, VolForecast.Config.DEFAULT).sigmaEff();
            assertEquals(PriceStats.touchProbability(PriceStats.sigmaDistance(cur, lim, sigmaEff, n)),
                    svc.touchEstimate(cur, lim, vols, n, VolForecast.Config.DEFAULT).probability(),
                    "N = " + n + " sessions");
        }
    }

    @Test
    void theGtcAssumptionDoesNotTripTheOverExtendedMark() {
        // P(N) runs to each order's own expiry; the only horizon the panel invents is the one for
        // an order with no deadline of its own, and that assumption must not flag itself.
        assertEquals(VolForecast.Config.DEFAULT_GTC_HORIZON_SESSIONS,
                OpenOrdersService.DEFAULT_HORIZON_SESSIONS);
        // A mark that fires on every GTC row on every load tells the reader nothing. The driftless
        // caveat that genuinely applies past a quarter is stated once in the panel footnote; the
        // per-row mark is reserved for a horizon pushed materially past that.
        assertTrue(OpenOrdersService.DEFAULT_HORIZON_SESSIONS <= OpenOrder.LONG_HORIZON_SESSIONS,
                "the GTC assumption must not mark itself as over-extended");
    }

    @Test
    void sessionsToExpiryTranslatesTheOrdersOwnDeadline() {
        // Display only — it is how the view offers "expires 2026-08-28, about 41 sessions away"
        // without the probability itself ever touching a calendar day.
        Integer s = OpenOrdersService.sessionsToExpiry(LocalDate.now().plusDays(30));
        assertNotNull(s);
        assertTrue(s >= 20 && s <= 23, "30 calendar days ≈ 21 sessions, got " + s);
        // No forward deadline to name: GTC, or an expiry today / already past.
        assertNull(OpenOrdersService.sessionsToExpiry(null));
        assertNull(OpenOrdersService.sessionsToExpiry(LocalDate.now()));
        assertNull(OpenOrdersService.sessionsToExpiry(LocalDate.now().minusDays(3)));
    }

    @Test
    void touchEstimateRePricesOverAnArbitraryHorizon() {
        OpenOrdersService svc = new OpenOrdersService(new HoldingFileLocator(outDir), List.of(),
                FX, new ExcelReportWriter(), outDir, null, null, new YahooTickerMap(), null);
        BigDecimal cur = new BigDecimal("100"), lim = new BigDecimal("95");
        VolForecast.Inputs vols = new VolForecast.Inputs(
                new BigDecimal("0.02"), new BigDecimal("0.02"), new BigDecimal("0.02"), new BigDecimal("0.02"));
        VolForecast.Config cfg = VolForecast.Config.DEFAULT;

        OpenOrdersService.TouchEstimate near = svc.touchEstimate(cur, lim, vols, 5, cfg);
        OpenOrdersService.TouchEstimate far = svc.touchEstimate(cur, lim, vols, 300, cfg);
        assertTrue(near.probability().compareTo(far.probability()) < 0,
                "same barrier, more time — the probability can only grow");
        assertTrue(near.sessions() < far.sessions());
        assertTrue(near.sigma().compareTo(far.sigma()) < 0, "σ distance shrinks as the horizon grows");

        // The re-price carries its own forecast, not just a number: a probability read off this
        // endpoint has to be judgeable against the σ that produced it, same as a rendered row.
        assertNotNull(near.sigmaEff());
        assertNotNull(near.decay());
        assertEquals(VolForecast.Source.BLEND.name(), near.volSource());
        assertNotNull(near.probabilityLow());
        assertNotNull(near.probabilityHigh());

        // A horizon pushed well past the order deadlines the column is built for is flagged rather
        // than silently served; the GTC default itself is not (see the default-horizon test above).
        assertFalse(near.longHorizon());
        assertFalse(svc.touchEstimate(cur, lim, vols, OpenOrdersService.DEFAULT_HORIZON_SESSIONS, cfg).longHorizon());
        assertTrue(far.longHorizon());

        // Clamped, not rejected — a stray keystroke can't ask for an absurd horizon.
        assertEquals(OpenOrdersService.MAX_HORIZON_SESSIONS,
                svc.touchEstimate(cur, lim, vols, 999999, cfg).sessions());
        assertEquals(1, svc.touchEstimate(cur, lim, vols, 0, cfg).sessions());

        // Missing inputs (no limit, no quote, no vol) give null rather than a fabricated number.
        assertNull(svc.touchEstimate(cur, null, vols, 5, cfg).probability());
        assertNull(svc.touchEstimate(null, lim, vols, 5, cfg).probability());
        assertNull(svc.touchEstimate(cur, lim, VolForecast.Inputs.NONE, 5, cfg).probability());
    }

    @Test
    void theHorizonIsTheOrdersOwnSessionsToExpiryAndTheGtcAssumptionOtherwise(@TempDir Path dir) throws Exception {
        // The whole point of the column: an order's deadline is a property of the order. A dated
        // AJ Bell order is priced to its expiry; a Roth GTC order, having none, takes the parameter.
        Files.writeString(dir.resolve("activeorders-1.csv"), """
                Date entered,Type,Investment,Order type,Quantity,Price,Amount,Expiry date,Status,Reference
                14/07/2026,Buy,Invesco EQQQ NASDAQ-100 ETF,Limit,50,50000p,"25000.00",%s,In progress,ref1
                """.formatted(LocalDate.now().plusDays(30).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        // Seeded under the Yahoo ticker, not the internal symbol: EQQQ maps to EQQQ.L, and a row
        // that never resolves a quote would price nothing and assert nothing.
        seedPrices(history, intraday, "EQQQ.L", "GBp", 51000.0, 52000.0);

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new AJBellOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, history, new YahooTickerMap(), new KeyValueStore(dir.resolve("db")));

        OpenOrder dated = find(service.load().orders(), "EQQQ");
        assertNotNull(dated.sessionsToExpiry());
        assertEquals(dated.sessionsToExpiry(), dated.horizonSessions(),
                "a dated order is priced to its own expiry");
        assertTrue(dated.horizonFromExpiry());

        // The GTC assumption is a parameter, not a constant: the same order set priced under a
        // different assumption moves only the rows that had no deadline of their own.
        OpenOrder gtcDefault = service.probe("EQQQ", new BigDecimal("500"), "Buy", null).hypothetical();
        assertEquals(VolForecast.Config.DEFAULT_GTC_HORIZON_SESSIONS, gtcDefault.horizonSessions());
        OpenOrder gtcTuned = service.probe("EQQQ", new BigDecimal("500"), "Buy", null,
                VolForecast.Config.of(null, 30)).hypothetical();
        assertEquals(30, gtcTuned.horizonSessions());
        assertTrue(gtcTuned.touchProbHorizon().compareTo(gtcDefault.touchProbHorizon()) < 0,
                "a shorter assumed horizon is a lower probability of touching");
    }

    @Test
    void theHeadlineVolIsForecastForTheHorizonRatherThanMeasuredOverIt(@TempDir Path dir) {
        // A name fresh off a violent stretch: RV20 well above its RV250 anchor. A trailing-window
        // model would price a long horizon at that elevated vol; the blend has to pull it back
        // toward the anchor as the horizon lengthens, and say how much weight it left on RV20.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedVolatileTail(history, intraday, "WMT");

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                FX, new ExcelReportWriter(), outDir, intraday, history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        BigDecimal limit = new BigDecimal("90.00");
        OpenOrder near = service.probe("WMT", limit, "Buy", 5).hypothetical();
        OpenOrder far = service.probe("WMT", limit, "Buy", 250).hypothetical();

        assertNotNull(near.dailyVol250(), "the anchor is loaded — the lookback covers 250 sessions");
        assertTrue(near.dailyVol20().compareTo(near.dailyVol250()) > 0, "recent vol is elevated here");
        assertTrue(far.sigmaEff().compareTo(near.sigmaEff()) < 0,
                "the long horizon must revert toward the anchor: " + far.sigmaEff() + " vs " + near.sigmaEff());
        assertTrue(near.decayWeight().doubleValue() > 0.9, "a 5-session horizon is nearly all current state");
        assertTrue(far.decayWeight().doubleValue() < 0.2, "a 250-session horizon is nearly all anchor");
        assertEquals(VolForecast.Source.BLEND.name(), far.volSource());
        // Reverting σ downward must not make a longer horizon less likely to touch — total variance
        // still grows with N, and the column would be nonsense if it didn't.
        assertTrue(far.touchProbHorizon().compareTo(near.touchProbHorizon()) > 0);
        // Near the front the blend sits inside the RV10/RV30 band, so the hover range brackets the
        // headline the way a reader expects.
        assertTrue(near.hasTouchRange());
        assertTrue(near.touchProbLow().compareTo(near.touchProbHorizon()) <= 0
                        && near.touchProbHorizon().compareTo(near.touchProbHigh()) <= 0,
                "5-session headline should sit inside its range: " + near.touchProbLow()
                        + " ≤ " + near.touchProbHorizon() + " ≤ " + near.touchProbHigh());

        // Far out it deliberately does not, and that is the finding rather than a defect: RV10 and
        // RV30 are flat trailing windows, so the range shows what the old same-length-window model
        // would have said. With a violent recent tail it says a materially higher probability than
        // the reverting forecast does — which is exactly the overstatement this change removed.
        assertTrue(far.hasTouchRange());
        assertTrue(far.touchProbHorizon().compareTo(far.touchProbLow()) < 0,
                "a reverting forecast should sit below the flat-window band at 250 sessions: "
                        + far.touchProbHorizon() + " vs " + far.touchProbLow());
    }

    @Test
    void aShortHistoryHasNoAnchorAndSaysSoRatherThanInventingOne(@TempDir Path dir) {
        // PriceStats.dailyVol shortens its window to whatever the series offers, which for a
        // freshly-listed name would hand back a 60-session "RV250" that is really current state
        // under another name — and the blend would then revert toward the very thing it corrects.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedPrices(history, intraday, "WMT", "USD", 110.0, 115.40);   // ~40 bars

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                FX, new ExcelReportWriter(), outDir, intraday, history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        OpenOrder o = service.probe("WMT", new BigDecimal("108.00"), "Buy", 90).hypothetical();
        assertNull(o.dailyVol250(), "no long-run anchor from 40 bars");
        assertEquals(VolForecast.Source.CURRENT_ONLY.name(), o.volSource());
        assertNotNull(o.touchProbHorizon(), "and it still prices — degraded, not blank");
        assertEquals(o.dailyVol20(), o.sigmaEff(), "with no anchor, σ_eff is current state itself");
    }

    @Test
    void emptyWhenNoOrderFiles(@TempDir Path dir) {
        OrdersReport report = service(dir).load();
        assertTrue(report.empty());
        assertTrue(report.orders().isEmpty());
        assertTrue(report.sources().isEmpty());
    }

    @Test
    void keepsOnlyOpenOrdersAcrossAllBrokers(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "RELX PLC","44626CC3LTC","Sell","Limit","100","2596.5","2600p","01/07/2026","22/09/2026","In progress"
                "Invesco EQQQ NASDAQ-100 ETF GBP","44626CD2R9X","Sell","Limit","50","26901.41","53800p","14/07/2026","29/07/2026","Dealt"
                """);
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                14/07/2026 16:52,27726IDT248,SELL/LIMIT,NVDA,NVIDIA Corp,51,n/a,"$11,467.84",PENDING
                14/07/2026 15:06,27726IDTNZM,SELL/LIMIT,GOOG,Alphabet Inc Class C,45,15/07/2026,"$15,885.00",EXECUTED
                """);
        writeRothWorkbook(dir.resolve("Order+Status.xlsx"));

        OrdersReport report = service(dir).load();

        assertFalse(report.empty());
        assertEquals(3, report.sources().size(), "one source line per broker with a file");

        // 1 open per broker (Dealt / EXECUTED / Canceled excluded)
        assertEquals(3, report.orders().size());
        assertTrue(report.orders().stream().allMatch(o -> OpenOrdersService.isOpen(o.status())));

        List<String> brokers = report.orders().stream().map(OpenOrder::broker).collect(Collectors.toList());
        assertTrue(brokers.contains("AJ Bell SIPP"));
        assertTrue(brokers.contains("II SIPP"));
        assertTrue(brokers.contains("Roth IRA"));

        // Grouped by parser order: AJ Bell, then II, then Roth
        assertEquals("AJ Bell SIPP", brokers.get(0));
        assertEquals("II SIPP", brokers.get(1));
        assertEquals("Roth IRA", brokers.get(2));

        // II NVDA amount $11,467.84 → ÷1.25 = £9,174.27
        OpenOrder nvda = find(report.orders(), "NVDA");
        assertEquals(0, new BigDecimal("9174.27").compareTo(nvda.amountGbp()));

        // Roth SNPS amount is derived (price 460 × qty 6 = $2,760) → ÷1.25 = £2,208.00
        OpenOrder snps = find(report.orders(), "SNPS");
        assertEquals(0, new BigDecimal("2760").compareTo(snps.amount()));
        assertEquals(0, new BigDecimal("2208.00").compareTo(snps.amountGbp()));
    }

    @Test
    void enrichesVolatilityColumnsFromDailyHistory(@TempDir Path dir) throws Exception {
        // An II order on NVDA (US ticker maps to itself). Seed a jagged daily series ending today so
        // the daily-close fallback fills `current` and the RV windows are non-zero.
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                14/07/2026 16:52,27726IDT248,SELL/LIMIT,NVDA,NVIDIA Corp,50,n/a,"$11,000.00",PENDING
                """);

        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        List<com.portfolio.domain.model.PriceBar> bars = new java.util.ArrayList<>();
        java.time.LocalDate d = java.time.LocalDate.now().minusDays(39);   // 40 bars, ending today
        double px = 200.0;
        for (int i = 0; i < 40; i++) {
            // alternate ±2% so the daily σ is a clean, non-zero, known-ish magnitude
            px *= (i % 2 == 0) ? 1.02 : (1 / 1.02);
            bars.add(new com.portfolio.domain.model.PriceBar("NVDA", d, px, px, px, px, px, 1.0, 1L, "USD"));
            d = d.plusDays(1);
        }
        history.savePriceBars(bars);

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new IIOrdersParser()), FX, new ExcelReportWriter(), outDir,
                new IntradayPriceRepository(connections), history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        OpenOrder nvda = find(service.load().orders(), "NVDA");
        assertNotNull(nvda.dailyVol10(), "RV10 computed from daily history");
        assertNotNull(nvda.dailyVol30(), "RV30 computed from daily history");
        assertTrue(nvda.dailyVol10().signum() > 0);
        assertNotNull(nvda.currentPrice(), "daily-close fallback fills current");
        assertNotNull(nvda.sigmaToLimit(), "σ distance computed from current + limit + RV10");
        assertNotNull(nvda.touchProb5d());
        assertNotNull(nvda.touchProbHorizon());
        double p = nvda.touchProb5d().doubleValue();
        assertTrue(p >= 0.0 && p <= 1.0, "touch probability is a valid probability");
        // The order is GTC (II exports no expiry), so P(N) runs over the GTC assumption — a longer
        // horizon than 5 sessions, and the same barrier, so it can only be larger.
        assertEquals(VolForecast.Config.DEFAULT_GTC_HORIZON_SESSIONS, nvda.horizonSessions());
        assertFalse(nvda.horizonFromExpiry());
        assertTrue(nvda.touchProbHorizon().doubleValue() >= p - 1e-9,
                "P(N) ≥ P(5d) for the same limit over a longer horizon");
    }

    @Test
    void staleQuoteIsNotShownAsCurrentAndTheSymbolIsPublishedForFetching(@TempDir Path dir) throws Exception {
        // The WMT shape (2026-08-19): a resting buy on a name that is neither held nor ever traded,
        // so nothing kept its feed warm. Its last quote was two weeks old and printed as "Current"
        // beside a market $3 higher, with vs-limit/σ/touch all derived from it.
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                19/08/2026 16:52,27726IDT248,BUY/LIMIT,WMT,Walmart Inc,50,n/a,"$5,743.50",PENDING
                """);

        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        java.time.LocalDate old = java.time.LocalDate.now().minusDays(14);
        history.savePriceBars(List.of(new com.portfolio.domain.model.PriceBar(
                "WMT", old, 111.0, 112.0, 110.0, 111.79, 111.79, 1.0, 1L, "USD")));
        intraday.saveIntradayBars(List.of(new com.portfolio.domain.model.IntradayBar(
                "WMT", old.atTime(18, 8).toInstant(java.time.ZoneOffset.UTC), 111.79, null, "USD")));

        KeyValueStore kv = new KeyValueStore(dir.resolve("db"));
        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new IIOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, history, new YahooTickerMap(), kv);

        OrdersReport report = service.load();
        OpenOrder wmt = find(report.orders(), "WMT");
        assertNull(wmt.currentPrice(), "a two-week-old quote is not a current price");
        assertNull(wmt.distancePct(), "and nothing derived from it is shown either");
        assertNull(wmt.sigmaToLimit());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("WMT")),
                "the gap is surfaced, not silent");
        assertTrue(kv.getStringSet(OpenOrdersService.ORDER_SYMBOLS_KEY).contains("WMT"),
                "order symbols join the fetch universe so the feed stops going quiet");
    }

    @Test
    void freshQuoteIsStillUsed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                19/08/2026 16:52,27726IDT248,BUY/LIMIT,WMT,Walmart Inc,50,n/a,"$5,743.50",PENDING
                """);
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        intraday.saveIntradayBars(List.of(new com.portfolio.domain.model.IntradayBar(
                "WMT", java.time.Instant.now(), 115.40, null, "USD")));

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new IIOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, new PriceHistoryRepository(connections), new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        OpenOrder wmt = find(service.load().orders(), "WMT");
        assertEquals(0, new BigDecimal("115.40").compareTo(wmt.currentPrice()));
    }

    @Test
    void aQuoteBehindItsMarketPeersIsLabelledAndWarmed(@TempDir Path dir) throws Exception {
        // Two US orders. One quote is current, the other stopped updating hours ago — the row says
        // so and the symbol is handed to the warmer instead of waiting ~2 min for the polling pass.
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                19/08/2026 16:52,27726IDT248,BUY/LIMIT,WMT,Walmart Inc,50,n/a,"$5,743.50",PENDING
                19/08/2026 16:52,27726IDT249,BUY/LIMIT,PEP,PepsiCo Inc,50,n/a,"$7,043.50",PENDING
                """);
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        java.time.Instant now = java.time.Instant.now();
        intraday.saveIntradayBars(List.of(
                new com.portfolio.domain.model.IntradayBar("PEP", now, 141.86, null, "USD"),
                new com.portfolio.domain.model.IntradayBar(
                        "WMT", now.minus(java.time.Duration.ofHours(4)), 111.79, null, "USD")));

        List<String> warmed = new java.util.ArrayList<>();
        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new IIOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, new PriceHistoryRepository(connections), new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")), warmed::addAll);

        OrdersReport report = service.load();
        assertNotNull(report.priceNotes().get("WMT"), "the lagging row carries its quote time");
        assertNull(report.priceNotes().get("PEP"), "the current one is unannotated");
        assertTrue(warmed.contains("WMT"), "and is queued for an off-cycle fetch");
        assertFalse(warmed.contains("PEP"));
        assertNotNull(report.quotesAsOf());
    }

    @Test
    void quotesEquallyOldAreNotCalledStale(@TempDir Path dir) throws Exception {
        // Out of hours every quote is the close. Judging against the clock would annotate the whole
        // table every evening; judging against market peers leaves it silent.
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                19/08/2026 16:52,27726IDT248,BUY/LIMIT,WMT,Walmart Inc,50,n/a,"$5,743.50",PENDING
                19/08/2026 16:52,27726IDT249,BUY/LIMIT,PEP,PepsiCo Inc,50,n/a,"$7,043.50",PENDING
                """);
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        java.time.Instant close = java.time.Instant.now().minus(java.time.Duration.ofHours(9));
        intraday.saveIntradayBars(List.of(
                new com.portfolio.domain.model.IntradayBar("PEP", close, 141.86, null, "USD"),
                new com.portfolio.domain.model.IntradayBar("WMT", close, 111.79, null, "USD")));

        List<String> warmed = new java.util.ArrayList<>();
        OrdersReport report = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new IIOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, new PriceHistoryRepository(connections), new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")), warmed::addAll).load();

        assertTrue(report.priceNotes().isEmpty(), "nothing is behind anything");
        assertTrue(warmed.isEmpty(), "and no needless fetch is queued");
    }

    @Test
    void giltsAreNeitherComparedWithEquitiesNorWarmed(@TempDir Path dir) throws Exception {
        // A gilt order alongside an equity one. Gilts are priced by the hourly HL scrape, so an
        // hour-old quote is current for them — and Yahoo has nothing to warm them from.
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "TREASURY 4.75% 2038","44626CC3LTC","Buy","Limit","100000","95090","9509p","01/07/2026","22/09/2026","In progress"
                """);
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        intraday.saveIntradayBars(List.of(new com.portfolio.domain.model.IntradayBar(
                "GILT 4.75% 2038", java.time.Instant.now().minus(java.time.Duration.ofMinutes(90)),
                95.09, null, "GBP")));

        List<String> warmed = new java.util.ArrayList<>();
        OrdersReport report = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new AJBellOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, new PriceHistoryRepository(connections), new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")), warmed::addAll).load();

        assertTrue(report.priceNotes().isEmpty(), "a 90-minute-old gilt quote is not behind anything");
        assertTrue(warmed.isEmpty(), "and Yahoo is not asked for a gilt");
    }

    @Test
    void exportWritesDatedXlsxToOutputDir(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "RELX PLC","44626CC3LTC","Sell","Limit","100","2596.5","2600p","01/07/2026","22/09/2026","In progress"
                """);

        ExportResult result = service(dir).export();

        assertFalse(result.empty());
        assertEquals(1, result.files().size());
        Path written = Path.of(result.files().get(0));
        assertTrue(Files.exists(written));
        assertEquals(outDir, written.getParent(), "written to the output dir, not Downloads");
        assertTrue(written.getFileName().toString().startsWith("Open Orders-"));
    }

    @Test
    void exportIsNothingWhenNoOrderFiles(@TempDir Path dir) {
        assertTrue(service(dir).export().empty());
    }

    @Test
    void priceInCurrency_convertsPenceAndCrossCurrency() {
        Map<String, BigDecimal> rates = Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));
        // GBp pence → GBP pounds for a GBP order (÷100).
        assertEquals(0, new BigDecimal("5.38").compareTo(
                OpenOrdersService.priceInCurrency(new BigDecimal("538"), "GBp", "GBP", rates)));
        // Same currency passes through unchanged.
        assertEquals(0, new BigDecimal("224.86").compareTo(
                OpenOrdersService.priceInCurrency(new BigDecimal("224.86"), "USD", "USD", rates)));
        // USD listing → GBP order: 100 USD ÷ 1.25 = 80 GBP.
        assertEquals(0, new BigDecimal("80").compareTo(
                OpenOrdersService.priceInCurrency(new BigDecimal("100"), "USD", "GBP", rates)));
        // GBP listing → USD order: 80 GBP × 1.25 = 100 USD.
        assertEquals(0, new BigDecimal("100").compareTo(
                OpenOrdersService.priceInCurrency(new BigDecimal("80"), "GBP", "USD", rates)));
        // Missing rate → null (no misleading number).
        assertNull(OpenOrdersService.priceInCurrency(new BigDecimal("10"), "EUR", "GBP", rates));
        assertNull(OpenOrdersService.priceInCurrency(null, "USD", "GBP", rates));
    }

    @Test
    void toGbp_dividesByRateAndPassesGbpThrough() {
        Map<String, BigDecimal> rates = Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));
        assertEquals(0, new BigDecimal("80.00").compareTo(
                OpenOrdersService.toGbp(new BigDecimal("100"), "USD", rates)));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                OpenOrdersService.toGbp(new BigDecimal("100"), "GBP", rates)));
        assertNull(OpenOrdersService.toGbp(new BigDecimal("100"), "EUR", rates), "no rate → null");
        assertNull(OpenOrdersService.toGbp(null, "USD", rates));
    }

    // ── Pre-trade limit probe ──────────────────────────────────────────────────────────────

    /** A daily series with a clean non-zero σ, plus a live quote, stored under {@code ticker}. */
    private static void seedPrices(PriceHistoryRepository history, IntradayPriceRepository intraday,
                                   String ticker, String currency, double startPx, double quote) {
        List<com.portfolio.domain.model.PriceBar> bars = new java.util.ArrayList<>();
        java.time.LocalDate d = LocalDate.now().minusDays(39);
        double px = startPx;
        for (int i = 0; i < 40; i++) {
            px *= (i % 2 == 0) ? 1.02 : (1 / 1.02);
            bars.add(new com.portfolio.domain.model.PriceBar(ticker, d, px, px, px, px, px, 1.0, 1L, currency));
            d = d.plusDays(1);
        }
        history.savePriceBars(bars);
        intraday.saveIntradayBars(List.of(new com.portfolio.domain.model.IntradayBar(
                ticker, java.time.Instant.now(), quote, null, currency)));
    }

    /**
     * A long, calm history with a violent recent tail: ~300 sessions at ±0.4% a day, then 25 at
     * ±4%. RV20 lands well above the RV250 anchor, which is exactly the shape a same-length
     * trailing window gets wrong — it would price a year-out horizon off the panic fortnight.
     */
    private static void seedVolatileTail(PriceHistoryRepository history,
                                         IntradayPriceRepository intraday, String ticker) {
        List<com.portfolio.domain.model.PriceBar> bars = new java.util.ArrayList<>();
        LocalDate d = LocalDate.now().minusDays(400);
        double px = 100.0;
        for (int i = 0; i < 325; i++) {
            double step = (i < 300) ? 0.004 : 0.04;
            px *= (i % 2 == 0) ? (1 + step) : (1 / (1 + step));
            bars.add(new com.portfolio.domain.model.PriceBar(ticker, d, px, px, px, px, px, 1.0, 1L, "USD"));
            d = d.plusDays(1);
        }
        history.savePriceBars(bars);
        intraday.saveIntradayBars(List.of(new com.portfolio.domain.model.IntradayBar(
                ticker, java.time.Instant.now(), px, null, "USD")));
    }

    @Test
    void probeReportsExactlyWhatTheTableWouldForTheSameLimit(@TempDir Path dir) throws Exception {
        // The whole design claim: a hypothetical order is the same object as a real one, built
        // synthetically and pushed through the same enrichment. So probing the limit of an order
        // that *is* on the table has to reproduce that row's columns to the last digit — if it ever
        // stops doing so, the two panels have grown a second implementation between them.
        Files.writeString(dir.resolve("2bc3405a-877c-4bf3-ac16-8dcb5303b89a.csv"), """
                Submitted,Reference,Type,Symbol,Name,Quantity,Settlement Date,Amount,Status
                19/08/2026 16:52,27726IDT248,BUY/LIMIT,WMT,Walmart Inc,50,n/a,"$5,743.50",PENDING
                """);
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedPrices(history, intraday, "WMT", "USD", 110.0, 115.40);

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir),
                List.of(new IIOrdersParser()), FX, new ExcelReportWriter(), outDir,
                intraday, history, new YahooTickerMap(), new KeyValueStore(dir.resolve("db")));

        OpenOrder resting = find(service.load().orders(), "WMT");
        OpenOrder hypothetical = service.probe("WMT", resting.price(), "Buy", null).hypothetical();

        assertEquals(resting.currentPrice(), hypothetical.currentPrice(), "Current");
        assertEquals(resting.distancePct(), hypothetical.distancePct(), "vs limit");
        assertEquals(resting.dailyVol10(), hypothetical.dailyVol10(), "RV10");
        assertEquals(resting.dailyVol30(), hypothetical.dailyVol30(), "RV30");
        assertEquals(resting.sigmaToLimit(), hypothetical.sigmaToLimit(), "σ→lim");
        assertEquals(resting.touchProb5d(), hypothetical.touchProb5d(), "P(5d)");
        assertEquals(resting.sigmaEff(), hypothetical.sigmaEff(), "σ_eff");
        assertEquals(resting.volSource(), hypothetical.volSource(), "vol source");
        assertEquals(resting.touchProbHorizon(), hypothetical.touchProbHorizon(), "P(N)");
        assertEquals(resting.touchProbLow(), hypothetical.touchProbLow(), "P(N) low");
        assertEquals(resting.touchProbHigh(), hypothetical.touchProbHigh(), "P(N) high");
        // The resting order is a II one, so it carries no dated expiry either — both land on the
        // GTC assumption, which is what makes the two directly comparable here.
        assertEquals(OpenOrdersService.DEFAULT_HORIZON_SESSIONS, hypothetical.horizonSessions());
    }

    @Test
    void probeHorizonIsInSessionsSoFiveReproducesPFiveDay(@TempDir Path dir) {
        // The same on-screen check the table offers, and for the same reason: one first-passage
        // implementation, one unit. A probe whose N meant calendar days would quietly disagree with
        // the two fixed columns printed inches to its left.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedPrices(history, intraday, "WMT", "USD", 110.0, 115.40);
        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                FX, new ExcelReportWriter(), outDir, intraday, history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        BigDecimal limit = new BigDecimal("108.00");
        OpenOrder five = service.probe("WMT", limit, "Buy", 5).hypothetical();
        assertEquals(five.touchProb5d(), five.touchProbHorizon(), "N = 5 sessions is P(5d)");
        // Clamped, not rejected — a stray keystroke can't ask for an absurd horizon.
        assertEquals(1, service.probe("WMT", limit, "Buy", 0).hypothetical().horizonSessions());
        assertEquals(OpenOrdersService.MAX_HORIZON_SESSIONS,
                service.probe("WMT", limit, "Buy", 99_999).hypothetical().horizonSessions());
    }

    @Test
    void probeNeedsNoFxBecauseItValuesTheQuoteInItsOwnCurrency(@TempDir Path dir) {
        // A London listing quotes in GBp (pence); the limit is read in pounds, the way the AJ Bell
        // rows print it. Because the target currency *is* the quote's own, no rate is ever consulted
        // — asserted with an FX provider that throws, so the probe stays a pure disk read that can
        // be re-run on every keystroke of the limit box.
        FxRateProvider explodes = () -> {
            throw new IllegalStateException("FX must not be called by the probe");
        };
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedPrices(history, intraday, "XWHS.L", "GBp", 500.0, 538.00);

        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                explodes, new ExcelReportWriter(), outDir, intraday, history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        OpenOrdersService.LimitProbe probe = service.probe("XWHS.L", new BigDecimal("5.60"), "Buy", null);
        assertEquals("GBP", probe.hypothetical().currency(), "pence folded into pounds");
        assertEquals(0, new BigDecimal("5.38").compareTo(probe.hypothetical().currentPrice()),
                "538p reads as £5.38, matching what the table prints for a London order");
        assertNotNull(probe.hypothetical().touchProb5d());
    }

    @Test
    void probeOnAColdSymbolWarmsBothFeedsAndNamesTheTickerItTried(@TempDir Path dir) {
        // The state a pre-trade probe finds most symbols in: nothing holds, orders or watches the
        // name, so load()'s order_symbols publication never covered it and both feeds are empty.
        // A typo looks identical from here, so the message has to serve both readings.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        List<String> backfilled = new java.util.ArrayList<>();
        List<String> quoted = new java.util.ArrayList<>();
        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                FX, new ExcelReportWriter(), outDir, new IntradayPriceRepository(connections),
                new PriceHistoryRepository(connections), new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")), quoted::addAll, backfilled::add);

        OpenOrdersService.LimitProbe probe = service.probe("NOSUCH", new BigDecimal("10"), "Buy", null);
        assertNull(probe.hypothetical(), "no currency to read the limit in, so nothing is computed");
        assertFalse(probe.priced());
        assertTrue(probe.warnings().stream().anyMatch(w -> w.contains("NOSUCH")),
                "the ticker actually tried is named — that is what has to change if it's wrong");
        assertEquals(List.of("NOSUCH"), backfilled, "daily history queued");
        assertEquals(List.of("NOSUCH"), quoted, "and the quote too");

        // One fetch per symbol per cooldown, not one per keystroke of the limit box.
        service.probe("NOSUCH", new BigDecimal("11"), "Buy", null);
        assertEquals(List.of("NOSUCH"), backfilled, "the warm is cooled down across repeat probes");
    }

    @Test
    void probeSaysWhenTheLimitWouldFillOnPlacement(@TempDir Path dir) {
        // A limit on the fill side of the market isn't a resting order at all — it executes on
        // placement, so its execution probability is ~100% while the touch columns go on printing a
        // modest figure for the market travelling *to* that price. First passage is symmetric in
        // |σ|, so nothing in those numbers distinguishes the two cases; this flag is what does, and
        // it is what the panel hangs its warning on. Symmetric in side: the same price flips it.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedPrices(history, intraday, "WMT", "USD", 110.0, 115.40);
        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                FX, new ExcelReportWriter(), outDir, intraday, history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        assertTrue(service.probe("WMT", new BigDecimal("116.00"), "Buy", null).immediateFill(),
                "a buy limit above the market executes on placement");
        assertFalse(service.probe("WMT", new BigDecimal("110.00"), "Buy", null).immediateFill(),
                "a buy limit below the market rests");
        assertFalse(service.probe("WMT", new BigDecimal("116.00"), "Sell", null).immediateFill());
        assertTrue(service.probe("WMT", new BigDecimal("110.00"), "Sell", null).immediateFill(),
                "a sell limit below the market executes on placement");
    }

    @Test
    void probeWithoutALimitStillReportsTheVolatilityColumns(@TempDir Path dir) {
        // Half-entered input is the normal state while typing — the form re-fires on every
        // keystroke. RV10/RV30 need only the daily series, so they must still render; the columns
        // that genuinely need a limit blank out and the panel says which.
        JdbcConnectionFactory connections = new JdbcConnectionFactory(dir.resolve("db"));
        PriceHistoryRepository history = new PriceHistoryRepository(connections);
        IntradayPriceRepository intraday = new IntradayPriceRepository(connections);
        seedPrices(history, intraday, "WMT", "USD", 110.0, 115.40);
        OpenOrdersService service = new OpenOrdersService(new HoldingFileLocator(dir), List.of(),
                FX, new ExcelReportWriter(), outDir, intraday, history, new YahooTickerMap(),
                new KeyValueStore(dir.resolve("db")));

        OpenOrdersService.LimitProbe probe = service.probe("WMT", null, "Buy", null);
        assertTrue(probe.priced(), "the current price needs no limit");
        assertNotNull(probe.hypothetical().dailyVol10());
        assertNotNull(probe.hypothetical().dailyVol30());
        assertNull(probe.hypothetical().sigmaToLimit());
        assertNull(probe.hypothetical().touchProb5d());
        assertNull(probe.hypothetical().touchProbHorizon());
        assertFalse(probe.immediateFill(), "no limit is not a fill");
        assertTrue(probe.warnings().stream().anyMatch(w -> w.contains("limit price")));
    }

    private static OpenOrder find(List<OpenOrder> orders, String symbol) {
        return orders.stream().filter(o -> symbol.equals(o.symbol())).findFirst().orElseThrow();
    }

    @Test
    void isOpen_treatsTerminalStatusesAsClosed() {
        assertTrue(OpenOrdersService.isOpen("In progress"));
        assertTrue(OpenOrdersService.isOpen("PENDING"));
        assertTrue(OpenOrdersService.isOpen("Open"));
        assertTrue(OpenOrdersService.isOpen(null));
        assertFalse(OpenOrdersService.isOpen("Dealt"));
        assertFalse(OpenOrdersService.isOpen("EXECUTED"));
        assertFalse(OpenOrdersService.isOpen("Canceled"));
        assertFalse(OpenOrdersService.isOpen("cancelled"));
    }

    private static void writeRothWorkbook(Path file) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Order Status");
            sheet.createRow(1).createCell(1).setCellValue("Order Status");
            Row hdr = sheet.createRow(3);
            String[] cols = {"Date", "Transaction", "Security ID", "Quantity", "Order Type", "Price", "Duration", "Status"};
            for (int i = 0; i < cols.length; i++) hdr.createCell(1 + i).setCellValue(cols[i]);
            row(sheet, 4, "07/14/2026 11:13:37 AM", "Sell", "SNPS", "6", "Limit", "460.00", "GTC", "Open");
            row(sheet, 5, "07/14/2026 10:25:28 AM", "Sell", "ASML", "5", "Limit", "1780.00", "GTC", "Canceled");
            try (OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }
    }

    private static void row(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) row.createCell(1 + i).setCellValue(values[i]);
    }

    // ---- unmapped-instrument detection (the inline mapping editor's input) -------------------

    /**
     * The AJ Bell export names an instrument it carries no ticker for, so the parser passes the
     * description through as the symbol. Both mappings are missing, and the panel has to say so —
     * a blank Current column on its own looks like a dead feed.
     */
    @Test
    void reportsAnUnmappedInstrumentNameWithASuggestedKeyword(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "Xtrackers MSCI World Health Care ETF 1C GBP","44626XW1","Buy","Limit","100","4568.50","4565p","14/08/2026","30/10/2026","In progress"
                """);

        List<OpenOrdersService.UnmappedInstrument> unmapped = service(dir).load().unmapped();

        assertEquals(1, unmapped.size());
        OpenOrdersService.UnmappedInstrument u = unmapped.get(0);
        assertTrue(u.needsAlias());
        assertNull(u.symbol(), "there is no symbol yet — the user supplies it");
        assertEquals("Xtrackers MSCI World Health Care ETF 1C GBP", u.name());
        assertEquals("xtrackers msci world health", u.suggestedKeyword());
        assertEquals(".L", u.tickerSuffix(), "a sterling order completes the ticker box for London");
    }

    /** A mapped symbol that has simply never priced needs only the ticker half. */
    @Test
    void reportsAMappedSymbolThatHasNeverPriced(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "Primary Health Properties PLC","44626PHP1","Buy","Limit","100","9500.00","95p","14/08/2026","30/10/2026","In progress"
                """);

        List<OpenOrdersService.UnmappedInstrument> unmapped = service(dir).load().unmapped();

        assertEquals(1, unmapped.size());
        assertFalse(unmapped.get(0).needsAlias());
        assertEquals("PHP", unmapped.get(0).symbol());
        assertEquals("PHP.L", unmapped.get(0).triedTicker());
    }

    /** One row per instrument, however many orders rest on it. */
    @Test
    void dedupesRepeatedOrdersOnTheSameInstrument(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("activeorders.csv"), """
                "Investment","Reference","Type","Order type","Quantity","Amount","Price","Date entered","Expiry date","Status"
                "Xtrackers MSCI World Health Care ETF 1C GBP","44626XW1","Buy","Limit","100","4568.50","4565p","14/08/2026","30/10/2026","In progress"
                "Xtrackers MSCI World Health Care ETF 1C GBP","44626XW2","Buy","Limit","200","9113.50","4555p","14/08/2026","30/10/2026","In progress"
                """);

        assertEquals(1, service(dir).load().unmapped().size());
    }

    @Test
    void suggestsTheLondonSuffixOnlyForSterlingListings() {
        assertEquals("XWHS.L", OpenOrdersService.suggestTicker("xwhs", "GBP"));
        assertEquals("NVDA", OpenOrdersService.suggestTicker("NVDA", "USD"));
        assertEquals("BRK.B", OpenOrdersService.suggestTicker("BRK.B", "GBP"), "already qualified");
        assertEquals("", OpenOrdersService.exchangeSuffix("EUR"), "EUR spans three exchanges — no guess");
    }

}
