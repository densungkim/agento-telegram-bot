package com.agento.bot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TelegramTextFormatter {

    private static final Pattern BLOCK = Pattern.compile("(?s)\\x60{3}([a-zA-Z0-9_+.#-]*)[ \\t]*\\r?\\n(.*?)\\x60{3}");
    private static final Pattern INLINE = Pattern.compile("\\x60([^\\x60\\r\\n]+)\\x60|\\*\\*([^*\\r\\n]+)\\*\\*");

    private TelegramTextFormatter() {
    }

    static String html(String text) {
        Matcher blocks = BLOCK.matcher(text);
        StringBuilder result = new StringBuilder();
        int position = 0;
        while (blocks.find()) {
            result.append(inline(text.substring(position, blocks.start())));
            result.append("<pre><code");
            if (!blocks.group(1).isEmpty()) {
                result.append(" class=\"language-").append(blocks.group(1)).append("\"");
            }
            result.append(">").append(escape(blocks.group(2))).append("</code></pre>");
            position = blocks.end();
        }
        return result.append(inline(text.substring(position))).toString();
    }

    static String clip(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = Math.max(0, limit - 1);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static String inline(String text) {
        Matcher matches = INLINE.matcher(text);
        StringBuilder result = new StringBuilder();
        int position = 0;
        while (matches.find()) {
            result.append(escape(text.substring(position, matches.start())));
            String value = matches.group(1) == null ? matches.group(2) : matches.group(1);
            String tag = matches.group(1) == null ? "b" : "code";
            result.append('<').append(tag).append('>').append(escape(value))
                    .append("</").append(tag).append('>');
            position = matches.end();
        }
        return result.append(escape(text.substring(position))).toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
