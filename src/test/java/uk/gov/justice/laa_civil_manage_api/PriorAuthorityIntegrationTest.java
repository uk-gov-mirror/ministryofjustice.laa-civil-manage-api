package uk.gov.justice.laa_civil_manage_api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import uk.gov.justice.laa_civil_manage_api.controllers.PriorAuthorityController.PriorAuthorityIdResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityApplicationResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDocumentTypeUpdateResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDraft;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityType;
import uk.gov.justice.laa_civil_manage_api.models.UploadedDocument;
import uk.gov.justice.laa_civil_manage_api.services.accessdatastore.AccessDataStoreProperties;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PriorAuthorityIntegrationTest {

  private static final String SERVICE_NAME = "CIVIL_MANAGE";

  @RegisterExtension
  static WireMockExtension accessDataStore =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @LocalServerPort private int port;

  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean private AccessDataStoreProperties accessDataStoreProperties;

  @MockitoBean private OAuth2AuthorizedClientManager authorizedClientManager;

  private RestClient authenticatedClient;

  @BeforeEach
  void setUp() {
    when(accessDataStoreProperties.baseUrl()).thenReturn(accessDataStore.baseUrl());
    when(accessDataStoreProperties.serviceName()).thenReturn(SERVICE_NAME);

    Jwt mockJwt =
        Jwt.withTokenValue("test-token").header("alg", "none").claim("sub", "test-user").build();

    when(jwtDecoder.decode("test-token")).thenReturn(mockJwt);

    ClientRegistration clientRegistration =
        ClientRegistration.withRegistrationId("entra-obo-access-data-store")
            .clientId("test-client-id")
            .clientSecret("test-client-secret")
            .authorizationGrantType(AuthorizationGrantType.JWT_BEARER)
            .tokenUri("https://test-tenant.example.com/oauth2/v2.0/token")
            .build();

    OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "downstream-access-token",
            Instant.now(),
            Instant.now().plusSeconds(300));

    OAuth2AuthorizedClient authorizedClient =
        new OAuth2AuthorizedClient(clientRegistration, "test-user", accessToken);

    when(authorizedClientManager.authorize(any())).thenReturn(authorizedClient);

    authenticatedClient =
        RestClient.builder()
            .defaultHeader("Authorization", "Bearer test-token")
            .defaultHeader("X-Authorization", "test-id-token")
            .build();
  }

  @Test
  void fullLifecycleFlowsThroughToTheAccessDataStore() {
    UUID applicationId = UUID.randomUUID();
    UUID priorAuthorityId = UUID.randomUUID();

    accessDataStore.stubFor(
        post(urlEqualTo("/api/v0/prior-authorities"))
            .withHeader("X-Service-Name", equalTo(SERVICE_NAME))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                                                { "priorAuthorityId": "%s" }
                                                """
                            .formatted(priorAuthorityId))));

    PriorAuthorityDraft createBody =
        PriorAuthorityDraft.builder()
            .applicationId(applicationId)
            .priorAuthorityType(PriorAuthorityType.EXPERT)
            .justification("Required expert evidence.")
            .build();

    ResponseEntity<PriorAuthorityIdResponse> create =
        authenticatedClient
            .post()
            .uri("http://localhost:" + port + "/prior-authorities")
            .body(createBody)
            .retrieve()
            .toEntity(PriorAuthorityIdResponse.class);

    assertEquals(HttpStatus.CREATED, create.getStatusCode());
    assertNotNull(create.getBody());
    assertEquals(priorAuthorityId, create.getBody().priorAuthorityId());

    accessDataStore.stubFor(
        put(urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(aResponse().withStatus(204)));

    PriorAuthorityDraft updateBody =
        PriorAuthorityDraft.builder()
            .applicationId(applicationId)
            .priorAuthorityType(PriorAuthorityType.EXPERT)
            .justification("Updated justification.")
            .build();

    ResponseEntity<Void> update =
        authenticatedClient
            .put()
            .uri("http://localhost:" + port + "/prior-authorities/{id}", priorAuthorityId)
            .body(updateBody)
            .retrieve()
            .toBodilessEntity();

    assertEquals(HttpStatus.NO_CONTENT, update.getStatusCode());

    accessDataStore.stubFor(
        get(urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                                                {
                                                  "priorAuthorityId": "%s",
                                                  "applicationId": "%s",
                                                  "status": null,
                                                  "priorAuthorityType": "EXPERT",
                                                  "justification": "Updated justification."
                                                }
                                                """
                            .formatted(priorAuthorityId, applicationId))));

    ResponseEntity<PriorAuthorityResponse> getResponse =
        authenticatedClient
            .get()
            .uri("http://localhost:" + port + "/prior-authorities/{id}", priorAuthorityId)
            .retrieve()
            .toEntity(PriorAuthorityResponse.class);

    assertEquals(HttpStatus.OK, getResponse.getStatusCode());
    assertNotNull(getResponse.getBody());
    assertEquals(priorAuthorityId, getResponse.getBody().priorAuthorityId());
    assertEquals("Updated justification.", getResponse.getBody().draft().justification());

    accessDataStore.stubFor(
        post(urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId + "/submit"))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                                                {
                                                  "priorAuthorityId": "%s",
                                                  "submittedAt": "2026-05-22T10:00:00Z"
                                                }
                                                """
                            .formatted(priorAuthorityId))));

    accessDataStore.stubFor(
        get(urlEqualTo("/api/v0/applications/" + applicationId))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                                                {
                                                  "applicationId": "%s",
                                                  "laaReference": "LAA123456",
                                                  "status": "APPLICATION_SUBMITTED"
                                                }
                                                """
                            .formatted(applicationId))));

    ResponseEntity<PriorAuthorityApplicationResponse> submit =
        authenticatedClient
            .post()
            .uri("http://localhost:" + port + "/prior-authorities/{id}/submit", priorAuthorityId)
            .retrieve()
            .toEntity(PriorAuthorityApplicationResponse.class);

    assertEquals(HttpStatus.CREATED, submit.getStatusCode());
    assertNotNull(submit.getBody());
    assertEquals(priorAuthorityId, submit.getBody().priorAuthorityId());
  }

  @Test
  void requestWithoutTokenReturns401Unauthorized() {
    RestClient unauthenticatedClient = RestClient.create();

    PriorAuthorityDraft body =
        PriorAuthorityDraft.builder().applicationId(UUID.randomUUID()).build();

    HttpStatusCode status =
        unauthenticatedClient
            .post()
            .uri("http://localhost:" + port + "/prior-authorities")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(_ -> true, (_, _) -> {})
            .toBodilessEntity()
            .getStatusCode();

    assertEquals(HttpStatus.UNAUTHORIZED, status);
  }

  @Test
  void uploadDocumentLargerThanConfiguredMultipartLimitReturns413() {
    UUID priorAuthorityId = UUID.randomUUID();
    byte[] oversizedFile = new byte[(10 * 1024 * 1024) + 1];
    ByteArrayResource fileResource =
        new ByteArrayResource(oversizedFile) {
          @Override
          public String getFilename() {
            return "large.pdf";
          }
        };

    MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
    multipartBody.add("file", fileResource);

    HttpStatusCode status =
        authenticatedClient
            .post()
            .uri("http://localhost:" + port + "/prior-authorities/{id}/documents", priorAuthorityId)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipartBody)
            .retrieve()
            .onStatus(_ -> true, (_, _) -> {})
            .toBodilessEntity()
            .getStatusCode();

    assertEquals(HttpStatus.CONTENT_TOO_LARGE, status);
  }

  @Test
  void uploadDocumentWithContentThatIsNotAPdfReturns415AndIsNotSentToTheAccessDataStore() {
    UUID priorAuthorityId = UUID.randomUUID();

    HttpStatusCode status =
        uploadDocument(priorAuthorityId, "not a pdf at all".getBytes(), "corrupt.pdf");

    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, status);
    accessDataStore.verify(
        0,
        postRequestedFor(
            urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId + "/documents")));
  }

  @Test
  void uploadDocumentWithEmptyContentReturns400AndIsNotSentToTheAccessDataStore() {
    UUID priorAuthorityId = UUID.randomUUID();

    HttpStatusCode status = uploadDocument(priorAuthorityId, new byte[0], "empty.pdf");

    assertEquals(HttpStatus.BAD_REQUEST, status);
    accessDataStore.verify(
        0,
        postRequestedFor(
            urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId + "/documents")));
  }

  @Test
  void uploadDocumentReturns404WhenTheAccessDataStoreCannotFindThePriorAuthority() {
    UUID priorAuthorityId = UUID.randomUUID();
    stubDocumentUploadResponse(priorAuthorityId, 404);

    assertEquals(HttpStatus.NOT_FOUND, uploadValidDocument(priorAuthorityId));
  }

  @Test
  void uploadDocumentReturns409WhenTheAccessDataStoreRejectsTheDocument() {
    UUID priorAuthorityId = UUID.randomUUID();
    stubDocumentUploadResponse(priorAuthorityId, 409);

    assertEquals(HttpStatus.CONFLICT, uploadValidDocument(priorAuthorityId));
  }

  @Test
  void uploadDocumentReturns502WhenTheAccessDataStoreFails() {
    UUID priorAuthorityId = UUID.randomUUID();
    stubDocumentUploadResponse(priorAuthorityId, 500);

    assertEquals(HttpStatus.BAD_GATEWAY, uploadValidDocument(priorAuthorityId));
  }

  private void stubDocumentUploadResponse(UUID priorAuthorityId, int status) {
    accessDataStore.stubFor(
        post(urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId + "/documents"))
            .withHeader("X-Service-Name", equalTo(SERVICE_NAME))
            .willReturn(aResponse().withStatus(status)));
  }

  private HttpStatusCode uploadValidDocument(UUID priorAuthorityId) {
    return uploadDocument(
        priorAuthorityId, "%PDF-1.4\nmock pdf content for testing".getBytes(), "evidence.pdf");
  }

  private HttpStatusCode uploadDocument(UUID priorAuthorityId, byte[] content, String filename) {
    ByteArrayResource fileResource =
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        };

    MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
    multipartBody.add("file", fileResource);

    return authenticatedClient
        .post()
        .uri("http://localhost:" + port + "/prior-authorities/{id}/documents", priorAuthorityId)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(multipartBody)
        .retrieve()
        .onStatus(_ -> true, (_, _) -> {})
        .toBodilessEntity()
        .getStatusCode();
  }

  @Test
  void uploadDocumentThenUpdateDocumentTypeFlowsThroughToTheAccessDataStore() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();

    accessDataStore.stubFor(
        post(urlEqualTo("/api/v0/prior-authorities/" + priorAuthorityId + "/documents"))
            .withHeader("X-Service-Name", equalTo(SERVICE_NAME))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                                                {
                                                  "documentId": "%s",
                                                  "fileName": "evidence.pdf",
                                                  "fileType": "pdf",
                                                  "contentType": "application/pdf",
                                                  "size": 11,
                                                  "uploadedAt": "2026-05-22T10:00:00Z",
                                                  "sourceService": "%s",
                                                  "checksum": "checksum-value"
                                                }
                                                """
                            .formatted(documentId, SERVICE_NAME))));

    ByteArrayResource fileResource =
        new ByteArrayResource("%PDF-1.4\nmock pdf content for testing".getBytes()) {
          @Override
          public String getFilename() {
            return "evidence.pdf";
          }
        };
    MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
    multipartBody.add("file", fileResource);

    ResponseEntity<UploadedDocument> uploadResponse =
        authenticatedClient
            .post()
            .uri("http://localhost:" + port + "/prior-authorities/{id}/documents", priorAuthorityId)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipartBody)
            .retrieve()
            .toEntity(UploadedDocument.class);

    assertEquals(HttpStatus.OK, uploadResponse.getStatusCode());
    assertNotNull(uploadResponse.getBody());
    assertEquals(documentId, uploadResponse.getBody().documentId());

    accessDataStore.stubFor(
        patch(
                urlEqualTo(
                    "/api/v0/prior-authorities/" + priorAuthorityId + "/documents/" + documentId))
            .withHeader("X-Service-Name", equalTo(SERVICE_NAME))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                                                {
                                                  "documentId": "%s",
                                                  "updatedAt": "2026-05-22T10:05:00Z"
                                                }
                                                """
                            .formatted(documentId))));

    ResponseEntity<PriorAuthorityDocumentTypeUpdateResponse> updateResponse =
        authenticatedClient
            .patch()
            .uri(
                "http://localhost:" + port + "/prior-authorities/{id}/documents/{documentId}",
                priorAuthorityId,
                documentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                                { "documentType": "GATEWAY_EVIDENCE" }
                                """)
            .retrieve()
            .toEntity(PriorAuthorityDocumentTypeUpdateResponse.class);

    assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
    assertNotNull(updateResponse.getBody());
    assertEquals(documentId, updateResponse.getBody().documentId());
  }

  @Test
  void deleteDocumentFlowsThroughToTheAccessDataStore() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();

    accessDataStore.stubFor(
        delete(
                urlEqualTo(
                    "/api/v0/prior-authorities/" + priorAuthorityId + "/document/" + documentId))
            .withHeader("X-Service-Name", equalTo(SERVICE_NAME))
            .withHeader("Authorization", equalTo("Bearer downstream-access-token"))
            .withHeader("X-Authorization", equalTo("test-id-token"))
            .willReturn(aResponse().withStatus(204)));

    ResponseEntity<Void> response =
        authenticatedClient
            .delete()
            .uri(
                "http://localhost:" + port + "/prior-authorities/{id}/documents/{documentId}",
                priorAuthorityId,
                documentId)
            .retrieve()
            .toBodilessEntity();

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
  }

  @Test
  void deleteDocumentReturns404WhenTheAccessDataStoreCannotFindTheDocument() {
    UUID priorAuthorityId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();

    accessDataStore.stubFor(
        delete(
                urlEqualTo(
                    "/api/v0/prior-authorities/" + priorAuthorityId + "/document/" + documentId))
            .willReturn(aResponse().withStatus(404)));

    HttpStatusCode status =
        authenticatedClient
            .delete()
            .uri(
                "http://localhost:" + port + "/prior-authorities/{id}/documents/{documentId}",
                priorAuthorityId,
                documentId)
            .retrieve()
            .onStatus(_ -> true, (_, _) -> {})
            .toBodilessEntity()
            .getStatusCode();

    assertEquals(HttpStatus.NOT_FOUND, status);
  }
}
