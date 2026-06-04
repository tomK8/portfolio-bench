package com.portfolio.application;

import java.util.List;

/**
 * Outcome of an Excel export: the files written, or empty when there was nothing to export.
 */
public record ExportResult(boolean empty, List<String> files) {

    public static ExportResult nothing() {
        return new ExportResult(true, List.of());
    }
}
