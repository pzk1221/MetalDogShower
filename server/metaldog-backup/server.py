#!/usr/bin/env python3
"""Small private backup receiver for MetalDogShower shower history.

The service accepts no read API. Each app installation registers an RSA public key and every
backup is accepted only after a SHA-256/RSA signature verifies with that key. The Android app
keeps the matching private key in Android Keystore, so neither an SSH key nor a static API secret
is shipped in the APK.
"""

from __future__ import annotations

import base64
import json
import os
import re
import tempfile
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


DATA_DIR = Path(os.environ.get("METALDOG_BACKUP_DATA_DIR", "/var/lib/metaldog-backup"))
MAX_BODY_BYTES = 2 * 1024 * 1024
DEVICE_ID_RE = re.compile(r"^[0-9a-fA-F-]{36}$")


def error(status: int, message: str) -> tuple[int, dict[str, Any]]:
    return status, {"ok": False, "error": message}


def json_response(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def atomic_write(path: Path, payload: dict[str, Any]) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=DATA_DIR,
        prefix=f".{path.name}.",
        delete=False,
    ) as temporary:
        json.dump(payload, temporary, ensure_ascii=False, indent=2)
        temporary.write("\n")
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_name = temporary.name
    os.chmod(temporary_name, 0o600)
    os.replace(temporary_name, path)


def device_path(device_id: str) -> Path:
    return DATA_DIR / f"{device_id}.json"


def decode_public_key(encoded: str):
    raw = base64.b64decode(encoded, validate=True)
    key = serialization.load_der_public_key(raw)
    if not isinstance(key, rsa.RSAPublicKey) or key.key_size < 2048:
        raise ValueError("public key must be RSA 2048 or larger")
    return key, encoded


def read_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    try:
        length = int(handler.headers.get("Content-Length", "-1"))
    except ValueError as exc:
        raise ValueError("invalid content length") from exc
    if length < 0 or length > MAX_BODY_BYTES:
        raise ValueError("request body is too large")
    raw = handler.rfile.read(length)
    if len(raw) != length:
        raise ValueError("incomplete request body")
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("request must be a JSON object")
    return value


class Handler(BaseHTTPRequestHandler):
    server_version = "MetalDogBackup/1"

    def do_GET(self) -> None:  # noqa: N802
        if urlparse(self.path).path == "/healthz":
            json_response(self, HTTPStatus.OK, {"ok": True})
        else:
            json_response(self, HTTPStatus.NOT_FOUND, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path.startswith("/api/metaldog"):
            path = path[len("/api/metaldog"):]
        try:
            body = read_json(self)
            if path == "/v1/register":
                self.register(body)
                return
            if path == "/v1/backup":
                self.backup(body)
                return
            json_response(self, HTTPStatus.NOT_FOUND, {"ok": False, "error": "not found"})
        except ValueError as exc:
            json_response(self, HTTPStatus.BAD_REQUEST, {"ok": False, "error": str(exc)})
        except Exception:
            # Do not return exception text: it could reveal filesystem or crypto details.
            json_response(self, HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": "server error"})

    def register(self, body: dict[str, Any]) -> None:
        device_id = str(body.get("deviceId", ""))
        encoded_key = str(body.get("publicKey", ""))
        if not DEVICE_ID_RE.fullmatch(device_id):
            json_response(self, HTTPStatus.BAD_REQUEST, {"ok": False, "error": "invalid device id"})
            return
        try:
            _, encoded_key = decode_public_key(encoded_key)
        except Exception as exc:
            raise ValueError("invalid public key") from exc
        path = device_path(device_id)
        existing: dict[str, Any] | None = None
        if path.is_file():
            existing = json.loads(path.read_text(encoding="utf-8"))
            if existing.get("publicKey") != encoded_key:
                json_response(self, HTTPStatus.CONFLICT, {"ok": False, "error": "device key mismatch"})
                return
        if existing is None:
            atomic_write(
                path,
                {
                    "deviceId": device_id,
                    "publicKey": encoded_key,
                    "createdAt": int(time.time() * 1000),
                    "lastBackupAt": None,
                    "payload": None,
                },
            )
        json_response(self, HTTPStatus.OK, {"ok": True})

    def backup(self, body: dict[str, Any]) -> None:
        device_id = str(body.get("deviceId", ""))
        payload = body.get("payload")
        signature_encoded = str(body.get("signature", ""))
        try:
            timestamp = int(body.get("timestamp", 0))
        except (TypeError, ValueError) as exc:
            raise ValueError("invalid timestamp") from exc
        if not DEVICE_ID_RE.fullmatch(device_id) or not isinstance(payload, str):
            raise ValueError("invalid backup body")
        if len(payload.encode("utf-8")) > MAX_BODY_BYTES:
            raise ValueError("payload is too large")
        if abs(int(time.time() * 1000) - timestamp) > 24 * 60 * 60 * 1000:
            raise ValueError("timestamp outside allowed window")
        try:
            signature = base64.b64decode(signature_encoded, validate=True)
            parsed_payload = json.loads(payload)
            if not isinstance(parsed_payload, dict) or parsed_payload.get("schemaVersion") != 1:
                raise ValueError("unsupported payload")
        except Exception as exc:
            raise ValueError("invalid backup payload") from exc
        path = device_path(device_id)
        if not path.is_file():
            json_response(self, HTTPStatus.NOT_FOUND, {"ok": False, "error": "device not registered"})
            return
        registration = json.loads(path.read_text(encoding="utf-8"))
        try:
            public_key, _ = decode_public_key(str(registration["publicKey"]))
            public_key.verify(
                signature,
                f"{device_id}\n{timestamp}\n{payload}".encode("utf-8"),
                padding.PKCS1v15(),
                hashes.SHA256(),
            )
        except Exception as exc:
            raise ValueError("signature verification failed") from exc
        registration["lastBackupAt"] = timestamp
        registration["receivedAt"] = int(time.time() * 1000)
        registration["payload"] = parsed_payload
        atomic_write(path, registration)
        json_response(self, HTTPStatus.OK, {"ok": True, "receivedAt": registration["receivedAt"]})

    def log_message(self, format: str, *args: Any) -> None:
        # Keep logs free of request bodies, tokens, and history contents.
        print(f"{self.address_string()} {self.command} {self.path} {args[1] if len(args) > 1 else ''}", flush=True)


def main() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(DATA_DIR, 0o700)
    server = ThreadingHTTPServer(("127.0.0.1", 8091), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
