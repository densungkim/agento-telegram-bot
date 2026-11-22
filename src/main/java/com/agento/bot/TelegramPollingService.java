package com.agento.bot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.agento.bot.TelegramClient.Button;
import static com.agento.bot.TelegramMenus.Screen;

@Service
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);
    private static final DateTimeFormatter STARTUP_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final TelegramClient telegram;
    private final ClaudeRunner claude;
    private final ProcessRunner processes;
    private final BotProperties properties;
    private final ClaudeSettingsService settings;
    private final ClaudeSessionStore sessions;
    private final TelegramMenus menus;
    private final long startedAtEpochSeconds = Instant.now().getEpochSecond();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean telegramReady = new AtomicBoolean();
    private final AtomicReference<ActiveTask> activeTask = new AtomicReference<>();
    private volatile String lastRuntime;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(threads("agento-task-runner"));
    private final ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor(threads("agento-progress"));
    private long offset;

    public TelegramPollingService(TelegramClient telegram, ClaudeRunner claude, ProcessRunner processes,
                                  BotProperties properties, ClaudeSettingsService settings,
                                  ClaudeSessionStore sessions, TelegramMenus menus) {
        if (properties.telegram().allowedChatId() == 0) {
            throw new IllegalStateException("TELEGRAM_ALLOWED_CHAT_ID is required and cannot be 0");
        }
        this.telegram = telegram;
        this.claude = claude;
        this.processes = processes;
        this.properties = properties;
        this.settings = settings;
        this.sessions = sessions;
        this.menus = menus;
    }

    @PostConstruct
    public void configureTelegram() {
        progress.scheduleWithFixedDelay(this::refreshProgress, 5, 5, TimeUnit.SECONDS);
        try {
            telegram.initialize();
            telegram.setCommands(List.of(
                    new TelegramClient.BotCommand("start", "Открыть управление ботом"),
                    new TelegramClient.BotCommand("menu", "Модель, effort и доступ"),
                    new TelegramClient.BotCommand("help", "Как пользоваться ботом"),
                    new TelegramClient.BotCommand("ping", "Проверить связь"),
                    new TelegramClient.BotCommand("status", "Сессия и текущая задача"),
                    new TelegramClient.BotCommand("cancel", "Отменить текущую задачу"),
                    new TelegramClient.BotCommand("new", "Удалить контекст и начать заново"),
                    new TelegramClient.BotCommand("model", "Выбрать модель"),
                    new TelegramClient.BotCommand("effort", "Выбрать уровень effort"),
                    new TelegramClient.BotCommand("mode", "Выбрать режим доступа")
            ));
        } catch (TelegramClient.ApiException e) {
            log.warn("Could not configure Telegram commands: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            JsonNode response = telegram.getUpdates(offset);
            if (telegramReady.compareAndSet(false, true)) {
                log.info("AGENTO_TELEGRAM_READY");
                sendStartupMessage();
            }
            for (JsonNode update : response.path("result")) {
                offset = update.path("update_id").longValue() + 1;
                try {
                    if (update.path("callback_query").asOptional().isPresent()) {
                        handleCallback(update.path("callback_query"));
                    } else if (update.path("message").asOptional().isPresent()) {
                        handleMessage(update.path("message"));
                    }
                } catch (Exception e) {
                    log.warn("Could not handle Telegram update: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Telegram polling error: {}", e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    /** Sent once, after the first successful getUpdates; a failure must not stop polling. */
    private void sendStartupMessage() {
        String startedAt = STARTUP_TIME.format(Instant.ofEpochSecond(startedAtEpochSeconds).atZone(ZoneId.systemDefault()));
        try {
            telegram.sendMessage(properties.telegram().allowedChatId(),
                    "✅ Приложение успешно запущено\n\n🕒 Время запуска: " + startedAt);
        } catch (RuntimeException e) {
            log.warn("Could not send the startup message: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        progress.shutdownNow();
        processes.cancelActiveProcess();
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Task worker did not finish within the shutdown grace period");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleMessage(JsonNode message) {
        long chatId = message.path("chat").path("id").longValue();
        long date = message.path("date").longValue(0);
        if (chatId != properties.telegram().allowedChatId()
                || (date > 0 && date < startedAtEpochSeconds - 10)) {
            return;
        }
        int replyTo = message.path("message_id").intValue();
        String text = message.path("text").asString("").strip();
        if (text.isBlank()) {
            telegram.sendScreen(chatId, "Пока принимаю только текст. Опишите задачу сообщением.",
                    menus.mainButtons(), replyTo);
            return;
        }
        if (!text.startsWith("/")) {
            startClaude(chatId, replyTo, text);
            return;
        }

        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length == 2 ? parts[1].strip() : "";
        int mention = command.indexOf('@');
        if (mention >= 0) {
            if (telegram.username().isBlank()) {
                telegram.initialize();
            }
            if (!command.substring(mention + 1).equalsIgnoreCase(telegram.username())) {
                return;
            }
            command = command.substring(0, mention);
        }
        if (command.equals("/model") || command.equals("/effort") || command.equals("/mode")) {
            String kind = command.substring(1);
            if (argument.isBlank()) {
                show(chatId, null, replyTo, menus.options(kind, 0));
            } else {
                updateSetting(chatId, null, replyTo, kind, argument);
            }
            return;
        }
        if (!argument.isBlank()) {
            show(chatId, null, replyTo, menus.main("Эта команда не принимает аргументы."));
            return;
        }
        switch (command) {
            case "/start" -> {
                telegram.removeLegacyKeyboard(chatId);
                show(chatId, null, replyTo, menus.main("Agento готов. Отправьте задачу обычным текстом."));
            }
            case "/help" -> show(chatId, null, replyTo, help());
            case "/menu", "/settings" -> show(chatId, null, replyTo, menus.main(""));
            case "/status" -> show(chatId, null, replyTo, status());
            case "/ping" -> telegram.sendScreen(chatId, "На связи.", menus.mainButtons(), replyTo);
            case "/cancel" -> cancelCurrent(chatId, null, replyTo);
            case "/new" -> startReset(chatId, replyTo);
            default -> show(chatId, null, replyTo, menus.main("Неизвестная команда. Используйте кнопки или /help."));
        }
    }

    private void handleCallback(JsonNode query) {
        String queryId = query.path("id").asString("");
        long chatId = query.path("message").path("chat").path("id").longValue();
        int messageId = query.path("message").path("message_id").intValue();
        if (chatId != properties.telegram().allowedChatId() || messageId <= 0) {
            telegram.answerCallback(queryId, "Кнопка недоступна.");
            return;
        }
        String data = query.path("data").asString("");
        if (data.startsWith("cancel:")) {
            ActiveTask active = activeTask.get();
            if (active == null || !active.id.equals(data.substring("cancel:".length()))) {
                telegram.answerCallback(queryId, "Кнопка этой задачи устарела. Используйте /status.");
            } else if (active.finishing) {
                telegram.answerCallback(queryId, "Выполнение уже завершено. Доставляю ответ.");
            } else {
                telegram.answerCallback(queryId, "Отмена запрошена.");
                requestCancel(active);
            }
            return;
        }
        telegram.answerCallback(queryId, "");
        try {
            if (data.startsWith("page:") || data.startsWith("choose:")) {
                String[] parts = data.split(":", -1);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Некорректная кнопка.");
                }
                int index = Integer.parseInt(parts[3]);
                if (parts[0].equals("page")) {
                    if (!menus.currentPage(parts[1], parts[2])) {
                        throw new IllegalArgumentException("Меню устарело.");
                    }
                    show(chatId, messageId, messageId, menus.options(parts[1], index));
                } else {
                    String value = menus.resolveChoice(parts[1], parts[2], index);
                    updateSetting(chatId, messageId, messageId, parts[1], value);
                }
                return;
            }
            switch (data) {
                case "menu" -> show(chatId, messageId, messageId, menus.main(""));
                case "models" -> show(chatId, messageId, messageId, menus.options("model", 0));
                case "effort" -> show(chatId, messageId, messageId, menus.options("effort", 0));
                case "modes" -> show(chatId, messageId, messageId, menus.options("mode", 0));
                case "status" -> show(chatId, messageId, messageId, status());
                case "help" -> show(chatId, messageId, messageId, help());
                case "cancel" -> cancelCurrent(chatId, messageId, messageId);
                case "new" -> startReset(chatId, messageId);
                default -> show(chatId, messageId, messageId, menus.main("Кнопка устарела. Выберите действие заново."));
            }
        } catch (IllegalArgumentException e) {
            show(chatId, messageId, messageId, menus.main("Меню изменилось. Выберите настройку заново."));
        }
    }

    private void updateSetting(long chatId, Integer editId, int replyTo, String kind, String value) {
        String notice;
        try {
            switch (kind) {
                case "model" -> settings.setModel(value);
                case "effort" -> settings.setEffort(value);
                case "mode" -> settings.setAccessMode(value);
                default -> throw new IllegalArgumentException("Неизвестная настройка.");
            }
            notice = "Настройка сохранена." + (busy() ? " Она применяется к следующим запросам." : "");
        } catch (IllegalArgumentException e) {
            notice = e.getMessage();
        } catch (Exception e) {
            log.warn("Could not save bot setting: {}", e.getMessage());
            notice = "Не удалось сохранить настройку. Предыдущее значение осталось в силе.";
        }
        show(chatId, editId, replyTo, menus.main(notice));
    }

    private void startClaude(long chatId, int replyTo, String prompt) {
        if (busy()) {
            showBusy(chatId, replyTo);
            return;
        }
        ClaudeSettings snapshot;
        try {
            snapshot = settings.settingsForRun();
        } catch (IllegalArgumentException | IllegalStateException e) {
            show(chatId, null, replyTo, menus.main(e.getMessage()));
            return;
        }
        startTask(chatId, replyTo, prompt, snapshot, "Запускаю Claude Code.",
                updates -> claude.run(prompt, snapshot, updates));
    }

    private void startReset(long chatId, int replyTo) {
        startTask(chatId, replyTo, "Сброс контекста /new", null, "Удаляю предыдущую сессию.",
                updates -> claude.newSession());
    }

    private void startTask(long chatId, int replyTo, String prompt, ClaudeSettings snapshot,
                           String phase, Task task) {
        ActiveTask active = new ActiveTask(chatId, replyTo, prompt, snapshot, phase);
        if (processes.hasActiveProcess() || !activeTask.compareAndSet(null, active)) {
            showBusy(chatId, replyTo);
            return;
        }
        try {
            active.messageId = telegram.sendScreen(chatId,
                    progressText(active, "Запрос принят."), progressButtons(active), replyTo);
            if (active.messageId <= 0) {
                throw new IllegalStateException("Telegram не подтвердил сообщение о запуске.");
            }
            worker.execute(() -> runTask(active, task));
        } catch (RuntimeException e) {
            activeTask.compareAndSet(active, null);
            throw e;
        }
    }

    private void runTask(ActiveTask active, Task task) {
        active.worker = Thread.currentThread();
        String result = null;
        String terminal = "Обработка завершена.";
        try {
            if (!active.cancelRequested) {
                result = task.run(new TaskProgress() {
                    @Override
                    public void phase(String text) {
                        active.phase = text;
                    }

                    @Override
                    public void runtime(String model, String permissionMode) {
                        active.runtime = runtimeText(model, permissionMode, active.snapshot);
                        lastRuntime = active.runtime;
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Bot task failed: {}", e.getMessage());
            result = "Не удалось обработать запрос: " + e.getMessage();
            terminal = "Ошибка обработки.";
        } finally {
            active.finishing = true;
            boolean interrupted = Thread.interrupted();
            try {
                if (active.cancelRequested) {
                    terminal = processes.hasActiveProcess()
                            ? "Процесс ещё останавливается. Проверьте /status."
                            : "Обработка отмены завершена.";
                } else if (result != null) {
                    finishProgress(active, "Доставляю ответ.");
                    try {
                        telegram.sendResult(active.chatId, result, active.replyTo, active.shortId());
                    } catch (TelegramClient.ApiException e) {
                        log.warn("Could not deliver bot result: {}", e.getMessage());
                        terminal = "Обработка завершена, но ответ не доставлен в Telegram."
                                + "\n" + TelegramTextFormatter.clip(e.getMessage(), 180)
                                + "\nЗадача не запускалась повторно.";
                    }
                }
                finishProgress(active, terminal + "\nСессия: " + sessions.description());
            } finally {
                active.worker = null;
                activeTask.compareAndSet(active, null);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void refreshProgress() {
        ActiveTask active = activeTask.get();
        if (active == null || active.messageId <= 0 || active.finishing
                || (active.nextUpdateNanos != 0 && System.nanoTime() - active.nextUpdateNanos < 0)) {
            return;
        }
        try {
            synchronized (active.uiLock) {
                if (active.finishing || activeTask.get() != active) {
                    return;
                }
                telegram.editScreen(active.chatId, active.messageId,
                        progressText(active, active.cancelRequested ? "Отмена запрошена." : "Выполняется."),
                        progressButtons(active));
                telegram.sendTyping(active.chatId);
            }
        } catch (TelegramClient.ApiException e) {
            active.nextUpdateNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(Math.max(5, e.retryAfterSeconds()));
            log.debug("Could not update task progress: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Could not render task progress: {}", e.getMessage());
        }
    }

    private void finishProgress(ActiveTask active, String state) {
        try {
            synchronized (active.uiLock) {
                telegram.editScreen(active.chatId, active.messageId,
                        progressText(active, state), List.of(List.of(
                                new Button("Меню", "menu"), new Button("Статус", "status"))));
            }
        } catch (TelegramClient.ApiException e) {
            log.warn("Could not finalize task progress: {}", e.getMessage());
        }
    }

    private void cancelCurrent(long chatId, Integer editId, int replyTo) {
        ActiveTask active = activeTask.get();
        String notice;
        if (active == null) {
            if (processes.hasActiveProcess()) {
                processes.cancelActiveProcess();
                notice = "Повторно запрошена остановка процесса. Проверьте /status.";
            } else {
                notice = "Нет активной задачи.";
            }
        } else if (active.finishing) {
            notice = "Выполнение уже завершено. Доставляю результат.";
        } else {
            requestCancel(active);
            notice = "Отмена запрошена. Дождитесь её завершения перед /new.";
        }
        show(chatId, editId, replyTo, menus.main(notice));
    }

    private void requestCancel(ActiveTask active) {
        if (active.finishing) {
            return;
        }
        active.cancelRequested = true;
        active.phase = "Останавливаю Claude Code.";
        Thread running = active.worker;
        if (running != null) {
            running.interrupt();
        }
        processes.cancelActiveProcess();
    }

    private String progressText(ActiveTask active, String state) {
        return "**Задача #" + active.shortId() + "** · " + active.elapsed()
                + "\n" + active.preview + "\n\n" + state
                + (active.finishing ? "" : "\n" + active.phase)
                + (active.snapshot == null ? "" : "\n\n" + menus.summary(active.snapshot))
                + (active.runtime == null ? "" : "\n" + active.runtime);
    }

    /** Model id and permission mode Claude Code really started with; a downgraded mode is flagged. */
    private static String runtimeText(String model, String permissionMode, ClaudeSettings snapshot) {
        String expected = snapshot == null ? "" : snapshot.permissionMode();
        return "**Запущено:** " + (model.isBlank() ? "модель неизвестна" : model)
                + " · " + (permissionMode.isBlank() ? "режим неизвестен" : permissionMode)
                + (expected.isEmpty() || expected.equals(permissionMode) ? "" : " ⚠ ожидался " + expected);
    }

    private List<List<Button>> progressButtons(ActiveTask active) {
        return List.of(List.of(new Button("Отменить", "cancel:" + active.id),
                new Button("Статус", "status")));
    }

    private void showBusy(long chatId, int replyTo) {
        show(chatId, null, replyTo, new Screen(
                "Запрос не принят: предыдущая задача выполняется или отменяется."
                        + "\nДождитесь завершения либо используйте /cancel.",
                menus.mainButtons()));
    }

    private boolean busy() {
        return activeTask.get() != null || processes.hasActiveProcess();
    }

    private Screen status() {
        ActiveTask active = activeTask.get();
        String state = active == null ? (processes.hasActiveProcess() ? "Процесс ещё останавливается" : "Готов к запросу")
                : (active.finishing ? "Доставляю ответ" : active.cancelRequested ? "Отменяю задачу" : "Выполняю задачу");
        String text = "**Статус Agento**\n\n" + state + "\nСессия: " + sessions.description();
        if (active != null) {
            text += "\n\nЗадача #" + active.shortId() + " · " + active.elapsed()
                    + "\n" + active.preview + "\n" + active.phase
                    + (active.snapshot == null ? "" : "\n\nПараметры выполняемой задачи:\n" + menus.summary(active.snapshot));
        }
        if (lastRuntime != null) {
            text += "\n\nПоследний запуск Claude Code:\n" + lastRuntime;
        }
        text += "\n\nНастройки следующих запросов:\n" + menus.summary(settings.current())
                + "\n\nРабочий каталог: " + properties.claude().workdir();
        String problem = settings.validationProblem();
        return new Screen(text + (problem.isBlank() ? "" : "\n\n" + problem), menus.mainButtons());
    }

    private Screen help() {
        return new Screen("""
                **Agento — помощник для VPS**

                Отправляйте задачи обычным текстом. Следующие сообщения продолжают тот же разговор, в том числе после перезапуска бота. Claude Code удаляет старые сессии (по умолчанию через 30 дней).

                /menu — модель, effort и доступ
                /status — сессия и выполняемая задача
                /cancel — запросить остановку задачи
                /new — удалить текущий контекст
                /model, /effort, /mode — выбор настройки
                /ping — проверить связь

                Одна задача за раз. Во время работы новые запросы не ставятся в очередь. Смена настроек относится к следующим запросам.

                /new сохраняет настройки и удаляет текущую сессию Claude Code. Сообщения Telegram и изменения на VPS сохраняются.

                Длинные ответы приходят файлом. Неизвестные команды не отправляются в Claude.
                """, menus.mainButtons());
    }

    private void show(long chatId, Integer editId, int replyTo, Screen screen) {
        if (editId != null) {
            ActiveTask active = activeTask.get();
            if (active != null && active.chatId == chatId && active.messageId == editId) {
                // A status button must not replace the live progress card.
                telegram.sendScreen(chatId, screen.text(), screen.buttons(), replyTo);
                return;
            }
            try {
                telegram.editScreen(chatId, editId, screen.text(), screen.buttons());
                return;
            } catch (TelegramClient.ApiException e) {
                if (!e.cannotEdit()) {
                    throw e;
                }
            }
        }
        telegram.sendScreen(chatId, screen.text(), screen.buttons(), replyTo);
    }

    private static ThreadFactory threads(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    private interface Task {
        String run(TaskProgress progress);
    }

    private static final class ActiveTask {
        private final String id = UUID.randomUUID().toString();
        private final long startedNanos = System.nanoTime();
        private final Object uiLock = new Object();
        private final long chatId;
        private final int replyTo;
        private final String preview;
        private final ClaudeSettings snapshot;
        private volatile String phase;
        private volatile String runtime;
        private volatile int messageId;
        private volatile long nextUpdateNanos;
        private volatile Thread worker;
        private volatile boolean cancelRequested;
        private volatile boolean finishing;

        private ActiveTask(long chatId, int replyTo, String prompt, ClaudeSettings snapshot, String phase) {
            this.chatId = chatId;
            this.replyTo = replyTo;
            this.preview = TelegramTextFormatter.clip(prompt.replaceAll("\\s+", " ").strip(), 100);
            this.snapshot = snapshot;
            this.phase = phase;
        }

        private String shortId() {
            return id.substring(0, 8);
        }

        private String elapsed() {
            long seconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
            return seconds < 60 ? seconds + " с" : seconds / 60 + " мин " + seconds % 60 + " с";
        }
    }
}
