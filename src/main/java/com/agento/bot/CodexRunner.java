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
            return "CODEX_WORKDIR не существует или не является папкой: " + workdir.getAbsolutePath();
        }

        List<String> command = buildCodexCommand(userPrompt);
        CommandResult result = processRunner.run(
                command,
                workdir,
                Duration.ofSeconds(properties.codex().timeoutSeconds())
        );

        if (result.timedOut()) {
            return limitOutput("Codex остановлен по timeout: " + properties.codex().timeoutSeconds() + " секунд.\n\nВывод до остановки:\n" + result.output());
        }

        return limitOutput("Codex завершил работу. Exit code: " + result.exitCode() + "\n\n" + cleanOutput(result.output()));
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

                Рабочая папка Codex уже выбрана: %s.
                Сервис Telegram-бота запущен как jar на VPS из домашней папки пользователя agento.
                Выполняй задачу в текущей папке и в пределах прав пользователя agento.
                В финальном ответе дай короткий отчет: что проверил, что изменил, какие команды запускал, что делать дальше.

                Задача пользователя из Telegram:
                %s
                """.formatted(properties.codex().systemPrompt(), properties.codex().workdir(), userPrompt);
    }

    private String cleanOutput(String output) {
        if (output == null || output.isBlank()) {
            return "Codex не вернул текстовый ответ.";
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
                + "\n\n... вывод обрезан, пропущено символов: " + omitted;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
