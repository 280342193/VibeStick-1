import subprocess
import unittest
from unittest import mock

from vibe_stick.paste import input_injector


class WindowsPasteInjectorTests(unittest.TestCase):
    def test_clipboard_write_retries_transient_failure(self) -> None:
        failed = subprocess.CompletedProcess(
            args=["powershell"],
            returncode=1,
            stdout="",
            stderr="clipboard busy",
        )
        succeeded = subprocess.CompletedProcess(
            args=["powershell"],
            returncode=0,
            stdout="",
            stderr="",
        )

        with mock.patch.object(input_injector.subprocess, "run", side_effect=[failed, succeeded]) as run:
            with mock.patch.object(input_injector.time, "sleep"):
                result = input_injector.WindowsPasteInjector()._set_clipboard("hello")

        self.assertTrue(result.success)
        self.assertEqual(run.call_count, 2)


if __name__ == "__main__":
    unittest.main()
