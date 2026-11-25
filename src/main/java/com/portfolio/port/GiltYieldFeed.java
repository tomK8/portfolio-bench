package com.portfolio.port;

import com.portfolio.domain.model.GiltYield;

import java.util.List;

/**
 * Supplies benchmark gilt yields for whole-year tenors — the figures quoted as "the 2-year",
 * "the 10-year" and so on.
 */
public interface GiltYieldFeed {

    /**
     * The yields the source quotes for {@code tenorYears}, in no particular order. A tenor the
     * source does not answer is <b>absent</b> from the list — never a row with a zero or blank
     * yield — so the caller can name it.
     *
     * @throws Exception when the source is unreachable, refuses, or answers in a shape that
     *                   cannot be read; an empty list is never used to mean "failed"
     */
    List<GiltYield> benchmarkYields(List<Integer> tenorYears) throws Exception;

    /** Short label for the source, shown beside the figures. */
    String sourceName();
}
