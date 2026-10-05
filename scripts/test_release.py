import copy
import hashlib
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release_check", Path(__file__).with_name("check-modrinth-version.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseRetryTest(unittest.TestCase):
    def test_only_exact_artifact_and_metadata_can_be_reused(self):
        artifact = b"test jar"
        version = {"version_number": "0.1.0-alpha.1", "loaders": ["neoforge"],
                   "game_versions": ["1.21.1"], "version_type": "alpha",
                   "files": [{"hashes": {"sha512": hashlib.sha512(artifact).hexdigest()}}]}
        self.assertTrue(module.matches(version, "0.1.0-alpha.1", artifact))
        self.assertFalse(module.matches(version, "0.1.0-alpha.1", b"different"))
        for field, value in [("loaders", ["fabric"]), ("game_versions", ["1.21"]),
                             ("version_type", "release"), ("version_number", "0.2.0")]:
            modified = copy.deepcopy(version)
            modified[field] = value
            self.assertFalse(module.matches(modified, "0.1.0-alpha.1", artifact))


if __name__ == "__main__":
    unittest.main()
