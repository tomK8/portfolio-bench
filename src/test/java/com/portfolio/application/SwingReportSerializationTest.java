package com.portfolio.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapter.YahooTickerMap;
import com.portfolio.config.JacksonConfig;
import com.portfolio.domain.model.PriceBar;
import com.portfolio.domain.swing.SwingConfig;
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
 * Serializes the full {@link SwingReport} graph through the same Jackson customizer the web layer
 * uses ({@link JacksonConfig}), guarding against the {@code NaN}/{@code Infinity} tokens that empty
 * distributions (empty quartiles, zero-size bootstrap pools) would otherwise emit — invalid JSON
 * that takes down the whole endpoint.
 */
class SwingReportSerializationTest {

    @TempDir
    Path dbDir;

    private final YahooTickerMap tickers = new YahooTickerMap();

    private ObjectMapper springLikeMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().nonFiniteDoublesAsNull().customize(builder);
        return builder.build();
    }

    private List<PriceBar> path(String ticker) {
        double[] rets = {0.01, -0.02, 0.03, -0.06, 0.02, 0.01, -0.03, 0.04, -0.05, 0.02};
        List<PriceBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.now().minusDays(80);
        double close = 100;
        for (int i = 0; i < 80; i++) {
            if (i > 0) close *= (1 + rets[i % rets.length]);
            bars.add(new PriceBar(ticker, d, null, close, close, close, close, 1.0, null, "USD"));
            d = d.plusDays(1);
        }
        return bars;
    }

    @Test
    void fullReportSerialisesWithoutNonFiniteTokens() throws Exception {
        var priceRepo = new PriceHistoryRepository(new JdbcConnectionFactory(dbDir));
        priceRepo.savePriceBars(path(tickers.tickerFor("AAA")));
        priceRepo.savePriceBars(path(tickers.tickerFor("BBB")));
        // CCC has no data → a missing-data symbol in the payload.

        var service = new SwingAnalysisService(priceRepo, tickers, new KeyValueStore(dbDir), null, null);
        SwingConfig cfg = service.baseConfigBuilder()
                .symbols(List.of("AAA", "BBB", "CCC"))
                .cluster(List.of("AAA", "BBB"))
                .bootstrapIterations(100)
                .build();
        SwingReport report = service.report(cfg);

        ObjectMapper mapper = springLikeMapper();
        String json = mapper.writeValueAsString(report);

        // The bare NaN/Infinity tokens Jackson emits by default are invalid JSON; must be absent.
        assertFalse(json.contains("NaN"), "payload must not contain a bare NaN token");
        assertFalse(json.contains("Infinity"), "payload must not contain a bare Infinity token");

        // And it must round-trip (a stronger check than substring matching).
        JsonNode root = mapper.readTree(json);
        assertEquals(3, root.get("perSymbol").size());
        JsonNode family = root.get("pooled").get("full").get("families").get(0);
        JsonNode barriers = family.get("variants").get(0).get("barriers");
        assertTrue(barriers.size() > 0);
        assertTrue(barriers.get(0).has("bootstrap"));
        // §A/§B/§E graph must serialise too (they carry nullable/NaN-prone figures).
        assertTrue(family.has("eventLog"));
        assertTrue(family.has("annual"));
        assertTrue(family.get("maSplit").has("above"));
        assertTrue(root.get("earningsHygiene").has("coverage"));
    }
}
