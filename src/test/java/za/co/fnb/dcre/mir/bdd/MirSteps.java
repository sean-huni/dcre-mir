package za.co.fnb.dcre.mir.bdd;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.mir.ManTestTables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step definitions for the mandate initial response writer. Seeding mirrors MirJobTest:
 * mandate_request_header + mandate_request_entry (spine_state) + man_validation_log rows
 * stand in for the MRR/MRV/MAS upstream writers (R-04 single-writer seams).
 */
public class MirSteps {

    @Autowired
    Job mirJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    UUID arrival;
    String msgId;
    JobExecution execution;
    List<String> firstRunContent;

    @Given("a mandate arrival of {int} instructions that all passed validation and score")
    public void allPassArrival(int total) {
        seedHeader(total);
        for (int seq = 1; seq <= total; seq++) {
            ManTestTables.insertPassRow(jdbc, arrival, seq);
        }
    }

    @Given("a mandate arrival of {int} instructions where these rows were rejected:")
    public void rejectedRowsArrival(int total, DataTable table) {
        seedHeader(total);
        Map<Integer, Map<String, String>> rejects = new java.util.HashMap<>();
        for (Map<String, String> row : table.asMaps()) {
            rejects.put(Integer.parseInt(row.get("sequence")), row);
        }
        for (int seq = 1; seq <= total; seq++) {
            Map<String, String> reject = rejects.get(seq);
            if (reject == null) {
                ManTestTables.insertPassRow(jdbc, arrival, seq);
            } else if ("score".equals(reject.get("source"))) {
                ManTestTables.insertRow(jdbc, arrival, seq, "PASS", "SCORE_DECLINED");
            } else {
                ManTestTables.insertRow(jdbc, arrival, seq, reject.get("reason"), "REJECTED");
            }
        }
    }

    @Given("a file-fatal mandate arrival declaring {int} instructions with no verdicts recorded")
    public void fatalArrival(int total) {
        seedHeader(total); // header only: no spine rows, no verdicts (MRR fataled the file)
    }

    @When("the initial mandate response job runs")
    public void jobRuns() throws Exception {
        launch(builderWithIdentity().addString("route.id", "onhost-req-man", false));
    }

    @When("the initial mandate response job runs for a business-file-rejected arrival")
    public void jobRunsFileRejected() throws Exception {
        launch(builderWithIdentity().addString("route.id", "onhost-req-man", false)
                .addString("outcome.hint", "BUSINESS_FILE_REJECTED", false));
    }

    @When("the initial mandate response job runs citing the fatal reason {string}")
    public void jobRunsWithFatalReason(String reason) throws Exception {
        launch(builderWithIdentity().addString("route.id", "onhost-req-man", false)
                .addString("fatal.reason", reason, false));
    }

    @When("the initial mandate response job runs again for the same arrival")
    public void jobRunsAgain() throws Exception {
        firstRunContent = responseLines();
        launch(builderWithIdentity().addString("route.id", "onhost-req-man", false)
                .addString("attempt", "2", true));
    }

    @Then("the mandate response file acknowledges {int} of {int} instructions")
    public void responseAcknowledges(int accepted, int total) throws Exception {
        assertEquals("ACK|FNBRF01|" + msgId + "|" + accepted + "/" + total + "|ACCEPTED_BY_DCRE",
                responseLines().get(0));
    }

    @Then("the mandate response file carries no rejection details")
    public void noRejectionDetails() throws Exception {
        assertEquals(1, responseLines().size(), "an all-pass arrival yields the ACK line only");
    }

    @Then("the mandate response file lists the rejections:")
    public void listsRejections(DataTable table) throws Exception {
        List<Map<String, String>> expected = table.asMaps();
        List<String> lines = responseLines();
        assertEquals(1 + expected.size(), lines.size(), "header line plus one REJ line per rejection");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("REJ|" + expected.get(i).get("sequence") + "|" + expected.get(i).get("reason"),
                    lines.get(i + 1));
        }
    }

    @Then("the mandate response file is a NACK for {int} instructions citing {string}")
    public void responseIsNack(int total, String reason) throws Exception {
        assertEquals("NACK|FNBRF01|" + msgId + "|0/" + total + "|" + reason, responseLines().get(0));
    }

    @Then("the response filename is recorded in man_initial_response as an ACK of {int} of {int}")
    public void ledgerRecordsResponse(int accepted, int total) {
        Map<String, Object> row = jdbc.queryForMap("SELECT outcome, file_name, accepted_count,"
                + " total_count, written_at FROM man_initial_response WHERE arrival_id=?", arrival);
        assertEquals("ACK", row.get("outcome"));
        assertEquals(responseFile().getFileName().toString(), row.get("file_name"));
        assertEquals(accepted, ((Number) row.get("accepted_count")).intValue());
        assertEquals(total, ((Number) row.get("total_count")).intValue());
        assertNotNull(row.get("written_at"), "written_at is stamped after the file write");
    }

    @Then("the mandate response file on disk is unchanged")
    public void responseUnchanged() throws Exception {
        assertEquals(firstRunContent, responseLines(),
                "an existing response is never overwritten (R-05 StagedWrite no-op)");
        assertTrue(Files.notExists(responseFile().resolveSibling(responseFile().getFileName() + ".tmp")),
                "no staging leftovers");
    }

    private void seedHeader(int total) {
        arrival = UUID.randomUUID();
        msgId = "DCREMANBDD" + arrival.toString().substring(0, 4);
        ManTestTables.createUpstream(jdbc);
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", msgId, total);
    }

    private JobParametersBuilder builderWithIdentity() {
        return new JobParametersBuilder().addString("arrival.id", arrival.toString(), true);
    }

    private void launch(JobParametersBuilder builder) throws Exception {
        execution = jobOperator.start(mirJob, builder.toJobParameters());
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
    }

    private Path responseFile() {
        return Path.of(execution.getExecutionContext().getString("responseFile"));
    }

    private List<String> responseLines() throws Exception {
        return Files.readAllLines(responseFile());
    }
}
