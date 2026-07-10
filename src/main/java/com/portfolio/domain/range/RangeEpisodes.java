package com.portfolio.domain.range;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Episode de-duplication for pooled significance: cohort names hit their floors on shared dates,
 * so ten entries in one sell-off are one independent observation, not ten. Entries are sorted by
 * date and greedily clustered — an entry within {@code dedupCalendarDays} of its episode's
 * <em>first</em> entry joins that episode; the next later entry opens a new one. Calendar days
 * (default 7 ≈ one trading week) rather than per-name session counts, because the cohort spans
 * three exchange calendars.
 */
public final class RangeEpisodes {

    private RangeEpisodes() {
    }

    /** Number of distinct episodes among {@code entryDates} (any order; empty → 0). */
    public static int count(List<LocalDate> entryDates, int dedupCalendarDays) {
        if (entryDates.isEmpty()) return 0;
        List<LocalDate> sorted = new ArrayList<>(entryDates);
        sorted.sort(null);
        int episodes = 1;
        LocalDate episodeStart = sorted.get(0);
        for (LocalDate d : sorted) {
            if (ChronoUnit.DAYS.between(episodeStart, d) > dedupCalendarDays) {
                episodes++;
                episodeStart = d;
            }
        }
        return episodes;
    }
}
