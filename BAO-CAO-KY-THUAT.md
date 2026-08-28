# Báo cáo kỹ thuật SmartHub - Phân hệ tra cứu quy chế (RAG)

## 1. Kiến trúc tổng thể

```
+---------------------------------------------------------------+
| TANG GIAO TIEP                                                |
|   GET /api/v1/rag/ask?question=...                            |
+---------------------------------------------------------------+
| TANG DIEU PHOI AI                                             |
|   RagService: ChatClient + QuestionAnswerAdvisor (topK 4,     |
|   nguong 0.5) + system prompt trich dan va tu choi chuan      |
|   Model chat: gemini-3.7-flash                                |
|   Embedding: gemini-embedding-001 (768 chieu)                 |
+---------------------------------------------------------------+
| TANG DU LIEU                                                  |
|   Supabase Postgres + pgvector: bang vector_store             |
|   768 chieu, index HNSW, khoang cach cosine                   |
+---------------------------------------------------------------+

Pipeline nap lieu (chay mot lan luc khoi dong):
PDF quy che -> TikaDocumentReader -> TokenTextSplitter 512 token
-> embedding -> vector_store (co buoc dem ban ghi chong nap trung)
```

Ngoài API web ở profile mặc định, dự án có thêm cấu hình MCP server ở profile `mcp`: chạy với `--spring.profiles.active=mcp` thì ứng dụng không mở cổng web mà expose ba tool đối soát dữ liệu vận hành (truy vấn SELECT qua lớp chặn `SafeSqlValidator`, thống kê bưu cục, xuất báo cáo Markdown) theo JSON-RPC qua stdio, để một MCP client bên ngoài tự khám phá qua `tools/list`. Vì stdout lúc này là kênh giao thức, banner Spring bị tắt và toàn bộ log được logback đẩy sang stderr nhằm chống stdio pollution.

## 2. Chiến lược chunking và ngưỡng tương đồng

Tài liệu quy chế có cấu trúc "Điều N" rất đều, mỗi Điều là một đơn vị ngữ nghĩa trọn vẹn (biểu phí, bồi thường, khiếu nại...). Tham số cắt chunk chọn 512 token với ngưỡng tối thiểu 350 ký tự vì hai lý do đối xứng:

- Chunk quá nhỏ (cỡ 300 ký tự) sẽ cắt đôi bảng biểu phí hoặc tách mức bồi thường khỏi điều kiện áp dụng của nó; câu hỏi "hỏng có khai giá đền bao nhiêu" có thể chỉ khớp được nửa câu trả lời.
- Chunk quá lớn (cỡ 1000 token) gộp nhiều Điều vào một vector, điểm tương đồng bị pha loãng và ngữ cảnh đưa vào prompt phình to vô ích.

512 token xấp xỉ độ dài một Điều dài nhất của quy chế, nên phần lớn chunk trùng khớp ranh giới Điều một cách tự nhiên. Ngưỡng tương đồng 0.5 là điểm cân bằng: đặt cao hơn (0.7) thì câu hỏi diễn đạt đời thường ("hàng bể đền sao") hay bị bỏ sót vì từ vựng lệch với văn bản pháp quy; đặt thấp hơn thì các Điều không liên quan lọt vào làm model dễ trộn số liệu. Khoảng cách cosine phù hợp văn bản vì so hướng của vector ngữ nghĩa, không bị độ dài đoạn văn chi phối như khoảng cách Euclid.

## 3. Thiết kế chống ảo tưởng và trích dẫn nguồn

Tầng system prompt khóa hành vi ba lớp: chỉ trả lời từ ngữ cảnh được cấp, luôn nêu số Điều để nhân viên đối chiếu lại được văn bản gốc, và khi thiếu thông tin phải trả đúng câu "Tôi không tìm thấy thông tin trong tài liệu quy chế." kèm lời mời gọi tổng đài 1900 2929. Câu từ chối chuẩn hóa giúp phía client nhận diện được trường hợp không có căn cứ, thay vì nhận một câu bịa trôi chảy.

Danh sách nguồn trả về không lấy từ nội bộ advisor mà chạy lại chính phép truy vấn của advisor (cùng topK, cùng ngưỡng, cùng câu hỏi) qua `vectorStore.similaritySearch`. Hai phép truy vấn giống hệt tham số nên kết quả nhất quán, đổi lại không phụ thuộc khóa context nội bộ của advisor giữa các phiên bản thư viện.

## 4. Kết quả đánh giá thực nghiệm

