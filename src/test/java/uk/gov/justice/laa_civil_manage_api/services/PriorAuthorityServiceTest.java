package uk.gov.justice.laa_civil_manage_api.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.justice.laa.civil.notify.model.SendEmailRequest;
import uk.gov.justice.laa.civil.notify.service.NotifyEmailSender;
import uk.gov.justice.laa_civil_manage_api.config.NotifyEmailProperties;
import uk.gov.justice.laa_civil_manage_api.models.ApplicationSummary;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDocumentType;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDraft;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityType;
import uk.gov.justice.laa_civil_manage_api.models.UploadedDocument;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.AccessDataStoreClient;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.DocumentTypeUpdateResponse;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.PriorAuthorityIdResponse;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.PriorAuthorityRecordResponse;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.SubmitPriorAuthorityDraftResponse;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.UploadPriorAuthorityDocumentResponse;

class PriorAuthorityServiceTest {

  private static final byte[] PDF_CONTENT = "%PDF-1.4\nmock pdf content for testing".getBytes();
  private static final UUID PRIOR_AUTHORITY_ID = UUID.randomUUID();

  private final AccessDataStoreClient client = mock(AccessDataStoreClient.class);
  private final NotifyEmailSender notifyEmailSender = mock(NotifyEmailSender.class);
  private final DocumentValidationService documentValidationService =
      mock(DocumentValidationService.class);
  private final NotifyEmailProperties notifyEmailProperties =
      new NotifyEmailProperties(
          "api-key",
          "https://api.notifications.service.gov.uk",
          "template-id",
          "ops@example.com",
          true);
  private final PriorAuthorityService service =
      new PriorAuthorityService(
          client, notifyEmailSender, notifyEmailProperties, documentValidationService);

  @BeforeEach
  void resetMocks() {
    reset(client, notifyEmailSender, documentValidationService);
  }

  @Test
  void createDraftDelegatesToAccessDataStoreClientAndReturnsId() {
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityDraft draft =
        PriorAuthorityDraft.builder()
            .applicationId(applicationId)
            .priorAuthorityType(PriorAuthorityType.EXPERT)
            .justification("Required expert evidence.")
            .build();
    when(client.createPriorAuthorityDraft(any()))
        .thenReturn(
            PriorAuthorityIdResponse.builder().priorAuthorityId(PRIOR_AUTHORITY_ID).build());

    UUID result = service.createDraft(draft);

    assertEquals(PRIOR_AUTHORITY_ID, result);
  }

  @Test
  void updateDraftDelegatesToAccessDataStoreClient() {
    UUID applicationId = UUID.randomUUID();
    PriorAuthorityDraft draft =
        PriorAuthorityDraft.builder()
            .applicationId(applicationId)
            .justification("Updated justification.")
            .build();

    service.updateDraft(PRIOR_AUTHORITY_ID, draft);

    verify(client).updatePriorAuthorityDraft(eq(PRIOR_AUTHORITY_ID), any());
  }

  @Test
  void getReturnsMappedSummaryWhenPresent() {
    UUID applicationId = UUID.randomUUID();
    UploadedDocument uploadedDocument =
        UploadedDocument.builder()
            .documentId(UUID.randomUUID())
            .documentType(PriorAuthorityDocumentType.GATEWAY_EVIDENCE)
            .fileName("evidence.pdf")
            .build();
    when(client.getPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            Optional.of(
                PriorAuthorityRecordResponse.builder()
                    .priorAuthorityId(PRIOR_AUTHORITY_ID)
                    .applicationId(applicationId)
                    .status(null)
                    .priorAuthorityType(PriorAuthorityType.EXPERT)
                    .justification("Required.")
                    .uploadedDocuments(List.of(uploadedDocument))
                    .build()));

    Optional<PriorAuthorityResponse> result = service.get(PRIOR_AUTHORITY_ID);

    assertTrue(result.isPresent());
    assertEquals(PRIOR_AUTHORITY_ID, result.get().priorAuthorityId());
    assertEquals(applicationId, result.get().draft().applicationId());
    assertEquals(PriorAuthorityType.EXPERT, result.get().draft().priorAuthorityType());
    assertEquals(List.of(uploadedDocument), result.get().uploadedDocuments());
  }

