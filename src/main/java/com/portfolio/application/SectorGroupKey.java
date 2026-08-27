package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.domain.SectorBucket;
import com.portfolio.domain.SectorClassifier;
import com.portfolio.domain.grouping.HoldingGroupKey;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.persistence.FundamentalsRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Groups the Cash-ledger table by sector, using {@link SectorClassifier} — the same classifier
 * that draws Allocation → Breakdown, fed from the same {@code fundamentals_snapshot} cache, so
 * the two panels cannot disagree about what a holding is.
 *
 * <p>It lives in {@code application} rather than beside the other key types in {@code
 * domain.grouping} because it is the one that needs data off disk. The cache is read once per
 * grouped render and handed in, so the resolver itself stays a lookup.
 *
 * <h2>A fund spanning sectors gets its own group, not its largest one</h2>
 * The classifier's answer for a fund is a <i>split</i> — EQQQ is 57.8% technology, not a
 * technology holding — and a table row cannot be split without breaking the reconciliation that
 * makes a grouped table readable. The two ways out are to file the whole position under its
 * dominant bucket or to say plainly that it spans several, and only the second is true: filing
 * £150k of a Nasdaq-100 tracker under Technology would report a sector concentration that is not
 * what is held, and it would disagree with the Technology row in the breakdown block, which is a
 * genuine look-through partition. So multi-sector funds group together and the note points at the
 * panel that does decompose them. A fund whose look-through lands wholly in one bucket — a sector
 * ETF — is filed there like anything else.
 *
 * <h2>Cash is placed here, not left to the classifier</h2>
 * A CASH row has no symbol Yahoo knows, so the classifier would return {@code UNCLASSIFIED} for
 * it and the largest genuinely-unplaceable warning in the table would be a balance that is not
 * unplaceable at all. {@link SectorBreakdownService} handles cash outside the classify loop for
 * the same reason.
 */
public final class SectorGroupKey implements HoldingGroupKey {

    private static final String CASH_SYMBOL = "CASH";
    private static final String MULTI_FUND_ID = "MULTI_SECTOR_FUND";

    private final SectorClassifier classifier;
    private final Map<String, FundamentalsRepository.Cached> cache;

    public SectorGroupKey(SectorClassifier classifier,
                          Map<String, FundamentalsRepository.Cached> cache) {
        this.classifier = classifier;
        this.cache = cache;
    }

    @Override
    public Group keyFor(AggHolding holding) {
        String symbol = holding.securityId();
        if (CASH_SYMBOL.equalsIgnoreCase(symbol)) {
            return Group.of(SectorBucket.CASH.name(), SectorBucket.CASH.label());
        }

        QuoteSummary quote = quoteFor(symbol);
        SectorClassifier.Classification c = classifier.classify(new SectorClassifier.Position(
                symbol, holding.marketValueGbp(),
                quote == null ? null : quote.labels().get("sector"),
                quote == null ? Map.of() : quote.sectorWeights(),
                constituentsOf(quote)));

        if (c.fund() && c.byBucket().size() > 1) {
            return Group.of(MULTI_FUND_ID, "Funds — multi-sector",
                    "Funds whose holdings span several sectors. A table row cannot be split "
                    + "across groups without the group totals ceasing to add up, and filing a "
                    + "broad tracker under its largest sector would overstate that sector. "
                    + "Allocation → Breakdown looks these through properly.");
        }

        SectorBucket bucket = c.byBucket().keySet().stream()
                .findFirst().orElse(SectorBucket.UNCLASSIFIED);
        if (bucket == SectorBucket.UNCLASSIFIED) {
            return Group.residual(bucket.name(), bucket.label(),
                    "No sector is known for these — either the fundamentals row has not been "
                    + "fetched yet (it fills on the next background refresh) or Yahoo files no "
                    + "sector for the listing. They are still counted in every total.");
        }
        return Group.of(bucket.name(), bucket.label());
    }

    private QuoteSummary quoteFor(String symbol) {
        FundamentalsRepository.Cached c = cache.get(symbol);
        return c == null ? null : c.quote();
    }

    /** A fund's named constituents with their own sectors resolved from the same cache. */
    private List<SectorClassifier.Constituent> constituentsOf(QuoteSummary quote) {
        if (quote == null || quote.topHoldings().isEmpty()) return List.of();
        List<SectorClassifier.Constituent> out = new ArrayList<>();
        for (QuoteSummary.FundHolding h : quote.topHoldings()) {
            QuoteSummary q = quoteFor(h.symbol());
            out.add(new SectorClassifier.Constituent(
                    h.symbol(), h.weight(), q == null ? null : q.labels().get("sector")));
        }
        return out;
    }
}
