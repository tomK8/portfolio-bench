package com.portfolio.application;

import com.portfolio.domain.model.PriceBar;
import com.portfolio.parser.ParseException;
import com.portfolio.parser.TradewebGiltPriceParser;
import com.portfolio.persistence.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks {@code ~/Downloads} for {@code Tradeweb_FTSE_ClosePrices*.csv}, parses each, upserts the
 * rows into {@code price_history}, and archives the file to the DB dir (matching the cash-import
 * UX). One file per gilt; the archived filename embeds the gilt symbol so multiple gilts dropped
 * the same day don't collide.
 *
 * <p>Invoked both from the dashboard ({@code POST /import-gilt-prices}) and from a startup hook
 * in {@code PriceFetchScheduler}, so files dropped between runs are picked up automatically.
 */
public class ImportGiltPricesService {

    private static final Logger log = LoggerFactory.getLogger(ImportGiltPricesService.class);

    private static final String GLOB = "Tradeweb_FTSE_ClosePrices*.csv";

    private final Path inputDir;
    private final Path archiveDir;
    private final PriceHistoryRepository repo;
    private final TradewebGiltPriceParser parser = new TradewebGiltPriceParser();

    public ImportGiltPricesService(Path inputDir, Path archiveDir, PriceHistoryRepository repo) {
        this.inputDir = inputDir;
        this.archiveDir = archiveDir;
        this.repo = repo;
    }

    public List<GiltPriceImportResult> importAll() {
        List<Path> files = matchingFiles();
        if (files.isEmpty()) return List.of(GiltPriceImportResult.notFound());

        List<GiltPriceImportResult> results = new ArrayList<>(files.size());
        for (Path file : files) results.add(importOne(file));
        return results;
    }

    private GiltPriceImportResult importOne(Path file) {
        String name = file.getFileName().toString();
        List<PriceBar> bars;
        try {
            bars = parser.parse(file);
        } catch (IOException | ParseException e) {
            log.warn("Failed to parse gilt prices from {}", name, e);
            return GiltPriceImportResult.failed(name, e.getMessage());
        }
        int touched = repo.upsertPriceBars(bars);
        String symbol = bars.get(0).symbol();
        try {
            Path archived = archiveDir.resolve(archiveName(symbol, file));
            Files.move(file, archived, StandardCopyOption.REPLACE_EXISTING);
            return GiltPriceImportResult.imported(touched, name, archived.toString());
        } catch (IOException e) {
            log.warn("Imported {} rows but could not archive {}", touched, name, e);
            return GiltPriceImportResult.failed(name, "archive failed: " + e.getMessage());
        }
    }

    private List<Path> matchingFiles() {
        if (!Files.isDirectory(inputDir)) return List.of();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, GLOB)) {
            List<Path> out = new ArrayList<>();
            for (Path p : stream) out.add(p);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan input directory " + inputDir, e);
        }
    }

    /** {@code Tradeweb_GILT_3.75pct_2038_2026-06-05.csv} — "%" isn't filename-safe. */
    private static String archiveName(String symbol, Path original) {
        String safe = symbol.replace(' ', '_').replace("%", "pct");
        String ext = extension(original);
        return "Tradeweb_" + safe + "_" + LocalDate.now() + ext;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
