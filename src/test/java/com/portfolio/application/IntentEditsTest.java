package com.portfolio.application;

import com.portfolio.domain.model.IntendedRung;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule behind the group panels' editable cells: which rungs they can reach, and what counts as
 * an edit.
 */
class IntentEditsTest {

    /**
     * The load-bearing one. A rung that came from a broker's CSV is not editable, and it is not
     * enough that the panel declines to draw an input over it: a key naming one must reach nothing,
     * so a stale tab or a hand-made request cannot have this app restate what a broker is holding.
     */
    @Test
    void onlyAnIntendedRungCanBeAddressed() {
        assertTrue(IntentEdits.of(List.of("AJ Bell SIPP|A12345"), List.of("560"), List.of("30"))
                .isEmpty(), "a broker reference names nothing this panel can write");
        assertTrue(IntentEdits.of(List.of("Roth IRA|NVDA|Buy|20|203.18|2026-08-28"),
                List.of("175"), List.of("20")).isEmpty(), "nor does a Citi composite");
        assertTrue(IntentEdits.of(List.of("intent:nope"), List.of("1"), List.of("1")).isEmpty());

        List<IntentEdits.Typed> edits =
                IntentEdits.of(List.of("intent:7"), List.of("175.5"), List.of("30")).all();
        assertEquals(1, edits.size());
        assertEquals(7L, edits.get(0).intentId());
        assertEquals(0, new BigDecimal("175.5").compareTo(edits.get(0).price()));
        assertEquals(0, new BigDecimal("30").compareTo(edits.get(0).quantity()));
    }

    /**
     * The panel posts every editable cell on screen rather than tracking which ones were touched, so
     * "unchanged" has to be something the server recognises rather than a claim the browser makes.
     * Without this, a commit anywhere in the block would rewrite every intent in it.
     */
    @Test
    void aFigureTypedBackAsRenderedIsNotAnEdit() {
        IntendedRung r = intent(new BigDecimal("25"), new BigDecimal("175.00"));

        assertFalse(IntentEdits.differs(new BigDecimal(IntentEdits.editable(r.price())), r.price()));
        assertFalse(IntentEdits.differs(new BigDecimal(IntentEdits.editable(r.quantity())),
                r.quantity()));
        assertTrue(IntentEdits.differs(new BigDecimal("168.50"), r.price()));
    }

    /** A figure with more decimals than money has still has to survive its own round trip. */
    @Test
    void anAwkwardFigureSurvivesTheRoundTrip() {
        BigDecimal thirds = new BigDecimal("1").divide(new BigDecimal("3"), 12,
                java.math.RoundingMode.HALF_UP);

        String rendered = IntentEdits.editable(thirds);
        assertFalse(IntentEdits.differs(new BigDecimal(rendered), thirds),
                "the figure the cell was filled with must post back as unchanged");
    }

    /** Blank is "no opinion", never zero — the app-wide rule, and what makes tabbing through safe. */
    @Test
    void aBlankCellChangesNothing() {
        IntendedRung r = intent(new BigDecimal("25"), new BigDecimal("175.00"));
        IntentEdits.Typed typed =
                IntentEdits.of(List.of("intent:1"), List.of(""), List.of("  ")).all().get(0);

        assertFalse(IntentEdits.differs(typed.price(), r.price()));
        assertFalse(IntentEdits.differs(typed.quantity(), r.quantity()));
        assertTrue(IntentEdits.differs(new BigDecimal("1"), null),
                "a figure typed onto a rung that had none is exactly the edit that gives it a "
                        + "probability");
    }

    /** A half-typed figure is an unchanged cell, not a 400 that wipes the block being worked in. */
    @Test
    void anUnparseableFigureIsTreatedAsUntouched() {
        IntentEdits.Typed typed =
                IntentEdits.of(List.of("intent:1"), List.of("17-5"), List.of(".")).all().get(0);

        assertFalse(IntentEdits.differs(typed.price(), new BigDecimal("175.00")));
        assertFalse(IntentEdits.differs(typed.quantity(), new BigDecimal("25")));
    }

    /**
     * What the cell is filled with has to be something the browser can post straight back: no
     * grouping separator, no currency symbol, no trailing zeros.
     */
    @Test
    void anEditableCellRendersAFigureTheServerCanReadBack() {
        assertEquals("1234.5", IntentEdits.editable(new BigDecimal("1234.500")));
        assertEquals("0", IntentEdits.editable(BigDecimal.ZERO));
        assertEquals("", IntentEdits.editable(null));
        assertEquals(0, new BigDecimal(IntentEdits.editable(new BigDecimal("682.35")))
                .compareTo(new BigDecimal("682.35")));
    }

    private static IntendedRung intent(BigDecimal quantity, BigDecimal price) {
        return new IntendedRung(1L, 1L, "Roth IRA", "Buy", quantity, price, null,
                LocalDate.parse("2026-09-01"));
    }
}
