package com.portfolio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic ticker → SEC CIK resolver backed by SEC's canonical
 * {@code https://www.sec.gov/files/company_tickers.json} (one JSON object of
 * {@code {cik_str, ticker, title}} rows for every ~10k US filers). Frees the earnings proxy from a
 * hand-maintained CIK map: any new swing name resolves automatically.
 *
 * <p>The file is fetched once and cached to {@code <db-dir>/sec_company_tickers.json}; subsequent
 * runs read the disk copy while it is younger than {@link #TTL}. A fetch failure with a stale disk
 * copy falls back to that copy; with neither, the registry is simply empty and callers fall back to
 * their own overrides. Loading is lazy (first {@link #cik} call), so bean construction never blocks
 * on the network.
 *
 * <p>Only bare US tickers appear in the file, so foreign/suffixed symbols (e.g. {@code SEMI.L})
 * resolve to {@link Optional#empty()} — exactly the "not a US 10-Q/10-K filer" signal §D wants.
 */
public class CikRegistry {

    private static final Logger log = LoggerFactory.getLogger(CikRegistry.class);

    private static final String TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String CACHE_FILE = "sec_company_tickers.json";
    private static final String USER_AGENT = "PortfolioBench tomaszp@gmail.com";
    private static final Duration TTL = Duration.ofDays(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Path cacheFile;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Uppercase ticker → CIK. Null until {@link #ensureLoaded()} runs. */
    private volatile Map<String, Integer> byTicker;

    public CikRegistry(Path dbDir) {
        this.cacheFile = dbDir.resolve(CACHE_FILE);
    }

    /** CIK for {@code ticker} (case-insensitive), or empty when unknown / unresolvable. */
    public Optional<Integer> cik(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        ensureLoaded();
        return Optional.ofNullable(byTicker.get(ticker.trim().toUpperCase()));
    }

    /** Force a re-fetch of the SEC file on the next {@link #cik} call (drops the in-memory map). */
    public void refresh() {
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException e) {
            log.warn("Could not clear CIK cache {}", cacheFile, e);
        }
        byTicker = null;
    }

    private void ensureLoaded() {
        if (byTicker != null) return;
        synchronized (this) {
            if (byTicker != null) return;
            String json = readFreshCache();
            if (json == null) {
                json = fetch();
                if (json != null) writeCache(json);
            }
            if (json == null) json = readStaleCache();          // last resort: an expired copy beats nothing
            byTicker = json == null ? Map.of() : parse(json);
            log.info("CIK registry loaded: {} tickers", byTicker.size());
        }
    }

    /** Disk copy if present and younger than {@link #TTL}, else null. */
    private String readFreshCache() {
        try {
            if (!Files.exists(cacheFile)) return null;
            Instant modified = Files.getLastModifiedTime(cacheFile).toInstant();
            if (modified.isBefore(Instant.now().minus(TTL))) return null;
            return Files.readString(cacheFile);
        } catch (IOException e) {
            log.warn("Could not read CIK cache {}", cacheFile, e);
            return null;
        }
    }

    private String readStaleCache() {
        try {
            return Files.exists(cacheFile) ? Files.readString(cacheFile) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(String json) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, json);
        } catch (IOException e) {
            log.warn("Could not write CIK cache {}", cacheFile, e);
        }
    }

    /** HTTP GET the SEC tickers file; null on any failure (logged). Package-visible for tests to stub. */
    String fetch() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(TICKERS_URL))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("SEC company_tickers HTTP {}", resp.statusCode());
        } catch (Exception e) {
            log.warn("SEC company_tickers fetch failed", e);
        }
        return null;
    }

    /** Parse the {@code {"0":{cik_str,ticker,title},…}} shape into an uppercase ticker → CIK map. */
    static Map<String, Integer> parse(String json) {
        Map<String, Integer> out = new HashMap<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            for (JsonNode row : root) {
                String ticker = row.path("ticker").asText(null);
                JsonNode cik = row.path("cik_str");
                if (ticker == null || ticker.isBlank() || cik.isMissingNode() || !cik.canConvertToInt()) continue;
                out.put(ticker.trim().toUpperCase(), cik.asInt());
            }
        } catch (Exception e) {
            log.warn("Could not parse SEC company_tickers JSON", e);
        }
        return out;
    }
}
