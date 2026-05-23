package com.agento.bot;

import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class CodexRunner {

    private final BotProperties properties;
    private final ProcessRunner processRunner;
    private final CodexSettingsService settingsService;

    public CodexRunner(BotProperties properties, ProcessRunner processRunner, CodexSettingsService settingsService) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.settingsService = settingsService;
    }

    public String runCodex(String userPrompt) {
        File workdir = new File(properties.codex().workdir());
        if (!workdir.exists() || !workdir.isDirectory()) {
            return "CODEX_WORKDIR does not exist or is not a directory: " + workdir.getAbsolutePath();
        }

        List<String> command = buildCodexCommand(userPrompt);
        CommandResult result = processRunner.run(
                command,
                workdir,
                Duration.ofSeconds(properties.codex().timeoutSeconds())
        );

        if (result.timedOut()) {
            return limitOutput("Codex stopped by timeout after " + properties.codex().timeoutSeconds() + " seconds.\n\nOutput before stop:\n" + result.output());
        }

        return limitOutput("Codex finished. Exit code: " + result.exitCode() + "\n\n" + cleanOutput(result.output()));
    }

    private List<String> buildCodexCommand(String userPrompt) {
        CodexSettings settings = settingsService.current();
        List<String> command = new ArrayList<>();
        command.add(properties.codex().command());
        command.add("exec");
        command.add("--color");
        command.add("never");
        command.add("--cd");
        command.add(properties.codex().workdir());

        if (isNotBlank(settings.model())) {
            command.add("--model");
            command.add(settings.model());
        }

        if (isNotBlank(settings.reasoningEffort())) {
            command.add("--config");
            command.add("model_reasoning_effort=\"" + settings.reasoningEffort() + "\"");
        }

        if (properties.codex().skipGitRepoCheck()) {
            command.add("--skip-git-repo-check");
        }

        if (settings.usesDangerousBypass()) {
            command.add("--dangerously-bypass-approvals-and-sandbox");
        } else {
            command.add("--sandbox");
            command.add(settings.sandboxMode());
            command.add("--ask-for-approval");
            command.add(settings.approvalPolicy());
        }

        command.add(buildPrompt(userPrompt));
        return command;
    }

    private String buildPrompt(String userPrompt) {
        return """
                %s

                Codex working directory is already selected: %s.
                The Telegram bot service is running as a jar on a VPS from the home directory of the agento user.
                Work in the current directory and stay within the permissions of the agento user.
                In your final answer, provide a short report: what you checked, what you changed, which commands you ran, and what should be done next.

                User task from Telegram:
                %s
                """.formatted(properties.codex().systemPrompt(), properties.codex().workdir(), userPrompt);
    }

    private String cleanOutput(String output) {
        if (output == null || output.isBlank()) {
            return "Codex did not return any text output.";
        }
        return output.strip();
    }

    private String limitOutput(String output) {
        int maxChars = Math.max(properties.codex().maxOutputChars(), 1_000);
        if (output.length() <= maxChars) {
            return output;
        }
        int omitted = output.length() - maxChars;
        return output.substring(0, maxChars).stripTrailing()
                + "\n\n... output truncated, omitted characters: " + omitted;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
