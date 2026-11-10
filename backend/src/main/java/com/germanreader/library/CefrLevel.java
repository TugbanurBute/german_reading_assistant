package com.germanreader.library;

public enum CefrLevel {
    A1(60),
    A2(80),
    B1(100);

    private final int wordsPerMinute;

    CefrLevel(int wordsPerMinute) {
        this.wordsPerMinute = wordsPerMinute;
    }

    public int readingMinutes(int words) {
        return Math.max(1, Math.round((float) words / wordsPerMinute));
    }
}
