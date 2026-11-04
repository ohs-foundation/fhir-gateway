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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.fhir.gateway.GenericFhirClient.GenericFhirClientBuilder;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a helper class to create appropriate FHIR clients to talk to the configured FHIR server.
 */
public class FhirClientFactory {

  private static final Logger logger = LoggerFactory.getLogger(FhirClientFactory.class);

  private static final String PROXY_TO_ENV = "PROXY_TO";
  private static final String BACKEND_TYPE_ENV = "BACKEND_TYPE";

  @VisibleForTesting static final String AUDIT_EVENT_SINK_URL_ENV = "AUDIT_EVENT_SINK_URL";

  @VisibleForTesting
  static final String AUDIT_EVENT_SINK_AUTH_TYPE_ENV = "AUDIT_EVENT_SINK_AUTH_TYPE";

  /** Reuses the credentials the gateway already holds for the proxied FHIR store. */
  @VisibleForTesting static final String AUTH_TYPE_INHERIT = "inherit";

  /** Sends no credentials at all; for an audit store that does not require authentication. */
  @VisibleForTesting static final String AUTH_TYPE_NONE = "none";

  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";

  public static HttpFhirClient createFhirClientFromEnvVars() throws IOException {
    String backendType = System.getenv(BACKEND_TYPE_ENV);
    if (backendType == null) {
      throw new IllegalArgumentException(
          String.format("The environment variable %s is not set!", BACKEND_TYPE_ENV));
    }
    String fhirStore = System.getenv(PROXY_TO_ENV);
    if (fhirStore == null) {
      throw new IllegalArgumentException(
          String.format("The environment variable %s is not set!", PROXY_TO_ENV));
    }
    return chooseHttpFhirClient(backendType, fhirStore);
  }

  /**
   * Creates the client that is used to write AuditEvents. When no separate audit store is
   * configured this is {@code gatewayFhirClient} itself, i.e. AuditEvents are written to the
   * proxied FHIR store exactly as they were before this option existed.
   *
   * @param gatewayFhirClient the client used for proxied (clinical) traffic.
   * @return a client for writing AuditEvents; never {@code null}.
   */
  public static HttpFhirClient createAuditFhirClientFromEnvVars(HttpFhirClient gatewayFhirClient) {
    return createAuditFhirClient(
        System.getenv(PROXY_TO_ENV),
        System.getenv(AUDIT_EVENT_SINK_URL_ENV),
        System.getenv(AUDIT_EVENT_SINK_AUTH_TYPE_ENV),
        gatewayFhirClient);
  }

  /**
   * The environment-free implementation of {@link #createAuditFhirClientFromEnvVars}, so that all
   * of the validation below is unit-testable without manipulating the process environment.
   */
  @VisibleForTesting
  static HttpFhirClient createAuditFhirClient(
      @Nullable String proxyTo,
      @Nullable String sinkUrl,
      @Nullable String authType,
      HttpFhirClient gatewayFhirClient) {
    Preconditions.checkNotNull(gatewayFhirClient);

    if (isBlank(sinkUrl)) {
      if (!isBlank(authType)) {
        logger.warn(
            "{} is set to '{}' but {} is not set; AuditEvents are written to the proxied FHIR"
                + " store.",
            AUDIT_EVENT_SINK_AUTH_TYPE_ENV,
            authType,
            AUDIT_EVENT_SINK_URL_ENV);
      }
      return gatewayFhirClient;
    }

    URI sinkUri = parseHttpUri(sinkUrl, AUDIT_EVENT_SINK_URL_ENV);
    URI proxyUri = isBlank(proxyTo) ? null : parseHttpUri(proxyTo, PROXY_TO_ENV);
    boolean sameOrigin = isSameOrigin(sinkUri, proxyUri);

    validateTransport(sinkUri, proxyUri);

    if (AUTH_TYPE_NONE.equals(resolveAuthType(authType, sameOrigin))) {
      logger.info("AuditEvents are written to {} with no credentials.", sinkUri);
      return new GenericFhirClientBuilder().setFhirStore(sinkUrl).build();
    }

    if (!sameOrigin) {
      logger.warn(
          "The credentials the gateway uses for the FHIR store at {} will also be sent to the"
              + " separate AuditEvent store at {}. Only do this if both stores are in the same"
              + " trust domain.",
          proxyUri,
          sinkUri);
    }
    return new DelegatingAuthFhirClient(sinkUrl, gatewayFhirClient);
  }

