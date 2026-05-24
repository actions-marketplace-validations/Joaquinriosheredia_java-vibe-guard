package com.vibeguard.mcp.config;

import com.vibeguard.mcp.tools.VibeGuardMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VibeGuardMcpConfig {

    @Bean
    public ToolCallbackProvider vibeGuardToolCallbackProvider(VibeGuardMcpTools tools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
    }
}
