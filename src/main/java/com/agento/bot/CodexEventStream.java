package com.agento.bot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Consumer;

final class CodexEventStream implements Consumer<String> {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CodexSessionStore sessions;
    private final String expectedSessionId;
    private final Consumer<String> progress;
    private volatile boolean started;
    private volatile String error = "";

    CodexEventStream(CodexSessionStore sessions, String expectedSessionId, Consumer<String> progress) {
        this.sessions = sessions;
        this.expectedSessionId = expectedSessionId;
        this.progress = progress;
    }

    @Override
    public void accept(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode event = mapper.readTree(line);
        switch (event.path("type").asString("")) {
            case "thread.started" -> {
                String id = CodexSessionStore.checkedId(event.path("thread_id").asString(""));
                if (expectedSessionId != null && !expectedSessionId.equals(id)) {
                    throw new IllegalStateException("Codex не продолжил выбранную сессию. Выполнение остановлено.");
                }
                sessions.remember(id);
                started = true;
                progress.accept("Сессия готова. Codex обрабатывает запрос.");
            }
            case "error", "turn.failed" -> {
                String message = event.path("error").path("message").asString("");
                if (message.isBlank()) {
                    message = event.path("message").asString("Ошибка Codex");
                }
                error = message.substring(0, Math.min(message.length(), 2_000));
                progress.accept("Codex сообщил об ошибке. Ожидаю завершения.");
            }
            case "turn.completed" -> {
                error = "";
                progress.accept("Codex завершил обработку. Получаю ответ.");
            }
            case "turn.started" -> progress.accept("Codex анализирует запрос.");
            case "item.started", "item.updated", "item.completed" -> progress.accept(switch (
                    event.path("item").path("type").asString("")) {
                case "command_execution" -> "Работаю с командами на VPS.";
                case "file_change" -> "Работаю с файлами.";
                case "mcp_tool_call", "tool_call" -> "Работаю с инструментом.";
                case "web_search" -> "Ищу информацию.";
                case "agent_message" -> "Готовлю ответ.";
                case "plan", "todo_list" -> "Уточняю план выполнения.";
                default -> "Codex обрабатывает запрос.";
            });
            default -> {
                // Other events do not need to be retained for conversation continuity.
            }
        }
    }

    boolean started() {
        return started;
    }

    String error() {
        return error;
    }
}
