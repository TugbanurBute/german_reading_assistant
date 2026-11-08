package com.germanreader.processing;

import java.util.UUID;

public record DocumentProcessingRequested(UUID documentId, int version) {
}
