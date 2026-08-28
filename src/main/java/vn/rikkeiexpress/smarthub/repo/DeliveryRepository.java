package vn.rikkeiexpress.smarthub.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.rikkeiexpress.smarthub.domain.Delivery;

import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByTrackingCode(String trackingCode);

    boolean existsByTrackingCode(String trackingCode);
}
