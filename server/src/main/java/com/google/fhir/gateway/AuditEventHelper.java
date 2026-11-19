/*
 * Copyright 2021-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.gateway;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.storage.interceptor.balp.BalpProfileEnum;
import ca.uhn.fhir.util.UrlUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Strings;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.codesystems.V3ParticipationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO unit test this class

/**
 * This class is meant to be immutable with a new instance created to process each individual
 * request.
 *
 * <p>This implementation is partially inspired by the HAPI FHIR Storage BalpAuditCaptureInterceptor
 * class. See
 * https://github.com/hapifhir/hapi-fhir/blob/v8.2.0/hapi-fhir-storage/src/main/java/ca/uhn/fhir/storage/interceptor/balp/BalpAuditCaptureInterceptor.java
 */
public class AuditEventHelper {

  private static final Logger logger = LoggerFactory.getLogger(AuditEventHelper.class);

  /**
   * A dedicated logger for AuditEvents that could not be written, so that deployments can route and
   * alert on audit loss without grepping the general application log.
   */
  private static final Logger auditDeliveryLogger =
      LoggerFactory.getLogger("com.google.fhir.gateway.AUDIT_DELIVERY_FAILURE");

  private final PatientFinderImp patientFinder;
  private final RequestDetailsReader requestDetailsReader;
  private final String responseContentLocation;
  private final Reference agentUserWho;
  private final DecodedJWT decodedJWT;
  private final Date periodStartTime;
  private final String fhirServerBaseUrl;
  private final HttpFhirClient auditFhirClient;
  private final FhirContext fhirContext;
  @Nullable private final IBaseResource requestResource;
  @Nullable private final IBaseResource responseResource;
  @Nullable private final IBaseResource contentLocationResponseResource;
  private final Set<String> auditEventActionsConfigSet;

  /**
   * Note {@code fhirServerBaseUrl} and {@code auditFhirClient} are two different FHIR stores when a
   * separate AuditEvent store is configured, and it matters which is used where.
   *
   * @param fhirServerBaseUrl the base URL of the proxied (clinical) FHIR store the audited data was
   *     read from or written to. Under BALP this goes <em>inside</em> the AuditEvent, as the
   *     destination agent; it is not where the AuditEvent is filed.
   * @param auditFhirClient the client used to write the generated AuditEvents.
   */
  public AuditEventHelper(
      RequestDetailsReader requestDetailsReader,
      String responseContent,
      String responseContentLocation,
      Reference agentUserWho,
      @Nullable DecodedJWT decodedJWT,
      Date periodStartTime,
      String fhirServerBaseUrl,
      HttpFhirClient auditFhirClient,
      FhirContext fhirContext,
      Set<String> auditEventActionsConfigSet) {
    this.patientFinder = PatientFinderImp.getInstance(fhirContext);
    this.requestDetailsReader = requestDetailsReader;
    this.responseContentLocation = responseContentLocation;
    this.agentUserWho = agentUserWho;
    this.decodedJWT = decodedJWT;
    this.periodStartTime = periodStartTime;
    this.fhirServerBaseUrl = fhirServerBaseUrl;
    this.auditFhirClient = auditFhirClient;
    this.fhirContext = fhirContext;
    this.auditEventActionsConfigSet = auditEventActionsConfigSet;

    // The following constructor logic prepares the source for the AuditEvent to be generated. It
    // uses a combination of the request payload, response resource and the Content-Location header
    // to generate the most complete input resource. This input is mostly used to generate
    // AuditEvents for single resource requests as opposed to POST bundle requests which are handled
    // by the {@link #extractResourceFromBundleComponent()} method.

    requestResource =
        requestDetailsReader.loadRequestContents().length > 0
                && !RequestTypeEnum.PATCH.equals(
                    requestDetailsReader
                        .getRequestType()) // Should we consider moving these checks to the Util
            // function and return null otherwise?
            ? FhirUtil.createResourceFromRequest(fhirContext, requestDetailsReader)
            : null;

    responseResource = FhirUtil.parseResourceOrNull(fhirContext, responseContent);

    // This handles operations with no response body returned i.e. POST/PUT/PATCH requests with
    // Prefer: return=minimal HTTP header
    contentLocationResponseResource =
        responseContentLocation != null
            ? getResourceFromContentLocation(responseContentLocation)
            : null;
  }

