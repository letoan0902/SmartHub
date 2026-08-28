# SmartHub - Trung tâm vận hành logistics tích hợp AI

SmartHub là ứng dụng Spring Boot cho trung tâm vận hành của RikkeiExpress, gom bốn năng lực AI vào một codebase duy nhất và tách vai trò bằng ba profile chạy. Nhân viên tuyến đầu tra cứu quy chế bằng ngôn ngữ tự nhiên, báo sự cố bằng một câu chat, còn quản lý vận hành hỏi thẳng dữ liệu đối soát qua giao thức MCP; toàn bộ lượt gọi model được đẩy trace sang Langfuse để giám sát.

| Phân hệ | Năng lực | Endpoint / cách chạy |
|---|---|---|
| 1. RAG tra cứu quy chế | Trả lời theo quy chế vận chuyển, trích dẫn số Điều, kèm danh sách nguồn | GET /api/v1/rag/ask?question=... (profile mặc định) |
| 2. Agent xử lý sự cố | Bóc tách thực thể từ câu chat, tạo phiếu sự cố, cập nhật trạng thái đơn | POST /api/v1/operations/chat (profile mặc định) |
| 3. MCP đối soát dữ liệu | MCP server stdio expose 3 tool truy vấn an toàn; client spawn process và khám phá tool | POST /api/v1/analytics/chat (profile analytics, spawn profile mcp) |
| 4. LLMOps Langfuse | Trace toàn trình Client - ChatClient - LLM - Tool - DB qua OTLP | Bật cùng mọi profile web, xem trên Langfuse |

## 1. Chuẩn bị

- Tạo project Supabase, vào SQL Editor chạy `create extension if not exists vector;` để bật pgvector. Bảng `vector_store`, `deliveries`, `incidents` sẽ được ứng dụng tự tạo ở lần chạy đầu.
- Chép `.env.example` thành `.env` rồi điền giá trị thật: chuỗi kết nối pooler cổng 6543 kèm `sslmode=require`, user, mật khẩu, khóa Gemini. Nạp các biến này vào môi trường trước khi chạy (ví dụ dùng tiện ích dotenv của shell hoặc export tay).
- Langfuse là tùy chọn, chỉ cần cho phân hệ 4: dựng bản local theo docker compose của Langfuse tại `http://localhost:3000`, tạo project rồi lấy cặp khóa để điền `LANGFUSE_BASIC_AUTH`. Chưa cấu hình thì ba phân hệ còn lại vẫn chạy bình thường.

Yêu cầu máy: JDK 17 trở lên, không cần cài Gradle vì đã có wrapper.

## 2. Chạy từng phân hệ

### 2.1. Phân hệ 1 - RAG tra cứu quy chế

Chạy profile mặc định (cổng 8080):

```
./gradlew bootRun
```

Lần chạy đầu, ứng dụng đọc `docs/quy-che-van-chuyen-rikkeiexpress.md`, cắt chunk và nạp vào `vector_store`; các lần sau phát hiện dữ liệu đã có thì bỏ qua. Gọi thử:

```
curl -G "http://localhost:8080/api/v1/rag/ask" --data-urlencode "question=Đơn hàng hỏng có khai giá được bồi thường thế nào"
```

Câu trả lời phải trích dẫn Điều 8, kèm mảng `sourceDocuments` liệt kê các đoạn quy chế đã dùng làm căn cứ.

### 2.2. Phân hệ 2 - Agent xử lý sự cố

Vẫn trên profile mặc định:

```
curl -X POST "http://localhost:8080/api/v1/operations/chat" -H "Content-Type: application/json" -d "{\"message\": \"Đơn RK-2026-001 của tôi gửi tuần trước bị ướt sũng hỏng đồ ở kho Hà Nội, đề nghị kiểm tra\"}"
```

Chuỗi hành vi kỳ vọng: agent quy "ướt sũng hỏng đồ" về loại HỎNG_HÓC, "kho Hà Nội" về HN-01, gọi tool `createIncident` tạo phiếu sự cố trạng thái OPEN, rồi gọi tiếp `updateDeliveryStatus` chuyển đơn RK-2026-001 sang DAMAGED, cuối cùng trả lời xác nhận kèm số phiếu. Gửi lại `conversationId` nhận được để hỏi tiếp trong cùng phiên; câu thiếu thông tin (chẳng hạn không có mã vận đơn) sẽ bị hỏi lại đúng phần thiếu thay vì tạo phiếu bừa.

### 2.3. Phân hệ 3 - MCP đối soát dữ liệu

Build jar trước vì profile analytics sẽ spawn chính jar đó làm MCP server con:

```
./gradlew bootJar
./gradlew bootRun --args='--spring.profiles.active=analytics'
```

Khi khởi động, MCP client mở process `java -jar build/libs/smarthub-0.0.1-SNAPSHOT.jar --spring.profiles.active=mcp`, bắt tay qua stdio và khám phá 3 tool: `runSafeQuery`, `getHubPerformance`, `exportHubReportMarkdown`. Gọi thử:

