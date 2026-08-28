package vn.rikkeiexpress.smarthub.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
public class RegulationIngestionConfig {

    private static final Logger log = LoggerFactory.getLogger(RegulationIngestionConfig.class);

    private static final String SOURCE_FILE = "quy-che-van-chuyen-rikkeiexpress.md";

    @Bean
    ApplicationRunner regulationIngestionRunner(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                Long existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
                if (existing != null && existing > 0) {
                    log.info("vector_store da co {} ban ghi, bo qua buoc nap quy che", existing);
                    return;
                }

                MarkdownDocumentReaderConfig readerConfig = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeBlockquote(true)
                        .withIncludeCodeBlock(true)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(
                        new ClassPathResource("docs/" + SOURCE_FILE), readerConfig);
                List<Document> rawDocuments = reader.get();

                TokenTextSplitter splitter = TokenTextSplitter.builder()
                        .withChunkSize(512)
                        .withMinChunkSizeChars(350)
                        .withMinChunkLengthToEmbed(5)
                        .withMaxNumChunks(10000)
                        .withKeepSeparator(true)
                        .build();
                List<Document> chunks = splitter.apply(rawDocuments);
                chunks.forEach(doc -> doc.getMetadata().put("source_file", SOURCE_FILE));

                vectorStore.add(chunks);
                log.info("Da nap {} chunk quy che van chuyen vao vector_store", chunks.size());
            } catch (Exception e) {
                // Loi nap tai lieu (mat mang, het quota embedding...) khong duoc chan app khoi dong
                log.error("Nap quy che vao vector store that bai, ung dung van tiep tuc chay", e);
            }
        };
    }
}