  private IBaseResource createAuditEventSource(
      IBaseResource requestResource,
      IBaseResource responseResource,
      IBaseResource contentLocationResponseResource) {
    IBaseResource auditEventSource;
    if (responseResource != null) {
      auditEventSource = responseResource;
    } else if (requestResource != null) {
      auditEventSource = requestResource;
      auditEventSource.setId(
          contentLocationResponseResource != null
              ? contentLocationResponseResource.getIdElement()
              : auditEventSource.getIdElement()); // POST requests don't have a valid id yet.
    } else {
      auditEventSource = contentLocationResponseResource;
    }
    return auditEventSource;
  }

  public void processAuditEvents() {
    List<AuditEvent> auditEventList = new ArrayList<>();

    IBaseResource auditEventSource =
        createAuditEventSource(
            this.requestResource, this.responseResource, this.contentLocationResponseResource);

    try {
      // For a Bundle requestDetails.getResourceName() returns null
      if (requestDetailsReader.getRequestType() == RequestTypeEnum.POST
          && requestDetailsReader.getResourceName() == null
          && requestResource != null) {
        auditEventList = processBundleRequest((Bundle) requestResource, (Bundle) auditEventSource);

      } else { // Process non-Bundle requests

        AuditEventBuilder.QueryEntity queryEntity =
            generateRequestDetailsQueryEntity(this.requestDetailsReader);

        AuditEventBuilder.ResourceContext resource =
            AuditEventBuilder.ResourceContext.builder()
                .resourceEntity(auditEventSource)
                .queryEntity(queryEntity)
                .build();

        RestOperationTypeEnum restOperationType = this.requestDetailsReader.getRestOperationType();
        auditEventList = generateAuditEventsByRestOperationType(restOperationType, resource);
      }

    } catch (Exception e) {
      logger.error("Exception while processing AuditEvents ", e);
    }

    // TODO Investigate bulk saving (batch processing) instead to improve performance. We'll
    // probably need a mechanism for chunking e.g. 100, 200, or 500 batch
    for (AuditEvent auditEvent : auditEventList) {
      auditEvent.getPeriod().setEnd(new Date());
      writeAuditEvent(auditEvent);
    }
  }

  /**
   * Writes a single AuditEvent to the audit FHIR store. This runs after the audited FHIR store
   * operation has already committed, so a failure here must never be thrown at the caller: doing so
   * would report a successful clinical operation, for example a DELETE, as a 500. Failures are
   * logged through {@link #auditDeliveryLogger} instead.
   */
  private void writeAuditEvent(AuditEvent auditEvent) {
    HttpResponse response = null;
    try {
      response = this.auditFhirClient.postResource(auditEvent);
      if (response != null && !HttpUtil.isResponseValid(response)) {
        auditDeliveryLogger.error(
            "Failed to write an AuditEvent to {} for request {}; status {}{}",
            this.auditFhirClient.getBaseUrl(),
            this.requestDetailsReader.getRequestId(),
            response.getStatusLine(),
            describeFailure(response));
      }
    } catch (IOException | RuntimeException exception) {
      auditDeliveryLogger.error(
          "Failed to write an AuditEvent to {} for request {}",
          this.auditFhirClient.getBaseUrl(),
          this.requestDetailsReader.getRequestId(),
          exception);
    } finally {
      if (response != null) {
        EntityUtils.consumeQuietly(response.getEntity());
      }
    }
  }

