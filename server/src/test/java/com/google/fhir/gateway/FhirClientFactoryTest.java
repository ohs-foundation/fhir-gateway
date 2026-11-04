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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import com.google.fhir.gateway.GenericFhirClient.GenericFhirClientBuilder;
import org.junit.Test;

/**
 * Tests for the audit-store selection logic of {@link FhirClientFactory}. These all drive the
 * environment-free {@code createAuditFhirClient} so that no process environment manipulation is
 * needed.
 */
public class FhirClientFactoryTest {

  private static final String CLINICAL_STORE = "https://clinical-store.example/fhir";
  private static final String AUDIT_STORE = "https://audit-store.example/fhir";

  private final HttpFhirClient gatewayClient =
      new GenericFhirClientBuilder().setFhirStore(CLINICAL_STORE).build();

  @Test
  public void noSinkUrlReusesTheGatewayClient() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(CLINICAL_STORE, null, null, gatewayClient);

    assertThat(auditClient, is(sameInstance(gatewayClient)));
  }

  @Test
  public void blankSinkUrlReusesTheGatewayClient() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(CLINICAL_STORE, "   ", "none", gatewayClient);

    assertThat(auditClient, is(sameInstance(gatewayClient)));
  }

  @Test
  public void inheritAuthCreatesADelegatingClient() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(
            CLINICAL_STORE, AUDIT_STORE, "inherit", gatewayClient);

    assertThat(auditClient, is(instanceOf(DelegatingAuthFhirClient.class)));
    assertThat(auditClient.getBaseUrl(), equalTo(AUDIT_STORE));
  }

  @Test
  public void noneAuthCreatesAGenericClient() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(CLINICAL_STORE, AUDIT_STORE, "none", gatewayClient);

    assertThat(auditClient, is(instanceOf(GenericFhirClient.class)));
    assertThat(auditClient.getBaseUrl(), equalTo(AUDIT_STORE));
  }

  @Test
  public void authTypeIsCaseAndWhitespaceInsensitive() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(
            CLINICAL_STORE, AUDIT_STORE, " Inherit ", gatewayClient);

    assertThat(auditClient, is(instanceOf(DelegatingAuthFhirClient.class)));
  }

  /** A same-origin sink is provably today's behaviour, so the auth type may be left unset. */
  @Test
  public void sameOriginSinkDefaultsToInheritedAuth() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(
            CLINICAL_STORE, CLINICAL_STORE + "-audit", null, gatewayClient);

    assertThat(auditClient, is(instanceOf(DelegatingAuthFhirClient.class)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void differentOriginSinkRequiresAnExplicitAuthType() {
    FhirClientFactory.createAuditFhirClient(CLINICAL_STORE, AUDIT_STORE, null, gatewayClient);
  }

  @Test(expected = IllegalArgumentException.class)
  public void unknownAuthTypeIsRejected() {
    FhirClientFactory.createAuditFhirClient(CLINICAL_STORE, AUDIT_STORE, "oauth2", gatewayClient);
  }

  /**
   * A deployment that protects clinical traffic with TLS must not ship its audit trail in clear.
   */
  @Test(expected = IllegalArgumentException.class)
  public void insecureSinkIsRejectedWhenTheClinicalStoreUsesHttps() {
    FhirClientFactory.createAuditFhirClient(
        CLINICAL_STORE, "http://audit-store.example/fhir", "none", gatewayClient);
  }

  @Test
  public void insecureSinkIsAllowedWhenTheClinicalStoreIsAlsoInsecure() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(
            "http://hapi:8080/fhir", "http://hapi-audit:8080/fhir", "none", gatewayClient);

    assertThat(auditClient.getBaseUrl(), equalTo("http://hapi-audit:8080/fhir"));
  }

  @Test
  public void insecureLoopbackSinkIsAllowed() {
    HttpFhirClient auditClient =
        FhirClientFactory.createAuditFhirClient(
            CLINICAL_STORE, "http://localhost:8080/fhir", "none", gatewayClient);

    assertThat(auditClient.getBaseUrl(), equalTo("http://localhost:8080/fhir"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void relativeSinkUrlIsRejected() {
    FhirClientFactory.createAuditFhirClient(CLINICAL_STORE, "/fhir", "none", gatewayClient);
  }

  @Test(expected = IllegalArgumentException.class)
  public void nonHttpSinkUrlIsRejected() {
    FhirClientFactory.createAuditFhirClient(
        CLINICAL_STORE, "file:///tmp/audit", "none", gatewayClient);
  }

  @Test(expected = IllegalArgumentException.class)
  public void sinkUrlWithAQueryStringIsRejected() {
    FhirClientFactory.createAuditFhirClient(
        CLINICAL_STORE, AUDIT_STORE + "?_format=json", "none", gatewayClient);
  }
}
