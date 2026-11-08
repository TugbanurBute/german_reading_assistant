package com.germanreader.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.germanreader.dto.document.DocumentLimits;
import com.germanreader.dto.document.DocumentResponse;
import com.germanreader.dto.document.DocumentSummary;
import com.germanreader.exception.DocumentException;
import com.germanreader.entity.ProcessingStatus;
import com.germanreader.exception.ErrorCode;
import com.germanreader.service.DocumentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.germanreader.security.TestAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@WebMvcTest(controllers = DocumentController.class,
        properties = {"app.cors.allowed-origins=http://localhost:5173", "app.auth.allow-generated-secret=true"})
@Import(TestAuth.WebSecurity.class)
class DocumentControllerTest {

    private static final String URL = "/api/v1/documents";
    private static final UUID ID = UUID.fromString("0b6f1c1e-3c55-4c43-9a43-6f1f3c1b2a10");
    private static final Instant CREATED = Instant.parse("2026-11-04T10:15:30Z");
    private static final DocumentResponse DOCUMENT =
            new DocumentResponse(ID, "Im Park", "Am Morgen sitzt meine Großmutter auf der Bank.", CREATED, CREATED,
                    ProcessingStatus.PENDING, null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void createReturns201WithLocationAndTheDocument() throws Exception {
        given(documentService.create(TestAuth.USER_ID, "Im Park", DOCUMENT.content())).willReturn(DOCUMENT);

        postJson("""
                {"title": "Im Park", "content": "Am Morgen sitzt meine Großmutter auf der Bank."}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, URL + "/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.title").value("Im Park"))
                .andExpect(jsonPath("$.content").value(DOCUMENT.content()))
                .andExpect(jsonPath("$.createdAt").value("2026-11-04T10:15:30Z"));
    }

    @Test
    void createRejectsBlankTitleAndContent() throws Exception {
        postJson("""
                {"title": "  ", "content": ""}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2));

        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(documentService);
    }

    @Test
    void createRejectsTooLongTitleAndContent() throws Exception {
        String title = "t".repeat(DocumentLimits.MAX_TITLE_LENGTH + 1);
        String content = "c".repeat(DocumentLimits.MAX_CONTENT_LENGTH + 1);

        postJson("""
                {"title": "%s", "content": "%s"}
                """.formatted(title, content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'content')]").exists());

        verifyNoInteractions(documentService);
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        postJson("{\"title\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void listReturnsMetadataWithoutContent() throws Exception {
        given(documentService.list(TestAuth.USER_ID)).willReturn(List.of(new DocumentSummary(ID, "Im Park", CREATED, CREATED,
                ProcessingStatus.COMPLETED, 9, 47, 1)));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()))
                .andExpect(jsonPath("$[0].title").value("Im Park"))
                .andExpect(jsonPath("$[0].content").doesNotExist())
                .andExpect(jsonPath("$[0].processingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].wordCount").value(9))
                .andExpect(jsonPath("$[0].paragraphCount").value(1));
    }

    @Test
    void getReturnsTheDocument() throws Exception {
        given(documentService.get(ID, TestAuth.USER_ID)).willReturn(DOCUMENT);

        mockMvc.perform(get(URL + "/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(DOCUMENT.content()));
    }

    @Test
    void getAndDeleteReturn404ForAMissingDocument() throws Exception {
        given(documentService.get(ID, TestAuth.USER_ID)).willThrow(new DocumentException(ErrorCode.DOCUMENT_NOT_FOUND, "No document"));
        willThrow(new DocumentException(ErrorCode.DOCUMENT_NOT_FOUND, "No document")).given(documentService).delete(ID, TestAuth.USER_ID);

        mockMvc.perform(get(URL + "/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(delete(URL + "/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        mockMvc.perform(get(URL + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
        verifyNoInteractions(documentService);
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete(URL + "/" + ID)).andExpect(status().isNoContent());
        verify(documentService).delete(ID, TestAuth.USER_ID);
    }

    @Test
    void uploadReturns201() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "park.txt", "text/plain", "Text.".getBytes());
        given(documentService.upload(any(), any())).willReturn(DOCUMENT);

        mockMvc.perform(multipart(URL + "/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, URL + "/" + ID))
                .andExpect(jsonPath("$.title").value("Im Park"));
    }

    @Test
    void invalidUploadReturns400WithoutInternalDetails() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bild.txt", "text/plain", new byte[]{0});
        given(documentService.upload(any(), any()))
                .willThrow(new DocumentException(ErrorCode.INVALID_UPLOAD, "The file contains control characters"));

        mockMvc.perform(multipart(URL + "/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"))
                .andExpect(jsonPath("$.detail").value(ErrorCode.INVALID_UPLOAD.message()));
    }

    @Test
    void tooLargeUploadsReturn413() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lang.txt", "text/plain", "Text.".getBytes());
        given(documentService.upload(any(), any())).willThrow(new MaxUploadSizeExceededException(512 * 1024));

        mockMvc.perform(multipart(URL + "/upload").file(file))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
    }

    @Test
    void uploadingJsonInsteadOfAFileIsRejected() throws Exception {
        mockMvc.perform(post(URL + "/upload").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(documentService);
    }

    private ResultActions patchJson(String body) throws Exception {
        return mockMvc.perform(patch(URL + "/" + ID).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void renameReturnsTheUpdatedSummaryWithoutContent() throws Exception {
        Instant renamed = CREATED.plusSeconds(60);
        given(documentService.rename(ID, TestAuth.USER_ID, "Im Garten")).willReturn(new DocumentSummary(ID, "Im Garten", CREATED, renamed,
                ProcessingStatus.PENDING, null, null, null));

        patchJson("{\"title\": \"Im Garten\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.title").value("Im Garten"))
                .andExpect(jsonPath("$.updatedAt").value(renamed.toString()))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void renameRejectsABlankOrMissingTitle() throws Exception {
        patchJson("{\"title\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
        patchJson("{}").andExpect(status().isBadRequest());
        verifyNoInteractions(documentService);
    }

    @Test
    void renameRejectsATooLongTitle() throws Exception {
        patchJson("{\"title\": \"" + "a".repeat(DocumentLimits.MAX_TITLE_LENGTH + 1) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(documentService);
    }

    @Test
    void renamingAMissingDocumentReturns404() throws Exception {
        given(documentService.rename(any(), any(), any()))
                .willThrow(new DocumentException(ErrorCode.DOCUMENT_NOT_FOUND, "No document"));

        patchJson("{\"title\": \"Neu\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void renameWithANonUuidIdIsARequestError() throws Exception {
        mockMvc.perform(patch(URL + "/not-a-uuid").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"Neu\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }

    @Test
    void corsAllowsPatchFromTheFrontend() throws Exception {
        mockMvc.perform(options(URL + "/" + ID)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void corsAllowsDeleteFromTheFrontend() throws Exception {
        mockMvc.perform(options(URL + "/" + ID)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        verifyNoInteractions(documentService);
    }
}
