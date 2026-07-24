package za.co.fnb.dcre.mir.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Read model over the MRV-owned {@code man_validation_log} (shared dcre_man): the
 * per-row MRV verdicts that become MIR NACK / REJ reasons. outcome carries a
 * platform-model {@code MandateOutcome} name; a non-PASS row is a per-record rejection.
 * Standalone view (CIR VerdictView pattern): MIR only reads.
 */
@Table("man_validation_log")
public class ManValidationView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private Integer sequence;
    private String outcome;

    public Integer getSequence() { return sequence; }
    public String getOutcome() { return outcome; }
}
