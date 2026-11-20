import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "client"))

from runner import Case, render_html, CaseResult, Step  # noqa: E402


class FakeClient:
    def screenshot(self, path):
        with open(path, "wb") as f:
            f.write(b"\x89PNG\r\n")   # 内容不重要
        return path


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.case = Case(FakeClient(), "单测用例", shots_dir=os.path.join(self.tmp, "shots"))

    def test_all_pass(self):
        with self.case.step("步骤一"):
            pass
        with self.case.step("步骤二"):
            pass
        res = self.case.finish(self.tmp)
        self.assertEqual(res.status, "pass")
        self.assertEqual(res.passed, 2)
        self.assertEqual(res.failed, 0)

    def test_failure_recorded_and_continues(self):
        with self.case.step("会失败"):
            raise AssertionError("刻意失败")
        with self.case.step("后续仍执行"):
            pass
        res = self.case.finish(self.tmp)
        self.assertEqual(res.status, "fail")
        self.assertEqual(res.failed, 1)
        self.assertEqual(len(res.steps), 2, "失败后应继续执行后续步骤")
        self.assertIn("刻意失败", res.steps[0].error)

    def test_failure_captures_screenshot(self):
        with self.case.step("失败并截图"):
            assert False, "boom"
        res = self.case.finish(self.tmp)
        shot = res.steps[0].screenshot
        self.assertTrue(shot and os.path.exists(shot), "失败步骤应留下截图")

    def test_report_files_written(self):
        with self.case.step("一步"):
            pass
        self.case.finish(self.tmp)
        names = os.listdir(self.tmp)
        self.assertTrue(any(n.endswith(".json") for n in names))
        self.assertTrue(any(n.endswith(".html") for n in names))

    def test_html_escapes_special_chars(self):
        res = CaseResult(name="<script>alert(1)</script>")
        res.steps.append(Step(name="a<b", status="pass"))
        html = render_html(res, os.path.join(self.tmp, "shots"))
        self.assertNotIn("<script>", html)
        self.assertIn("&lt;script&gt;", html)
        self.assertIn("a&lt;b", html)


if __name__ == "__main__":
    unittest.main()
