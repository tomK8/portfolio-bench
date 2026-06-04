package com.portfolio.adapter;

import com.portfolio.parser.AccountParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * Locates broker export files in a single input directory (default ~/Downloads).
 * For a given parser, returns the most recently modified file that parser supports.
 * Single concrete implementation by design — there is no second file source, so no port.
 */
public class HoldingFileLocator {

    private final Path inputDir;

    public HoldingFileLocator() {
        this(Path.of(System.getProperty("user.home"), "Downloads"));
    }

    public HoldingFileLocator(Path inputDir) {
        this.inputDir = inputDir;
    }

    public Path inputDir() {
        return inputDir;
    }

    public Optional<Path> findMostRecent(AccountParser parser) throws IOException {
        if (!Files.isDirectory(inputDir)) return Optional.empty();
        try (var stream = Files.list(inputDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(parser::supports)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        }
    }
}
