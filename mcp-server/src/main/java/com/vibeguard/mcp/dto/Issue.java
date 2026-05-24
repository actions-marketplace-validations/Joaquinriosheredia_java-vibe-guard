package com.vibeguard.mcp.dto;

public record Issue(
    String ruleId,
    String severity,
    String file,
    int line,
    String message
) {}
