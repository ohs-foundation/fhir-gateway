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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import org.apache.http.HttpHeaders;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class JwtUtilTest {

  private static final String SUBJECT = "test-subject";
  private static final String ISSUER = "https://issuer.example.com/realms/test";

  @Mock private RequestDetailsReader requestMock;

  private static String signedToken() {
    return JWT.create().withSubject(SUBJECT).withIssuer(ISSUER).sign(Algorithm.none());
  }

  @Test
  public void getDecodedJwtFromRequestDetails_decodesBearerToken() {
    when(requestMock.getHeader(HttpHeaders.AUTHORIZATION))
        .thenReturn(TokenVerifier.BEARER_PREFIX + signedToken());

    DecodedJWT decodedJWT = JwtUtil.getDecodedJwtFromRequestDetails(requestMock);

    assertThat(decodedJWT, notNullValue());
    assertThat(decodedJWT.getSubject(), equalTo(SUBJECT));
    assertThat(decodedJWT.getIssuer(), equalTo(ISSUER));
  }

  /**
   * Regression test: the Gateway serves endpoints such as the CapabilityStatement at {@code
   * /metadata} without requiring a token, so {@link
   * com.google.fhir.gateway.interfaces.AccessDecision#getUserWho} reaches this method with no {@code
   * Authorization} header whenever AuditEvent logging is enabled. That used to throw a {@link
   * NullPointerException} mid-response.
   */
  @Test
  public void getDecodedJwtFromRequestDetails_returnsNullWhenNoAuthorizationHeader() {
    when(requestMock.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

    assertThat(JwtUtil.getDecodedJwtFromRequestDetails(requestMock), nullValue());
  }

  @Test
  public void getDecodedJwtFromRequestDetails_returnsNullWhenSchemeIsNotBearer() {
    when(requestMock.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Basic dXNlcjpwYXNzd29yZA==");

    assertThat(JwtUtil.getDecodedJwtFromRequestDetails(requestMock), nullValue());
  }

  @Test
  public void getDecodedJwtFromRequestDetails_returnsNullWhenRequestIsNull() {
    assertThat(JwtUtil.getDecodedJwtFromRequestDetails(null), nullValue());
  }
}
