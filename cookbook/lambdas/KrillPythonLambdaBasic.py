#!/usr/bin/env python3
"""
Krill Python Lambda - Basic Echo Example

This is a simple example lambda that demonstrates how to:
1. Read input data passed from a Krill DataPoint
2. Process the data (in this case, just echo it)
3. Return output that will be stored in a Krill DataSource

Usage:
- Configure a Lambda node in Krill
- Set the filename to this script name
- Select a source DataPoint (input)
- Select a target DataSource (output)
- When the source DataPoint receives data, this script runs

The Krill server passes the input value as the first command-line argument
and captures stdout as the output to store in the target DataSource.
"""

import sys

def main():
    # Check if input was provided
    if len(sys.argv) < 2:
        print("ERROR: No input provided", file=sys.stderr)
        sys.exit(1)

    # Get the input value from command line argument
    input_value = sys.argv[1]

    # Echo the input value to stdout
    # The Krill server will capture this and store it in the target DataSource
    print(input_value)

if __name__ == "__main__":
    main()

