import unittest
from unittest import mock

from vibe_stick.audio import recorder


class RecordingControllerConcurrencyTests(unittest.TestCase):
    def test_second_device_cannot_replace_active_recording_session(self) -> None:
        controller = recorder.RecordingController.__new__(recorder.RecordingController)
        active = recorder.RecordingSession(
            session_id="device-one",
            active=True,
            status="recording",
            message="Recording session started",
        )
        controller.session = active
        controller.audio_recorder = mock.Mock()
        controller._save = mock.Mock()

        result = controller.start(
            {"session_id": "device-two", "audio_source": "sticks3_pcm"}
        )

        self.assertEqual(result.status, "start_failed")
        self.assertIn("active", result.message.lower())
        self.assertIs(controller.session, active)
        self.assertTrue(controller.session.active)

    def test_mismatched_pcm_upload_does_not_corrupt_active_session(self) -> None:
        controller = recorder.RecordingController.__new__(recorder.RecordingController)
        active = recorder.RecordingSession(
            session_id="device-one",
            active=True,
            status="recording",
            message="Recording session started",
        )
        controller.session = active
        controller._save = mock.Mock()

        result = controller.attach_pcm(b"\x00\x01", session_id="device-two")

        self.assertEqual(result.status, "audio_failed")
        self.assertIs(controller.session, active)
        self.assertEqual(controller.session.status, "recording")
        self.assertTrue(controller.session.active)


if __name__ == "__main__":
    unittest.main()
