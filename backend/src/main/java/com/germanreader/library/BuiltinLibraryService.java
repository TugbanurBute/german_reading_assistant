package com.germanreader.library;

import com.germanreader.exception.DocumentException;
import com.germanreader.exception.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BuiltinLibraryService {

    public record BuiltinTextSummary(UUID id, String title, CefrLevel level, String description, int wordCount,
                                     int readingMinutes) {
    }

    public record BuiltinTextResponse(UUID id, String title, CefrLevel level, String description, int wordCount,
                                      int readingMinutes, String content) {
    }

    private static final String COLUMNS = "id, title, cefr_level, description, word_count";

    private final JdbcTemplate jdbc;

    public BuiltinLibraryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BuiltinTextSummary> list(CefrLevel level) {
        String sql = "SELECT " + COLUMNS + " FROM reading_document WHERE builtin_key IS NOT NULL"
                + (level == null ? "" : " AND cefr_level = ?") + " ORDER BY cefr_level, builtin_key";
        Object[] args = level == null ? new Object[0] : new Object[] {level.name()};
        return jdbc.query(sql, (row, i) -> summary(row), args);
    }

    public BuiltinTextResponse get(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + ", content FROM reading_document WHERE id = ? AND builtin_key IS NOT NULL",
                        (row, i) -> {
                            BuiltinTextSummary s = summary(row);
                            return new BuiltinTextResponse(s.id(), s.title(), s.level(), s.description(), s.wordCount(),
                                    s.readingMinutes(), row.getString("content"));
                        }, id)
                .stream().findFirst()
                .orElseThrow(() -> new DocumentException(ErrorCode.DOCUMENT_NOT_FOUND, "Built-in text " + id + " not found"));
    }

    private static BuiltinTextSummary summary(ResultSet row) throws SQLException {
        CefrLevel level = CefrLevel.valueOf(row.getString("cefr_level"));
        int words = row.getInt("word_count");
        return new BuiltinTextSummary(row.getObject("id", UUID.class), row.getString("title"), level,
                row.getString("description"), words, level.readingMinutes(words));
    }
}
