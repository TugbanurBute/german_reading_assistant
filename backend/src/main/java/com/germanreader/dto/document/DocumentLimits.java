package com.germanreader.dto.document;

public final class DocumentLimits {

    public static final int MAX_TITLE_LENGTH = 200;

    /** Characters. Larger texts make the reader slow, since every word becomes a button. */
    public static final int MAX_CONTENT_LENGTH = 100_000;

    private DocumentLimits() {
    }
}
