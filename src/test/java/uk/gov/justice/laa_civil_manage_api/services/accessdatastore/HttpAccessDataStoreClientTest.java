package uk.gov.justice.laa_civil_manage_api.services.accessdatastore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.justice.laa_civil_manage_api.models.*;

class HttpAccessDataStoreClientTest {

  private static final String BASE_URL = "http://ads.test";
  private static final String SERVICE_NAME = "CIVIL_MANAGE";

  private MockRestServiceServer server;
  private HttpAccessDataStoreClient client;

  @BeforeEach
  void setup() {
    RestClient.Builder builder =
        RestClient.builder()
            .requestInterceptor(
                (request, body, execution) -> {
                  request.getHeaders().add("X-Service-Name", SERVICE_NAME);
                  return execution.execute(request, body);
                });
    server = MockRestServiceServer.bindTo(builder).build();
    AccessDataStoreProperties properties =
        new AccessDataStoreProperties(
            BASE_URL, Duration.ofSeconds(3), Duration.ofSeconds(5), SERVICE_NAME);
    client = new HttpAccessDataStoreClient(builder.build(), properties);
  }

  @Test
  void createPriorAuthorityDraftPostsToAdsAndReturnsId() {
    UUID applicationId = UUID.randomUUID();
    UUID priorAuthorityId = UUID.randomUUID();

    server
        .expect(requestTo(BASE_URL + "/api/v0/prior-authorities"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andExpect(jsonPath("$.applicationId").value(applicationId.toString()))
        .andExpect(jsonPath("$.priorAuthorityType").value("EXPERT"))
        .andRespond(
            withSuccess(
                "{ \"priorAuthorityId\": \"" + priorAuthorityId + "\" }",
                MediaType.APPLICATION_JSON));

    CreatePriorAuthorityDraftRequest request =
        CreatePriorAuthorityDraftRequest.builder()
            .applicationId(applicationId)
            .priorAuthorityType(PriorAuthorityType.EXPERT)
            .justification("Required expert evidence.")
            .build();

    PriorAuthorityIdResponse response = client.createPriorAuthorityDraft(request);

    assertEquals(priorAuthorityId, response.priorAuthorityId());
    server.verify();
  }

  @Test
  void updatePriorAuthorityDraftPutsToAdsWithIdInPath() {
    UUID priorAuthorityId = UUID.randomUUID();

    server
        .expect(requestTo(BASE_URL + "/api/v0/prior-authorities/" + priorAuthorityId))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.justification").value("Updated justification."))
        .andRespond(withSuccess());

    SavePriorAuthorityDraftRequest request =
        SavePriorAuthorityDraftRequest.builder()
            .priorAuthorityType(PriorAuthorityType.EXPERT)
            .justification("Updated justification.")
            .build();

    client.updatePriorAuthorityDraft(priorAuthorityId, request);
    server.verify();
  }

  @Test
  void getPriorAuthorityReturnsRecordWhenPresent() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();

    server
        .expect(requestTo(BASE_URL + "/api/v0/prior-authorities/" + priorAuthorityId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                    {
                      "priorAuthorityId": "%s",
                      "applicationId": "%s",
                      "status": null,
                      "priorAuthorityType": "EXPERT",
                      "justification": "Required.",
                      "uploadedDocuments": []
                    }
                    """
                    .formatted(priorAuthorityId, applicationId),
                MediaType.APPLICATION_JSON));

    Optional<PriorAuthorityRecordResponse> result = client.getPriorAuthority(priorAuthorityId);

    assertTrue(result.isPresent());
    assertEquals(priorAuthorityId, result.get().priorAuthorityId());
    assertEquals(PriorAuthorityType.EXPERT, result.get().priorAuthorityType());
    assertEquals(List.of(), result.get().uploadedDocuments());
    server.verify();
  }

  @Test
  void getPriorAuthorityReturnsEmptyWhenNotFound() {
    UUID priorAuthorityId = UUID.randomUUID();

    server
        .expect(requestTo(BASE_URL + "/api/v0/prior-authorities/" + priorAuthorityId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    Optional<PriorAuthorityRecordResponse> result = client.getPriorAuthority(priorAuthorityId);

    assertTrue(result.isEmpty());
    server.verify();
  }

  @Test
  void submitPriorAuthorityPostsToAdsSubmitEndpoint() {
    UUID priorAuthorityId = UUID.randomUUID();
    OffsetDateTime submittedAt = OffsetDateTime.parse("2026-05-22T10:00:00Z");

    server
        .expect(requestTo(BASE_URL + "/api/v0/prior-authorities/" + priorAuthorityId + "/submit"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(
            withSuccess(
                """
                    {
                      "priorAuthorityId": "%s",
                      "submittedAt": "%s"
                    }
                    """
                    .formatted(priorAuthorityId, submittedAt),
                MediaType.APPLICATION_JSON));

    SubmitPriorAuthorityDraftResponse response = client.submitPriorAuthority(priorAuthorityId);

    assertEquals(priorAuthorityId, response.priorAuthorityId());
    assertEquals(submittedAt, response.submittedAt());
    server.verify();
  }

  @Test
  void uploadPriorAuthorityDocumentPostsMultipartToAds() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    MockMultipartFile file =
        new MockMultipartFile("file", "evidence.pdf", "application/pdf", "pdf-content".getBytes());

    server
        .expect(
            requestTo(BASE_URL + "/api/v0/prior-authorities/" + priorAuthorityId + "/documents"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(
            withSuccess("{ \"documentId\": \"" + documentId + "\" }", MediaType.APPLICATION_JSON));

    UploadPriorAuthorityDocumentResponse result =
        client.uploadPriorAuthorityDocument(priorAuthorityId, file);

    assertEquals(documentId, result.documentId());
    server.verify();
  }

  @Test
  void updatePriorAuthorityDocumentTypePatchesAdsWithJsonBody() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    OffsetDateTime updatedAt = OffsetDateTime.parse("2026-05-22T10:00:00Z");

    server
        .expect(
            requestTo(
                BASE_URL
                    + "/api/v0/prior-authorities/"
                    + priorAuthorityId
                    + "/documents/"
                    + documentId))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.documentType").value("GATEWAY_EVIDENCE"))
        .andRespond(
            withSuccess(
                """
                    {
                      "documentId": "%s",
                      "updatedAt": "%s"
                    }
                    """
                    .formatted(documentId, updatedAt),
                MediaType.APPLICATION_JSON));

    DocumentTypeUpdateResponse result =
        client.updatePriorAuthorityDocumentType(
            priorAuthorityId, documentId, PriorAuthorityDocumentType.GATEWAY_EVIDENCE);

    assertEquals(documentId, result.documentId());
    assertEquals(updatedAt, result.updatedAt());
    server.verify();
  }

  @Test
  void deletePriorAuthorityDocumentDeletesFromAdsWithServiceNameHeader() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();

    server
        .expect(
            requestTo(
                BASE_URL
                    + "/api/v0/prior-authorities/"
                    + priorAuthorityId
                    + "/document/"
                    + documentId))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    client.deletePriorAuthorityDocument(priorAuthorityId, documentId);

    server.verify();
  }

  @Test
  void getApplicationsGetsFromAdsWithServiceNameHeader() {
    server
        .expect(
            requestTo(
                BASE_URL
                    + "/api/v0/applications?page=1&pageSize=20&status=APPLICATION_GRANTED&matterType=SPECIAL_CHILDREN_ACT&sortBy=SUBMITTED_DATE&orderBy=DESC"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(
            withSuccess(
                """
                                {
                                  "paging": {
                                    "page": 1,
                                    "pageSize": 20,
                                    "itemsReturned": 1,
                                    "totalRecords": 1
                                  },
                                  "applications": [
                                    {
                                      "applicationId": "11111111-2222-3333-4444-555555555555",
                                      "laaReference": "APP-1",
                                      "status": "APPLICATION_SUBMITTED",
                                      "submittedAt": "2026-07-22T10:00:00Z",
                                      "clientFirstName": "John",
                                      "clientLastName": "Doe"
                                    }
                                  ]
                                }
                                """,
                MediaType.APPLICATION_JSON));

    ApplicationSummaryResponse result =
        client.getApplications(1, 20, ApplicationStatus.APPLICATION_GRANTED, null, null, null);

    assertNotNull(result);
    assertEquals(1, result.applications().size());
    assertEquals("APP-1", result.applications().getFirst().laaReference());
    server.verify();
  }

  @Test
  void getApplicationsIncludesFilterQueryParamsWhenProvided() {
    server
        .expect(
            requestTo(
                BASE_URL
                    + "/api/v0/applications?page=1&pageSize=20&status=APPLICATION_GRANTED&matterType=SPECIAL_CHILDREN_ACT&sortBy=SUBMITTED_DATE&orderBy=DESC&laaReference=APP-1&clientFirstName=John&clientLastName=Doe"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(
            withSuccess(
                """
                                {
                                  "paging": {
                                    "page": 1,
                                    "pageSize": 20,
                                    "itemsReturned": 1,
                                    "totalRecords": 1
                                  },
                                  "applications": [
                                    {
                                      "applicationId": "11111111-2222-3333-4444-555555555555",
                                      "laaReference": "APP-1",
                                      "status": "APPLICATION_SUBMITTED",
                                      "submittedAt": "2026-07-22T10:00:00Z",
                                      "clientFirstName": "John",
                                      "clientLastName": "Doe"
                                    }
                                  ]
                                }
                                """,
                MediaType.APPLICATION_JSON));

    ApplicationSummaryResponse result =
        client.getApplications(
            1, 20, ApplicationStatus.APPLICATION_GRANTED, "APP-1", "John", "Doe");

    assertNotNull(result);
    assertEquals(1, result.applications().size());
    assertEquals("APP-1", result.applications().getFirst().laaReference());
    server.verify();
  }

  @Test
  void getApplicationByIdGetsFromAdsWithServiceNameHeader() {
    UUID applicationId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    server
        .expect(requestTo(BASE_URL + "/api/v0/applications/" + applicationId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(
            withSuccess(
                """
                                {
                                  "applicationId": "11111111-2222-3333-4444-555555555555",
                                  "laaReference": "APP-1",
                                  "status": "APPLICATION_SUBMITTED",
                                  "submittedAt": "2026-07-22T10:00:00Z",
                                  "clientFirstName": "John",
                                  "clientLastName": "Doe"
                                }
                                """,
                MediaType.APPLICATION_JSON));

    ApplicationSummary result = client.getApplicationById(applicationId);

    assertNotNull(result);
    assertEquals(applicationId, result.applicationId());
    assertEquals("APP-1", result.laaReference());
    assertEquals("APPLICATION_SUBMITTED", result.status());
    server.verify();
  }

  @Test
  void getIndividualsGetsFromAdsWithServiceNameHeader() {
    UUID applicationId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    server
        .expect(requestTo(BASE_URL + "/api/v0/individuals?applicationId=" + applicationId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Service-Name", SERVICE_NAME))
        .andRespond(
            withSuccess(
                """
                                {
                                  "individuals": [
                                    {
                                      "firstName": "John",
                                      "lastName": "Doe"
                                    }
                                  ]
                                }
                                """,
                MediaType.APPLICATION_JSON));

    IndividualsResponse result = client.getIndividuals(applicationId);

    assertNotNull(result);
    assertEquals(1, result.individuals().size());
    assertEquals("John", result.individuals().getFirst().firstName());
    server.verify();
  }
}
