# QT Py USB Sensor Pod (CircuitPython)
# Sensors:
# - SHT30/31 @ 0x44/0x45 via I2C (STEMMA QT)
# - Optional SHT4x @ 0x44 via I2C (coexists with SHT30 at 0x45)
# - DS18B20 (1-Wire) on DS18_PIN with 4.7k pull-up to 3V3
# - Hall flow sensor on FLOW_PIN (open-collector) using countio if available
#
# Output: newline-delimited JSON over USB CDC (print())
#
# Commands over USB serial (one per line):
#   rate=<Hz>      sample frequency (0 < Hz <= 20)
#   id=<string>    device identifier (<= 40 chars)
#   k=<pulses/L>   flow sensor K-factor (pulses per liter)
#
# Tested on: QT Py RP2040 (CircuitPython 9.x)
# Requires libs on CIRCUITPY/lib:
#   adafruit_bus_device, adafruit_register, adafruit_sht31d.mpy, adafruit_sht4x.mpy,
#   adafruit_onewire, adafruit_ds18x20.mpy

import board
import busio
import json
import supervisor
import sys
import time

# Optional modules (graceful fallback if missing/not supported)
try:
    import countio
    from digitalio import Pull
except Exception:  # pragma: no cover
    countio = None
    Pull = None

try:
    import adafruit_sht31d
except Exception:
    adafruit_sht31d = None

try:
    import adafruit_sht4x
except Exception:
    adafruit_sht4x = None

try:
    import adafruit_onewire.bus
    import adafruit_ds18x20
except Exception:
    adafruit_onewire = None
    adafruit_ds18x20 = None

# ---------------- Configuration (change if desired) ----------------
DEVICE_ID = "qtpy-1"
SAMPLE_HZ = 1.0                 # default 1 Hz
DS18_PIN = board.A3             # 1-Wire data pin for DS18B20 + 4.7k pull-up to 3.3V
FLOW_PIN = board.A2             # Pulse input for hall flow sensor (open-collector)
FLOW_K = 450.0                  # pulses per liter (set with 'k=' command)
I2C_FREQ = 100_000              # 100 kHz for longer cables/stability
# -------------------------------------------------------------------

# USB serial helper (non-blocking line reader)
_cmd_buf = ""

def read_serial_command():
    """Return a full line (str) if available, else None."""
    global _cmd_buf
    if not supervisor.runtime.serial_bytes_available:
        return None
    while supervisor.runtime.serial_bytes_available:
        c = sys.stdin.read(1)
        if c in ("\n", "\r"):
            if _cmd_buf:
                line = _cmd_buf
                _cmd_buf = ""
                return line.strip()
        else:
            if len(_cmd_buf) < 128:
                _cmd_buf += c
    return None

def handle_command(line):
    global SAMPLE_HZ, DEVICE_ID, FLOW_K
    if "=" not in line:
        return
    key, val = [s.strip() for s in line.split("=", 1)]
    if key == "rate":
        try:
            hz = float(val)
            if 0.0 < hz <= 20.0:
                SAMPLE_HZ = hz
                print(json.dumps({"type":"cfg","rate_hz":SAMPLE_HZ}))
        except Exception:
            pass
    elif key == "id":
        if 0 < len(val) <= 40:
            DEVICE_ID = val
            print(json.dumps({"type":"cfg","id":DEVICE_ID}))
    elif key == "k":
        try:
            kf = float(val)
            if kf > 0:
                FLOW_K = kf
                print(json.dumps({"type":"cfg","k_pulses_per_liter":FLOW_K}))
        except Exception:
            pass

# I2C setup
i2c = busio.I2C(board.SCL, board.SDA, frequency=I2C_FREQ)

# Detect SHT30/31 (0x44/0x45)
sht30_sensors = {}
if adafruit_sht31d:
    for addr in (0x44, 0x45):
        try:
            dev = adafruit_sht31d.SHT31D(i2c, address=addr)
            # Prime a read to verify presence
            _ = dev.temperature
            sht30_sensors[addr] = dev
        except Exception:
            pass

# Detect SHT4x (optional)
sht4 = None
if adafruit_sht4x:
    try:
        s = adafruit_sht4x.SHT4x(i2c)
        # Pick a reasonable precision / heater off
        s.mode = adafruit_sht4x.Mode.NOHEAT_HIGHPRECISION
        # Prime a read
        _ = s.temperature, s.relative_humidity
        sht4 = s
    except Exception:
        sht4 = None

