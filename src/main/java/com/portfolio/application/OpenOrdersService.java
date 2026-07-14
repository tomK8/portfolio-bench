package com.portfolio.application;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.parser.OrderParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only view of resting (open) orders across brokers. Locates the most recent order-status
 * export per broker in the input directory (same "newest file wins" rule as holdings), parses each
 * with its {@link OrderParser}, and keeps only orders whose status is non-terminal. No DB, no FX —
 * a pure disk read, so it always reflects whatever was last exported to {@code ~/Downloads}.
 */
public class OpenOrdersService {

    private static final Logger log = LoggerFactory.getLogger(OpenOrdersService.class);

    /**
     * Statuses that mean the order is no longer working. Everything else (In progress / PENDING /
     * Open / partially-executed variants) counts as open — a denylist so an unseen "still working"
     * status is shown rather than silently dropped.
     */
    private static final Set<String> TERMINAL = Set.of(
            "dealt", "executed", "filled", "part executed", "cancelled", "canceled",
            "expired", "rejected", "completed", "done", "settled");

    private final HoldingFileLocator fileLocator;
    private final List<OrderParser> parsers;

    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers) {
        this.fileLocator = fileLocator;
        this.parsers = parsers;
    }

    public OrdersReport load() {
        List<OpenOrder> orders = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (OrderParser parser : parsers) {
            Optional<Path> file;
            try {
                file = fileLocator.findMostRecent(parser::supports);
            } catch (Exception e) {
                warnings.add(parser.sourceName() + ": could not scan input directory (" + e.getMessage() + ")");
                log.warn("Order file scan failed for {}", parser.sourceName(), e);
                continue;
            }
            if (file.isEmpty()) continue;

            try {
                List<OpenOrder> open = new ArrayList<>();
                for (OpenOrder o : parser.parse(file.get())) {
                    if (isOpen(o.status())) open.add(o);
                }
                open.sort(Comparator
                        .comparing(OpenOrder::dateEntered, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(o -> displayKey(o), Comparator.nullsLast(Comparator.naturalOrder())));
                orders.addAll(open);
                sources.add(parser.sourceName() + " — " + file.get().getFileName());
            } catch (Exception e) {
                warnings.add(parser.sourceName() + ": failed to parse " + file.get().getFileName()
                        + " (" + e.getMessage() + ")");
                log.warn("Order parse failed for {} ({})", parser.sourceName(), file.get(), e);
            }
        }

        return new OrdersReport(orders, sources, warnings, orders.isEmpty() && sources.isEmpty());
    }

    private static String displayKey(OpenOrder o) {
        if (o.symbol() != null && !o.symbol().isBlank()) return o.symbol();
        return o.name();
    }

    static boolean isOpen(String status) {
        if (status == null || status.isBlank()) return true;   // unknown → show it
        return !TERMINAL.contains(status.trim().toLowerCase());
    }

    /**
     * @param empty true when no order file was found for any broker (distinct from "files found,
     *              but nothing open") so the view can show the right message.
     */
    public record OrdersReport(List<OpenOrder> orders, List<String> sources,
                               List<String> warnings, boolean empty) {
    }
}
