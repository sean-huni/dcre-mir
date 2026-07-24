package za.co.fnb.dcre.mir.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Read model over the MRR-owned {@code mandate_request_entry} spine (shared dcre_man):
 * MIR reads {@code spine_state} to surface the MAF decline. A row MAF put at
 * {@code SCORE_DECLINED} (below the per-client bureau threshold, R-08) passed MRV, so it
 * never appears in man_validation_log as a non-PASS; MIR itemizes it as a distinct
 * per-record rejection. Standalone view (CIR pattern): the derived query selects only the
 * mapped columns, so the spine's many other columns are untouched and MIR never writes it.
 */
@Table("mandate_request_entry")
public class ManRequestEntryView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private Integer sequence;
    private String spineState;

    public Integer getSequence() { return sequence; }
    public String getSpineState() { return spineState; }
}
