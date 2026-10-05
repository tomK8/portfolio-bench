package com.portfolio.parser;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The AJ Bell SIPP's {@code portfolio*.csv} export — rows whose {@code Portfolio} column reads
 * "SIPP". The format, and every field convention in it, is {@link AJBellPortfolioParser}'s.
 *
 * <p>This account is the <b>default</b> for an export that names no portfolio at all: the column
 * is present in every export AJ Bell produces today, but a file archived before the dealing
 * account existed can only have been the SIPP's.
 */
public class AJBellSippParser extends AJBellPortfolioParser {

    public AJBellSippParser() {
        this(Map.of());
    }

    public AJBellSippParser(Map<String, BigDecimal> liveRates) {
        super(liveRates);
    }

    @Override
    public String sourceName() {
        return "AJ Bell SIPP";
    }

    @Override
    protected String portfolioName() {
        return "SIPP";
    }

    @Override
    protected boolean isDefaultAccount() {
        return true;
    }
}
