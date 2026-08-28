package vn.rikkeiexpress.smarthub.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.rikkeiexpress.smarthub.domain.Delivery;
import vn.rikkeiexpress.smarthub.domain.Incident;
import vn.rikkeiexpress.smarthub.repo.DeliveryRepository;
import vn.rikkeiexpress.smarthub.repo.IncidentRepository;

import java.util.Optional;
import java.util.Set;

/**
 * Bo cong cu cho agent dieu phoi su co. Nguyen tac phong thu: moi loi deu tra ve
 * chuoi thong bao de model doc va dien giai lai cho nguoi dung, KHONG throw exception
 * de vong lap tool calling khong bao gio sap giua chung.
 */
@Component
public class OperationsTools {

    private static final Set<String> INCIDENT_TYPES = Set.of("HỎNG_HÓC", "GIAO_TRỄ", "THẤT_LẠC");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "CRITICAL");
    private static final Set<String> DELIVERY_STATUSES = Set.of("IN_TRANSIT", "DELIVERED", "DELAYED", "DAMAGED");

    private final DeliveryRepository deliveryRepository;
    private final IncidentRepository incidentRepository;

    public OperationsTools(DeliveryRepository deliveryRepository, IncidentRepository incidentRepository) {
        this.deliveryRepository = deliveryRepository;
        this.incidentRepository = incidentRepository;
    }

    @Tool(description = "Tạo phiếu sự cố vận hành. Cần đủ: mã vận đơn tồn tại trong hệ thống, "
            + "loại sự cố đúng một trong HỎNG_HÓC / GIAO_TRỄ / THẤT_LẠC, mã bưu cục (ví dụ HN-01), "
            + "mức độ đúng một trong LOW / MEDIUM / CRITICAL, mô tả chi tiết. "
            + "Kết quả lỗi thì đọc thông báo và hỏi lại người dùng.")
    @Transactional
    public String createIncident(String trackingCode, String incidentType, String hubCode,
                                 String severity, String description) {
        if (trackingCode == null || trackingCode.isBlank()) {
            return "Thiếu mã vận đơn, hãy hỏi khách mã vận đơn dạng RK-yyyy-xxx.";
        }
        if (incidentType == null || incidentType.isBlank()) {
            return "Thiếu loại sự cố, cần một trong: HỎNG_HÓC, GIAO_TRỄ, THẤT_LẠC.";
        }
        if (hubCode == null || hubCode.isBlank()) {
            return "Thiếu mã bưu cục xảy ra sự cố, ví dụ HN-01, SG-02, DN-03.";
        }
        if (severity == null || severity.isBlank()) {
            return "Thiếu mức độ nghiêm trọng, cần một trong: LOW, MEDIUM, CRITICAL.";
        }
        if (description == null || description.isBlank()) {
            return "Thiếu mô tả chi tiết sự cố, hãy hỏi khách diễn biến cụ thể.";
        }
        if (!INCIDENT_TYPES.contains(incidentType)) {
            return "Loại sự cố '" + incidentType + "' không hợp lệ. Giá trị hợp lệ: HỎNG_HÓC, GIAO_TRỄ, THẤT_LẠC.";
        }
        if (!SEVERITIES.contains(severity)) {
            return "Mức độ '" + severity + "' không hợp lệ. Giá trị hợp lệ: LOW, MEDIUM, CRITICAL.";
        }
        if (!deliveryRepository.existsByTrackingCode(trackingCode)) {
            return "Mã vận đơn " + trackingCode + " không tồn tại trong hệ thống, hãy kiểm tra lại với khách.";
        }

        Incident incident = new Incident();
        incident.setTrackingCode(trackingCode);
        incident.setIncidentType(incidentType);
        incident.setHubCode(hubCode);
        incident.setSeverity(severity);
        incident.setDescription(description);
        incident.setStatus("OPEN");
        Incident saved = incidentRepository.save(incident);

        return "Đã tạo phiếu sự cố số " + saved.getId() + " (trạng thái OPEN) cho vận đơn " + trackingCode
                + ", loại " + incidentType + ", mức độ " + severity + ", tại bưu cục " + hubCode + ".";
    }

    @Tool(description = "Cập nhật trạng thái đơn hàng theo mã vận đơn. Trạng thái đúng một trong "
            + "IN_TRANSIT / DELIVERED / DELAYED / DAMAGED. "
            + "Sự cố HỎNG_HÓC đi với DAMAGED, GIAO_TRỄ đi với DELAYED.")
    @Transactional
    public String updateDeliveryStatus(String trackingCode, String newStatus) {
        if (trackingCode == null || trackingCode.isBlank()) {
            return "Thiếu mã vận đơn cần cập nhật trạng thái.";
        }
        if (newStatus == null || newStatus.isBlank()) {
            return "Thiếu trạng thái mới, cần một trong: IN_TRANSIT, DELIVERED, DELAYED, DAMAGED.";
        }
        if (!DELIVERY_STATUSES.contains(newStatus)) {
            return "Trạng thái '" + newStatus + "' không hợp lệ. Giá trị hợp lệ: IN_TRANSIT, DELIVERED, DELAYED, DAMAGED.";
        }

        Optional<Delivery> found = deliveryRepository.findByTrackingCode(trackingCode);
        if (found.isEmpty()) {
            return "Mã vận đơn " + trackingCode + " không tồn tại trong hệ thống, hãy kiểm tra lại với khách.";
        }

        Delivery delivery = found.get();
        String oldStatus = delivery.getStatus();
        delivery.setStatus(newStatus);
        deliveryRepository.save(delivery);

        return "Đã cập nhật vận đơn " + trackingCode + " từ trạng thái " + oldStatus + " sang " + newStatus + ".";
    }
}
