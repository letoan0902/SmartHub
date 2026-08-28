package vn.rikkeiexpress.smarthub.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("mcp")
public class McpServerConfig {

    // Chi profile mcp moi expose bo tool nay ra stdio; profile mac dinh va analytics
    // khong dang ky ToolCallbackProvider nay nen khong lan sang ChatClient nghiep vu
    @Bean
    ToolCallbackProvider analyticsTools(AnalyticsMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
