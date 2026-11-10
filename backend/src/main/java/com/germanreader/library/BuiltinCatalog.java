package com.germanreader.library;

import com.germanreader.dto.document.DocumentLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BuiltinCatalog {

    static final String CATALOG = "library/catalog.json";

    public record BuiltinText(String key, CefrLevel level, String title, String description, String content) {
    }

    private record Entry(String key, CefrLevel level, String title, String description) {
    }

    private final List<BuiltinText> texts;

    public BuiltinCatalog(JsonMapper jsonMapper) {
        this.texts = load(jsonMapper);
    }

    public List<BuiltinText> texts() {
        return texts;
    }

    static List<BuiltinText> load(JsonMapper jsonMapper) {
        List<Entry> entries;
        try (InputStream in = new ClassPathResource(CATALOG).getInputStream()) {
            entries = jsonMapper.readValue(in, new TypeReference<List<Entry>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + CATALOG, e);
        }
        Set<String> keys = new HashSet<>();
        return entries.stream().map(entry -> {
            if (entry.key() == null || !entry.key().matches("[a-z0-9-]{1,80}") || !keys.add(entry.key())) {
                throw new IllegalStateException("Invalid or duplicate built-in text key: " + entry.key());
            }
            if (entry.level() == null || isBlank(entry.title()) || entry.title().length() > DocumentLimits.MAX_TITLE_LENGTH
                    || entry.description() == null || entry.description().length() > 300) {
                throw new IllegalStateException("Incomplete catalog entry: " + entry.key());
            }
            String content = read("library/" + entry.key() + ".txt");
            if (isBlank(content) || content.length() > DocumentLimits.MAX_CONTENT_LENGTH) {
                throw new IllegalStateException("Empty or too long built-in text: " + entry.key());
            }
            return new BuiltinText(entry.key(), entry.level(), entry.title().strip(), entry.description().strip(), content);
        }).toList();
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
