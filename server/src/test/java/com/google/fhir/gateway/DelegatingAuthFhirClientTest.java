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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URISyntaxException;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DelegatingAuthFhirClientTest {

  private static final String AUDIT_STORE = "https://audit-store.example/fhir";
  private static final String CLINICAL_STORE = "https://clinical-store.example/fhir";

  @Mock private HttpFhirClient authDelegate;

  @Before
  public void setUp() {
    when(authDelegate.getBaseUrl()).thenReturn(CLINICAL_STORE);
  }

  @Test
  public void getBaseUrlIsTheAuditStore() {
    DelegatingAuthFhirClient testInstance = new DelegatingAuthFhirClient(AUDIT_STORE, authDelegate);

    assertThat(testInstance.getBaseUrl(), equalTo(AUDIT_STORE));
  }

  @Test
  public void getBaseUrlStripsTrailingSlashes() {
    DelegatingAuthFhirClient testInstance =
        new DelegatingAuthFhirClient(AUDIT_STORE + "//", authDelegate);

    assertThat(testInstance.getBaseUrl(), equalTo(AUDIT_STORE));
  }

  @Test
  public void getUriForResourceIsRelativeToTheAuditStore() throws URISyntaxException {
    DelegatingAuthFhirClient testInstance = new DelegatingAuthFhirClient(AUDIT_STORE, authDelegate);

    assertThat(
        testInstance.getUriForResource("AuditEvent").toString(),
        equalTo(AUDIT_STORE + "/AuditEvent"));
  }

  @Test
  public void getAuthHeaderComesFromTheDelegate() {
    Header delegateHeader = new BasicHeader("Authorization", "Bearer some-token");
    when(authDelegate.getAuthHeader()).thenReturn(delegateHeader);
    DelegatingAuthFhirClient testInstance = new DelegatingAuthFhirClient(AUDIT_STORE, authDelegate);

    assertThat(testInstance.getAuthHeader(), equalTo(delegateHeader));
  }

  /**
   * The delegate is where an expiring access token is refreshed (see GcpFhirClient), so caching the
   * returned Header would start producing 401s once the token's lifetime is up.
   */
  @Test
  public void getAuthHeaderIsNotCachedBetweenCalls() {
    when(authDelegate.getAuthHeader())
        .thenReturn(new BasicHeader("Authorization", "Bearer first-token"))
        .thenReturn(new BasicHeader("Authorization", "Bearer second-token"));
    DelegatingAuthFhirClient testInstance = new DelegatingAuthFhirClient(AUDIT_STORE, authDelegate);

    assertThat(testInstance.getAuthHeader().getValue(), equalTo("Bearer first-token"));
    assertThat(testInstance.getAuthHeader().getValue(), equalTo("Bearer second-token"));
    verify(authDelegate, times(2)).getAuthHeader();
  }

  @Test(expected = IllegalArgumentException.class)
  public void blankFhirStoreIsRejected() {
    new DelegatingAuthFhirClient("  ", authDelegate);
  }

  @Test(expected = NullPointerException.class)
  public void nullDelegateIsRejected() {
    new DelegatingAuthFhirClient(AUDIT_STORE, null);
  }
}
