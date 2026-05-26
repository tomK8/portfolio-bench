package com.pension.application;

import com.pension.adapter.HoldingFileLocator;
import com.pension.domain.model.Holding;
import com.pension.parser.AJBellSippParser;
import com.pension.parser.AccountParser;
import com.pension.parser.IISippParser;
import com.pension.parser.ParseException;
import com.pension.parser.RothIraParser;
import com.pension.port.FxRateProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads everything an operation needs from disk: live FX rates plus the most
 * recent supported file for each account parser. Shared by the operations that
 * need holdings (sync, Excel export) so the gather logic lives in one place.
 */
public class PortfolioGatherer {

    private final FxRateProvider fxRateProvider;
    private final HoldingFileLocator fileLocator;

    public PortfolioGatherer(FxRateProvider fxRateProvider, HoldingFileLocator fileLocator) {
        this.fxRateProvider = fxRateProvider;
        this.fileLocator = fileLocator;
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

        return new GatheredPortfolio(rates, holdings, sources);
    }
}
