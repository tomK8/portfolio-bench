package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

public class FrankfurterFxClient implements FxRateProvider, HistoricalFxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterFxClient.class);

    private static final String LATEST_URL =
            "https://api.frankfurter.dev/v1/latest?from=GBP&to=USD,EUR";
    private static final String SERIES_URL_FMT =
            "https://api.frankfurter.dev/v1/%s..%s?base=GBP&symbols=%s";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Map<String, BigDecimal> fetchRates() throws Exception {
        JsonNode rates = getJson(LATEST_URL).path("rates");

        Map<String, BigDecimal> out = new HashMap<>();
        out.put("GBP", BigDecimal.ONE);
        Iterator<Map.Entry<String, JsonNode>> it = rates.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), new BigDecimal(e.getValue().asText()));
        }
        return out;
    }

    @Override
    public Map<LocalDate, BigDecimal> fetchRateSeries(String currency, LocalDate start, LocalDate end)
            throws Exception {
        String url = String.format(SERIES_URL_FMT, start, end, currency);
        JsonNode rates = getJson(url).path("rates");

        // {"rates":{"2025-10-03":{"USD":1.3447}, ...}}
        Map<LocalDate, BigDecimal> series = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = rates.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode rate = e.getValue().path(currency);
            if (!rate.isMissingNode()) {
                series.put(LocalDate.parse(e.getKey()), new BigDecimal(rate.asText()));
            }
        }
        return series;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("Frankfurter HTTP {} for {}", resp.statusCode(), url);
            throw new IllegalStateException("Frankfurter HTTP " + resp.statusCode() + " for " + url);
        }
        return mapper.readTree(resp.body());
    }
}
