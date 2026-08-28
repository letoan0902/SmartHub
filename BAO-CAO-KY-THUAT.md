# Báo cáo kỹ thuật SmartHub

## 1. Kiến trúc tổng thể

Một codebase, ba profile, ba vai trò chạy khác nhau:

```
+---------------------------------------------------------------+
| TANG GIAO TIEP                                                |
|   GET  /api/v1/rag/ask            (profile mac dinh)          |
|   POST /api/v1/operations/chat    (profile mac dinh)          |
|   POST /api/v1/analytics/chat     (profile analytics)         |
+---------------------------------------------------------------+
| TANG DIEU PHOI AI                                             |
|   RagService: ChatClient + QuestionAnswerAdvisor (topK 4)     |
|   OperationsChat: ChatClient + 2 @Tool + MessageChatMemory    |
|   AnalyticsChat: ChatClient + ToolCallbackProvider tu MCP     |
|   OTLP exporter -> Langfuse (moi luot goi model/tool)         |
+---------------------------------------------------------------+
| TANG DU LIEU                                                  |
|   Supabase Postgres: deliveries, incidents (JPA)              |
|   pgvector: vector_store 768 chieu, HNSW, cosine              |
+---------------------------------------------------------------+

Profile mac dinh   : web 8080, phan he 1 + 2, MCP server/client deu tat
Profile mcp        : khong web, MCP server stdio, khong export telemetry
Profile analytics  : nhu mac dinh + MCP client spawn "java -jar ... --spring.profiles.active=mcp"
```

Điểm đáng chú ý là quan hệ giữa hai profile cuối: profile analytics không nói chuyện với một service từ xa mà tự spawn một process con của chính jar này ở profile mcp, hai bên bắt tay bằng JSON-RPC qua stdin/stdout của process con. Vì vậy toàn bộ log của ứng dụng phải rời khỏi stdout (xem chuyên đề 3).

## 2. Chuyên đề 1: RAG tra cứu quy chế

Tài liệu quy chế có cấu trúc "## Điều N" rất đều, mỗi Điều là một đơn vị ngữ nghĩa trọn vẹn (biểu phí, bồi thường, khiếu nại...). Tham số cắt chunk chọn 512 token với ngưỡng tối thiểu 350 ký tự vì hai lý do đối xứng:

- Chunk quá nhỏ (cỡ 300 ký tự) sẽ cắt đôi bảng biểu phí hoặc tách mức bồi thường khỏi điều kiện áp dụng của nó; câu hỏi "hỏng có khai giá đền bao nhiêu" có thể chỉ khớp được nửa câu trả lời.
- Chunk quá lớn (cỡ 1000 token) gộp nhiều Điều vào một vector, điểm tương đồng bị pha loãng và ngữ cảnh đưa vào prompt phình to vô ích.

512 token xấp xỉ độ dài một Điều dài nhất của quy chế, nên phần lớn chunk trùng khớp ranh giới Điều một cách tự nhiên. Ngưỡng tương đồng 0.5 là điểm cân bằng đo được: đặt cao hơn (0.7) thì câu hỏi diễn đạt đời thường ("hàng bể đền sao") hay bị bỏ sót vì từ vựng lệch với văn bản pháp quy; đặt thấp hơn thì các Điều không liên quan lọt vào làm model dễ trộn số liệu. Khoảng cách cosine phù hợp văn bản vì so hướng của vector ngữ nghĩa, không bị độ dài đoạn văn chi phối như khoảng cách Euclid.

Tầng system prompt khóa hành vi ba lớp: chỉ trả lời từ ngữ cảnh được cấp, luôn nêu số Điều để nhân viên đối chiếu lại được văn bản gốc, và khi thiếu thông tin phải trả đúng câu "Tôi không tìm thấy thông tin trong tài liệu quy chế." kèm lời mời gọi hotline. Câu từ chối chuẩn hóa giúp phía client nhận diện được trường hợp không có căn cứ, thay vì nhận một câu bịa trôi chảy.

Danh sách nguồn trả về không lấy từ nội bộ advisor mà chạy lại chính phép truy vấn của advisor (cùng topK, cùng ngưỡng, cùng câu hỏi) qua `vectorStore.similaritySearch`. Hai phép truy vấn giống hệt tham số nên kết quả nhất quán, đổi lại không phụ thuộc khóa context nội bộ của advisor giữa các phiên bản thư viện.

