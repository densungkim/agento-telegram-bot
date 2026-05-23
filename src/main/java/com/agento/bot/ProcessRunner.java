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

@Component
public class ProcessRunner {

    private static final int MAX_CAPTURED_OUTPUT_CHARS = 200_000;

    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public CommandResult run(List<String> command, File workdir, Duration timeout) {
        return run(command, workdir, timeout, null);
    }

    public CommandResult run(List<String> command, File workdir, Duration timeout, String input) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workdir);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            activeProcess.set(process);

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
            String inputError = writeProcessInput(process, input);
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                String output = readOutputWithTimeout(outputFuture);
                return new CommandResult(-1, true, appendInputError(output, inputError));
            }

            String output = readOutputWithTimeout(outputFuture);
            return new CommandResult(process.exitValue(), false, appendInputError(output, inputError));
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

    private String writeProcessInput(Process process, String input) {
        try (var output = process.getOutputStream()) {
            if (input != null && !input.isEmpty()) {
                output.write(input.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        } catch (IOException e) {
            return "Failed to write process input: " + e.getMessage();
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
            return "Process finished, but output reader did not complete in time.";
        }
    }

    private String readOutput(Process process) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            StringBuilder output = new StringBuilder();
            int omittedChars = 0;
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
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
                return "[captured output truncated, omitted first characters: " + omittedChars + "]\n" + output;
            }
            return output.toString();
        } catch (IOException e) {
            return "Failed to read process output: " + e.getMessage();
        }
    }
}
