"""Offline regression tests for the catalog generator."""
import io
import json
import pathlib
import runpy
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = pathlib.Path(__file__).with_name("generate-catalog.py")
SCRATCH = pathlib.Path.home() / "AppData/Local/hermes/cache/scratch"


class ReasoningCatalogTest(unittest.TestCase):
    def test_reasoning_only_does_not_fetch_or_overwrite_other_catalogs(self):
        requested = []

        def response(request, **kwargs):
            requested.append(request.full_url)
            return io.BytesIO(json.dumps({"data": [
                {"id": "vendor/model", "reasoning": {"mandatory": True}},
            ]}).encode("utf-8"))

        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = pathlib.Path(directory)
            assets = root / "app/src/main/assets"
            assets.mkdir(parents=True)
            untouched = assets / "model-names.json"
            untouched.write_text("sentinel", encoding="utf-8")
            import os
            previous = os.getcwd()
            try:
                os.chdir(root)
                with patch("urllib.request.urlopen", side_effect=response), patch("sys.argv", [str(SCRIPT), "--reasoning-only"]):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                self.assertEqual(["https://openrouter.ai/api/v1/models"], requested)
                self.assertEqual("sentinel", untouched.read_text(encoding="utf-8"))
                catalog = json.loads((assets / "model-reasoning.json").read_text(encoding="utf-8"))
                self.assertFalse(catalog["vendor/model"]["toggleable"])
            finally:
                os.chdir(previous)

    def test_reasoning_comes_only_from_openrouter(self):
        models_dev = {"provider": {"models": {
            "vendor/locked": {"reasoning": False},
            "vendor/plain": {"reasoning": True},
            "models-dev-only": {"reasoning": True},
        }}}
        openrouter = {"data": [
            {"id": "vendor/locked", "reasoning": {
                "mandatory": True, "supported_efforts": ["high", "low"],
            }},
            {"id": "vendor/optional", "reasoning": {
                "mandatory": False, "supported_efforts": ["high", "low"],
            }},
            {"id": "vendor/unknown-toggle", "reasoning": {
                "supported_efforts": ["high", "low"],
            }},
            {"id": "vendor/legacy", "supported_parameters": ["reasoning"]},
            {"id": "vendor/plain", "supported_parameters": ["temperature"]},
            {"id": "openrouter/auto", "supported_parameters": ["reasoning"]},
        ]}

        def response(request, **kwargs):
            data = openrouter if "openrouter.ai" in request.full_url else models_dev
            return io.BytesIO(json.dumps(data).encode("utf-8"))

        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = pathlib.Path(directory)
            (root / "app/src/main/assets").mkdir(parents=True)
            import os
            previous = os.getcwd()
            try:
                os.chdir(root)
                with patch("urllib.request.urlopen", side_effect=response):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                self.assertFalse((root / "desktop").exists())
                catalog = json.loads((root / "app/src/main/assets/model-reasoning.json").read_text(encoding="utf-8"))
            finally:
                os.chdir(previous)

        self.assertTrue(catalog["vendor/locked"]["supports"])
        self.assertFalse(catalog["vendor/locked"]["toggleable"])
        self.assertTrue(catalog["vendor/optional"]["toggleable"])
        self.assertNotIn("toggleable", catalog["vendor/unknown-toggle"])
        self.assertTrue(catalog["vendor/legacy"]["supports"])
        self.assertNotIn("toggleable", catalog["vendor/legacy"])
        self.assertFalse(catalog["vendor/plain"]["supports"])
        self.assertNotIn("models-dev-only", catalog)
        self.assertNotIn("openrouter/auto", catalog)
        self.assertEqual(catalog["vendor/locked"], catalog["locked"])
        self.assertEqual(catalog["vendor/plain"], catalog["plain"])


if __name__ == "__main__":
    unittest.main()
