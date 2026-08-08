package com.portfolio.domain.momentum;

import java.util.List;

/**
 * How a rebalance scores and orders the eligible universe at bar {@code i} — the only thing the
 * five signal variants are allowed to differ in (plus the {@code RotationBacktester} overlays:
 * V4's gate is a {@link RotationBacktester.Selector}, V5's stop lives in the daily loop).
 * Everything downstream — weights, costs, drift, cash — is the shared accounting path.
 *
 * <p>The incumbent ranker delegates to {@link Momentum#rank} verbatim, so a backtest run through
 * the {@code Ranker}-parameterised path with {@link #incumbent} is bit-identical to the original
 * fixed-signal path (asserted in tests — the V1 reproduction requirement).
 */
public interface Ranker {

    /** Ranked eligible universe at bar {@code i}, strongest first, ranks 1-based. */
    List<Momentum.Ranked> rank(MomentumSeries s, int i);

    /** The incumbent 12-1 signal exactly as the Momentum tab runs it. */
    static Ranker incumbent(MomentumConfig cfg) {
        return (s, i) -> Momentum.rank(s, i, cfg.lookbackSessions(), cfg.skipSessions());
    }
}
