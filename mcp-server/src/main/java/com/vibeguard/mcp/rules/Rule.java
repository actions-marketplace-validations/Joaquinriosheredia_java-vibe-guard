package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;

import java.util.List;

public interface Rule {
    String id();
    String description();
    List<Issue> analyze(FileContent file);
}
