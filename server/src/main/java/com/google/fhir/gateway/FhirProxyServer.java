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
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.server.ApacheProxyAddressStrategy;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import com.google.fhir.gateway.interfaces.AccessCheckerFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.cors.CorsConfiguration;

@WebServlet("/fhir/*")
public class FhirProxyServer extends RestfulServer {

  private static final Logger logger = LoggerFactory.getLogger(FhirProxyServer.class);

  private static final String ACCESS_CHECKER_ENV = "ACCESS_CHECKER";
  private static final String PERMISSIVE_ACCESS_CHECKER = "permissive";
  private static final String ALLOWED_QUERIES_FILE_ENV = "ALLOWED_QUERIES_FILE";
  private static final String AUDIT_EVENT_ACTIONS_CONFIG = "AUDIT_EVENT_ACTIONS_CONFIG";
  private static final Set<String> AUDIT_EVENT_ACTION_CODES =
      Set.of(
          AuditEvent.AuditEventAction.C.toCode(),
          AuditEvent.AuditEventAction.R.toCode(),
          AuditEvent.AuditEventAction.U.toCode(),
          AuditEvent.AuditEventAction.D.toCode(),
          AuditEvent.AuditEventAction.E.toCode());

  // TODO: improve this mixture of Spring based IOC with non-@Component classes. This is the
  //   only place we use Spring annotations to automatically discover AccessCheckerFactory plugins.
  //   But this implicitly means that the wrapper executable code (or container) should enable
  //   Spring's automatic scanning.
  @Autowired private Map<String, AccessCheckerFactory> accessCheckerFactories;

  public static boolean isDevMode() {
    String runMode = System.getenv("RUN_MODE");
    return "DEV".equals(runMode);
  }

  // TODO: force `initialize` to happen once the server started, not after the first query; also
  // implement a way to kill the server immediately when initialize fails.
  @Override
  protected void initialize() throws ServletException {
    // TODO make the FHIR version configurable.
    // Create a context for the appropriate version
    setFhirContext(FhirContext.forR4());
    FhirContext.forR4Cached()
        .getParserOptions()
        .setDontStripVersionsFromReferencesAtPaths("AuditEvent.entity.what");

    // Note interceptor registration order is important.
    registerCorsInterceptor();

    try {
      logger.info("Adding BearerAuthorizationInterceptor ");
      AccessCheckerFactory checkerFactory = chooseAccessCheckerFactory();
      HttpFhirClient httpFhirClient = FhirClientFactory.createFhirClientFromEnvVars();
      // This is the same instance as `httpFhirClient` unless a separate AuditEvent store is
      // configured; see FhirClientFactory#createAuditFhirClientFromEnvVars.
      HttpFhirClient auditFhirClient =
          FhirClientFactory.createAuditFhirClientFromEnvVars(httpFhirClient);
      TokenVerifier tokenVerifier = TokenVerifier.createFromEnvVars();

      String auditEventActionsConfig = System.getenv(AUDIT_EVENT_ACTIONS_CONFIG);
      String normalized =
          auditEventActionsConfig != null ? auditEventActionsConfig.trim().toUpperCase() : "";
      Set<String> auditEventActionsConfigSet =
          normalized.isEmpty() ? Set.of() : Set.of(normalized.split(""));

      for (String code : auditEventActionsConfigSet) {
        if (!AUDIT_EVENT_ACTION_CODES.contains(code.toUpperCase(Locale.ENGLISH))) {
          ExceptionUtil.throwRuntimeExceptionAndLog(
              logger, "Invalid AuditEvent Action value configured for AuditEvent logging");
        }
      }

      registerInterceptor(
          new BearerAuthorizationInterceptor(
              httpFhirClient,
              auditFhirClient,
              tokenVerifier,
              this,
              checkerFactory,
              new AllowedQueriesChecker(System.getenv(ALLOWED_QUERIES_FILE_ENV)),
              auditEventActionsConfigSet));

    } catch (IOException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(logger, "IOException while initializing", e);
    }

    setServerAddressStrategy(new ApacheProxyAddressStrategy(true));
  }

  private AccessCheckerFactory chooseAccessCheckerFactory() {
    logger.info(
        String.format(
            "List of registered access-checker factories: %s",
            Arrays.toString(accessCheckerFactories.keySet().toArray())));
    String accessCheckerName = System.getenv(ACCESS_CHECKER_ENV);
    if (isDevMode() && accessCheckerName.equals(PERMISSIVE_ACCESS_CHECKER)) {
      logger.warn(
          String.format(
              "Env. variable %s is '%s' which disables Patient access-checker (DEV mode)!",
              ACCESS_CHECKER_ENV, PERMISSIVE_ACCESS_CHECKER));
      return new PermissiveAccessChecker.Factory();
    }
    if (accessCheckerName == null || !accessCheckerFactories.containsKey(accessCheckerName)) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          String.format(
              "Environment variable %s has value %s which is not recognized!",
              ACCESS_CHECKER_ENV, accessCheckerName));
    }
    return accessCheckerFactories.get(accessCheckerName);
  }

  /**
   * This is to add a CORS interceptor for adding "Cross-Origin Resource Sharing" headers:
   * https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS. We need this for example for Javascript
   * SMART apps that need to fetch FHIR resources. Enabling CORS on all paths should be fine because
   * we don't rely on cookies for access-tokens and access to any sensitive resource is protected by
   * a Bearer access-token.
   *
   * <p>We could have used servlet filters but this is the "cleaner" native HAPI solution:
   * https://hapifhir.io/hapi-fhir/docs/security/cors.html. The drawback of this is the extra Spring
   * dependency. It is probably okay as it increased the size of the war file from 35 MB to 38 MB
   * (and opens the door to other Spring goodies too).
   */
  private void registerCorsInterceptor() {
    // Create the CORS interceptor and register it. This mostly relies on the default config used in
    // `CorsInterceptor.createDefaultCorsConfig`; the main difference is the authorization header.
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedHeaders(new ArrayList<>(Constants.CORS_ALLOWED_HEADERS));
    // Note the typo in ALLWED is coming from the HAPI library.
    config.setAllowedMethods(new ArrayList<>(Constants.CORS_ALLWED_METHODS));
    config.addAllowedHeader(Constants.HEADER_AUTHORIZATION);
    config.addAllowedOrigin("*");

    CorsInterceptor interceptor = new CorsInterceptor(config);
    registerInterceptor(interceptor);
  }
}
