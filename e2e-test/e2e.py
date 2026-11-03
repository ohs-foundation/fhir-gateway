#
# Copyright 2021-2025 Google LLC
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

"""End-to-end tests using the FHIR Proxy, HAPI Server, and AuthZ Server."""

import logging
import time
from typing import List, Tuple, Dict, Any

import clients

from clients import read_file, extract_date_only
from datetime import date, timezone

def test_proxy_and_server_equal_count(
    patient_list: List[str],
    resource_search_pairs: List[Tuple[str, str]],
    hapi: clients.HapiClient,
    fhir_proxy: clients.FhirProxyClient,
    auth: clients.AuthClient,
) -> None:
    """Checks number of resources are the same via the Proxy or HAPI."""
    token = auth.get_auth_token()
    for patient in patient_list:
        for resource_search_pair in resource_search_pairs:
            value_from_server = hapi.get_resource_count(
                resource_search_pair[0], patient
            )
            value_from_proxy = fhir_proxy.get_resource_count(
                token, resource_search_pair, patient
            )
            logging.info(
                "%s resources returned for %s from: \n\tServer: %s, Proxy: %s",
                resource_search_pair[0],
                patient,
                value_from_server,
                value_from_proxy,
            )

            if value_from_server != value_from_proxy:
                error_msg = "Number of resources do not match\n\tServer: {}, Proxy: {}".format(
                    value_from_server, value_from_proxy
                )
                raise ValueError(error_msg)


def test_post_resource_increase_count(
    resource_search_pair: Tuple[str, str],
    file_name: str,
    patient_id: str,
    hapi: clients.HapiClient,
    fhir_proxy: clients.FhirProxyClient,
    auth: clients.AuthClient,
) -> None:
    """Test to add a resource to the backend via the Proxy."""
    token = auth_client.get_auth_token()
    value_from_server = hapi.get_resource_count(resource_search_pair[0], patient_id)
    value_from_proxy = fhir_proxy.get_resource_count(
        token, resource_search_pair, patient_id
    )

    if value_from_server != value_from_proxy:
        error_msg = "Number of resources do not match\n\tServer: {}, Proxy: {}".format(
            value_from_server, value_from_proxy
        )
        raise ValueError(error_msg)

    current_value = value_from_proxy
    logging.info(
        "%s %ss returned for %s", current_value, resource_search_pair[0], patient_id,
    )

    logging.info("Adding one %s for %s", resource_search_pair[0], patient_id)
    fhir_proxy.post_resource(resource_search_pair[0], file_name, token)

    while value_from_proxy != (current_value + 1):
        token = auth.get_auth_token()
        value_from_proxy = fhir_proxy.get_resource_count(
            token, resource_search_pair, patient_id
        )
        time.sleep(10)

    logging.info("Added one %s for %s", resource_search_pair[0], patient_id)
    logging.info(
        "%s %ss returned for %s", value_from_proxy, resource_search_pair[0], patient_id,
    )

def test_post_resource_with_logging_enabled_creates_audit_event(
    file_name: str,
    hapi: clients.HapiClient,
    fhir_proxy: clients.FhirProxyClient,
    auth: clients.AuthClient,
) -> None:
    """Test to add a resource to the backend via the Proxy and verify AuditEvent creation."""
    _test_post_and_verify_audit_events(
        "Observation",
        file_name,
        1,
        hapi,
        fhir_proxy,
        auth
    )

def test_post_bundle_with_logging_enabled_creates_audit_events(
    file_name: str,
    hapi: clients.HapiClient,
    fhir_proxy: clients.FhirProxyClient,
    auth: clients.AuthClient,
) -> None:
    """Test to add a bundle to the backend via the Proxy and verify AuditEvents creation."""
    _test_post_and_verify_audit_events(
        "",
        file_name,
        2,
        hapi,
        fhir_proxy,
        auth
    )

 
