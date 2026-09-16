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
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.IRestfulResponse;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessCheckerFactory;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

@Interceptor
public class BearerAuthorizationInterceptor {

  private static final Logger logger =
      LoggerFactory.getLogger(BearerAuthorizationInterceptor.class);

  private static final String DEFAULT_CONTENT_TYPE = "application/json; charset=UTF-8";

  private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";

  private static final String CONTENT_LOCATION_HEADER = "content-Location";

  private static final String GZIP_ENCODING_VALUE = "gzip";

  // See https://hl7.org/fhir/smart-app-launch/conformance.html#using-well-known
  @VisibleForTesting static final String WELL_KNOWN_CONF_PATH = ".well-known/smart-configuration";

  // For fetching CapabilityStatement: https://www.hl7.org/fhir/http.html#capabilities
  @VisibleForTesting static final String METADATA_PATH = "metadata";

  private final TokenVerifier tokenVerifier;
  private final RestfulServer server;
  private final HttpFhirClient fhirClient;
  private final HttpFhirClient auditFhirClient;
  private final AccessCheckerFactory accessFactory;
  private final AllowedQueriesChecker allowedQueriesChecker;
  private final Set<String> auditEventActionsConfigSet;

  BearerAuthorizationInterceptor(
      HttpFhirClient fhirClient,
      HttpFhirClient auditFhirClient,
      TokenVerifier tokenVerifier,
      RestfulServer server,
      AccessCheckerFactory accessFactory,
      AllowedQueriesChecker allowedQueriesChecker,
      Set<String> auditEventActionsConfigSet)
      throws IOException {
    Preconditions.checkNotNull(fhirClient);
    Preconditions.checkNotNull(auditFhirClient);
    Preconditions.checkNotNull(server);
    this.server = server;
    this.fhirClient = fhirClient;
    this.auditFhirClient = auditFhirClient;
    this.tokenVerifier = tokenVerifier;
    this.accessFactory = accessFactory;
    this.allowedQueriesChecker = allowedQueriesChecker;
    this.auditEventActionsConfigSet = auditEventActionsConfigSet;
    // Set once here rather than per request: this mutates the process-wide cached R4 context, and
    // it is what preserves `_history` in AuditEvent.entity.what when the AuditEvent is encoded.
    // FhirProxyServer.initialize() does the same; this covers callers that construct the
    // interceptor directly, e.g. tests.
    FhirContext.forR4Cached()
        .getParserOptions()
        .setDontStripVersionsFromReferencesAtPaths("AuditEvent.entity.what");
    logger.info("Created proxy to the FHIR store " + this.fhirClient.getBaseUrl());
    if (!auditEventActionsConfigSet.isEmpty()) {
      logger.info("AuditEvents are written to " + this.auditFhirClient.getBaseUrl());
    }
  }

