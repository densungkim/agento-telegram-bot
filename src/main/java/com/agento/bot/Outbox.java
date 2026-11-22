package com.agento.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Folder where Claude puts files for the user. The bot empties it before every Claude task, sends its files after a
 * successful answer and deletes them in every outcome, so nothing accumulates on the server.
 */
@Component
public class Outbox {

    static final Path DIR = Path.of("/home/agento/agento-outbox");
    /** Telegram accepts documents up to 50 MB from bots; keep a margin. */
    static final long MAX_FILE_BYTES = 49L * 1024 * 1024;
    static final int MAX_FILES = 10;

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    /** Creates the folder if needed and removes everything in it. */
    public void prepare() {
        try {
            Files.createDirectories(DIR);
        } catch (IOException e) {
            log.warn("Could not create outbox {}: {}", DIR, e.getMessage());
        }
        clear();
    }

    /** Top-level entries, sorted by name. */
    public List<Path> entries() {
        if (!Files.isDirectory(DIR)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(DIR)) {
            return entries.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException e) {
            log.warn("Could not list outbox {}: {}", DIR, e.getMessage());
            return List.of();
        }
    }

    /** Deletes everything inside the folder; errors are only logged. */
    public void clear() {
        for (Path entry : entries()) {
            try (Stream<Path> tree = Files.walk(entry)) {
                for (Path item : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            } catch (IOException e) {
                log.warn("Could not delete outbox entry {}: {}", entry, e.getMessage());
            }
        }
    }
}
