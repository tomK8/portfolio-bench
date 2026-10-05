package com.portfolio.parser;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The AJ Bell Dealing account's {@code portfolio*.csv} export — rows whose {@code Portfolio}
 * column reads "Dealing account". The format is identical to the SIPP's in every respect, so all
 * of it lives in {@link AJBellPortfolioParser}; only the account differs.
 *
 * <p>Note the filename carries it too ({@code portfolio-A50742D-Dealing account.csv} against the
 * SIPP's {@code portfolio-A50742S-SIPP.csv}), but the column is what is tested — it is the
 * broker's own statement of the account and survives being renamed on the way to disk.
 */
public class AJBellDealingParser extends AJBellPortfolioParser {

    public AJBellDealingParser() {
        this(Map.of());
    }

    public AJBellDealingParser(Map<String, BigDecimal> liveRates) {
        super(liveRates);
    }

    @Override
    public String sourceName() {
        return "AJ Bell Dealing";
    }

    @Override
    protected String portfolioName() {
        return "Dealing account";
    }
}
