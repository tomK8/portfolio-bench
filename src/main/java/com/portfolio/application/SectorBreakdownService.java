package com.portfolio.application;

import com.portfolio.adapter.YahooQuoteSummaryFetcher.QuoteSummary;
import com.portfolio.application.AllocationService.AllocationPoint;
import com.portfolio.domain.IndustryGroups;
import com.portfolio.domain.SectorBucket;
import com.portfolio.domain.SectorClassifier;
import com.portfolio.persistence.FundamentalsRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the sector + AI/Tech breakdown of the current portfolio.
 *
 * <p>Values come from {@link AllocationService#currentPoint()} — the same forward-replay that draws
 * the charts directly beneath this block, so the two can never disagree about what is held.
 * Classification comes from {@link SectorClassifier}, fed with the cached Yahoo sector for each
 * symbol and, for funds, their sector weights and named constituents.
 *
 * <p>Everything degrades: an empty ledger, a missing fundamentals cache or an unclassifiable
 * position each produce a warning and a partial answer rather than an exception. The one thing
 * that is never done quietly is dropping value — anything that cannot be placed lands in
 * {@link SectorBucket#UNCLASSIFIED} and is listed by name, because a bucket that silently
 * swallowed it would understate exactly the concentration this panel is for.
 */
public class SectorBreakdownService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int GBP_SCALE = 2;
    private static final int PCT_SCALE = 6;
    private static final String CASH_SYMBOL = "CASH";

    private final AllocationService allocationService;
    private final FundamentalsRepository fundamentalsRepo;
    private final SectorClassifier classifier;
    private final IndustryGroups industryGroups;
    private final FundamentalsWarmer warmer;

    public SectorBreakdownService(AllocationService allocationService,
                                  FundamentalsRepository fundamentalsRepo,
                                  SectorClassifier classifier,
                                  IndustryGroups industryGroups,
                                  FundamentalsWarmer warmer) {
        this.allocationService = allocationService;
        this.fundamentalsRepo = fundamentalsRepo;
        this.classifier = classifier;
        this.industryGroups = industryGroups;
        this.warmer = warmer;
    }

    public SectorBreakdown breakdown() {
        List<String> warnings = new ArrayList<>();

        AllocationService.CurrentSnapshot snapshot = allocationService.currentSnapshot();
        if (snapshot == null) {
            warnings.add("No cash-ledger transactions yet — nothing to classify.");
            return empty(warnings);
        }
        AllocationPoint point = snapshot.point();

        Map<String, FundamentalsRepository.Cached> cache = fundamentalsRepo.loadAll();
        if (cache.isEmpty()) {
            warnings.add("Fundamentals cache is empty, so no sector is known for any holding. "
                    + "It fills on the next background refresh.");
        }

        Map<SectorBucket, BigDecimal> total = new EnumMap<>(SectorBucket.class);
        Map<SectorBucket, BigDecimal> direct = new EnumMap<>(SectorBucket.class);
        Map<SectorBucket, BigDecimal> viaFund = new EnumMap<>(SectorBucket.class);
        // What feeds each bucket, for the click-through popup. The classifier already produces
        // this split per position; previously only the totals were kept.
        Map<SectorBucket, List<SectorBreakdown.Constituent>> members = new EnumMap<>(SectorBucket.class);
        List<SectorBreakdown.Contributor> unclassified = new ArrayList<>();
        // Un-placed only because their fundamentals row hasn't been fetched yet — handed to the
        // warmer at the end of the pass rather than one at a time, so the throttle applies once.
        Set<String> awaitingData = new LinkedHashSet<>();
        List<SectorBreakdown.AiContributor> aiContributors = new ArrayList<>();
        List<SectorBreakdown.FundNote> fundNotes = new ArrayList<>();

        BigDecimal aiTotal = BigDecimal.ZERO;
        BigDecimal aiDirect = BigDecimal.ZERO;
        BigDecimal aiViaFund = BigDecimal.ZERO;
        BigDecimal aiFromTechSector = BigDecimal.ZERO;
        BigDecimal aiCrossSector = BigDecimal.ZERO;
        boolean lowerBound = false;

        for (Map.Entry<String, BigDecimal> e : point.symbolGbp().entrySet()) {
            String symbol = e.getKey();
            BigDecimal gbp = e.getValue();
            if (gbp == null || gbp.signum() == 0 || CASH_SYMBOL.equalsIgnoreCase(symbol)) continue;

            QuoteSummary quote = quoteFor(cache, symbol);
            SectorClassifier.Classification c = classifier.classify(new SectorClassifier.Position(
                    symbol, gbp,
                    quote == null ? null : quote.labels().get("sector"),
                    quote == null ? Map.of() : quote.sectorWeights(),
                    constituentsOf(quote, cache)));

            for (Map.Entry<SectorBucket, BigDecimal> b : c.byBucket().entrySet()) {
                if (b.getValue().signum() == 0) continue;
                total.merge(b.getKey(), b.getValue(), BigDecimal::add);
                (c.fund() ? viaFund : direct).merge(b.getKey(), b.getValue(), BigDecimal::add);
                // Industry rides along from the same cached quote the sector came from — already
                // fetched, never a second call. Only meaningful for a direct holding; a fund's
                // slice spans a whole sector. The raw value and the maintained group both travel:
                // the group is what the drill-down reads, the raw value is what shows it drifting.
                String industry = c.fund() || quote == null ? null : quote.labels().get("industry");
                IndustryGroups.Group group = c.fund() ? null : industryGroups.groupFor(symbol, industry);
                members.computeIfAbsent(b.getKey(), k -> new ArrayList<>())
                        .add(new SectorBreakdown.Constituent(symbol, b.getValue(), null, c.fund(),
                                c.fund() ? fraction(b.getValue(), gbp) : null,
                                industry,
                                group == null ? null : group.name(),
                                group != null && group.bySymbol()));
                if (b.getKey() == SectorBucket.UNCLASSIFIED) {
                    String state = dataState(cache.get(symbol));
                    if (!"NO_SECTOR".equals(state)) awaitingData.add(symbol);
                    unclassified.add(new SectorBreakdown.Contributor(symbol, b.getValue(), state));
                }
            }
            if (c.aiTechGbp().signum() > 0) {
                aiTotal = aiTotal.add(c.aiTechGbp());
                aiFromTechSector = aiFromTechSector.add(c.fromTechSectorGbp());
                aiCrossSector = aiCrossSector.add(c.crossSectorGbp());
                if (c.fund()) {
                    aiViaFund = aiViaFund.add(c.aiTechGbp());
                    lowerBound = true;
                } else {
                    aiDirect = aiDirect.add(c.aiTechGbp());
                }
                SectorBucket own = c.byBucket().keySet().stream().findFirst().orElse(null);
                String source = c.fund() ? "FUND"
                        : (c.fromTechSectorGbp().signum() > 0 ? "TECHNOLOGY_SECTOR" : "TAGGED");
                aiContributors.add(new SectorBreakdown.AiContributor(
                        symbol, c.aiTechGbp(), source,
                        c.fund() || own == null ? null : own.label(),
                        c.fromTechSectorGbp(), c.crossSectorGbp()));
            }
            if (c.fund()) {
                fundNotes.add(new SectorBreakdown.FundNote(
                        symbol, gbp,
                        cache.containsKey(symbol) ? cache.get(symbol).fetchedAt().toString() : null,
                        c.nameCoverage(),
                        quote == null ? null : quote.sectorWeights().get("technology")));
            }
        }

        BigDecimal cashGbp = point.cashGbp() == null ? BigDecimal.ZERO : point.cashGbp();
        if (cashGbp.signum() != 0) {
            total.merge(SectorBucket.CASH, cashGbp, BigDecimal::add);
            // Cash is one number on the row but four balances underneath; the drill-down is the
            // only place that distinction is visible, so keep the per-(account, currency) detail.
            for (Map.Entry<String, BigDecimal> e : snapshot.cashGbpByAccountCcy().entrySet()) {
                members.computeIfAbsent(SectorBucket.CASH, k -> new ArrayList<>())
                        .add(new SectorBreakdown.Constituent(
                                e.getKey().replace("|", " · "), e.getValue(), null, false,
                                null, null, null, false));
            }
        }

        BigDecimal totalGbp = point.totalGbp();
        BigDecimal investedGbp = point.investedGbp();

        List<SectorBreakdown.Row> rows = new ArrayList<>();
        for (Map.Entry<SectorBucket, BigDecimal> b : total.entrySet()) {
            if (b.getValue().signum() == 0) continue;
            SectorBucket bucket = b.getKey();
            rows.add(new SectorBreakdown.Row(
                    bucket.name(), bucket.label(), scaled(b.getValue()),
                    fraction(b.getValue(), totalGbp),
                    // Cash is the thing "invested" is measured against, so a share-of-invested
                    // for it would be nonsense rather than merely uninteresting.
                    bucket == SectorBucket.CASH ? null : fraction(b.getValue(), investedGbp),
                    scaled(direct.getOrDefault(bucket, BigDecimal.ZERO)),
                    scaled(viaFund.getOrDefault(bucket, BigDecimal.ZERO)),
                    finaliseConstituents(members.get(bucket), b.getValue())));
        }
        rows.sort(Comparator.comparing(SectorBreakdown.Row::gbp).reversed());

        if (!unclassified.isEmpty()) {
            unclassified.sort(Comparator.comparing(SectorBreakdown.Contributor::gbp).reversed());
            long settled = unclassified.stream()
                    .filter(c -> "NO_SECTOR".equals(c.state())).count();
            if (settled > 0) {
                warnings.add(settled + " position(s) could not be classified and are "
                        + "excluded from every sector — see the list below.");
            }
        }
        // Fire-and-forget: the fetch lands in the cache for the next load rather than making this
        // request wait on Yahoo. Same bargain as the Open-orders quote warm.
        warmer.warm(awaitingData);
        aiContributors.sort(Comparator.comparing(SectorBreakdown.AiContributor::gbp).reversed());
        fundNotes.sort(Comparator.comparing(SectorBreakdown.FundNote::gbp).reversed());

        SectorBreakdown.AiTech aiTech = new SectorBreakdown.AiTech(
                scaled(aiTotal), fraction(aiTotal, totalGbp), fraction(aiTotal, investedGbp),
                scaled(aiDirect), scaled(aiViaFund),
                scaled(aiFromTechSector), scaled(aiCrossSector), lowerBound, aiContributors);

        return new SectorBreakdown(point.date(), totalGbp, investedGbp, scaled(cashGbp),
                rows, aiTech, unclassified, fundNotes, warnings);
    }

    /**
     * Orders a bucket's members by value and fills in each one's share of the bucket, which can
     * only be computed once the bucket total is known.
     */
    private static List<SectorBreakdown.Constituent> finaliseConstituents(
            List<SectorBreakdown.Constituent> members, BigDecimal bucketTotal) {
        if (members == null || members.isEmpty()) return List.of();
        List<SectorBreakdown.Constituent> out = new ArrayList<>();
        for (SectorBreakdown.Constituent c : members) {
            out.add(new SectorBreakdown.Constituent(c.label(), scaled(c.gbp()),
                    fraction(c.gbp(), bucketTotal), c.viaFund(), c.shareOfPosition(),
                    c.industry(), c.industryGroup(), c.industryOverride()));
        }
        out.sort(Comparator.comparing(SectorBreakdown.Constituent::gbp).reversed());
        return List.copyOf(out);
    }

    /**
     * The fundamentals row for a symbol. Bonds and anything never fetched simply have none, which
     * the classifier reads as "no sector known" — not an error.
     */
    /**
     * Why a position could not be placed. Distinguishing "not fetched yet" from "Yahoo files no
     * sector for this" is the whole difference between a red strip that is noise and one that is
     * a finding — a name bought since the four-hourly fundamentals tick has no row at all, and
     * says nothing about whether it is classifiable.
     */
    static String dataState(FundamentalsRepository.Cached cached) {
        if (cached == null) return "AWAITING_DATA";
        if (cached.quote() == null || cached.quote().missing()) return "FETCH_FAILED";
        return "NO_SECTOR";
    }

    private static QuoteSummary quoteFor(Map<String, FundamentalsRepository.Cached> cache, String symbol) {
        FundamentalsRepository.Cached c = cache.get(symbol);
        return c == null ? null : c.quote();
    }

    /**
     * A fund's named constituents, each resolved to its own sector from the same cache. Doing the
     * lookup here keeps {@link SectorClassifier} free of any repository dependency; a constituent
     * we hold no fundamentals for arrives with a null sector, which the classifier treats as
     * "skip" rather than guessing.
     */
    private static List<SectorClassifier.Constituent> constituentsOf(
            QuoteSummary quote, Map<String, FundamentalsRepository.Cached> cache) {
        if (quote == null || quote.topHoldings().isEmpty()) return List.of();
        List<SectorClassifier.Constituent> out = new ArrayList<>();
        for (QuoteSummary.FundHolding h : quote.topHoldings()) {
            QuoteSummary q = quoteFor(cache, h.symbol());
            out.add(new SectorClassifier.Constituent(
                    h.symbol(), h.weight(), q == null ? null : q.labels().get("sector")));
        }
        return out;
    }

    private static SectorBreakdown empty(List<String> warnings) {
        return new SectorBreakdown(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(),
                new SectorBreakdown.AiTech(BigDecimal.ZERO, null, null,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        false, List.of()),
                List.of(), List.of(), warnings);
    }

    private static BigDecimal fraction(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.divide(whole, MC).setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v == null ? null : v.setScale(GBP_SCALE, RoundingMode.HALF_UP);
    }
}
