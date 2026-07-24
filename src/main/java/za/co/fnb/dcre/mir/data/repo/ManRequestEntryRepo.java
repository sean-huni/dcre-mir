package za.co.fnb.dcre.mir.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mir.data.model.ManRequestEntryView;

import java.util.List;
import java.util.UUID;

public interface ManRequestEntryRepo extends CrudRepository<ManRequestEntryView, UUID> {

    /** Spine rows in a given state (e.g. MAF SCORE_DECLINED), ordered by sequence for the response. */
    List<ManRequestEntryView> findByArrivalIdAndSpineStateOrderBySequence(UUID arrivalId, String spineState);
}
