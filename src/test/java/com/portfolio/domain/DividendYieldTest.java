package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DividendYieldTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 25);

    private static DividendYield.Payment pay(String date, String amount) {
        return new DividendYield.Payment(LocalDate.parse(date), new BigDecimal(amount));
    }

    private static double pct(DividendYield.Result r) {
        return r.yield().multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * The case the whole thing exists for. HICL.L pays ~2.09p quarterly against a 140p close and
     * publishes no {@code dividendYield} or {@code dividendRate} at all, so the income overlay read
     * it as a non-payer. Its July 2025 payment had just dropped out of a trailing year before the
     * 2026 replacement went ex, so a plain 365-day sum sees three payments and reads 4.48%.
     */
    @Test
    void hiclClipsATrailingYearAndTheWindowWidensToLastYearsCount() {
        List<DividendYield.Payment> payments = List.of(
                pay("2024-11-21", "2.06"), pay("2025-03-06", "2.06"), pay("2025-05-22", "2.07"),
                pay("2025-07-31", "2.08"),
                pay("2025-11-27", "2.09"), pay("2026-03-05", "2.09"), pay("2026-05-28", "2.09"));

        DividendYield.Result r = DividendYield.derive(payments, new BigDecimal("140.0"), AS_OF);

        assertNotNull(r);
        assertEquals(4, r.payments(), "last year paid four times, so four is the annual cadence");
        assertTrue(r.windowWidened(), "the trailing year held only three — that must be visible");
        assertEquals(5.96, pct(r), 0.01);
        assertEquals(LocalDate.of(2026, 5, 28), r.lastExDate());
    }

    /**
     * The reason the estimator is not "frequency × latest payment". Inferring a frequency from the
     * median gap between ex-dates reads LGEN's unequal interim/final pattern as quarterly, and
     * multiplying the latest payment by four reports 15% against a real 7.6% — a doubling, in the
     * flattering direction. Reading the cadence off last year's actual payment *count* cannot do
     * that: two payments last year means two payments summed.
     */
    @Test
    void anUnequalInterimAndFinalPayerIsNotDoubled() {
        List<DividendYield.Payment> payments = List.of(
                pay("2024-09-05", "6.00"), pay("2025-04-24", "15.36"),
                pay("2025-09-04", "6.20"), pay("2026-04-23", "15.80"));

        DividendYield.Result r = DividendYield.derive(payments, new BigDecimal("289.04"), AS_OF);

        assertNotNull(r);
        assertEquals(2, r.payments(), "two payments a year, however the gaps are spaced");
        assertFalse(r.windowWidened());
        // 6.20 + 15.80 = 22.00 over 289.04 = 7.61%, not the 15%+ a ×4 annualisation would give.
        assertEquals(7.61, pct(r), 0.01);
    }

    /** Regular payments: the widened window is exactly the trailing-twelve-month sum. */
    @Test
    void aRegularPayerIsJustTheTrailingTwelveMonthSum() {
        List<DividendYield.Payment> payments = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2023, 2, 10); d.isBefore(AS_OF); d = d.plusMonths(3)) {
            payments.add(new DividendYield.Payment(d, new BigDecimal("0.25")));
        }
        DividendYield.Result r = DividendYield.derive(payments, new BigDecimal("100"), AS_OF);

        assertNotNull(r);
        assertEquals(4, r.payments());
        assertFalse(r.windowWidened(), "nothing was clipped, so nothing was widened");
        assertEquals(1.00, pct(r), 0.001);
    }

    /** A scanned symbol with no payments is a real zero — an accumulating fund, not a gap. */
    @Test
    void noPaymentsIsARealZeroNotAnAbsence() {
        DividendYield.Result r = DividendYield.derive(List.of(), new BigDecimal("100"), AS_OF);
        assertNotNull(r);
        assertEquals(0, r.yield().signum());
        assertEquals(0, r.payments());
        assertEquals("No dividends on record", r.explain());
    }

    /**
     * A payer that stopped counts as stopped. Summing a payment older than two years would report a
     * yield for an instrument that no longer distributes.
     */
    @Test
    void aPayerThatStoppedTwoYearsAgoYieldsZero() {
        List<DividendYield.Payment> payments = List.of(
                pay("2023-01-10", "5.00"), pay("2023-07-10", "5.00"));
        DividendYield.Result r = DividendYield.derive(payments, new BigDecimal("100"), AS_OF);
        assertNotNull(r);
        assertEquals(0, r.yield().signum());
        assertEquals(0, r.payments());
    }

    /** No price means no ratio. Null, never zero — the caller has to tell the two apart. */
    @Test
    void anUnpricedSymbolYieldsNullRatherThanZero() {
        List<DividendYield.Payment> payments = List.of(pay("2026-05-28", "2.09"));
        assertNull(DividendYield.derive(payments, null, AS_OF));
        assertNull(DividendYield.derive(payments, BigDecimal.ZERO, AS_OF));
        assertNull(DividendYield.derive(payments, new BigDecimal("-5"), AS_OF));
    }

    /** A dividend declared with a future ex-date is not income yet, and must not be summed. */
    @Test
    void aFutureExDateIsIgnored() {
        List<DividendYield.Payment> payments = List.of(
                pay("2025-11-27", "2.09"), pay("2026-03-05", "2.09"), pay("2026-05-28", "2.09"),
                pay("2026-09-30", "2.10"));
        DividendYield.Result r = DividendYield.derive(payments, new BigDecimal("140.0"), AS_OF);
        assertNotNull(r);
        assertEquals(LocalDate.of(2026, 5, 28), r.lastExDate());
    }

    /** Units cancel: amounts and price share a basis, so pence over pence is the same as £ over £. */
    @Test
    void theRatioIsUnitFreeSoPenceNeedsNoConversion() {
        List<DividendYield.Payment> pence = List.of(
                pay("2025-11-27", "2.09"), pay("2026-03-05", "2.09"),
                pay("2026-05-28", "2.09"), pay("2025-09-30", "2.09"));
        List<DividendYield.Payment> pounds = List.of(
                pay("2025-11-27", "0.0209"), pay("2026-03-05", "0.0209"),
                pay("2026-05-28", "0.0209"), pay("2025-09-30", "0.0209"));

        DividendYield.Result a = DividendYield.derive(pence, new BigDecimal("140.0"), AS_OF);
        DividendYield.Result b = DividendYield.derive(pounds, new BigDecimal("1.40"), AS_OF);

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(pct(a), pct(b), 0.0001);
    }
}
