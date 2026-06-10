package com.portfolio.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Daily portfolio totals. One row per snapshot date; a re-run on the same day
 * overwrites the existing row (so the latest figures win).
 */
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS portfolio_snapshots (
                snapshot_date      INTEGER PRIMARY KEY,
                snapshot_date_text TEXT    NOT NULL,
                total_value_gbp    REAL    NOT NULL,
                total_gain_gbp     REAL,
                total_cash_gbp     REAL,
                return_pct         REAL,
                total_return       REAL,
                gbpusd             REAL,
                gbpeur             REAL
            )""";

    private final JdbcConnectionFactory connections;

    public SnapshotRepository(JdbcConnectionFactory connections) {
        this.connections = connections;
        try (Connection conn = connections.open(); Statement ddl = conn.createStatement()) {
            ddl.execute(CREATE_TABLE);
            // Tolerate pre-existing tables created before the later columns were added.
            for (String col : List.of(
                    "gbpusd REAL", "gbpeur REAL", "total_gain_gbp REAL",
                    "total_cash_gbp REAL", "return_pct REAL", "total_return REAL")) {
                try {
                    ddl.execute("ALTER TABLE portfolio_snapshots ADD COLUMN " + col);
                } catch (SQLException alreadyPresent) {
                    // column already there — fine
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise snapshot table", e);
        }
    }

    /**
     * All saved snapshots, oldest first. Used by the Snapshot-delta UI to populate the
     * date picker and let the user diff any two points in time without re-computing.
     */
    public List<Snapshot> listAll() {
        List<Snapshot> out = new java.util.ArrayList<>();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT snapshot_date_text, total_value_gbp, total_gain_gbp, " +
                             "total_cash_gbp, return_pct, total_return, gbpusd, gbpeur " +
                             "FROM portfolio_snapshots ORDER BY snapshot_date_text")) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Snapshot(rs.getString(1),
                            BigDecimal.valueOf(rs.getDouble(2)),
                            wrap(rs.getDouble(3), rs.wasNull()),
                            wrap(rs.getDouble(4), rs.wasNull()),
                            wrap(rs.getDouble(5), rs.wasNull()),
                            wrap(rs.getDouble(6), rs.wasNull()),
                            wrap(rs.getDouble(7), rs.wasNull()),
                            wrap(rs.getDouble(8), rs.wasNull())));
                }
            }
        } catch (Exception e) {
            log.warn("Could not list snapshots", e);
        }
        return out;
    }

    private static BigDecimal wrap(double v, boolean wasNull) {
        return wasNull ? null : BigDecimal.valueOf(v);
    }

    /**
     * One saved snapshot row. All totals are GBP except {@code gbpusd} / {@code gbpeur},
     * which are FX rates (foreign per 1 GBP) at snapshot time.
     */
    public record Snapshot(String date,
                           BigDecimal totalValueGbp,
                           BigDecimal totalGainGbp,
                           BigDecimal totalCashGbp,
                           BigDecimal returnPct,
                           BigDecimal totalReturn,
                           BigDecimal gbpusd,
                           BigDecimal gbpeur) {
    }

    public void saveSnapshot(BigDecimal totalGbp, BigDecimal totalGainGbp,
                             BigDecimal totalCashGbp, BigDecimal returnPct,
                             BigDecimal totalReturn, Map<String, BigDecimal> gbpRates) {
        long snapshotDate = Instant.now().getEpochSecond();
        String snapshotDateText = LocalDate.now().toString();

        double gbpusd = gbpRates.getOrDefault("USD", BigDecimal.ZERO).doubleValue();
        double gbpeur = gbpRates.getOrDefault("EUR", BigDecimal.ZERO).doubleValue();

        try (Connection conn = connections.open()) {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM portfolio_snapshots WHERE snapshot_date_text = ?")) {
                del.setString(1, snapshotDateText);
                del.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO portfolio_snapshots " +
                            "(snapshot_date, snapshot_date_text, total_value_gbp, " +
                            " total_gain_gbp, total_cash_gbp, return_pct, total_return, gbpusd, gbpeur) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, snapshotDate);
                ps.setString(2, snapshotDateText);
                ps.setDouble(3, totalGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                ps.setDouble(4, totalGainGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                ps.setDouble(5, totalCashGbp.setScale(2, RoundingMode.HALF_UP).doubleValue());
                ps.setDouble(6, returnPct.setScale(6, RoundingMode.HALF_UP).doubleValue());
                ps.setDouble(7, totalReturn.setScale(6, RoundingMode.HALF_UP).doubleValue());
                ps.setDouble(8, gbpusd);
                ps.setDouble(9, gbpeur);
                ps.executeUpdate();
            }
            log.info(String.format("Snapshot saved to DB: %s → £%,.2f  gain £%,.2f  return %.2f%%",
                    snapshotDateText, totalGbp.doubleValue(),
                    totalGainGbp.doubleValue(),
                    returnPct.multiply(BigDecimal.valueOf(100)).doubleValue()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not save portfolio snapshot", e);
        }
    }
}
