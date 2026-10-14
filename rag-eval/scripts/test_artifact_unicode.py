"""Artifact 编码回归：保持解析值不变，不调用网络。"""
import json
import tempfile
import unittest
from pathlib import Path
from run_scifact_production_hybrid import save, append


class ArtifactUnicodeTest(unittest.TestCase):
    def test_save_preserves_isolated_surrogate_by_json_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "chunks.json"
            value = {"content": "中文\udc49资料", "normalEmoji": "👋"}
            save(path, value)
            self.assertEqual(value, json.loads(path.read_text(encoding="utf-8")))
            self.assertIn("\\udc49", path.read_text(encoding="utf-8"))

    def test_append_preserves_existing_rows_and_all_surrogate_shapes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.jsonl"
            values = [{"text": "普通中文👋"}, {"text": "\ud83d"}, {"text": "\udc49"}, {"text": "\ud83d\udc49"}]
            for value in values: append(path, value)
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
            # JSON 对合法代理对允许规范化为同一个 Unicode 字符；孤立代理不丢失。
            self.assertEqual(values[:3], rows[:3])
            self.assertEqual(json.loads(json.dumps(values[3])), rows[3])
            self.assertIn("普通中文", path.read_text(encoding="utf-8"))


if __name__ == "__main__": unittest.main()
