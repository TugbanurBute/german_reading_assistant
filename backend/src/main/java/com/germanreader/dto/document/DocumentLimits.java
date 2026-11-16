package com.germanreader.dto.document;

public final class DocumentLimits {

    public static final int MAX_TITLE_LENGTH = 200;

    public static final int MAX_CONTENT_LENGTH = 100_000;

    public static final long MAX_TXT_UPLOAD_BYTES = 512 * 1024;
    public static final long MAX_PDF_UPLOAD_BYTES = 5 * 1024 * 1024;

    private DocumentLimits() {
    }
}
