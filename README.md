# dcre-mir

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register#repositories) table.

Mandates Initial Response: the terminal response leg of the M10 mandates request flow (SCRUM-78) that composes the initial ACK/NACK for one OnHost mandate arrival and stages it back to the client exchange (`onhost-resp-man/out`) in `dcre_man`.

## What it does

| | |
|---|---|
| Stage code | `MIR` (AGT `Stage.MIR`) |
| Family | Mandates (pain.009, `dcre_man`) |
| Leg | REQ (terminal, the response to OnHost) |
| Trigger | Arrival-launched: after MIT on the accept path, or as the route's whole-file responder when an earlier stage rejects or fails the file |
| Upstream | `MIT` (DAG edge); any rejecting stage via AGT's responder slot |
| Downstream | None: MIR and `MRW` are the terminal fork of the DAG |
| Diagram sheet | `dcre-mandates-req` in the design register |

DAG position read from AGT `origin/dev` `RouteDags.java` and `Stage.java` (checked 2026-09-28): `MRR -> MRV -> MAS -> MIT -> fork {MIR, MRW}`, with `Optional.of(Stage.MIR)` as the `MAN` route's whole-file NACK responder.

MIR is a terminal leg of the mandates request DAG (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). Once MIT has initialized the accepted rows, AGT launches MIR as a short-lived Kubernetes Job. MIR reads the JobParameters `arrival.id`, `route.id`, `client.token`, `msg.id`, `outcome.hint` and `fatal.reason`; AGT's `serviceArgs` always pass the first four to a responder, pass `outcome.hint` only when a non-reader stage returned `BUSINESS_FILE_REJECTED` or `BUSINESS_FILE_FATAL`, and never pass `fatal.reason` (checked 2026-09-28). MIR reads the arrival's whole-file identity and per-row verdicts and writes a single response file the OnHost client can consume. It is a direct clone of `dcre-cir` (Collections Initial Response); the mandate variant differs only in its data sources and its output channel.

MIR does NOT transition the spine forward: it is the response leg, and the reader chain proceeds via MRW independently. Its ONLY write is the `man_initial_response` ledger row, guarded idempotent on the full arrival identity.

### ACK vs NACK

The header line is `ACK|<client>|<msgId>|<accepted>/<total>|ACCEPTED_BY_DCRE` (accepted-by-DCRE, partial ACKs allowed) or `NACK|<client>|<msgId>|0/<total>|<reason>`. Each rejection follows as a `REJ|<sequence>|<reason>` detail line. The decision:

- `outcome.hint = BUSINESS_FILE_REJECTED` (R-41 ALL_OR_NOTHING whole-file rejection routed to MIR only): whole-file NACK citing `FILE_REJECTED_BY_POLICY`, itemizing every rejection.
- no header (MRR fataled before persisting) or a `fatal.reason` param or zero MRV verdicts: whole-file NACK citing the fatal reason / `NO_HEADER` / `NO_VERDICTS`.
- otherwise: ACK, `accepted = total - rejections`.

### Reject reason sources (two)

Rejections are gathered by `RejectGatherer` from the two mandate reject sources and merged by sequence:

- **MRV verdicts** (`man_validation_log`, outcome != PASS): validation FAILs, reported under their `MandateOutcome` name (e.g. `FAIL_ACCOUNT_NOT_FOUND`, `FAIL_STRUCTURE`, `FAIL_DUPLICATE_REF`).
- **MAS declines** (`mandate_request_entry.spine_state = SCORE_DECLINED`): rows that PASSED MRV then failed the bureau score gate (R-08). They never appear as a non-PASS MRV verdict, so they are a DISTINCT source, reported as `FAIL_SCORE_BELOW_THRESHOLD`. `HOLD_BUREAU_UNAVAILABLE` rows are never MIR-NACKed (R-12 carry-over): they stay `SCORE_PENDING` and are never `SCORE_DECLINED`.

