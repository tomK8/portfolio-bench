package com.portfolio.web;

import com.portfolio.adapter.HoldingFileLocator;
import com.portfolio.application.HealthService;
import com.portfolio.persistence.JdbcConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The page behind the demo banner: what this instance is running on, and the evidence that it
 * cannot be the real portfolio.
 *
 * <p>The directories are read back from the beans that actually resolved them —
 * {@link HoldingFileLocator#inputDir()} and {@link JdbcConnectionFactory#dbDir()} — rather than
 * re-derived from the properties. Re-deriving would only prove what was configured; reading the
 * beans proves where the running application is genuinely looking, which is the point of showing
 * it at all.
 *
 * <p>Deliberately renders rather than throws when demo mode is off. A bare
 * {@code fragments/error} is fine as an htmx panel swap but makes a poor full page, and this
 * URL is reachable from any page's banner.
 */
@Controller
public class DemoController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final boolean demo;
    private final HealthService health;
    private final Path inputDir;
    private final Path dbDir;
    private final Path dbPath;
    private final String outputDir;

    public DemoController(@Value("${portfolio.demo:false}") boolean demo,
                          @Value("${portfolio.output-dir:}") String outputDir,
                          HealthService health,
                          HoldingFileLocator fileLocator,
                          JdbcConnectionFactory connections) {
        this.demo = demo;
        this.outputDir = outputDir;
        this.health = health;
        this.inputDir = fileLocator.inputDir();
        this.dbDir = connections.dbDir();
        this.dbPath = connections.dbPath();
    }

    @GetMapping("/demo")
    public String demo(Model model) {
        model.addAttribute("demo", demo);
        if (!demo) {
            return "demo";
        }
        model.addAttribute("generatedAt", builtAt());
        model.addAttribute("inputDir", inputDir.toString());
        model.addAttribute("dbDir", dbDir.toString());
        model.addAttribute("outputDir", outputDir.isBlank()
                ? Path.of(System.getProperty("user.home"), "Documents").toString()
                : outputDir);
        model.addAttribute("tables", health.status().tables());
        return "demo";
    }

    /**
     * When the demo database was built, taken from the file itself rather than a value written
     * beside it — a stamp that cannot disagree with the data it describes.
     */
    private String builtAt() {
        try {
            return STAMP.format(Files.getLastModifiedTime(dbPath).toInstant());
        } catch (IOException e) {
            return "unknown";
        }
    }
}