  /** Returns the diagnostics of a failed response as a loggable suffix, or "" if there are none. */
  private String describeFailure(HttpResponse response) {
    if (response.getEntity() == null) {
      return "";
    }
    StringWriter responseStringWriter = new StringWriter();
    try (Reader reader = HttpUtil.readerFromEntity(response.getEntity())) {
      reader.transferTo(responseStringWriter);
    } catch (IOException exception) {
      return "";
    }
    IBaseResource resource =
        FhirUtil.parseResourceOrNull(this.fhirContext, responseStringWriter.toString());
    if (!(resource instanceof OperationOutcome)) {
      return "";
    }
    return "; " + ((OperationOutcome) resource).getIssueFirstRep().getDiagnostics();
  }

  private @Nonnull List<AuditEvent> generateAuditEventsByRestOperationType(
      RestOperationTypeEnum restOperationType, AuditEventBuilder.ResourceContext auditEventSource) {
    List<AuditEvent> auditEventList = new ArrayList<>();
    switch (restOperationType) {
      case SEARCH_TYPE:
      case SEARCH_SYSTEM:
      case GET_PAGE:
        if (auditEventActionsConfigSet.contains(AuditEvent.AuditEventAction.E.toCode())) {
          auditEventList =
              processRestOperationByType(
                  restOperationType,
                  auditEventSource,
                  BalpProfileEnum.PATIENT_QUERY,
                  BalpProfileEnum.BASIC_QUERY);
        }
        break;
      case READ:
      case VREAD:
        if (auditEventActionsConfigSet.contains(AuditEvent.AuditEventAction.R.toCode())) {
          auditEventList =
              processRestOperationByType(
                  restOperationType,
                  auditEventSource,
                  BalpProfileEnum.PATIENT_READ,
                  BalpProfileEnum.BASIC_READ);
        }
        break;
      case CREATE:
        if (auditEventActionsConfigSet.contains(AuditEvent.AuditEventAction.C.toCode())) {
          auditEventList =
              processRestOperationByType(
                  restOperationType,
                  auditEventSource,
                  BalpProfileEnum.PATIENT_CREATE,
                  BalpProfileEnum.BASIC_CREATE);
        }
        break;
      case UPDATE:
      case PATCH:
        if (auditEventActionsConfigSet.contains(AuditEvent.AuditEventAction.U.toCode())) {
          auditEventList =
              processRestOperationByType(
                  restOperationType,
                  auditEventSource,
                  BalpProfileEnum.PATIENT_UPDATE,
                  BalpProfileEnum.BASIC_UPDATE);
        }
        break;
      case DELETE:

        // NOTE: The success of processing of DELETE is heavily dependent on server validation
        // policy e.g. If you have a permission list that references the resource being deleted,
        // it breaks the delete operation. Likewise if you reference the resource in AuditEvent
        // such as logging when you first created or updated it the operation breaks. In case of
        // such an error, AuditEvent.outcome and AuditEvent.outcomeDesc are populated accordingly

        // Also note, this implementation only fully captures that a specific resource was deleted
        // and by whom but not the compartment owner.
        // With no access to the actual deleted resource we can't get the compartment owner unless
        // the resource itself is a Patient. A crude way to get the owner would be to fetch the
        // actual resource from the database first before creating the AuditEvent.

        // This implementation skips logging Deletes if the operation is conditional (thus no
        // Resource ID present)

        if (auditEventActionsConfigSet.contains(AuditEvent.AuditEventAction.D.toCode())) {
          auditEventList =
              processRestOperationByType(
                  restOperationType,
                  auditEventSource,
                  BalpProfileEnum.PATIENT_DELETE,
                  BalpProfileEnum.BASIC_DELETE);
        }
        break;
      default:
        break;
    }
    return auditEventList;
  }

