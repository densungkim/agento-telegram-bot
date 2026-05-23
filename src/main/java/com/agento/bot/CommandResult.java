package com.agento.bot;

public record CommandResult(
        int exitCode,
        boolean timedOut,
        String output
) {
}
