#!/bin/bash
#
# Copyright 2021-2022 Google LLC
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



# This is for setting up a test Keycloak server. It is intended to be run as
# part of the docker container initialization; see Dockerfile's entrypoint.
#
# For documentation of the kcadm.sh tool, see:
# https://www.keycloak.org/docs/latest/server_admin/index.html#the-admin-cli
# Tip: To figure out command parameter values, we can do the required edits
# in the web-UI, then `get` the created resources to see their config.
#
# Note this is for creating a test config only; do not deploy this confiuration
# in production!
#
# Environment variables used in this setup are:
# KEYCLOAK_USER: the admin user name for Keycloak
# KEYCLOAK_PASSWORD: the password for the admin user
# KEYCLOAK_BASE_URL: the base URL of the Keycloak instance being configured
# TEST_REALM: the Keycloak realm in which the user configuration is created
# TEST_USER: the user to be created for accessing the FHIR store
# TEST_PASS: the password of the test user
# SMART_REALM: if set, this realm is modified by adding the above user, mapping
#   it to a Patient resource and set up the Growth Chart SoF app as a client.
# SMART_PATIENT_ID: the logical id of the mapped FHIR Patient resource

cd /opt/jboss/keycloak/bin || exit 1

success="no"
while [[ "${success}" != "yes" ]]; do
  # Login as admin first; wait until this is successful which makes sure that
  # the Keycloak server is up and running.
  sh kcadm.sh config credentials \
    --server "${KEYCLOAK_BASE_URL:-http://localhost:9080/auth}"  \
    --realm master --user ${KEYCLOAK_USER} --password ${KEYCLOAK_PASSWORD}
  if [[ $? -eq 0 ]]; then
    success="yes"
  else
    echo "ERROR: Keycloak admin authentication failed; retry after 10 seconds!"
    sleep 10
  fi
done
echo "SUCCESS; logged in with admin password!"

# From this point on, fail upon any failure.
set -e

echo "Creating a new test realm; never use this for production purposes!"
readonly REALM=${TEST_REALM:-test}
sh kcadm.sh create realms -s realm=${REALM} -s enabled=true \
  -s sslRequired=none

# Enable saving of events.
sh kcadm.sh update events/config -r ${REALM} -s eventsEnabled=true \
  -s adminEventsEnabled=true

# Update the list of saved events; note although the documentation says that
# with an empty list all events will be enabled, that is not ture. Namely, the
# REFRESH_* events are not enabled by default; hence this enumeration!
sh kcadm.sh update events/config -r ${REALM} \
  -s 'enabledEventTypes=['"$(cat keycloak_events_list.txt)"']'

# Create a new public client with direct-grant enabled. This is useful for an
# app developed by the FHIR SDK: https://github.com/google/android-fhir/
CID=$(sh kcadm.sh create clients -r ${REALM} -s clientId=my-fhir-client \
  -s publicClient=true -s directAccessGrantsEnabled=true \
  -s defaultClientScopes='["profile"]' \
  -s redirectUris='["com.google.fhir.examples.demo:/oauth2redirect"]' -i)
echo "Created the new 'my-fhir-client' client ${CID}"

# Create a protocol-mapper for `patient_list` user attribute.
sh kcadm.sh create -r ${REALM} clients/${CID}/protocol-mappers/models/ \
  -s name=list-mapper -s protocolMapper=oidc-usermodel-attribute-mapper \
  -s protocol=openid-connect \
  -s config='{"user.attribute":"patient_list","claim.name":"patient_list","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true"}'

# Create the test user; set its password, group, etc.
sh kcadm.sh create users -r ${REALM} -s username=${TEST_USER} \
  -s enabled=true \
  -s attributes='{"patient_list":"patient-list-example"}' \
  -s credentials='[{"type":"password","value":"'${TEST_PASS}'","temporary":false}]'

echo "Setting up list-based ${REALM} realm is complete."

##############################################
# SMART config
##############################################

if [[ -z "${SMART_REALM}" ]]; then
  echo "SMART_REALM env variable is not set; not adding SMART user/client."
  exit 0
fi

echo "Configuring the SMART realm ${SMART_REALM}; not for production use!"

# Disable SSL for easy local testing (never use in production).
sh kcadm.sh update / -r ${SMART_REALM} -s sslRequired=none

# Create a new public client for the Growth Chart SMART app. This assumes the
# app is running locally on port 9000. Direct grant is enabled for easy testing.
SCID=$(sh kcadm.sh create clients -r ${SMART_REALM} -s clientId=growth_chart \
  -s publicClient=true -s directAccessGrantsEnabled=true \
  -s redirectUris='["http://localhost:9000/"]' -i)
echo "Created the new 'growth_chart' client ${SCID}"

# Create a new user in this realm with the same user credentials as before.
sh kcadm.sh create users -r ${SMART_REALM} -s username=${TEST_USER} \
  -s enabled=true \
  -s attributes='{"resourceId":"'${SMART_PATIENT_ID}'"}' \
  -s credentials='[{"type":"password","value":"'${TEST_PASS}'","temporary":false}]'

echo "Setting up patient-based ${SMART_REALM} realm is complete."
