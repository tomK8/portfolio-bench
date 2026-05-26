package com.pension.port;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Supplies GBP FX rates as units of foreign currency per 1 GBP
 * (e.g. {"USD": 1.3621}); always includes {"GBP": 1}.
 */
public interface FxRateProvider {

    Map<String, BigDecimal> fetchRates() throws Exception;
}
