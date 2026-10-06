"""Source wiring checks for the Android-only reasoning catalog setting.

JVM tests cover catalog semantics; these checks guard the preference and
Compose bindings without driving or modifying the user's device.
"""
import pathlib
import re
import unittest


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java/com/irio"


class ReasoningSettingWiringTest(unittest.TestCase):
    def test_setting_reads_saved_value_and_defaults_off(self):
        store = (SOURCE / "ProviderStore.kt").read_text(encoding="utf-8")
        match = re.search(
            r"fun loadModelsDevReasoning\(context: Context\): Boolean\s*=\s*([^\n]+)",
            store,
        )
        if match is None:
            self.fail("Reasoning preference loader not found")
        self.assertEqual(
            "preferences(context).getBoolean(MODELS_DEV_REASONING, false)",
            match.group(1).strip(),
        )
        self.assertIn('private const val MODELS_DEV_REASONING = "models_dev_reasoning"', store)
        save = store.split("fun saveModelsDevReasoning", 1)[1].split("\n    }", 1)[0]
        self.assertIn("putBoolean(MODELS_DEV_REASONING, enabled)", save)
        self.assertIn("notifyChanged()", save)

    def test_setting_can_be_toggled_and_saves_the_new_value(self):
        screen = (SOURCE / "ModelSettingsActivity.kt").read_text(encoding="utf-8")
        title = screen.index('tr("使用 OpenRouter 思考数据")')
        start = screen.rfind("SettingsSectionCard {", 0, title)
        section = screen[start:screen.index("\n            }", title)]
        self.assertIn("checked = modelsDevReasoning", section)
        self.assertIn("modelsDevReasoning = it", section)
        self.assertIn("ProviderStore.saveModelsDevReasoning(context, it)", section)
        self.assertNotIn("enabled = false", section)

    def test_experimental_badge_sits_beside_wrapping_title_before_switch(self):
        screen = (SOURCE / "ModelSettingsActivity.kt").read_text(encoding="utf-8")
        title = screen.index('tr("使用 OpenRouter 思考数据")')
        start = screen.rfind("SettingsSectionCard {", 0, title)
        section = screen[start:screen.index("\n            }", title)]
        self.assertIn("ExperimentalBadge()", section)
        self.assertLess(section.index("ExperimentalBadge()"), section.index("Switch("))
        self.assertIn("modifier = Modifier.weight(1f)", section[section.index("Text("):section.index("ExperimentalBadge()")])
        self.assertIn("Spacer(Modifier.width(8.dp))", section)
        self.assertNotIn("SettingsSwitchRow(", section)


if __name__ == "__main__":
    unittest.main()
