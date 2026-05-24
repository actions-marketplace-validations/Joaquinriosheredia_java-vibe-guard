package com.vibeguard.mcp.dto;

import java.util.List;

public record FileContent(
    String path,
    String content,
    List<String> lines,
    int lineCount
) {
    public static FileContent of(String path, String content) {
        List<String> lines = content.lines().toList();
        return new FileContent(path, content, lines, lines.size());
    }
}
