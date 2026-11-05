package com.germanreader.controller;

import com.germanreader.dto.vocabulary.SaveVocabularyRequest;
import com.germanreader.dto.vocabulary.VocabularyResponse;
import com.germanreader.service.VocabularyService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyController {

    private final VocabularyService vocabularyService;

    public VocabularyController(VocabularyService vocabularyService) {
        this.vocabularyService = vocabularyService;
    }

    /** 201 for a new entry; 200 with the existing entry when this meaning is already saved. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VocabularyResponse> save(@Valid @RequestBody SaveVocabularyRequest request) {
        VocabularyService.SaveResult result = vocabularyService.save(request);
        VocabularyResponse entry = result.entry();
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/vocabulary/" + entry.id())).body(entry)
                : ResponseEntity.ok(entry);
    }

    @GetMapping
    public List<VocabularyResponse> list() {
        return vocabularyService.list();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        vocabularyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
