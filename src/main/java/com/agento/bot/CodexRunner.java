package com.agento.bot;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Path lastMessageFile = null;
        try {
            lastMessageFile = Files.createTempFile("agento-codex-last-message-", ".txt");
            List<String> command = buildCodexCommand(userPrompt, lastMessageFile);
            CommandResult result = processRunner.run(
                    command,
                    workdir,
                    Duration.ofSeconds(properties.codex().timeoutSeconds())
            );

            String lastMessage = readLastMessage(lastMessageFile);

            if (result.timedOut()) {
                return limitOutput("Codex timed out after " + properties.codex().timeoutSeconds() + " seconds.\n\n" + firstNotBlank(lastMessage, result.output()));
            }

            if (result.exitCode() == 0 && isNotBlank(lastMessage)) {
                return limitOutput(lastMessage);
            }

            if (isNotBlank(lastMessage)) {
                return limitOutput(lastMessage + "\n\nCodex exit code: " + result.exitCode());
            }

            return limitOutput("Codex exit code: " + result.exitCode() + "\n\n" + cleanOutput(result.output()));
        } catch (IOException e) {
            return "Failed to prepare Codex output file: " + e.getMessage();
        } finally {
            deleteIfExists(lastMessageFile);
        }
    }

    private List<String> buildCodexCommand(String userPrompt, Path lastMessageFile) {
        CodexSettings settings = settingsService.current();
        List<String> command = new ArrayList<>();
        command.add(properties.codex().command());
        command.add("exec");
        command.add("--color");
        command.add("never");
        command.add("--output-last-message");
        command.add(lastMessageFile.toString());
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
        }

        command.add(buildPrompt(userPrompt));
        return command;
    }

    private String buildPrompt(String userPrompt) {
        return """
                %s

                User task from Telegram:
                %s
                """.formatted(properties.codex().systemPrompt(), userPrompt);
    }

    private String cleanOutput(String output) {
        if (output == null || output.isBlank()) {
            return "Codex did not return any text output.";
        }
        return output.strip();
    }

    private String readLastMessage(Path lastMessageFile) throws IOException {
        if (lastMessageFile == null || !Files.isRegularFile(lastMessageFile)) {
            return "";
        }
        return Files.readString(lastMessageFile).strip();
    }

    private String firstNotBlank(String first, String second) {
        return isNotBlank(first) ? first : cleanOutput(second);
    }

    private void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary output files should not block a Telegram response.
        }
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
