package com.agento.bot;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
public class ProcessRunner {

    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public CommandResult run(List<String> command, File workdir, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workdir);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            activeProcess.set(process);

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                String output = readOutputWithTimeout(outputFuture);
                return new CommandResult(-1, true, output);
            }

            String output = readOutputWithTimeout(outputFuture);
            return new CommandResult(process.exitValue(), false, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelActiveProcess();
            return new CommandResult(-1, false, "Command interrupted.");
        } catch (Exception e) {
            return new CommandResult(-1, false, "Failed to run command: " + e.getMessage());
        } finally {
            activeProcess.set(null);
        }
    }

    public boolean cancelActiveProcess() {
        Process process = activeProcess.get();
        if (process == null || !process.isAlive()) {
            return false;
        }

        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return true;
    }

    private String readOutputWithTimeout(CompletableFuture<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Process finished, but output reader did not complete in time.";
        }
    }

    private String readOutput(Process process) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Failed to read process output: " + e.getMessage();
        }
    }
}
