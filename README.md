# dcre-mir

Mandates Initial Response: the terminal response leg of the M10 mandates request flow (SCRUM-78) that composes the initial ACK/NACK for one OnHost mandate arrival and stages it back to the client exchange (`onhost-resp-man/out`) in `dcre_man`.

## What it does

MIR is a terminal leg of the mandates request DAG (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). Once MIT has initialized the accepted rows, AGT launches MIR as a short-lived Kubernetes Job with `arrival.id` (plus `route.id`, and optional `fatal.reason` / `client.token` / `msg.id` / `outcome.hint`) as JobParameters. MIR reads the arrival's whole-file identity and per-row verdicts and writes a single response file the OnHost client can consume. It is a direct clone of `dcre-cir` (Collections Initial Response); the mandate variant differs only in its data sources and its output channel.

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

## Idempotency and restart safety

`man_initial_response` is the write-ahead filename ledger and system of record for every initial mandate response. The filename `<client>_<msgId>_<route>_RESP.txt` is DETERMINISTIC from the full arrival identity (client, msg id, route: route is part of identity per A-45 cross-route twins), so a restart re-emits the SAME single file. The ledger row is inserted `ON CONFLICT (arrival_id) DO NOTHING` write-ahead of the file, and `StagedWrite` (temp -> fsync -> atomic rename) treats an existing target as a completed write (restart no-op, R-05). A resume therefore no-ops the row and stamps `written_at` exactly once: zero duplicate rows, zero duplicate files.

## Architecture

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the CIR skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, SERIALIZABLE isolation (no READ COMMITTED override), layer-first packages (`config/`, `service/`, `domain/`, `data/model/`, `data/repo/`). A single `responseStep` tasklet (`InitialResponseTasklet -> InitialResponseService`) composes the response; `ResponseLedgerWriter` owns the REQUIRES_NEW write-ahead / stamp transactions; the shared `CrdbRetryExceptionHandler` re-runs the tasklet on a CRDB 40001 commit abort. `MirJobConfig` also sweeps stale `MIR_BATCH_` executions to ABANDONED before the runner fires (A-39a).

## Database

Liquibase owns the schema in the shared `dcre_man`, pure-XML changesets + MARK_RAN guards + rollbacks, per-service history tables (`mir_databasechangelog` / `mir_databasechangeloglock`), calendar layout `2026/07/`:

- `000-man-core-bootstrap.xml`: MARK_RAN-guarded pre-creates of the shared core tables (byte-equivalent in shape to MRR's copy, only the changeset id prefix differs, per the house shared-core canon).
- `001-man-initial-response.xml`: `man_initial_response` (UNIQUE `arrival_id`, UNIQUE `file_name`, `written_at` NULL = staged-not-written signal). MIR's only owned table, cloned from `cir_response`.
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 DDL (`batch-metadata-mir.sql`), prefixed `MIR_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB.

The MRR-owned spine (`mandate_request_header` / `mandate_request_entry`) and the MRV-owned `man_validation_log` are read-only for MIR (single-writer, R-04) and are not in MIR's changelog.

## Prerequisites

- `dcre_man` reachable via `DCRE_DB_URL` (default `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable`).
- The per-client exchange layout: MIR imports `classpath:dcre-exchange-layout.yml` and stages into `onhost-resp-man/out`. The shipped platform-batch layout carries the `onhost-resp-man` channel from SCRUM-73 T2.

## Tests

Red-first Testcontainers CockroachDB + filesystem, 26 tests across 5 classes:

- `InitialResponseServiceIT` (10): the ACK path, the partial path itemizing both a validation FAIL and a MAS `SCORE_DECLINED`, the whole-file `BUSINESS_FILE_REJECTED` NACK, headerless / no-verdicts NACKs, and the fail-closed paths (unconfigured client, missing/invalid route, over-length client identity).
- `ManInitialResponseCaptureIT` (5): the ledger is queryable by filename with its reason and ratio, and the zero-duplicate resume audit (exactly one stamped file and one row after a kill between the row commit and the staged write, and after a kill between the file write and the stamp).
- `MirJobTest` (4): the real job end to end, restart no-op with a zero-duplicate ledger, and the seam-level route / unconfigured-client fail-closed.
- `MirJobConfigRetryTest` (1): the CRDB 40001 commit-abort retry is wired on the respond step.
- `mir_initial_response.feature` (6 BDD scenarios): business-language coverage of the ACK/NACK/partial/file-fatal/ledger/restart flows.
