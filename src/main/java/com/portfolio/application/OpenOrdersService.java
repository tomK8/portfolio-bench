package com.portfolio.application;

import com.portfolio.ExcelReportWriter;
import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.domain.model.OpenOrder;
import com.portfolio.parser.OrderParser;
import com.portfolio.port.FxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only view of resting (open) orders across brokers. Locates the most recent order-status
 * export per broker in the input directory (same "newest file wins" rule as holdings), parses each
 * with its {@link OrderParser}, and keeps only orders whose status is non-terminal. Otherwise a pure
 * disk read — the one non-file dependency is a current FX snapshot, used to fill each order's GBP
 * amount (degrades to null + a warning if the fetch fails).
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HoldingFileLocator fileLocator;
    private final List<OrderParser> parsers;
    private final FxRateProvider fxRateProvider;
    private final ExcelReportWriter excelReportWriter;
    private final Path outputDir;

    public OpenOrdersService(HoldingFileLocator fileLocator, List<OrderParser> parsers,
                             FxRateProvider fxRateProvider, ExcelReportWriter excelReportWriter,
                             Path outputDir) {
        this.fileLocator = fileLocator;
        this.parsers = parsers;
        this.fxRateProvider = fxRateProvider;
        this.excelReportWriter = excelReportWriter;
        this.outputDir = outputDir;
    }

    /**
     * Writes the current open orders to a date-stamped {@code Open Orders-YYYYMMDD.xlsx} in the
     * output directory (defaults to ~/Documents — same place the portfolio exports land, not the
     * browser's download folder). Returns the written path, or {@link ExportResult#nothing()} when
     * no order files were found at all.
     */
    public ExportResult export() {
        OrdersReport report = load();
        if (report.empty()) return ExportResult.nothing();
        try {
            Files.createDirectories(outputDir);
            Path file = outputDir.resolve("Open Orders-" + LocalDate.now().format(DATE_FMT) + ".xlsx");
            Files.write(file, excelReportWriter.ordersWorkbook(report.orders()));
            return new ExportResult(false, List.of(file.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write open-orders Excel to " + outputDir, e);
        }
    }

    public OrdersReport load() {
        List<OpenOrder> orders = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, BigDecimal> rates = Map.of();
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            warnings.add("FX rates unavailable — GBP amounts not shown (" + e.getMessage() + ")");
            log.warn("FX fetch failed for open-orders GBP conversion", e);
        }

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
                    if (isOpen(o.status())) open.add(o.withAmountGbp(toGbp(o.amount(), o.currency(), rates)));
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
     * Convert a native-currency amount to GBP. Rates are units of foreign currency per 1 GBP, so
     * native → GBP divides (the app-wide convention). Returns null when the amount, currency or a
     * usable rate is missing — the view then shows "—" rather than a wrong number.
     */
    static BigDecimal toGbp(BigDecimal amount, String currency, Map<String, BigDecimal> rates) {
        if (amount == null || currency == null) return null;
        if ("GBP".equals(currency)) return amount.setScale(2, RoundingMode.HALF_UP);
        if ("GBp".equals(currency)) return amount.movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = rates.get(currency);
        if (rate == null || rate.signum() == 0) return null;
        return amount.divide(rate, 2, RoundingMode.HALF_UP);
    }

    /**
     * @param empty true when no order file was found for any broker (distinct from "files found,
     *              but nothing open") so the view can show the right message.
     */
    public record OrdersReport(List<OpenOrder> orders, List<String> sources,
                               List<String> warnings, boolean empty) {
    }
}
