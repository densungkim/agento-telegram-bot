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
    private final ShellRunner shellRunner;
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
            ShellRunner shellRunner,
            ProcessRunner processRunner,
            BotProperties properties,
            CodexSettingsService settingsService
    ) {
        this.telegramClient = telegramClient;
        this.codexRunner = codexRunner;
        this.shellRunner = shellRunner;
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
            telegramClient.sendMessage(chatId, "Твой chat_id: " + chatId);
            return;
        }

        if (chatId != properties.telegram().allowedChatId()) {
            telegramClient.sendMessage(chatId, "Нет доступа.");
            return;
        }

        if (text.equals("/start") || text.equals("/help")) {
            telegramClient.sendMessage(chatId, helpText());
            return;
        }

        if (text.equals("/ping")) {
            telegramClient.sendMessage(chatId, "pong");
            return;
        }

        if (text.equals("/status") || text.equals("/settings")) {
            telegramClient.sendMessage(chatId, statusText());
            return;
        }

        if (text.equals("/cancel")) {
            cancelTask(chatId);
            return;
        }

        if (handleSettingsCommand(chatId, text)) {
            return;
        }

        if (text.equals("/docker")) {
            runAndReply(chatId, "Проверяю Docker...", shellRunner::runDockerPs);
            return;
        }

        if (text.equals("/logs")) {
            runAndReply(chatId, "Смотрю docker compose logs...", shellRunner::runProjectLogs);
            return;
        }

        if (text.startsWith("/codex ")) {
            String prompt = text.substring("/codex ".length()).trim();
            if (prompt.isBlank()) {
                telegramClient.sendMessage(chatId, "Напиши задачу после /codex");
                return;
            }
            runCodex(chatId, prompt);
            return;
        }

        if (text.startsWith("/")) {
            telegramClient.sendMessage(chatId, "Не понял команду. Напиши /help");
            return;
        }

        runCodex(chatId, text);
    }

    private boolean handleSettingsCommand(long chatId, String text) {
        if (text.equals("/model")) {
            telegramClient.sendKeyboard(chatId, optionsText("Модель Codex", "/model", settingsService.allowedModels()), keyboard("/model", settingsService.allowedModels()));
            return true;
        }
        if (text.startsWith("/model ")) {
            return updateSetting(chatId, () -> settingsService.setModel(text.substring("/model ".length())), "Модель обновлена");
        }

        if (text.equals("/reasoning")) {
            telegramClient.sendKeyboard(chatId, optionsText("Reasoning effort", "/reasoning", settingsService.allowedReasoningEfforts()), keyboard("/reasoning", settingsService.allowedReasoningEfforts()));
            return true;
        }
        if (text.startsWith("/reasoning ")) {
            return updateSetting(chatId, () -> settingsService.setReasoningEffort(text.substring("/reasoning ".length())), "Reasoning обновлен");
        }

        if (text.equals("/mode")) {
            telegramClient.sendKeyboard(chatId, optionsText("Codex access mode", "/mode", settingsService.allowedAccessModes()), keyboard("/mode", settingsService.allowedAccessModes()));
            return true;
        }
        if (text.startsWith("/mode ")) {
            return updateSetting(chatId, () -> settingsService.setAccessMode(text.substring("/mode ".length())), "Codex mode обновлен");
        }

        if (text.equals("/approval")) {
            telegramClient.sendKeyboard(chatId, optionsText("Approval policy", "/approval", settingsService.allowedApprovalPolicies()), keyboard("/approval", settingsService.allowedApprovalPolicies()));
            return true;
        }
        if (text.startsWith("/approval ")) {
            return updateSetting(chatId, () -> settingsService.setApprovalPolicy(text.substring("/approval ".length())), "Approval policy обновлена");
        }

        return false;
    }

    private boolean updateSetting(long chatId, SettingsUpdate update, String successPrefix) {
        try {
            CodexSettings settings = update.apply();
            telegramClient.sendMessage(chatId, successPrefix + ".\n\n" + settingsSummary(settings));
        } catch (IllegalArgumentException e) {
            telegramClient.sendMessage(chatId, e.getMessage());
        } catch (Exception e) {
            telegramClient.sendMessage(chatId, "Не удалось сохранить настройку: " + e.getMessage());
        }
        return true;
    }

    private void runCodex(long chatId, String prompt) {
        runAndReply(chatId, "Запускаю Codex с текущими настройками:\n" + settingsSummary(settingsService.current()), () -> codexRunner.runCodex(prompt));
    }

    private void runAndReply(long chatId, String startedMessage, Task task) {
        ActiveTask active = new ActiveTask();
        if (!activeTask.compareAndSet(null, active)) {
            telegramClient.sendMessage(chatId, "Я уже выполняю задачу. Напиши /status или /cancel.");
            return;
        }

        Future<?> future = executorService.submit(() -> {
            try {
                telegramClient.sendMessage(chatId, startedMessage);
                String result = task.run();
                telegramClient.sendMessage(chatId, result);
            } catch (Exception e) {
                telegramClient.sendMessage(chatId, "Ошибка: " + e.getMessage());
            } finally {
                activeTask.compareAndSet(active, null);
            }
        });
        active.future = future;
    }

    private void cancelTask(long chatId) {
        ActiveTask active = activeTask.get();
        if (active == null) {
            telegramClient.sendMessage(chatId, "Активной задачи нет.");
            return;
        }

        boolean processCancelled = processRunner.cancelActiveProcess();
        Future<?> future = active.future;
        if (future != null) {
            future.cancel(true);
        }
        activeTask.compareAndSet(active, null);

        telegramClient.sendMessage(chatId, processCancelled ? "Остановил активный процесс." : "Задача отменена.");
    }

    private String helpText() {
        return """
                Agento Bot готов.

                Можно просто отправить текст, и он уйдет в Codex как задача.

                Команды:
                /id - узнать chat_id
                /ping - проверить, что бот живой
                /status - текущие настройки и занятость
                /cancel - остановить активную задачу
                /model - выбрать модель
                /reasoning - выбрать reasoning effort
                /mode - выбрать режим доступа Codex
                /approval - выбрать approval policy
                /docker - docker ps
                /logs - docker compose logs --tail=120
                /codex текст задачи - явно запустить Codex
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
                approval: %s
                """.formatted(
                settings.model(),
                settings.reasoningEffort(),
                settings.accessMode(),
                settings.usesDangerousBypass() ? "bypassed" : settings.sandboxMode(),
                settings.usesDangerousBypass() ? "bypassed" : settings.approvalPolicy()
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
