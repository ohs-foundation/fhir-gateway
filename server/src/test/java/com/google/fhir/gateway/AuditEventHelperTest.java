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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.http.HttpResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.codesystems.AuditEntityType;
import org.hl7.fhir.r4.model.codesystems.ObjectRole;
import org.hl7.fhir.r4.model.codesystems.V3ParticipationType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AuditEventHelperTest {

  private static final FhirContext fhirContext = FhirContext.forR4();

  @Mock private HttpFhirClient auditFhirClientMock;

  @Mock private DecodedJWT decodedJWT;

  @Mock private RequestDetailsReader requestDetailsReader;

  @Mock private Claim claim;

  private Reference agentUserWho;

  private static final String FHIR_SERVER_BASE_URL = "http://my-fhir-server/fhir";
  private static final String FHIR_INFO_GATEWAY_SERVER_BASE_URL =
      "http://my-fhir-info-gateway/fhir";
  private static final String TEST_REQUEST_ID = "test-request-id-1";

  @Before
  public void setUp() {

    agentUserWho =
        new Reference().setReference("Practitioner/test-practitioner-id").setDisplay("Dr. Smith");

    when(claim.asString()).thenReturn("some-value");
    when(decodedJWT.getClaim(anyString())).thenReturn(claim);
    when(requestDetailsReader.getFhirServerBase()).thenReturn(FHIR_INFO_GATEWAY_SERVER_BASE_URL);
    when(requestDetailsReader.getRequestId()).thenReturn(TEST_REQUEST_ID);
  }

  @Test
  public void testProcessAuditEventsCreatePatient() throws IOException {

    Patient patient = new Patient();
    patient.addGeneralPractitioner(agentUserWho);

    String responseContentLocation =
        String.format(
            "%s/fhir/%s/%s/_history/hid-1",
            FHIR_SERVER_BASE_URL, ResourceType.Encounter.name(), "test-patient-id-1");

    AuditEventHelper auditEventHelper =
        createTestInstance(patient, null, responseContentLocation, RestOperationTypeEnum.CREATE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("C"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("create"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1/_history/hid-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo(
            "Patient/test-patient-id-1")); // We don't need version when capturing compartment owner
  }

  @Test
  public void testProcessAuditEventReadPatient() throws IOException {

    Patient patient = new Patient();
    patient.setId("test-patient-id-1");
    patient.addGeneralPractitioner(agentUserWho);

    AuditEventHelper auditEventHelper =
        createTestInstance(patient, null, RestOperationTypeEnum.READ);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("R"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("read"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
  }

  @Test
  public void testProcessAuditEventUpdatePatient() throws IOException {

    Patient patient = new Patient();
    patient.setId("test-patient-id-1/_history/2");
    patient.addGeneralPractitioner(agentUserWho);

    AuditEventHelper auditEventHelper =
        createTestInstance(patient, null, RestOperationTypeEnum.UPDATE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("U"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("update"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1/_history/2"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
  }

  @Test
  public void testProcessAuditEventDeletePatient() throws IOException {

    Patient patient = new Patient();
    patient.setId("test-patient-id-1");
    patient.addGeneralPractitioner(agentUserWho);

    AuditEventHelper auditEventHelper =
        createTestInstance(patient, null, RestOperationTypeEnum.DELETE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("D"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("delete"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceIdentifierSystem(auditEvent.getEntity()),
        equalTo(AuditEventBuilder.CS_INFO_GATEWAY_DELETED));
  }

  @Test
  public void testProcessAuditEventsCreateEncounter() throws IOException {

    Encounter encounter = new Encounter();
    encounter.setSubject(new Reference("Patient/test-patient-id-1"));

    String responseContentLocation =
        String.format(
            "%s/fhir/%s/%s/_history/hid-1",
            FHIR_SERVER_BASE_URL, ResourceType.Encounter.name(), "test-encounter-id-1");

    AuditEventHelper auditEventHelper =
        createTestInstance(encounter, null, responseContentLocation, RestOperationTypeEnum.CREATE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("C"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("create"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Encounter/test-encounter-id-1/_history/hid-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
  }

  @Test
  public void testProcessAuditEventReadEncounter() throws IOException {

    Encounter encounter = new Encounter();
    encounter.setId("test-encounter-id-1");
    encounter.setSubject(new Reference("Patient/test-patient-id-1"));

    AuditEventHelper auditEventHelper =
        createTestInstance(encounter, null, RestOperationTypeEnum.READ);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("R"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("read"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Encounter/test-encounter-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
  }

  @Test
  public void testProcessAuditEventUpdateEncounter() throws IOException {

    Encounter encounter = new Encounter();
    encounter.setId("test-encounter-id-1");
    encounter.setSubject(new Reference("Patient/test-patient-id-1"));

    AuditEventHelper auditEventHelper =
        createTestInstance(encounter, null, RestOperationTypeEnum.UPDATE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("U"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("update"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Encounter/test-encounter-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
  }

  @Test
  public void testProcessAuditEventDeleteEncounter() throws IOException {

    Encounter encounter = new Encounter();
    encounter.setId("test-encounter-id-1");
    encounter.setSubject(new Reference("Patient/test-patient-id-1"));

    AuditEventHelper auditEventHelper =
        createTestInstance(encounter, null, RestOperationTypeEnum.DELETE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("D"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("delete"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Encounter/test-encounter-id-1"));

    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceIdentifierSystem(auditEvent.getEntity()),
        equalTo(AuditEventBuilder.CS_INFO_GATEWAY_DELETED));
  }

  @Test
  public void testProcessAuditEventsCreateLocation() throws IOException {

    Location location = new Location();
    String responseContentLocation =
        String.format(
            "%s/fhir/%s/%s/_history/hid-1",
            FHIR_SERVER_BASE_URL, ResourceType.Encounter.name(), "test-location-id-1");

    AuditEventHelper auditEventHelper =
        createTestInstance(location, null, responseContentLocation, RestOperationTypeEnum.CREATE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("C"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("create"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Location/test-location-id-1/_history/hid-1"));
  }

  @Test
  public void testProcessAuditEventReadLocation() throws IOException {

    Location location = new Location();
    location.setId("test-location-id-1");

    AuditEventHelper auditEventHelper =
        createTestInstance(location, null, RestOperationTypeEnum.READ);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("R"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("read"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Location/test-location-id-1"));
  }

  @Test
  public void testProcessAuditEventUpdateLocation() throws IOException {

    Location location = new Location();
    location.setId("test-location-id-1");

    AuditEventHelper auditEventHelper =
        createTestInstance(location, null, RestOperationTypeEnum.UPDATE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("U"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("update"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Location/test-location-id-1"));
  }

  @Test
  public void testProcessAuditEventDeleteLocation() throws IOException {

    Location location = new Location();
    location.setId("test-location-id-1");

    AuditEventHelper auditEventHelper =
        createTestInstance(location, null, RestOperationTypeEnum.DELETE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("D"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("delete"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Location/test-location-id-1"));
  }

  @Test
  public void testProcessAuditOperationError() throws IOException {

    Location location = new Location();
    location.setId("test-location-id-1");

    OperationOutcome outcome = new OperationOutcome();
    outcome
        .addIssue()
        .setSeverity(OperationOutcome.IssueSeverity.ERROR)
        .setCode(OperationOutcome.IssueType.PROCESSING)
        .setDiagnostics("Deletion not allowed");

    AuditEventHelper auditEventHelper =
        createTestInstance(location, outcome, RestOperationTypeEnum.DELETE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertThat(auditEvent.getAction().toCode(), equalTo("D"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("delete"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Location/test-location-id-1"));
    assertThat(auditEvent.getOutcome().toCode(), equalTo("8"));
    assertThat(auditEvent.getOutcomeDesc(), equalTo("Deletion not allowed"));
  }

  @Test
  public void testProcessAuditNoRequestNoResponseBody() throws IOException {
    when(requestDetailsReader.loadRequestContents()).thenReturn(new byte[0]);
    when(requestDetailsReader.getRestOperationType()).thenReturn(RestOperationTypeEnum.DELETE);

    AuditEventHelper auditEventHelper =
        new AuditEventHelper(
            requestDetailsReader,
            "",
            String.format(
                "%s/fhir/%s/%s/_history/hid-1",
                FHIR_SERVER_BASE_URL, ResourceType.Medication.name(), "test-medication-id-1"),
            agentUserWho,
            decodedJWT,
            new Date(),
            FHIR_SERVER_BASE_URL,
            auditFhirClientMock,
            fhirContext,
            Set.of(
                AuditEvent.AuditEventAction.C.toCode(),
                AuditEvent.AuditEventAction.R.toCode(),
                AuditEvent.AuditEventAction.U.toCode(),
                AuditEvent.AuditEventAction.D.toCode(),
                AuditEvent.AuditEventAction.E.toCode()));

    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);
    verify(auditFhirClientMock).postResource(payloadResourceCaptor.capture());

    IBaseResource resource = payloadResourceCaptor.getValue();
    assertThat(resource instanceof AuditEvent, is(true));

    AuditEvent auditEvent = (AuditEvent) resource;
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("D"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("delete"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Medication/test-medication-id-1/_history/hid-1"));
    assertThat(auditEvent.getOutcome().toCode(), equalTo("0"));
    assertThat(auditEvent.getOutcomeDesc(), equalTo("Success"));
  }

  @Test
  public void testProcessAuditEventBundlePayloadRequest() throws IOException {

    when(requestDetailsReader.getRequestType()).thenReturn(RequestTypeEnum.POST);

    // Request Bundle
    Bundle requestBundle = new Bundle();
    requestBundle.setType(Bundle.BundleType.TRANSACTION);

    String patientId = "test-patient-id-1";

    Patient patient = new Patient();
    patient.setId("test-patient-id-1");

    Bundle.BundleEntryComponent postEntry = new Bundle.BundleEntryComponent();
    postEntry.setResource(patient);
    postEntry.getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("Patient");

    requestBundle.addEntry(postEntry);

    Bundle.BundleEntryComponent putEntry = new Bundle.BundleEntryComponent();
    putEntry.setResource(patient);
    putEntry.getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Patient/" + patientId);

    requestBundle.addEntry(putEntry);

    Bundle.BundleEntryComponent getEntry = new Bundle.BundleEntryComponent();
    getEntry.getRequest().setMethod(Bundle.HTTPVerb.GET).setUrl("Patient/" + patientId);

    requestBundle.addEntry(getEntry);

    Bundle.BundleEntryComponent deleteEntry = new Bundle.BundleEntryComponent();
    deleteEntry.getRequest().setMethod(Bundle.HTTPVerb.DELETE).setUrl("Patient/" + patientId);

    requestBundle.addEntry(deleteEntry);

    // Response Bundle
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
    responseBundle.setId("response-bundle-1");

    Bundle.BundleEntryComponent postResponseEntry = new Bundle.BundleEntryComponent();
    Bundle.BundleEntryResponseComponent postResponse = new Bundle.BundleEntryResponseComponent();
    postResponse.setLocation("Patient/test-patient-id-1");
    postResponseEntry.setResponse(postResponse);
    responseBundle.addEntry(postResponseEntry);

    Bundle.BundleEntryComponent putResponseEntry = new Bundle.BundleEntryComponent();
    Bundle.BundleEntryResponseComponent putResponse = new Bundle.BundleEntryResponseComponent();
    putResponse.setLocation("Patient/test-patient-id-1");
    putResponseEntry.setResponse(putResponse);
    responseBundle.addEntry(putResponseEntry);

    Bundle.BundleEntryComponent getResponseEntry = new Bundle.BundleEntryComponent();
    getResponseEntry.setResource(patient);
    Bundle.BundleEntryResponseComponent getResponse = new Bundle.BundleEntryResponseComponent();
    getResponseEntry.setResponse(getResponse);
    responseBundle.addEntry(getResponseEntry);

    Bundle.BundleEntryComponent deleteResponseEntry = new Bundle.BundleEntryComponent();
    Bundle.BundleEntryResponseComponent deleteResponse = new Bundle.BundleEntryResponseComponent();
    deleteResponse.setLocation("Patient/test-patient-id-1");
    deleteResponseEntry.setResponse(deleteResponse);
    responseBundle.addEntry(deleteResponseEntry);

    // Test logic
    AuditEventHelper auditEventHelper = createTestInstance(requestBundle, responseBundle, null);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);

    verify(auditFhirClientMock, times(4)).postResource(payloadResourceCaptor.capture());

    List<IBaseResource> resources = payloadResourceCaptor.getAllValues();

    assertThat(resources.size(), is(4));
    assertThat(resources.get(0) instanceof AuditEvent, is(true));
    assertThat(resources.get(1) instanceof AuditEvent, is(true));
    assertThat(resources.get(2) instanceof AuditEvent, is(true));
    assertThat(resources.get(3) instanceof AuditEvent, is(true));

    AuditEvent auditEvent;

    // POST
    auditEvent = (AuditEvent) resources.get(0);
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("C"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("create"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));

    // PUT
    auditEvent = (AuditEvent) resources.get(1);
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("U"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("update"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));

    // GET
    auditEvent = (AuditEvent) resources.get(2);
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("R"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("read"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));

    // DELETE
    auditEvent = (AuditEvent) resources.get(3);
    assertCommonAuditEventFields(auditEvent, true);
    assertThat(auditEvent.getAction().toCode(), equalTo("D"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("delete"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
    assertThat(
        getAuditEventPatientResourceReference(auditEvent.getEntity()),
        equalTo("Patient/test-patient-id-1"));
  }

  @Test
  public void testProcessAuditEventSearchObservations() throws IOException {
    String fullRequestUrl =
        String.format(
            "%s/Observation?_id=test-observation-id-1&_id=test-observation-id-2",
            FHIR_INFO_GATEWAY_SERVER_BASE_URL);
    when(requestDetailsReader.getCompleteUrl()).thenReturn(fullRequestUrl);
    when(requestDetailsReader.getRequestType()).thenReturn(RequestTypeEnum.GET);
    when(requestDetailsReader.getRestOperationType()).thenReturn(RestOperationTypeEnum.SEARCH_TYPE);
    when(requestDetailsReader.getCompleteUrl()).thenReturn(fullRequestUrl);
    when(requestDetailsReader.getRequestPath()).thenReturn(ResourceType.Observation.name());

    when(requestDetailsReader.getParameters())
        .thenReturn(Map.of("_id", new String[] {"test-observation-id-1", "test-observation-id-2"}));

    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
    responseBundle.setId("response-bundle-1");

    Bundle.BundleEntryComponent postResponseEntry = new Bundle.BundleEntryComponent();
    Bundle.BundleEntryResponseComponent postResponse = new Bundle.BundleEntryResponseComponent();
    postResponse.setLocation("Observation/test-observation-id-1");
    postResponseEntry.setResponse(postResponse);
    responseBundle.addEntry(postResponseEntry);

    Bundle.BundleEntryComponent putResponseEntry = new Bundle.BundleEntryComponent();
    Bundle.BundleEntryResponseComponent putResponse = new Bundle.BundleEntryResponseComponent();
    putResponse.setLocation("Observation/test-observation-id-2");
    putResponseEntry.setResponse(putResponse);
    responseBundle.addEntry(putResponseEntry);

    AuditEventHelper auditEventHelper =
        createTestInstance(null, responseBundle, RestOperationTypeEnum.SEARCH_TYPE);
    auditEventHelper.processAuditEvents();

    ArgumentCaptor<IBaseResource> payloadResourceCaptor =
        ArgumentCaptor.forClass(IBaseResource.class);

    verify(auditFhirClientMock, times(2)).postResource(payloadResourceCaptor.capture());

    List<IBaseResource> resources = payloadResourceCaptor.getAllValues();

    assertThat(resources.size(), is(2));
    assertThat(resources.get(0) instanceof AuditEvent, is(true));
    assertThat(resources.get(1) instanceof AuditEvent, is(true));

    AuditEvent auditEvent;

    // Observation 1
    auditEvent = (AuditEvent) resources.get(0);
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("E"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("search-type"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Observation/test-observation-id-1"));
    assertThat(
        getEntityQueryBySystemCode(
            auditEvent.getEntity(),
            "http://terminology.hl7.org/CodeSystem/object-role",
            ObjectRole._24.toCode()),
        equalTo(fullRequestUrl));

    assertThat(
        getEntityQueryDescriptionBySystemCode(
            auditEvent.getEntity(),
            "http://terminology.hl7.org/CodeSystem/object-role",
            ObjectRole._24.toCode()),
        equalTo("GET " + fullRequestUrl));

    // Observation 2
    auditEvent = (AuditEvent) resources.get(1);
    assertCommonAuditEventFields(auditEvent, false);
    assertThat(auditEvent.getAction().toCode(), equalTo("E"));
    assertThat(auditEvent.getSubtype().get(0).getCode(), equalTo("search-type"));
    assertThat(
        getAuditEventDomainResourceReference(auditEvent.getEntity()),
        equalTo("Observation/test-observation-id-2"));
    assertThat(
        getEntityQueryBySystemCode(
            auditEvent.getEntity(),
            "http://terminology.hl7.org/CodeSystem/object-role",
            ObjectRole._24.toCode()),
        equalTo(fullRequestUrl));
    assertThat(
        getEntityQueryDescriptionBySystemCode(
            auditEvent.getEntity(),
            "http://terminology.hl7.org/CodeSystem/object-role",
            ObjectRole._24.toCode()),
        equalTo("GET " + fullRequestUrl));
  }

  private void assertCommonAuditEventFields(AuditEvent auditEvent, boolean inPatientCompartment) {
    assertThat(
        auditEvent.getType().getSystem(),
        equalTo("http://terminology.hl7.org/CodeSystem/audit-event-type"));
    assertThat(auditEvent.getType().getCode(), equalTo("rest"));
    assertThat(auditEvent.getOutcome().toCode(), equalTo("0"));
    assertThat(auditEvent.getOutcomeDesc(), equalTo("Success"));
    assertThat(TestUtil.isSameDate(auditEvent.getRecorded(), new Date()), is(true));
    assertThat(TestUtil.isSameDate(auditEvent.getPeriod().getStart(), new Date()), is(true));
    assertThat(TestUtil.isSameDate(auditEvent.getPeriod().getEnd(), new Date()), is(true));
    assertThat(
        auditEvent.getSource().getObserver().getDisplay(),
        equalTo(FHIR_INFO_GATEWAY_SERVER_BASE_URL));

    // Assert that that destination is the FHIR server
    AuditEvent.AuditEventAgentComponent agentComponentDestination =
        getAuditEventAgentComponentByType(
            auditEvent.getAgent(),
            "D".equals(auditEvent.getAction().toCode())
                ? "http://terminology.hl7.org/CodeSystem/provenance-participant-type"
                : "http://dicom.nema.org/resources/ontology/DCM",
            "D".equals(auditEvent.getAction().toCode()) ? "custodian" : "110152");

    assertThat(agentComponentDestination.getWho().getDisplay(), equalTo(FHIR_SERVER_BASE_URL));
    assertThat(agentComponentDestination.getRequestor(), equalTo(false));
    assertThat(agentComponentDestination.getNetwork().getAddress(), equalTo(FHIR_SERVER_BASE_URL));
    assertThat(
        agentComponentDestination.getNetwork().getType(),
        equalTo(AuditEvent.AuditEventAgentNetworkType._5));

    // Assert that the source is the FHIR Info Gateway server
    AuditEvent.AuditEventAgentComponent agentComponentSource =
        getAuditEventAgentComponentByType(
            auditEvent.getAgent(),
            "http://dicom.nema.org/resources/ontology/DCM",
            "D".equals(auditEvent.getAction().toCode()) ? "110150" : "110153");
    assertThat(
        agentComponentSource.getWho().getDisplay(), equalTo(FHIR_INFO_GATEWAY_SERVER_BASE_URL));
    assertThat(agentComponentSource.getRequestor(), equalTo(false));
    assertThat(
        agentComponentSource.getNetwork().getAddress(), equalTo(FHIR_INFO_GATEWAY_SERVER_BASE_URL));
    assertThat(
        agentComponentSource.getNetwork().getType(),
        equalTo(AuditEvent.AuditEventAgentNetworkType._5));

    // Assert the authorization server
    AuditEvent.AuditEventAgentComponent agentComponentAuthServer =
        getAuditEventAgentComponentByType(
            auditEvent.getAgent(),
            AuditEventBuilder.CS_EXTRA_SECURITY_ROLE_TYPE,
            AuditEventBuilder.CS_EXTRA_SECURITY_ROLE_TYPE_CODING_AUTHSERVER);

    assertThat(agentComponentAuthServer.getRequestor(), equalTo(false));
    assertThat(
        agentComponentAuthServer.getWho().getIdentifier().getSystem(),
        equalTo("some-value/oauth-client-id"));
    assertThat(agentComponentAuthServer.getWho().getIdentifier().getValue(), equalTo("some-value"));
    assertThat(agentComponentAuthServer.getNetwork().getAddress(), equalTo("some-value"));
    assertThat(
        agentComponentAuthServer.getNetwork().getType(),
        equalTo(AuditEvent.AuditEventAgentNetworkType._5));

    // Assert the correct information recipient
    AuditEvent.AuditEventAgentComponent agentComponentInformationRecipient =
        getAuditEventAgentComponentByType(
            auditEvent.getAgent(),
            "D".equals(auditEvent.getAction().toCode())
                ? V3ParticipationType.CST.getSystem()
                : V3ParticipationType.IRCP.getSystem(),
            "D".equals(auditEvent.getAction().toCode())
                ? V3ParticipationType.CST.toCode()
                : V3ParticipationType.IRCP.toCode());
    assertThat(
        agentComponentInformationRecipient.getWho().getReference(),
        equalTo("Practitioner/test-practitioner-id"));
    assertThat(agentComponentInformationRecipient.getWho().getDisplay(), equalTo("Dr. Smith"));
    assertThat(agentComponentInformationRecipient.getRequestor(), equalTo(true));

    // AuditEvent.entity assertions
    AuditEvent.AuditEventEntityComponent entityComponentDomainResource =
        getAuditEventEntityComponentByType(
            auditEvent.getEntity(),
            "http://terminology.hl7.org/CodeSystem/audit-entity-type",
            AuditEntityType._2.toCode());
    assertThat(
        entityComponentDomainResource.getRole().getSystem(),
        equalTo("http://terminology.hl7.org/CodeSystem/object-role"));
    assertThat(entityComponentDomainResource.getRole().getCode(), equalTo(ObjectRole._4.toCode()));

    if (inPatientCompartment) {
      AuditEvent.AuditEventEntityComponent entityComponentPatient =
          getAuditEventEntityComponentByType(
              auditEvent.getEntity(),
              "http://terminology.hl7.org/CodeSystem/audit-entity-type",
              AuditEntityType._1.toCode());
      assertThat(
          entityComponentPatient.getRole().getSystem(),
          equalTo("http://terminology.hl7.org/CodeSystem/object-role"));
      assertThat(entityComponentPatient.getRole().getCode(), equalTo(ObjectRole._1.toCode()));
    }

    // Assert Request ID
    AuditEvent.AuditEventEntityComponent entityComponentRequestId =
        getAuditEventEntityComponentByType(
            auditEvent.getEntity(),
            "https://profiles.ihe.net/ITI/BALP/CodeSystem/BasicAuditEntityType",
            "XrequestId");
    assertThat(
        entityComponentRequestId.getWhat().getIdentifier().getValue(), equalTo(TEST_REQUEST_ID));
  }

  private String getEntityQueryBySystemCode(
      List<AuditEvent.AuditEventEntityComponent> components, String system, String code) {
    for (AuditEvent.AuditEventEntityComponent component : components) {
      if (system.equals(component.getRole().getSystem())
          && code.equals(component.getRole().getCode())) {
        return new String(component.getQuery(), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private String getEntityQueryDescriptionBySystemCode(
      List<AuditEvent.AuditEventEntityComponent> components, String system, String code) {
    for (AuditEvent.AuditEventEntityComponent component : components) {
      if (system.equals(component.getRole().getSystem())
          && code.equals(component.getRole().getCode())) {
        return component.getDescription();
      }
    }
    return null;
  }

  private String getAuditEventDomainResourceReference(
      List<AuditEvent.AuditEventEntityComponent> components) {
    AuditEvent.AuditEventEntityComponent compartmentOwnerComponent =
        getAuditEventEntityComponentByRole(
            components,
            "http://terminology.hl7.org/CodeSystem/object-role",
            ObjectRole._4.toCode());
    return compartmentOwnerComponent.getWhat().hasReference()
        ? compartmentOwnerComponent.getWhat().getReference()
        : compartmentOwnerComponent.getWhat().getIdentifier().getValue();
  }

  private String getAuditEventPatientResourceReference(
      List<AuditEvent.AuditEventEntityComponent> components) {
    AuditEvent.AuditEventEntityComponent entityComponentPatient =
        getAuditEventEntityComponentByType(
            components,
            "http://terminology.hl7.org/CodeSystem/audit-entity-type",
            AuditEntityType._1.toCode());
    return entityComponentPatient.getWhat().hasReference()
        ? entityComponentPatient.getWhat().getReference()
        : entityComponentPatient.getWhat().getIdentifier().getValue();
  }

  private String getAuditEventPatientResourceIdentifierSystem(
      List<AuditEvent.AuditEventEntityComponent> components) {
    AuditEvent.AuditEventEntityComponent entityComponentPatient =
        getAuditEventEntityComponentByType(
            components,
            "http://terminology.hl7.org/CodeSystem/audit-entity-type",
            AuditEntityType._1.toCode());
    return entityComponentPatient.getWhat().getIdentifier().getSystem();
  }

  private AuditEvent.AuditEventAgentComponent getAuditEventAgentComponentByType(
      List<AuditEvent.AuditEventAgentComponent> components, String system, String code) {
    for (AuditEvent.AuditEventAgentComponent component : components) {
      if (system.equals(component.getType().getCodingFirstRep().getSystem())
          && code.equals(component.getType().getCodingFirstRep().getCode())) {
        return component;
      }
    }
    return null;
  }

  private AuditEvent.AuditEventEntityComponent getAuditEventEntityComponentByRole(
      List<AuditEvent.AuditEventEntityComponent> components, String system, String code) {
    for (AuditEvent.AuditEventEntityComponent component : components) {
      if (system.equals(component.getRole().getSystem())
          && code.equals(component.getRole().getCode())) {
        return component;
      }
    }
    return null;
  }

  private AuditEvent.AuditEventEntityComponent getAuditEventEntityComponentByType(
      List<AuditEvent.AuditEventEntityComponent> components, String system, String code) {
    for (AuditEvent.AuditEventEntityComponent component : components) {
      if (system.equals(component.getType().getSystem())
          && code.equals(component.getType().getCode())) {
        return component;
      }
    }
    return null;
  }

  /**
   * The audited operation has already committed by the time AuditEvents are written, so a delivery
   * failure must be logged rather than thrown; throwing would report a successful clinical
   * operation to the client as a 500.
   */
  @Test
  public void testProcessAuditEventsSwallowsDeliveryIOException() throws IOException {
    when(auditFhirClientMock.postResource(any()))
        .thenThrow(new IOException("the audit store is unreachable"));

    Patient patient = new Patient();
    patient.addGeneralPractitioner(agentUserWho);
    String responseContentLocation =
        String.format(
            "%s/fhir/%s/%s/_history/hid-1",
            FHIR_SERVER_BASE_URL, ResourceType.Patient.name(), "test-patient-id-1");
    AuditEventHelper auditEventHelper =
        createTestInstance(patient, null, responseContentLocation, RestOperationTypeEnum.CREATE);

    auditEventHelper.processAuditEvents();

    verify(auditFhirClientMock).postResource(any());
  }

  @Test
  public void testProcessAuditEventsSwallowsErrorResponse() throws IOException {
    HttpResponse errorResponse = Mockito.mock(HttpResponse.class, Answers.RETURNS_DEEP_STUBS);
    when(errorResponse.getStatusLine().getStatusCode()).thenReturn(500);
    when(errorResponse.getEntity()).thenReturn(null);
    when(auditFhirClientMock.postResource(any())).thenReturn(errorResponse);

    Patient patient = new Patient();
    patient.addGeneralPractitioner(agentUserWho);
    String responseContentLocation =
        String.format(
            "%s/fhir/%s/%s/_history/hid-1",
            FHIR_SERVER_BASE_URL, ResourceType.Patient.name(), "test-patient-id-1");
    AuditEventHelper auditEventHelper =
        createTestInstance(patient, null, responseContentLocation, RestOperationTypeEnum.CREATE);

    auditEventHelper.processAuditEvents();

    verify(auditFhirClientMock).postResource(any());
  }

  private AuditEventHelper createTestInstance(
      @Nullable IBaseResource payload,
      @Nullable IBaseResource response,
      @Nullable RestOperationTypeEnum restOperationType) {

    String responseContentLocation =
        String.format(
            "%s/%s/%s",
            FHIR_SERVER_BASE_URL,
            payload != null ? payload.fhirType() : "",
            payload != null && payload.getIdElement() != null
                ? payload.getIdElement().toString()
                : "");

    return createTestInstance(payload, response, responseContentLocation, restOperationType);
  }

  private AuditEventHelper createTestInstance(
      @Nullable IBaseResource payload,
      @Nullable IBaseResource response,
      @Nullable String responseContentLocation,
      @Nullable RestOperationTypeEnum restOperationType) {
    when(requestDetailsReader.loadRequestContents())
        .thenReturn(
            payload != null
                ? TestUtil.resourceToString(fhirContext, payload).getBytes(StandardCharsets.UTF_8)
                : new byte[0]);

    when(requestDetailsReader.getRestOperationType()).thenReturn(restOperationType);

    return new AuditEventHelper(
        requestDetailsReader,
        response == null ? "{}" : fhirContext.newJsonParser().encodeResourceToString(response),
        responseContentLocation,
        agentUserWho,
        decodedJWT,
        new Date(),
        FHIR_SERVER_BASE_URL,
        auditFhirClientMock,
        fhirContext,
        Set.of(
            AuditEvent.AuditEventAction.C.toCode(),
            AuditEvent.AuditEventAction.R.toCode(),
            AuditEvent.AuditEventAction.U.toCode(),
            AuditEvent.AuditEventAction.D.toCode(),
            AuditEvent.AuditEventAction.E.toCode()));
  }
}
