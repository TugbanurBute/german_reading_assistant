package com.germanreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.grammar.Article;
import com.germanreader.dto.vocabulary.SaveVocabularyRequest;
import com.germanreader.dto.vocabulary.VocabularyResponse;
import com.germanreader.entity.VocabularyEntry;
import com.germanreader.exception.ErrorCode;
import com.germanreader.exception.VocabularyException;
import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.repository.VocabularyEntryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class VocabularyServiceTest {

    private static final UUID OWNER = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID DOCUMENT_ID = UUID.fromString("0b6f1c1e-3c55-4c43-9a43-6f1f3c1b2a10");
    private static final UUID ENTRY_ID = UUID.fromString("5b8ae4f2-8647-404b-a675-4d1a2b3c4d5e");
    private static final String SENTENCE = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";

    private final VocabularyEntryRepository repository = mock(VocabularyEntryRepository.class);
    private final ReadingDocumentRepository documentRepository = mock(ReadingDocumentRepository.class);
    private final VocabularyService service = new VocabularyService(repository, documentRepository);

    private static final VocabularyResponse STORED = new VocabularyResponse(ENTRY_ID, "Bank", "Bank", Article.DIE,
            PartOfSpeech.NOUN, "bench", TargetLanguage.EN, SENTENCE, DOCUMENT_ID, "Im Park", Instant.EPOCH);

    @BeforeEach
    void repositoryDefaults() {
        given(repository.findMeaning(any(), any(), any(), any())).willReturn(Optional.empty());
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(repository.findResponseById(any(), any())).willReturn(Optional.of(STORED));
        given(documentRepository.existsByIdAndOwnerId(DOCUMENT_ID, OWNER)).willReturn(true);
    }

    private static SaveVocabularyRequest bench() {
        return new SaveVocabularyRequest("Bank", "Bank", Article.DIE, PartOfSpeech.NOUN, "bench", TargetLanguage.EN,
                SENTENCE, DOCUMENT_ID);
    }

    private VocabularyEntry savedEntry() {
        ArgumentCaptor<VocabularyEntry> captor = ArgumentCaptor.forClass(VocabularyEntry.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void savesANewMeaningWithAllFields() {
        VocabularyService.SaveResult result = service.save(OWNER, bench());

        assertThat(result.created()).isTrue();
        assertThat(result.entry()).isEqualTo(STORED);
        VocabularyEntry saved = savedEntry();
        assertThat(saved.getLemma()).isEqualTo("Bank");
        assertThat(saved.getArticle()).isEqualTo(Article.DIE);
        assertThat(saved.getPartOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
        assertThat(saved.getTranslation()).isEqualTo("bench");
        assertThat(saved.getTargetLanguage()).isEqualTo(TargetLanguage.EN);
        assertThat(saved.getSourceSentence()).isEqualTo(SENTENCE);
        assertThat(saved.getSourceDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void normalizesWhitespaceAndUsesTheWordWhenTheLemmaIsMissing() {
        service.save(OWNER, new SaveVocabularyRequest("  schnell ", null, null, null, " quickly\n", TargetLanguage.EN,
                "  Er läuft\n schnell. ", null));

        VocabularyEntry saved = savedEntry();
        assertThat(saved.getOriginalText()).isEqualTo("schnell");
        assertThat(saved.getLemma()).isEqualTo("schnell");
        assertThat(saved.getTranslation()).isEqualTo("quickly");
        assertThat(saved.getSourceSentence()).isEqualTo("Er läuft schnell.");
        assertThat(saved.getArticle()).isNull();
        assertThat(saved.getPartOfSpeech()).isNull();
        assertThat(saved.getSourceDocumentId()).isNull();
    }

    @Test
    void verbsAreSavedWithoutAnArticle() {
        service.save(OWNER, new SaveVocabularyRequest("abzuheben", "abheben", null, PartOfSpeech.VERB, "to withdraw",
                TargetLanguage.EN, "Danach geht sie zur Bank, um Geld abzuheben.", null));

        VocabularyEntry saved = savedEntry();
        assertThat(saved.getLemma()).isEqualTo("abheben");
        assertThat(saved.getPartOfSpeech()).isEqualTo(PartOfSpeech.VERB);
        assertThat(saved.getArticle()).isNull();
    }

    @Test
    void blankSentenceIsStoredAsNull() {
        service.save(OWNER, new SaveVocabularyRequest("Bank", "Bank", null, null, "bench", TargetLanguage.EN, "   ", null));

        assertThat(savedEntry().getSourceSentence()).isNull();
    }

    @Test
    void anAlreadySavedMeaningIsReturnedWithoutInserting() {
        given(repository.findMeaning(OWNER, "Bank", TargetLanguage.EN, "bench")).willReturn(Optional.of(ENTRY_ID));

        VocabularyService.SaveResult result = service.save(OWNER, bench());

        assertThat(result.created()).isFalse();
        assertThat(result.entry()).isEqualTo(STORED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aDeletedSourceDocumentIsNotLinked() {
        given(documentRepository.existsByIdAndOwnerId(DOCUMENT_ID, OWNER)).willReturn(false);

        service.save(OWNER, bench());

        assertThat(savedEntry().getSourceDocumentId()).isNull();
    }

    @Test
    void aConcurrentSaveOfTheSameMeaningReturnsTheOtherEntry() {
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("unique"));
        given(repository.findMeaning(OWNER, "Bank", TargetLanguage.EN, "bench"))
                .willReturn(Optional.empty(), Optional.of(ENTRY_ID));

        VocabularyService.SaveResult result = service.save(OWNER, bench());

        assertThat(result.created()).isFalse();
        assertThat(result.entry().id()).isEqualTo(ENTRY_ID);
    }

    @Test
    void aSourceDocumentDeletedDuringTheInsertIsDroppedAndTheWordStillSaved() {
        given(repository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("foreign key"))
                .willAnswer(invocation -> invocation.getArgument(0));

        VocabularyService.SaveResult result = service.save(OWNER, bench());

        assertThat(result.created()).isTrue();
        ArgumentCaptor<VocabularyEntry> captor = ArgumentCaptor.forClass(VocabularyEntry.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        List<VocabularyEntry> attempts = captor.getAllValues();
        assertThat(attempts.get(0).getSourceDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(attempts.get(1).getSourceDocumentId()).isNull();
        assertThat(attempts.get(1).getTranslation()).isEqualTo("bench");
    }

    @Test
    void anUnexplainedIntegrityErrorIsNotHidden() {
        given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("check"));

        SaveVocabularyRequest unlinked = new SaveVocabularyRequest("Bank", null, null, null, "bench",
                TargetLanguage.EN, null, null);
        assertThatThrownBy(() -> service.save(OWNER, unlinked)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listReturnsTheRepositoryResult() {
        given(repository.findAllResponses(OWNER)).willReturn(List.of(STORED));

        assertThat(service.list(OWNER)).containsExactly(STORED);
    }

    @Test
    void deleteRemovesTheEntry() {
        given(repository.deleteEntryById(ENTRY_ID, OWNER)).willReturn(1);

        service.delete(ENTRY_ID, OWNER);

        verify(repository).deleteEntryById(ENTRY_ID, OWNER);
    }

    @Test
    void deletingAMissingEntryIsNotFound() {
        given(repository.deleteEntryById(ENTRY_ID, OWNER)).willReturn(0);

        assertThatThrownBy(() -> service.delete(ENTRY_ID, OWNER))
                .isInstanceOfSatisfying(VocabularyException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOCABULARY_NOT_FOUND));
    }
}
