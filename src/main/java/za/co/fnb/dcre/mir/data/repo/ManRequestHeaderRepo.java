package za.co.fnb.dcre.mir.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mir.data.model.ManRequestHeaderView;

import java.util.Optional;
import java.util.UUID;

public interface ManRequestHeaderRepo extends CrudRepository<ManRequestHeaderView, UUID> {

    Optional<ManRequestHeaderView> findByArrivalId(UUID arrivalId);
}
