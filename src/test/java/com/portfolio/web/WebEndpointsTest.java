package com.portfolio.web;

import com.portfolio.port.FxRateProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebEndpointsTest {

    static Path inputDir;
    static Path dbDir;
    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void temporaryDirs(DynamicPropertyRegistry registry) throws IOException {
        inputDir = Files.createTempDirectory("pa-input");
        dbDir = Files.createTempDirectory("pa-db");
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");
        registry.add("portfolio.input-dir", inputDir::toString);
        registry.add("portfolio.db-dir", dbDir::toString);
    }

    @Test
    void dashboardShowsSyncForm() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Aggregated Portfolio")));
    }

    @Test
    void syncRendersPortfolioTable() throws Exception {
        mvc.perform(post("/sync").param("iiSippCash", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AAPL")))
                .andExpect(content().string(containsString("Total value")))
                .andExpect(content().string(containsString("Dividends (£)")))
                .andExpect(content().string(containsString("Total Gain (£)")))
                .andExpect(content().string(containsString("Total Gain %")));
    }

    @Test
    void dashboardShowsMomentumTab() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("panel-momentum")));
    }

    @Test
    void momentumEndpointServializesEvenWithNoPriceData() throws Exception {
        // Essentially no price history is loaded in this fixture, so the report degrades to a
        // warning — but the bean must wire, the config echo + survivorship caveat must render, and
        // JSON must serialize. Which of the two "not enough data" warnings fires depends on how far
        // the context's startup price jobs got in the background, so assert only the shared
        // "Backfill" nudge.
        mvc.perform(get("/momentum").param("bootstrap", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("survivorshipCaveat")))
                .andExpect(content().string(containsString("Backfill")));
    }

    /**
     * Override the live FX adapter so the test never hits the network.
     */
    @TestConfiguration
    static class FakeFxConfig {
        @Bean
        @Primary
        FxRateProvider fakeFxRateProvider() {
            return () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));
        }
    }
}
