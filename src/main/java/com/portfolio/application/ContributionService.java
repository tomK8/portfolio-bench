package com.portfolio.application;

import com.portfolio.domain.model.Account;
import com.portfolio.domain.model.CashTransaction;
import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.KeyValueStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the cumulative-contributions time series for the dashboard chart. Replays the
 * {@code CONTRIBUTION} rows in date order, tracking per-account running totals plus an
 * overall total line. RothIRA has no contribution rows in the ledger (the broker history
 * doesn't go back that far), so it's represented as a flat line at the
 * {@code roth_balance_brought_forward} seed across the same x-range as the other lines.
 *
 * <p><b>II SIPP caveat:</b> some II {@code CONTRIBUTION} rows are "Trf from AJ Bell"
 * internal transfers, not external money. The corresponding outflow doesn't appear on the
 * AJBell side in {@code cash_transactions} (the SIPP cash statement export doesn't record
 * outbound pension transfers), so we treat all classified contributions equally here.
 */
public class ContributionService {

    private static final Map<Account, String> ACCOUNT_LABELS = Map.of(
            Account.AJBELL, "AJ Bell SIPP",
            Account.II, "II SIPP",
            Account.ROTH_IRA, "Roth IRA");

    private final CashTransactionRepository repo;
    private final KeyValueStore settings;

    public ContributionService(CashTransactionRepository repo, KeyValueStore settings) {
        this.repo = repo;
        this.settings = settings;
    }

    public ContributionTimeline timeline() {
        List<CashTransaction> contribs = repo.loadContributions();
        BigDecimal rothSeed = settings.getBigDecimal(
                CashTransactionRepository.ROTH_BROUGHT_FORWARD_KEY, BigDecimal.ZERO);
        String rothStart = repo.earliestTransactionDate(Account.ROTH_IRA);

        // Merge Roth's seed into the same stream as the contribution rows so the chronology
        // is exact: Roth's first dollar lands on rothStart, not at the start of AJBell history.
        List<TimedEvent> events = new ArrayList<>();
        for (CashTransaction t : contribs) {
            String label = ACCOUNT_LABELS.getOrDefault(t.account(), t.account().dbValue());
            events.add(new TimedEvent(t.transactionDate(), label,
                    BigDecimal.valueOf(t.amountGbp()).setScale(2, RoundingMode.HALF_UP)));
        }
        boolean hasRothSeed = rothSeed.signum() > 0 && rothStart != null;
        if (hasRothSeed) {
            events.add(new TimedEvent(rothStart, ACCOUNT_LABELS.get(Account.ROTH_IRA), rothSeed));
        }
        events.sort(java.util.Comparator.comparing(TimedEvent::date));

        Map<String, List<DataPoint>> byAccount = new LinkedHashMap<>();
        for (String label : ACCOUNT_LABELS.values()) byAccount.put(label, new ArrayList<>());
        Map<String, BigDecimal> running = new LinkedHashMap<>();
        BigDecimal runningTotal = BigDecimal.ZERO;
        List<DataPoint> totalPoints = new ArrayList<>();

        for (TimedEvent e : events) {
            BigDecimal cum = running.getOrDefault(e.account(), BigDecimal.ZERO).add(e.amount());
            running.put(e.account(), cum);
            byAccount.get(e.account()).add(new DataPoint(e.date(), cum));
            runningTotal = runningTotal.add(e.amount());
            totalPoints.add(new DataPoint(e.date(), runningTotal));
        }

        // Roth: extend the seed point to the right with a second flat point so the line
        // stays visible across the rest of the chart range.
        if (hasRothSeed && !events.isEmpty()) {
            String latest = events.get(events.size() - 1).date();
            List<DataPoint> roth = byAccount.get(ACCOUNT_LABELS.get(Account.ROTH_IRA));
            if (!roth.isEmpty() && !roth.get(roth.size() - 1).date().equals(latest)) {
                roth.add(new DataPoint(latest, rothSeed));
            }
        }

        List<AccountLine> lines = new ArrayList<>();
        byAccount.forEach((label, points) -> {
            if (!points.isEmpty()) lines.add(new AccountLine(label, points));
        });
        if (!totalPoints.isEmpty()) lines.add(new AccountLine("Total", totalPoints));

        return new ContributionTimeline(lines);
    }

    private record TimedEvent(String date, String account, BigDecimal amount) {
    }

    public record DataPoint(String date, BigDecimal cumulativeGbp) {
    }

    public record AccountLine(String label, List<DataPoint> points) {
    }

    public record ContributionTimeline(List<AccountLine> lines) {
    }
}
