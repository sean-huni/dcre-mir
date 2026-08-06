package za.co.fnb.dcre.mir;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Test seeding helpers. man_initial_response comes from MIR's own Liquibase changelog
 * (001-man-initial-response.xml). The MRR-owned spine (mandate_request_header /
 * mandate_request_entry) and the MRV-owned man_validation_log are NOT in MIR's changelog
 * (single-writer, R-04), so this helper stands them up with the subset of columns the MIR
 * read models map, exactly as CIR's tests stand up the CRR/CTV upstream tables. spine_state
 * defaults to VALIDATED here (the state a row reaches after MRV passes it); a MAS decline is
 * seeded by flipping it to SCORE_DECLINED.
 */
public final class ManTestTables {

    private ManTestTables() {
    }

    public static void createUpstream(final JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL UNIQUE,
                    client_token VARCHAR(16),
                    msg_id VARCHAR(35) NOT NULL,
                    entry_count INT NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL,
                    sequence INT NOT NULL,
                    spine_state VARCHAR(16) NOT NULL DEFAULT 'VALIDATED',
                    UNIQUE (arrival_id, sequence))""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS man_validation_log (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL,
                    sequence INT NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    detail VARCHAR(256),
                    UNIQUE (arrival_id, sequence))""");
    }

    public static void insertHeader(final JdbcTemplate jdbc, final UUID arrival, final String client,
                                    final String msgId, final int entryCount) {
        jdbc.update("""
                UPSERT INTO mandate_request_header (arrival_id, client_token, msg_id, entry_count)
                VALUES (?,?,?,?)""", arrival, client, msgId, entryCount);
    }

    /** One spine row plus its MRV verdict; spineState VALIDATED (passed) or SCORE_DECLINED (MAS decline). */
    public static void insertRow(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                 final String outcome, final String spineState) {
        jdbc.update("UPSERT INTO mandate_request_entry (arrival_id, sequence, spine_state) VALUES (?,?,?)",
                arrival, sequence, spineState);
        jdbc.update("UPSERT INTO man_validation_log (arrival_id, sequence, outcome) VALUES (?,?,?)",
                arrival, sequence, outcome);
    }

    /** A clean, fully-passed row (VALIDATED spine, PASS verdict). */
    public static void insertPassRow(final JdbcTemplate jdbc, final UUID arrival, final int sequence) {
        insertRow(jdbc, arrival, sequence, "PASS", "VALIDATED");
    }
}
