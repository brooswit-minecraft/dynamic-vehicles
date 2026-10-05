"""Sync this release's environment without relabeling older artifacts."""
import hashlib
import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

def read_until(api, path, predicate=lambda value: True, wait=time.sleep):
    # Modrinth's read replicas can briefly lag successful publication/updates.
    for attempt in range(6):
        try:
            result = api(path)
            if predicate(result):
                return result
        except HTTPError as error:
            if error.code != 404 and error.code < 500:
                raise
            if attempt == 5:
                raise
        if attempt < 5:
            wait(min(2 ** (attempt + 1), 30))
    raise ValueError(f"Modrinth read-back did not converge: {path}")


def sync(api, project, version, settings, artifact_hash):
    published = read_until(api, f"/version_file/{artifact_hash}?algorithm=sha512")
    if published["project_id"] != project or published["version_number"] != version:
        raise ValueError("Published artifact belongs to a different project or version")
    target = f'/version/{published["id"]}'
    api(target, {"environment": settings["environment"]})
    read_until(api, target, lambda item: item.get("environment") == settings["environment"])
    # The project's environment list is derived from all its versions.
    api(f"/project/{project}", {"side_types_migration_review_status": "reviewed"})
    result = read_until(api, f"/project/{project}",
                        lambda item: settings["environment"] in item.get("environment", []))
    if settings.get("submit_for_review") and result["status"] in ("draft", "rejected"):
        api(f"/project/{project}", {"status": "processing"})
        result = read_until(api, f"/project/{project}",
                            lambda item: item["status"] in ("processing", "approved", "unlisted"))
    return result["status"]


def main():
    headers = {"Authorization": os.environ["MODRINTH_TOKEN"],
               "User-Agent": "brooswit-minecraft/dynamic-vehicles-release",
               "Content-Type": "application/json"}

    def api(path, patch=None):
        request = Request(f"https://api.modrinth.com/v3{path}",
                          data=None if patch is None else json.dumps(patch).encode(),
                          headers=headers, method="GET" if patch is None else "PATCH")
        with urlopen(request, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    settings = json.loads(Path("modrinth/project.json").read_text())
    version = os.environ["VERSION"]
    artifact = Path(f"build/libs/dynamicvehicles-{version}.jar").read_bytes()
    status = sync(api, os.environ["PROJECT_ID"], version, settings,
                  hashlib.sha512(artifact).hexdigest())
    print("Verified release environment:", settings["environment"])
    print("Modrinth project status:", status)


if __name__ == "__main__":
    main()