### Kết quả đánh giá thực nghiệm

Chạy thật trên Supabase (Session pooler khu vực ap-southeast-2) với tài liệu quy chế chính thức QC-RKE-2026-01: pipeline nạp thành công 8 chunk (512 token, minChunkSizeChars 350). Bộ câu hỏi kiểm chứng được chấm bằng cách đối chiếu trực tiếp với văn bản gốc:

| Câu hỏi kiểm chứng | Đáp án chuẩn theo quy chế | Kết quả | Latency |
|---|---|---|---|
| Cước 0,5kg liên tỉnh khác miền | 22.000đ tiêu chuẩn / 35.000đ nhanh (Điều 3 Khoản 3) | Đúng, trích dẫn đúng Điều | 6,1s |
| Bồi thường khi không khai giá | 4 lần cước, không vượt 1.000.000đ (Điều 8 Khoản 1) | Đúng cả mức trần (câu gài) | 4,4s |
| Cách tính phụ phí COD | 1,5% giá trị thu hộ, tối thiểu 5.000đ (Điều 4 Khoản 1) | Đúng | 5,1s |
| Quyền lợi khi giao trễ | Hoàn 100% cước (Điều 6 Khoản 2) | Đúng | 6,2s |
| Thời hạn gửi và xử lý khiếu nại | 7 ngày; hư hỏng phản hồi trong 3 ngày làm việc (Điều 7, Điều 11) | Đúng | 5,1s |
| Phí gửi xe máy sang Mỹ (ngoài phạm vi) | Phải từ chối | Trả đúng nguyên văn câu từ chối chuẩn kèm tổng đài 1900 2929 | 5,7s |
| Chương trình tích điểm (ngoài phạm vi) | Phải từ chối | Trả đúng nguyên văn câu từ chối chuẩn | 4,5s |

Tỉ lệ trả lời đúng nội dung 5/5, trích dẫn đúng Điều 5/5, từ chối đúng với câu ngoài phạm vi 2/2; mọi phản hồi đều kèm 4 đoạn nguồn từ đúng file PDF. Điểm chưa đạt duy nhất là latency: trung bình khoảng 5,3 giây, vượt mức SLA 3 giây của bản đặc tả. Nguyên nhân chính là gemini-3.7-flash thuộc dòng model suy nghĩ nhiều bước cộng với khứ hồi mạng tới hạ tầng model và database ở nước ngoài; hướng cải thiện đã xác định: dùng model bậc nhẹ hơn cho tra cứu thuần, giảm topK từ 4 xuống 3, và rút gọn system prompt.

Phía Agent (chuyên đề 2), ca kiểm chứng dùng đúng câu ví dụ của bản đặc tả "Đơn RK-2026-001 bị ướt sũng hỏng đồ ở kho Hà Nội" cho kết quả trọn chuỗi: bóc đủ 4 thực thể (suy ra CRITICAL từ chi tiết "hàng giá trị cao"), tạo phiếu sự cố OPEN rồi chuyển trạng thái đơn sang DAMAGED, diễn giải lại tự nhiên. Ca phòng thủ với mã đơn không tồn tại RK-9999-888 được từ chối lịch sự kèm hướng dẫn kiểm tra lại, ứng dụng không lỗi. Một quan sát phụ đáng giá cho chuyên đề 4: trong suốt phiên chạy chưa dựng Langfuse, exporter OTel liên tục báo lỗi kết nối localhost:3000 ở luồng nền nhưng không request nghiệp vụ nào bị chậm hay hỏng - minh chứng sống cho thiết kế non-blocking và cơ chế chịu lỗi của Batch Span Processor.

## 3. Chuyên đề 2: Agent xử lý sự cố

Bài toán lõi của agent là bóc tách 4 thực thể từ một câu nói đời thường: mã vận đơn, loại sự cố, bưu cục, mức độ. Chiến lược đặt toàn bộ ánh xạ từ vựng vào system prompt: "ướt, vỡ, bể, móp" quy về HỎNG_HÓC, "kho Hà Nội" quy về HN-01, "khách đòi bồi thường" đẩy mức độ lên CRITICAL. Cách này tận dụng đúng sở trường của LLM (hiểu biến thể ngôn ngữ) và giữ cho tool chỉ phải làm việc với giá trị chuẩn hóa.

