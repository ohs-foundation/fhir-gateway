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

import com.google.common.base.Preconditions;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.http.Header;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link HttpFhirClient} that talks to its own FHIR store while reusing the credentials of
 * another client. This is what allows AuditEvents to be written to a store that is separate from
 * the proxied (clinical) store without configuring a second set of credentials.
 *
 * <p>Note this wraps rather than extends a concrete client, so it works for any backend type; it
 * does not need to know whether the credentials it borrows are a GCP access token, no credentials
 * at all, or something added later.
 */
public final class DelegatingAuthFhirClient extends HttpFhirClient {

  private static final Logger logger = LoggerFactory.getLogger(DelegatingAuthFhirClient.class);

  private final String fhirStore;
  private final HttpFhirClient authDelegate;

  public DelegatingAuthFhirClient(String fhirStore, HttpFhirClient authDelegate) {
    Preconditions.checkArgument(
        fhirStore != null && !fhirStore.isBlank(),
        "The FHIR store of a delegated client must be set!");
    // Remove trailing '/'s to be consistent with GcpFhirClient.
    this.fhirStore = fhirStore.replaceAll("/+$", "");
    this.authDelegate = Preconditions.checkNotNull(authDelegate);
    logger.info(
        "Initialized a client for FHIR store {} which reuses the credentials of {}",
        this.fhirStore,
        authDelegate.getBaseUrl());
  }

  @Override
  protected String getBaseUrl() {
    return fhirStore;
  }

  @Override
  protected URI getUriForResource(String resourcePath) throws URISyntaxException {
    String uri = String.format("%s/%s", fhirStore, resourcePath);
    URIBuilder uriBuilder = new URIBuilder(uri);
    return uriBuilder.build();
  }

  @Override
  protected Header getAuthHeader() {
    // This is delegated on every call and never memoized on purpose: for GcpFhirClient this is
    // where an expiring access token is refreshed, so a cached Header starts returning 401s once
    // the token's lifetime (about an hour) is up.
    return authDelegate.getAuthHeader();
  }
}