  private List<AuditEvent> processBundleRequest(
      Bundle requestBundle, Bundle auditEventSourceBundle) {

    List<AuditEvent> auditEventList = new ArrayList<>();
    List<Bundle.BundleEntryComponent> auditEventSourceEntries = auditEventSourceBundle.getEntry();
    List<Bundle.BundleEntryComponent> requestBundleEntryComponents = requestBundle.getEntry();

    for (int i = 0; i < auditEventSourceEntries.size(); i++) {

      IBaseResource entryResource =
          extractResourceFromBundleComponent(
              requestBundleEntryComponents.get(i), auditEventSourceEntries.get(i));

      AuditEventBuilder.QueryEntity nestedResourceQueryEntity =
          generateBundleEntryComponentQueryEntity(requestBundle.getEntry().get(i));

      RestOperationTypeEnum restOperationType =
          getRestOperationTypeForBundleEntry(requestBundle.getEntry().get(i), entryResource);

      if (entryResource instanceof Bundle) {
        auditEventList.addAll(
            createAuditEventsFromBundle(
                restOperationType, (Bundle) entryResource, nestedResourceQueryEntity));

      } else {

        AuditEventBuilder.ResourceContext resourceContext =
            AuditEventBuilder.ResourceContext.builder()
                .queryEntity(nestedResourceQueryEntity)
                .resourceEntity(entryResource)
                .build();
        auditEventList.addAll(
            generateAuditEventsByRestOperationType(restOperationType, resourceContext));
      }
    }
    return auditEventList;
  }

  private List<AuditEvent> createAuditEventsFromBundle(
      RestOperationTypeEnum restOperationType,
      Bundle responseBundle,
      AuditEventBuilder.QueryEntity queryEntity) {
    List<AuditEvent> auditEventList = new ArrayList<>();
    for (Bundle.BundleEntryComponent nestedBundleEntryComponent : responseBundle.getEntry()) {
      IBaseResource nestedBundleEntryComponentResource =
          extractResourceFromBundleComponent(null, nestedBundleEntryComponent);

      AuditEventBuilder.ResourceContext resourceContext =
          AuditEventBuilder.ResourceContext.builder()
              .queryEntity(queryEntity)
              .resourceEntity(nestedBundleEntryComponentResource)
              .build();

      auditEventList.addAll(
          generateAuditEventsByRestOperationType(restOperationType, resourceContext));
    }
    return auditEventList;
  }

  private List<AuditEvent> processRestOperationByType(
      RestOperationTypeEnum restOperationType,
      AuditEventBuilder.ResourceContext resource,
      BalpProfileEnum patientProfile,
      BalpProfileEnum basicProfile) {
    List<AuditEvent> auditEventList;

    if (resource.getResourceEntity() instanceof Bundle) {
      auditEventList =
          createAuditEventsFromBundle(
              restOperationType, (Bundle) resource.getResourceEntity(), resource.getQueryEntity());

    } else {
      auditEventList =
          createAuditEventCore(restOperationType, List.of(resource), patientProfile, basicProfile);
    }

    return auditEventList;
  }

  private String getResourceTemplate(String resourceType, String resourceId) {
    return String.format("{ \"resourceType\": \"%s\", \"id\": \"%s\"}", resourceType, resourceId);
  }

  private List<AuditEvent> createAuditEventCore(
      RestOperationTypeEnum restOperationType,
      List<AuditEventBuilder.ResourceContext> resources,
      BalpProfileEnum patientProfile,
      BalpProfileEnum basicProfile) {
    List<AuditEvent> auditEventList = new ArrayList<>();
    for (AuditEventBuilder.ResourceContext resourceContext : resources) {

      IBaseResource iBaseResource = resourceContext.getResourceEntity();
      if (iBaseResource instanceof DomainResource) {

        DomainResource resource = (DomainResource) iBaseResource;
        Set<String> patientIds = patientFinder.findPatientIds(resource);

        if (!patientIds.isEmpty()) {
          if (resource instanceof OperationOutcome) {
            auditEventList.add(
                createAuditEventOperationOutcome(
                    restOperationType,
                    (OperationOutcome) resource,
                    patientProfile,
                    resourceContext.getQueryEntity()));
          } else {
            auditEventList.add(
                createAuditEventEHR(
                    restOperationType,
                    resource,
                    patientProfile,
                    patientIds,
                    resourceContext.getQueryEntity()));
          }

        } else {
          if (resource instanceof OperationOutcome) {
            auditEventList.add(
                createAuditEventOperationOutcome(
                    restOperationType,
                    (OperationOutcome) resource,
                    basicProfile,
                    resourceContext.getQueryEntity()));
          } else {
            auditEventList.add(
                createAuditEventEHR(
                    restOperationType,
                    resource,
                    basicProfile,
                    Set.of(),
                    resourceContext.getQueryEntity()));
          }
        }
      }
    }

    return auditEventList;
  }

