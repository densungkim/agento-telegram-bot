package com.agento.bot;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class ProcessRunner {

    private static final int MAX_CAPTURED_OUTPUT_CHARS = 200_000;

    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public CommandResult run(List<String> command, File workdir, Duration timeout) {
        return run(command, workdir, timeout, null);
    }

    public CommandResult run(List<String> command, File workdir, Duration timeout, String input) {
        return run(command, workdir, timeout, input, Map.of(), null);
    }

    public CommandResult run(List<String> command, File workdir, Duration timeout, String input,
                             Map<String, String> environment, Consumer<String> stdoutListener) {
        return run(command, workdir, timeout, input, environment, Set.of(), stdoutListener);
    }

    public CommandResult run(List<String> command, File workdir, Duration timeout, String input,
                             Map<String, String> environment, Set<String> unsetEnvironment,
                             Consumer<String> stdoutListener) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workdir);
        processBuilder.environment().keySet().removeAll(unsetEnvironment);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectErrorStream(stdoutListener == null);
        Object listenerLock = new Object();
        AtomicBoolean acceptingEvents = new AtomicBoolean(true);
        AtomicReference<String> listenerError = new AtomicReference<>();
        Process process = null;
        CompletableFuture<String> eventsFuture = null;

        try {
            if (hasActiveProcess()) {
                return new CommandResult(-1, false, "Предыдущий процесс ещё выполняется.");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            process = processBuilder.start();
            if (!activeProcess.compareAndSet(null, process)) {
                process.destroyForcibly();
                return new CommandResult(-1, false, "Предыдущий процесс ещё выполняется.");
            }
            Process running = process;

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(running,
                    stdoutListener != null, null));
            eventsFuture = stdoutListener == null ? null
                    : CompletableFuture.supplyAsync(() -> readOutput(running, false, line -> {
                        synchronized (listenerLock) {
                            if (acceptingEvents.get() && listenerError.get() == null) {
                                try {
                                    stdoutListener.accept(line);
                                } catch (RuntimeException e) {
                                    listenerError.set("Не удалось обработать событие Claude Code: " + e.getMessage());
                                    cancelActiveProcess();
                                }
                            }
                        }
                    }));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            String inputError = writeProcessInput(process, input);
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                cancelActiveProcess();
                if (eventsFuture != null) {
                    readOutputWithTimeout(eventsFuture);
                }
                String output = readOutputWithTimeout(outputFuture);
                return new CommandResult(-1, true, appendInputError(output, inputError));
            }

            String eventsOutput = eventsFuture == null ? "" : readOutputWithTimeout(eventsFuture);
            String output = appendInputError(readOutputWithTimeout(outputFuture), eventsOutput);
            if (listenerError.get() != null) {
                return new CommandResult(-1, false, appendInputError(output, listenerError.get()));
            }
            return new CommandResult(process.exitValue(), false, appendInputError(output, inputError));
        } catch (InterruptedException e) {
            cancelActiveProcess();
            if (eventsFuture != null) {
                try {
                    readOutputWithTimeout(eventsFuture);
                } catch (Exception ignored) {
                    // Preserve cancellation while giving a buffered system/init event a chance to confirm the session.
                }
            }
            Thread.currentThread().interrupt();
            return new CommandResult(-1, false, "Команда прервана.");
        } catch (Exception e) {
            return new CommandResult(-1, false, "Не удалось выполнить команду: " + e.getMessage());
        } finally {
            synchronized (listenerLock) {
                acceptingEvents.set(false);
            }
            if (process != null) {
                if (process.isAlive() && activeProcess.get() == process) {
                    cancelActiveProcess();
                }
                if (!process.isAlive()) {
                    activeProcess.compareAndSet(process, null);
                }
            }
        }
    }

    public boolean cancelActiveProcess() {
        Process process = activeProcess.get();
        if (process == null || !process.isAlive()) {
            return false;
        }

        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroy);
        process.destroy();
        boolean interrupted = Thread.interrupted();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        } finally {
            descendants.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return !process.isAlive();
    }

    public boolean hasActiveProcess() {
        Process process = activeProcess.get();
        if (process == null) {
            return false;
        }
        if (process.isAlive()) {
            return true;
        }
        activeProcess.compareAndSet(process, null);
        return false;
    }

    private String writeProcessInput(Process process, String input) {
        try (var output = process.getOutputStream()) {
            if (input != null && !input.isEmpty()) {
                output.write(input.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        } catch (IOException e) {
            return "Не удалось передать запрос процессу: " + e.getMessage();
        }
    }

    private String appendInputError(String output, String inputError) {
        if (inputError == null || inputError.isBlank()) {
            return output;
        }
        if (output == null || output.isBlank()) {
            return inputError;
        }
        return output + "\n" + inputError;
    }

    private String readOutputWithTimeout(CompletableFuture<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Процесс завершён, но чтение вывода не завершилось вовремя.";
        }
    }

    private String readOutput(Process process, boolean stderr, Consumer<String> listener) {
        try (var reader = stderr ? process.errorReader(StandardCharsets.UTF_8) : process.inputReader(StandardCharsets.UTF_8)) {
            StringBuilder output = new StringBuilder();
            int omittedChars = 0;
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (listener != null) {
                    listener.accept(line);
                    continue;
                }
                if (!firstLine) {
                    output.append('\n');
                }
                output.append(line);
                firstLine = false;

                if (output.length() > MAX_CAPTURED_OUTPUT_CHARS) {
                    int deleteChars = output.length() - MAX_CAPTURED_OUTPUT_CHARS;
                    output.delete(0, deleteChars);
                    omittedChars += deleteChars;
                }
            }

            if (omittedChars > 0) {
                return "[диагностика сокращена; пропущено начальных символов: " + omittedChars + "]\n" + output;
            }
            return output.toString();
        } catch (IOException e) {
            return "Не удалось прочитать вывод процесса: " + e.getMessage();
        }
    }
}