# 1-Wire DS18B20 setup
ds18_devices = []
if (adafruit_onewire is not None) and (adafruit_ds18x20 is not None):
    try:
        ow_bus = adafruit_onewire.bus.OneWireBus(DS18_PIN)
        # scan() returns low-level OneWire devices; wrap them as DS18X20 objects
        for dev in ow_bus.scan():
            try:
                ds = adafruit_ds18x20.DS18X20(ow_bus, dev)
                # First read to confirm
                _ = ds.temperature
                ds18_devices.append(ds)
            except Exception:
                pass
    except Exception:
        pass

# Flow sensor using countio (optional)
flow_counter = None
if countio is not None and Pull is not None:
    try:
        flow_counter = countio.Counter(FLOW_PIN, edge=countio.Edge.RISE, pull=Pull.UP)
        # Clear any startup counts
        flow_counter.reset()
    except Exception:
        flow_counter = None

# Report what we found
startup = {
    "type": "startup",
    "id": DEVICE_ID,
    "i2c_freq": I2C_FREQ,
    "sht30_addrs": ["0x%02X" % a for a in sht30_sensors.keys()],
    "sht4_present": bool(sht4),
    "ds18b20_count": len(ds18_devices),
    "flow_counter": bool(flow_counter),
    "k_pulses_per_liter": FLOW_K,
    "status": "ok"
}
print(json.dumps(startup))

# Sampling loop
last_ts = time.monotonic()
sample_period = 1.0 / SAMPLE_HZ
last_flow_count = 0
last_flow_time = time.monotonic()

while True:
    # Handle incoming commands
    cmd = read_serial_command()
    if cmd:
        handle_command(cmd)
        # Update period after rate change
        sample_period = 1.0 / SAMPLE_HZ

    now = time.monotonic()
    if now - last_ts >= sample_period:
        last_ts = now

        payload = {
            "type": "env",
            "id": DEVICE_ID,
            "ts_s": now,
        }

        # Read SHT30s
        if sht30_sensors:
            sht30_dict = {}
            for addr, dev in sht30_sensors.items():
                try:
                    t = dev.temperature
                    h = dev.relative_humidity
                    sht30_dict[f"0x{addr:02X}"] = {"t_c": round(t, 2), "rh": round(h, 2)}
                except Exception:
                    sht30_dict[f"0x{addr:02X}"] = {"t_c": None, "rh": None, "status": "read_error"}
            payload["sht30"] = sht30_dict

        # Read SHT4x
        if sht4:
            try:
                t = sht4.temperature
                h = sht4.relative_humidity
                payload["sht4x"] = {"t_c": round(t, 2), "rh": round(h, 2)}
            except Exception:
                payload["sht4x"] = {"t_c": None, "rh": None, "status": "read_error"}

        # Read DS18B20s
        if ds18_devices:
            temps = []
            for ds in ds18_devices:
                try:
                    temps.append({"id": ds.rom, "t_c": round(ds.temperature, 3)})
                except Exception:
                    temps.append({"id": getattr(ds, "rom", "unknown"), "t_c": None, "status": "read_error"})
            payload["ds18b20"] = temps

        # Flow rate calculation
        if flow_counter:
            try:
                count = flow_counter.count
                now2 = time.monotonic()
                dt = max(1e-6, now2 - last_flow_time)
                dcount = max(0, count - last_flow_count)
                pps = dcount / dt  # pulses per second
                lpm = (pps / FLOW_K) * 60.0
                payload["flow"] = {
                    "pulses_total": count,
                    "pps": round(pps, 3),
                    "l_min": round(lpm, 4),
                    "k_pulses_per_l": FLOW_K
                }
                last_flow_count = count
                last_flow_time = now2
            except Exception:
                payload["flow"] = {"status": "error"}

        # Emit JSON line
        try:
            print(json.dumps(payload))
        except Exception:
            # As a fallback, avoid crashing on unexpected values
            try:
                payload["status"] = "encode_error"
                print(str(payload))
            except Exception:
                print('{"type":"env","status":"fatal_encode_error"}')

    # Small sleep to reduce CPU; not tied to sample rate
    time.sleep(0.005)