  @Nullable
  private AuditEvent.AuditEventOutcome mapOutcomeErrorCode(
      OperationOutcome.IssueSeverity issueSeverity) {
    AuditEvent.AuditEventOutcome errorCode = null;
    if (OperationOutcome.IssueSeverity.FATAL.equals(issueSeverity)) {

      errorCode = AuditEvent.AuditEventOutcome._12;
    } else if (OperationOutcome.IssueSeverity.ERROR.equals(issueSeverity)) {

      errorCode = AuditEvent.AuditEventOutcome._8;
    } else if (OperationOutcome.IssueSeverity.WARNING.equals(issueSeverity)) {

      errorCode = AuditEvent.AuditEventOutcome._4;
    } else if (OperationOutcome.IssueSeverity.INFORMATION.equals(issueSeverity)) {

      errorCode = AuditEvent.AuditEventOutcome._0;
    }
    return errorCode;
  }

  private AuditEventBuilder.QueryEntity generateBundleEntryComponentQueryEntity(
      Bundle.BundleEntryComponent bundleEntryComponent) {
    return AuditEventBuilder.QueryEntity.builder()
        .completeUrl(
            String.format(
                "%s/%s",
                requestDetailsReader.getFhirServerBase(),
                bundleEntryComponent.getRequest().getUrl()))
        .requestType(RequestTypeEnum.valueOf(bundleEntryComponent.getRequest().getMethod().name()))
        .gatewayServerBase(requestDetailsReader.getFhirServerBase())
        .requestPath(
            UrlUtil.determineResourceTypeInResourceUrl(
                fhirContext, bundleEntryComponent.getRequest().getUrl()))
        .parameters(
            UrlUtil.parseQueryString(getQueryStringFromBundleComponent(bundleEntryComponent)))
        .build();
  }

  private AuditEventBuilder.QueryEntity generateRequestDetailsQueryEntity(
      RequestDetailsReader requestDetailsReader) {
    return AuditEventBuilder.QueryEntity.builder()
        .completeUrl(requestDetailsReader.getCompleteUrl())
        .requestType(requestDetailsReader.getRequestType())
        .gatewayServerBase(requestDetailsReader.getFhirServerBase())
        .requestPath(requestDetailsReader.getRequestPath())
        .parameters(requestDetailsReader.getParameters())
        .build();
  }

  /**
   * Generates the source resource for the AuditEvent to be generated. It uses a combination of the
   * request bundle entry component resource, response bundle entry component resource and the
   * `response.location` field of the response bundle entry component to generate the most complete
   * input resource.
   *
   * @param requestBundleEntryComponent the bundle entry component from the request at a specific
   *     index position
   * @param responseBundleEntryComponent the bundle entry component from the response at an index
   *     position corresponding to the {@code requestBundleEntryComponent}
   * @return a FHIR Resource to be used as the input source of the AuditEvent generation
   */
  private @Nullable IBaseResource extractResourceFromBundleComponent(
      Bundle.BundleEntryComponent requestBundleEntryComponent,
      Bundle.BundleEntryComponent responseBundleEntryComponent) {
    IBaseResource resource;
    IBaseResource contentLocationResource = null;

    if (responseBundleEntryComponent.hasResponse()
        && responseBundleEntryComponent.getResponse().getLocation() != null) {
      contentLocationResource =
          getResourceFromContentLocation(responseBundleEntryComponent.getResponse().getLocation());
    }

    if (responseBundleEntryComponent.hasResource()) {
      resource = responseBundleEntryComponent.getResource();

    } else if (requestBundleEntryComponent != null && requestBundleEntryComponent.hasResource()) {
      resource = requestBundleEntryComponent.getResource();
      resource.setId(
          contentLocationResource != null
              ? contentLocationResource.getIdElement()
              : resource.getIdElement());

    } else {
      resource = contentLocationResource;
    }
    return resource;
  }

