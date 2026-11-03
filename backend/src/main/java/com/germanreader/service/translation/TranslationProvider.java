package com.germanreader.service.translation;

import com.germanreader.dto.TranslationRequest;
import com.germanreader.dto.TranslationResponse;

public interface TranslationProvider {

    String cacheNamespace();

    String promptVersion();

    TranslationResponse translate(TranslationRequest request);
}
