#!/usr/bin/env python3

import argparse
import string
import time
from pylibftdi.device import Device
from pylibftdi.driver import Driver, FtdiError


class AtlasDevice(Device):
    def __init__(self, serial):
        # 't' mode attempts to give text; fallback logic handles bytes too.
        super().__init__(mode='t', device_id=serial)
        # flush any residual data
        try:
            self.flush()
        except Exception:
            pass

    def _read_raw_char(self):
        # Device.read(1) may return bytes or str depending on mode; unify to bytes
        ch = self.read(1)
        if isinstance(ch, str):
            return ch.encode('latin1')
        elif isinstance(ch, bytes):
            return ch
        else:
            return b''

    def read_line(self, size=0):
        """
        Read until '\r' (the EZO line terminator) or EOF/size limit.
        Returns a decoded string.
        """
        lsl = len(b'\r')
        buf = bytearray()
        while True:
            next_char = self._read_raw_char()
            if not next_char:
                break
            buf.extend(next_char)
            if size > 0 and len(buf) >= size:
                break
            if len(buf) >= lsl and buf[-lsl:] == b'\r':
                break
        # decode safely
        try:
            return buf.decode('utf-8', errors='ignore')
        except Exception:
            return buf.decode('latin1', errors='ignore')

    def read_lines(self):
        lines = []
        try:
            while True:
                line = self.read_line()
                if not line:
                    break
                lines.append(line)
            return lines
        except FtdiError:
            print("Failed to read from the sensor.")
            return []

    def send_cmd(self, cmd):
        """
        Send command with carriage return appended.
        """
        buf = cmd + "\r"
        try:
            # pylibftdi Device.write accepts str in 't' mode
            self.write(buf)
            return True
        except FtdiError as e:
            print("Failed to send command to the sensor:", e)
            return False


def list_ftdi_serials():
    serials = []
    for device in Driver().list_devices():
        # device is typically a tuple like (vendor, product, serial) of bytes or str
        normalized = []
        for x in device:
            if isinstance(x, bytes):
                normalized.append(x.decode('latin1'))
            else:
                normalized.append(x)
        if len(normalized) >= 3:
            _, _, serial = normalized[:3]
            serials.append(serial)
    return serials


def get_ph_reading(dev: AtlasDevice):
    # Request a reading
    if not dev.send_cmd("R"):
        return None
    # Atlas docs suggest waiting ~1.3s for the response; give a bit of headroom
    time.sleep(1.5)
    lines = dev.read_lines()
    for line in lines:
        # skip lines that start with '*' (busy markers) or empty
        if line and not line.startswith('*'):
            return line.strip()
    return None


def main():
    parser = argparse.ArgumentParser(description="Read pH from Atlas Scientific EZO pH probe over FTDI on Raspberry Pi.")
    parser.add_argument(
        "--serial", "-s", type=str, default=None,
        help="Serial number of the FTDI device to use (e.g., DP06843O). If omitted, first device is used."
    )
    parser.add_argument(
        "--poll", "-p", type=float, default=None,
        help="If provided, poll every N seconds continuously."
    )
    args = parser.parse_args()

    available = list_ftdi_serials()
    if not available:
        print("No FTDI devices detected.")
        return

    chosen_serial = None
    if args.serial:
        # fuzzy match: exact or substring
        matches = [s for s in available if args.serial in s]
        if not matches:
            print(f"Serial '{args.serial}' not found among detected devices: {available}")
            return
        chosen_serial = matches[0]
    else:
        chosen_serial = available[0]

    print(f"Using FTDI device serial: {chosen_serial}")
    try:
        dev = AtlasDevice(chosen_serial)
    except Exception as e:
        print("Failed to open device:", e)
        return

    try:
        if args.poll:
            interval = args.poll
            print(f"Polling pH every {interval} seconds. Ctrl-C to stop.")
            while True:
                reading = get_ph_reading(dev)
                timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
                if reading is not None:
                    print(f"[{timestamp}] pH: {reading}")
                else:
                    print(f"[{timestamp}] No valid response.")
                time.sleep(interval)
        else:
            reading = get_ph_reading(dev)
            if reading is not None:
                print(f"pH reading: {reading}")
            else:
                print("Failed to get a pH reading.")
    except KeyboardInterrupt:
        print("Stopped by user.")
    finally:
        try:
            dev.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
