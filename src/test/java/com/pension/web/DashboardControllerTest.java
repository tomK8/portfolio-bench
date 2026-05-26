package com.pension.web;

import com.pension.port.FxRateProvider;
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
class DashboardControllerTest {

    /** Override the live FX adapter so the test never hits the network. */
    @TestConfiguration
    static class FakeFxConfig {
        @Bean
        @Primary
        FxRateProvider fakeFxRateProvider() {
            return () -> Map.of("GBP", BigDecimal.ONE, "USD", new BigDecimal("1.25"));
        }
    }

    static Path inputDir;
    static Path dbDir;

    @DynamicPropertySource
    static void temporaryDirs(DynamicPropertyRegistry registry) throws IOException {
        inputDir = Files.createTempDirectory("pa-input");
        dbDir = Files.createTempDirectory("pa-db");
        Files.writeString(inputDir.resolve("11111111-1111-1111-1111-111111111111.csv"),
                "Symbol,Qty,Market Value,Book Cost\nAAPL,10,$1500.00,$1000.00\n");
        registry.add("pension.input-dir", inputDir::toString);
        registry.add("pension.db-dir", dbDir::toString);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void dashboardShowsSyncForm() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sync portfolio")));
    }

    @Test
    void syncRendersPortfolioTable() throws Exception {
        mvc.perform(post("/sync").param("iiSippCash", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AAPL")))
                .andExpect(content().string(containsString("Total value")));
    }
}
