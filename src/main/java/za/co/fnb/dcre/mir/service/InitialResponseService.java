package za.co.fnb.dcre.mir.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mir.data.model.ManRequestHeaderView;
import za.co.fnb.dcre.mir.data.repo.ManRequestHeaderRepo;
import za.co.fnb.dcre.mir.domain.RowReject;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;
import za.co.fnb.dcre.platform.files.StagedWrite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Business tier (configuration.md point 21): composes the initial mandate ACK/NACK for one
 * arrival from the dcre_man spine + MRV verdicts + MAF SCORE_DECLINED state, and stages it to
 * the OnHost mandate response directory (onhost-resp-man). Cloned from CIR InitialResponseService.
 *
 * <p>SYNTHETIC-CONTRACT response layout (A-57 class), pending the mandate response-copybook:
 * a header line {@code ACK|<client>|<msgId>|<accepted>/<total>|ACCEPTED_BY_DCRE} or
 * {@code NACK|<client>|<msgId>|0/<total>|<reason>}, followed by one
 * {@code REJ|<sequence>|<reason>} line per rejection. ACK means accepted-by-DCRE.
 * MIR is the terminal response leg (DAG MIT -> {MIR, MRW}); it does NOT transition the spine
 * forward. StagedWrite makes a re-run a restart no-op (R-05); the ledger insert is idempotent
 * on the full arrival identity, so a restart re-emits the SAME single file and row.
 */
@Service
public class InitialResponseService {

    public record Result(Path responseFile, boolean written) { }

    // route tokens are hyphen-only lowercase constants (onhost-req-man, ...)
    private static final Pattern ROUTE_TOKEN = Pattern.compile("[a-z0-9-]+");

    // man_initial_response.reason column width: free-text reasons are clipped to fit.
    private static final int REASON_MAX = 64;

    // man_initial_response.client column width. Its header source client_token is also VARCHAR(16),
    // so a real header never overflows; the headerless fallback takes client.token verbatim, so the
    // guard still fails an over-length identity closed (never clipped) before the write-ahead insert.
    private static final int CLIENT_MAX = 16;

    /** The ACK/NACK outcome plus the acceptance ratio and NACK reason captured into man_initial_response. */
    private record Decision(String outcome, String reason, Integer acceptedCount, Integer totalCount) { }

    private final ManRequestHeaderRepo headers;
    private final RejectGatherer gatherer;
    private final ExchangeLayout layout;
    private final ResponseLedgerWriter ledger;

    public InitialResponseService(final ManRequestHeaderRepo headers, final RejectGatherer gatherer,
                                  final ExchangeLayout layout, final ResponseLedgerWriter ledger) {
        this.headers = headers;
        this.gatherer = gatherer;
        this.layout = layout;
        this.ledger = ledger;
    }

