# SmartHub - Tra cứu quy chế vận chuyển bằng AI

SmartHub là ứng dụng Spring Boot giải bài toán quá tải tra cứu quy chế của RikkeiExpress: thay vì khách hàng và nhân viên bưu cục lật thủ công tập tài liệu PDF, hệ thống nạp toàn bộ quy chế vào cơ sở dữ liệu vector và cung cấp một API hỏi đáp bằng ngôn ngữ tự nhiên theo kỹ thuật RAG. Mọi câu trả lời đều bám nội dung tài liệu, trích dẫn số Điều cụ thể kèm danh sách nguồn, và tự từ chối khi thông tin không có trong quy chế.

| Năng lực | Chi tiết |
|---|---|
| Nạp tài liệu | Đọc PDF quy chế chính thức bằng TikaDocumentReader, cắt chunk 512 token, embedding gemini-embedding-001 và lưu vào pgvector trên Supabase |
| Hỏi đáp RAG | GET /api/v1/rag/ask?question=... với QuestionAnswerAdvisor (topK 4, ngưỡng tương đồng 0.5), trả answer kèm sourceDocuments |
| Chống ảo tưởng | System prompt bắt trích dẫn Điều, câu hỏi ngoài phạm vi trả đúng câu từ chối chuẩn và mời gọi tổng đài 1900 2929 |
| MCP server | Profile mcp expose 3 tool đối soát dữ liệu vận hành (truy vấn SELECT an toàn, thống kê bưu cục, xuất báo cáo Markdown) qua stdio JSON-RPC |

## 1. Chuẩn bị

- Tạo project Supabase, vào SQL Editor chạy `create extension if not exists vector;` để bật pgvector. Bảng `vector_store` được ứng dụng tự tạo ở lần chạy đầu.
- Chép `.env.example` thành `.env` rồi điền giá trị thật: chuỗi kết nối Session pooler cổng 5432 kèm `sslmode=require`, user, mật khẩu, khóa Gemini. Nạp các biến này vào môi trường trước khi chạy (export tay hoặc tiện ích dotenv của shell).
- Yêu cầu máy: JDK 17 trở lên, không cần cài Gradle vì đã có wrapper.

## 2. Chạy và gọi thử

```
./gradlew bootRun
```

Lần chạy đầu, ứng dụng đọc tài liệu quy chế chính thức `docs/TaiLieu_QuyCheVanChuyen_RikkeiExpress.pdf` (mã QC-RKE-2026-01) qua TikaDocumentReader, cắt chunk và nạp vào `vector_store`; các lần sau phát hiện dữ liệu đã có thì bỏ qua. Nếu trước đó đã nạp một bản tài liệu khác, chạy `TRUNCATE TABLE vector_store;` trên database rồi khởi động lại để nạp bản mới. Gọi thử:

```
curl -G "http://localhost:8080/api/v1/rag/ask" --data-urlencode "question=Đơn hàng hỏng có khai giá được bồi thường thế nào"
```

Câu trả lời phải trích dẫn Điều 8, kèm mảng `sourceDocuments` liệt kê các đoạn quy chế đã dùng làm căn cứ. Vài câu kiểm chứng khác nên thử:

```
curl -G "http://localhost:8080/api/v1/rag/ask" --data-urlencode "question=Gửi hàng 0,5kg tuyến liên tỉnh khác miền cước bao nhiêu"
curl -G "http://localhost:8080/api/v1/rag/ask" --data-urlencode "question=Đơn hàng giao trễ thì được bồi thường gì"
curl -G "http://localhost:8080/api/v1/rag/ask" --data-urlencode "question=Phí gửi một chiếc xe máy sang Mỹ là bao nhiêu"
```

Câu cuối nằm ngoài phạm vi tài liệu nên hệ thống phải trả đúng câu "Tôi không tìm thấy thông tin trong tài liệu quy chế." thay vì bịa số liệu.

### Chạy MCP server đối soát dữ liệu

Profile `mcp` không mở cổng web mà giao tiếp JSON-RPC qua stdin/stdout, dùng để cắm vào một MCP client (Claude Desktop hoặc một ứng dụng Spring AI khác). Đóng gói rồi khai báo trong cấu hình client:

```
./gradlew bootJar
```

```json
{
  "mcpServers": {
    "smarthub-analytics": {
      "command": "java",
      "args": ["-jar", "duong-dan-toi/build/libs/smarthub-0.0.1-SNAPSHOT.jar", "--spring.profiles.active=mcp"]
    }
  }
}
```

Client kết nối xong tự khám phá 3 tool qua `tools/list`, không cần biết trước hợp đồng: `runSafeQuery` (chỉ nhận SELECT, tự chặn từ khóa ghi xóa và ép LIMIT 100), `getHubPerformance` và `exportHubReportMarkdown` (nhận mã bưu cục, truy vấn tham số hóa). Hai lưu ý khi chạy:

- Các biến môi trường trong `.env.example` phải được cấp cho process con (mục `env` trong cấu hình client hoặc export sẵn ở shell cha).
- Stdout là kênh giao thức nên banner Spring đã tắt trong `application-mcp.yml` và toàn bộ log được `logback-spring.xml` đẩy sang stderr; nếu thêm code mới, tuyệt đối không `System.out.println`.

Hai tool thống kê đọc hai bảng `deliveries` và `incidents` có sẵn trên database; nếu bảng chưa tồn tại, tool trả về thông báo lỗi dạng chuỗi cho model diễn giải chứ không làm sập phiên.

## 3. Minh chứng

- Trả lời đúng chính sách kèm trích dẫn Điều và danh sách nguồn: (chen anh chup man hinh minh chung)
- Từ chối chuẩn với câu hỏi ngoài phạm vi tài liệu: (chen anh chup man hinh minh chung)

## 4. Cấu trúc mã nguồn

```
SmartHub
|-- build.gradle
|-- settings.gradle
|-- .env.example
|-- README.md
|-- BAO-CAO-KY-THUAT.md
`-- src/main
    |-- java/vn/rikkeiexpress/smarthub
    |   |-- SmartHubApplication.java
    |   |-- mcp
    |   |   |-- AnalyticsMcpTools.java           # 3 tool doi soat, loi tra ve chuoi, khong throw
    |   |   |-- SafeSqlValidator.java            # chi cho SELECT mot statement, ep LIMIT 100
    |   |   `-- McpServerConfig.java             # dang ky bo tool khi chay profile mcp
    |   `-- rag
    |       |-- RegulationIngestionConfig.java   # nap quy che vao pgvector, chay mot lan
    |       |-- RagService.java                  # ChatClient + QuestionAnswerAdvisor + danh sach nguon
    |       `-- RagController.java               # GET /api/v1/rag/ask
    `-- resources
        |-- application.yml                      # datasource Supabase + Gemini + pgvector
        |-- application-mcp.yml                  # profile mcp: tat web, tat banner, bat MCP server
        |-- logback-spring.xml                   # don het log sang stderr, giu stdout sach
        `-- docs/TaiLieu_QuyCheVanChuyen_RikkeiExpress.pdf
```

## 5. Ghi chú an toàn

- Mọi secret (chuỗi kết nối, mật khẩu, khóa API) đều đọc từ biến môi trường; file `.env` nằm trong `.gitignore` nên không bao giờ vào lịch sử mã nguồn.
- Pipeline nạp tài liệu bọc try-catch: lỗi mạng hay hết hạn mức embedding không làm ứng dụng sập lúc khởi động.
- API kiểm tra câu hỏi rỗng trả 400, lỗi hạ tầng trả 503 với thông điệp thân thiện, không lộ stacktrace ra ngoài.
