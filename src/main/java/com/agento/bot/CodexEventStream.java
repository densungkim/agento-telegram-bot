package com.agento.bot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Consumer;

final class CodexEventStream implements Consumer<String> {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CodexSessionStore sessions;
    private final String expectedSessionId;
    private volatile boolean started;
    private volatile String error = "";

    CodexEventStream(CodexSessionStore sessions, String expectedSessionId) {
        this.sessions = sessions;
        this.expectedSessionId = expectedSessionId;
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
            }
            case "error", "turn.failed" -> {
                String message = event.path("error").path("message").asString("");
                if (message.isBlank()) {
                    message = event.path("message").asString("Ошибка Codex");
                }
                error = message.substring(0, Math.min(message.length(), 2_000));
            }
            case "turn.completed" -> error = "";
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
