package com.agento.bot;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class CodexModelCatalog {

    private static final List<String> AUTH_MODES = List.of("chatgpt", "api-key");

    private final Map<String, Model> models;

    public CodexModelCatalog(BotProperties properties) {
        String authMode = properties.codex().authMode();
        if (!AUTH_MODES.contains(authMode)) {
            throw new IllegalArgumentException("CODEX_AUTH_MODE must be chatgpt or api-key");
        }

        String catalogFile = properties.codex().modelCatalogFile();
        Map<String, Model> available = new LinkedHashMap<>();
        try (InputStream input = catalogFile == null || catalogFile.isBlank()
                ? new ClassPathResource("codex-models.json").getInputStream()
                : Files.newInputStream(Path.of(catalogFile))) {
            Catalog catalog = JsonMapper.builder().build().readValue(input, Catalog.class);
            if (catalog == null || catalog.models() == null || catalog.models().isEmpty()) {
                throw new IllegalArgumentException("Codex model catalog must contain models");
            }
            Map<String, Model> all = new LinkedHashMap<>();
            for (Model model : catalog.models()) {
                validate(model);
                if (all.putIfAbsent(model.id(), model) != null) {
                    throw new IllegalArgumentException("Duplicate Codex model: " + model.id());
                }
                if (model.authModes().contains(authMode)) {
                    available.put(model.id(), new Model(model.id(), model.defaultReasoningEffort(),
                            List.copyOf(model.reasoningEfforts()), List.copyOf(model.authModes())));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Codex model catalog", e);
        }
        this.models = java.util.Collections.unmodifiableMap(available);
    }

    public List<String> modelIds() {
        return Stream.concat(Stream.of(CodexSettings.DEFAULT), models.keySet().stream()).toList();
    }

    public Model model(String id) {
        Model model = models.get(id);
        if (model == null) {
            throw new IllegalArgumentException("Модель '" + id
                    + "' отсутствует в текущем каталоге. Выберите модель через /model.");
        }
        return model;
    }

    public List<String> reasoningEfforts(String id) {
        if (CodexSettings.DEFAULT.equals(id)) {
            return List.of(CodexSettings.DEFAULT);
        }
        return Stream.concat(Stream.of(CodexSettings.DEFAULT), model(id).reasoningEfforts().stream()).toList();
    }

    private static void validate(Model model) {
        if (model == null || model.id() == null || !model.id().matches("[a-z0-9][a-z0-9._-]*")
                || CodexSettings.DEFAULT.equals(model.id())
                || model.reasoningEfforts() == null || model.reasoningEfforts().isEmpty()
                || model.authModes() == null || model.authModes().isEmpty()) {
            throw new IllegalArgumentException("Invalid Codex model catalog entry");
        }
        if (model.reasoningEfforts().stream().anyMatch(effort -> effort == null
                || !effort.matches("[a-z][a-z0-9-]*") || CodexSettings.DEFAULT.equals(effort))
                || model.reasoningEfforts().stream().distinct().count() != model.reasoningEfforts().size()
                || !model.reasoningEfforts().contains(model.defaultReasoningEffort())
                || !AUTH_MODES.containsAll(model.authModes())) {
            throw new IllegalArgumentException("Invalid reasoning or authentication options for " + model.id());
        }
    }

    public record Catalog(String cliVersion, String source, List<Model> models) {
    }

    public record Model(String id, String defaultReasoningEffort,
                        List<String> reasoningEfforts, List<String> authModes) {
    }
}