  private RestOperationTypeEnum getRestOperationTypeForBundleEntry(
      Bundle.BundleEntryComponent requestResourceBundleComponent, IBaseResource resource) {

    RestOperationTypeEnum restOperationType;
    String queryString = getQueryStringFromBundleComponent(requestResourceBundleComponent);

    restOperationType =
        FhirUtil.getRestOperationType(
            requestResourceBundleComponent.getRequest().getMethod().name(),
            resource instanceof Bundle ? null : resource.getIdElement(),
            UrlUtil.parseQueryString(queryString));

    return restOperationType;
  }

  private @Nonnull String getQueryStringFromBundleComponent(
      Bundle.BundleEntryComponent requestResourceBundleComponent) {
    String requestUrl = requestResourceBundleComponent.getRequest().getUrl();
    return !Strings.isNullOrEmpty(requestUrl) && requestUrl.contains("?")
        ? requestUrl.substring(requestUrl.indexOf('?'))
        : "";
  }

  // If we get here we capture all audit log details except the compartment owner for non-patient
  // resources. Reason being we don't have the full resource.
  // The content location header carries the minimal information we need to generate the resource
  // identity https://www.hl7.org/fhir/http.html#ops
  // One way to get the patient compartment owner for non-patient resources would be to fetch the
  // actual resource from the database first before creating the AuditEvent.
  private IBaseResource getResourceFromContentLocation(String responseContentLocation) {
    IdType id = new IdType(responseContentLocation);
    String resourceType = id.getResourceType();
    String resourceId =
        id.hasVersionIdPart()
            ? String.format("%s/_history/%s", id.getIdPart(), id.getVersionIdPart())
            : id.getIdPart();
    IBaseResource resource =
        FhirUtil.parseResourceOrNull(
            this.fhirContext, getResourceTemplate(resourceType, resourceId));
    return resource != null ? resource.setId(resourceId) : null;
  }

