package com.agento.bot;

import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class ClaudeRunner {

    /** Workspace mode: shell commands run in the Bash sandbox (writes only in the workdir, no network). */
    static final String SANDBOX_SETTINGS = """
            {"sandbox":{"enabled":true,"autoAllowBashIfSandboxed":true,\
            "allowUnsandboxedCommands":false,"failIfUnavailable":true}}""";

    /**
     * Inherited variables removed from the {@code claude} process: an API key always wins over the subscription
     * token in {@code -p} mode, the effort variable wins over {@code --effort}, and the bot's Telegram token must
     * not be visible to the commands Claude runs.
     */
    static final Set<String> UNSET_ENVIRONMENT = Set.of(
            "ANTHROPIC_API_KEY", "CLAUDE_CODE_EFFORT_LEVEL", "TELEGRAM_BOT_TOKEN");

    /**
     * Auto memory is shared by all sessions of a project; the bot's {@code /new} must start from a clean slate, so
     * Claude Code neither writes nor loads it.
     */
    static final Map<String, String> ENVIRONMENT = Map.of("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1");

    private final BotProperties properties;
    private final ProcessRunner processRunner;
    private final ClaudeSessionStore sessions;

    public ClaudeRunner(BotProperties properties, ProcessRunner processRunner, ClaudeSessionStore sessions) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.sessions = sessions;
    }

    public String run(String userPrompt, ClaudeSettings settings, Consumer<String> progress) {
        File workdir = new File(properties.claude().workdir());
        if (!workdir.exists() || !workdir.isDirectory()) {
            return "Рабочий каталог Claude Code не найден: " + workdir.getAbsolutePath();
        }
        ClaudeSessionStore.Session session;
        try {
            session = sessions.sessionForRun();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return e.getMessage();
        }

        ClaudeEventStream events = new ClaudeEventStream(sessions, session.id(), progress);
        CommandResult run = processRunner.run(
                buildCommand(settings, session.id(), session.resume()),
                workdir,
                Duration.ofSeconds(properties.claude().timeoutSeconds()),
                userPrompt,
                ENVIRONMENT,
                UNSET_ENVIRONMENT,
                events
        );
        ClaudeEventStream.Result result = events.result();

        if (run.timedOut()) {
            return limitOutput("Превышено время ожидания Claude Code: " + properties.claude().timeoutSeconds()
                    + " с.\n\n" + cleanOutput(run.output()));
        }
        if (result == null) {
            String resumeHint = session.resume() ? "\nСессия сохранена. Если она больше недоступна, используйте /new." : "";
            return limitOutput("Claude Code завершился без итогового результата. Код завершения: " + run.exitCode()
                    + resumeHint + "\n\n" + cleanOutput(run.output()));
        }
        if (result.error()) {
            String details = result.errors().isEmpty() ? result.text() : String.join("\n", result.errors());
            String missingHint = details.contains("No conversation found")
                    ? "\n\nСессия больше недоступна (Claude Code удаляет старые сессии, по умолчанию через 30 дней)."
                    + " Используйте /new." : "";
            return limitOutput("Ошибка Claude Code (" + result.subtype() + "): "
                    + (details.isBlank() ? "без описания" : details.strip()) + missingHint
                    + withDiagnostics(run.output()));
        }
        if (!events.started()) {
            return "Claude Code не подтвердил ID сессии. Результат не будет автоматически повторён.\n\n"
                    + (result.text().isBlank() ? "" : result.text()) + withDiagnostics(run.output());
        }
        String answer = result.text().isBlank() ? "Claude Code не вернул текст ответа." : result.text();
        return answer + deniedNote(result.deniedTools(), settings);
    }

    /** {@code /new}: Claude Code has no per-session delete command, so the store forgets the id and deletes the file. */
    public String newSession() {
        return sessions.reset();
    }

    List<String> buildCommand(ClaudeSettings settings, String sessionId, boolean resume) {
        List<String> command = new ArrayList<>(List.of(
                properties.claude().command(), "-p",
                "--output-format", "stream-json",
                "--verbose"));

        if (!settings.usesDefaultModel()) {
            command.add("--model");
            command.add(settings.model());
        }
        if (!settings.usesDefaultEffort()) {
            command.add("--effort");
            command.add(settings.effort());
        }

        command.add("--permission-mode");
        command.add(settings.permissionMode());
        // Anything that would need an interactive approval is denied, and Claude is told not to retry it.
        command.add("--permission-prompts");
        command.add("none");
        if (settings.usesSandbox()) {
            command.add("--settings");
            command.add(SANDBOX_SETTINGS);
        }

        String systemPrompt = properties.claude().systemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            command.add("--append-system-prompt");
            command.add(systemPrompt);
        }

        command.add(resume ? "--resume" : "--session-id");
        command.add(ClaudeSessionStore.checkedId(sessionId));
        // The user prompt is written to stdin.
        return command;
    }

    private String deniedNote(List<String> deniedTools, ClaudeSettings settings) {
        if (deniedTools.isEmpty()) {
            return "";
        }
        return "\n\nНе разрешено в режиме доступа «" + settings.accessMode() + "»: "
                + String.join(", ", deniedTools.stream().distinct().toList())
                + " (" + deniedTools.size() + "). Смените режим через /mode, если это нужно.";
    }

    private String withDiagnostics(String output) {
        return output == null || output.isBlank() ? "" : "\n\nДиагностика:\n" + limitOutput(output.strip());
    }

    private String cleanOutput(String output) {
        if (output == null || output.isBlank()) {
            return "Claude Code не вывел диагностики.";
        }
        return output.strip();
    }

    private String limitOutput(String output) {
        int maxChars = Math.max(properties.claude().maxOutputChars(), 1_000);
        if (output.length() <= maxChars) {
            return output;
        }
        int omitted = output.length() - maxChars;
        return output.substring(0, maxChars).stripTrailing()
                + "\n\n… диагностика сокращена; пропущено символов: " + omitted;
    }
}