    public Result respond(final UUID arrivalId, final String route, final String fatalReason,
                          final String clientToken, final String msgId,
                          final String outcomeHint) throws IOException {
        if (route == null || !ROUTE_TOKEN.matcher(route).matches()) {
            // A-45: route is part of the arrival identity; a fallback token would re-create the
            // (client, msgId) collision class, so fail the job. The whitelist also keeps the token
            // from escaping onhost-resp-man as a path.
            throw new IllegalArgumentException(
                    "arrival route missing or invalid: required for response identity (A-45)");
        }
        final Optional<ManRequestHeaderView> maybeHeader = headers.findByArrivalId(arrivalId);
        if (maybeHeader.isEmpty()) {
            // MRR fataled before persisting the header; identity comes from AGT job params.
            final String client = hasText(clientToken) ? clientToken : "UNKNOWN";
            final String responseMsgId = hasText(msgId) ? msgId : arrivalId.toString();
            final String reason = fatalReason != null ? fatalReason : "NO_HEADER";
            return stage(arrivalId, client, responseMsgId, route,
                    List.of("NACK|" + client + "|" + responseMsgId + "|0/0|" + reason),
                    new Decision("NACK", reason, 0, 0));
        }
        final ManRequestHeaderView header = maybeHeader.get();
        final String client = header.getClientToken().strip();
        final String headerMsgId = header.getMsgId().strip();
        final int total = header.getEntryCount();

        final RejectGatherer.Gathered gathered = gatherer.gather(arrivalId);
        final List<String> lines = new ArrayList<>();
        final Decision decision;
        if ("BUSINESS_FILE_REJECTED".equals(outcomeHint)) {
            // R-41 ALL_OR_NOTHING: whole file refused by policy, itemized per rejection (MRV + MAF).
            lines.add("NACK|" + client + "|" + headerMsgId + "|0/" + total + "|FILE_REJECTED_BY_POLICY");
            addRejLines(lines, gathered.rejects());
            decision = new Decision("NACK", "FILE_REJECTED_BY_POLICY", 0, total);
        } else if (fatalReason != null || gathered.mrvVerdictCount() == 0) {
            final String reason = fatalReason != null ? fatalReason : "NO_VERDICTS";
            lines.add("NACK|" + client + "|" + headerMsgId + "|0/" + total + "|" + reason);
            decision = new Decision("NACK", reason, 0, total);
        } else {
            final int accepted = total - gathered.rejects().size();
            lines.add("ACK|" + client + "|" + headerMsgId + "|" + accepted + "/" + total + "|ACCEPTED_BY_DCRE");
            addRejLines(lines, gathered.rejects());
            // A clean/partial ACK carries no NACK reason; the ratio lives in accepted/total.
            decision = new Decision("ACK", null, accepted, total);
        }
        return stage(arrivalId, client, headerMsgId, route, lines, decision);
    }

    private static void addRejLines(final List<String> lines, final List<RowReject> rejects) {
        for (final RowReject reject : rejects) {
            lines.add("REJ|" + reject.sequence() + "|" + reject.reason());
        }
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private Result stage(final UUID arrivalId, final String client, final String msgId, final String route,
                         final List<String> lines, final Decision decision) throws IOException {
        // Identity is NEVER clipped (unlike reason): an over-length client is an upstream contract
        // violation; reject it with a readable message BEFORE the write-ahead insert so it never
        // surfaces as a raw CRDB "value too long for type varchar(16)" that kills the job.
        if (client.length() > CLIENT_MAX) {
            throw new IllegalStateException(
                    "man_initial_response.client identity exceeds %d chars: %s".formatted(CLIENT_MAX, client));
        }
        // Per-client dir <root>/<base>/onhost-resp-man/out; fail-closed for an unconfigured client
        // (the headerless "UNKNOWN" fallback has no dir, so the job fails). A-45: the route token keeps
        // the R-05 idempotency key (the filename) aligned with the full arrival identity. Resolve BEFORE
        // any capture: a fail-closed config error writes neither a row nor a file.
        final String fileName = "%s_%s_%s_RESP.txt".formatted(client, msgId, route);
        final Path target = layout.resolve(client, ExchangeChannel.ONHOST_RESP_MAN, ExchangeSub.OUT).resolve(fileName);
        // Write-ahead: the filename row commits (REQUIRES_NEW) BEFORE the file exists, so a kill between
        // here and the write leaves a written_at IS NULL stuck signal; the table is the system of record.
        // The insert is idempotent on arrival_id, StagedWrite is a restart no-op (R-05), and the stamp is
        // guarded, so a resume no-ops the row and stamps exactly once.
        ledger.stage(arrivalId, client, msgId, route, decision.outcome(), fileName,
                clip(decision.reason()), decision.acceptedCount(), decision.totalCount());
        final boolean written = StagedWrite.write(target, lines);
        ledger.stampWritten(arrivalId);
        return new Result(target, written);
    }

    /**
     * man_initial_response.reason is VARCHAR(64) descriptive free text; a free-text fatalReason job
     * param can exceed that. Clip for the row (the full reason stays in the response file line) so an
     * over-length reason never turns a describable NACK into a hard write-ahead job failure.
     */
    private static String clip(final String reason) {
        return reason != null && reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
    }
}
