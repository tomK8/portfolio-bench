package com.portfolio.application;

import com.portfolio.domain.model.CashTransaction;
import com.portfolio.parser.CashTransactionParser;
import com.portfolio.parser.ParseException;
import com.portfolio.persistence.CashTransactionRepository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Shared mechanics for {@link CashImporter} implementations: glob matching, parsing,
 * archive-or-delete on import. Subclasses say <em>what</em> to import; this base says
 * <em>how</em>.
 */
abstract class AbstractCashImporter implements CashImporter {

    protected final Path archiveDir;
    protected final CashTransactionRepository repo;

    protected AbstractCashImporter(Path archiveDir, CashTransactionRepository repo) {
        this.archiveDir = archiveDir;
        this.repo = repo;
    }

    /** All files in {@code inputDir} matching {@code glob}; empty when the directory is absent. */
    protected static List<Path> matchingFiles(Path inputDir, String glob) {
        if (!Files.isDirectory(inputDir)) return List.of();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, glob)) {
            List<Path> out = new ArrayList<>();
            for (Path p : stream) out.add(p);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan input directory " + inputDir, e);
        }
    }

    protected static Optional<Path> mostRecent(Path inputDir, String glob) {
        return matchingFiles(inputDir, glob).stream()
                .max(Comparator.comparingLong(AbstractCashImporter::mtime));
    }

    protected static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    protected static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    protected static List<CashTransaction> parse(CashTransactionParser parser, Path file) {
        try {
            return parser.parse(file);
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Failed to parse cash statement " + file, e);
        }
    }

    /**
     * Archive on imported rows, delete on duplicate. The archive name is
     * {@code <prefix>_<today>.<ext>} under the archive dir.
     */
    protected ImportCashResult archiveOrDelete(String source, Path file, int inserted, String archivePrefix) {
        String sourceFile = file.getFileName().toString();
        try {
            if (inserted > 0) {
                Path archived = archiveDir.resolve(archivePrefix + "_" + LocalDate.now() + extension(file));
                Files.move(file, archived, StandardCopyOption.REPLACE_EXISTING);
                return ImportCashResult.imported(source, inserted, sourceFile, archived.toString());
            }
            Files.delete(file);
            return ImportCashResult.noNewData(source, sourceFile);
        } catch (IOException e) {
            throw new IllegalStateException("Imported rows but could not archive/remove " + file, e);
        }
    }
}
