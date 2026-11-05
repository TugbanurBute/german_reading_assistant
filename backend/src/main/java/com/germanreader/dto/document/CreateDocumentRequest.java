package com.germanreader.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(
        @NotBlank
        @Size(max = DocumentLimits.MAX_TITLE_LENGTH)
        String title,

        @NotBlank
        @Size(max = DocumentLimits.MAX_CONTENT_LENGTH)
        String content
) {
}
