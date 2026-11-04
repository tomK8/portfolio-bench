package com.portfolio.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The layer rules in CLAUDE.md, checked rather than asserted in prose.
 *
 * <p>These existed as a table in {@code docs/architecture.md} listing thirteen known violations, by
 * hand. That is the maintained list this codebase forbids everywhere else — it could only ever go
 * stale, and a stale violation table reads as coverage that is not there. The table is now this
 * file plus {@code archunit_store/}, which no one has to remember to update.
 *
 * <p>Two kinds of exception are deliberately not the same thing:
 * <ul>
 *   <li><b>Intended exceptions</b> are named in the rules below with their reason. They are design,
 *       not debt, and will not disappear.
 *   <li><b>Debt</b> is frozen in {@code archunit_store/}. It can shrink on its own and cannot grow.
 *       Nothing in there is endorsed; it is a burn-down list.
 * </ul>
 */
class LayerRulesTest {

    /**
     * Driven from plain {@code @Test} methods rather than ArchUnit's {@code @ArchTest} engine.
     * That engine is on no one's side here: with this surefire setup it loads, logs its config,
     * creates its freeze store — and reports <em>zero</em> tests, so a rule that must fail left
     * the build green. It was caught by deliberately breaking a rule and watching it pass. A guard
     * is not a guard until it has been seen to fail.
     */
    private static final JavaClasses PRODUCTION_CODE = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.portfolio");

    /**
     * One direction only. Frozen, because ten controller-to-repository and controller-to-fetcher
     * hops predate the rule; a controller reaching a repository skips the use-case layer that owns
     * the read filters, so the hops are debt rather than style.
     */
    static final ArchRule layerDependenciesRunOneWay = FreezingArchRule.freeze(
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .layer("domain").definedBy("com.portfolio.domain..")
                    .layer("port").definedBy("com.portfolio.port..")
                    .layer("parser").definedBy("com.portfolio.parser..")
                    .layer("persistence").definedBy("com.portfolio.persistence..")
                    .layer("adapter").definedBy("com.portfolio.adapter..")
                    .layer("application").definedBy("com.portfolio.application..")
                    .layer("web").definedBy("com.portfolio.web..")
                    .layer("config").definedBy("com.portfolio.config..")

                    .whereLayer("config").mayNotBeAccessedByAnyLayer()
                    .whereLayer("web").mayOnlyBeAccessedByLayers("config")
                    .whereLayer("application").mayOnlyBeAccessedByLayers("web", "config")
                    .whereLayer("adapter").mayOnlyBeAccessedByLayers("application", "config")
                    .whereLayer("persistence").mayOnlyBeAccessedByLayers("application", "config")
                    .whereLayer("parser").mayOnlyBeAccessedByLayers("adapter", "application", "config")
                    .whereLayer("port").mayOnlyBeAccessedByLayers("parser", "adapter", "application", "config")
                    .as("layer dependencies run one way (CLAUDE.md § Architecture)"));

    /**
     * The one that matters most, and the one that holds without exception today: {@code domain} is
     * pure logic, so it reaches for nothing — not a repository, not a fetcher, not a framework.
     * Never frozen. A violation here is not debt to schedule, it is the wrong place for the code.
     */
    static final ArchRule domainDependsOnNothingButDomain = noClasses()
            .that().resideInAPackage("com.portfolio.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.portfolio.application..", "com.portfolio.adapter..",
                    "com.portfolio.persistence..", "com.portfolio.parser..",
                    "com.portfolio.web..", "com.portfolio.config..", "com.portfolio.port..")
            .as("domain depends on nothing but domain");

    /**
     * Spring stays in {@code web} and {@code config} so the other six packages can be unit-tested
     * by calling a constructor. The two exceptions are both intended: the application class carries
     * {@code @SpringBootApplication} by definition, and {@code SmtpAlertNotifier} is the adapter
     * whose whole job is to hold a {@code JavaMailSender}.
     */
    static final ArchRule springIsConfinedToWebAndConfig = noClasses()
            .that().resideOutsideOfPackages("com.portfolio.web..", "com.portfolio.config..")
            // Name patterns, not exact names: the exception has to cover nested and anonymous
            // classes too. SmtpAlertNotifier$1 — its jakarta.mail Authenticator — slipped straight
            // past an exact-name exception and failed the rule.
            .and().haveNameNotMatching("com\\.portfolio\\.PortfolioBenchApplication.*")
            .and().haveNameNotMatching("com\\.portfolio\\.adapter\\.SmtpAlertNotifier.*")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..")
            .as("Spring is confined to web and config");

    /**
     * All JDBC goes through {@code JdbcConnectionFactory}, and {@code com.portfolio.persistence} is
     * its only owner. Two intended exceptions, both of which speak SQL on purpose rather than to
     * reach data: {@code HealthService} pings the database for liveness, and {@code BackupService}
     * writes a plain SQL dump — deliberately independent of our own code, so a restore needs none
     * of it.
     */
    static final ArchRule jdbcIsConfinedToPersistence = noClasses()
            .that().resideOutsideOfPackage("com.portfolio.persistence..")
            .and().haveNameNotMatching("com\\.portfolio\\.application\\.HealthService.*")
            .and().haveNameNotMatching("com\\.portfolio\\.application\\.BackupService.*")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .as("JDBC is confined to persistence");

    /** Logging is SLF4J + Logback. A {@code System.out} in a daemon writes to a rotated log nobody greps. */
    static final ArchRule productionCodeDoesNotPrintToTheConsole = noClasses()
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .as("production code does not print to the console");

    /** A cycle between two layers means neither can be read, tested or moved on its own. */
    static final ArchRule layersAreFreeOfCycles = slices()
            .matching("com.portfolio.(*)..").should().beFreeOfCycles()
            .as("layers are free of cycles");

    @Test
    void layerDependenciesRunOneWay() {
        layerDependenciesRunOneWay.check(PRODUCTION_CODE);
    }

    @Test
    void domainDependsOnNothingButDomain() {
        domainDependsOnNothingButDomain.check(PRODUCTION_CODE);
    }

    @Test
    void springIsConfinedToWebAndConfig() {
        springIsConfinedToWebAndConfig.check(PRODUCTION_CODE);
    }

    @Test
    void jdbcIsConfinedToPersistence() {
        jdbcIsConfinedToPersistence.check(PRODUCTION_CODE);
    }

    @Test
    void productionCodeDoesNotPrintToTheConsole() {
        productionCodeDoesNotPrintToTheConsole.check(PRODUCTION_CODE);
    }

    @Test
    void layersAreFreeOfCycles() {
        layersAreFreeOfCycles.check(PRODUCTION_CODE);
    }
}
