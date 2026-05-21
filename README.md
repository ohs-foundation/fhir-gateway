# FHIR Information Gateway

<!-- Build status of the main branch -->

[![Build Status](https://storage.googleapis.com/fhir-proxy-build-badges/build.svg)](https://storage.googleapis.com/fhir-proxy-build-badges/build.html)
[![codecov](https://codecov.io/gh/google/fhir-gateway/branch/main/graph/badge.svg)](https://app.codecov.io/gh/google/fhir-gateway/tree/main)

FHIR Information Gateway is a simple access-control proxy that sits in front of
a [FHIR](https://www.hl7.org/fhir/) store (e.g., a
[HAPI FHIR](https://hapifhir.io/) server,
[GCP FHIR store](https://cloud.google.com/healthcare-api/docs/concepts/fhir),
etc.) and controls access to FHIR resources.

Note: "gateway" and "proxy" are used interchangably here, as the gateway is
implemented as a proxy server.

The authorization and access-control have three components; one of them is this
access proxy. The other two are an Identity Provider (IDP) and an Authorization
server (AuthZ). The responsibility of this pair is to authenticate the user and
issue access tokens (in JWT format and using authorization flow of OAuth 2.0).
The requests to the access proxy should have the access token as a Bearer
Authorization header. Based on that, the proxy decides whether to grant access
for a FHIR query.

<img src="doc/docs/images/summary.png" width=50% alt="Modules involved in FHIR authorization/access-control">

For more information on the technical design,
[see the design doc](doc/docs/design.md).

# Modules

The proxy consists of a core, which is in the [server](server) module, and a set
of _access-checker_ plugins, which can be implemented by third parties and added
to the proxy server. Two sample plugins are implemented in the
[plugins](plugins) module. There is also a sample `exec` module which shows how
all pieces can be woven together into a single Spring Boot app. To build all
modules, from the root run:

```shell
mvn package -Dspotless.apply.skip=true
```

The server and the plugins can be run together through this executable jar (
`--server.port` is just one of the many default Spring Boot flags):

```shell
java -jar exec/target/exec-0.1.0.jar --server.port=8081
```

Note that extra access-checker plugins can be added through the `loader.path`
property (although it is probably easier to build them into your server):

```shell
java -Dloader.path="PATH-TO-ADDITIONAL-PLUGINGS/custom-plugins.jar" \
  -jar exec/target/exec-0.1.0.jar --server.port=8081
```

The plugin library can be swapped with any third party access-checker as
described in the [plugins](plugins) directory and
[the wiki](https://github.com/google/fhir-gateway/wiki/Understanding-access-checker-plugins).

Note: Spring Boot is not a requirement for using FHIR Information Gateway; we
just use it to simplify the
[MainApp](exec/src/main/java/com/google/fhir/gateway/MainApp.java). The only
Spring-related requirement is to do a
[@ComponentScan](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/ComponentScan.html)
to find all access-checker plugins in the classpath.

# Configuration parameters

The configuration parameters are provided through environment variables:

- `PROXY_TO`: The base url of the FHIR store e.g.:

  ```shell
  export PROXY_TO=https://example.com/fhir
  ```

- `TOKEN_ISSUER`: The URL of the access token issuer, e.g.:

  ```shell
  export TOKEN_ISSUER=http://localhost:9080/auth/realms/test
  ```

  The above example is based on the default config of a test IDP+AuthZ
  [Keycloak](https://github.com/Alvearie/keycloak-extensions-for-fhir) server.
  To see how this server is configured, check the
  [docker/keycloak](docker/keycloak) directory. If you want to use a
  SMART-on-FHIR app use this realm instead:

  ```shell
  export TOKEN_ISSUER=http://localhost:9080/auth/realms/test-smart
  ```

- `ACCESS_CHECKER`: The access-checker to use. Each access-checker has a name
  (see [plugins](plugins) for details) and this variable should be set to the
  name of the plugin to use. For example, to use one of the sample plugins
  include one of:

  ```shell
  export ACCESS_CHECKER=list
  export ACCESS_CHECKER=patient
  ```

  For more information on how access-checkers work and building your own, see
  [Understanding access checker plugins](https://github.com/google/fhir-gateway/wiki/Understanding-access-checker-plugins).

- `ALLOWED_QUERIES_FILE`: A list of URL requests that should bypass the access
  checker and always be allowed.
  [`AllowedQueriesChecker`](https://github.com/google/fhir-gateway/blob/main/server/src/main/java/com/google/fhir/gateway/AllowedQueriesChecker.java)
  compares the incoming request with a configured set of allowed-queries. The
  intended use of this checker is to override all other access-checkers for
  certain user-defined criteria. The user defines their criteria in a config
  file and if the URL query matches an entry in the config file, access is
  granted.
  [AllowedQueriesConfig](https://github.com/google/fhir-gateway/blob/main/server/src/main/java/com/google/fhir/gateway/AllowedQueriesConfig.java)
  provides all the supported configurations. An example of this is
  [`hapi_page_url_allowed_queries.json`](https://github.com/google/fhir-gateway/blob/main/resources/hapi_page_url_allowed_queries.json).
  To use this file with `ALLOWED_QUERIES_FILE`:

  ```shell
  export ALLOWED_QUERIES_FILE="resources/hapi_page_url_allowed_queries.json"
  ```

- `BACKEND_TYPE`: The type of backend, either `HAPI` or `GCP`. `HAPI` should be
  used for most FHIR servers, while `GCP` should be used for GCP FHIR stores.

- `AUDIT_EVENT_ACTIONS_CONFIG`: A flag to configure AuditEvent logging. Set to a
  subset of `C`,`R`,`U`,`D`,`E` or all of them to enable and select the audit
  event actions to be logged. This model is guided by the value set codes
  defined here - https://hl7.org/fhir/R4/valueset-audit-event-action.html. When
  no value is set or the value is an empty string, audit logging is disabled. If
  any other character is set other than the predefined values, the server will
  fail to start.

## Gateway to server access

The proxy must be able to send FHIR queries to the FHIR server. The FHIR server
must be configured to accept connections from the proxy while rejecting most
other requests.

If you use a
[GCP FHIR store](https://cloud.google.com/healthcare-api/docs/concepts/fhir) you
have the following options:

- If you have access to the FHIR store, you can use your own credentials by
  doing
  [application-default login](https://cloud.google.com/sdk/gcloud/reference/auth/application-default/login).
  This is useful when testing the proxy on your local machine, and you have
  access to the FHIR server through your credentials.
- Use a service account with required access (e.g., "Healthcare FHIR Resource
  Reader", "Healthcare Dataset Viewer", "Healthcare FHIR Store Viewer"). You can
  then run the proxy in the same GCP project on a VM with this service account.
- [not-recommended] You can create and download a key file for the above service
  account, then use it with
  ```shell
  export GOOGLE_APPLICATION_CREDENTIALS="PATH_TO_THE_JSON_KEY_FILE"
  ```

Once you have set all the above, you can run the proxy server. The sample `exec`
module uses [Apache Tomcat](https://tomcat.apache.org/) through
[Spring Boot](https://spring.io/projects/spring-boot) and the usual
configuration parameters apply, e.g., to run on port 8081:

```shell
java -jar exec/target/exec-0.1.0.jar --server.port=8081
```

## Docker

The proxy is also available as a [docker image](Dockerfile):

```shell
$ docker run -p 8081:8080 -e TOKEN_ISSUER=[token_issuer_url] \
  -e PROXY_TO=[fhir_server_url] -e ACCESS_CHECKER=list \
  us-docker.pkg.dev/fhir-proxy-build/stable/fhir-gateway:latest
```

Note if the `TOKEN_ISSUER` is on the `localhost` you may need to bypass proxy's
token issuer check by setting `RUN_MODE=DEV` environment variable if you are
accessing the proxy from an Android emulator, which runs on a separate network.

[Try the proxy with test servers in Docker](https://github.com/google/fhir-gateway/wiki/Try-out-FHIR-Information-Gateway).

GCP note: if this is not on a VM with proper service account (e.g., on a local
host), you need to pass GCP credentials to it, for example by mapping the
`.config/gcloud` volume (i.e., add `-v ~/.config/gcloud:/root/.config/gcloud` to
the docker command).

# How to use this proxy

Once the proxy is running, we first need to fetch an access token from the
`TOKEN_ISSUER`; you need the test `username` and `password` plus the
`client_id`:

```shell
$ curl -X POST -d 'client_id=CLIENT_ID' -d 'username=testuser' \
  -d 'password=testpass' -d 'grant_type=password' \
"http://localhost:9080/auth/realms/test/protocol/openid-connect/token"
```

We need the `access_token` of the returned JSON to be able to convince the proxy
to authorize our FHIR requests (there is also a `refresh_token` in the above
response). Assuming this is stored in the `ACCESS_TOKEN` environment variable,
we can access the FHIR store:

```shell
$ curl -X GET -H "Authorization: Bearer ${ACCESS_TOKEN}" \
-H "Content-Type: application/json; charset=utf-8" \
'http://localhost:8081/Patient/f16b5191-af47-4c5a-b9ca-71e0a4365824'
```

```shell
$ curl -X PUT -H "Authorization: Bearer ${ACCESS_TOKEN}" \
-H "Content-Type: application/json; charset=utf-8" \
'http://localhost:8081/Patient/f16b5191-af47-4c5a-b9ca-71e0a4365824' \
-d @Patient_f16b5191-af47-4c5a-b9ca-71e0a4365824_modified.json
```

Of course, whether a query is accepted or denied, depends on the access-checker
used and the `ACCESS_TOKEN` claims. For example, for `ACCESS_CHECKER=list` there
should be a `patient_list` claim which is the ID of a `List` FHIR resource with
all the patients that this user has access to. For `ACCESS_CHECKER=patient`,
there should be a `patient_id` claim with a valid Patient resource ID.

# Acknowledgement

This proxy is implemented as a
[HAPI FHIR Plain Server](https://hapifhir.io/hapi-fhir/docs/server_plain/introduction.html),
starting from this
[hapi-fhirstarters-simple-server](https://github.com/FirelyTeam/fhirstarters/tree/master/java/hapi-fhirstarters-simple-server)
example.

# Contributions
This is an open-source project and we welcome contributions. Our development
process is also open. We use GitHub issues and review processes. If you are
working on a feature or an issue, please comment on that GitHub issue to
communicate with other developers. It would also be great to attend our weekly
developers' calls on Tuesdays; here are the details:
- **When**: Tuesdays at 10 AM Eastern Time (Toronto)
- **Where**: Virtual at https://meet.google.com/ago-nmcc-oed