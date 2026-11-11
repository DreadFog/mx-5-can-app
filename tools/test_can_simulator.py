import unittest
import queue
import json
import socket
import threading
import time

from can_simulator import CanSimulatorApp, build_obd_frame, build_uds_frame, load_catalog, respond_to_request


class CanSimulatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.signals = load_catalog()
        cls.values = {signal.id: signal.initial for signal in cls.signals}
        cls.by_id = {signal.id: signal for signal in cls.signals}

    def test_loads_all_default_signals(self):
        self.assertEqual(len(self.signals), 91)
        self.assertIn("Engine", {signal.category for signal in self.signals})

    def test_brake_and_clutch_share_a_response_frame(self):
        can_id, brake = build_uds_frame("MX5_BPA_SW", 1, self.signals, dict(self.values))
        self.assertEqual(can_id, 0x7E8)
        self.assertEqual(brake[:5], bytes((4, 0x62, 0xA2, 0x11, 0x40)))
        _, clutch = build_uds_frame("MX5_CPP_SW", 1, self.signals, dict(self.values))
        self.assertEqual(clutch[4], 0x80)

    def test_obd_rpm_and_speed_responses(self):
        can_id, rpm = build_obd_frame(0x0C, 720)
        self.assertEqual(can_id, 0x7E8)
        self.assertEqual(rpm[:5], bytes((4, 0x41, 0x0C, 0x0B, 0x40)))
        _, speed = build_obd_frame(0x0D, 42)
        self.assertEqual(speed[:4], bytes((3, 0x41, 0x0D, 42)))

    def test_phone_active_obd_reads_get_current_slider_values(self):
        rpm = respond_to_request(0x7DF, bytes((2, 1, 0x0C)), self.signals, self.values,
                                 {0x0C: 720, 0x0D: 42})
        speed = respond_to_request(0x7DF, bytes((2, 1, 0x0D)), self.signals, self.values,
                                   {0x0C: 720, 0x0D: 42})
        self.assertEqual(rpm[1][:5], bytes((4, 0x41, 0x0C, 0x0B, 0x40)))
        self.assertEqual(speed[1][:4], bytes((3, 0x41, 0x0D, 42)))

    def test_phone_uds_read_uses_current_brake_value(self):
        values = dict(self.values)
        values["MX5_BPA_SW"] = 1
        response = respond_to_request(0x7E0, bytes((3, 0x22, 0xA2, 0x11)),
                                      self.signals, values, {})
        self.assertEqual(response[0], 0x7E8)
        self.assertEqual(response[1][:5], bytes((4, 0x62, 0xA2, 0x11, 0x40)))

    def test_slider_frame_is_queued_without_waiting_for_phone_ack(self):
        app = CanSimulatorApp.__new__(CanSimulatorApp)
        app.socket = object()
        app.outbound_frames = queue.Queue()
        app._log = lambda message: None

        CanSimulatorApp._send_frame(app, 0x7E8, bytes((3, 0x41, 0x0D, 42)))

        queued_socket, can_id, data = app.outbound_frames.get_nowait()
        self.assertIs(app.socket, queued_socket)
        self.assertEqual(can_id, 0x7E8)
        self.assertEqual(data, bytes((3, 0x41, 0x0D, 42)))

    def test_async_frame_sender_processes_phone_ack(self):
        phone, pc = socket.socketpair()
        sent = threading.Event()

        class FakeRoot:
            @staticmethod
            def after(delay, callback, *args):
                callback(*args)

        class FakeCounter:
            @staticmethod
            def set(value):
                if value == "1 frames sent":
                    sent.set()

        app = CanSimulatorApp.__new__(CanSimulatorApp)
        app.socket = pc
        app.outbound_frames = queue.Queue()
        app.pending_acks = {}
        app.pending_lock = threading.Lock()
        app.write_lock = threading.Lock()
        app.next_sequence = 1
        app.root = FakeRoot()
        app.frame_count = FakeCounter()
        app.sent_frames = 0
        app._log = lambda message, error=False: None

        reader = threading.Thread(target=app._read_phone_messages, args=(pc,), daemon=True)
        writer = threading.Thread(target=app._send_worker, daemon=True)
        reader.start()
        writer.start()

        started = time.monotonic()
        CanSimulatorApp._send_frame(app, 0x7E8, bytes((3, 0x41, 0x0D, 42)))
        self.assertLess(time.monotonic() - started, 0.1)

        with phone.makefile("r", encoding="ascii") as stream:
            message = json.loads(stream.readline())
        self.assertEqual(message["type"], "frame")
        phone.sendall((json.dumps({"type": "ack", "seq": message["seq"], "ok": True}) + "\n").encode())
        self.assertTrue(sent.wait(1))
        self.assertEqual(app.sent_frames, 1)

        app.socket = None
        pc.close()
        phone.close()
        app.outbound_frames.put(None)
        writer.join(1)


if __name__ == "__main__":
    unittest.main()