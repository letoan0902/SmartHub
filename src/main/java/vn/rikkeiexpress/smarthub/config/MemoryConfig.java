package vn.rikkeiexpress.smarthub.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemoryConfig {

    @Bean
    ChatMemory chatMemory() {
        // Cua so 20 thong diep gan nhat cho moi conversationId
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }
}
