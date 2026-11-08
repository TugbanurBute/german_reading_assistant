package com.germanreader.processing;

import java.util.Arrays;
import java.util.regex.Pattern;

public record DocumentStats(int words, int characters, int paragraphs) {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    public static DocumentStats of(String content) {
        int words = (int) WORD.matcher(content).results().count();
        int characters = content.codePointCount(0, content.length());
        int paragraphs = (int) Arrays.stream(PARAGRAPH_BREAK.split(content)).filter(p -> !p.isBlank()).count();
        return new DocumentStats(words, characters, paragraphs);
    }
}
