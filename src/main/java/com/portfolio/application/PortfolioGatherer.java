package com.portfolio.application;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.domain.model.Holding;
import com.portfolio.parser.*;
import com.portfolio.port.FxRateProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads everything an operation needs from disk: live FX rates plus the most
 * recent supported file for each account parser. Shared by the operations that
 * need holdings (sync, Excel export) so the gather logic lives in one place.
 */
public class PortfolioGatherer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PortfolioGatherer.class);

    private final FxRateProvider fxRateProvider;
    private final HoldingFileLocator fileLocator;
    private final ManualPositionService manualPositions;

    public PortfolioGatherer(FxRateProvider fxRateProvider, HoldingFileLocator fileLocator) {
        this(fxRateProvider, fileLocator, null);
    }

    /**
     * @param manualPositions accounts with no downloadable export, whose holdings are derived from
     *                        the cash ledger instead of a file; null when there are none
     */
    public PortfolioGatherer(FxRateProvider fxRateProvider, HoldingFileLocator fileLocator,
                             ManualPositionService manualPositions) {
        this.fxRateProvider = fxRateProvider;
        this.fileLocator = fileLocator;
        this.manualPositions = manualPositions;
    }

    public GatheredPortfolio gather() {
        Map<String, BigDecimal> rates;
        try {
            rates = fxRateProvider.fetchRates();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch FX rates", e);
        }

        // AJBellSippParser needs the live rates, so parsers are built per run.
        List<AccountParser> parsers = List.of(
                new RothIraParser(),
                new AJBellSippParser(rates),
                new IISippParser());

        List<Holding> holdings = new ArrayList<>();
        Map<String, Path> sources = new LinkedHashMap<>();
        try {
            for (AccountParser parser : parsers) {
                Optional<Path> file = fileLocator.findMostRecent(parser);
                if (file.isPresent()) {
                    holdings.addAll(parser.parse(file.get()));
                    sources.put(parser.sourceName(), file.get());
                }
            }
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to read or parse input files", e);
        }

        // Hand-entered accounts have no file to locate, so they are appended rather than parsed.
        // A failure here must not take the whole sync down with it: the broker exports parsed
        // fine and are worth showing.
        if (manualPositions != null) {
            try {
                List<Holding> manual = manualPositions.holdings();
                if (!manual.isEmpty()) {
                    holdings.addAll(manual);
                    for (Holding h : manual) sources.putIfAbsent(h.getSource(), null);
                }
            } catch (Exception e) {
                log.warn("Could not read manually entered positions — continuing without them", e);
            }
        }

        return new GatheredPortfolio(rates, holdings, sources);
    }
}
