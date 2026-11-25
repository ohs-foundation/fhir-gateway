#!/bin/bash
#
# Copyright 2021-2023 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Fail on any error.
set -e
# Display commands being run.
# WARNING: please only enable 'set -x' if necessary for debugging, and be very
#  careful if you handle credentials (e.g. from Keystore) with 'set -x':
#  statements like "export VAR=$(cat /tmp/keystore/credentials)" will result in
#  the credentials being printed in build logs.
#  Additionally, recursive invocation with credentials as command-line
#  parameters, will print the full command, with credentials, in the build logs.
# set -x
# Code under repo is checked out to ${KOKORO_ARTIFACTS_DIR}/git.
# The final directory name in this path is determined by the scm name specified
# in the job configuration.

function setup() {
  # Install Docker-Compose v2
  sudo curl -L "https://github.com/docker/compose/releases/download/v2.5.0/docker-compose-$(uname -s)-$(uname -m)" \
    -o /usr/local/bin/docker-compose
  sudo chmod +x /usr/local/bin/docker-compose

  # Update gcloud components
  gcloud components update --quiet

  # On Kokoro Instances (RBE), builds execute inside a primary toolchain
  # container using sibling containers via the host VM's Docker daemon
  # (indicated by KOKORO_HOST_ROOT_DIR being set). Perform two RBE-specific
  # adjustments here so ./e2e-test/e2e.sh works unchanged:
  if [[ -n "${KOKORO_HOST_ROOT_DIR:-}" ]]; then
    # 1. Trust the RBE outbound network-monitoring TLS proxy inside
    #    `docker build`. RBE workers mount the proxy CA bundle and Java
    #    truststore at /var/cache/proxy.crt and /var/cache/proxy.crt.jks,
    #    which are available in the primary container but not automatically
    #    inside BuildKit `docker build` stages. Layer them onto the local
    #    Maven build base image and use classic Docker builds
    #    (DOCKER_BUILDKIT=0) so `npm` and `mvn` in the `build` stage trust
    #    the proxy. The final runtime stage (`eclipse-temurin:17-jdk-focal`)
    #    only copies the built JAR and does not include proxy certs.
    export DOCKER_BUILDKIT=0
    if [[ -f /var/cache/proxy.crt ]]; then
      docker pull maven:3.8.7-eclipse-temurin-17-focal
      local proxy_dir
      proxy_dir="$(mktemp -d)"
      cp /var/cache/proxy.crt "${proxy_dir}/proxy.crt"
      if [[ -f /var/cache/proxy.crt.jks ]]; then
        cp /var/cache/proxy.crt.jks "${proxy_dir}/proxy.crt.jks"
      fi
      cat <<'EOF' > "${proxy_dir}/Dockerfile"
FROM maven:3.8.7-eclipse-temurin-17-focal
COPY proxy.crt* /var/cache/
ENV SSL_CERT_FILE=/var/cache/proxy.crt
ENV CURL_CA_BUNDLE=/var/cache/proxy.crt
ENV NODE_EXTRA_CA_CERTS=/var/cache/proxy.crt
ENV NPM_CONFIG_CAFILE=/var/cache/proxy.crt
ENV JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/var/cache/proxy.crt.jks"
EOF
      DOCKER_BUILDKIT=0 docker build \
        -t maven:3.8.7-eclipse-temurin-17-focal "${proxy_dir}"
      rm -rf "${proxy_dir}"
    fi

    # 2. Ensure `requests` is available for e2e-test/e2e.py and forward
    #    localhost ports 8080 (fhir-proxy), 8099 (hapi-server), and 9080
    #    (keycloak) to the host Docker bridge gateway. Because docker-compose
    #    launches sibling containers on the host VM, their published ports
    #    bind on the host rather than the primary container's loopback.
    python3 -c "import requests" 2>/dev/null \
      || pip3 install --no-cache-dir requests
    sudo apt-get update && sudo apt-get install -y socat
    local host_ip
    host_ip="$(ip route show default | awk '/default/ {print $3; exit}')"
    if [[ -n "${host_ip}" ]]; then
      export NO_PROXY="localhost,127.0.0.1,${host_ip}"
      export no_proxy="${NO_PROXY}"
      for port in 8080 8099 9080; do
        socat "TCP-LISTEN:${port},fork,reuseaddr,bind=127.0.0.1" \
          "TCP:${host_ip}:${port}" &
      done
    fi
  fi
}

setup
cd "${KOKORO_ARTIFACTS_DIR}/github/fhir-gateway"
if [[ "${KOKORO_JOB_TYPE:-}" == PRESUBMIT* || "${KOKORO_JOB_NAME:-}" == *-test/* ]]; then
  export BUILD_ID=${KOKORO_BUILD_ID:-local}
  gcloud auth configure-docker us-docker.pkg.dev --quiet
  ./e2e-test/e2e.sh
else
  ./build.sh
fi