def _test_post_and_verify_audit_events(
    resource_type: str,
    file_name: str,
    expected_audit_event_increase: int,
    hapi: clients.HapiClient,
    fhir_proxy: clients.FhirProxyClient,
    auth: clients.AuthClient
) -> None:
    """Helper function to POST a resource and verify AuditEvent creation."""
    token = auth.get_auth_token()
    payload_type = "Bundle" if resource_type == "" else resource_type

    initial_audit_event_count = hapi.get_audit_event_count()
    logging.info("Initial AuditEvent count before posting %s is %d", 
                                                file_name, initial_audit_event_count)

    fhir_proxy.post_resource(resource_type, file_name, token)
    logging.info("Posted %s resource via Proxy.", payload_type)

    max_wait_seconds = 300
    poll_interval = 5
    waited = 0
    while waited < max_wait_seconds:
        new_audit_events = hapi.get_audit_events(expected_audit_event_increase)
        current_audit_event_count = hapi.get_audit_event_count()
        if (current_audit_event_count == 
                            initial_audit_event_count + expected_audit_event_increase):
            expected_output_filename = ("transaction_bundle_audit_events.json" 
            if resource_type == "" 
            else "{resource_type}_audit_events.json".format(resource_type=resource_type)
            ).lower()                           
            expected_data = read_file("e2e-test/{}".format(expected_output_filename))
            if not isinstance(expected_data, list):
                raise AssertionError("Expected audit event file must contain a list of objects.")
            if len(expected_data) != len(new_audit_events):
                raise AssertionError(
                    "Number of expected and actual audit events do not match: expected {}, actual {}".format(
                        len(expected_data), len(new_audit_events)
                    )
                )
            for index, (expected_audit_event, audit_event_entry) in enumerate(zip(expected_data, new_audit_events)):
                if not isinstance(expected_audit_event, dict):
                    logging.warning("Expected audit event at index {} is not a dict: {}".format(index, expected_audit_event))
                    continue
                audit_event = audit_event_entry.get("resource", {})
                if not isinstance(audit_event, dict):
                    logging.warning("AuditEvent resource at index {} is not a dict: {}".format(index, audit_event))
                    continue
                _assert_audit_events(expected_audit_event, audit_event)
                    
            logging.info("AuditEvents created successfully after %s POST.", payload_type)
            return
        time.sleep(poll_interval)
        waited += poll_interval
        logging.info("POST %s :: Waiting for AuditEvent(s)... (%ds elapsed)", 
                                                                  payload_type, waited)

    raise AssertionError(
        "Valid AuditEvent was NOT created after {} POST within {} seconds. "
        "Initial count: {}, Current count: {}".format(
            payload_type, max_wait_seconds, 
            initial_audit_event_count, current_audit_event_count
        )
    )   