Các giá trị nghiệp vụ HỎNG_HÓC / GIAO_TRỄ / THẤT_LẠC có dấu tiếng Việt nên không thể làm hằng số enum Java; entity và tham số tool dùng String, còn tính hợp lệ được ép bằng `Set.of(...)` ngay đầu mỗi tool. Đây là đánh đổi có chủ đích: giữ giá trị nghiệp vụ nguyên bản tiếng Việt (đúng ngôn ngữ vận hành nội bộ) thay vì phiên âm không dấu rồi phải dịch qua lại hai chiều.

Phòng thủ zero-crash: hai tool không bao giờ ném exception. Thiếu trường nào trả thông báo riêng cho trường đó, giá trị ngoài tập hợp lệ trả kèm danh sách giá trị đúng, mã vận đơn không tồn tại trả câu hướng dẫn kiểm tra lại với khách. Model đọc chuỗi lỗi đó như một quan sát và tự diễn giải lịch sự cho người dùng, vòng lặp tool calling tiếp tục thay vì đổ 500 giữa hội thoại. Phiếu sự cố chỉ được tạo khi đủ cả 4 thực thể; thiếu gì hỏi lại đúng phần đó, chống việc model tự đoán dữ liệu ghi vào cơ sở dữ liệu.

## 4. Chuyên đề 3: MCP đối soát dữ liệu

Khác biệt bản chất giữa REST và MCP không nằm ở định dạng mà ở chiều của hợp đồng. Với REST, client phải biết trước hợp đồng: URL nào, tham số gì, trả về cấu trúc nào - hợp đồng chết cứng lúc viết code. Với MCP, sau khi client mở kết nối là bước handshake `initialize` trao đổi capabilities hai chiều, rồi client gọi `tools/list` để server tự khai danh sách tool kèm mô tả và JSON Schema tham số. Trong SmartHub, `AnalyticsChatController` không import bất kỳ lớp tool nào: nó chỉ nhận một `ToolCallbackProvider` mà starter MCP client dựng từ những gì khám phá được qua stdio. Server thêm tool thứ tư thì client tự thấy, không sửa một dòng client nào.

Stdio Pollution là rủi ro đặc thù của transport stdio: JSON-RPC đi chung đường stdout với mọi thứ process in ra, chỉ cần một dòng banner hay một dòng log INFO chen vào là client parse hỏng và phiên MCP chết. SmartHub cách ly ba lớp: tắt banner Spring, `logback-spring.xml` trỏ toàn bộ log sang System.err ở mọi profile, và profile mcp tắt luôn export telemetry để không sinh thêm output ngoài ý muốn. Chuẩn MCP cho phép server ghi tự do vào stderr, nên stderr thành kênh chẩn đoán hợp lệ duy nhất.

`SafeSqlValidator` chặn SQL do model sinh theo nhiều lớp kế tiếp: bắt buộc bắt đầu bằng SELECT; cấm dấu chấm phẩy ở giữa để chặn tiêm nhiều statement; cấm theo từ nguyên (regex `\b`) các từ khóa DROP, DELETE, UPDATE, INSERT, ALTER, TRUNCATE, CREATE, GRANT, REVOKE, EXECUTE - so theo từ nguyên để không chặn oan tên cột như `created_at`; cuối cùng ép LIMIT 100 nếu thiếu, vừa bảo vệ cơ sở dữ liệu vừa giữ kết quả đủ nhỏ cho cửa sổ ngữ cảnh của model. Hai tool còn lại (`getHubPerformance`, `exportHubReportMarkdown`) không nhận SQL tự do mà chỉ nhận mã bưu cục, mọi truy vấn đều tham số hóa bằng `?` của JdbcTemplate.

## 5. Chuyên đề 4: LLMOps với Langfuse

Trace được đẩy qua OTLP về endpoint `/api/public/otel/v1/traces` của Langfuse với sampling 100%. Một lượt chat sự cố hiện thành cây span: request HTTP vào controller, span ChatClient, span gọi Gemini lần một (model quyết định gọi tool), span thực thi `createIncident` kèm truy vấn JDBC, span gọi Gemini lần hai (model đọc kết quả tool), span `updateDeliveryStatus`, và lượt Gemini chốt câu trả lời. Nhìn cây này đọc được ngay tầng nào chậm, lượt nào tốn token, tool nào bị gọi lặp.