Chạy thật trên Supabase (Session pooler khu vực ap-southeast-2) với tài liệu quy chế chính thức QC-RKE-2026-01: pipeline nạp thành công 8 chunk (512 token, minChunkSizeChars 350). Bộ câu hỏi kiểm chứng được chấm bằng cách đối chiếu trực tiếp với văn bản gốc:

| Câu hỏi kiểm chứng | Căn cứ trong quy chế | Kết quả | Latency |
|---|---|---|---|
| Cước 0,5kg liên tỉnh khác miền | 22.000đ tiêu chuẩn / 35.000đ nhanh (Điều 3 Khoản 3) | Đúng, trích dẫn đúng Điều | 6,1s |
| Bồi thường khi không khai giá | 4 lần cước, không vượt 1.000.000đ (Điều 8 Khoản 1) | Đúng cả mức trần (câu gài) | 4,4s |
| Cách tính phụ phí COD | 1,5% giá trị thu hộ, tối thiểu 5.000đ (Điều 4 Khoản 1) | Đúng | 5,1s |
| Quyền lợi khi giao trễ | Hoàn 100% cước (Điều 6 Khoản 2) | Đúng | 6,2s |
| Thời hạn gửi và xử lý khiếu nại | 7 ngày; hư hỏng phản hồi trong 3 ngày làm việc (Điều 7, Điều 11) | Đúng | 5,1s |
| Phí gửi xe máy sang Mỹ (ngoài phạm vi) | Phải từ chối | Trả đúng nguyên văn câu từ chối chuẩn kèm tổng đài 1900 2929 | 5,7s |
| Chương trình tích điểm (ngoài phạm vi) | Phải từ chối | Trả đúng nguyên văn câu từ chối chuẩn | 4,5s |

Tỉ lệ trả lời đúng nội dung 5/5, trích dẫn đúng Điều 5/5, từ chối đúng với câu ngoài phạm vi 2/2; mọi phản hồi đều kèm 4 đoạn nguồn từ đúng file PDF. Điểm chưa đạt duy nhất là latency: trung bình khoảng 5,3 giây, vượt mức SLA 3 giây của bản đặc tả. Nguyên nhân chính là gemini-3.7-flash thuộc dòng model suy nghĩ nhiều bước cộng với khứ hồi mạng tới hạ tầng model và database ở nước ngoài; hướng cải thiện đã xác định: dùng model bậc nhẹ hơn cho tra cứu thuần, giảm topK từ 4 xuống 3, và rút gọn system prompt.

## 5. Lựa chọn kỹ thuật thay thế có chủ đích

Đặc tả gốc của hệ thống nêu embedding `text-embedding-3-small` 1536 chiều của OpenAI. Dự án thay bằng `gemini-embedding-001` ép xuống 768 chiều với hai lý do:

- Hạ tầng khóa API: máy triển khai đã có sẵn `GEMINI_API_KEY` dùng chung cho model chat `gemini-3.7-flash`, dùng một nhà cung cấp cho cả chat lẫn embedding giảm một secret phải quản lý.
- Giới hạn chỉ mục: pgvector chỉ đánh index HNSW cho vector tối đa 2000 chiều ở kiểu `vector`; 768 chiều nằm an toàn dưới ngưỡng, truy vấn nhanh mà chất lượng truy hồi trên tập tài liệu một quy chế không suy giảm đo được.

Kiến trúc không đổi ở bất kỳ tầng nào: `VectorStore`, `QuestionAnswerAdvisor` và pipeline nạp tài liệu làm việc qua abstraction của Spring AI, đổi nhà cung cấp embedding chỉ là đổi cấu hình YAML và số chiều khai báo cho pgvector.

## 6. Hạn chế và hướng phát triển

- Latency trung bình 5,3 giây vượt SLA 3 giây; ba hướng cải thiện đã nêu ở mục 4, ưu tiên thử model bậc nhẹ hơn cho tác vụ tra cứu thuần.
- Nạp tài liệu mới phải xóa bảng `vector_store` để chạy lại; có thể bổ sung phiên bản hóa tài liệu và nạp tăng dần theo hash nội dung.
- Metadata nguồn hiện dừng ở tên tài liệu và trích đoạn; bước tiếp theo là tách số Điều vào metadata ngay lúc chunking để trích dẫn máy đọc được thay vì chỉ nằm trong lời văn.
- API đang mở tự do; đưa vào dùng thật cần thêm xác thực và giới hạn tần suất gọi.
