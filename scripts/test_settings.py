import importlib.util
from pathlib import Path
import unittest
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("settings", Path(__file__).with_name("sync-modrinth-settings.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SettingsTest(unittest.TestCase):
    def test_read_retries_missing_and_stale_values(self):
        responses = iter([HTTPError("url", 404, "lag", {}, None), "old", "new"])
        delays = []
        def api(path):
            value = next(responses)
            if isinstance(value, Exception):
                raise value
            return value
        self.assertEqual("new", module.read_until(api, "/version/id", lambda v: v == "new", delays.append))
        self.assertEqual([2, 4], delays)

    def test_read_retry_is_bounded_and_does_not_retry_auth_failures(self):
        delays = []
        with self.assertRaises(ValueError):
            module.read_until(lambda path: "old", "/version/id", lambda v: False, delays.append)
        self.assertEqual(5, len(delays))
        def forbidden(path):
            raise HTTPError("url", 403, "denied", {}, None)
        with self.assertRaises(HTTPError):
            module.read_until(forbidden, "/version/id", wait=lambda delay: self.fail("must not retry"))

    def test_updates_only_target_version_and_accepts_mixed_project_environments(self):
        calls = []

        def api(path, patch=None):
            calls.append((path, patch))
            if patch is not None:
                return None
            if path.startswith("/version_file/"):
                return {"id": "new", "project_id": "project", "version_number": "0.3.0-alpha.1"}
            if path == "/version/new":
                return {"environment": "client_and_server"}
            return {"status": "processing", "environment": ["server_only", "client_and_server"]}

        self.assertEqual("processing", module.sync(api, "project", "0.3.0-alpha.1",
                         {"environment": "client_and_server", "submit_for_review": True}, "hash"))
        self.assertIn(("/version/new", {"environment": "client_and_server"}), calls)
        self.assertFalse(any(path == "/version/old" for path, _ in calls))
        self.assertFalse(any(path == "/project/project" and patch and "environment" in patch
                             for path, patch in calls))

    def test_wrong_project_or_version_stops_before_writes(self):
        for published in ({"project_id": "other", "version_number": "v"},
                          {"project_id": "p", "version_number": "other"}):
            calls = []
            def api(path, patch=None):
                calls.append(patch)
                return published
            with self.assertRaises(ValueError):
                module.sync(api, "p", "v", {"environment": "client_and_server"}, "hash")
            self.assertEqual([None], calls)


if __name__ == "__main__":
    unittest.main()