### Synthetic response layout (A-57 class)

The response record layout is SYNTHETIC (A-57 class marker), pending the mandate response copybook, exactly as CIR's collections response is synthetic. It is recorded intent, not a placeholder: the pipe-delimited ACK/NACK/REJ lines above are the contract until the real copybook is attested.

### Idempotency and restart safety

`man_initial_response` is the write-ahead filename ledger and system of record for every initial mandate response. The filename `<client>_<msgId>_<route>_RESP.txt` is DETERMINISTIC from the full arrival identity (client, msg id, route: route is part of identity per A-45 cross-route twins), so a restart re-emits the SAME single file. The ledger row is inserted `ON CONFLICT (arrival_id) DO NOTHING` write-ahead of the file, and `StagedWrite` (temp -> fsync -> atomic rename) treats an existing target as a completed write (restart no-op, R-05). A resume therefore no-ops the row and stamps `written_at` exactly once: zero duplicate rows, zero duplicate files.

## Architecture and principles

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the CIR skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, SERIALIZABLE isolation (no READ COMMITTED override), layer-first packages (`config/`, `service/`, `domain/`, `data/model/`, `data/repo/`). A single `responseStep` tasklet (`InitialResponseTasklet -> InitialResponseService`) composes the response; `ResponseLedgerWriter` owns the REQUIRES_NEW write-ahead / stamp transactions; the shared `CrdbRetryExceptionHandler` re-runs the tasklet on a CRDB 40001 commit abort. `MirJobConfig` also sweeps stale `MIR_BATCH_` executions to ABANDONED before the runner fires (A-39a).

### Database

One business datasource: `dcre_man` via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second datasource (`DCRE_AGTOPS_DB_*`, platform-batch `HeartbeatDatasourceConfig`) carries only the `HeartbeatWriter` liveness stamp into `agt_ops`.

- Writes: `man_initial_response` (`INSERT ... ON CONFLICT (arrival_id) DO NOTHING`, then `written_at` stamped once), the response file under the client's `onhost-resp-man/out`, and its own `MIR_BATCH_*` metadata. No `spine_state` transition.
- Reads: `mandate_request_header`, `mandate_request_entry` (`SCORE_DECLINED` rows) and `man_validation_log`.
- Fail-closed: a missing or non-whitelisted `route.id` fails the job (A-45), and a client with no configured exchange directory fails rather than writing to a shared one.
- Outcome seam: `OutcomeSeamListener("mir", ...)` writes the constant `BUSINESS_ACCEPTED` to `<DCRE_EXCHANGE_ROOT>/outcomes/<JOB_NAME>`; the ACK/NACK decision lives in the ledger row and the file.

Liquibase owns the schema in the shared `dcre_man`, pure-XML changesets (MARK_RAN convergence guards in 000; explicit rollbacks in 000 and 001), per-service history tables (`mir_databasechangelog` / `mir_databasechangeloglock`), calendar layout `2026/07/`:

- `000-man-core-bootstrap.xml`: MARK_RAN-guarded pre-creates of the shared core tables, structurally identical (comments and the `mir-` changeset id prefix aside) to the copies in mrr, mrv, mas and mit (checked 2026-09-28).
- `001-man-initial-response.xml`: `man_initial_response` (UNIQUE `arrival_id`, UNIQUE `file_name`, `written_at` NULL = staged-not-written signal). MIR's only owned table, cloned from `cir_response`.
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6 metadata as typed XML, one changeset per object (six tables, three sequences), prefixed `MIR_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB.

The MRR-owned spine (`mandate_request_header` / `mandate_request_entry`) and the MRV-owned `man_validation_log` are read-only for MIR (single-writer, R-04) and are not in MIR's changelog.

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source/target compatibility 25.
- Gradle 9.5.1 through the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`).
- Docker: Testcontainers CockroachDB for the tests, and the image build.
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and `platform-batch:0.1.0` (`platform.files` `ExchangeLayout` / `StagedWrite` and `platform.model` arrive transitively).
- The per-client exchange layout: MIR imports `classpath:dcre-exchange-layout.yml` from platform-batch. platform-batch `origin/dev` carries `onhost-resp-man` for FNBCC01, FNBCC02 and FNBRF01 (checked 2026-09-28).
- For a real local run: CockroachDB on `localhost:26257` with `dcre_man` and `agt_ops`.

