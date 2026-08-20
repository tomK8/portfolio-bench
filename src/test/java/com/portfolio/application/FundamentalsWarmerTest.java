package com.portfolio.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundamentalsWarmerTest {

    private final List<List<String>> calls = new ArrayList<>();
    private final FundamentalsWarmer warmer =
            new FundamentalsWarmer(symbols -> calls.add(List.copyOf(symbols)));

    @Test
    void firstRequestPassesThrough() {
        assertEquals(List.of("XWIS"), warmer.warm(List.of("XWIS")));
        assertEquals(List.of(List.of("XWIS")), calls);
    }

    @Test
    void secondRequestForTheSameSymbolIsSwallowedByTheCooldown() {
        warmer.warm(List.of("XWIS"));
        // Both breakdown panels load together and each tab click re-fetches them, so without a
        // cooldown a single new holding would be one Yahoo round-trip per click.
        assertEquals(List.of(), warmer.warm(List.of("XWIS")));
        assertEquals(1, calls.size());
    }

    @Test
    void onlyTheUncooledSymbolsOfAMixedBatchAreSent() {
        warmer.warm(List.of("XWIS"));
        assertEquals(List.of("ISRG"), warmer.warm(List.of("XWIS", "ISRG")));
        assertEquals(List.of(List.of("XWIS"), List.of("ISRG")), calls);
    }

    @Test
    void emptyAndBlankInputsNeverReachTheFetcher() {
        assertEquals(List.of(), warmer.warm(List.of()));
        assertEquals(List.of(), warmer.warm(null));
        assertEquals(List.of(), warmer.warm(List.of("", "   ")));
        assertTrue(calls.isEmpty());
    }

    @Test
    void aFailingFetcherDoesNotPropagate() {
        // The warm-up is a side errand of a read endpoint; it must never fail the panel.
        FundamentalsWarmer boom = new FundamentalsWarmer(s -> {
            throw new IllegalStateException("Yahoo said no");
        });
        assertEquals(List.of("XWIS"), boom.warm(Set.of("XWIS")));
    }

    @Test
    void disabledWarmerIsANoOpButStillReportsWhatItWouldHaveSent() {
        assertEquals(List.of("XWIS"), FundamentalsWarmer.disabled().warm(List.of("XWIS")));
    }
}