  @Test
  void getReturnsEmptyUploadedDocumentsWhenRecordHasNone() {
    UUID applicationId = UUID.randomUUID();
    when(client.getPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            Optional.of(
                PriorAuthorityRecordResponse.builder()
                    .priorAuthorityId(PRIOR_AUTHORITY_ID)
                    .applicationId(applicationId)
                    .status(null)
                    .priorAuthorityType(PriorAuthorityType.EXPERT)
                    .justification("Required.")
                    .uploadedDocuments(null)
                    .build()));

    Optional<PriorAuthorityResponse> result = service.get(PRIOR_AUTHORITY_ID);

    assertTrue(result.isPresent());
    assertEquals(List.of(), result.get().uploadedDocuments());
  }

  @Test
  void getReturnsEmptyWhenNotFound() {
    when(client.getPriorAuthority(PRIOR_AUTHORITY_ID)).thenReturn(Optional.empty());

    assertTrue(service.get(PRIOR_AUTHORITY_ID).isEmpty());
  }

  @Test
  void submitDelegatesAndSendsConfirmationEmail() {
    UUID applicationId = UUID.randomUUID();
    when(client.getPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            Optional.of(
                PriorAuthorityRecordResponse.builder()
                    .priorAuthorityId(PRIOR_AUTHORITY_ID)
                    .applicationId(applicationId)
                    .status("PENDING")
                    .priorAuthorityType(PriorAuthorityType.EXPERT)
                    .build()));
    when(client.getApplicationById(applicationId))
        .thenReturn(
            ApplicationSummary.builder()
                .applicationId(applicationId)
                .laaReference("LAA123456")
                .build());
    when(notifyEmailSender.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(client.submitPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            new SubmitPriorAuthorityDraftResponse(PRIOR_AUTHORITY_ID, OffsetDateTime.now()));

    var response = service.submit(PRIOR_AUTHORITY_ID);

    assertEquals(PRIOR_AUTHORITY_ID, response.priorAuthorityId());
    verify(client).submitPriorAuthority(PRIOR_AUTHORITY_ID);

    ArgumentCaptor<SendEmailRequest> emailCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(notifyEmailSender).sendEmail(emailCaptor.capture());
    SendEmailRequest sentEmail = emailCaptor.getValue();
    assertEquals("template-id", sentEmail.templateId());
    assertEquals("LAA123456", sentEmail.personalisation().get("laaReference"));
    assertEquals("Expert", sentEmail.personalisation().get("priorAuthorityType"));
  }

  @Test
  void submitReturnsResponseEvenWhenEmailFutureFails() {
    UUID applicationId = UUID.randomUUID();
    when(client.getPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            Optional.of(
                PriorAuthorityRecordResponse.builder()
                    .priorAuthorityId(PRIOR_AUTHORITY_ID)
                    .applicationId(applicationId)
                    .priorAuthorityType(PriorAuthorityType.EXPERT)
                    .uploadedDocuments(null)
                    .build()));
    when(client.getApplicationById(applicationId))
        .thenReturn(
            ApplicationSummary.builder()
                .applicationId(applicationId)
                .laaReference("LAA123456")
                .build());
    when(notifyEmailSender.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("notify down")));
    when(client.submitPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            new SubmitPriorAuthorityDraftResponse(PRIOR_AUTHORITY_ID, OffsetDateTime.now()));

    var response = service.submit(PRIOR_AUTHORITY_ID);

    assertEquals(PRIOR_AUTHORITY_ID, response.priorAuthorityId());
  }

  @Test
  void doesNotSendEmailWhenNotifyIsNotConfigured() {
    PriorAuthorityService unconfiguredService =
        new PriorAuthorityService(
            client,
            notifyEmailSender,
            new NotifyEmailProperties("", "", "", "", false),
            documentValidationService);
    when(client.submitPriorAuthority(PRIOR_AUTHORITY_ID))
        .thenReturn(
            new SubmitPriorAuthorityDraftResponse(PRIOR_AUTHORITY_ID, OffsetDateTime.now()));

    var response = unconfiguredService.submit(PRIOR_AUTHORITY_ID);

    assertEquals(PRIOR_AUTHORITY_ID, response.priorAuthorityId());
    verify(notifyEmailSender, never()).sendEmail(any(SendEmailRequest.class));
    verify(client, never()).getPriorAuthority(any());
  }

