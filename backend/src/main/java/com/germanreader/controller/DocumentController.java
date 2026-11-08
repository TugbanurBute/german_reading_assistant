package com.germanreader.controller;

import com.germanreader.dto.document.CreateDocumentRequest;
import com.germanreader.dto.document.DocumentResponse;
import com.germanreader.dto.document.DocumentSummary;
import com.germanreader.dto.document.RenameDocumentRequest;
import com.germanreader.security.AuthTokens;
import com.germanreader.service.DocumentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateDocumentRequest request) {
        return created(documentService.create(AuthTokens.userId(jwt), request.title(), request.content()));
    }

    /** Not required here, so a missing file gets the same INVALID_UPLOAD answer as an empty one. */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestPart(name = "file", required = false) MultipartFile file) {
        return created(documentService.upload(AuthTokens.userId(jwt), file));
    }

    @GetMapping
    public List<DocumentSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return documentService.list(AuthTokens.userId(jwt));
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return documentService.get(id, AuthTokens.userId(jwt));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DocumentSummary rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody RenameDocumentRequest request) {
        return documentService.rename(id, AuthTokens.userId(jwt), request.title());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        documentService.delete(id, AuthTokens.userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<DocumentResponse> created(DocumentResponse document) {
        return ResponseEntity.created(URI.create("/api/v1/documents/" + document.id())).body(document);
    }
}
