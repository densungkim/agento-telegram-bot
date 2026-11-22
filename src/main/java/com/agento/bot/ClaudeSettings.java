package com.agento.bot;

import java.util.List;

public record ClaudeSettings(
        String model,
        String effort,
        String accessMode
) {
    public static final String DEFAULT = "default";
    public static final String READ_ONLY = "read-only";
    public static final String WORKSPACE = "workspace";
    public static final String FULL_ACCESS = "full-access";
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    public static final List<String> ACCESS_MODES = List.of(READ_ONLY, WORKSPACE, FULL_ACCESS);

    public boolean usesDefaultModel() {
        return DEFAULT.equals(model);
    }

    public boolean usesDefaultEffort() {
        return DEFAULT.equals(effort);
    }

    /** Value for {@code claude --permission-mode}. */
    public String permissionMode() {
        return switch (accessMode) {
            case READ_ONLY -> "default";
            case WORKSPACE -> "acceptEdits";
            case FULL_ACCESS -> "bypassPermissions";
            default -> throw new IllegalStateException("Unknown access mode: " + accessMode);
        };
    }

    /** Workspace mode runs shell commands inside the Claude Code Bash sandbox. */
    public boolean usesSandbox() {
        return WORKSPACE.equals(accessMode);
    }
}
