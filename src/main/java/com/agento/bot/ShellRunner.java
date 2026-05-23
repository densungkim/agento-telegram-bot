package com.agento.bot;

import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.List;

@Service
public class ShellRunner {

    private final BotProperties properties;
    private final ProcessRunner processRunner;

    public ShellRunner(BotProperties properties, ProcessRunner processRunner) {
        this.properties = properties;
        this.processRunner = processRunner;
    }

    public String runDockerPs() {
        return runSafeShell("docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'");
    }

    public String runProjectLogs() {
        return runSafeShell("docker compose logs --tail=120");
    }

    private String runSafeShell(String shellCommand) {
        File workdir = new File(properties.codex().workdir());
        if (!workdir.exists() || !workdir.isDirectory()) {
            return "CODEX_WORKDIR does not exist or is not a directory: " + workdir.getAbsolutePath();
        }

        CommandResult result = processRunner.run(
                List.of("bash", "-lc", shellCommand),
                workdir,
                Duration.ofSeconds(120)
        );

        if (result.timedOut()) {
            return "Command stopped by timeout.\n\n" + result.output();
        }

        return "Exit code: " + result.exitCode() + "\n\n" + result.output();
    }
}
