package com.portfolio.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.config.JacksonConfig;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.persistence.JdbcConnectionFactory;
import com.portfolio.persistence.KeyValueStore;
import com.portfolio.persistence.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Serializes the full {@link TrendReport} graph through the same Jackson customizer the web layer
 * uses ({@link JacksonConfig}) — degenerate cells (a variant that never trades, a censored
 * drawdown, an undefined Sharpe on a flat series) must land as JSON {@code null}, never as the
 * bare {@code NaN} token that breaks the whole payload.
 */
class TrendReportSerializationTest {

    @TempDir
    Path dbDir;

    private ObjectMapper springLikeMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().nonFiniteDoublesAsNull().customize(builder);
        return builder.build();
    }

    private List<PriceBar> path(String symbol, int n) {
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.now().minusDays((long) (n * 1.5));
        double close = 100;
        for (int i = 0; i < n; i++) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            // Includes a flat stretch (undefined vol pieces) and a crash to force trades.
            double ret = i < 60 ? 0 : (i < 90 ? -0.02 : 0.005);
            if (i > 0) close *= 1 + ret;
            bars.add(new PriceBar(symbol, d, close, null, null, close, close, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return bars;
    }

    @Test
    void fullReportSerialisesWithoutNonFiniteTokens() throws Exception {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        repo.savePriceBars(path("QQQ", 200));
        // No ^NDX/^IRX → warning paths and flat-cash fallback are in the payload too.

        var service = new TrendAnalysisService(repo, new YahooTickerMap(), new KeyValueStore(dbDir), null);
        TrendReport report = service.report(service.baseConfigBuilder()
                .maLengths(new int[]{20, 30})
                .monthlyMaLengths(new int[]{2})
                .anchorEvery(5)
                .bufferPcts(new double[]{0.02})
                .bufferNs(new int[]{2})
                .bootstrapIterations(20)
                .build());

        ObjectMapper mapper = springLikeMapper();
        String json = mapper.writeValueAsString(report);

        assertFalse(json.contains("NaN"), "payload must not contain a bare NaN token");
        assertFalse(json.contains("Infinity"), "payload must not contain a bare Infinity token");

        JsonNode root = mapper.readTree(json);
        assertEquals(9, root.get("variants").size());
        JsonNode v = root.get("variants").get(0);
        assertTrue(v.has("headline"));
        assertTrue(v.has("whipsaw"));
        assertTrue(v.has("bootstrap"));
        assertTrue(v.has("episodes"));
        assertTrue(v.has("trades"));
        assertTrue(root.get("data").has("warnings"));
        assertTrue(root.get("liveState").has("variants"));
        assertEquals(2, root.get("anchorSensitivity").size());
    }

    @Test
    void emptyReportSerialisesToo() throws Exception {
        var repo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        var service = new TrendAnalysisService(repo, new YahooTickerMap(), new KeyValueStore(dbDir), null);
        TrendReport report = service.report(service.baseConfigBuilder().build());

        String json = springLikeMapper().writeValueAsString(report);
        assertFalse(json.contains("NaN"));
        JsonNode root = springLikeMapper().readTree(json);
        assertTrue(root.get("buyHold").isNull());
        assertTrue(root.get("data").get("warnings").size() > 0);
    }
}
