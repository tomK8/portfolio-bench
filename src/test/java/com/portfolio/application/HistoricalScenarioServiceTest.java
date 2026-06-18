package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.HistoricalScenarioService.ScenarioResponse;
import com.portfolio.application.HistoricalScenarioService.SymbolRow;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayBar;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.model.TransactionType;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.IntradayPriceRepository;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalScenarioServiceTest {

    @TempDir
    Path dbDir;

    /** Flat USD FX: 1 GBP = 1.25 USD across the whole range. */
    private static final HistoricalFxRateProvider FLAT_USD_HIST = (ccy, from, to) -> {
        TreeMap<LocalDate, BigDecimal> out = new TreeMap<>();
        if ("USD".equals(ccy)) out.put(from, new BigDecimal("1.25"));
        return out;
    };

    /** Live FX matches the historical one so projections from today's GBP value reconcile. */
    private static final FxRateProvider FLAT_USD_LIVE = () -> Map.of("USD", new BigDecimal("1.25"));

    private HistoricalScenarioService service() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        CashTransactionRepository cash = new CashTransactionRepository(cf, kv);
        IntradayPriceRepository intraday = new IntradayPriceRepository(cf);
        PriceHistoryRepository prices = new PriceHistoryRepository(cf);
        YahooTickerMap tickerMap = new YahooTickerMap();
        SyncFromCashService sync = new SyncFromCashService(cash, intraday, FLAT_USD_LIVE, tickerMap);
        return new HistoricalScenarioService(sync, prices, FLAT_USD_HIST, tickerMap, kv);
    }

    private CashTransactionRepository cashRepo() {
        return new CashTransactionRepository(new JdbcConnectionFactory(dbDir), new KeyValueStore(dbDir));
    }

    private IntradayPriceRepository intradayRepo() {
        return new IntradayPriceRepository(new JdbcConnectionFactory(dbDir));
    }

    private PriceHistoryRepository priceRepo() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    private KeyValueStore kv() {
        return new KeyValueStore(dbDir);
    }

    private static CashTransaction iiBuy(String date, String symbol, double qty, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.TRANSACTION, symbol,
                qty, -amountGbp, "GBP", 1.0, -amountGbp, null, null, date);
    }

    private static IntradayBar intradayGbp(String ticker, String day, double close) {
        return new IntradayBar(ticker, Instant.parse(day + "T16:00:00Z"), close, null, "GBp");
    }

    private static PriceBar gbpBar(String symbol, String date, double price) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                price, price, 1.0, null, "GBP");
    }

    private static PriceBar gbpPenceBar(String symbol, String date, double price) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                price, price, 1.0, null, "GBp");
    }

    private static SymbolRow find(ScenarioResponse r, String sym) {
        return r.perSymbol().stream().filter(x -> x.symbol().equals(sym)).findFirst().orElse(null);
    }

    @Test
    void extractCouponParsesGiltCouponRate() {
        assertEquals(0, new BigDecimal("0.042500").compareTo(
                HistoricalScenarioService.extractCoupon("GILT 4.25% 2032")));
        assertEquals(0, new BigDecimal("0.040000").compareTo(
                HistoricalScenarioService.extractCoupon("GILT 4% 2030")));
        assertNull(HistoricalScenarioService.extractCoupon("MSFT"));
        assertNull(HistoricalScenarioService.extractCoupon(null));
    }

    @Test
    void scenarioAppliesHistoricalReturnToTodayPosition() {
        // Buy AVUV (GBP-listed for test simplicity), priced today via intraday at £100/share.
        // Today's holding = 10 shares × £100 = £1000 GBP.
        // Historical scenario: AVUV's adj_close goes 50 → 65 over the window (+30%).
        // Projection: £1000 × 1.30 = £1300.
        cashRepo().saveII(List.of(iiBuy("2025-12-15", "AVUV", 10, 1000)));
        // SyncFromCashService resolves AVUV via the ticker map; for unmapped symbols it uses
        // the symbol itself, so store intraday + history under "AVUV".
        intradayRepo().saveIntradayBars(List.of(intradayGbp("AVUV", "2026-06-17", 10000)));  // GBp pence = £100
        priceRepo().upsertPriceBars(List.of(
                gbpBar("AVUV", "2022-01-01", 50.0),
                gbpBar("AVUV", "2022-12-31", 65.0)));

        ScenarioResponse r = service().run(
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        SymbolRow row = find(r, "AVUV");
        assertNotNull(row);
        assertFalse(row.missing());
        assertEquals(1000.0, row.startValueGbp().doubleValue(), 0.5);
        assertEquals(1300.0, row.endValueGbp().doubleValue(), 0.5);
        assertEquals(0.30, row.periodReturn().doubleValue(), 0.001);
    }

    @Test
    void missingSymbolFallsBackToEqqqDefaultSubstitute() {
        // OBSCURE has no price history → service should default-substitute EQQQ.
        // EQQQ's GBp-priced history flat 100 → 120 → +20% projection.
        cashRepo().saveII(List.of(iiBuy("2025-12-15", "OBSCURE", 5, 500)));
        intradayRepo().saveIntradayBars(List.of(intradayGbp("OBSCURE", "2026-06-17", 10000)));
        priceRepo().upsertPriceBars(List.of(
                gbpPenceBar("EQQQ.L", "2022-01-01", 10000),
                gbpPenceBar("EQQQ.L", "2022-12-31", 12000)));

        ScenarioResponse r = service().run(
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        SymbolRow row = find(r, "OBSCURE");
        assertNotNull(row);
        assertTrue(row.substituted(), "EQQQ should be substituted in");
        assertTrue(row.defaultSubstitute(), "and flagged as the service-applied default");
        assertEquals("EQQQ", row.effectiveSymbol());
        assertFalse(row.missing());
        assertEquals(600.0, row.endValueGbp().doubleValue(), 0.5);   // 500 × 1.20
        assertEquals(0.20, row.periodReturn().doubleValue(), 0.001);
    }

    @Test
    void userSubstituteWinsOverDefault() {
        // AVUV has its own data (would project +20%), but user overrides to ALT (which projects +50%).
        // Should use ALT, mark substituted=true, defaultSubstitute=false.
        cashRepo().saveII(List.of(iiBuy("2025-12-15", "AVUV", 10, 1000)));
        intradayRepo().saveIntradayBars(List.of(intradayGbp("AVUV", "2026-06-17", 10000)));
        priceRepo().upsertPriceBars(List.of(
                gbpBar("AVUV", "2022-01-01", 50.0),
                gbpBar("AVUV", "2022-12-31", 60.0),
                gbpBar("ALT", "2022-01-01", 100.0),
                gbpBar("ALT", "2022-12-31", 150.0)));

        ScenarioResponse r = service().run(
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of("AVUV", "ALT"));

        SymbolRow row = find(r, "AVUV");
        assertTrue(row.substituted());
        assertFalse(row.defaultSubstitute());
        assertEquals("ALT", row.effectiveSymbol());
        assertEquals(0.50, row.periodReturn().doubleValue(), 0.001);
    }

    @Test
    void substitutesPersistViaKvRoundTrip() {
        HistoricalScenarioService s = service();
        s.saveSubstitutes(Map.of("NVDA", "EQQQ", "TSLA", "VWRP"));

        Map<String, String> reloaded = s.loadSubstitutes();
        assertEquals("EQQQ", reloaded.get("NVDA"));
        assertEquals("VWRP", reloaded.get("TSLA"));

        // Empty values are filtered; identity mappings (X=X) are filtered too.
        s.saveSubstitutes(Map.of("FOO", "", "BAR", "BAR", "QQQ", "EQQQ"));
        reloaded = s.loadSubstitutes();
        assertEquals(1, reloaded.size());
        assertEquals("EQQQ", reloaded.get("QQQ"));
    }

    @Test
    void persistedSubstitutesPickedUpWhenUserMapEmpty() {
        // User saved NVDA→EQQQ once; subsequent run with no ad-hoc overrides should still apply it.
        cashRepo().saveII(List.of(iiBuy("2025-12-15", "NVDA", 5, 500)));
        intradayRepo().saveIntradayBars(List.of(intradayGbp("NVDA", "2026-06-17", 10000)));
        priceRepo().upsertPriceBars(List.of(
                gbpBar("NVDA", "2022-01-01", 100.0), gbpBar("NVDA", "2022-12-31", 500.0),
                gbpBar("EQQQ.L", "2022-01-01", 50.0), gbpBar("EQQQ.L", "2022-12-31", 60.0)));

        HistoricalScenarioService s = service();
        s.saveSubstitutes(Map.of("NVDA", "EQQQ"));

        ScenarioResponse r = s.run(
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        SymbolRow row = find(r, "NVDA");
        assertEquals("EQQQ", row.effectiveSymbol());
        // EQQQ's 50→60 = +20%, NOT NVDA's own +400%.
        assertEquals(0.20, row.periodReturn().doubleValue(), 0.001);
    }

    @Test
    void giltAccruesCouponOnTopOfPriceReturn() {
        // £1000 of "GILT 4% 2030". Price flat 100 → 102 (+2% price). 4% coupon over 1 year ≈ £40.
        // Total ≈ £1020 + £40 = £1060 → +6%.
        cashRepo().saveII(List.of(iiBuy("2025-12-15", "GILT 4% 2030", 1000, 1000)));
        intradayRepo().saveIntradayBars(List.of(intradayGbp("GILT 4% 2030", "2026-06-17", 10000)));
        priceRepo().upsertPriceBars(List.of(
                gbpBar("GILT 4% 2030", "2022-01-01", 100.0),
                gbpBar("GILT 4% 2030", "2023-01-01", 102.0)));

        ScenarioResponse r = service().run(
                LocalDate.parse("2022-01-01"), LocalDate.parse("2023-01-01"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        SymbolRow row = find(r, "GILT 4% 2030");
        assertNotNull(row);
        assertEquals(1060.0, row.endValueGbp().doubleValue(), 1.0);
    }

    @Test
    void invertedWindowRejected() {
        assertThrows(IllegalArgumentException.class, () -> service().run(
                LocalDate.parse("2022-12-31"), LocalDate.parse("2022-01-01"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of()));
    }

    @Test
    void emptyLedgerYieldsEmptyResponse() {
        ScenarioResponse r = service().run(
                LocalDate.parse("2022-01-01"), LocalDate.parse("2022-12-31"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        assertTrue(r.perSymbol().isEmpty());
        assertTrue(r.timeline().isEmpty());
    }
}
