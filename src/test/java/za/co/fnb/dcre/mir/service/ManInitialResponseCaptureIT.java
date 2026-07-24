package za.co.fnb.dcre.mir.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.mir.ManTestTables;
import za.co.fnb.dcre.mir.data.model.ManInitialResponseEntity;
import za.co.fnb.dcre.mir.data.repo.ManInitialResponseRepo;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * man_initial_response is the write-ahead filename ledger and system of record for every
 * initial mandate response. The zero-duplicate resume audit (engineering.md chaos gate) is
 * pinned here: after a kill between the row commit and the staged write, a re-run resolves to
 * EXACTLY ONE stamped file and ONE row (idempotent on the full arrival identity).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "DCRE_EXCHANGE_ROOT=build/test-exchange"})
class ManInitialResponseCaptureIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    InitialResponseService service;

    @Autowired
    ManInitialResponseRepo repo;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void upstreamTablesExist() {
        ManTestTables.createUpstream(jdbc);
    }

    @Test
    void nackFilenameIsQueryableWithItsReason() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANCAP" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 4);
        ManTestTables.insertRow(jdbc, arrival, 1, "FAIL_ACCOUNT_NOT_FOUND", "REJECTED");
        ManTestTables.insertRow(jdbc, arrival, 2, "PASS", "REJECTED");
        ManTestTables.insertRow(jdbc, arrival, 3, "PASS", "SCORE_DECLINED");
        ManTestTables.insertRow(jdbc, arrival, 4, "PASS", "REJECTED");

        service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, "BUSINESS_FILE_REJECTED");

        Map<String, Object> row = ledgerRow(arrival);
        assertEquals("NACK", row.get("outcome"));
        assertEquals("FILE_REJECTED_BY_POLICY", row.get("reason"));
        assertEquals("FNBRF01_" + msgId + "_onhost-req-man_RESP.txt", row.get("file_name"));
        assertEquals(0, ((Number) row.get("accepted_count")).intValue());
        assertEquals(4, ((Number) row.get("total_count")).intValue());
        assertNotNull(row.get("written_at"), "written_at is stamped after the file write");
    }

    @Test
    void partialAckLedgerRecordsAcceptanceRatioAndNoReason() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANPRT" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 3);
        ManTestTables.insertRow(jdbc, arrival, 1, "PASS", "SCORE_DECLINED");
        ManTestTables.insertPassRow(jdbc, arrival, 2);
        ManTestTables.insertPassRow(jdbc, arrival, 3);

        service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);

        Map<String, Object> row = ledgerRow(arrival);
        assertEquals("ACK", row.get("outcome"), "a partial file is still an ACK (accepted-by-DCRE)");
        assertEquals(2, ((Number) row.get("accepted_count")).intValue());
        assertEquals(3, ((Number) row.get("total_count")).intValue());
        assertNull(row.get("reason"), "a clean/partial ACK carries no NACK reason");

        ManInitialResponseEntity entity = repo.findByArrivalId(arrival).orElseThrow();
        assertEquals("ACK", entity.getOutcome());
        assertEquals(2, entity.getAcceptedCount());
        assertEquals("onhost-req-man", entity.getRouteId());
        assertNotNull(entity.getWrittenAt());
    }

    @Test
    void killBetweenRowCommitAndStagedWriteResumesToOneStampedFileAndRow() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANKIL" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 5);
        for (int seq = 1; seq <= 5; seq++) {
            ManTestTables.insertPassRow(jdbc, arrival, seq);
        }
        String fileName = "FNBRF01_" + msgId + "_onhost-req-man_RESP.txt";

        // Durable state after the write-ahead insert committed but BEFORE the file write.
        jdbc.update("INSERT INTO man_initial_response (arrival_id, client, msg_id, route_id, outcome,"
                + " file_name, accepted_count, total_count) VALUES (?,?,?,?,?,?,?,?)",
                arrival, "FNBRF01", msgId, "onhost-req-man", "ACK", fileName, 5, 5);
        assertNull(ledgerRow(arrival).get("written_at"), "staged-not-written: written_at IS NULL");

        var resumed = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);

        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM man_initial_response WHERE arrival_id=?",
                Long.class, arrival), "resume ON CONFLICT no-ops: exactly one row per arrival");
        assertNotNull(ledgerRow(arrival).get("written_at"), "resume stamps written_at");
        assertTrue(Files.exists(resumed.responseFile()), "resume writes exactly one file");
        assertEquals("ACK|FNBRF01|" + msgId + "|5/5|ACCEPTED_BY_DCRE",
                Files.readAllLines(resumed.responseFile()).get(0));
    }

    @Test
    void killBetweenFileWriteAndStampResumesToStampWithoutRewriting() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANSTM" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 3);
        for (int seq = 1; seq <= 3; seq++) {
            ManTestTables.insertPassRow(jdbc, arrival, seq);
        }
        service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);
        jdbc.update("UPDATE man_initial_response SET written_at = NULL WHERE arrival_id=?", arrival);

        var resumed = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);

        assertFalse(resumed.written(), "the existing file is a restart no-op, never a rewrite");
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM man_initial_response WHERE arrival_id=?",
                Long.class, arrival), "resume never duplicates the row");
        assertNotNull(ledgerRow(arrival).get("written_at"), "resume stamps the previously-unstamped row");
    }

    @Test
    void overlongHeaderlessReasonStillNacksWithAClippedLedgerReason() throws Exception {
        UUID arrival = UUID.randomUUID();   // headerless path
        String msgId = "DCREMANLNG" + arrival.toString().substring(0, 4);
        String longReason = "spine instruction count 1000 does not match declared control total 1001 in trailer";
        assertTrue(longReason.length() > 64, "fixture must exceed the reason column width");

        var result = service.respond(arrival, "onhost-req-man", longReason, "FNBRF01", msgId, null);

        assertTrue(Files.readAllLines(result.responseFile()).get(0).endsWith("|" + longReason),
                "the response file line keeps the full reason");
        assertEquals(longReason.substring(0, 64), ledgerRow(arrival).get("reason"),
                "the ledger reason is clipped to VARCHAR(64)");
    }

    private Map<String, Object> ledgerRow(final UUID arrival) {
        return jdbc.queryForMap("SELECT outcome, reason, file_name, accepted_count, total_count, written_at"
                + " FROM man_initial_response WHERE arrival_id=?", arrival);
    }
}