def _assert_audit_events(expected_audit_event: Dict[str, Any],
                        actual_audit_event: Dict[str, Any]) -> None:
    """Assert that actual AuditEvents match the expected structure and content."""
    verification_fields = ["action", "subtype", "outcome", 
                          "agent", "entity", "source", "recorded", "period"]  
    for field in verification_fields:
        logging.info("Verifying AuditEvent.{}".format(field))
        if field in expected_audit_event:
            expected_value = expected_audit_event.get(field)
            actual_value = actual_audit_event.get(field)

            if field == "agent": 
                expected_value = (expected_value[2].get("who")
                                 if isinstance(expected_value, list) 
                                 and len(expected_value) > 2 else "")
                actual_value = (actual_value[2].get("who")
                               if isinstance(actual_value, list) 
                               and len(actual_value) > 2 else "")
            elif field == "entity":
                #check if the AuditEvent has correct compartment owner
                expected_compartment_value = (expected_value[1].get("what")
                                             if isinstance(expected_value, list) 
                                             and len(expected_value) > 1 else "")
                actual_compartment_value = (actual_value[1].get("what")
                                           if isinstance(actual_value, list) 
                                           and len(actual_value) > 1 else "")

                expected_entity_resource_ref = (expected_compartment_value.get("reference") 
                                 if isinstance(expected_compartment_value, dict) else "")
                actual_entity_resource_ref = (actual_compartment_value.get("reference") 
                                if isinstance(actual_compartment_value, dict) else "")

                expected_compartment_value = (expected_entity_resource_ref.split("/")[0] 
                                if isinstance(expected_entity_resource_ref, str) else "")
                actual_compartment_value = (actual_entity_resource_ref.split("/")[0] 
                                if isinstance(actual_entity_resource_ref, str) else "")

                if expected_compartment_value != actual_compartment_value:
                    raise AssertionError(
                        "Field '{}' compartment owner mismatch in AuditEvent:\n"
                        "Expected: {}\n"
                        "Actual: {}".format(
                            field, expected_entity_resource_ref, 
                            actual_entity_resource_ref
                        )
                    )

                #check if the AuditEvent is for the correct resource type
                expected_entity_what = (expected_value[2].get("what")
                                        if isinstance(expected_value, list) 
                                        and len(expected_value) > 2 else "")
                actual_entity_what = (actual_value[2].get("what")
                                      if isinstance(actual_value, list) 
                                      and len(actual_value) > 2 else "")

                expected_entity_resource_ref = (expected_entity_what.get("reference") 
                if isinstance(expected_entity_what, dict) else "")
                actual_entity_resource_ref = (actual_entity_what.get("reference") 
                if isinstance(actual_entity_what, dict) else "")

                expected_value = (expected_entity_resource_ref.split("/")[0] 
                                 if isinstance(expected_entity_resource_ref, str) else "")
                actual_value = (actual_entity_resource_ref.split("/")[0] 
                                 if isinstance(actual_entity_resource_ref, str) else "")
                                 
                if expected_entity_resource_ref is not None and "_history" not in expected_entity_resource_ref:
                    raise AssertionError(
                        "Reference '{}' does not contain version id:\n".format(expected_entity_resource_ref)
                    )
                                   
            elif field == "recorded":
                expected_value = datetime.now(timezone.utc).date().strftime('%Y-%m-%d')

                logging.info("Raw actual {}".format(actual_value))
                actual_value = extract_date_only(actual_value)

                logging.info("Comparing expected {} vs actual {}".format(expected_value, actual_value))
            elif field == "period":
                #confirm with period.end that object is correct
                actual_period_end = (actual_value.get("end") 
                if isinstance(actual_value, dict) else None)
                expected_value = date.today().strftime('%Y-%m-%d')
                actual_value = extract_date_only(actual_period_end)
            
            if actual_value != expected_value:
                raise AssertionError(
                    "Field '{}' mismatch in AuditEvent:\n"
                    "Expected: {}\n"
                    "Actual: {}".format(field, expected_value, actual_value)
                )
    
    logging.info("All AuditEvent fields verified successfully")

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)

    patients = ["Patient/3177", "Patient/2835"]
    resources = [("Encounter", "patient"), ("Observation", "subject")]
    auth_client = clients.AuthClient()
    fhir_proxy_client = clients.FhirProxyClient()
    hapi_client = clients.HapiClient()

    logging.info("Testing proxy and server resource counts ...")
    test_proxy_and_server_equal_count(
        patients, resources, hapi_client, fhir_proxy_client, auth_client
    )
    logging.info("Testing post resource ...")
    test_post_resource_increase_count(
        ("Observation", "subject"),
        "e2e-test/obs.json",
        "Patient/3177",
        hapi_client,
        fhir_proxy_client,
        auth_client,
    )
   
    logging.info("Testing POST Resource with AuditEvent logging enabled")
    test_post_resource_with_logging_enabled_creates_audit_event(
        "e2e-test/obs.json",
        hapi_client,
        fhir_proxy_client,
        auth_client,
    )
   
    logging.info("Testing POST Bundle with AuditEvent logging enabled")
    test_post_bundle_with_logging_enabled_creates_audit_events(
        "e2e-test/transaction_bundle.json",
        hapi_client,
        fhir_proxy_client,
        auth_client,
    )

