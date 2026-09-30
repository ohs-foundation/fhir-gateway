#
# Copyright 2021-2026 Google LLC
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

"""Clients to make calls to FHIR Proxy, HAPI Server, and AuthZ Server."""

import json
from typing import Dict, Tuple, List, Any

import requests
from datetime import datetime


def _setup_session(base_url: str) -> requests.Session:
    """Creates a request.Session instance with retry and populated header."""
    session = requests.Session()
    retry = requests.adapters.Retry()
    adapter = requests.adapters.HTTPAdapter(max_retries=retry)
    session.mount(base_url, adapter)
    session.headers.update({"Content-Type": "application/fhir+json;charset=utf-8"})
    return session


def read_file(file_name: str) -> Dict[str, Any]:
    with open(file_name, "r") as f:
        data = json.load(f)

    return data
  
def extract_date_only(date_str):
    """"Returns date part only e.g. 2025-07-16"""
    return date_str[:10]

class HapiClient:
    """Client for connecting to a HAPI FHIR server.
    
    Default init values based on docker/hapi-proxy-compose.yaml
    """

    def __init__(self, host: str = "http://localhost", port: int = 8099) -> None:
        self.base_url = "{}:{}/fhir".format(host, port)
        self.session = _setup_session(self.base_url)

    def get_resource_count(self, resource_type: str, patient_id: str) -> int:
        resource_path = "{}/{}?subject={}&_summary=count".format(
            self.base_url, resource_type, patient_id
        )
        response = self.session.get(resource_path)
        response.raise_for_status()
        return response.json()["total"]

    def get_audit_event_count(self) -> int:
        """Returns the count of AuditEvent resources."""
        resource_path = "{}/AuditEvent?_summary=count".format(self.base_url)
        response = self.session.get(resource_path)
        response.raise_for_status()
        return response.json()["total"]

    def get_audit_events(self, limit: int = 1) -> List[Dict[str, str]]:
        """Returns the most recent AuditEvent resources."""
        resource_path = "{}/AuditEvent?_count={}&_sort=-_lastUpdated".format(self.base_url, limit)
        response = self.session.get(resource_path)
        response.raise_for_status()
        return response.json().get("entry", [])


class FhirProxyClient:
    """Client for connecting to a FHIR Proxy.
    
    Default init values based on docker/hapi-proxy-compose.yaml
    """

    def __init__(self, host: str = "http://localhost", port: int = 8080) -> None:
        self.base_url = "{}:{}/fhir".format(host, port)
        self.session = _setup_session(self.base_url)

    def get_resource_count(
        self, token: str, resource_search_pair: Tuple[str, str], patient_id: str
    ) -> int:
        resource_path = "{}/{}?{}={}&_summary=count".format(
            self.base_url, resource_search_pair[0], resource_search_pair[1], patient_id
        )
        auth_dict = {"Authorization": "Bearer {}".format(token)}
        self.session.headers.update(auth_dict)

        response = self.session.get(resource_path)
        response.raise_for_status()
        return response.json()["total"]

    def post_resource(self, resource_type: str, file_name: str, token: str,) -> None:
        auth_dict = {"Authorization": "Bearer {}".format(token)}
        self.session.headers.update(auth_dict)

        resource_path = "{}/{}".format(self.base_url, resource_type)
        data = read_file(file_name)

        response = self.session.post(resource_path, json.dumps(data))
        response.raise_for_status()


class AuthClient:
    """Client for connecting to a Keycloak AuthZ server.
    
    Default init values based on docker/keycloak/env.sample
    """

    def __init__(
        self,
        host: str = "http://localhost",
        port: int = 9080,
        client_id: str = "my-fhir-client",
        username: str = "testuser",
        password: str = "testpass",
    ) -> None:
        self.url = "{}:{}/auth/realms/test/protocol/openid-connect/token".format(
            host, port
        )
        self.client_id = client_id
        self.username = username
        self.password = password

    def get_auth_token(self) -> str:
        payload = {
            "client_id": self.client_id,
            "username": self.username,
            "password": self.password,
            "grant_type": "password",
        }
        response = requests.post(self.url, data=payload)
        response.raise_for_status()
        return response.json()["access_token"]
