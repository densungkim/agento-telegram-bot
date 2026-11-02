package com.agento.bot;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.agento.bot.TelegramClient.Button;

@Component
public class TelegramMenus {

    private static final int PAGE_SIZE = 8;
    private final CodexSettingsService settings;
    private final BotProperties properties;

    public TelegramMenus(CodexSettingsService settings, BotProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    public Screen main(String notice) {
        String text = "**Управление Agento**\n\n" + summary(settings.current())
                + "\n\nОтправьте задачу обычным текстом. Контекст сохраняется до /new."
                + "\n«Сбросить контекст» удаляет текущую сессию.";
        String problem = settings.validationProblem();
        if (!problem.isBlank()) {
            text += "\n\nТребуется выбрать настройки:\n" + problem;
        }
        return new Screen((notice.isBlank() ? "" : notice + "\n\n") + text, mainButtons());
    }

    public Screen options(String kind, int page) {
        if (kind.equals("reasoning") && !settings.allowedModels().contains(settings.current().model())) {
            return main("Сначала выберите актуальную модель через /model.");
        }
        Choices choices = choices(kind);
        int lastPage = Math.max(0, (choices.values().size() - 1) / PAGE_SIZE);
        int currentPage = Math.clamp(page, 0, lastPage);
        int start = currentPage * PAGE_SIZE;
        List<List<Button>> rows = new ArrayList<>();
        for (int index = start; index < Math.min(start + PAGE_SIZE, choices.values().size()); index += 2) {
            List<Button> row = new ArrayList<>();
            for (int i = index; i < Math.min(index + 2, Math.min(start + PAGE_SIZE, choices.values().size())); i++) {
                String value = choices.values().get(i);
                row.add(new Button((value.equals(choices.selected()) ? "✓ " : "") + label(kind, value),
                        "choose:" + kind + ":" + choices.signature() + ":" + i));
            }
            rows.add(row);
        }
        List<Button> navigation = new ArrayList<>();
        if (currentPage > 0) {
            navigation.add(new Button("‹ Назад", "page:" + kind + ":" + choices.signature() + ":" + (currentPage - 1)));
        }
        if (currentPage < lastPage) {
            navigation.add(new Button("Далее ›", "page:" + kind + ":" + choices.signature() + ":" + (currentPage + 1)));
        }
        if (!navigation.isEmpty()) {
            rows.add(navigation);
        }
        rows.add(List.of(new Button("‹ Меню", "menu")));
        String description = switch (kind) {
            case "model" -> "«Как в CLI» использует конфигурацию Codex. Доступ зависит от аккаунта."
                    + "\nПрофиль каталога: " + properties.codex().authMode()
                    + ". Несовместимый reasoning при смене модели сбрасывается на default.";
            case "reasoning" -> "Модель: " + settings.current().model()
                    + "\nПоказаны только совместимые уровни. Более высокий уровень может требовать больше времени и квоты."
                    + (settings.current().usesDefaultModel() ? "\nДля ручного уровня сначала выберите конкретную модель." : "");
            case "mode" -> "Чтение — просмотр данных. Рабочий каталог — запись в его пределах."
                    + "\nПолный доступ — без sandbox и подтверждений Codex.";
            default -> throw new IllegalArgumentException("Неизвестное меню.");
        };
        String title = switch (kind) {
            case "model" -> "Модель";
            case "reasoning" -> "Уровень reasoning";
            case "mode" -> "Режим доступа";
            default -> "";
        };
        return new Screen("**" + title + "**\n\n" + description
                + "\n\nИзменения применяются к следующим запросам."
                + (lastPage == 0 ? "" : "\nСтраница " + (currentPage + 1) + " из " + (lastPage + 1)), rows);
    }

    public String resolveChoice(String kind, String signature, int index) {
        Choices choices = choices(kind);
        if (!choices.signature().equals(signature) || index < 0 || index >= choices.values().size()) {
            throw new IllegalArgumentException("Меню устарело. Откройте настройки заново.");
        }
        return choices.values().get(index);
    }

    public boolean currentPage(String kind, String signature) {
        return choices(kind).signature().equals(signature);
    }

    public String summary(CodexSettings value) {
        return "**Модель:** " + (value.usesDefaultModel() ? "как в Codex CLI" : value.model())
                + "\n**Reasoning:** " + settings.reasoningDescription(value)
                + "\n**Доступ:** " + label("mode", value.accessMode());
    }

    public List<List<Button>> mainButtons() {
        return List.of(
                List.of(new Button("Модель", "models"), new Button("Reasoning", "reasoning")),
                List.of(new Button("Доступ", "modes"), new Button("Статус", "status")),
                List.of(new Button("Сбросить контекст", "new"), new Button("Помощь", "help")),
                List.of(new Button("Отменить текущую задачу", "cancel"))
        );
    }

    private Choices choices(String kind) {
        CodexSettings selected = settings.current();
        List<String> values = switch (kind) {
            case "model" -> settings.allowedModels();
            case "reasoning" -> settings.allowedReasoningEfforts();
            case "mode" -> settings.allowedAccessModes();
            default -> throw new IllegalArgumentException("Неизвестное меню.");
        };
        String value = switch (kind) {
            case "model" -> selected.model();
            case "reasoning" -> selected.reasoningEffort();
            case "mode" -> selected.accessMode();
            default -> "";
        };
        String context = kind.equals("reasoning") ? selected.model() : kind;
        return new Choices(values, value, fingerprint(context + "\n" + String.join("\n", values)));
    }

    private String label(String kind, String value) {
        if (kind.equals("mode")) {
            return switch (value) {
                case "read-only" -> "Только чтение";
                case "workspace" -> "Рабочий каталог";
                case "full-access" -> "Полный доступ";
                default -> value;
            };
        }
        return TelegramTextFormatter.clip(value.equals(CodexSettings.DEFAULT)
                ? (kind.equals("model") ? "Как в CLI" : "По умолчанию") : value, 40);
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Screen(String text, List<List<Button>> buttons) {
    }

    private record Choices(List<String> values, String selected, String signature) {
    }
}
