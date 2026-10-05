package com.portfolio.application;

/**
 * Outcome of importing one cash source. One row per source: IMPORTED / NO_NEW_DATA when a
 * file was processed, NOT_FOUND when no matching file existed in the input dir, REFUSED when one
 * did and was deliberately not read.
 *
 * <p>{@code sourceFile} is the filename that was processed (without directory), or {@code null}
 * for NOT_FOUND. {@code detail} carries the archived file path for IMPORTED, the reason for
 * REFUSED, otherwise null.
 *
 * <p><b>REFUSED is not a failure, it is a refusal to guess.</b> Two AJ Bell accounts export cash
 * statements that are identical in name, header and format, so a statement whose account cannot be
 * established is left on disk untouched and reported. Filing one against the wrong ledger would
 * corrupt both at once, and the AJ Bell dedup key is scoped per account — the next import would
 * not repair it.
 */
public record ImportCashResult(String source, Status status, int inserted,
                               String sourceFile, String detail) {

    public static ImportCashResult noNewData(String source, String sourceFile) {
        return new ImportCashResult(source, Status.NO_NEW_DATA, 0, sourceFile, null);
    }

    public static ImportCashResult imported(String source, int inserted,
                                            String sourceFile, String archivedTo) {
        return new ImportCashResult(source, Status.IMPORTED, inserted, sourceFile, archivedTo);
    }

    public static ImportCashResult notFound(String source) {
        return new ImportCashResult(source, Status.NOT_FOUND, 0, null, null);
    }

    /** A file was found, nothing was read from it, and it is still on disk. */
    public static ImportCashResult refused(String source, String sourceFile, String reason) {
        return new ImportCashResult(source, Status.REFUSED, 0, sourceFile, reason);
    }

    public enum Status {NO_NEW_DATA, IMPORTED, NOT_FOUND, REFUSED}
}
