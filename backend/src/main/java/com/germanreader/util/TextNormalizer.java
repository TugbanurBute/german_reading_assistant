package com.germanreader.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class TextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
        return WHITESPACE.matcher(composed).replaceAll(" ").strip();
    }
}
