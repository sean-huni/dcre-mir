package za.co.fnb.dcre.mir.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mir.service.InitialResponseTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;

import javax.sql.DataSource;

@Configuration
public class MirJobConfig {

    @Bean
    public Job mirJob(final JobRepository repo, final PlatformTransactionManager tx,
                      final InitialResponseTasklet tasklet, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        // The respond step WRITES the man_initial_response ledger + idempotent staging concurrent
        // with the fleet's heavy writers; CRDB 40001 commit-time aborts are normal under contention
        // and are retried in a fresh tx by the shared handler (retry, never skip).
        Step responseStep = new StepBuilder("responseStep", repo)
                .tasklet(tasklet, tx)
                .exceptionHandler(new CrdbRetryExceptionHandler("MIR"))
                .build();
        // The seam listener records the job's business outcome for AGT; MIR is a terminal response
        // leg that always emits its response (the ACK/NACK is the file's content, not the job verdict),
        // so the constant BUSINESS_ACCEPTED matches CIR. man_initial_response is captured on the
        // respond path (per-arrival), not here, so this listener carries no persistence hook. The
        // HeartbeatWriter stamps agt_ops liveness while this job runs (Batch 6 registers it explicitly).
        return new JobBuilder("mirJob", repo)
                .listener(new OutcomeSeamListener("mir", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(responseStep)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(final DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "MIR_BATCH_", 60);
    }
}
