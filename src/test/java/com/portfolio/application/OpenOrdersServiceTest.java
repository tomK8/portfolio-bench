package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.OpenOrdersService.OrdersReport;
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
        assertNotNull(nvda.touchProb20d());
        double p = nvda.touchProb5d().doubleValue();
        assertTrue(p >= 0.0 && p <= 1.0, "touch probability is a valid probability");
        // longer horizon can only raise the touch probability (same barrier, more time)
        assertTrue(nvda.touchProb20d().doubleValue() >= p - 1e-9,
                "P(touch 20d) ≥ P(touch 5d) for the same limit");
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
