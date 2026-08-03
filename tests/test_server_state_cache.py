import threading
import unittest
from unittest import mock

from vibe_stick.protocol.state import default_state
from vibe_stick.server import app


class ServerStateCacheTests(unittest.TestCase):
    def test_repeated_state_reads_share_recent_provider_refresh(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._last_provider_refresh_monotonic = 0.0
        store._refresh_providers_locked = mock.Mock()
        store._save_state_locked = mock.Mock()

        with mock.patch.object(app.time, "monotonic", side_effect=[100.0, 101.0]):
            with mock.patch.object(app, "_computer_name", return_value="Desk"):
                store.get_state()
                store.get_state()

        store._refresh_providers_locked.assert_called_once_with()

    def test_recording_mutation_returns_cached_provider_state(self) -> None:
        store = app.BridgeStateStore.__new__(app.BridgeStateStore)
        store._lock = threading.RLock()
        store._operation_lock = threading.Lock()
        store._state = default_state()
        store._save_state_locked = mock.Mock()
        session = mock.Mock()
        session.to_jsonable.return_value = {"status": "recording"}
        store.recording = mock.Mock()
        store.recording.start.return_value = session
        store.get_state = mock.Mock(return_value=default_state())

        store.start_recording({"session_id": "phone-session"})

        store.get_state.assert_called_once_with(refresh_providers=False)


if __name__ == "__main__":
    unittest.main()
