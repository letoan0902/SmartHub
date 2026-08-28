package vn.rikkeiexpress.smarthub.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsChatController {

    private static final Logger log = LoggerFactory.getLogger(OperationsChatController.class);

    private final ChatClient chatClient;

    public OperationsChatController(ChatClient.Builder builder, OperationsTools operationsTools,
                                    ChatMemory chatMemory) {
        // Spring AI 1.1.8 chua co cau hinh cung gioi han so vong tool calling
        // (da kiem bang javap tren spring-ai-model 1.1.8: ToolCallingChatOptions chi co
        // internalToolExecutionEnabled, khong co maxIterations), nen chot chan vong lap
        // dat o tang system prompt duoi day.
        this.chatClient = builder
                .defaultSystem("""
                        Bạn là điều phối viên xử lý sự cố của trung tâm vận hành RikkeiExpress.
                        Nhiệm vụ: bóc tách từ tin nhắn của nhân viên tuyến đầu đúng 4 thực thể sau:
                        1. trackingCode: mã vận đơn định dạng RK-yyyy-xxx (ví dụ RK-2026-001).
                        2. incidentType: quy về đúng một trong HỎNG_HÓC, GIAO_TRỄ, THẤT_LẠC.
                           Từ ngữ đời thường như ướt, vỡ, bể, móp, hỏng đồ nghĩa là HỎNG_HÓC;
                           chậm, trễ, lâu quá chưa tới nghĩa là GIAO_TRỄ;
                           mất, thất lạc, không thấy hàng nghĩa là THẤT_LẠC.
                        3. hubCode: một trong HN-01, SG-02, DN-03.
                           "kho Hà Nội" là HN-01, "kho Sài Gòn" là SG-02, "kho Đà Nẵng" là DN-03.
                        4. severity: hỏng nặng, mất hàng, khách đòi bồi thường là CRITICAL;
                           trễ thông thường là LOW hoặc MEDIUM tùy mức ảnh hưởng.
                        Chỉ khi đủ cả 4 thực thể mới gọi công cụ createIncident.
                        Sau khi tạo phiếu thành công: nếu là HỎNG_HÓC thì gọi tiếp updateDeliveryStatus
                        chuyển đơn sang DAMAGED; nếu là GIAO_TRỄ thì chuyển sang DELAYED.
                        Thiếu thực thể nào thì hỏi lại người dùng đúng thực thể đó, không tự đoán.
                        Công cụ trả về thông báo lỗi thì diễn giải lại lịch sự cho người dùng.
                        Không gọi công cụ quá 6 lượt trong một phiên hội thoại.
                        Trả lời bằng tiếng Việt, ngắn gọn, đúng nghiệp vụ.
                        """)
                .defaultTools(operationsTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public record ChatRequest(String message, String conversationId) {
    }

    public record ChatResponse(String conversationId, String answer) {
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Trường message không được để trống"));
        }
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString()
                : request.conversationId();
        try {
            String answer = chatClient.prompt()
                    .user(request.message())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            return ResponseEntity.ok(new ChatResponse(conversationId, answer));
        } catch (Exception e) {
            log.error("Xu ly hoi thoai su co that bai", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Hệ thống điều phối đang gián đoạn, vui lòng thử lại sau"));
        }
    }
}
