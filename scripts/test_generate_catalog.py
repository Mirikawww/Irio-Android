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
    def test_targeted_patch_only_updates_the_requested_model_and_bare_id(self):
        fixture = {"data": [{
            "id": "qwen/qwen3.8-max-0902", "name": "Qwen: Qwen3.8 Max (0902)",
            "context_length": 1000000, "reasoning": {"mandatory": True, "supported_efforts": ["high", "low"]},
        }, {"id": "unrelated/model", "name": "Unrelated: Changed", "context_length": 999}]}
        requested = []

        def response(request, **kwargs):
            requested.append(request.full_url)
            self.assertEqual("https://openrouter.ai/api/v1/models", request.full_url)
            return io.BytesIO(json.dumps(fixture).encode())

        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = pathlib.Path(directory)
            assets = root / "app/src/main/assets"
            assets.mkdir(parents=True)
            filenames = ("model-names.json", "model-context.json", "model-reasoning.json")
            for filename in filenames:
                (assets / filename).write_text('{"untouched":"sentinel"}', encoding="utf-8")
            (assets / "model-image.json").write_text("untouched image bytes", encoding="utf-8")
            import os
            previous = os.getcwd()
            try:
                os.chdir(root)
                with patch("urllib.request.urlopen", side_effect=response), patch("sys.argv", [str(SCRIPT), "--patch-model=qwen/qwen3.8-max"]):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                self.assertEqual(["https://openrouter.ai/api/v1/models"], requested)
                for filename in filenames:
                    catalog = json.loads((assets / filename).read_text(encoding="utf-8"))
                    self.assertEqual({"untouched", "qwen/qwen3.8-max", "qwen3.8-max"}, set(catalog), filename)
                    self.assertEqual("sentinel", catalog["untouched"])
                self.assertEqual("untouched image bytes", (assets / "model-image.json").read_text(encoding="utf-8"))
            finally:
                os.chdir(previous)

    def test_qwen_max_alias_survives_catalog_generation(self):
        canonical = "qwen/qwen3.8-max-0902"
        fixture = {"data": [{
            "id": canonical, "name": "Qwen: Qwen3.8 Max (0902)",
            "context_length": 1000000,
            "reasoning": {"mandatory": True, "supported_efforts": ["xhigh", "high", "medium", "low", "minimal"]},
        }]}

        def response(request, **kwargs):
            return io.BytesIO(json.dumps(fixture if "openrouter.ai" in request.full_url else {}).encode())

        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = pathlib.Path(directory)
            assets = root / "app/src/main/assets"
            assets.mkdir(parents=True)
            import os
            previous = os.getcwd()
            try:
                os.chdir(root)
                with patch("urllib.request.urlopen", side_effect=response), patch("sys.argv", [str(SCRIPT)]):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                for filename in ("model-names.json", "model-context.json", "model-reasoning.json"):
                    catalog = json.loads((assets / filename).read_text(encoding="utf-8"))
                    for alias in ("qwen/qwen3.8-max", "qwen3.8-max"):
                        self.assertIn(alias, catalog, filename)
                        self.assertEqual(catalog[canonical], catalog[alias], filename)
            finally:
                os.chdir(previous)

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


class ImageCatalogTest(unittest.TestCase):
    def generate(self, models_dev, image_only=False):
        requested = []

        def response(request, **kwargs):
            requested.append(request.full_url)
            self.assertTrue(request.get_header("User-agent"))
            payload = {"data": []} if "openrouter.ai" in request.full_url else models_dev
            return io.BytesIO(json.dumps(payload).encode("utf-8"))

        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = pathlib.Path(directory)
            assets = root / "app/src/main/assets"
            assets.mkdir(parents=True)
            untouched = ("model-names.json", "model-reasoning.json", "model-context.json")
            for name in untouched:
                (assets / name).write_text('{"sentinel":true}', encoding="utf-8")
            # Regeneration must replace stale classifications, not merge them back.
            (assets / "model-image.json").write_text('{"gpt-5-nano":true}', encoding="utf-8")
            import os
            previous = os.getcwd()
            try:
                os.chdir(root)
                args = [str(SCRIPT)] + (["--image-only"] if image_only else [])
                with patch("urllib.request.urlopen", side_effect=response), patch("sys.argv", args):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                images = json.loads((assets / "model-image.json").read_text(encoding="utf-8"))
                other_assets = {name: (assets / name).read_text(encoding="utf-8") for name in untouched}
            finally:
                os.chdir(previous)
        return images, requested, other_assets

    def test_chat_models_with_image_capabilities_do_not_become_generation_endpoints(self):
        fixture = {"provider": {"models": {
            "openai/gpt-5-nano": {"modalities": {"input": ["text", "image"], "output": ["text"]}, "tool_call": True},
            # Real shape of the noisy Poe entry: image output alone does not mean Images API.
            "openai/gpt-5.4": {"modalities": {"output": ["text", "image"]}, "tool_call": True},
            "vendor/multimodal-chat": {"modalities": {"output": ["text", "image"]}, "tool_call": True},
            "vendor/vision-reader": {"modalities": {"input": ["image"], "output": ["text"]}, "tool_call": False},
            "openai/gpt-image-2": {"modalities": {"output": ["image"]}, "tool_call": False},
            "openai/gpt-image-1.5": {"modalities": {"output": ["text", "image"]}, "tool_call": False},
            # Tool metadata may itself be noisy on an image-only endpoint.
            "google/imagen-fixture": {"modalities": {"output": ["image"]}, "tool_call": True},
            "vendor/malformed": {"modalities": None},
        }}}
        images, _, _ = self.generate(fixture)
        for model in ("gpt-5-nano", "gpt-5.4", "multimodal-chat", "vision-reader", "malformed"):
            self.assertNotIn(model, images)
            self.assertFalse(any(key.endswith("/" + model) for key in images), model)
        for model in ("openai/gpt-image-2", "gpt-image-2", "openai/gpt-image-1.5", "gpt-image-1.5", "google/imagen-fixture", "imagen-fixture", "gpt-image-2.5"):
            self.assertTrue(images[model], model)

    def test_image_only_refresh_does_not_fetch_or_rewrite_openrouter_catalogs(self):
        images, requested, other_assets = self.generate({"provider": {"models": {
            "fixture-image": {"modalities": {"output": ["image"]}},
        }}}, image_only=True)
        self.assertEqual(["https://models.dev/api.json"], requested)
        self.assertIn("fixture-image", images)
        self.assertNotIn("gpt-5-nano", images)
        self.assertTrue(all(value == '{"sentinel":true}' for value in other_assets.values()))


if __name__ == "__main__":
    unittest.main()
