package za.co.fnb.dcre.mir;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MIR job over the real job + CockroachDB (Testcontainers, fleet pattern). */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "DCRE_EXCHANGE_ROOT=build/test-exchange"})
class MirJobTest {

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
    Job mirJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void upstreamTablesExist() {
        ManTestTables.createUpstream(jdbc);
    }

    @Test
    void ackWithRejectDetailsThenRestartNoOp() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANJOB" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 5);
        ManTestTables.insertRow(jdbc, arrival, 1, "FAIL_ACCOUNT_NOT_FOUND", "REJECTED");
        ManTestTables.insertRow(jdbc, arrival, 2, "PASS", "SCORE_DECLINED");
        ManTestTables.insertPassRow(jdbc, arrival, 3);
        ManTestTables.insertPassRow(jdbc, arrival, 4);
        ManTestTables.insertPassRow(jdbc, arrival, 5);

        JobExecution run = jobOperator.start(mirJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("route.id", "onhost-req-man", false).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        Path file = Path.of(run.getExecutionContext().getString("responseFile"));
        List<String> lines = Files.readAllLines(file);
        assertEquals("ACK|FNBRF01|" + msgId + "|3/5|ACCEPTED_BY_DCRE", lines.get(0));
        assertEquals(3, lines.size());
        assertEquals("REJ|1|FAIL_ACCOUNT_NOT_FOUND", lines.get(1));
        assertEquals("REJ|2|FAIL_SCORE_BELOW_THRESHOLD", lines.get(2));

        // restart no-op: second run (new identity to dodge JobInstance reuse) must not rewrite
        JobExecution rerun = jobOperator.start(mirJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("route.id", "onhost-req-man", false)
                .addString("attempt", "2", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, rerun.getStatus());
        assertEquals(lines, Files.readAllLines(file), "existing response is never overwritten (R-05)");
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM man_initial_response WHERE arrival_id=?",
                Long.class, arrival), "zero-duplicate audit: one ledger row per arrival after re-run");
    }

    @Test
    void fileFatalArrivalGetsNack() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANFAT" + arrival.toString().substring(0, 4);
        // header present but no verdicts -> MRV never validated (file-fatal upstream)
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 3);

        JobExecution run = jobOperator.start(mirJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("route.id", "onhost-req-man", false)
                .addString("fatal.reason", "V1 layout fails closed in production", false)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        List<String> lines = Files.readAllLines(Path.of(run.getExecutionContext().getString("responseFile")));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("NACK|FNBRF01|" + msgId + "|0/3|V1 layout"));
    }

    @Test
    void launchWithoutRouteIdFailsClosedAndWritesNothing() throws Exception {
        UUID arrival = UUID.randomUUID();
        String msgId = "DCREMANNR" + arrival.toString().substring(0, 4);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, 2);
        ManTestTables.insertPassRow(jdbc, arrival, 1);
        ManTestTables.insertPassRow(jdbc, arrival, 2);

        JobExecution run = jobOperator.start(mirJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true).toJobParameters());

        assertEquals(BatchStatus.FAILED, run.getStatus());
        assertEquals(ExitStatus.FAILED.getExitCode(), run.getExitStatus().getExitCode());
        assertFalse(run.getExecutionContext().containsKey("responseFile"),
                "a failed launch must not advertise a response file");
        Path respDir = Files.createDirectories(Path.of("build/test-exchange/fnbrf01/onhost-resp-man/out"));
        try (var responses = Files.list(respDir)) {
            assertTrue(responses.noneMatch(p -> p.getFileName().toString().contains(msgId)),
                    "no response file may be staged without the route identity");
        }
    }

    @Test
    void launchWithUnconfiguredClientFailsClosedAndWritesNothing() throws Exception {
        UUID arrival = UUID.randomUUID();   // NO header and NO client.token -> UNKNOWN, no configured dir
        JobExecution run = jobOperator.start(mirJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .addString("route.id", "onhost-req-man", false)
                .addString("fatal.reason", "no header persisted", false)
                .toJobParameters());

        assertEquals(BatchStatus.FAILED, run.getStatus());
        assertFalse(run.getExecutionContext().containsKey("responseFile"),
                "a fail-closed launch must not advertise a response file");
        Path root = Path.of("build/test-exchange");
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                assertTrue(walk.noneMatch(p -> p.getFileName().toString().contains(arrival.toString())),
                        "no response file may be staged for an unconfigured (UNKNOWN) client");
            }
        }
    }
}
