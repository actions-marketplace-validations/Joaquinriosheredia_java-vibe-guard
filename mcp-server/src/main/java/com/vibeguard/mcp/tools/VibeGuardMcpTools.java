package com.vibeguard.mcp.tools;

import com.vibeguard.mcp.dto.AnalysisResult;
import com.vibeguard.mcp.engine.VibeGuardEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class VibeGuardMcpTools {

    private static final Logger log = LoggerFactory.getLogger(VibeGuardMcpTools.class);

    private final VibeGuardEngine engine;

    public VibeGuardMcpTools(VibeGuardEngine engine) {
        this.engine = engine;
    }

    @Tool(description = "Analyze a Java/Spring Boot project directory for vibe-coding anti-patterns. " +
        "Returns a structured list of issues with severity, file path, line number, and explanation. " +
        "Detects patterns such as blocking calls inside @Transactional methods.")
    public AnalysisResult analyzeProject(
        @ToolParam(description = "Absolute path to the Java/Spring Boot project directory to analyze")
        String projectPath
    ) {
        log.info("analyzeProject called with path: {}", projectPath);
        AnalysisResult result = engine.scan(projectPath);
        log.info("analyzeProject completed: {} issues found in {} files", result.totalIssues(), result.totalFiles());
        return result;
    }
}
