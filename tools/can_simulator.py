#!/usr/bin/env python3
import json
import logging
import queue
import socket
import subprocess
import threading
import tkinter as tk
from collections import deque
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from tkinter import ttk


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON = ROOT / "default.json"
ADB_FORWARD_PORT = 39082
ADB_SOCKET = "localabstract:mx5-can-sim"
LOG_FILE = Path.home() / ".local/state/mx5-can-simulator.log"


@dataclass(frozen=True)
class Signal:
    id: str
    name: str
    category: str
    unit: str
    request_id: int
    response_id: int
    did: int
    bit_index: int
    bit_length: int
    minimum: float
    maximum: float
    multiplier: float
    divisor: float
    offset: float
    signed: bool

    @property
    def group(self):
        return self.response_id, self.did

    @property
    def initial(self):
        return min(self.maximum, max(self.minimum, 0.0))


def load_catalog(path=DEFAULT_JSON):
    with open(path, encoding="utf-8") as source:
        document = json.load(source)

    signals = []
    for command in document.get("commands", []):
        request = command.get("cmd", {})
        did_hex = request.get("22")
        if not did_hex or len(did_hex) != 4:
            continue
        response_id = int(command["rax"], 16)
        request_id = int(command["hdr"], 16)
        did = int(did_hex, 16)
        for item in command.get("signals", []):
            fmt = item["fmt"]
            bits = int(fmt["len"])
            if not 1 <= bits <= 16:
                continue
            multiplier = float(fmt.get("mul", 1))
            divisor = float(fmt.get("div", 1))
            offset = float(fmt.get("add", 0))
            signed = bool(fmt.get("sign", False))
            if not multiplier or not divisor:
                continue
            raw_min = -(1 << (bits - 1)) if signed else 0
            raw_max = (1 << (bits - 1)) - 1 if signed else (1 << bits) - 1
            minimum = float(fmt.get("min", raw_min * multiplier / divisor + offset))
            maximum = float(fmt.get("max", raw_max * multiplier / divisor + offset))
            if maximum < minimum:
                continue
            signals.append(Signal(
                id=item["id"],
                name=item.get("name", item["id"]),
                category=item.get("path", "Other"),
                unit=fmt.get("unit", "scalar"),
                request_id=request_id,
                response_id=response_id,
                did=did,
                bit_index=int(fmt.get("bix", 0)),
                bit_length=bits,
                minimum=minimum,
                maximum=maximum,
                multiplier=multiplier,
                divisor=divisor,
                offset=offset,
                signed=signed,
            ))
    return signals


