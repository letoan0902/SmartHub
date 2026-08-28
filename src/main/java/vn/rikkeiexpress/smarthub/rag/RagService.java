package vn.rikkeiexpress.smarthub.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private static final String SOURCE_FILE = "TaiLieu_QuyCheVanChuyen_RikkeiExpress.pdf";
    private static final int TOP_K = 4;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultSystem("""
                        Bạn là chuyên viên tra cứu quy chế vận chuyển của RikkeiExpress.
                        Bạn CHỈ được trả lời dựa trên ngữ cảnh tài liệu được cung cấp kèm câu hỏi.
                        Luôn trích dẫn số Điều cụ thể của quy chế trong câu trả lời (ví dụ: "theo Điều 8").
                        Nếu ngữ cảnh không có thông tin để trả lời, hãy trả lời đúng câu:
                        "Tôi không tìm thấy thông tin trong tài liệu quy chế."
                        rồi mời khách liên hệ hotline 1900 8888 để được hỗ trợ.
                        Tuyệt đối không tự bịa số liệu về phí, thời gian hay mức bồi thường.
                        """)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                                .topK(TOP_K)
                                .similarityThreshold(SIMILARITY_THRESHOLD)
                                .build())
                        .build())
                .build();
    }

    public record SourceDocument(String document, String snippet) {
    }

    public record RagAnswer(String answer, List<SourceDocument> sourceDocuments) {
    }

    public RagAnswer ask(String question) {
        String answer = chatClient.prompt()
                .user(question)
                .call()
                .content();

        // Advisor va danh sach nguon dung cung mot phep truy van (topK, threshold, query)
        // nen phan nguon tra ve luon nhat quan voi ngu canh model da doc
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .query(question)
                .build());

        List<SourceDocument> sources = documents == null ? List.of() : documents.stream()
                .map(doc -> {
                    Object sourceFile = doc.getMetadata().get("source_file");
                    String text = doc.getText() == null ? "" : doc.getText();
                    String snippet = text.length() > 160 ? text.substring(0, 160) : text;
                    return new SourceDocument(
                            sourceFile != null ? sourceFile.toString() : SOURCE_FILE,
                            snippet);
                })
                .toList();

        return new RagAnswer(answer, sources);
    }
}
