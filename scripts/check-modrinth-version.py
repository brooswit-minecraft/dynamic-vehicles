"""Allow release retries only when Modrinth already holds the exact artifact."""
import hashlib
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def matches(version, expected_version, artifact):
    return (
        version["version_number"] == expected_version
        and version["loaders"] == ["neoforge"]
        and version["game_versions"] == ["1.21.1"]
        and version["version_type"] == "alpha"
        and len(version["files"]) == 1
        and version["files"][0]["hashes"]["sha512"]
        == hashlib.sha512(artifact).hexdigest()
    )


if __name__ == "__main__":
    project = os.environ["PROJECT_ID"]
    version = os.environ["VERSION"]
    request = Request(
        f"https://api.modrinth.com/v2/project/{project}/version",
        headers={"Authorization": os.environ["MODRINTH_TOKEN"],
                 "User-Agent": "brooswit-minecraft/dynamic-vehicles-release"},
    )
    with urlopen(request, timeout=30) as response:
        versions = json.load(response)
    existing = [v for v in versions if v["version_number"] == version]
    artifact = Path(f"build/libs/dynamicvehicles-{version}.jar").read_bytes()
    if not existing:
        # Newly published versions can be absent from the cached project listing.
        digest = hashlib.sha512(artifact).hexdigest()
        direct = Request(f"https://api.modrinth.com/v2/version_file/{digest}?algorithm=sha512",
                         headers=dict(request.header_items()))
        try:
            with urlopen(direct, timeout=30) as response:
                found = json.load(response)
            if found["project_id"] != project:
                raise SystemExit("Artifact belongs to a different Modrinth project.")
            existing = [found]
        except HTTPError as error:
            if error.code != 404:
                raise
    if existing:
        if len(existing) != 1 or not matches(existing[0], version, artifact):
            raise SystemExit("Existing Modrinth version differs; bump the version. Never overwrite.")
    print(f"exists={'true' if existing else 'false'}")
