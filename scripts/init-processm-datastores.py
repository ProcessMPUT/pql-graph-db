import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request


DATASETS = [
    ("Hospital_log", "Hospital_log.xes.gz"),
    ("JournalReview", "JournalReview.xes.gz"),
    ("Sepsis", "Sepsis.xes.gz"),
    ("teleclaims", "teleclaims.xes.gz"),
]


BASE_URL = os.environ.get("PROCESSM_URL", "http://processm:2080/api").rstrip("/")
EMAIL = os.environ.get("PROCESSM_LOGIN", "admin@example.com")
PASSWORD = os.environ.get("PROCESSM_PASSWORD", "Admin1234")
ORG = os.environ.get("PROCESSM_ORG", "TestOrg")
XES_DIR = os.environ.get("XES_DIR", "/xes")


def decode_json(body):
    if not body.strip():
        return {}
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {"raw": body.decode(errors="replace")}


def request_json(method, path, data=None, token=None):
    body = None if data is None else json.dumps(data).encode()
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req) as response:
            return decode_json(response.read()), response.status
    except urllib.error.HTTPError as error:
        return decode_json(error.read()), error.code


def wait_for_processm():
    for attempt in range(1, 31):
        try:
            request_json("POST", "/users/session", {"login": "probe", "password": "probe"})
            return
        except Exception as error:
            print(f"Waiting for ProcessM... ({attempt}/30): {error}")
            time.sleep(5)
    raise RuntimeError("ProcessM API did not become reachable")


def login():
    response, _ = request_json("POST", "/users/session", {"login": EMAIL, "password": PASSWORD})
    return response.get("authorizationToken")


def ensure_user():
    token = login()
    if token:
        print(f"User {EMAIL} already exists.")
        return token

    print(f"Creating user {EMAIL}...")
    response, status = request_json(
        "POST",
        "/users",
        {
            "userEmail": EMAIL,
            "userPassword": PASSWORD,
            "newOrganization": True,
            "organizationName": ORG,
        },
    )
    if status not in (200, 201) and "already exists" not in str(response):
        raise RuntimeError(f"Create user failed ({status}): {response}")

    token = login()
    if not token:
        raise RuntimeError("Login failed after user creation")
    print("Login OK.")
    return token


def list_data_stores(token):
    response, status = request_json("GET", "/data-stores", token=token)
    if status != 200:
        raise RuntimeError(f"List data stores failed ({status}): {response}")
    return response if isinstance(response, list) else []


def ensure_data_store(token, name):
    for store in list_data_stores(token):
        if store.get("name") == name:
            print(f'Data store "{name}" already exists: {store["id"]}')
            return store["id"]

    print(f'Creating data store "{name}"...')
    response, status = request_json("POST", "/data-stores", {"name": name}, token=token)
    if status not in (200, 201) or "id" not in response:
        raise RuntimeError(f'Create data store "{name}" failed ({status}): {response}')

    print(f'Data store "{name}" created: {response["id"]}')
    return response["id"]


def list_logs(token, data_store_id):
    response, status = request_json("GET", f"/data-stores/{data_store_id}/logs", token=token)
    if status != 200:
        raise RuntimeError(f"List logs failed for {data_store_id} ({status}): {response}")
    if isinstance(response, list):
        return response
    if isinstance(response, dict) and isinstance(response.get("data"), list):
        return response["data"]
    return []


def multipart_upload(token, data_store_id, file_path, filename):
    boundary = "----ProcessMInitBoundary"
    with open(file_path, "rb") as file:
        file_data = file.read()

    content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + file_data + f"\r\n--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        f"{BASE_URL}/data-stores/{data_store_id}/logs",
        data=body,
        method="POST",
    )
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")

    try:
        with urllib.request.urlopen(req) as response:
            return decode_json(response.read()), response.status
    except urllib.error.HTTPError as error:
        return decode_json(error.read()), error.code


def ensure_dataset(token, data_store_name, filename):
    file_path = os.path.join(XES_DIR, filename)
    if not os.path.isfile(file_path):
        raise RuntimeError(f"Missing XES file: {file_path}")

    data_store_id = ensure_data_store(token, data_store_name)
    existing_logs = list_logs(token, data_store_id)
    if existing_logs:
        print(f'Data store "{data_store_name}" already has {len(existing_logs)} log(s), skipping upload.')
        return

    print(f'Uploading {filename} to data store "{data_store_name}"...')
    response, status = multipart_upload(token, data_store_id, file_path, filename)
    if status not in range(200, 300):
        raise RuntimeError(f"Upload {filename} failed ({status}): {response}")
    print(f"Upload OK ({status}): {response}")


def main():
    wait_for_processm()
    token = ensure_user()

    for data_store_name, filename in DATASETS:
        ensure_dataset(token, data_store_name, filename)

    print("ProcessM datastore init complete.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Init failed: {error}")
        sys.exit(1)