```
curl -X POST "http://localhost:8080/api/v1/analytics/chat" -H "Content-Type: application/json" -d "{\"message\": \"Tổng hợp số đơn giao trễ của bưu cục HN-01\"}"
```

Muốn kiểm thử trực tiếp MCP server không qua client Spring, dùng MCP Inspector:

```
npx @modelcontextprotocol/inspector java -jar build/libs/smarthub-0.0.1-SNAPSHOT.jar --spring.profiles.active=mcp
```

Trong giao diện Inspector sẽ thấy server `smarthub-analytics` cùng danh sách tool và gọi thử được từng tool.

### 2.4. Phân hệ 4 - LLMOps với Langfuse

Dựng Langfuse local, tạo project và lấy public key, secret key. Mã hóa base64 chuỗi `publicKey:secretKey` rồi đặt vào biến môi trường:

```
LANGFUSE_BASIC_AUTH=<base64 cua publicKey:secretKey>
LANGFUSE_HOST=http://localhost:3000
```

Chạy lại ứng dụng rồi bắn vài request ở phân hệ 1 và 2. Vào Langfuse mở mục Traces sẽ thấy cây span đầy đủ: request HTTP, bước gọi Gemini, từng lượt thực thi tool và truy vấn cơ sở dữ liệu, kèm token sử dụng và độ trễ từng tầng.

## 3. Minh chứng

- Phân hệ 1 - RAG trả lời kèm trích dẫn Điều và nguồn: (chen anh chup man hinh minh chung)
- Phân hệ 2 - Agent tạo phiếu sự cố và chuyển trạng thái đơn: (chen anh chup man hinh minh chung)
- Phân hệ 3 - MCP client khám phá tool và trả lời số liệu đối soát: (chen anh chup man hinh minh chung)
- Phân hệ 4 - Cây trace trên Langfuse: (chen anh chup man hinh minh chung)

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
    |   |-- domain
    |   |   |-- Delivery.java            # don hang: tracking_code, hub_code, status, cod_amount
    |   |   `-- Incident.java            # phieu su co: loai su co, muc do, trang thai OPEN
    |   |-- repo
    |   |   |-- DeliveryRepository.java
    |   |   `-- IncidentRepository.java
    |   |-- rag
    |   |   |-- RegulationIngestionConfig.java   # nap quy che vao pgvector, chay mot lan
    |   |   |-- RagService.java                  # ChatClient + QuestionAnswerAdvisor + danh sach nguon
    |   |   `-- RagController.java               # GET /api/v1/rag/ask
    |   |-- agent
    |   |   |-- OperationsTools.java             # 2 tool: tao phieu su co, cap nhat trang thai don
    |   |   `-- OperationsChatController.java    # POST /api/v1/operations/chat, co chat memory
    |   |-- config
    |   |   `-- MemoryConfig.java                # MessageWindowChatMemory 20 thong diep
    |   |-- mcp
    |   |   |-- SafeSqlValidator.java            # chi cho SELECT, chan tu khoa nguy hiem, ep LIMIT
    |   |   |-- AnalyticsMcpTools.java           # 3 tool doi soat du lieu
    |   |   `-- McpServerConfig.java             # chi profile mcp moi expose tool ra stdio
    |   `-- analytics
    |       `-- AnalyticsChatController.java     # POST /api/v1/analytics/chat, dung tool tu MCP client
    `-- resources
        |-- application.yml              # profile mac dinh: web 8080, MCP server + client deu tat
        |-- application-mcp.yml          # MCP server stdio, khong web, khong export telemetry
        |-- application-analytics.yml    # bat MCP client, spawn jar voi profile mcp
        |-- logback-spring.xml           # toan bo log sang System.err
        |-- data.sql                     # seed 6 don hang mau
        `-- docs/quy-che-van-chuyen-rikkeiexpress.md
```

## 5. Ghi chú an toàn

- Mọi secret (chuỗi kết nối, mật khẩu, khóa API, khóa Langfuse) đều đọc từ biến môi trường; file `.env` nằm trong `.gitignore` nên không bao giờ vào lịch sử mã nguồn.
- SQL do model sinh ra phải đi qua `SafeSqlValidator`: chỉ chấp nhận một câu SELECT duy nhất, chặn theo từ nguyên các từ khóa DROP, DELETE, UPDATE, INSERT, ALTER, TRUNCATE, CREATE, GRANT, REVOKE, EXECUTE, và tự động nối LIMIT 100 nếu thiếu.
- Toàn bộ log đẩy qua System.err bằng `logback-spring.xml`, giữ stdout sạch tuyệt đối cho JSON-RPC khi chạy profile mcp (chống Stdio Pollution); banner Spring cũng tắt vì lý do tương tự.
- Tất cả tool của agent và MCP không ném exception: lỗi nghiệp vụ trả về dạng chuỗi thông báo để model diễn giải lại cho người dùng, vòng lặp tool calling không bao giờ sập giữa chừng.
- Hàng đợi export trace cấu hình theo hướng không chặn nghiệp vụ: đầy hàng đợi thì bỏ span chứ không giữ request.