  private AuthorizationDto checkAuthorization(RequestDetails requestDetails) {
    if (METADATA_PATH.equals(requestDetails.getRequestPath())) {
      // No further check is required; provide CapabilityStatement with security information.
      // Note this is potentially an expensive resource to produce because of its size and parsings.
      // Abuse of this open endpoint should be blocked by DDOS prevention means.
      return AuthorizationDto.builder()
          .accessDecision(CapabilityPostProcessor.getInstance(server.getFhirContext()))
          .build();
    }
    RequestDetailsReader requestDetailsReader = new RequestDetailsToReader(requestDetails);
    AccessDecision unauthenticatedQueriesDecision =
        allowedQueriesChecker.checkUnAuthenticatedAccess(requestDetailsReader);
    if (unauthenticatedQueriesDecision.canAccess()) {
      return AuthorizationDto.builder().accessDecision(unauthenticatedQueriesDecision).build();
    }
    // Check the Bearer token to be a valid JWT with required claims.
    String authHeader = requestDetails.getHeader("Authorization");
    if (authHeader == null) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger, "No Authorization header provided!", AuthenticationException.class);
    }
    DecodedJWT decodedJwt = tokenVerifier.decodeAndVerifyBearerToken(authHeader);
    FhirContext fhirContext = server.getFhirContext();
    AccessDecision allowedQueriesDecision = allowedQueriesChecker.checkAccess(requestDetailsReader);
    if (allowedQueriesDecision.canAccess()) {
      return AuthorizationDto.builder()
          .decodedJWT(decodedJwt)
          .accessDecision(allowedQueriesDecision)
          .build();
    }
    PatientFinderImp patientFinder = PatientFinderImp.getInstance(fhirContext);
    AccessChecker accessChecker =
        accessFactory.create(decodedJwt, fhirClient, fhirContext, patientFinder);
    if (accessChecker == null) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger, "Cannot create an AccessChecker!", AuthenticationException.class);
    }

    AccessDecision outcome = accessChecker.checkAccess(requestDetailsReader);
    if (!outcome.canAccess()) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          String.format(
              "User is not authorized to %s %s",
              requestDetails.getRequestType(), requestDetails.getCompleteUrl()),
          ForbiddenOperationException.class);
    }
    return AuthorizationDto.builder().decodedJWT(decodedJwt).accessDecision(outcome).build();
  }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
  public boolean authorizeRequest(RequestDetails requestDetails) {
    Preconditions.checkArgument(requestDetails instanceof ServletRequestDetails);
    ServletRequestDetails servletDetails = (ServletRequestDetails) requestDetails;
    logger.info(
        "Started authorization check for {} {}",
        requestDetails.getRequestType(),
        requestDetails.getCompleteUrl());
    final String requestPath = requestDetails.getRequestPath();
    if (WELL_KNOWN_CONF_PATH.equals(requestPath)) {
      // No authorization is needed for the well-known URL.
      serveWellKnown(servletDetails);
      return false;
    }

    Date periodStartTime = new Date();

    AuthorizationDto authorizationDto = checkAuthorization(requestDetails);
    AccessDecision outcome = authorizationDto.getAccessDecision();
    mutateRequest(requestDetails, outcome);
    logger.debug("Authorized request path " + requestPath);
    try {
      HttpResponse response = fhirClient.handleRequest(servletDetails);
      HttpUtil.validateResponseEntityExistsOrFail(response, requestPath);
      // TODO communicate post-processing failures to the client; see:
      //   https://github.com/google/fhir-access-proxy/issues/66

      String content = null;
      RequestDetailsReader requestDetailsReader = new RequestDetailsToReader(requestDetails);
      if (HttpUtil.isResponseValid(response)) {
        try {
          // For post-processing rationale/example see b/207589782#comment3.
          content = outcome.postProcess(requestDetailsReader, response);
        } catch (Exception e) {
          // Note this is after a successful fetch/update of the FHIR store. That success must be
          // passed to the client even if the access related post-processing fails.
          logger.error(
              "Exception in access related post-processing for {} {}",
              requestDetails.getRequestType(),
              requestDetails.getRequestPath(),
              e);
        }
      }
      HttpEntity entity = response.getEntity();
      logger.debug(String.format("The response for %s is %s ", requestPath, response));
      logger.info("FHIR store response length: " + entity.getContentLength());
      IRestfulResponse proxyResponse = requestDetails.getResponse();
      for (Header header : fhirClient.responseHeadersToKeep(response)) {
        proxyResponse.addHeader(header.getName(), header.getValue());
      }
      // This should be called after adding headers.
      // TODO verify DEFAULT_CONTENT_TYPE/CHARSET are compatible with `entity.getContentType()`.
      Writer writer =
          proxyResponse.getResponseWriter(
              response.getStatusLine().getStatusCode(),
              DEFAULT_CONTENT_TYPE,
              Constants.CHARSET_NAME_UTF8,
              sendGzippedResponse(servletDetails));
      Reader reader;
      if (content != null) {
        // We can read the entity body stream only once; in this case we have already done that.
        reader = new StringReader(content);
      } else {
        reader = HttpUtil.readerFromEntity(entity);
      }

      if (!auditEventActionsConfigSet.isEmpty()) {
        Reference agentUserWho = outcome.getUserWho(requestDetailsReader);
        if (agentUserWho != null) {
          // Auditing needs the whole response body, so buffer it. An IOException here is a genuine
          // failure to read the FHIR store's response and is handled by the catch below.
          StringWriter responseStringWriter = new StringWriter();
          reader.transferTo(responseStringWriter);
          String responseStringContent = responseStringWriter.toString();

          // Restore the reader before any audit work is attempted, so that what the client gets
          // back cannot depend on whether auditing succeeds.
          reader = new StringReader(responseStringContent);

          processAuditEvents(
              requestDetailsReader,
              responseStringContent,
              response.getFirstHeader(CONTENT_LOCATION_HEADER),
              agentUserWho,
              authorizationDto.getDecodedJWT(),
              periodStartTime);
        }
      }

      replaceAndCopyResponse(reader, writer, server.getServerBaseForRequest(servletDetails));

    } catch (IOException e) {
      logger.error(
          "Exception for resource {} method {} with error: {}",
          requestPath,
          servletDetails.getServletRequest().getMethod(),
          e.getMessage());
      ExceptionUtil.throwRuntimeExceptionAndLog(logger, e.getMessage(), e);
    }

    // The request processing stops here, hence returning false.
    return false;
  }

  /**
   * Generates this request's AuditEvents and writes them to the audit FHIR store.
   *
   * <p>Note this runs after a successful fetch/update of the FHIR store, so that success must be
   * passed to the client even if auditing fails; every failure here is logged and swallowed. The
   * caller has already restored the response reader, hence nothing this method does can affect the
   * response body.
   */
  private void processAuditEvents(
      RequestDetailsReader requestDetailsReader,
      String responseContent,
      @Nullable Header contentLocationHeader,
      Reference agentUserWho,
      @Nullable DecodedJWT decodedJWT,
      Date periodStartTime) {
    try {
      AuditEventHelper auditEventHelper =
          new AuditEventHelper(
              requestDetailsReader,
              responseContent,
              contentLocationHeader != null ? contentLocationHeader.getValue() : null,
              agentUserWho,
              decodedJWT,
              periodStartTime,
              fhirClient.getBaseUrl(),
              auditFhirClient,
              server.getFhirContext(),
              auditEventActionsConfigSet);
      auditEventHelper.processAuditEvents();
    } catch (Exception e) {
      logger.error(
          "Exception while processing AuditEvents for {} {}",
          requestDetailsReader.getRequestType(),
          requestDetailsReader.getRequestPath(),
          e);
    }
  }

  private boolean sendGzippedResponse(ServletRequestDetails requestDetails) {
    // we send gzipped encoded response to client only if they requested so
    String acceptEncodingValue = requestDetails.getHeader(ACCEPT_ENCODING_HEADER.toLowerCase());
    if (acceptEncodingValue == null) {
      return false;
    }
    return acceptEncodingValue.toLowerCase(Locale.ENGLISH).contains(GZIP_ENCODING_VALUE);
  }

  /**
   * Reads the content from the FHIR store response `entity`, replaces any FHIR store URLs by the
   * corresponding proxy URLs, and write the modified response to the proxy response `writer`.
   *
   * @param entityContentReader a reader for the entity content of the FHIR store response
   * @param writer the writer for proxy response
   * @param proxyBase the base URL of the proxy
   */
  private void replaceAndCopyResponse(Reader entityContentReader, Writer writer, String proxyBase)
      throws IOException {
    // To make this more efficient, this only does a string search/replace; we may need to add
    // proper URL parsing if we need to address edge cases in URL no-op changes. This string
    // matching can be done more efficiently if needed, but we should avoid loading the full
    // stream in memory.
    String fhirStoreUrl = fhirClient.getBaseUrl();
    int numMatched = 0;
    int n;
    while ((n = entityContentReader.read()) >= 0) {
      char c = (char) n;
      if (fhirStoreUrl.charAt(numMatched) == c) {
        numMatched++;
        if (numMatched == fhirStoreUrl.length()) {
          // A match found; replace it with proxy's base URL.
          writer.write(proxyBase);
          numMatched = 0;
        }
      } else {
        writer.write(fhirStoreUrl.substring(0, numMatched));
        writer.write(c);
        numMatched = 0;
      }
    }
    if (numMatched > 0) {
      // Handle any remaining characters that partially matched.
      writer.write(fhirStoreUrl.substring(0, numMatched));
    }
    writer.close();
  }

  private void serveWellKnown(ServletRequestDetails request) {
    IRestfulResponse proxyResponse = request.getResponse();
    try {
      Writer writer =
          proxyResponse.getResponseWriter(
              HttpStatus.SC_OK, DEFAULT_CONTENT_TYPE, Constants.CHARSET_NAME_UTF8, false);
      writer.write(tokenVerifier.getWellKnownConfig());
      writer.close();
    } catch (IOException e) {
      logger.error(
          String.format("Exception serving %s with error %s", request.getRequestPath(), e));
      ExceptionUtil.throwRuntimeExceptionAndLog(logger, e.getMessage(), e);
    }
  }

  @VisibleForTesting
  void mutateRequest(RequestDetails requestDetails, AccessDecision accessDecision) {
    RequestMutation mutation =
        accessDecision.getRequestMutation(new RequestDetailsToReader(requestDetails));
    if (mutation == null
        || (CollectionUtils.isEmpty(mutation.getAdditionalQueryParams())
            && CollectionUtils.isEmpty(mutation.getDiscardQueryParams()))) {
      return;
    }

    mutation.getDiscardQueryParams().forEach(requestDetails::removeParameter);

    mutation
        .getAdditionalQueryParams()
        .forEach((key, value) -> requestDetails.addParameter(key, value.toArray(new String[0])));
  }

  @Builder
  @Getter
  public static class AuthorizationDto {
    private DecodedJWT decodedJWT;
    private AccessDecision accessDecision;
  }
}
