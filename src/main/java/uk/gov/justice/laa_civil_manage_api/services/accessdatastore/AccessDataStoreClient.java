package uk.gov.justice.laa_civil_manage_api.services.accessdatastore;

import java.util.Optional;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.justice.laa_civil_manage_api.models.*;

public interface AccessDataStoreClient {

  PriorAuthorityIdResponse createPriorAuthorityDraft(CreatePriorAuthorityDraftRequest request);

  void updatePriorAuthorityDraft(UUID priorAuthorityId, SavePriorAuthorityDraftRequest request);

  Optional<PriorAuthorityRecordResponse> getPriorAuthority(UUID priorAuthorityId);

  SubmitPriorAuthorityDraftResponse submitPriorAuthority(UUID priorAuthorityId);

  UploadPriorAuthorityDocumentResponse uploadPriorAuthorityDocument(
      UUID priorAuthorityId, MultipartFile file);

  DocumentTypeUpdateResponse updatePriorAuthorityDocumentType(
      UUID priorAuthorityId, UUID documentId, PriorAuthorityDocumentType documentType);

  void deletePriorAuthorityDocument(UUID priorAuthorityId, UUID documentId);

  ApplicationSummaryResponse getApplications(
      int page,
      int pageSize,
      ApplicationStatus status,
      String laaReference,
      String clientFirstName,
      String clientLastName);

  ApplicationSummary getApplicationById(UUID applicationId);

  IndividualsResponse getIndividuals(UUID applicationId);
}
