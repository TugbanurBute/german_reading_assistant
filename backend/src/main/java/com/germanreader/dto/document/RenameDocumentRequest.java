package com.germanreader.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameDocumentRequest(
        @NotBlank @Size(max = DocumentLimits.MAX_TITLE_LENGTH) String title
) {
}
