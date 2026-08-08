package com.portfolio.parser;

import com.portfolio.domain.model.OpenOrder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Parses a broker's order-status export into {@link OpenOrder}s. Mirrors {@link AccountParser}'s
 * shape (a {@code supports} predicate + a {@code parse}) but returns orders, and returns <b>all</b>
 * orders in the file regardless of status — {@code OpenOrdersService} applies the open/closed filter
 * so the terminal-status vocabulary lives in one place across brokers.
 */
public interface OrderParser {

    /** Human label for the broker, shown in the Orders table and the sources line. */
    String sourceName();

    /** True if this parser recognises the file (by name, and for UUID files a header sniff). */
    boolean supports(Path file);

    /** Parse every order row in the file. Malformed individual rows are skipped, not thrown. */
    List<OpenOrder> parse(Path file) throws IOException;
}
