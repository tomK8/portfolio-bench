package com.portfolio.application;

import com.portfolio.domain.SectorClassifier;
import com.portfolio.persistence.FundamentalsRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Supplies a {@link SectorGroupKey} with the fundamentals cache already loaded, behind a short
 * TTL.
 *
 * <p>It exists because the Live tab groups on the SSE tick. {@code FundamentalsRepository.loadAll}
 * scans the whole table and Jackson-deserialises four JSON columns per row, which is a fine price
 * to pay when a user picks something from a dropdown and an absurd one to pay every 15 seconds for
 * data a background job only refreshes every four hours. The same instance serves the Cash-ledger
 * dropdown, where it also means flipping between None and Sector costs one read rather than one
 * per flip.
 *
 * <p><b>The TTL is short on purpose.</b> A symbol bought since the last fundamentals tick has no
 * sector yet and lands in the Unclassified group; {@code FundamentalsWarmer} fetches it in the
 * background on a 5-minute cooldown, so a cache pinned for much longer would keep showing the gap
 * for minutes after it had been filled. Two minutes is well inside that and still cuts the Live
 * tab's reads by roughly eight to one.
 *
 * <p>Thread-safe by the same means as {@code CachingFxRateProvider}: {@link #get()} is
 * synchronized, so concurrent SSE ticks and tab clicks share one load rather than stampeding
 * SQLite.
 */
public class SectorGroupKeyProvider {

    private static final Duration TTL = Duration.ofMinutes(2);

    private final SectorClassifier classifier;
    private final FundamentalsRepository fundamentalsRepo;

    private SectorGroupKey cached;
    private Instant cachedAt = Instant.EPOCH;

    public SectorGroupKeyProvider(SectorClassifier classifier,
                                  FundamentalsRepository fundamentalsRepo) {
        this.classifier = classifier;
        this.fundamentalsRepo = fundamentalsRepo;
    }

    public synchronized SectorGroupKey get() {
        if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(TTL) < 0) {
            return cached;
        }
        Map<String, FundamentalsRepository.Cached> snapshot = fundamentalsRepo.loadAll();
        cached = new SectorGroupKey(classifier, snapshot);
        cachedAt = Instant.now();
        return cached;
    }
}