  @Test
  void uploadDocumentForwardsToAccessDataStoreClient() {
    MockMultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", PDF_CONTENT);
    when(documentValidationService.validateAndSanitize(file)).thenReturn("evidence.pdf");
    when(client.uploadPriorAuthorityDocument(eq(PRIOR_AUTHORITY_ID), any()))
        .thenReturn(
            new UploadPriorAuthorityDocumentResponse(
                UUID.randomUUID(),
                "evidence.pdf",
                "pdf",
                "application/pdf",
                (long) PDF_CONTENT.length,
                OffsetDateTime.now(),
                "CIVIL_MANAGE",
                "checksum-value"));

    UploadedDocument uploadedDocument = service.uploadDocument(PRIOR_AUTHORITY_ID, file);

    assertEquals("evidence.pdf", uploadedDocument.fileName());
    verify(documentValidationService).validateAndSanitize(file);
    verify(client).uploadPriorAuthorityDocument(PRIOR_AUTHORITY_ID, file);
  }

  @Test
  void uploadDocumentFallsBackToSanitizedFilenameFileSizeAndNowWhenResponseFieldsAreNull() {
    MockMultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", PDF_CONTENT);
    when(documentValidationService.validateAndSanitize(file)).thenReturn("evidence.pdf");
    when(client.uploadPriorAuthorityDocument(eq(PRIOR_AUTHORITY_ID), any()))
        .thenReturn(
            new UploadPriorAuthorityDocumentResponse(
                UUID.randomUUID(),
                null,
                "pdf",
                "application/pdf",
                null,
                null,
                "CIVIL_MANAGE",
                "checksum-value"));

    UploadedDocument uploadedDocument = service.uploadDocument(PRIOR_AUTHORITY_ID, file);

    assertEquals("evidence.pdf", uploadedDocument.fileName());
    assertEquals(file.getSize(), uploadedDocument.size());
    assertTrue(uploadedDocument.uploadedAt().isAfter(OffsetDateTime.now().minusMinutes(1)));
  }

  @Test
  void uploadDocumentPropagatesValidationFailureWithoutCallingAccessDataStoreClient() {
    MockMultipartFile file =
        new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());
    when(documentValidationService.validateAndSanitize(file))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported file type; allowed: PDF"));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.uploadDocument(PRIOR_AUTHORITY_ID, file));

    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getStatusCode());
    verify(client, never()).uploadPriorAuthorityDocument(any(), any());
  }

  @Test
  void updateDocumentTypeForwardsToAccessDataStoreClient() {
    UUID documentId = UUID.randomUUID();
    OffsetDateTime updatedAt = OffsetDateTime.now();
    when(client.updatePriorAuthorityDocumentType(
            PRIOR_AUTHORITY_ID, documentId, PriorAuthorityDocumentType.GATEWAY_EVIDENCE))
        .thenReturn(new DocumentTypeUpdateResponse(documentId, updatedAt));

    var response =
        service.updateDocumentType(
            PRIOR_AUTHORITY_ID, documentId, PriorAuthorityDocumentType.GATEWAY_EVIDENCE);

    assertEquals(documentId, response.documentId());
    assertEquals(updatedAt, response.updatedAt());
    verify(client)
        .updatePriorAuthorityDocumentType(
            PRIOR_AUTHORITY_ID, documentId, PriorAuthorityDocumentType.GATEWAY_EVIDENCE);
  }

  @Test
  void deleteDocumentForwardsToAccessDataStoreClient() {
    UUID documentId = UUID.randomUUID();

    service.deleteDocument(PRIOR_AUTHORITY_ID, documentId);

    verify(client).deletePriorAuthorityDocument(PRIOR_AUTHORITY_ID, documentId);
  }
}
