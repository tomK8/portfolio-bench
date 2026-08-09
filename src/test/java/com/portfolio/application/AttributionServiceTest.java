package com.portfolio.application;

import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.application.AttributionService.AttributionResult;
import com.portfolio.application.AttributionService.AttributionRow;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class AttributionServiceTest {

    /** GBP rate fixture: 1 GBP = 1.25 USD, flat. (Frankfurter convention: foreign / GBP.) */
    private static final HistoricalFxRateProvider FLAT_USD_FX = (ccy, from, to) -> {
        TreeMap<LocalDate, BigDecimal> out = new TreeMap<>();
        if ("USD".equals(ccy)) out.put(from, new BigDecimal("1.25"));
        return out;
    };

    /** Live FX is unused unless {@code to} is today; tests fix {@code to} in the past, so this is a no-op. */
    private static final FxRateProvider EMPTY_LIVE_FX = () -> Map.of();

    @TempDir
    Path dbDir;

    private AttributionService service() {
        return service(FLAT_USD_FX);
    }

    private AttributionService service(HistoricalFxRateProvider fx) {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        KeyValueStore kv = new KeyValueStore(dbDir);
        return new AttributionService(
                new CashTransactionRepository(cf, kv),
                new PriceHistoryRepository(cf),
                new IntradayPriceRepository(cf),
                fx,
                EMPTY_LIVE_FX,
                new YahooTickerMap());
    }

    private CashTransactionRepository repo() {
        JdbcConnectionFactory cf = new JdbcConnectionFactory(dbDir);
        return new CashTransactionRepository(cf, new KeyValueStore(dbDir));
    }

    private PriceHistoryRepository prices() {
        return new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
    }

    /** Buy/sell row for II SIPP. {@code amount} is GBP (II native is GBP here for simplicity). */
    private static CashTransaction trade(String date, String symbol, double qty, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.TRANSACTION, symbol,
                qty, amountGbp, "GBP", 1.0, amountGbp, null, null, date);
    }

    private static CashTransaction dividend(String date, String symbol, double amountGbp) {
        return new CashTransaction(date, Account.II, TransactionType.DIVIDEND, symbol,
                0.0, amountGbp, "GBP", 1.0, amountGbp, null, null, date);
    }

    private static PriceBar bar(String symbol, String date, double close) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                close, close, 1.0, null, "USD");
    }

    private static PriceBar barGbp(String symbol, String date, double close) {
        return new PriceBar(symbol, LocalDate.parse(date), null, null, null,
                close, close, 1.0, null, "GBP");
    }

    private static AttributionRow find(AttributionResult r, String sym) {
        return r.rows().stream().filter(x -> x.symbol().equals(sym)).findFirst().orElse(null);
    }

    @Test
    void emptyLedgerYieldsEmptyResult() {
        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));
        assertTrue(r.rows().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.totalPnlGbp()));
    }

    @Test
    void heldThroughoutPnlIsPureMarketMove() {
        // Bought 10 MU before the window at £100 each (£1000 cost). Held through the window.
        // Window start price £110, end price £130. P&L = 10 × (130 − 110) = £200.
        repo().saveII(List.of(trade("2025-12-15", "MU", 10, -1000)));
        // Use GBP-listed price to avoid FX in the assertion.
        prices().upsertPriceBars(List.of(
                barGbp("MU", "2026-01-01", 110.0),
                barGbp("MU", "2026-06-01", 130.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow mu = find(r, "MU");
        assertNotNull(mu);
        assertEquals(0, new BigDecimal("1100.00").compareTo(mu.startValueGbp()));
        assertEquals(0, new BigDecimal("1300.00").compareTo(mu.endValueGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(mu.cashFlowGbp()));
        assertEquals(0, new BigDecimal("200.00").compareTo(mu.pnlGbp()));
    }

    @Test
    void buyInsideWindowAtFairValueContributesZeroBeforePriceMove() {
        // Bought 5 NVDA mid-window at £200; no price move thereafter → P&L = 0.
        repo().saveII(List.of(trade("2026-03-15", "NVDA", 5, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("NVDA", "2026-03-15", 200.0),
                barGbp("NVDA", "2026-06-01", 200.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow nv = find(r, "NVDA");
        assertNotNull(nv);
        assertEquals(0, BigDecimal.ZERO.compareTo(nv.startValueGbp()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(nv.endValueGbp()));
        assertEquals(0, new BigDecimal("-1000.00").compareTo(nv.cashFlowGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(nv.pnlGbp()));
    }

    @Test
    void dividendCountsAsPositiveContribution() {
        repo().saveII(List.of(
                trade("2025-12-15", "AAPL", 100, -10000),
                dividend("2026-03-10", "AAPL", 50)));
        prices().upsertPriceBars(List.of(
                barGbp("AAPL", "2026-01-01", 100.0),
                barGbp("AAPL", "2026-06-01", 100.0)));   // no market move

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow aapl = find(r, "AAPL");
        // No market move + £50 dividend → P&L = +£50.
        assertEquals(0, new BigDecimal("50.00").compareTo(aapl.pnlGbp()));
        assertEquals(0, new BigDecimal("50.00").compareTo(aapl.cashFlowGbp()));
    }

    @Test
    void sellAtProfitInsideWindowRealisesGain() {
        // Bought 10 GOOG before window at £100 (£1000). Sold all on day-1-of-window at £150 (£1500).
        // End qty = 0 → end value = 0. Cash flow within window = +£1500 (sell).
        // P&L = 0 − 1500 + 1500 = 0? No — start_value uses price on `from` ≈ £150.
        // start_value at window open = 10 × £150 = £1500. So P&L = 0 − 1500 + 1500 = 0.
        // That's correct: the price had already moved before the sell happened in the same day;
        // P&L of the gain is attributed to the pre-window appreciation, not the realisation.
        // (Same logic: only price moves *within* the window count for that symbol.)
        repo().saveII(List.of(
                trade("2025-12-15", "GOOG", 10, -1000),
                trade("2026-01-02", "GOOG", 10, 1500)));
        prices().upsertPriceBars(List.of(
                barGbp("GOOG", "2026-01-01", 150.0),
                barGbp("GOOG", "2026-01-02", 150.0),
                barGbp("GOOG", "2026-06-01", 150.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow goog = find(r, "GOOG");
        assertEquals(0, BigDecimal.ZERO.compareTo(goog.pnlGbp()));
        assertEquals(0, new BigDecimal("1500.00").compareTo(goog.startValueGbp()));
        assertEquals(0, BigDecimal.ZERO.compareTo(goog.endValueGbp()));
        assertEquals(0, new BigDecimal("1500.00").compareTo(goog.cashFlowGbp()));
    }

    @Test
    void usdHoldingConvertsAtBoundaryFx() {
        // USD-listed stock priced in USD. Hold 10 shares throughout window.
        // Start price $200, end price $250. FX flat 1.25 USD/GBP.
        // Start value = 10 × 200 / 1.25 = £1600. End = 10 × 250 / 1.25 = £2000. P&L = £400.
        repo().saveII(List.of(trade("2025-12-15", "MSFT", 10, -1500)));
        prices().upsertPriceBars(List.of(
                bar("MSFT", "2026-01-01", 200.0),
                bar("MSFT", "2026-06-01", 250.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow ms = find(r, "MSFT");
        assertEquals(0, new BigDecimal("1600.00").compareTo(ms.startValueGbp()));
        assertEquals(0, new BigDecimal("2000.00").compareTo(ms.endValueGbp()));
        assertEquals(0, new BigDecimal("400.00").compareTo(ms.pnlGbp()));
    }

    @Test
    void rowsSortedDescByPnl() {
        repo().saveII(List.of(
                trade("2025-12-15", "WIN", 10, -1000),
                trade("2025-12-15", "LOSE", 10, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("WIN", "2026-01-01", 100.0),
                barGbp("WIN", "2026-06-01", 130.0),
                barGbp("LOSE", "2026-01-01", 100.0),
                barGbp("LOSE", "2026-06-01", 80.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        assertEquals("WIN", r.rows().get(0).symbol());
        assertEquals("LOSE", r.rows().get(1).symbol());
        // Total ≈ +300 − 200 = +100.
        assertEquals(0, new BigDecimal("100.00").compareTo(r.totalPnlGbp()));
    }

    @Test
    void heldThroughoutReturnIsPnlOverStartValue() {
        // Same fixture as heldThroughoutPnlIsPureMarketMove — start £1100, P&L £200 → +18.18%.
        // Window is ~5 months (Jan 1 → Jun 1 — 151 days), so annualised ≈ (1.1818)^(365.25/151) - 1 ≈ +50%.
        repo().saveII(List.of(trade("2025-12-15", "MU", 10, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("MU", "2026-01-01", 110.0),
                barGbp("MU", "2026-06-01", 130.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow mu = find(r, "MU");
        assertNotNull(mu.periodReturn());
        // pnl 200 / peak 1100 = 0.181818…
        assertEquals(0.18, mu.periodReturn().doubleValue(), 0.01);
        assertNotNull(mu.annualizedReturn());
        assertEquals(0.50, mu.annualizedReturn().doubleValue(), 0.05);
    }

    @Test
    void boughtMidWindowReturnUsesBuyAsCapital() {
        // No prior position. Bought £1000 mid-window, end value £1200, no other flows.
        // Peak deployed = £1000. P&L = 1200 + (−1000) = £200 → +20%.
        repo().saveII(List.of(trade("2026-03-15", "NVDA", 5, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("NVDA", "2026-03-15", 200.0),
                barGbp("NVDA", "2026-06-01", 240.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow nv = find(r, "NVDA");
        assertEquals(0.20, nv.periodReturn().doubleValue(), 0.001);
    }

    @Test
    void totalLossAnnualisedClampsToNull() {
        // Pathological case: bought £1000, position evaporated to 0 with no proceeds.
        // periodReturn = -100% → (1 + r) = 0 → annualised undefined; service returns null.
        repo().saveII(List.of(trade("2026-03-15", "DOOMED", 100, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("DOOMED", "2026-03-15", 10.0),
                barGbp("DOOMED", "2026-06-01", 0.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        AttributionRow d = find(r, "DOOMED");
        assertEquals(0, new BigDecimal("-1.000000").compareTo(d.periodReturn()));
        assertNull(d.annualizedReturn());
    }

    // ---- boundaries that can't be valued ------------------------------------------------------
    // Regression: a Frankfurter timeout used to leave the USD series absent, and every USD position
    // silently valued at £0 on the start boundary while the end boundary priced fine — a phantom
    // gain the size of the whole book. Excluded-and-named is the only safe answer.

    @Test
    void missingFxExcludesSymbolFromRowsAndTotals() {
        HistoricalFxRateProvider brokenFx = (ccy, from, to) -> {
            throw new IllegalStateException("request timed out");
        };
        repo().saveII(List.of(
                trade("2025-12-15", "MSFT", 100, -10000),     // USD-priced
                trade("2025-12-15", "MU", 100, -1000)));      // GBP-priced, unaffected
        prices().upsertPriceBars(List.of(
                bar("MSFT", "2026-01-01", 200.0), bar("MSFT", "2026-06-01", 250.0),
                barGbp("MU", "2026-01-01", 10.0), barGbp("MU", "2026-06-01", 12.0)));

        AttributionResult r = service(brokenFx).attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        assertNull(find(r, "MSFT"), "unpriceable symbol must not appear as a row");
        assertEquals(1, r.excluded().size());
        assertEquals("MSFT", r.excluded().get(0).symbol());
        assertTrue(r.excluded().get(0).reason().contains("USD"), r.excluded().get(0).reason());
        // Totals cover only what survived: MU 100 × (12 − 10) = £200.
        assertNotNull(find(r, "MU"));
        assertEquals(0, new BigDecimal("200.00").compareTo(r.totalPnlGbp()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(r.totalStartValueGbp()));
    }

    @Test
    void missingPriceHistoryExcludesSymbol() {
        repo().saveII(List.of(trade("2025-12-15", "MCTS", 100, -1000)));   // never priced

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        assertNull(find(r, "MCTS"));
        assertEquals(1, r.excluded().size());
        assertEquals("MCTS", r.excluded().get(0).symbol());
        assertTrue(r.excluded().get(0).reason().contains("no price history"));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.totalPnlGbp()));
    }

    @Test
    void dividendOnSoldPositionNeedsNoValuationAndIsKept() {
        // Nothing held on either boundary, so no price is required — the row must survive even
        // though the symbol has no price_history at all.
        repo().saveII(List.of(dividend("2026-03-10", "GONE", 50)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));

        assertTrue(r.excluded().isEmpty());
        assertEquals(0, new BigDecimal("50.00").compareTo(find(r, "GONE").pnlGbp()));
    }

    // ---- annualisation floor -------------------------------------------------------------------

    @Test
    void shortWindowReportsNoAnnualisedReturn() {
        // +10% over 5 days compounds to ~1,300,000% — arithmetically right, analytically useless.
        repo().saveII(List.of(trade("2025-12-15", "NVO", 10, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("NVO", "2026-06-01", 100.0),
                barGbp("NVO", "2026-06-06", 110.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-06"));

        AttributionRow nvo = find(r, "NVO");
        assertEquals(0.10, nvo.periodReturn().doubleValue(), 0.001, "period return still reported");
        assertNull(nvo.annualizedReturn(), "5-day window must not annualise");
    }

    @Test
    void thirtyDayWindowStillAnnualises() {
        repo().saveII(List.of(trade("2025-12-15", "NVO", 10, -1000)));
        prices().upsertPriceBars(List.of(
                barGbp("NVO", "2026-06-01", 100.0),
                barGbp("NVO", "2026-07-01", 110.0)));

        AttributionResult r = service().attribute(
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-01"));

        AttributionRow nvo = find(r, "NVO");
        assertNotNull(nvo.annualizedReturn(), "30 days is the floor, not below it");
        // (1.10)^(365.25/30) − 1 ≈ 2.19
        assertEquals(2.19, nvo.annualizedReturn().doubleValue(), 0.05);
    }

    @Test
    void rejectsInvertedWindow() {
        assertThrows(IllegalArgumentException.class, () -> service().attribute(
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-01-01")));
    }
}
