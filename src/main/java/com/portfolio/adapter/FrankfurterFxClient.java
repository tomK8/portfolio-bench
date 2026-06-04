package com.portfolio.adapter;

import com.portfolio.port.FxRateProvider;
import com.portfolio.port.HistoricalFxRateProvider;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FrankfurterFxClient implements FxRateProvider, HistoricalFxRateProvider {

    private static final String FX_URL =
            "https://api.frankfurter.dev/v1/latest?from=GBP&to=USD,EUR";

    @Override
    public Map<String, BigDecimal> fetchRates() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(FX_URL)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("GBP", BigDecimal.ONE);
        Matcher m = Pattern.compile("\"([A-Z]{3})\":(\\d+\\.\\d+)").matcher(response.body());
        while (m.find()) rates.put(m.group(1), new BigDecimal(m.group(2)));
        return rates;
    }

    @Override
    public Map<LocalDate, BigDecimal> fetchRateSeries(String currency, LocalDate start, LocalDate end)
            throws Exception {
        String url = "https://api.frankfurter.dev/v1/" + start + ".." + end
                + "?base=GBP&symbols=" + currency;
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Time-series body: {"rates":{"2025-10-03":{"USD":1.3447}, ...}}
        Map<LocalDate, BigDecimal> series = new TreeMap<>();
        Matcher m = Pattern.compile(
                        "\"(\\d{4}-\\d{2}-\\d{2})\":\\{\"" + currency + "\":(\\d+(?:\\.\\d+)?)\\}")
                .matcher(response.body());
        while (m.find()) series.put(LocalDate.parse(m.group(1)), new BigDecimal(m.group(2)));
        return series;
    }
}
