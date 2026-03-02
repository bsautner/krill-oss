#!/usr/bin/env python3
"""
Krill Python Lambda - Temperature Conversion Example

This example demonstrates:
- Reading numeric input from a DataPoint
- Performing a calculation (Celsius to Fahrenheit)
- Returning the result to a DataSource

Usage:
- Connect a temperature sensor DataPoint (in Celsius) as the source
- Configure a DataSource to store the Fahrenheit value as the target
"""

import sys
import json

def celsius_to_fahrenheit(celsius):
    """Convert Celsius to Fahrenheit"""
    return (celsius * 9/5) + 32

def main():
    if len(sys.argv) < 2:
        print("ERROR: No input provided", file=sys.stderr)
        sys.exit(1)

    try:
        # Parse input as float (temperature in Celsius)
        celsius = float(sys.argv[1])

        # Convert to Fahrenheit
        fahrenheit = celsius_to_fahrenheit(celsius)

        # Output the result (rounded to 2 decimal places)
        print(f"{fahrenheit:.2f}")

    except ValueError:
        print(f"ERROR: Invalid number format: {sys.argv[1]}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