## Quickstart

Clean clone, no `.env` needed (working dev defaults committed in `application.yml`):

```bash
./gradlew test          # full suite, Docker required
./gradlew bootJar       # build/libs/mir-2.0.jar

java -jar build/libs/mir-2.0.jar \
  'arrival.id=<uuid>,java.lang.String,true' \
  'route.id=onhost-req-man,java.lang.String,false' \
  'client.token=FNBCC01,java.lang.String,false' \
  'msg.id=<msgId>,java.lang.String,false'
```

## Configuration

All keys live in `src/main/resources/application.yml` (the only profile) plus the imported `dcre-exchange-layout.yml`. Spring relaxed binding lets any property be overridden by its environment-variable form, so this table is the documented set, not a closed total.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam (`dcre.exchange-root`) and, through the imported layout's `dcre.exchange.root`, for the response file |
| `JOB_NAME` | unset | Set by AGT; names the outcome seam file |

Fixed in yml (no env placeholder): `dcre.batch.table-prefix: MIR_BATCH_`, Liquibase history tables `mir_databasechangelog` / `mir_databasechangeloglock`, virtual threads on.

## Testing

`./gradlew test` (Docker required; `useJUnitPlatform()` with no filter, so `*IT` classes and the Cucumber suite run in the same task). Testcontainers image: `cockroachdb/cockroach:v26.2.3`. A test copy of `dcre-exchange-layout.yml` in `src/test/resources` shadows the platform one and roots the exchange at `build/test-exchange`.

- `InitialResponseServiceIT`: the ACK path, the partial path itemizing both a validation FAIL and a MAS `SCORE_DECLINED`, the whole-file `BUSINESS_FILE_REJECTED` NACK, headerless / no-verdicts NACKs, and the fail-closed paths (unconfigured client, missing/invalid route, over-length client identity).
- `ManInitialResponseCaptureIT`: the ledger is queryable by filename with its reason and ratio, and the zero-duplicate resume audit (exactly one stamped file and one row after a kill between the row commit and the staged write, and after a kill between the file write and the stamp).
- `MirJobTest`: the real job end to end, restart no-op with a zero-duplicate ledger, and the seam-level route / unconfigured-client fail-closed.
- `config/MirJobConfigRetryTest`: the CRDB 40001 commit-abort retry is wired on the respond step.
- `CucumberSuiteTest`: `features/mir_initial_response.feature`, business-language coverage of the ACK/NACK/partial/file-fatal/ledger/restart flows.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mir:<version> .
kind load docker-image --name dcre-dev dcre-mir:<version>
```

Image base: `eclipse-temurin:25-jre-alpine` (`Dockerfile` copies `build/libs/mir-2.0.jar`). AGT launches MIR as a K8s Job in the mandates flow namespace (`AGT_NAMESPACE_MAN`, default `dcre-man`) with the image from `AGT_MIR_IMAGE` (empty default = launch-disabled). AGT injects `JOB_NAME`, `DCRE_DB_URL` (from `AGT_MAN_SERVICE_DB_URL`, default `dcre_man` on `crdb.dcre.svc.cluster.local`), `DCRE_EXCHANGE_ROOT=/exchange` (the `dcre-exchange` PVC), `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (AGT `origin/dev` `JobLauncher.java` and `application.yml`, checked 2026-09-28). The cluster itself, and the fleet-wide image switch, live in dcre-infra.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
