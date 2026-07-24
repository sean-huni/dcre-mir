package za.co.fnb.dcre.mir.domain;

/**
 * One per-record rejection on a mandate initial response: the instruction sequence
 * plus its reason code. The reason is either an MRV {@code MandateOutcome} name
 * (validation FAIL) or the MAF decline code (spine SCORE_DECLINED). Emitted as a
 * {@code REJ|<sequence>|<reason>} detail line in the SYNTHETIC response copybook.
 */
public record RowReject(int sequence, String reason) {
}
