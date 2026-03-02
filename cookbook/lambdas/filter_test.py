# This script is intended to test krill servers filters and triggers.
# It won't take any input
# when ran it will use the current time in seconds to and add a random double value to it and print it.
# So if the current second is 30 the output would be for example 30.42  where 42 is a random double generated between 0 and 99
#
# if the current second mod 10 is 0 (10, 20, 30 etc) you are to generate random noise that is a random number > 60 or < 0 randomly choosing to return a out of range number higher or lower.
# do not print anything to the console except the double value so it can be converted directly to a double

import random
from datetime import datetime

def main():
    current_second = datetime.now().second

    # Check if we should generate out-of-range noise (every 10 seconds: 0, 10, 20, 30, 40, 50)
    if current_second % 10 == 0:
        # Randomly choose to go high (> 60) or low (< 0)
        if random.choice([True, False]):
            # Generate a value > 60 (between 60.01 and 120)
            result = 60.0 + random.uniform(0.01, 60.0)
        else:
            # Generate a value < 0 (between -60 and -0.01)
            result = -random.uniform(0.01, 60.0)
    else:
        # Normal case: current second + random fractional part (0.00 to 0.99)
        fractional = random.uniform(0, 0.99)
        result = current_second + fractional

    print(result)

if __name__ == "__main__":
    main()

