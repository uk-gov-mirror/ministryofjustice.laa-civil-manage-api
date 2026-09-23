package uk.gov.justice.laa_civil_manage_api.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.justice.laa_civil_manage_api.config.SecurityConfig;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityApplicationResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDocumentType;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDocumentTypeUpdateResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDraft;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityType;
import uk.gov.justice.laa_civil_manage_api.models.UploadedDocument;
import uk.gov.justice.laa_civil_manage_api.services.PriorAuthorityService;

@WebMvcTest(PriorAuthorityController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class PriorAuthorityControllerTest {

  private static final UUID PRIOR_AUTHORITY_ID =
      UUID.fromString("c3b07e24-d92b-410a-9d95-88f117a12b43");
  private static final String APPLICATION_ID = "2a28f60d-fe15-43fe-92c3-5530595d5f51";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PriorAuthorityService priorAuthorityService;

  private ResultActions postPriorAuthority(String body) throws Exception {
    return mockMvc.perform(
        post("/prior-authorities").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static String expertDraft(String applicationId) {
    return """
        {
          "applicationId": "%s",
          "priorAuthorityType": "EXPERT",
          "justification": "Required for comprehensive child behavioral assessment.",
          "expertDetails": {
            "expertType": "Psychologist",
            "expertFullName": "Dr John Doe",
            "expertPostcode": "SW1H 9AJ",
            "expertCosts": {
              "billingType": "FIXED_RATE",
              "totalAmount": 500.00,
              "costsSharedWithOtherParties": false
            }
          }
        }
        """
        .formatted(applicationId);
  }

  @Test
  void createDraftReturns201WithLocationAndPriorAuthorityId() throws Exception {
    when(priorAuthorityService.createDraft(any(PriorAuthorityDraft.class)))
        .thenReturn(PRIOR_AUTHORITY_ID);

    postPriorAuthority(expertDraft(APPLICATION_ID))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/prior-authorities/" + PRIOR_AUTHORITY_ID))
        .andExpect(jsonPath("$.priorAuthorityId").value(PRIOR_AUTHORITY_ID.toString()));
  }

  @Test
  void createDraftAcceptsPartiallyCompletedFormWithoutCrossFieldValidation() throws Exception {
    when(priorAuthorityService.createDraft(any(PriorAuthorityDraft.class)))
        .thenReturn(PRIOR_AUTHORITY_ID);

    String body =
        """
            {
              "applicationId": "%s",
              "priorAuthorityType": "EXPERT"
            }
            """
            .formatted(APPLICATION_ID);

    postPriorAuthority(body).andExpect(status().isCreated());
  }

  @Test
  void createDraftReturns400WhenApplicationIdMissing() throws Exception {
    String body =
        """
            {
              "priorAuthorityType": "COUNSEL"
            }
            """;

    postPriorAuthority(body).andExpect(status().isBadRequest());
  }

  @Test
  void updateDraftReturns204() throws Exception {
    String body =
        """
            {
              "applicationId": "%s",
              "justification": "Updated justification"
            }
            """
            .formatted(APPLICATION_ID);

    mockMvc
        .perform(
            put("/prior-authorities/{priorAuthorityId}", PRIOR_AUTHORITY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    verify(priorAuthorityService)
        .updateDraft(eq(PRIOR_AUTHORITY_ID), any(PriorAuthorityDraft.class));
  }

  @Test
  void getPriorAuthorityReturns200WithBody() throws Exception {
    PriorAuthorityDraft draft =
        PriorAuthorityDraft.builder()
            .applicationId(UUID.fromString(APPLICATION_ID))
            .priorAuthorityType(PriorAuthorityType.EXPERT)
            .justification("Required.")
            .build();
    when(priorAuthorityService.get(PRIOR_AUTHORITY_ID))
        .thenReturn(
            Optional.of(
                PriorAuthorityResponse.builder()
                    .priorAuthorityId(PRIOR_AUTHORITY_ID)
                    .status(null)
                    .draft(draft)
                    .uploadedDocuments(List.of())
                    .build()));

    mockMvc
        .perform(get("/prior-authorities/{priorAuthorityId}", PRIOR_AUTHORITY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priorAuthorityId").value(PRIOR_AUTHORITY_ID.toString()))
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.draft.priorAuthorityType").value("EXPERT"));
  }

  @Test
  void getPriorAuthorityReturns404WhenNotFound() throws Exception {
    when(priorAuthorityService.get(PRIOR_AUTHORITY_ID)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/prior-authorities/{priorAuthorityId}", PRIOR_AUTHORITY_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  void submitReturns201WithLocationAndBody() throws Exception {
    when(priorAuthorityService.submit(PRIOR_AUTHORITY_ID))
        .thenReturn(
            PriorAuthorityApplicationResponse.builder()
                .priorAuthorityId(PRIOR_AUTHORITY_ID)
                .submittedAt(OffsetDateTime.parse("2026-05-22T10:00:00Z"))
                .build());

    mockMvc
        .perform(post("/prior-authorities/{priorAuthorityId}/submit", PRIOR_AUTHORITY_ID))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/prior-authorities/" + PRIOR_AUTHORITY_ID))
        .andExpect(jsonPath("$.priorAuthorityId").value(PRIOR_AUTHORITY_ID.toString()));
  }

  @Test
  void uploadDocumentReturns200WithFileMetadata() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "pdf-content".getBytes());
    UUID documentId = UUID.randomUUID();
    OffsetDateTime uploadedAt = OffsetDateTime.parse("2026-05-22T10:00:00Z");

    when(priorAuthorityService.uploadDocument(eq(PRIOR_AUTHORITY_ID), any()))
        .thenReturn(
            UploadedDocument.builder()
                .documentId(documentId)
                .fileName("evidence.pdf")
                .size(11L)
                .uploadedAt(uploadedAt)
                .build());

    mockMvc
        .perform(
            multipart("/prior-authorities/{priorAuthorityId}/documents", PRIOR_AUTHORITY_ID)
                .file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.fileName").value("evidence.pdf"))
        .andExpect(jsonPath("$.size").value(11))
        .andExpect(jsonPath("$.uploadedAt").value("2026-05-22T10:00:00Z"));
  }

  @Test
  void uploadDocumentReturns400WhenFileIsEmpty() throws Exception {
    MockMultipartFile emptyFile =
        new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

    when(priorAuthorityService.uploadDocument(eq(PRIOR_AUTHORITY_ID), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty"));

    mockMvc
        .perform(
            multipart("/prior-authorities/{priorAuthorityId}/documents", PRIOR_AUTHORITY_ID)
                .file(emptyFile))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uploadDocumentReturns415WhenFileTypeIsNotAllowed() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "script.js", "application/javascript", "alert(1)".getBytes());

    when(priorAuthorityService.uploadDocument(eq(PRIOR_AUTHORITY_ID), any()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported file type; allowed: PDF"));

    mockMvc
        .perform(
            multipart("/prior-authorities/{priorAuthorityId}/documents", PRIOR_AUTHORITY_ID)
                .file(file))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void uploadDocumentReturns413WhenFileExceeds10Mb() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "large.pdf", "application/pdf", new byte[(10 * 1024 * 1024) + 1]);

    when(priorAuthorityService.uploadDocument(eq(PRIOR_AUTHORITY_ID), any()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.CONTENT_TOO_LARGE, "file size must not exceed 10MB"));

    mockMvc
        .perform(
            multipart("/prior-authorities/{priorAuthorityId}/documents", PRIOR_AUTHORITY_ID)
                .file(file))
        .andExpect(status().isContentTooLarge());
  }

  @Test
  void updateDocumentTypeReturns200WithUpdatedResponse() throws Exception {
    UUID documentId = UUID.randomUUID();
    OffsetDateTime updatedAt = OffsetDateTime.parse("2026-05-22T10:00:00Z");

    when(priorAuthorityService.updateDocumentType(
            PRIOR_AUTHORITY_ID, documentId, PriorAuthorityDocumentType.GATEWAY_EVIDENCE))
        .thenReturn(
            PriorAuthorityDocumentTypeUpdateResponse.builder()
                .documentId(documentId)
                .updatedAt(updatedAt)
                .build());

    String body =
        """
            {
              "documentType": "GATEWAY_EVIDENCE"
            }
            """;

    mockMvc
        .perform(
            patch(
                    "/prior-authorities/{priorAuthorityId}/documents/{documentId}",
                    PRIOR_AUTHORITY_ID,
                    documentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.updatedAt").value("2026-05-22T10:00:00Z"));

    verify(priorAuthorityService)
        .updateDocumentType(
            PRIOR_AUTHORITY_ID, documentId, PriorAuthorityDocumentType.GATEWAY_EVIDENCE);
  }

  @Test
  void updateDocumentTypeReturns400WhenDocumentTypeMissing() throws Exception {
    UUID documentId = UUID.randomUUID();
    String body =
        """
        {}
        """;

    mockMvc
        .perform(
            patch(
                    "/prior-authorities/{priorAuthorityId}/documents/{documentId}",
                    PRIOR_AUTHORITY_ID,
                    documentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteDocumentReturns204() throws Exception {
    UUID documentId = UUID.randomUUID();

    mockMvc
        .perform(
            delete(
                "/prior-authorities/{priorAuthorityId}/documents/{documentId}",
                PRIOR_AUTHORITY_ID,
                documentId))
        .andExpect(status().isNoContent());

    verify(priorAuthorityService).deleteDocument(PRIOR_AUTHORITY_ID, documentId);
  }

  @Test
  void deleteDocumentReturns404WhenNotFound() throws Exception {
    UUID documentId = UUID.randomUUID();
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"))
        .when(priorAuthorityService)
        .deleteDocument(PRIOR_AUTHORITY_ID, documentId);

    mockMvc
        .perform(
            delete(
                "/prior-authorities/{priorAuthorityId}/documents/{documentId}",
                PRIOR_AUTHORITY_ID,
                documentId))
        .andExpect(status().isNotFound());
  }
}
