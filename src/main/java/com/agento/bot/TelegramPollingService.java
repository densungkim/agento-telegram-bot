package com.agento.bot;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramClient telegramClient;
    private final CodexRunner codexRunner;
    private final ProcessRunner processRunner;
    private final BotProperties properties;
    private final CodexSettingsService settingsService;
    private final long startedAtEpochSeconds = Instant.now().getEpochSecond();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicReference<ActiveTask> activeTask = new AtomicReference<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(new AgentThreadFactory());

    private long offset = 0;

    public TelegramPollingService(
            TelegramClient telegramClient,
            CodexRunner codexRunner,
            ProcessRunner processRunner,
            BotProperties properties,
            CodexSettingsService settingsService
    ) {
        this.telegramClient = telegramClient;
        this.codexRunner = codexRunner;
        this.processRunner = processRunner;
        this.properties = properties;
        this.settingsService = settingsService;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        try {
            JsonNode response = telegramClient.getUpdates(offset);
            if (response == null || !response.path("ok").booleanValue(false)) {
                log.warn("Telegram getUpdates returned non-ok response: {}", response);
                return;
            }

            for (JsonNode update : response.path("result")) {
                handleUpdate(update);
            }
        } catch (Exception e) {
            log.warn("Polling error: {}", e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        processRunner.cancelActiveProcess();
        executorService.shutdownNow();
    }

    private void handleUpdate(JsonNode update) {
        long updateId = update.path("update_id").longValue();
        offset = updateId + 1;

        JsonNode message = update.path("message");
        if (message.asOptional().isEmpty()) {
            return;
        }

        long messageDate = message.path("date").longValue(0);
        if (messageDate > 0 && messageDate < startedAtEpochSeconds - 10) {
            return;
        }

        long chatId = message.path("chat").path("id").longValue();
        String text = message.path("text").asString("").strip();
        if (text.isBlank()) {
            return;
        }

        if (text.equals("/id")) {
            telegramClient.sendMessage(chatId, "Your chat_id: " + chatId);
            return;
        }

        if (chatId != properties.telegram().allowedChatId()) {
            telegramClient.sendMessage(chatId, "Access denied.");
            return;
        }

        if (text.equals("/start") || text.equals("/help")) {
            telegramClient.sendMainMenu(chatId, helpText());
            return;
        }

        if (text.equals("/ping")) {
            telegramClient.sendMessage(chatId, "pong");
            return;
        }

        if (text.equals("/status") || text.equals("/settings")) {
            telegramClient.sendMainMenu(chatId, statusText());
            return;
        }

        if (text.equals("/cancel")) {
            cancelTask(chatId);
            return;
        }

        if (handleSettingsCommand(chatId, text)) {
            return;
        }

        if (text.startsWith("/")) {
            telegramClient.sendMainMenu(chatId, "Unknown command. Use the menu or send a plain text task for Codex.");
            return;
        }

        runCodex(chatId, text);
    }

    private boolean handleSettingsCommand(long chatId, String text) {
        if (text.equals("/model")) {
            telegramClient.sendKeyboard(chatId, optionsText("Codex model", "/model", settingsService.allowedModels()), keyboard("/model", settingsService.allowedModels()));
            return true;
        }
        if (text.startsWith("/model ")) {
            return updateSetting(chatId, () -> settingsService.setModel(text.substring("/model ".length())), "Model updated");
        }

        if (text.equals("/reasoning")) {
            telegramClient.sendKeyboard(chatId, optionsText("Reasoning effort", "/reasoning", settingsService.allowedReasoningEfforts()), keyboard("/reasoning", settingsService.allowedReasoningEfforts()));
            return true;
        }
        if (text.startsWith("/reasoning ")) {
            return updateSetting(chatId, () -> settingsService.setReasoningEffort(text.substring("/reasoning ".length())), "Reasoning updated");
        }

        if (text.equals("/mode")) {
            telegramClient.sendKeyboard(chatId, optionsText("Codex access mode", "/mode", settingsService.allowedAccessModes()), keyboard("/mode", settingsService.allowedAccessModes()));
            return true;
        }
        if (text.startsWith("/mode ")) {
            return updateSetting(chatId, () -> settingsService.setAccessMode(text.substring("/mode ".length())), "Codex mode updated");
        }

        return false;
    }

    private boolean updateSetting(long chatId, SettingsUpdate update, String successPrefix) {
        try {
            CodexSettings settings = update.apply();
            telegramClient.sendMainMenu(chatId, successPrefix + ".\n\n" + settingsSummary(settings));
        } catch (IllegalArgumentException e) {
            telegramClient.sendMessage(chatId, e.getMessage());
        } catch (Exception e) {
            telegramClient.sendMessage(chatId, "Failed to save setting: " + e.getMessage());
        }
        return true;
    }

    private void runCodex(long chatId, String prompt) {
        runAndReply(chatId, "Starting Codex with current settings:\n" + settingsSummary(settingsService.current()), () -> codexRunner.runCodex(prompt));
    }

    private void runAndReply(long chatId, String startedMessage, Task task) {
        ActiveTask active = new ActiveTask();
        if (!activeTask.compareAndSet(null, active)) {
            telegramClient.sendMainMenu(chatId, "A task is already running. Use /status or /cancel.");
            return;
        }

        Future<?> future = executorService.submit(() -> {
            try {
                telegramClient.sendMessage(chatId, startedMessage);
                String result = task.run();
                telegramClient.sendMessage(chatId, result);
            } catch (Exception e) {
                telegramClient.sendMessage(chatId, "Error: " + e.getMessage());
            } finally {
                activeTask.compareAndSet(active, null);
            }
        });
        active.future = future;
    }

    private void cancelTask(long chatId) {
        ActiveTask active = activeTask.get();
        if (active == null) {
            telegramClient.sendMainMenu(chatId, "No active task.");
            return;
        }

        boolean processCancelled = processRunner.cancelActiveProcess();
        Future<?> future = active.future;
        if (future != null) {
            future.cancel(true);
        }
        activeTask.compareAndSet(active, null);

        telegramClient.sendMainMenu(chatId, processCancelled ? "Stopped the active process." : "Task cancelled.");
    }

    private String helpText() {
        return """
                Agento Bot is ready.

                You can send plain text, and it will be sent to Codex as a task.

                Commands:
                /id - show chat_id
                /ping - check that the bot is alive
                /status - current settings and busy state
                /cancel - stop the active task
                /model - choose model
                /reasoning - choose reasoning effort
                /mode - choose Codex access mode

                There are no /docker, /logs, or approval commands.
                Send "run docker ps" as plain text when you want Codex to execute it.
                """;
    }

    private String statusText() {
        return """
                Agento Bot status:
                user: %s
                workdir: %s
                codex command: %s
                settings file: %s
                busy: %s

                %s
                """.formatted(
                System.getProperty("user.name"),
                properties.codex().workdir(),
                properties.codex().command(),
                properties.codex().settingsFile(),
                activeTask.get() != null,
                settingsSummary(settingsService.current())
        );
    }

    private String settingsSummary(CodexSettings settings) {
        return """
                model: %s
                reasoning: %s
                mode: %s
                sandbox: %s
                """.formatted(
                settings.model(),
                settings.reasoningEffort(),
                settings.accessMode(),
                settings.usesDangerousBypass() ? "bypassed" : settings.sandboxMode()
        ).strip();
    }

    private String optionsText(String title, String command, List<String> options) {
        return title + ":\n" + String.join("\n", options.stream().map(option -> command + " " + option).toList());
    }

    private List<List<String>> keyboard(String command, List<String> options) {
        List<List<String>> rows = new ArrayList<>();
        for (String option : options) {
            rows.add(List.of(command + " " + option));
        }
        return rows;
    }

    @FunctionalInterface
    private interface Task {
        String run();
    }

    @FunctionalInterface
    private interface SettingsUpdate {
        CodexSettings apply();
    }

    private static final class ActiveTask {
        private volatile Future<?> future;
    }

    private static final class AgentThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("agento-task-runner");
            thread.setDaemon(true);
            return thread;
        }
    }
}
