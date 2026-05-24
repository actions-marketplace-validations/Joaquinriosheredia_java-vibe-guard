package com.vibeguard.mcp.dto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record AnalysisResult(
    String projectPath,
    int totalFiles,
    int totalIssues,
    List<Issue> issues,
    Map<String, Long> issuesBySeverity
) {
    public static AnalysisResult of(String projectPath, int totalFiles, List<Issue> issues) {
        Map<String, Long> bySeverity = issues.stream()
            .collect(Collectors.groupingBy(Issue::severity, Collectors.counting()));
        return new AnalysisResult(projectPath, totalFiles, issues.size(), issues, bySeverity);
    }
}
