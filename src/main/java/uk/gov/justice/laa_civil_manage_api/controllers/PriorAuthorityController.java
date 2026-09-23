package uk.gov.justice.laa_civil_manage_api.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityApplicationResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDocumentTypeUpdateResponse;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityDraft;
import uk.gov.justice.laa_civil_manage_api.models.PriorAuthorityResponse;
import uk.gov.justice.laa_civil_manage_api.models.UpdatePriorAuthorityDocumentTypeRequest;
import uk.gov.justice.laa_civil_manage_api.models.UploadedDocument;
import uk.gov.justice.laa_civil_manage_api.services.PriorAuthorityService;

@Tag(
    name = "Prior Authority",
    description =
        "Create, update and submit prior-authority requests to the Legal Aid Agency, via a "
            + "create-draft -> update-draft -> upload-documents -> submit lifecycle.")
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/prior-authorities")
@RequiredArgsConstructor
public class PriorAuthorityController {

  private final PriorAuthorityService priorAuthorityService;

  @Operation(
      summary = "Create a prior-authority draft",
      description = "Starts a new prior-authority draft.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Draft created. Location header contains the prior-authority URL."),
    @ApiResponse(responseCode = "400", description = "applicationId missing.", content = @Content)
  })
  @PostMapping
  public ResponseEntity<PriorAuthorityIdResponse> createDraft(
      @Valid @RequestBody PriorAuthorityDraft draft) {
    UUID priorAuthorityId = priorAuthorityService.createDraft(draft);
    URI location = URI.create("/prior-authorities/" + priorAuthorityId);
    return ResponseEntity.created(location).body(new PriorAuthorityIdResponse(priorAuthorityId));
  }

  @Operation(summary = "Update an existing prior-authority draft")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Draft updated."),
    @ApiResponse(
        responseCode = "404",
        description = "Prior authority not found.",
        content = @Content)
  })
  @PutMapping("/{priorAuthorityId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateDraft(
      @Parameter(description = "ID of the prior authority to update.") @PathVariable
          UUID priorAuthorityId,
      @Valid @RequestBody PriorAuthorityDraft draft) {
    priorAuthorityService.updateDraft(priorAuthorityId, draft);
  }

  @Operation(summary = "Retrieve an existing prior-authority request (draft or submitted)")
  @GetMapping("/{priorAuthorityId}")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Prior authority returned."),
    @ApiResponse(
        responseCode = "404",
        description = "Prior authority not found.",
        content = @Content)
  })
  public ResponseEntity<PriorAuthorityResponse> getPriorAuthority(
      @Parameter(description = "ID of the prior authority to return.") @PathVariable
          UUID priorAuthorityId) {
    return priorAuthorityService
        .get(priorAuthorityId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @Operation(
      summary = "Submit an in-progress prior-authority request",
      description =
          "Locks the draft and forwards the submission to the Access Data Store for validation.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Request accepted. Location header contains the submission URL.",
        content =
            @Content(schema = @Schema(implementation = PriorAuthorityApplicationResponse.class))),
    @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
    @ApiResponse(
        responseCode = "409",
        description = "Not in a submittable state",
        content = @Content)
  })
  @PostMapping("/{priorAuthorityId}/submit")
  public ResponseEntity<PriorAuthorityApplicationResponse> submit(
      @Parameter(description = "ID of the prior authority to submit.") @PathVariable
          UUID priorAuthorityId) {
    PriorAuthorityApplicationResponse response = priorAuthorityService.submit(priorAuthorityId);
    URI location = URI.create("/prior-authorities/" + response.priorAuthorityId());
    return ResponseEntity.created(location).body(response);
  }

  @Operation(
      summary = "Upload a supporting document",
      description =
          "Accepts a multipart/form-data file upload from the frontend and returns the uploaded "
              + "file's metadata. The document is uploaded without a category; use the PATCH "
              + "endpoint to assign one afterwards.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "File accepted.",
        content = @Content(schema = @Schema(implementation = UploadedDocument.class))),
    @ApiResponse(
        responseCode = "400",
        description = "No file supplied or file is empty.",
        content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "Prior authority not found in the access data store.",
        content = @Content),
    @ApiResponse(
        responseCode = "409",
        description = "The access data store rejected the document as a conflict.",
        content = @Content),
    @ApiResponse(
        responseCode = "413",
        description = "File exceeds the configured maximum size.",
        content = @Content),
    @ApiResponse(responseCode = "415", description = "Unsupported file type.", content = @Content),
    @ApiResponse(
        responseCode = "502",
        description = "The access data store failed to store the document.",
        content = @Content)
  })
  @PostMapping(
      value = "/{priorAuthorityId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<UploadedDocument> uploadDocument(
      @Parameter(description = "ID of the prior authority.") @PathVariable UUID priorAuthorityId,
      @Parameter(description = "File to upload.") @RequestPart("file") MultipartFile file) {
    UploadedDocument uploadedDocument =
        priorAuthorityService.uploadDocument(priorAuthorityId, file);
    return ResponseEntity.ok(uploadedDocument);
  }

  @Operation(
      summary = "Categorise an uploaded document",
      description = "Assigns a document type to a previously uploaded supporting document.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Document category updated.",
        content =
            @Content(
                schema = @Schema(implementation = PriorAuthorityDocumentTypeUpdateResponse.class))),
    @ApiResponse(responseCode = "400", description = "Invalid document type.", content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "Prior authority or document not found.",
        content = @Content)
  })
  @PatchMapping("/{priorAuthorityId}/documents/{documentId}")
  public ResponseEntity<PriorAuthorityDocumentTypeUpdateResponse> updateDocumentType(
      @Parameter(description = "ID of the prior authority.") @PathVariable UUID priorAuthorityId,
      @Parameter(description = "ID of the document to categorise.") @PathVariable UUID documentId,
      @Valid @RequestBody UpdatePriorAuthorityDocumentTypeRequest request) {
    PriorAuthorityDocumentTypeUpdateResponse response =
        priorAuthorityService.updateDocumentType(
            priorAuthorityId, documentId, request.documentType());
    return ResponseEntity.ok(response);
  }

  @Operation(
      summary = "Delete an uploaded document",
      description = "Removes a supporting evidence document from a prior-authority draft.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "204",
        description = "Document deleted from prior authority draft."),
    @ApiResponse(
        responseCode = "404",
        description = "Prior authority or document not found.",
        content = @Content)
  })
  @DeleteMapping("/{priorAuthorityId}/documents/{documentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteDocument(
      @Parameter(description = "ID of the prior authority.") @PathVariable UUID priorAuthorityId,
      @Parameter(description = "ID of the document to delete.") @PathVariable UUID documentId) {
    priorAuthorityService.deleteDocument(priorAuthorityId, documentId);
  }

  public record PriorAuthorityIdResponse(UUID priorAuthorityId) {}
}
