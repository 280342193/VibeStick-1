import threading
import unittest
from http.client import HTTPConnection
from unittest import mock

from vibe_stick.paste.input_injector import PasteResult
from vibe_stick.protocol.state import AlertState, AlertType, default_state
from vibe_stick.server import app


class ServerEventsTests(unittest.TestCase):
    def test_complete_recording_keeps_state_reads_available_during_transcription(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._save_state_locked = mock.Mock()
        recording = mock.Mock()
        recording.start.return_value = mock.Mock(status="recording")
        recording.attach_pcm.return_value = mock.Mock(status="recording")
        stopped = mock.Mock()
        stopped.to_jsonable.return_value = {"status": "pasted"}
        stop_entered = threading.Event()
        release_stop = threading.Event()

        def stop_recording(_request: dict[str, object]) -> mock.Mock:
            stop_entered.set()
            release_stop.wait(timeout=2)
            return stopped

        recording.stop.side_effect = stop_recording
        store.recording = recording
        completion = threading.Thread(
            target=lambda: store.complete_recording(b"\x00\x01", session_id="phone"),
            daemon=True,
        )
        completion.start()
        self.assertTrue(stop_entered.wait(timeout=1))
        state_read_done = threading.Event()
        state_read = threading.Thread(
            target=lambda: (
                store.get_state(refresh_providers=False),
                state_read_done.set(),
            ),
            daemon=True,
        )
        state_read.start()

        try:
            self.assertTrue(
                state_read_done.wait(timeout=0.2),
                "state read blocked behind PC-side transcription",
            )
        finally:
            release_stop.set()
            completion.join(timeout=2)
            state_read.join(timeout=2)

    def test_text_input_waits_for_active_voice_paste_operation(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store.get_state = mock.Mock(return_value=default_state())
        recording = mock.Mock()
        recording.start.return_value = mock.Mock(status="recording")
        recording.attach_pcm.return_value = mock.Mock(status="recording")
        stopped = mock.Mock()
        stopped.to_jsonable.return_value = {"status": "pasted"}
        stop_entered = threading.Event()
        release_stop = threading.Event()

        def stop_recording(_request: dict[str, object]) -> mock.Mock:
            stop_entered.set()
            release_stop.wait(timeout=2)
            return stopped

        recording.stop.side_effect = stop_recording
        store.recording = recording
        injector = mock.Mock()
        paste_called = threading.Event()
        injector.paste.side_effect = lambda *_args, **_kwargs: (
            paste_called.set() or PasteResult(True, "sent")
        )

        with mock.patch.object(app, "make_paste_injector", return_value=injector):
            completion = threading.Thread(
                target=lambda: store.complete_recording(b"\x00\x01", session_id="phone"),
                daemon=True,
            )
            completion.start()
            self.assertTrue(stop_entered.wait(timeout=1))
            text_send = threading.Thread(
                target=lambda: store.send_text_input({"text": "run tests", "submit": True}),
                daemon=True,
            )
            text_send.start()

            try:
                self.assertFalse(
                    paste_called.wait(timeout=0.1),
                    "text paste raced an active voice paste operation",
                )
            finally:
                release_stop.set()
                completion.join(timeout=2)
                text_send.join(timeout=2)

        self.assertTrue(paste_called.is_set())

    def test_complete_recording_endpoint_forwards_pcm_and_audio_metadata(self) -> None:
        store = mock.Mock()
        store.complete_recording.return_value = {
            "recording": {"status": "pasted"},
            "state": {},
        }
        server = app.ThreadingHTTPServer(("127.0.0.1", 0), app.make_handler(store))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        connection = HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        pcm = b"\x00\x01\x02\x03"

        try:
            with mock.patch.object(app, "_bridge_tokens", return_value=("phone-token",)):
                connection.request(
                    "POST",
                    "/recording/complete?session_id=phone%20session",
                    body=pcm,
                    headers={
                        "Content-Type": "application/octet-stream",
                        "Content-Length": str(len(pcm)),
                        "X-Vibe-Stick-Token": "phone-token",
                        "X-Vibe-Stick-Sample-Rate": "16000",
                        "X-Vibe-Stick-Channels": "1",
                        "X-Vibe-Stick-Bits-Per-Sample": "16",
                    },
                )
                response = connection.getresponse()
                response.read()
        finally:
            connection.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertEqual(response.status, 200)
        store.complete_recording.assert_called_once_with(
            pcm,
            session_id="phone session",
            sample_rate=16000,
            channels=1,
            bits_per_sample=16,
        )

    def test_complete_recording_processes_uploaded_pcm_in_one_call(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._state.alert = AlertState(
            event_id="evt_done",
            type=AlertType.DONE,
            message="Task completed",
        )
        store._save_state_locked = mock.Mock()
        store.get_state = mock.Mock(return_value=default_state())
        recording = mock.Mock()
        started = mock.Mock(status="recording")
        uploaded = mock.Mock(status="recording")
        stopped = mock.Mock()
        stopped.to_jsonable.return_value = {
            "status": "pasted",
            "transcript": "run tests",
        }
        recording.start.return_value = started
        recording.attach_pcm.return_value = uploaded
        recording.stop.return_value = stopped
        store.recording = recording
        pcm = b"\x00\x01\x02\x03"

        response = store.complete_recording(
            pcm,
            session_id="phone-session",
            sample_rate=16000,
            channels=1,
            bits_per_sample=16,
        )

        recording.start.assert_called_once_with(
            {"session_id": "phone-session", "audio_source": "sticks3_android"}
        )
        recording.attach_pcm.assert_called_once_with(
            pcm,
            session_id="phone-session",
            sample_rate=16000,
            channels=1,
            bits_per_sample=16,
        )
        recording.stop.assert_called_once_with({"paste": True})
        self.assertEqual(response["recording"]["status"], "pasted")
        self.assertEqual(store._state.alert.event_id, "evt_done")
        store.get_state.assert_called_once_with(refresh_providers=False)

    def test_legacy_recording_start_keeps_pending_terminal_alert(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._state.alert = AlertState(
            event_id="evt_error",
            type=AlertType.ERROR,
            message="Task failed",
        )
        store._save_state_locked = mock.Mock()
        store.get_state = mock.Mock(return_value=default_state())
        session = mock.Mock()
        session.to_jsonable.return_value = {"status": "recording"}
        store.recording = mock.Mock()
        store.recording.start.return_value = session

        store.start_recording({"session_id": "phone-session"})

        self.assertEqual(store._state.alert.event_id, "evt_error")

    def test_text_input_pastes_and_submits_in_one_injection(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store.get_state = mock.Mock(return_value=default_state())
        injector = mock.Mock()
        injector.paste.return_value = PasteResult(True, "Pasted and submitted")

        with mock.patch.object(app, "make_paste_injector", return_value=injector):
            response = store.send_text_input({"text": "  run tests  ", "submit": True})

        injector.paste.assert_called_once_with("run tests", press_enter=True)
        self.assertEqual(response["input"]["status"], "sent")
        self.assertTrue(response["input"]["pasted"])
        self.assertTrue(response["input"]["submitted"])
        store.get_state.assert_called_once_with(refresh_providers=False)

    def test_button_short_presses_enter_and_clears_alert(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._save_state_locked = mock.Mock()
        injector = mock.Mock()
        injector.press_enter.return_value = PasteResult(True, "Pressed Enter")

        with mock.patch.object(app, "make_paste_injector", return_value=injector):
            state = store.update_from_event({"event": "button_short"})

        injector.press_enter.assert_called_once_with()
        self.assertEqual(state.alert.type, AlertType.NONE)
        store._save_state_locked.assert_called_once_with()

    def test_button_short_sets_error_alert_when_enter_fails(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._save_state_locked = mock.Mock()
        injector = mock.Mock()
        injector.press_enter.return_value = PasteResult(False, "Accessibility permission missing")

        with mock.patch.object(app, "make_paste_injector", return_value=injector):
            state = store.update_from_event({"event": "button_short"})

        self.assertEqual(state.alert.type, AlertType.ERROR)
        self.assertIn("Accessibility", state.alert.message)


if __name__ == "__main__":
    unittest.main()
