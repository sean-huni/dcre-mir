package za.co.fnb.dcre.mir.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mir.data.repo.ManRequestEntryRepo;
import za.co.fnb.dcre.mir.data.repo.ManValidationRepo;
import za.co.fnb.dcre.mir.domain.RowReject;
import za.co.fnb.dcre.platform.model.MandateOutcome;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Business tier: collects the per-record rejections for one arrival from the TWO
 * mandate reject sources (plan T7), so InitialResponseService stays a pure composer.
 *
 * <ul>
 *   <li><b>MRV verdicts</b> ({@code man_validation_log}, outcome != PASS): the
 *       validation FAILs, reported under their {@code MandateOutcome} name.</li>
 *   <li><b>MAS declines</b> ({@code mandate_request_entry.spine_state = SCORE_DECLINED}):
 *       rows that PASSED MRV then failed the bureau score gate (R-08). These never
 *       appear as a non-PASS MRV verdict, so they are a DISTINCT source, reported as
 *       {@link MandateOutcome#FAIL_SCORE_BELOW_THRESHOLD}.</li>
 * </ul>
 *
 * The two sources are disjoint by construction (MAS runs only on VALIDATED rows), but
 * the merge is keyed by sequence with MRV precedence so a defensive overlap is
 * de-duplicated rather than double-counted in the acceptance ratio.
 */
@Service
public class RejectGatherer {

    /** spine_state MAS sets when the bureau score is below the per-client threshold (plan T5). */
    static final String SCORE_DECLINED = "SCORE_DECLINED";
    private static final String PASS = MandateOutcome.PASS.name();

    private final ManValidationRepo validations;
    private final ManRequestEntryRepo entries;

    public RejectGatherer(final ManValidationRepo validations, final ManRequestEntryRepo entries) {
        this.validations = validations;
        this.entries = entries;
    }

    /** The merged per-record rejections plus the MRV verdict count (0 = MRV never validated). */
    public record Gathered(List<RowReject> rejects, long mrvVerdictCount) {
    }

    public Gathered gather(final UUID arrivalId) {
        final TreeMap<Integer, String> bySequence = new TreeMap<>();
        validations.findByArrivalIdAndOutcomeNotOrderBySequence(arrivalId, PASS)
                .forEach(v -> bySequence.put(v.getSequence(), v.getOutcome()));
        entries.findByArrivalIdAndSpineStateOrderBySequence(arrivalId, SCORE_DECLINED)
                .forEach(e -> bySequence.putIfAbsent(e.getSequence(),
                        MandateOutcome.FAIL_SCORE_BELOW_THRESHOLD.name()));
        final List<RowReject> rejects = bySequence.entrySet().stream()
                .map(e -> new RowReject(e.getKey(), e.getValue()))
                .toList();
        return new Gathered(rejects, validations.countByArrivalId(arrivalId));
    }
}
