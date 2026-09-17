package com.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two listings of one fund are one holding, everywhere exposure is measured. */
class ShareClassesTest {

    private static Map<String, BigDecimal> book(String... pairs) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], new BigDecimal(pairs[i + 1]));
        return out;
    }

    /**
     * The whole point: a half-finished switch is one position of the full size, not two of half.
     * Counted the other way, concentration says the book is more diversified than it is.
     */
    @Test
    void aDeclaredPairIsOneRowOfTheFullSize() {
        ShareClasses sc = ShareClasses.of(List.of(new ShareClasses.Pair("EQQQ", "EQQU")));
        Map<String, BigDecimal> folded = sc.fold(book("EQQQ", "30000", "NVDA", "20000", "EQQU", "20000"));

        assertEquals(2, folded.size());
        assertEquals(0, new BigDecimal("50000").compareTo(folded.get("EQQQ")));
        assertEquals(0, new BigDecimal("20000").compareTo(folded.get("NVDA")));
        assertFalse(folded.containsKey("EQQU"));
    }

    /** The fold moves value between rows and never into or out of the book. */
    @Test
    void foldingChangesNoTotal() {
        ShareClasses sc = ShareClasses.of(List.of(new ShareClasses.Pair("EQQQ", "EQQU")));
        Map<String, BigDecimal> before = book("EQQQ", "30000", "NVDA", "20000", "EQQU", "20000");
        BigDecimal sum = before.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal after = sc.fold(before).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(after));
    }

    /**
     * The canonical is the line the switch is <em>leaving</em>: the one with the price history, the
     * ticker mapping and the classification, so the folded row keeps working the moment the pair is
     * declared and the historical series stays unbroken.
     */
    @Test
    void theHoldingIsNamedAfterTheLineBeingLeft() {
        ShareClasses sc = ShareClasses.of(List.of(new ShareClasses.Pair("EQQQ", "EQQU")));
        assertEquals("EQQQ", sc.canonical("EQQU"));
        assertEquals("EQQQ", sc.canonical("eqqu"), "case is not a second instrument");
        assertEquals("EQQQ", sc.canonical("EQQQ"));
        assertTrue(sc.isAlias("EQQU"));
        assertFalse(sc.isAlias("EQQQ"));
        assertEquals(java.util.Set.of("EQQU"), sc.aliasesOf("EQQQ"));
    }

    /** A holding switched twice is still one holding. */
    @Test
    void chainsResolveToAFixedPoint() {
        ShareClasses sc = ShareClasses.of(List.of(
                new ShareClasses.Pair("EQQQ", "EQQU"),
                new ShareClasses.Pair("EQQU", "EQAC")));
        assertEquals("EQQQ", sc.canonical("EQAC"));
        Map<String, BigDecimal> folded = sc.fold(book("EQQQ", "10", "EQQU", "20", "EQAC", "30"));
        assertEquals(1, folded.size());
        assertEquals(0, new BigDecimal("60").compareTo(folded.get("EQQQ")));
    }

    /**
     * Two listings declared as each other's second line have no answer, and picking one would
     * silently decide which name every chart is keyed by. They stay separate and it is reported.
     */
    @Test
    void aCycleIsReportedRatherThanResolvedByPickingAWinner() {
        ShareClasses sc = ShareClasses.of(List.of(
                new ShareClasses.Pair("EQQQ", "EQQU"),
                new ShareClasses.Pair("EQQU", "EQQQ")));
        Map<String, BigDecimal> folded = sc.fold(book("EQQQ", "10", "EQQU", "20"));
        assertEquals(2, folded.size(), "neither can be the holding's name");
        assertFalse(sc.warnings().isEmpty());
    }

    /** A second declaration of the same alias is refused rather than silently re-pointed. */
    @Test
    void oneListingCannotBeTheSecondLineOfTwoFunds() {
        ShareClasses sc = ShareClasses.of(List.of(
                new ShareClasses.Pair("EQQQ", "EQQU"),
                new ShareClasses.Pair("CSP1", "EQQU")));
        assertEquals("EQQQ", sc.canonical("EQQU"), "the first declaration stands");
        assertFalse(sc.warnings().isEmpty());
    }

    /** Nothing declared costs nothing: the caller's own map comes straight back. */
    @Test
    void noDeclarationsLeaveTheBookExactlyAsItWas() {
        Map<String, BigDecimal> before = book("EQQQ", "10", "NVDA", "20");
        assertSame(before, ShareClasses.NONE.fold(before));
        assertSame(before, ShareClasses.of(List.of()).fold(before));
        assertTrue(ShareClasses.NONE.isEmpty());
    }

    /** A pair pointing a listing at itself says nothing and is dropped. */
    @Test
    void aSelfPairIsIgnored() {
        assertTrue(ShareClasses.of(List.of(new ShareClasses.Pair("EQQQ", "EQQQ"))).isEmpty());
    }

    /** A holding fully moved to the new line still has a row — under the name it is counted by. */
    @Test
    void aCompletedSwitchStillHasOneRow() {
        ShareClasses sc = ShareClasses.of(List.of(new ShareClasses.Pair("EQQQ", "EQQU")));
        Map<String, BigDecimal> folded = sc.fold(book("EQQU", "50000", "NVDA", "20000"));
        assertEquals(0, new BigDecimal("50000").compareTo(folded.get("EQQQ")));
        assertFalse(folded.containsKey("EQQU"));
    }
}
