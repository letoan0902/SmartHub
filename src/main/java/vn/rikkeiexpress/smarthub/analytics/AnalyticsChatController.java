package vn.rikkeiexpress.smarthub.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@Profile("analytics")
public class AnalyticsChatController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsChatController.class);

    private final ChatClient chatClient;

    // ToolCallbackProvider o day la SyncMcpToolCallbackProvider do starter mcp client tao:
    // cac tool duoc kham pha dong qua handshake stdio voi process smarthub profile mcp,
    // client khong can biet truoc hop dong tool nao ca
    public AnalyticsChatController(ChatClient.Builder builder, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = builder
                .defaultSystem("""
                        Bạn là chuyên viên phân tích dữ liệu vận hành của RikkeiExpress.
                        Hãy dùng các công cụ được cấp để truy vấn dữ liệu và xuất báo cáo khi cần.
                        Trả lời dựa trên số liệu công cụ trả về, tuyệt đối không tự bịa số liệu.
                        Trả lời bằng tiếng Việt, trình bày số liệu rõ ràng.
                        """)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
    }

    public record AnalyticsRequest(String message) {
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AnalyticsRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Trường message không được để trống"));
        }
        try {
            String answer = chatClient.prompt()
                    .user(request.message())
                    .call()
                    .content();
            return ResponseEntity.ok(Map.of("answer", answer));
        } catch (Exception e) {
            log.error("Phan tich du lieu that bai", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Hệ thống phân tích đang gián đoạn, vui lòng thử lại sau"));
        }
    }
}
