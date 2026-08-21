package com.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Boots the whole application context — the one test that exercises every bean definition
 * together, and so the one that has to be pointed away from the real installation.
 *
 * <p>Without the overrides below it runs on the production defaults ({@code ~/Downloads},
 * {@code ~/Documents/Investing}, {@code ~/Documents}), because those are what a blank
 * {@code portfolio.*-dir} resolves to. That is not theoretical: on 2026-08-21 a plain
 * {@code mvn test} applied a schema migration to the live ledger and wrote intraday price rows
 * into it. The input directory is the sharper edge of the two — {@code PriceFetchScheduler}
 * kicks {@code ImportGiltPricesService} on {@code ApplicationReadyEvent}, which {@code Files.move}s
 * any {@code Tradeweb_FTSE_ClosePrices*.csv} out of the input directory and into the archive. Those
 * files are hand-downloaded and irreplaceable, so a test run must never be able to consume one.
 *
 * <p>The startup jobs still reach the network (Yahoo, Frankfurter, HL) on daemon threads; they are
 * not awaited and die with the JVM. Confining them to temporary directories is what keeps them from
 * touching anything that matters. Same reasoning, and the same mechanism, as {@code WebEndpointsTest}.
 */
@SpringBootTest
class PortfolioBenchApplicationTests {

    @DynamicPropertySource
    static void temporaryDirs(DynamicPropertyRegistry registry) throws IOException {
        Path inputDir = Files.createTempDirectory("pb-ctx-input");
        Path dbDir = Files.createTempDirectory("pb-ctx-db");
        Path outputDir = Files.createTempDirectory("pb-ctx-output");
        registry.add("portfolio.input-dir", inputDir::toString);
        registry.add("portfolio.db-dir", dbDir::toString);
        registry.add("portfolio.output-dir", outputDir::toString);
    }

    @Test
    void contextLoads() {
    }
}
