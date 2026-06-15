#!/usr/bin/env python3
"""Upload a signed Android App Bundle to a Google Play track.

The script uses a local Google Cloud service account JSON key with Play Console
permissions. It does not read Google account passwords or browser cookies.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import requests

try:
    from google.auth.transport.requests import Request
    from google.oauth2 import service_account
except ImportError as exc:
    raise SystemExit(
        "Missing dependency: google-auth. Install with: python -m pip install google-auth"
    ) from exc


ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
ANDROID_PUBLISHER_API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
ANDROID_PUBLISHER_UPLOAD_API = (
    "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
)
REPO_ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Upload an AAB to Google Play using Android Publisher API.",
    )
    parser.add_argument(
        "--service-account",
        default="gemmatavern-8f41c3d4e64d.json",
        help="Path to the service account JSON key.",
    )
    parser.add_argument(
        "--package-name",
        default="com.xuyutech.gemmatavern",
        help="Android application ID registered in Play Console.",
    )
    parser.add_argument(
        "--aab",
        default="Android/src/app/build/outputs/bundle/release/app-release.aab",
        help="Path to the signed release Android App Bundle.",
    )
    parser.add_argument(
        "--track",
        default="internal",
        help="Play track, for example internal, alpha, beta, or production.",
    )
    parser.add_argument(
        "--status",
        default="draft",
        choices=("draft", "completed", "inProgress", "halted"),
        help="Release status. Use draft for first automated uploads.",
    )
    parser.add_argument(
        "--release-name",
        default="GemmaTavern 0.1.2",
        help="Release name shown in Play Console.",
    )
    parser.add_argument(
        "--release-notes",
        default="Internal testing build.",
        help="Release notes text for en-US.",
    )
    parser.add_argument(
        "--commit",
        action="store_true",
        help="Commit the Play edit. Without this flag, the script validates access only.",
    )
    return parser.parse_args()


def bearer_token(service_account_path: Path) -> str:
    credentials = service_account.Credentials.from_service_account_file(
        str(service_account_path),
        scopes=[ANDROID_PUBLISHER_SCOPE],
    )
    credentials.refresh(Request())
    if not credentials.token:
        raise RuntimeError("Google auth did not return an access token.")
    return credentials.token


def resolve_input_path(value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path

    cwd_path = path.resolve()
    if cwd_path.exists():
        return cwd_path

    return (REPO_ROOT / path).resolve()


def request_json(
    method: str,
    url: str,
    token: str,
    **kwargs: Any,
) -> dict[str, Any]:
    headers = kwargs.pop("headers", {})
    headers["Authorization"] = f"Bearer {token}"
    response = requests.request(method, url, headers=headers, timeout=120, **kwargs)
    if response.status_code >= 400:
        raise RuntimeError(
            f"{method} {url} failed with {response.status_code}: {response.text}"
        )
    if not response.text:
        return {}
    return response.json()


def upload_bundle(
    package_name: str,
    edit_id: str,
    aab_path: Path,
    token: str,
) -> int:
    url = (
        f"{ANDROID_PUBLISHER_UPLOAD_API}/applications/{package_name}"
        f"/edits/{edit_id}/bundles?uploadType=media"
    )
    with aab_path.open("rb") as bundle_file:
        data = bundle_file.read()
    bundle = request_json(
        "POST",
        url,
        token,
        headers={"Content-Type": "application/octet-stream"},
        data=data,
    )
    version_code = bundle.get("versionCode")
    if version_code is None:
        raise RuntimeError(f"Bundle upload response did not include versionCode: {bundle}")
    return int(version_code)


def main() -> int:
    args = parse_args()
    service_account_path = resolve_input_path(args.service_account)
    aab_path = resolve_input_path(args.aab)

    if not service_account_path.is_file():
        raise SystemExit(f"Service account JSON not found: {service_account_path}")
    if not aab_path.is_file():
        raise SystemExit(f"AAB not found: {aab_path}")

    token = bearer_token(service_account_path)
    package_name = args.package_name

    edit = request_json(
        "POST",
        f"{ANDROID_PUBLISHER_API}/applications/{package_name}/edits",
        token,
        json={},
    )
    edit_id = edit["id"]
    print(f"Created Play edit: {edit_id}")

    if not args.commit:
        request_json(
            "DELETE",
            f"{ANDROID_PUBLISHER_API}/applications/{package_name}/edits/{edit_id}",
            token,
        )
        print("Access validated. Edit deleted because --commit was not provided.")
        return 0

    try:
        version_code = upload_bundle(package_name, edit_id, aab_path, token)
        print(f"Uploaded bundle versionCode={version_code}")

        track_body = {
            "track": args.track,
            "releases": [
                {
                    "name": args.release_name,
                    "versionCodes": [str(version_code)],
                    "status": args.status,
                    "releaseNotes": [
                        {
                            "language": "en-US",
                            "text": args.release_notes,
                        }
                    ],
                }
            ],
        }
        request_json(
            "PUT",
            f"{ANDROID_PUBLISHER_API}/applications/{package_name}/edits/{edit_id}"
            f"/tracks/{args.track}",
            token,
            headers={"Content-Type": "application/json"},
            data=json.dumps(track_body),
        )
        print(f"Updated track '{args.track}' with status '{args.status}'.")

        commit = request_json(
            "POST",
            f"{ANDROID_PUBLISHER_API}/applications/{package_name}/edits/{edit_id}:commit",
            token,
            json={},
        )
        print(f"Committed Play edit: {commit.get('id', edit_id)}")
        return 0
    except Exception:
        try:
            request_json(
                "DELETE",
                f"{ANDROID_PUBLISHER_API}/applications/{package_name}/edits/{edit_id}",
                token,
            )
            print(f"Deleted failed Play edit: {edit_id}", file=sys.stderr)
        except Exception as delete_error:
            print(f"Failed to delete Play edit {edit_id}: {delete_error}", file=sys.stderr)
        raise


if __name__ == "__main__":
    raise SystemExit(main())
