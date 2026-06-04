package com.portfolio.application;

import com.portfolio.domain.model.Holding;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Raw material read from disk for one run: FX rates, every parsed holding, and
 * the source file behind each account (source name -&gt; file path, in parse order).
 */
public record GatheredPortfolio(
        Map<String, BigDecimal> rates,
        List<Holding> holdings,
        Map<String, Path> sources) {
}