def build_uds_frame(signal_id, value, signals, values):
    lookup = {signal.id: signal for signal in signals}
    selected = lookup[signal_id]
    values[signal_id] = min(selected.maximum, max(selected.minimum, float(value)))
    group = [signal for signal in signals if signal.group == selected.group]
    payload_bytes = max((signal.bit_index + signal.bit_length + 7) // 8 for signal in group)
    if not 1 <= payload_bytes <= 4:
        raise ValueError("UDS response does not fit in a classic CAN single frame")

    data = bytearray(8)
    data[0] = 3 + payload_bytes
    data[1] = 0x62
    data[2] = selected.did >> 8
    data[3] = selected.did & 0xFF
    for signal in group:
        physical = values.get(signal.id, signal.initial)
        raw = round((physical - signal.offset) * signal.divisor / signal.multiplier)
        if signal.signed and raw < 0:
            raw += 1 << signal.bit_length
        raw &= (1 << signal.bit_length) - 1
        for bit in range(signal.bit_length):
            if (raw >> bit) & 1:
                bit_index = signal.bit_index + bit
                data[4 + bit_index // 8] |= 1 << (bit_index % 8)
    return selected.response_id, bytes(data)


def build_obd_frame(pid, value):
    data = bytearray(8)
    if pid == 0x0C:
        raw = max(0, min(8000, int(value))) * 4
        data[:5] = bytes((4, 0x41, pid, raw >> 8, raw & 0xFF))
    elif pid == 0x0D:
        data[:4] = bytes((3, 0x41, pid, max(0, min(255, int(value)))))
    else:
        raise ValueError(f"Unsupported simulated OBD PID: {pid:02X}")
    return 0x7E8, bytes(data)


def respond_to_request(can_id, data, signals, values, obd_values):
    if can_id == 0x7DF and len(data) >= 3 and data[1] == 0x01:
        pid = data[2]
        if pid in obd_values:
            return build_obd_frame(pid, obd_values[pid])
        return None
    if len(data) >= 4 and data[1] == 0x22:
        did = (data[2] << 8) | data[3]
        match = next((signal for signal in signals
                      if signal.request_id == can_id and signal.did == did), None)
        if match:
            return build_uds_frame(match.id, values.get(match.id, match.initial), signals, values)
    return None


def adb_devices():
    result = subprocess.run(["adb", "devices", "-l"], check=True, capture_output=True, text=True)
    devices = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        serial = parts[0]
        transport = "USB" if any(part.startswith("usb:") for part in parts[2:]) else "Wi-Fi"
        devices.append((serial, f"{serial}  [{transport}]"))
    return devices


class CanSimulatorApp:
    def __init__(self, root):
        self.root = root
        self.root.title("MX-5 CAN Simulator")
        self.root.geometry("1020x820")
        self.root.minsize(760, 600)
        self.signals = load_catalog()
        self.values = {signal.id: signal.initial for signal in self.signals}
        self.obd_values = {0x0C: 700, 0x0D: 0}
        self.state_lock = threading.RLock()
        self.write_lock = threading.Lock()
        self.pending_lock = threading.Lock()
        self.pending_acks = {}
        self.next_sequence = 1
        self.outbound_frames = queue.Queue()
        self.events = deque(maxlen=100)
        self.event_log_height = 100
        self.ui_thread = threading.get_ident()
        self.socket = None
        self.forwarded_device = None
        self.sent_frames = 0
        self.device_rows = []
        self.status = tk.StringVar(value="ADB disconnected")
        self.frame_count = tk.StringVar(value="0 frames")
        self.category = tk.StringVar()

        self._build_ui()
        self._refresh_devices()
        self.writer_thread = threading.Thread(target=self._send_worker, daemon=True)
        self.writer_thread.start()
        self._log(f"Ready. Persistent diagnostics: {LOG_FILE}")
        self.root.protocol("WM_DELETE_WINDOW", self._close)

    def _build_ui(self):
        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="MX-5 CAN SIMULATOR", font=("TkDefaultFont", 18, "bold")).pack(anchor="w")
        ttk.Label(outer, text="Local test frames only. Nothing is sent to a physical CAN adapter.").pack(anchor="w", pady=(2, 14))

        connection = ttk.Frame(outer)
        connection.pack(fill="x", pady=(0, 14))
        self.device_choice = ttk.Combobox(connection, state="readonly", width=58)
        self.device_choice.pack(side="left", fill="x", expand=True)
        ttk.Button(connection, text="Refresh", command=self._refresh_devices).pack(side="left", padx=(8, 4))
        self.connect_button = ttk.Button(connection, text="Connect to phone", command=self._connect)
        self.connect_button.pack(side="left")
        ttk.Label(outer, textvariable=self.status).pack(anchor="w")
        ttk.Label(outer, textvariable=self.frame_count).pack(anchor="w", pady=(0, 12))

        obd = ttk.LabelFrame(outer, text="Standard OBD-II display", padding=10)
        obd.pack(fill="x", pady=(0, 12))
        self._add_obd_slider(obd, "Engine RPM", 0, 8000, 700, 0x0C)
        self._add_obd_slider(obd, "Vehicle speed (km/h)", 0, 220, 0, 0x0D)

        category_row = ttk.Frame(outer)
        category_row.pack(fill="x", pady=(0, 8))
        ttk.Label(category_row, text=f"Vehicle signals from default.json ({len(self.signals)})").pack(side="left")
        categories = list(dict.fromkeys(signal.category for signal in self.signals))
        self.category.set("Engine" if "Engine" in categories else categories[0])
        category_picker = ttk.Combobox(category_row, textvariable=self.category, values=categories, state="readonly", width=24)
        category_picker.pack(side="right")
        category_picker.bind("<<ComboboxSelected>>", lambda event: self._populate_signals())

        list_frame = ttk.Frame(outer)
        list_frame.pack(fill="both", expand=True)
        canvas = tk.Canvas(list_frame, highlightthickness=0)
        scrollbar = ttk.Scrollbar(list_frame, orient="vertical", command=canvas.yview)
        self.signal_rows = ttk.Frame(canvas)
        self.signal_rows.bind("<Configure>", lambda event: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=self.signal_rows, anchor="nw")
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        self._populate_signals()

        events_frame = ttk.LabelFrame(outer, text="Simulator event log", padding=6)
        events_frame.pack(fill="x", pady=(10, 0))
        self.event_log = tk.Text(events_frame, height=6, wrap="word", state="disabled")
        self.event_log.tag_configure("error", foreground="#b42318")
        events_scroll = ttk.Scrollbar(events_frame, orient="vertical", command=self.event_log.yview)
        self.event_log.configure(yscrollcommand=events_scroll.set)
        self.event_log.pack(side="left", fill="both", expand=True)
        events_scroll.pack(side="right", fill="y")

    def _add_obd_slider(self, parent, label, minimum, maximum, initial, pid):
        row = ttk.Frame(parent)
        row.pack(fill="x", pady=2)
        ttk.Label(row, text=label, width=24).pack(side="left")
        value_text = tk.StringVar(value=str(initial))
        ttk.Label(row, textvariable=value_text, width=8).pack(side="right")
        scale = tk.Scale(row, from_=minimum, to=maximum, orient="horizontal", showvalue=False,
                         resolution=1, command=lambda value: self._obd_changed(pid, value, value_text))
        scale.set(initial)
        scale.pack(side="left", fill="x", expand=True, padx=8)

    def _populate_signals(self):
        for child in self.signal_rows.winfo_children():
            child.destroy()
        for signal in self.signals:
            if signal.category != self.category.get():
                continue
            row = ttk.Frame(self.signal_rows, padding=(4, 5))
            row.pack(fill="x")
            ttk.Label(row, text=signal.name, width=40).pack(side="left")
            if signal.unit == "offon":
                state = tk.BooleanVar(value=bool(self.values[signal.id]))
                ttk.Checkbutton(row, text="On", variable=state,
                                command=lambda item=signal, var=state: self._signal_changed(item, int(var.get()))).pack(side="left")
            else:
                value_text = tk.StringVar(value=self._format_value(self.values[signal.id], signal.unit))
                ttk.Label(row, textvariable=value_text, width=14).pack(side="right")
                scale = tk.Scale(row, from_=signal.minimum, to=signal.maximum, orient="horizontal",
                                 showvalue=False, resolution=max((signal.maximum - signal.minimum) / 500, 0.001),
                                 command=lambda value, item=signal, label=value_text: self._signal_changed(item, value, label))
                scale.set(self.values[signal.id])
                scale.pack(side="left", fill="x", expand=True, padx=8)

    def _refresh_devices(self):
        try:
            self.device_rows = adb_devices()
        except (OSError, subprocess.CalledProcessError) as error:
            self.device_rows = []
            self.status.set(f"ADB unavailable: {error}")
            self._log(f"ADB unavailable: {error}", error=True)
        self.device_choice["values"] = [label for _, label in self.device_rows]
        if self.device_rows:
            preferred = next((index for index, (_, label) in enumerate(self.device_rows) if "[USB]" in label), 0)
            self.device_choice.current(preferred)
        else:
            self.device_choice.set("")

    def _connect(self):
        if self.socket:
            self._disconnect()
            return
        index = self.device_choice.current()
        if index < 0 or index >= len(self.device_rows):
            self.status.set("Select an authorized ADB device")
            return
        serial = self.device_rows[index][0]
        try:
            subprocess.run(["adb", "-s", serial, "forward", f"tcp:{ADB_FORWARD_PORT}", ADB_SOCKET],
                           check=True, capture_output=True, text=True)
            self.socket = socket.create_connection(("127.0.0.1", ADB_FORWARD_PORT), timeout=3)
            self.socket.settimeout(None)
            self.forwarded_device = serial
            self.connect_button.configure(text="Disconnect")
            self.device_choice.configure(state="disabled")
            self.status.set(f"Connected to {serial}; frames are local simulations")
            self._log(f"Connected to {serial} through adb forward")
            threading.Thread(target=self._read_phone_messages, args=(self.socket,), daemon=True).start()
            self._send_frame(*build_obd_frame(0x0C, 700))
            self._send_frame(*build_obd_frame(0x0D, 0))
        except (OSError, subprocess.CalledProcessError) as error:
            self.status.set(f"Connection failed: {error}")
            self._log(f"Connection failed: {error}", error=True)
            self._remove_forward(serial)

    def _disconnect(self):
        sock = self.socket
        self.socket = None
        if sock:
            try:
                sock.close()
            except OSError:
                pass
        with self.pending_lock:
            pending = list(self.pending_acks.values())
            self.pending_acks.clear()
        for item in pending:
            item["ack"] = {"ok": False}
            item["event"].set()
        while True:
            try:
                self.outbound_frames.get_nowait()
            except queue.Empty:
                break
        self._remove_forward(self.forwarded_device)
        self.forwarded_device = None
        self.connect_button.configure(text="Connect to phone")
        self.device_choice.configure(state="readonly")
        self.status.set("ADB disconnected")
        self._log("ADB simulator disconnected")

    def _remove_forward(self, serial):
        if not serial:
            return
        subprocess.run(["adb", "-s", serial, "forward", "--remove", f"tcp:{ADB_FORWARD_PORT}"],
                       capture_output=True, text=True)

    def _obd_changed(self, pid, value, label):
        value = int(float(value))
        with self.state_lock:
            self.obd_values[pid] = value
        label.set(str(value))
        self._send_frame(*build_obd_frame(pid, value))

    def _signal_changed(self, signal, value, label=None):
        value = float(value)
        with self.state_lock:
            self.values[signal.id] = value
        if label:
            label.set(self._format_value(value, signal.unit))
        try:
            can_id, data = build_uds_frame(signal.id, value, self.signals, self.values)
            self._send_frame(can_id, data)
        except (KeyError, ValueError) as error:
            self.status.set(f"Frame encoding failed: {error}")
            self._log(f"Frame encoding failed: {error}", error=True)

    def _send_frame(self, can_id, data, wait_for_ack=True):
        if not self.socket:
            return
        if wait_for_ack:
            self.outbound_frames.put((self.socket, can_id, bytes(data)))
            self._log(f"Queued frame 0x{can_id:03X} {data.hex().upper()}")
            return
        try:
            self._send_message({"type": "frame", "canId": can_id, "data": data.hex().upper()}, False)
            self.root.after(0, self._count_sent_frame)
            self.root.after(0, self._log, f"Sent response frame 0x{can_id:03X} {data.hex().upper()}")
        except (OSError, ValueError, TimeoutError) as error:
            self.root.after(0, self._report_link_error, error)

    def _send_worker(self):
        while True:
            task = self.outbound_frames.get()
            if task is None:
                return
            sock, can_id, data = task
            try:
                self._send_message({"type": "frame", "canId": can_id, "data": data.hex().upper()}, True, sock)
                self.root.after(0, self._count_sent_frame)
                self.root.after(0, self._log, f"Phone ACKed frame 0x{can_id:03X} {data.hex().upper()}")
            except (OSError, ValueError, TimeoutError) as error:
                self.root.after(0, self._report_link_error, error)

    def _send_message(self, message, wait_for_ack=True, sock=None):
        sock = sock or self.socket
        if sock is None:
            raise OSError("Simulator is disconnected")
        with self.pending_lock:
            sequence = self.next_sequence
            self.next_sequence += 1
            message["seq"] = sequence
            pending = {"event": threading.Event(), "ack": None} if wait_for_ack else None
            if pending:
                self.pending_acks[sequence] = pending
        with self.write_lock:
            sock.sendall((json.dumps(message) + "\n").encode("ascii"))
        if not pending:
            return sequence
        if not pending["event"].wait(3):
            with self.pending_lock:
                self.pending_acks.pop(sequence, None)
            raise TimeoutError("Phone did not acknowledge the simulated frame")
        acknowledgement = pending["ack"]
        if not acknowledgement or not acknowledgement.get("ok"):
            raise ValueError("Phone rejected the simulated frame")
        return sequence

    def _send_ack(self, sequence, accepted):
        if not self.socket:
            return
        message = {"type": "ack", "seq": sequence, "ok": accepted}
        with self.write_lock:
            self.socket.sendall((json.dumps(message) + "\n").encode("ascii"))

    def _read_phone_messages(self, sock):
        try:
            with sock.makefile("r", encoding="ascii") as reader:
                for line in reader:
                    message = json.loads(line)
                    message_type = message.get("type")
                    if message_type == "ack":
                        with self.pending_lock:
                            pending = self.pending_acks.pop(message.get("seq"), None)
                        if pending:
                            pending["ack"] = message
                            pending["event"].set()
                            self.root.after(0, self._log,
                                            f"Phone ACK seq {message.get('seq')}: {message.get('ok')}")
                    elif message_type == "tx":
                        self.root.after(0, self._log,
                                        f"Phone request 0x{int(message.get('canId', 0)):03X} {message.get('data', '')}")
                        self._handle_phone_request(message)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            if self.socket is sock:
                logging.exception("Phone message reader failed")
                self.root.after(0, self._link_lost, sock, error)

    def _handle_phone_request(self, message):
        sequence = message.get("seq", -1)
        try:
            can_id = int(message["canId"])
            data = bytes.fromhex(message["data"])
            with self.state_lock:
                values = dict(self.values)
                obd_values = dict(self.obd_values)
            response = respond_to_request(can_id, data, self.signals, values, obd_values)
            self._send_ack(sequence, True)
            if response:
                self._send_frame(*response, wait_for_ack=False)
                self.root.after(0, self._log,
                                f"Simulator response 0x{response[0]:03X} {response[1].hex().upper()}")
                self.root.after(0, self.status.set, f"Responded to app request 0x{can_id:03X}")
            else:
                self.root.after(0, self._log, f"No response configured for 0x{can_id:03X} {data.hex().upper()}", True)
                self.root.after(0, self.status.set, f"No simulated response for 0x{can_id:03X}")
        except (KeyError, ValueError, OSError) as error:
            try:
                self._send_ack(sequence, False)
            except OSError:
                pass
            logging.exception("Failed to answer phone request")
            self.root.after(0, self._log, f"Failed to answer phone request: {error}", True)
            self.root.after(0, self.status.set, f"App request failed: {error}")

    def _link_lost(self, sock, error):
        if self.socket is sock:
            self.status.set(f"Simulator link lost: {error}")
            self._log(f"Simulator link lost: {error}", error=True)
            self._disconnect()

    def _count_sent_frame(self):
        self.sent_frames += 1
        self.frame_count.set(f"{self.sent_frames} frames sent")

    def _report_link_error(self, error):
        self.status.set(f"Simulator send failed: {error}")
        self._log(f"Simulator send failed: {error}", error=True)

    def _log(self, message, error=False):
        if threading.get_ident() != self.ui_thread:
            self.root.after(0, self._log, message, error)
            return
        timestamp = datetime.now().strftime("%H:%M:%S")
        line = f"{timestamp}  {message}"
        logging.log(logging.ERROR if error else logging.INFO, message)
        self.events.append(line)
        self.event_log.configure(state="normal")
        self.event_log.insert("end", line + "\n", "error" if error else "normal")
        line_count = int(self.event_log.index("end-1c").split(".")[0])
        if line_count > self.event_log_height:
            self.event_log.delete("1.0", f"{line_count - self.event_log_height}.0")
        self.event_log.see("end")
        self.event_log.configure(state="disabled")

    @staticmethod
    def _format_value(value, unit):
        return f"{value:.2f} {unit}"

    def _close(self):
        self._disconnect()
        self.outbound_frames.put(None)
        self.writer_thread.join(timeout=1)
        self.root.destroy()


def main():
    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(filename=LOG_FILE, level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    root = tk.Tk()
    CanSimulatorApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()