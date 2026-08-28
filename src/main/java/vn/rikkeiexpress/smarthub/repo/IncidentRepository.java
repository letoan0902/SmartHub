package vn.rikkeiexpress.smarthub.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.rikkeiexpress.smarthub.domain.Incident;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
}
