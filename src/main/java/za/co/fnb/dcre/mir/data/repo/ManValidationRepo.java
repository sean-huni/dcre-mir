package za.co.fnb.dcre.mir.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mir.data.model.ManValidationView;

import java.util.List;
import java.util.UUID;

public interface ManValidationRepo extends CrudRepository<ManValidationView, UUID> {

    /** The MRV per-record rejections (outcome != PASS), ordered by sequence for the response. */
    List<ManValidationView> findByArrivalIdAndOutcomeNotOrderBySequence(UUID arrivalId, String outcome);

    /** Whether MRV validated this arrival at all: zero verdicts means the file never validated (NACK). */
    long countByArrivalId(UUID arrivalId);
}
