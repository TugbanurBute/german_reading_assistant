package com.germanreader.dto.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /api/v1/documents/{id}: only the title can change; the content never does. */
public record RenameDocumentRequest(
        @NotBlank @Size(max = DocumentLimits.MAX_TITLE_LENGTH) String title
) {
}
