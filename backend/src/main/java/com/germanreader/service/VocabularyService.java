package com.germanreader.service;

import com.germanreader.dto.vocabulary.SaveVocabularyRequest;
import com.germanreader.dto.vocabulary.VocabularyResponse;
import com.germanreader.entity.VocabularyEntry;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.VocabularyException;
import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.repository.VocabularyEntryRepository;
import com.germanreader.util.TextNormalizer;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class VocabularyService {

    /** The saved entry, and whether this call created it (false: the meaning was already saved). */
    public record SaveResult(VocabularyResponse entry, boolean created) {
    }

    private final VocabularyEntryRepository repository;
    private final ReadingDocumentRepository documentRepository;

    public VocabularyService(VocabularyEntryRepository repository, ReadingDocumentRepository documentRepository) {
        this.repository = repository;
        this.documentRepository = documentRepository;
    }

    /**
     * Saves one meaning of a word. A meaning is lemma + translation + target language, ignoring case, so
     * "Bank / bench" and "Bank / bank" are two entries, while saving "Bank / bench" again (from any sentence)
     * returns the existing entry unchanged.
     *
     * <p>Not transactional: each repository call runs in its own transaction. A failed INSERT aborts its
     * PostgreSQL transaction, and the follow-up lookup after a race must still be able to run.
     */
    public SaveResult save(UUID ownerId, SaveVocabularyRequest request) {
        String originalText = TextNormalizer.normalize(request.originalText());
        String lemma = isBlank(request.lemma()) ? originalText : TextNormalizer.normalize(request.lemma());
        String translation = TextNormalizer.normalize(request.translation());
        String sentence = isBlank(request.sourceSentence()) ? null : TextNormalizer.normalize(request.sourceSentence());

        var existing = repository.findMeaning(ownerId, lemma, request.targetLanguage(), translation);
        if (existing.isPresent()) {
            return new SaveResult(get(existing.get(), ownerId), false);
        }

        // Only the owner's own texts can be linked. A word read in a text that was deleted meanwhile (or an id
        // of someone else's text) is still saved, just without the link.
        UUID documentId = request.sourceDocumentId();
        if (documentId != null && !documentRepository.existsByIdAndOwnerId(documentId, ownerId)) {
            documentId = null;
        }

        VocabularyEntry entry = new VocabularyEntry(ownerId, originalText, lemma, request.article(), request.partOfSpeech(),
                translation, request.targetLanguage(), sentence, documentId);
        try {
            UUID id = repository.saveAndFlush(entry).getId();
            log.debug("Saved vocabulary entry {}", id);
            return new SaveResult(get(id, ownerId), true);
        } catch (DataIntegrityViolationException e) {
            return afterFailedInsert(entry, e);
        }
    }

    /**
     * Two rare races: the same meaning was saved at the same moment (unique index), or the source text was
     * deleted between the check above and the insert (foreign key).
     */
    private SaveResult afterFailedInsert(VocabularyEntry entry, DataIntegrityViolationException failure) {
        var saved = repository.findMeaning(entry.getOwnerId(), entry.getLemma(), entry.getTargetLanguage(), entry.getTranslation());
        if (saved.isPresent()) {
            return new SaveResult(get(saved.get(), entry.getOwnerId()), false);
        }
        if (entry.getSourceDocumentId() == null) {
            throw failure;
        }
        VocabularyEntry withoutLink = new VocabularyEntry(entry.getOwnerId(), entry.getOriginalText(), entry.getLemma(), entry.getArticle(),
                entry.getPartOfSpeech(), entry.getTranslation(), entry.getTargetLanguage(), entry.getSourceSentence(), null);
        return new SaveResult(get(repository.saveAndFlush(withoutLink).getId(), entry.getOwnerId()), true);
    }

    @Transactional(readOnly = true)
    public List<VocabularyResponse> list(UUID ownerId) {
        return repository.findAllResponses(ownerId);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        if (repository.deleteEntryById(id, ownerId) == 0) {
            throw new VocabularyException(ErrorCode.VOCABULARY_NOT_FOUND, "Vocabulary entry " + id + " not found");
        }
        log.debug("Deleted vocabulary entry {}", id);
    }

    private VocabularyResponse get(UUID id, UUID ownerId) {
        return repository.findResponseById(id, ownerId).orElseThrow(
                () -> new VocabularyException(ErrorCode.VOCABULARY_NOT_FOUND, "Vocabulary entry " + id + " not found"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