  private static HttpFhirClient chooseHttpFhirClient(String backendType, String fhirStore)
      throws IOException {
    // TODO add an enum if the list of special FHIR servers grow and rename HAPI to GENERIC.
    if (backendType.equals("GCP")) {
      return new GcpFhirClient(fhirStore, GcpFhirClient.createCredentials());
    }

    if (backendType.equals("HAPI")) {
      return new GenericFhirClientBuilder().setFhirStore(fhirStore).build();
    }
    throw new IllegalArgumentException(
        String.format(
            "The environment variable %s is not set to either GCP or HAPI!", BACKEND_TYPE_ENV));
  }

  /**
   * There is no safe default for the auth type once the audit store is on a different origin:
   * defaulting to {@code inherit} would silently forward the clinical store's credentials to a host
   * named in a different setting, while defaulting to {@code none} would silently produce 401s and
   * lose every audit record. So we refuse to guess.
   */
  private static String resolveAuthType(@Nullable String authType, boolean sameOrigin) {
    if (isBlank(authType)) {
      if (!sameOrigin) {
        throw new IllegalArgumentException(
            String.format(
                "The environment variable %s must be set to either '%s' or '%s' when %s points to a"
                    + " different origin than %s.",
                AUDIT_EVENT_SINK_AUTH_TYPE_ENV,
                AUTH_TYPE_INHERIT,
                AUTH_TYPE_NONE,
                AUDIT_EVENT_SINK_URL_ENV,
                PROXY_TO_ENV));
      }
      return AUTH_TYPE_INHERIT;
    }
    String normalized = authType.trim().toLowerCase(Locale.ENGLISH);
    if (!AUTH_TYPE_INHERIT.equals(normalized) && !AUTH_TYPE_NONE.equals(normalized)) {
      throw new IllegalArgumentException(
          String.format(
              "The environment variable %s has value '%s' which is not recognized; expected '%s' or"
                  + " '%s'.",
              AUDIT_EVENT_SINK_AUTH_TYPE_ENV, authType, AUTH_TYPE_INHERIT, AUTH_TYPE_NONE));
    }
    return normalized;
  }

  /**
   * Refuses a transport downgrade: a deployment that protects clinical traffic with TLS must not
   * ship its audit trail, or the credentials that reach the audit store, in the clear.
   */
  private static void validateTransport(URI sinkUri, @Nullable URI proxyUri) {
    if (HTTPS_SCHEME.equalsIgnoreCase(sinkUri.getScheme()) || isLoopback(sinkUri.getHost())) {
      return;
    }
    if (proxyUri != null && HTTPS_SCHEME.equalsIgnoreCase(proxyUri.getScheme())) {
      throw new IllegalArgumentException(
          String.format(
              "The environment variable %s is an insecure http URL (%s) while %s uses https."
                  + " Refusing to send AuditEvents, and possibly credentials, over plain http.",
              AUDIT_EVENT_SINK_URL_ENV, sinkUri, PROXY_TO_ENV));
    }
    logger.warn(
        "The AuditEvent store at {} is reached over plain http; AuditEvents carry patient"
            + " references and should be sent over https outside of a development setup.",
        sinkUri);
  }

  private static URI parseHttpUri(String value, String envVarName) {
    URI uri;
    try {
      uri = new URI(value.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          String.format(
              "The environment variable %s has value '%s' which is not a valid URL: %s",
              envVarName, value, e.getMessage()),
          e);
    }
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !(HTTP_SCHEME.equalsIgnoreCase(uri.getScheme())
            || HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalArgumentException(
          String.format(
              "The environment variable %s has value '%s'; an absolute http or https URL is"
                  + " expected, e.g. https://audit-store.example/fhir",
              envVarName, value));
    }
    if (uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          String.format(
              "The environment variable %s has value '%s'; a base URL with no query string or"
                  + " fragment is expected.",
              envVarName, value));
    }
    return uri;
  }

  private static boolean isSameOrigin(URI sinkUri, @Nullable URI proxyUri) {
    if (proxyUri == null) {
      return false;
    }
    return sinkUri.getScheme().equalsIgnoreCase(proxyUri.getScheme())
        && sinkUri.getHost().equalsIgnoreCase(proxyUri.getHost())
        && effectivePort(sinkUri) == effectivePort(proxyUri);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /** Note this is deliberately a string check; resolving names at startup would be a DNS call. */
  private static boolean isLoopback(String host) {
    return "localhost".equalsIgnoreCase(host)
        || "::1".equals(host)
        || "[::1]".equals(host)
        || host.startsWith("127.");
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }
}
