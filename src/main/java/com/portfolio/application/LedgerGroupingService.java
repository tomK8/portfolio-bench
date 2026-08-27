package com.portfolio.application;

import com.portfolio.domain.SectorClassifier;
import com.portfolio.domain.grouping.AccountGroupKey;
import com.portfolio.domain.grouping.HoldingGroup;
import com.portfolio.domain.grouping.HoldingGroupKey;
import com.portfolio.domain.grouping.HoldingGrouper;
import com.portfolio.domain.model.AggHolding;
import com.portfolio.persistence.FundamentalsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Resolves the Cash-ledger table's grouping dropdown to a {@link HoldingGroupKey} and runs it.
 *
 * <p>This is the only place that knows which key types exist, and {@link GroupBy} is the only
 * place that knows how to build one. <b>Adding a way to group the book is a constant here plus a
 * resolver class — nothing else.</b> The endpoint, the fragment, the header rows, the weighted
 * percentages and the expand/collapse are all written against the abstraction, so the currency
 * and permanent/rotation splits that are wanted next need no change to any of them.
 *
 * <p>The sector key is the one that costs anything: it reads {@code fundamentals_snapshot} once
 * per grouped render. That read is skipped entirely for the keys that do not need it, which is
 * why the cache is loaded inside the {@code SECTOR} branch rather than in the constructor.
 */
public class LedgerGroupingService {

    private static final Logger log = LoggerFactory.getLogger(LedgerGroupingService.class);

    private final SectorClassifier classifier;
    private final FundamentalsRepository fundamentalsRepo;

    public LedgerGroupingService(SectorClassifier classifier,
                                 FundamentalsRepository fundamentalsRepo) {
        this.classifier = classifier;
        this.fundamentalsRepo = fundamentalsRepo;
    }

    /** The dropdown's options, in the order they are offered. */
    public enum GroupBy {
        NONE("none", "None"),
        SECTOR("sector", "Sector"),
        ACCOUNT("account", "Account");

        private final String id;
        private final String label;

        GroupBy(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }
    }

    public List<GroupBy> options() {
        return List.of(GroupBy.values());
    }

    /**
     * The selected option, defaulting to {@link GroupBy#NONE}. An unrecognised value degrades to
     * the ungrouped table rather than throwing — it can only arrive from a hand-edited request,
     * and the panel's job is to show the portfolio.
     */
    public GroupBy parse(String raw) {
        if (raw == null || raw.isBlank()) return GroupBy.NONE;
        for (GroupBy g : GroupBy.values()) {
            if (g.id.equalsIgnoreCase(raw.trim())) return g;
        }
        log.warn("Unknown ledger grouping '{}' — falling back to ungrouped", raw);
        return GroupBy.NONE;
    }

    /**
     * The groups for this selection, or an empty list for {@link GroupBy#NONE} — which the
     * fragment reads as "render the flat table", so the ungrouped view stays exactly the markup
     * it has always been rather than a one-group special case of the grouped one.
     */
    public List<HoldingGroup> group(List<AggHolding> holdings, GroupBy by) {
        if (by == GroupBy.NONE || holdings == null || holdings.isEmpty()) return List.of();
        return HoldingGrouper.group(holdings, keyFor(by));
    }

    private HoldingGroupKey keyFor(GroupBy by) {
        return switch (by) {
            case SECTOR -> new SectorGroupKey(classifier, fundamentalsRepo.loadAll());
            case ACCOUNT -> new AccountGroupKey();
            case NONE -> throw new IllegalArgumentException("NONE has no resolver");
        };
    }
}
