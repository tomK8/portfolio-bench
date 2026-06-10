package com.portfolio.application;

import com.portfolio.application.DividendIncomeService.AnnualBucket;
import com.portfolio.application.DividendIncomeService.DividendIncome;
import com.portfolio.application.DividendIncomeService.SymbolRow;
import com.portfolio.domain.CashLedgerReconstructor;
import com.portfolio.domain.CashLedgerReconstructor.Position;
import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.domain.model.IntradayPrice;
import com.portfolio.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DividendIncomeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    @Test
    void emptyRowsReturnEmpty() {
        DividendIncome out = DividendIncomeService.build(
                List.of(), List.of(), Map.of(), Map.of("USD", new BigDecimal("1.3")), TODAY);
        assertEquals(BigDecimal.ZERO.setScale(2), out.summary().lifetimeIncomeGbp());
        assertTrue(out.rows().isEmpty());
        assertTrue(out.annual().isEmpty());
    }

    @Test
    void singleSymbolYocAndTrailingYieldComputedCorrectly() {
        // 100 LGEN shares bought at £2.50 each (cost £250). Annual divs: 4 × £6.25 = £25 over
        // the last 12 months. Current market value: 100 × £2.00 = £200 (price dropped).
        // YoC = 25/250 = 10%. Trailing yield = 25/200 = 12.5%.
        List<CashTransaction> rows = List.of(
                buyGbp("2024-01-15", "LGEN", 100, 250.00),
                divGbp("2025-08-01", "LGEN", 6.25),
                divGbp("2025-11-01", "LGEN", 6.25),
                divGbp("2026-02-01", "LGEN", 6.25),
                divGbp("2026-05-01", "LGEN", 6.25)
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, IntradayPrice> prices = Map.of("LGEN",
                new IntradayPrice(Instant.now(), 2.00, "GBP"));
        DividendIncome out = DividendIncomeService.build(rows, positions, prices, Map.of(), TODAY);

        assertEquals(1, out.rows().size());
        SymbolRow row = out.rows().get(0);
        assertEquals("LGEN", row.symbol());
        assertEquals(0, row.lifetimeGbp().compareTo(new BigDecimal("25.00")));
        assertEquals(0, row.trailingIncomeGbp().compareTo(new BigDecimal("25.00")));
        assertEquals(0, row.costBasisGbp().compareTo(new BigDecimal("250.00")));
        assertEquals(0, row.marketValueGbp().compareTo(new BigDecimal("200.00")));
        assertEquals(0.10, row.yieldOnCost().doubleValue(), 1e-6);
        assertEquals(0.125, row.trailingYield().doubleValue(), 1e-6);

        assertEquals(0.10, out.summary().portfolioYieldOnCost().doubleValue(), 1e-6);
        assertEquals(0.125, out.summary().portfolioTrailingYield().doubleValue(), 1e-6);
    }

    @Test
    void trailingWindowExcludesOldDividends() {
        // Two divs: one inside the 1-year window, one well outside. Lifetime sees both,
        // trailing only sees the recent one.
        List<CashTransaction> rows = List.of(
                buyGbp("2020-01-15", "BP", 100, 400.00),
                divGbp("2021-06-01", "BP", 50.00),  // > 1y ago: lifetime-only
                divGbp("2026-03-01", "BP", 20.00)   // within 1y: lifetime + trailing
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, IntradayPrice> prices = Map.of("BP",
                new IntradayPrice(Instant.now(), 5.00, "GBP"));
        DividendIncome out = DividendIncomeService.build(rows, positions, prices, Map.of(), TODAY);

        SymbolRow row = out.rows().get(0);
        assertEquals(0, row.lifetimeGbp().compareTo(new BigDecimal("70.00")));
        assertEquals(0, row.trailingIncomeGbp().compareTo(new BigDecimal("20.00")));
        assertEquals(0, out.summary().lifetimeIncomeGbp().compareTo(new BigDecimal("70.00")));
        assertEquals(0, out.summary().trailing12mIncomeGbp().compareTo(new BigDecimal("20.00")));
    }

    @Test
    void soldPositionStillShowsLifetimeDividends() {
        // Bought 100 NVDA, sold all 100. Got £15 in dividends while holding.
        // After sale: no position, but lifetime row should still surface.
        List<CashTransaction> rows = List.of(
                buyGbp("2022-01-15", "NVDA", 100, 200.00),
                divGbp("2022-06-01", "NVDA", 15.00),
                sellGbp("2023-12-15", "NVDA", 100, 350.00)
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        DividendIncome out = DividendIncomeService.build(rows, positions, Map.of(), Map.of(), TODAY);

        assertEquals(1, out.rows().size());
        SymbolRow row = out.rows().get(0);
        assertEquals("NVDA", row.symbol());
        assertFalse(row.currentlyHeld());
        assertEquals(0, row.lifetimeGbp().compareTo(new BigDecimal("15.00")));
        assertEquals(0, row.shares().compareTo(BigDecimal.ZERO));
        assertNull(row.yieldOnCost(),
                "Closed position has zero cost basis → YoC should be null, not infinity");
        assertNull(row.trailingYield());
    }

    @Test
    void annualBucketsStackTopSymbolsAndCollapseOther() {
        // Three symbols, only 2 should be in "top N" — set N=2 implicitly by being below the
        // default N=8. Verify the third still appears in the year total and that perSymbolGbp
        // covers the named ones.
        List<CashTransaction> rows = List.of(
                buyGbp("2024-01-15", "A", 1, 1.00),
                buyGbp("2024-01-15", "B", 1, 1.00),
                buyGbp("2024-01-15", "C", 1, 1.00),
                divGbp("2024-03-01", "A", 100.00),
                divGbp("2024-06-01", "B", 50.00),
                divGbp("2024-09-01", "C", 25.00),
                divGbp("2025-03-01", "A", 110.00),
                divGbp("2025-06-01", "B", 55.00),
                divGbp("2025-09-01", "C", 27.50)
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        DividendIncome out = DividendIncomeService.build(rows, positions, Map.of(), Map.of(), TODAY);

        // Year totals match.
        Map<Integer, BigDecimal> totalByYear = out.annual().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AnnualBucket::year, AnnualBucket::totalGbp));
        assertEquals(0, totalByYear.get(2024).compareTo(new BigDecimal("175.00")));
        assertEquals(0, totalByYear.get(2025).compareTo(new BigDecimal("192.50")));
        // Top symbols by lifetime: A (210), B (105), C (52.50). All ≤8 so all three named.
        assertEquals(List.of("A", "B", "C"), out.stackedSymbolOrder());
    }

    @Test
    void usdPriceConvertedViaRates() {
        // 10 GOOG shares bought for £1000 GBP, now $1200 USD with rate $1.20 per £1 → £1000.
        List<CashTransaction> rows = List.of(
                buyGbp("2024-01-15", "GOOG", 10, 1000.00),
                divGbp("2025-08-01", "GOOG", 5.00)
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, IntradayPrice> prices = Map.of("GOOG",
                new IntradayPrice(Instant.now(), 120.00, "USD"));
        Map<String, BigDecimal> rates = Map.of("USD", new BigDecimal("1.20"));
        DividendIncome out = DividendIncomeService.build(rows, positions, prices, rates, TODAY);

        SymbolRow row = out.rows().get(0);
        // 10 × 120 / 1.20 = 1000 GBP
        assertEquals(0, row.marketValueGbp().compareTo(new BigDecimal("1000.00")));
        // Trailing yield = 5 / 1000 = 0.5%
        assertEquals(0.005, row.trailingYield().doubleValue(), 1e-6);
    }

    @Test
    void missingFxFallsThroughWithNullMarketValue() {
        // USD price but no USD rate → marketValue null, trailing yield null (can't compute),
        // YoC still computable.
        List<CashTransaction> rows = List.of(
                buyGbp("2024-01-15", "GOOG", 10, 1000.00),
                divGbp("2025-08-01", "GOOG", 5.00)
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, IntradayPrice> prices = Map.of("GOOG",
                new IntradayPrice(Instant.now(), 120.00, "USD"));
        DividendIncome out = DividendIncomeService.build(rows, positions, prices, Map.of(), TODAY);

        SymbolRow row = out.rows().get(0);
        assertNull(row.marketValueGbp());
        assertNull(row.trailingYield());
        assertNotNull(row.yieldOnCost());
        assertEquals(0.005, row.yieldOnCost().doubleValue(), 1e-6);
    }

    @Test
    void londonPenceTickerNormalisedToPounds() {
        // LGEN listed on London at 200p (=£2.00). 100 shares → £200 market value.
        List<CashTransaction> rows = List.of(
                buyGbp("2024-01-15", "LGEN", 100, 250.00),
                divGbp("2026-03-01", "LGEN", 25.00)
        );
        List<Position> positions = new CashLedgerReconstructor().reconstruct(rows);
        Map<String, IntradayPrice> prices = Map.of("LGEN",
                new IntradayPrice(Instant.now(), 200.0, "GBp"));
        DividendIncome out = DividendIncomeService.build(rows, positions, prices, Map.of(), TODAY);

        SymbolRow row = out.rows().get(0);
        assertEquals(0, row.marketValueGbp().compareTo(new BigDecimal("200.00")));
    }

    // ---- Helpers --------------------------------------------------------

    private static CashTransaction buyGbp(String date, String symbol, double qty, double costGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, qty, -costGbp, "GBP", 1.0, -costGbp, null, null, "buy " + symbol);
    }

    private static CashTransaction sellGbp(String date, String symbol, double qty, double proceedsGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.TRANSACTION,
                symbol, qty, proceedsGbp, "GBP", 1.0, proceedsGbp, null, null, "sell " + symbol);
    }

    private static CashTransaction divGbp(String date, String symbol, double amountGbp) {
        return new CashTransaction(date, Account.AJBELL, TransactionType.DIVIDEND,
                symbol, 0, amountGbp, "GBP", 1.0, amountGbp, null, null, "div " + symbol);
    }
}
