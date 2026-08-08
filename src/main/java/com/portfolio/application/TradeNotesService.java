package com.portfolio.application;

import com.portfolio.persistence.CashTransactionRepository;
import com.portfolio.persistence.CashTransactionRepository.TradeRow;
import com.portfolio.persistence.TradeNotesRepository;
import com.portfolio.persistence.TradeNotesRepository.TradeNote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Trade-journal view: every TRANSACTION row from {@code cash_transactions} joined with
 * its optional {@code trade_notes} annotation, plus a tag-frequency aggregate so the
 * UI can show "you tagged 14 trades FOMO; 9 conviction; …" up top. The whole point is
 * the user studying their own decision patterns — the aggregate is what surfaces them.
 */
public class TradeNotesService {

    private final CashTransactionRepository trades;
    private final TradeNotesRepository notes;

    public TradeNotesService(CashTransactionRepository trades, TradeNotesRepository notes) {
        this.trades = trades;
        this.notes = notes;
    }

    public TradeJournal journal() {
        List<TradeRow> rows = trades.loadTrades();
        Map<Long, TradeNote> noteByRowid = notes.loadAll();
        List<TradeEntry> entries = new ArrayList<>(rows.size());
        Map<String, Integer> tagCounts = new TreeMap<>();
        int annotated = 0;
        for (TradeRow r : rows) {
            TradeNote n = noteByRowid.get(r.rowid());
            String note = n == null ? "" : nullToEmpty(n.note());
            String tagsCsv = n == null ? "" : nullToEmpty(n.tags());
            List<String> tagList = splitTags(tagsCsv);
            // "correction" trades are bookkeeping fixes (commission rebookings, reversals).
            // They aren't decisions, so we don't count them in the annotation summary
            // and don't fold their tags into the psychology frequencies — otherwise every
            // broker rebooking would skew the stats.
            boolean isCorrection = tagList.contains("correction");
            if (!isCorrection && (!note.isBlank() || !tagList.isEmpty())) annotated++;
            if (!isCorrection) for (String t : tagList) tagCounts.merge(t, 1, Integer::sum);
            // Side from amount sign (cash in = SELL, cash out = BUY). Brokers disagree on
            // whether quantity is signed; amount is consistent across all our parsers.
            String side = r.amount() >= 0 ? "SELL" : "BUY";
            entries.add(new TradeEntry(
                    r.rowid(), r.transactionDate(), r.account().dbValue(),
                    side, r.symbol(), Math.abs(r.quantity()), r.amount(), r.currency(),
                    r.amountGbp(), pricePerShare(r.quantity(), r.amount()),
                    r.description(), note, tagList,
                    n == null ? null : n.updatedAt()));
        }
        return new TradeJournal(entries, new Summary(entries.size(), annotated, tagCounts));
    }

    public TradeJournal save(long rowid, String note, String tags) {
        String cleanTags = normaliseTags(tags);
        String cleanNote = note == null ? "" : note.trim();
        notes.save(rowid, cleanNote, cleanTags, Instant.now().toString());
        return journal();
    }

    private static List<String> splitTags(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : csv.split(",")) {
            String tt = t.trim().toLowerCase();
            if (!tt.isEmpty() && !out.contains(tt)) out.add(tt);
        }
        return out;
    }

    /** De-dup, lowercase, comma-join. Empty input → empty output (triggers delete in repo). */
    private static String normaliseTags(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return String.join(",", splitTags(raw));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Effective price per share in the trade's own currency, or {@code null} where the row
     * doesn't represent a purchase or sale at a price.
     *
     * <p>The ledger stores no price column — only quantity and the cash amount — so this is
     * derived. That makes it the price <em>including</em> whatever costs the broker folded
     * into the cash movement (AJ Bell's amounts carry commission and stamp duty; the Roth
     * and II amounts we see are gross trade value), which is why it's labelled "effective"
     * rather than presented as the execution price.
     *
     * <p>Null rather than zero for the rows where the quotient is meaningless: a zero
     * quantity (nothing to divide by) and a zero amount, which in this ledger means a
     * "Stock Split Received" row — shares arriving for no cash. Those are real ledger
     * entries, not errors, so they stay in the journal and simply show no price.
     */
    static Double pricePerShare(double quantity, double amount) {
        if (quantity == 0 || amount == 0) return null;
        return Math.abs(amount) / Math.abs(quantity);
    }

    public record TradeEntry(long rowid, String date, String account, String side, String symbol,
                             double quantity, double amount, String currency, double amountGbp,
                             Double pricePerShare, String description, String note,
                             List<String> tags, String updatedAt) {
    }

    public record Summary(int total, int annotated, Map<String, Integer> tagCounts) {
    }

    public record TradeJournal(List<TradeEntry> trades, Summary summary) {
    }
}
