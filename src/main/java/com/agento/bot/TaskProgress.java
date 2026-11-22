package com.agento.bot;

/** Progress of a running task, reported to the Telegram progress card. */
interface TaskProgress {

    /** Short human-readable stage, e.g. "Работаю с командами на VPS.". */
    void phase(String text);

    /** What Claude Code actually started with ({@code system/init}): resolved model id and permission mode. */
    default void runtime(String model, String permissionMode) {
    }
}
