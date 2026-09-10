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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MIR composes the initial mandate ACK/NACK from the dcre_man spine + MRV verdicts + the
 * MAS SCORE_DECLINED spine state, and stages it to onhost-resp-man/out. Reject reasons come
 * from BOTH man_validation_log (MRV FAILs) and mandate_request_entry.spine_state (MAS
 * decline -> FAIL_SCORE_BELOW_THRESHOLD). Headerless arrivals (MRR fataled pre-persist) still
 * NACK from AGT job params; missing route / unconfigured client fail closed.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "DCRE_EXCHANGE_ROOT=build/test-exchange"})
class InitialResponseServiceIT {

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
    JdbcTemplate jdbc;

    @BeforeEach
    void upstreamTablesExist() {
        ManTestTables.createUpstream(jdbc);
    }

    @Test
    void fullyAcceptedArrivalIsAckedWithNoRejections() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMAN" + arrival.toString().substring(0, 6);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 5);
        for (int seq = 1; seq <= 5; seq++) {
            ManTestTables.insertPassRow(jdbc, arrival, seq);
        }

        var result = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);

        assertTrue(result.written());
        List<String> lines = Files.readAllLines(result.responseFile());
        assertEquals(1, lines.size(), "an all-pass arrival yields the ACK line only");
        assertEquals("ACK|FNBRF01|" + msgId + "|5/5|ACCEPTED_BY_DCRE", lines.get(0));
        assertEquals("FNBRF01_" + msgId + "_onhost-req-man_RESP.txt",
                result.responseFile().getFileName().toString());
        assertEquals(Path.of("build/test-exchange/fnbrf01/onhost-resp-man/out"),
                result.responseFile().getParent(),
                "response lands in the per-client onhost-resp-man/out dir");
    }

    @Test
    void partialArrivalItemizesValidationAndScoreDeclineRejections() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANPART" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 5);
        ManTestTables.insertRow(jdbc, arrival, 1, "FAIL_ACCOUNT_NOT_FOUND", "REJECTED"); // MRV fail
        ManTestTables.insertPassRow(jdbc, arrival, 2);
        ManTestTables.insertRow(jdbc, arrival, 3, "PASS", "SCORE_DECLINED"); // passed MRV, MAS declined
        ManTestTables.insertPassRow(jdbc, arrival, 4);
        ManTestTables.insertPassRow(jdbc, arrival, 5);

        var result = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);

        List<String> lines = Files.readAllLines(result.responseFile());
        assertEquals("ACK|FNBRF01|" + msgId + "|3/5|ACCEPTED_BY_DCRE", lines.get(0),
                "3 accepted = 5 total - (1 validation reject + 1 score decline)");
        assertEquals(3, lines.size(), "ACK line plus one REJ line per rejection source");
        assertEquals("REJ|1|FAIL_ACCOUNT_NOT_FOUND", lines.get(1));
        assertEquals("REJ|3|FAIL_SCORE_BELOW_THRESHOLD", lines.get(2),
                "a SCORE_DECLINED spine row is reported under its MandateOutcome name (R-08)");
    }

    @Test
    void businessFileRejectedArrivalNacksItemizingBothRejectSources() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANPOL" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 4);
        ManTestTables.insertRow(jdbc, arrival, 1, "PASS", "REJECTED"); // ALL_OR_NOTHING drags a passing row to REJECTED
        ManTestTables.insertRow(jdbc, arrival, 2, "FAIL_STRUCTURE", "REJECTED");
        ManTestTables.insertRow(jdbc, arrival, 3, "PASS", "REJECTED");
        ManTestTables.insertRow(jdbc, arrival, 4, "PASS", "SCORE_DECLINED");

        var result = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, "BUSINESS_FILE_REJECTED");

        List<String> lines = Files.readAllLines(result.responseFile());
        assertEquals("NACK|FNBRF01|" + msgId + "|0/4|FILE_REJECTED_BY_POLICY", lines.get(0));
        assertEquals(3, lines.size(), "NACK line plus one REJ line per non-PASS verdict and per score decline");
        assertEquals("REJ|2|FAIL_STRUCTURE", lines.get(1));
        assertEquals("REJ|4|FAIL_SCORE_BELOW_THRESHOLD", lines.get(2));
    }

    @Test
    void headerlessArrivalStillProducesNackFromJobParams() throws Exception {
        UUID arrival = UUID.randomUUID();   // NO mandate_request_header row: the MRR-fataled shape
        // The msgId must be UNIQUE PER RUN, like every sibling test in this class. R-05 makes
        // publication a no-op when the response file already exists, and the file is named from
        // the msgId, so a FIXED msgId makes written() false on the second run against a build
        // directory that was not cleaned: correct production behaviour, failing test.
        String msgId = "DCREMANHDR" + arrival.toString().substring(0, 5);
        var result = service.respond(arrival, "onhost-req-man", "spine count 10 != declared 11",
                "FNBRF01", msgId, null);
        assertTrue(result.written());
        List<String> lines = Files.readAllLines(result.responseFile());
        assertEquals(1, lines.size());
        assertEquals("NACK|FNBRF01|" + msgId + "|0/0|spine count 10 != declared 11", lines.get(0));
    }

    @Test
    void headerlessArrivalWithoutFatalReasonNacksNoHeader() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANHDRX" + arrival.toString().substring(0, 5);   // unique per run, see above
        var result = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);
        assertTrue(result.written());
        assertEquals("NACK|FNBRF01|" + msgId + "|0/0|NO_HEADER",
                Files.readAllLines(result.responseFile()).get(0));
    }

    @Test
    void arrivalWithHeaderButNoVerdictsNacksNoVerdicts() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANNV" + arrival.toString().substring(0, 5);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 3); // header present, MRV never ran
        var result = service.respond(arrival, "onhost-req-man", null, "FNBRF01", msgId, null);
        assertEquals("NACK|FNBRF01|" + msgId + "|0/3|NO_VERDICTS",
                Files.readAllLines(result.responseFile()).get(0));
    }

    @Test
    void headerlessArrivalWithoutClientTokenFailsClosed() {
        UUID arrival = UUID.randomUUID();   // no header AND no client.token -> UNKNOWN, no configured dir
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.respond(arrival, "onhost-req-man", "spine truncated", null, null, null));
        assertTrue(ex.getMessage().contains("UNKNOWN"),
                "unconfigured client must fail closed: " + ex.getMessage());
    }

    @Test
    void missingOrInvalidRouteFailsClosed() {
        UUID arrival = UUID.randomUUID();
        for (String badRoute : new String[] {null, "  ", "../onhost-req-man", "onhost-req-man/../../etc",
                "ONHOST-REQ-MAN", "onhost_req_man"}) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> service.respond(arrival, badRoute, null, "FNBRF01", "DCREMANROUTE", null),
                    "route must be rejected: " + badRoute);
            assertEquals("arrival route missing or invalid: required for response identity (A-45)",
                    ex.getMessage());
        }
    }

    @Test
    void overlongClientIdentityFailsClosedWritingNeitherRowNorFile() {
        // man_initial_response.client is VARCHAR(16). The headerless path takes client.token verbatim,
        // so an over-length client identity must fail closed with a readable message BEFORE the
        // write-ahead INSERT, not surface as a raw CRDB "value too long for type varchar(16)".
        UUID arrival = UUID.randomUUID();
        String overlongClient = "LONGINITIATINGPARTY01"; // 21 chars, configured in the test layout
        assertTrue(overlongClient.length() > 16);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.respond(arrival, "onhost-req-man", "spine truncated", overlongClient, "DCREMANWIDE", null));
        assertTrue(ex.getMessage().contains("man_initial_response.client identity exceeds 16 chars"),
                "the fail-closed message names the offending column and width: " + ex.getMessage());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM man_initial_response WHERE arrival_id=?",
                Long.class, arrival), "an over-length client identity writes no ledger row");
    }

    @Test
    void sameClientAndMsgIdOnDifferentRoutesGetDistinctResponses() throws Exception {
        String msgId = "DCREMANTWIN" + UUID.randomUUID().toString().substring(0, 4);
        UUID reqMan = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, reqMan, "FNBRF01", msgId, 2);
        ManTestTables.insertPassRow(jdbc, reqMan, 1);
        ManTestTables.insertPassRow(jdbc, reqMan, 2);

        var first = service.respond(reqMan, "onhost-req-man", null, "FNBRF01", msgId, null);
        var second = service.respond(other, "onhost-resp-man", "no header", "FNBRF01", msgId, null);

        assertNotEquals(first.responseFile(), second.responseFile(),
                "distinct arrivals (different routes) must never share a response file");
        assertEquals("FNBRF01_" + msgId + "_onhost-req-man_RESP.txt",
                first.responseFile().getFileName().toString());
        assertEquals("FNBRF01_" + msgId + "_onhost-resp-man_RESP.txt",
                second.responseFile().getFileName().toString());
    }
}
