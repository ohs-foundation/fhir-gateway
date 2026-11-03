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
package com.google.fhir.gateway.interfaces;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Strings;
import com.google.fhir.gateway.JwtUtil;
import java.io.IOException;
import javax.annotation.Nullable;
import org.apache.http.HttpResponse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;

public interface AccessDecision {

  /**
   * @return true iff access was granted.
   */
  boolean canAccess();

  /**
   * Allows the incoming request mutation based on the access decision.
   *
   * <p>Response is used to mutate the incoming request before executing the FHIR operation. We
   * currently only support query parameters update for GET Http method. This is expected to be
   * called after checking the access using @canAccess method. Mutating the request before checking
   * access can have side effect of wrong access check.
   *
   * @param requestDetailsReader details about the resource and operation requested
   * @return mutation to be applied on the incoming request or null if no mutation required
   */
  @Nullable
  RequestMutation getRequestMutation(RequestDetailsReader requestDetailsReader);

  /**
   * Depending on the outcome of the FHIR operations, this does any post-processing operations that
   * are related to access policies. This is expected to be called only if the actual FHIR operation
   * is finished successfully.
   *
   * <p>An example of this is when a new patient is created as the result of the query and that
   * patient ID should be added to some access lists.
   *
   * @param request the client to server request details
   * @param response the response returned from the FHIR store
   * @return the response entity content (with any post-processing modifications needed) if this
   *     reads the response; otherwise null. Note that we should try to avoid reading the whole
   *     content in memory whenever it is not needed for post-processing.
   */
  String postProcess(RequestDetailsReader request, HttpResponse response) throws IOException;

  /**
   * Returns a Reference to the user that performed the audit event action.
   *
   * <p>The Gateway expects a JWT token issued after authentication. We can therefore by default
   * extract enough claims to satisfy the requirements of the minimal audit event pattern as defined
   * by the IHE Basic Audit Logging Profiles IG.
   *
   * <p>A user may override this method in their AccessDecision and return a custom Reference with
   * additional (or less) information e.g. using {@link Reference}.setReference() to provide the
   * actual Practitioner resource id.
   *
   * @param request the client to server request details
   * @return the {@link Reference} to the user
   */
  @Nullable
  default Reference getUserWho(RequestDetailsReader request) {
    DecodedJWT decodedJWT;
    try {
      decodedJWT = JwtUtil.getDecodedJwtFromRequestDetails(request);
      if (decodedJWT == null) {
        // Unauthenticated request, e.g. the CapabilityStatement at /metadata, which the Gateway
        // serves without a token. There is no actor to attribute an AuditEvent to.
        return null;
      }
      String name =
          JwtUtil.getClaimOrDefault(
              decodedJWT,
              JwtUtil.CLAIM_IHE_IUA_SUBJECT_NAME,
              ""); // First try with the IHE IUA claim name defined by BALP IG before OpenID
      // connect/Keycloak default
      name =
          Strings.isNullOrEmpty(name)
              ? JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_NAME, "")
              : name;
      String subject = JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_SUBJECT, "");
      String issuer = JwtUtil.getClaimOrDefault(decodedJWT, JwtUtil.CLAIM_ISSUER, "");

      return new Reference()
          .setType(ResourceType.Practitioner.name())
          .setDisplay(name)
          .setIdentifier(new Identifier().setSystem(issuer).setValue(subject));

    } catch (JWTDecodeException e) {
      return null;
    }
  }
}
