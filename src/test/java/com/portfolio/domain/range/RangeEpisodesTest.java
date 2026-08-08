package com.portfolio.domain.range;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RangeEpisodesTest {

    /** Two names entering in the same week count as one independent episode. */
    @Test
    void sameWeekEntriesAcrossNamesAreOneEpisode() {
        List<LocalDate> entries = List.of(
                LocalDate.of(2026, 2, 2),    // name 1, Monday
                LocalDate.of(2026, 2, 5));   // name 2, Thursday — same sell-off
        assertEquals(1, RangeEpisodes.count(entries, 7));
    }

    @Test
    void entriesBeyondTheDedupWindowAreSeparateEpisodes() {
        List<LocalDate> entries = List.of(
                LocalDate.of(2026, 2, 2),
                LocalDate.of(2026, 2, 16));
        assertEquals(2, RangeEpisodes.count(entries, 7));
    }

    /** Clustering anchors on each episode's first entry, not a rolling chain. */
    @Test
    void chainOfCloseEntriesDoesNotMergeIntoOneEndlessEpisode() {
        List<LocalDate> entries = List.of(
                LocalDate.of(2026, 2, 2),
                LocalDate.of(2026, 2, 8),    // joins episode 1 (≤ 7 days from the 2nd)
                LocalDate.of(2026, 2, 12));  // 10 days from the anchor → new episode
        assertEquals(2, RangeEpisodes.count(entries, 7));
    }
}