  private AuditEventBuilder initBaseAuditEventBuilder(
      RestOperationTypeEnum restOperationType, BalpProfileEnum balpProfile) {

    AuditEventBuilder auditEventBuilder = new AuditEventBuilder(this.periodStartTime);
    auditEventBuilder.restOperationType(restOperationType);
    auditEventBuilder.auditEventAction(balpProfile.getAction());
    auditEventBuilder.agentSourceTypeCoding(balpProfile.getAgentClientTypeCoding());
    auditEventBuilder.agentDestinationTypeCoding(balpProfile.getAgentServerTypeCoding());
    auditEventBuilder.profileUrl(balpProfile.getProfileUrl());
    auditEventBuilder.fhirServerBaseUrl(this.fhirServerBaseUrl);
    auditEventBuilder.gatewayServerBaseUrl(this.requestDetailsReader.getFhirServerBase());
    auditEventBuilder.requestId(this.requestDetailsReader.getRequestId());
    auditEventBuilder.network(
        AuditEventBuilder.Network.builder()
            .address(this.requestDetailsReader.getServletRequestRemoteAddr())
            .type(AuditEvent.AuditEventAgentNetworkType._2)
            .build());

    Coding participantCoding = new Coding();
    participantCoding =
        BalpProfileEnum.BASIC_DELETE.equals(balpProfile)
                || BalpProfileEnum.PATIENT_DELETE.equals(balpProfile)
            ? participantCoding
                .setSystem(V3ParticipationType.CST.getSystem())
                .setCode(V3ParticipationType.CST.toCode())
                .setDisplay(V3ParticipationType.CST.getDisplay())
            : participantCoding
                .setSystem(V3ParticipationType.IRCP.getSystem())
                .setCode(V3ParticipationType.IRCP.toCode())
                .setDisplay(V3ParticipationType.IRCP.getDisplay());
    auditEventBuilder.agentUserWhoTypeCoding(participantCoding);

    auditEventBuilder.agentUserWho(this.agentUserWho);

    if (this.decodedJWT != null) {
      auditEventBuilder.agentUserPolicy(
          JwtUtil.getClaimOrDefault(this.decodedJWT, JwtUtil.CLAIM_JWT_ID, ""));
      auditEventBuilder.authServerUri(
          JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_ISSUER, ""));
      auditEventBuilder.agentClientWho(createAgentClientWhoRef(this.decodedJWT));
    }
    return auditEventBuilder;
  }

  private AuditEvent createAuditEventOperationOutcome(
      RestOperationTypeEnum restOperationType,
      OperationOutcome operationOutcomeResource,
      BalpProfileEnum balpProfile,
      AuditEventBuilder.QueryEntity queryEntity) {
    // We need to capture the context of the operation e.g. Successful DELETE returns
    // OperationOutcome but no info on the affected resource
    IBaseResource resource = getResourceFromContentLocation(this.responseContentLocation);

    Set<String> patientIds =
        resource instanceof DomainResource
            ? patientFinder.findPatientIds((DomainResource) resource)
            : Set.of();

    AuditEventBuilder auditEventBuilder =
        getAuditEventBuilder(
            restOperationType, (Resource) resource, balpProfile, patientIds, queryEntity);

    OperationOutcome.OperationOutcomeIssueComponent outcomeIssueComponent =
        operationOutcomeResource.getIssueFirstRep();

    String errorDescription =
        outcomeIssueComponent.getDetails().getText() != null
            ? outcomeIssueComponent.getDetails().getText()
            : outcomeIssueComponent.getDiagnostics();
    auditEventBuilder.outcome(
        AuditEventBuilder.Outcome.builder()
            .code(mapOutcomeErrorCode(outcomeIssueComponent.getSeverity()))
            .description(errorDescription)
            .build());

    return auditEventBuilder.build();
  }

  private AuditEvent createAuditEventEHR(
      RestOperationTypeEnum restOperationType,
      Resource resource,
      BalpProfileEnum balpProfile,
      Set<String> compartmentOwners,
      AuditEventBuilder.QueryEntity queryEntity) {

    AuditEventBuilder auditEventBuilder =
        getAuditEventBuilder(
            restOperationType, resource, balpProfile, compartmentOwners, queryEntity);

    return auditEventBuilder.build();
  }

  private AuditEventBuilder getAuditEventBuilder(
      RestOperationTypeEnum restOperationType,
      Resource resource,
      BalpProfileEnum balpProfile,
      Set<String> compartmentOwners,
      AuditEventBuilder.QueryEntity queryEntity) {
    AuditEventBuilder auditEventBuilder = initBaseAuditEventBuilder(restOperationType, balpProfile);

    if (!compartmentOwners.isEmpty()) {
      for (String owner : compartmentOwners) {
        auditEventBuilder.addEntityWhat(balpProfile, true, owner);
      }
    }

    auditEventBuilder.addEntityWhat(balpProfile, false, FhirUtil.extractLogicalId(resource, true));

    if (BalpProfileEnum.BASIC_QUERY.equals(balpProfile)
        || BalpProfileEnum.PATIENT_QUERY.equals(balpProfile)) {

      auditEventBuilder.addQuery(queryEntity);
    }
    return auditEventBuilder;
  }

  private Reference createAgentClientWhoRef(DecodedJWT decodedJWT) {
    String clientId = JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_IHE_IUA_CLIENT_ID, "");
    clientId =
        Strings.isNullOrEmpty(clientId)
            ? JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_AZP, "")
            : clientId;
    String issuer = JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_ISSUER, "");

    return new Reference()
        .setIdentifier(
            new Identifier()
                .setSystem(String.format("%s/oauth-client-id", issuer))
                .setValue(clientId));
  }
}
