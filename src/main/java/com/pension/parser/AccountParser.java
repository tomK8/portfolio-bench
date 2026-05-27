package com.pension.parser;

import com.pension.domain.model.Holding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Contract for all account file parsers.
 * Each parser handles one broker/account format and returns normalised Holdings.
 */
public interface AccountParser {

    /**
     * Parse the given file and return all holdings found within it.
     *
     * @param file path to the downloaded holdings file
     * @return non-null, possibly empty list of holdings
     * @throws IOException    if the file cannot be read
     * @throws ParseException if the file format is unrecognised or malformed
     */
    List<Holding> parse(Path file) throws IOException, ParseException;

    /**
     * Returns true if this parser can handle the given file
     * (used for auto-detection when multiple parsers are registered).
     */
    boolean supports(Path file);

    /**
     * Human-readable account name, used as the tab label in the output workbook.
     */
    String sourceName();
}
