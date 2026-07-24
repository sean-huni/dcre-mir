package za.co.fnb.dcre.mir.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Read model over the MRR-owned {@code mandate_request_header} (shared dcre_man,
 * grants-based R-04/R-06): the whole-file identity MIR needs to compose the ACK/NACK.
 * {@code client_token} names the per-client exchange dir; {@code msg_id} + the arrival
 * route form the response filename; {@code entry_count} is the file's declared total.
 * Standalone view (CIR TxHeaderView pattern): only the mapped columns are selected, so
 * the header's audit/version columns are irrelevant here and MIR never writes it.
 */
@Table("mandate_request_header")
public class ManRequestHeaderView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private String clientToken;
    private String msgId;
    private Integer entryCount;

    public UUID getArrivalId() { return arrivalId; }
    public String getClientToken() { return clientToken; }
    public String getMsgId() { return msgId; }
    public Integer getEntryCount() { return entryCount; }
}
