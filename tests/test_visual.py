import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "client"))

import visual  # noqa: E402


def px(r, g, b, a=255):
    return bytes([r, g, b, a])


class VisualDiffTest(unittest.TestCase):
    def test_identical(self):
        a = px(10, 20, 30) * 100
        ratio, delta = visual.diff_ratio(a, a)
        self.assertEqual(ratio, 0.0)
        self.assertEqual(delta, 0)

    def test_length_mismatch(self):
        ratio, delta = visual.diff_ratio(px(1, 2, 3), px(1, 2, 3) * 2)
        self.assertEqual(ratio, 1.0)
        self.assertEqual(delta, 255)

    def test_all_changed(self):
        a = px(0, 0, 0) * 10
        b = px(255, 255, 255) * 10
        ratio, delta = visual.diff_ratio(a, b)
        self.assertEqual(ratio, 1.0)
        self.assertEqual(delta, 255)

    def test_tolerance_absorbs_small_delta(self):
        a = px(100, 100, 100) * 10
        b = px(108, 108, 108) * 10      # 差 8，低于阈值 16
        ratio, delta = visual.diff_ratio(a, b, pixel_tolerance=16)
        self.assertEqual(ratio, 0.0)
        self.assertEqual(delta, 8)

    def test_partial_change(self):
        a = px(0, 0, 0) * 10
        b = px(0, 0, 0) * 8 + px(255, 0, 0) * 2
        ratio, _ = visual.diff_ratio(a, b)
        self.assertAlmostEqual(ratio, 0.2)

    def test_alpha_only_change_ignored(self):
        a = bytes([1, 2, 3, 255]) * 5
        b = bytes([1, 2, 3, 0]) * 5      # 只改 alpha
        ratio, _ = visual.diff_ratio(a, b)
        self.assertEqual(ratio, 0.0, "alpha 不参与比较")

    def test_baseline_create_then_compare(self):
        tmp = tempfile.mkdtemp()
        pixels = px(5, 6, 7) * 20

        same, ratio, created = visual.compare_or_create(tmp, "btn", 4, 5, pixels)
        self.assertTrue(created)
        self.assertEqual(ratio, 0.0)
        self.assertTrue(same)

        same2, ratio2, created2 = visual.compare_or_create(tmp, "btn", 4, 5, pixels)
        self.assertFalse(created2)
        self.assertTrue(same2)
        self.assertEqual(ratio2, 0.0)

        changed = px(5, 6, 7) * 19 + px(255, 0, 0)
        same3, ratio3, _ = visual.compare_or_create(tmp, "btn", 4, 5, changed)
        self.assertFalse(same3)
        self.assertAlmostEqual(ratio3, 0.05)

    def test_size_change_detected(self):
        tmp = tempfile.mkdtemp()
        visual.compare_or_create(tmp, "x", 4, 5, px(0, 0, 0) * 20)
        same, ratio, _ = visual.compare_or_create(tmp, "x", 5, 4, px(0, 0, 0) * 20)
        self.assertFalse(same)
        self.assertEqual(ratio, 1.0)


if __name__ == "__main__":
    unittest.main()