Về chi phí, so sánh hai chiến lược cấp tri thức cho model: nhét toàn bộ quy chế vào prompt mỗi lượt tốn cỡ vài nghìn token đầu vào bất kể câu hỏi, và phình tuyến tính khi quy chế dày lên; RAG chỉ tốn 4 chunk (cỡ vài trăm token) đúng phần liên quan, chi phí gần như phẳng theo độ dày tài liệu. Tương tự, agent dùng tool đọc đúng một đơn hàng từ cơ sở dữ liệu thay vì đổ cả bảng vào prompt. Cấu hình hàng đợi export (max-queue-size 2048, schedule-delay 5000, max-export-batch-size 512, export-timeout 30000) theo nguyên tắc quan sát không được chặn nghiệp vụ: hàng đợi đầy thì bỏ span, request của khách vẫn đi tiếp.

Về chốt chặn vòng lặp tool calling: kết quả kiểm tra bằng javap trên jar `spring-ai-model-1.1.8` cho thấy `ToolCallingChatOptions` của phiên bản này chỉ có `internalToolExecutionEnabled`, hoàn toàn không có cấu hình dạng `maxIterations`; quét chuỗi trong toàn bộ class của `spring-ai-model` và `spring-ai-client-chat` 1.1.8 cũng không có ký hiệu nào chứa "maxIterations". Vì vậy dự án chốt chặn ở tầng system prompt ("không gọi công cụ quá 6 lượt trong một phiên") và thiết kế tool không bao giờ trả lỗi dạng ném exception để không kích thích model gọi lại vô hạn; các phiên bản Spring AI mới hơn đã bổ sung cấu hình cứng cho giới hạn này và khi nâng cấp chỉ cần chuyển con số 6 từ prompt xuống options.

## 6. Lựa chọn kỹ thuật thay thế có chủ đích

Đặc tả gốc của hệ thống nêu embedding `text-embedding-3-small` 1536 chiều của OpenAI. Dự án thay bằng `gemini-embedding-001` ép xuống 768 chiều với hai lý do:

- Hạ tầng khóa API: máy triển khai đã có sẵn `GEMINI_API_KEY` dùng chung cho model chat `gemini-3.7-flash`, dùng một nhà cung cấp cho cả chat lẫn embedding giảm một secret phải quản lý.
- Giới hạn chỉ mục: pgvector chỉ đánh index HNSW cho vector tối đa 2000 chiều ở kiểu `vector`; 768 chiều nằm an toàn dưới ngưỡng, truy vấn nhanh mà chất lượng truy hồi trên tập tài liệu một quy chế không suy giảm đo được.

Kiến trúc không đổi ở bất kỳ tầng nào: `VectorStore`, `QuestionAnswerAdvisor` và pipeline nạp tài liệu làm việc qua abstraction của Spring AI, đổi nhà cung cấp embedding chỉ là đổi cấu hình YAML và số chiều khai báo cho pgvector.

## 7. Hạn chế và hướng phát triển

- Chat memory nằm trong bộ nhớ process, khởi động lại là mất phiên hội thoại; bước tiếp theo là chuyển sang `JdbcChatMemoryRepository` để phiên sống qua các lần deploy.
- Giới hạn vòng lặp tool calling mới ở tầng prompt như phân tích ở chuyên đề 4; nâng cấp Spring AI sẽ chuyển thành cấu hình cứng.
- `SafeSqlValidator` chặn theo từ khóa, đủ cho hai bảng nội bộ nhưng chưa phân tích cú pháp SQL thực thụ; hướng nâng cấp là dùng parser (JSqlParser) hoặc chạy truy vấn bằng một role Postgres chỉ có quyền SELECT trên đúng hai bảng.
- Nạp tài liệu mới phải xóa bảng `vector_store` để chạy lại; có thể bổ sung phiên bản hóa tài liệu và nạp tăng dần theo hash nội dung.
- MCP server hiện expose tool cho một client nội bộ; khi mở cho client ngoài cần thêm tầng xác thực và giới hạn tần suất gọi.
