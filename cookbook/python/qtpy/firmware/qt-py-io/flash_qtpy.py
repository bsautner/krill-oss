#!/usr/bin/env python3
"""
Flash script for Adafruit Qt Py CircuitPython firmware
Copies the CircuitPython code to the CIRCUITPY drive when Qt Py is in bootloader mode
"""

import os
import shutil
import sys
import time
from pathlib import Path


def find_circuitpy_drive():
    """Find the CIRCUITPY drive mount point"""
    # Common mount points on Linux
    possible_paths = [
        "/media/CIRCUITPY",
        "/mnt/CIRCUITPY",
        "/run/media/*/CIRCUITPY",
        "/media/*/CIRCUITPY"
    ]

    for path_pattern in possible_paths:
        if "*" in path_pattern:
            # Handle wildcard paths
            import glob
            matches = glob.glob(path_pattern)
            for match in matches:
                if os.path.exists(match):
                    return match
        else:
            if os.path.exists(path_pattern):
                return path_pattern

    # Manual check of /media and /mnt directories
    for base_dir in ["/media", "/mnt"]:
        if os.path.exists(base_dir):
            for item in os.listdir(base_dir):
                full_path = os.path.join(base_dir, item)
                if os.path.isdir(full_path) and "CIRCUITPY" in item.upper():
                    return full_path

    return None

def wait_for_circuitpy():
    """Wait for CIRCUITPY drive to appear"""
    print("Waiting for CIRCUITPY drive to appear...")
    print("Make sure your Qt Py is connected and in normal mode (not bootloader)")

    while True:
        drive = find_circuitpy_drive()
        if drive:
            print(f"Found CIRCUITPY drive at: {drive}")
            return drive

        print("CIRCUITPY drive not found. Retrying in 2 seconds...")
        print("If needed, double-tap the RESET button on your Qt Py to enter bootloader mode")
        time.sleep(2)

def copy_firmware():
    """Copy the firmware files to CIRCUITPY drive"""
    # Find CIRCUITPY drive
    circuitpy_path = wait_for_circuitpy()

    # Source firmware file
    firmware_source = Path(__file__).parent / "firmware" / "flash.py"

    if not firmware_source.exists():
        print(f"Error: Firmware file not found at {firmware_source}")
        return False

    # Destination path (rename to code.py for CircuitPython autorun)
    code_dest = os.path.join(circuitpy_path, "code.py")

    try:
        print(f"Copying {firmware_source} to {code_dest}")
        shutil.copy2(firmware_source, code_dest)
        print("Firmware flashed successfully!")

        # Check if lib directory exists, create if needed
        lib_dir = os.path.join(circuitpy_path, "lib")
        if not os.path.exists(lib_dir):
            print("Note: You may need to install required CircuitPython libraries in /lib/")
            print("Required libraries:")
            print("  - adafruit_bus_device")
            print("  - adafruit_register")
            print("  - adafruit_sht31d.mpy")
            print("  - adafruit_sht4x.mpy")
            print("  - adafruit_onewire")
            print("  - adafruit_ds18x20.mpy")

        return True

    except Exception as e:
        print(f"Error copying firmware: {e}")
        return False

def main():
    """Main flashing function"""
    print("Adafruit Qt Py Firmware Flash Tool")
    print("=" * 40)

    if copy_firmware():
        print("\nFlashing completed successfully!")
        print("Your Qt Py should restart and begin running the sensor code.")
    else:
        print("\nFlashing failed!")
        return 1

    return 0

if __name__ == "__main__":
    sys.exit(main())
