package com.agento.bot;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class CodexRunner {

    private final BotProperties properties;
    private final ProcessRunner processRunner;
    private final CodexSessionStore sessions;

    public CodexRunner(BotProperties properties, ProcessRunner processRunner, CodexSessionStore sessions) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.sessions = sessions;
    }

    public String runCodex(String userPrompt, CodexSettings settings, Consumer<String> progress) {
        String sessionId;
        try {
            sessionId = sessions.resumeId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return e.getMessage();
        }
        File workdir = new File(properties.codex().workdir());
        if (!workdir.exists() || !workdir.isDirectory()) {
            return "Рабочий каталог Codex не найден: " + workdir.getAbsolutePath();
        }

        Path lastMessageFile = null;
        try {
            CodexEventStream events = new CodexEventStream(sessions, sessionId, progress);
            lastMessageFile = Files.createTempFile("agento-codex-last-message-", ".txt");
            List<String> command = buildCodexCommand(lastMessageFile, settings, sessionId);
            CommandResult result = processRunner.run(
                    command,
                    workdir,
                    Duration.ofSeconds(properties.codex().timeoutSeconds()),
                    buildPrompt(userPrompt),
                    sessions.environment(),
                    events
            );

            String lastMessage = readLastMessage(lastMessageFile);

            if (result.timedOut()) {
                return "Превышено время ожидания Codex: " + properties.codex().timeoutSeconds()
                        + " с.\n\n" + responseWithDiagnostics(lastMessage, result.output());
            }

            if (!events.error().isBlank()) {
                return "Ошибка Codex: " + events.error() + "\n\n" + responseWithDiagnostics(lastMessage, result.output());
            }

            if (result.exitCode() == 0 && !events.started()) {
                return "Codex не подтвердил ID сессии. Результат не будет автоматически повторён.\n\n"
                        + responseWithDiagnostics(lastMessage, result.output());
            }

            if (result.exitCode() == 0 && isNotBlank(lastMessage)) {
                return lastMessage;
            }

            if (isNotBlank(lastMessage)) {
                return "Код завершения Codex: " + result.exitCode() + "\n\n"
                        + responseWithDiagnostics(lastMessage, result.output());
            }

            String resumeHint = sessionId == null ? "" : "\nСессия сохранена. Если она больше недоступна, используйте /new.";
            return limitOutput("Код завершения Codex: " + result.exitCode() + resumeHint + "\n\n" + cleanOutput(result.output()));
        } catch (IOException e) {
            return "Не удалось прочитать или подготовить ответ Codex: " + e.getMessage();
        } finally {
            deleteIfExists(lastMessageFile);
        }
    }

    public String newSession() {
        String id = sessions.beginReset();
        if (id != null) {
            CommandResult result = processRunner.run(
                    List.of(properties.codex().command(), "delete", CodexSessionStore.checkedId(id), "--force"),
                    new File(properties.codex().workdir()), Duration.ofSeconds(60), null,
                    sessions.environment(), null);
            // CLI 0.166.0 reports this exact UUID when a previous delete already completed.
            boolean alreadyDeleted = !result.timedOut() && result.exitCode() != 0
                    && result.output().contains("thread not found: " + id);
            if (result.timedOut() || (result.exitCode() != 0 && !alreadyDeleted)) {
                return limitOutput("Очистка сессии не завершена. ID сохранён; новые запросы заблокированы."
                        + " Повторите /new.\n\n" + cleanOutput(result.output()));
            }
        }
        sessions.finishReset(id);
        return "Предыдущий контекст удалён. Следующее сообщение начнёт новую сессию. Настройки сохранены.";
    }

    private List<String> buildCodexCommand(Path lastMessageFile, CodexSettings settings, String sessionId) {
        List<String> command = new ArrayList<>();
        command.add(properties.codex().command());
        command.add("exec");
        command.add("--json");
        command.add("--color");
        command.add("never");
        command.add("--output-last-message");
        command.add(lastMessageFile.toString());
        command.add("--cd");
        command.add(properties.codex().workdir());

        if (!settings.usesDefaultModel()) {
            command.add("--model");
            command.add(settings.model());
        }

        if (!settings.usesDefaultReasoning()) {
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

        if (sessionId != null) {
            command.add("resume");
            command.add(CodexSessionStore.checkedId(sessionId));
        }
        command.add("-");
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
            return "Codex не вернул текст ответа.";
        }
        return output.strip();
    }

    private String readLastMessage(Path lastMessageFile) throws IOException {
        if (lastMessageFile == null || !Files.isRegularFile(lastMessageFile)) {
            return "";
        }
        return Files.readString(lastMessageFile);
    }

    private String responseWithDiagnostics(String response, String diagnostics) {
        String details = limitOutput(cleanOutput(diagnostics));
        return isNotBlank(response) ? response + "\n\nДиагностика:\n" + details : details;
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
                + "\n\n… диагностика сокращена; пропущено символов: " + omitted;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
