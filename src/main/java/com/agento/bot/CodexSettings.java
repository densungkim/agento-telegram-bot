package com.agento.bot;

public record CodexSettings(
        String model,
        String reasoningEffort,
        String accessMode,
        String approvalPolicy
) {
    public String sandboxMode() {
        return switch (accessMode) {
            case "read-only" -> "read-only";
            case "workspace" -> "workspace-write";
            case "full-access" -> "danger-full-access";
            default -> "danger-full-access";
        };
    }

    public boolean usesDangerousBypass() {
        return "bypass".equals(accessMode);
    }
}
