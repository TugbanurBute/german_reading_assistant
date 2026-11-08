package com.germanreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.dto.PartOfSpeech;
import com.germanreader.dto.TargetLanguage;
import com.germanreader.dto.grammar.Article;
import com.germanreader.entity.ReadingDocument;
import com.germanreader.entity.VocabularyEntry;
import com.germanreader.repository.ReadingDocumentRepository;
import com.germanreader.repository.VocabularyEntryRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.germanreader.security.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "translation.provider=mock",
        "spring.flyway.schemas=" + TranslationCacheIT.SCHEMA,
        "spring.datasource.hikari.schema=" + TranslationCacheIT.SCHEMA
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestAuth.AsDefaultUser.class)
class VocabularyIT {

    private static final String URL = "/api/v1/vocabulary";
    private static final String PARK = "Am Morgen sitzt meine Großmutter auf der Bank im Park.";
    private static final String MONEY = "Danach geht sie zur Bank, um Geld abzuheben.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VocabularyEntryRepository repository;

    @Autowired
    private ReadingDocumentRepository documentRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void emptyTables() {
        TestAuth.ensureUser(jdbc, TestAuth.USER_ID);
        repository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
    }

    private static Map<String, Object> word(String originalText, String lemma, String translation, String sentence) {
        Map<String, Object> body = new HashMap<>();
        body.put("originalText", originalText);
        body.put("lemma", lemma);
        body.put("translation", translation);
        body.put("targetLanguage", "EN");
        body.put("sourceSentence", sentence);
        return body;
    }

    private static Map<String, Object> bankAsBench() {
        Map<String, Object> body = word("Bank", "Bank", "bench", PARK);
        body.put("article", "die");
        body.put("partOfSpeech", "NOUN");
        return body;
    }

    private ResultActions save(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)));
    }

    private UUID idOf(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(jsonMapper.readTree(json).get("id").asString());
    }

    @Test
    void savedWordIsStoredWithAllFields() throws Exception {
        ReadingDocument document = documentRepository.save(new ReadingDocument(TestAuth.USER_ID, "Im Park", PARK));
        Map<String, Object> body = bankAsBench();
        body.put("sourceDocumentId", document.getId().toString());

        UUID id = idOf(save(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.article").value("die"))
                .andExpect(jsonPath("$.sourceDocumentTitle").value("Im Park")));

        VocabularyEntry row = repository.findById(id).orElseThrow();
        assertThat(row.getLemma()).isEqualTo("Bank");
        assertThat(row.getArticle()).isEqualTo(Article.DIE);
        assertThat(row.getPartOfSpeech()).isEqualTo(PartOfSpeech.NOUN);
        assertThat(row.getTranslation()).isEqualTo("bench");
        assertThat(row.getTargetLanguage()).isEqualTo(TargetLanguage.EN);
        assertThat(row.getSourceSentence()).isEqualTo(PARK);
        assertThat(row.getSourceDocumentId()).isEqualTo(document.getId());
    }

    @Test
    void wordsWithoutGrammarFieldsAreSaved() throws Exception {
        save(word("abzuheben", "abheben", "to withdraw", MONEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lemma").value("abheben"))
                .andExpect(jsonPath("$.article").isEmpty())
                .andExpect(jsonPath("$.partOfSpeech").isEmpty());

        save(word("schnell", null, "quickly", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lemma").value("schnell"))
                .andExpect(jsonPath("$.sourceSentence").isEmpty());

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void savingTheSameMeaningAgainReturnsTheExistingEntry() throws Exception {
        UUID first = idOf(save(bankAsBench()).andExpect(status().isCreated()));

        // Same meaning from another sentence, with different capitalisation: no second row.
        Map<String, Object> again = word("Bänke", "bank", "Bench", "Die Bänke im Park sind neu.");
        UUID second = idOf(save(again)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceSentence").value(PARK)));

        assertThat(second).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void theSameLemmaWithDifferentMeaningsIsKeptApart() throws Exception {
        save(bankAsBench()).andExpect(status().isCreated());
        save(word("Bank", "Bank", "bank", MONEY)).andExpect(status().isCreated());
        Map<String, Object> turkish = word("Bank", "Bank", "bench", PARK);
        turkish.put("targetLanguage", "TR");
        save(turkish).andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void theDatabaseRejectsADuplicateMeaningEvenWithoutTheService() {
        repository.saveAndFlush(new VocabularyEntry(TestAuth.USER_ID, "Bank", "Bank", null, null, "bench", TargetLanguage.EN, null, null));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new VocabularyEntry(TestAuth.USER_ID, "Bank", "BANK", null, null, "BENCH", TargetLanguage.EN, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listIsNewestFirst() throws Exception {
        save(bankAsBench()).andExpect(status().isCreated());
        save(word("Geld", "Geld", "money", MONEY)).andExpect(status().isCreated());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].lemma").value("Geld"))
                .andExpect(jsonPath("$[1].lemma").value("Bank"));
    }

    @Test
    void deleteRemovesTheEntryAndASecondDeleteIsNotFound() throws Exception {
        UUID id = idOf(save(bankAsBench()));

        mockMvc.perform(delete(URL + "/" + id)).andExpect(status().isNoContent());
        assertThat(repository.existsById(id)).isFalse();

        mockMvc.perform(delete(URL + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOCABULARY_NOT_FOUND"));
    }

    @Test
    void deletingTheSourceTextKeepsTheWordAndClearsTheLink() throws Exception {
        ReadingDocument document = documentRepository.save(new ReadingDocument(TestAuth.USER_ID, "Im Park", PARK));
        Map<String, Object> body = bankAsBench();
        body.put("sourceDocumentId", document.getId().toString());
        UUID id = idOf(save(body));

        mockMvc.perform(delete("/api/v1/documents/" + document.getId())).andExpect(status().isNoContent());

        assertThat(repository.findById(id).orElseThrow().getSourceDocumentId()).isNull();
        mockMvc.perform(get(URL))
                .andExpect(jsonPath("$[0].lemma").value("Bank"))
                .andExpect(jsonPath("$[0].sourceSentence").value(PARK))
                .andExpect(jsonPath("$[0].sourceDocumentId").isEmpty())
                .andExpect(jsonPath("$[0].sourceDocumentTitle").isEmpty());
    }

    @Test
    void aWordFromAnAlreadyDeletedTextIsSavedWithoutTheLink() throws Exception {
        Map<String, Object> body = bankAsBench();
        body.put("sourceDocumentId", UUID.randomUUID().toString());

        UUID id = idOf(save(body).andExpect(status().isCreated()).andExpect(jsonPath("$.sourceDocumentId").isEmpty()));

        assertThat(repository.findById(id).orElseThrow().getSourceDocumentId()).isNull();
    }

    @Test
    void invalidWordsAreNotStored() throws Exception {
        save(word(" ", null, "bench", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        save(word("Bank", null, "x".repeat(301), null))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }
}
