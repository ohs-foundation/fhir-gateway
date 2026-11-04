# Sample Identity Provider and Authorization Server

This directory contains the docker configuration for a sample
[Keycloak](https://www.keycloak.org/) server that can be used as the Identity
Provider (IDP) and Authorization server (AuthZ) companions of the FHIR proxy.

**NOTE:** This is just for test purposes; never use this configuration in
production without addressing security issues, in particular SSL access.

There are three components involved here which are all combined in
[config-compose.yaml](config-compose.yaml):

- The
  [Alvearie SMART Keycloak](https://github.com/Alvearie/keycloak-extensions-for-fhir).
  We could also use the base Keycloak image if the access-checker does not care
  about [SMART on FHIR](http://www.hl7.org/fhir/smart-app-launch/) spec (for
  example the
  [`list` access-checker](../../plugins/src/main/java/com/google/fhir/gateway/plugin/ListAccessChecker.java)).
  The
  [`patient` access-checker](../../plugins/src/main/java/com/google/fhir/gateway/plugin/PatientAccessChecker.java)
  is intended for a SMART on FHIR app with patient scopes.

- The `alvearie/keycloak-config:latest` docker image to configure a SMART
  enabled realm. This is useful for the `patient` access-checker.

- The `us-docker.pkg.dev/fhir-proxy-build/stable/keycloak-config:latest` docker
  image to configure a realm for the `list` access-checker.

You can change the configuration parameters by changing environment variables
passed to the docker images. These are read from a `.env` file in this
directory, which is not under version control; create it from the defaults in
[`env.sample`](env.sample) first. To run all above components:

```shell
cp env.sample .env
docker-compose -f config-compose.yaml up
```
