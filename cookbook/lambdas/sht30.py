# Krill Server will send input like this:
#
# {
#     "source": "bfd3a4da-4f3e-46d5-bbaf-d30b3336319d",
#     "target": "",
#     "sourceNode": {
#         "id": "bfd3a4da-4f3e-46d5-bbaf-d30b3336319d",
#         "parent": "c1272281-adc4-4405-815f-4c0e8bc2e87c",
#         "host": "eb45684a-64e5-4928-a7cd-ae71265de3f9",
#         "type": {
#             "type": "krill.zone.KrillApp.DataPoint"
#         },
#         "state": "NONE",
#         "meta": {
#             "type": "krill.zone.feature.datapoint.DataPointMetaData",
#             "name": "sht30",
#             "snapshot": {
#                 "timestamp": 1766434887039,
#                 "value": "{\"type\":\"sht30\",\"data\":{\"t_c\":21.84,\"rh\":33.22}}\n{\"type\":\"sht30\",\"data\":{\"t_c\":21.84,\"rh\":33.19}}\n{\"type\":\"sht30\",\"data\":{\"t_c\":21.86,\"rh\":33.12}}\n{\"type\":\"sht30\",\"data\":{\"t_c\":21.85,\"rh\":33.17}}\n{\"type\":\"sht30\",\"data\":{\"t_c\":21.84,\"rh\":33.19}}\n{\"type\":\"sht30\",\"data\":{\"t_c\":21.84,\"rh\":33.18}}\n{\"type\":\"sht30\",\"data\":{\"t_c\":21.85,\"rh\":33.22}}"
#             },
#             "precision": 2,
#             "unit": "",
#             "manualEntry": true,
#             "isNumber": false,
#             "maxAge": 0
#         }
#     },
#     "datapointTags": {
#         "t_c": "bda3270d-3fc6-4c80-b83f-c80b0d15ac70",
#         "rh": "43999442-0b61-4537-836f-3595872970ce"
#     },
#     "filename": "dht30.py",
#     "timestamp": 0
# }
# you'll first download the individial node json using
# GET https://localhost:8042/node/{data_point_id}
# you will parse the data and match data point tags with the data point ids, iterate over the data and compute an average of all incoming values for each point and post the result to
# using POST https://localhost:8042/node/{data_point_id} with the snapshot
# "snapshot": {
#                  "timestamp": 1766434887039,
#                  "value": ""
#   }
# that endpoint expects a complete node json object so you will need to reassemble it with the new snapshot value


import sys
import json
import requests
import time
from typing import Dict, List

def parse_sensor_data(snapshot_value: str) -> Dict[str, List[float]]:
    """Parse the snapshot value containing multiple JSON lines and extract sensor data."""
    lines = snapshot_value.strip().split('\n')
    t_c_values = []
    rh_values = []

    for line in lines:
        try:
            data = json.loads(line)
            if data.get('type') == 'sht30' and 'data' in data:
                sensor_data = data['data']
                if 't_c' in sensor_data:
                    t_c_values.append(float(sensor_data['t_c']))
                if 'rh' in sensor_data:
                    rh_values.append(float(sensor_data['rh']))
        except json.JSONDecodeError:
            print(f"WARNING: Failed to parse line: {line}", file=sys.stderr)
            continue

    return {'t_c': t_c_values, 'rh': rh_values}

def compute_average(values: List[float]) -> float:
    """Compute the average of a list of values."""
    if not values:
        return 0.0
    return sum(values) / len(values)

def post_datapoint(node_id: str, value: str, timestamp: int):
    """Fetch the node, update its snapshot, and post it back."""
    try:
        # Fetch the node
        url = f"http://localhost:8042/node/{node_id}"
        response = requests.get(url, verify=False)

        if response.status_code != 200:
            print(f"ERROR: Failed to fetch node {node_id}: {response.status_code}", file=sys.stderr)
            return False

        node = response.json()

        # Update the snapshot
        if 'meta' not in node:
            node['meta'] = {}

        node['meta']['snapshot'] = {
            'timestamp': timestamp,
            'value': value
        }

        # Post the updated node back
        post_response = requests.post(url, json=node, verify=False)

        if post_response.status_code not in [200, 201]:
            print(f"ERROR: Failed to update node {node_id}: {post_response.status_code}", file=sys.stderr)
            return False

        print(f"Successfully updated {node_id} with value: {value}", file=sys.stderr)
        return True

    except Exception as e:
        print(f"ERROR: Exception updating node {node_id}: {str(e)}", file=sys.stderr)
        return False

def main():
    # Check if input was provided
    if len(sys.argv) < 2:
        print("ERROR: No input provided", file=sys.stderr)
        sys.exit(1)

    # Get the input value from command line argument
    input_value = sys.argv[1]

    try:
        # Parse the input JSON
        input_data = json.loads(input_value)

        # Extract the source node and datapoint tags
        source_node = input_data.get('sourceNode', {})
        meta = source_node.get('meta', {})
        snapshot = meta.get('snapshot', {})
        snapshot_value = snapshot.get('value', '')
        timestamp = snapshot.get('timestamp', 0)
        datapoint_tags = input_data.get('datapointTags', {})

        # Parse the sensor data
        sensor_data = parse_sensor_data(snapshot_value)

        # Compute averages
        t_c_avg = compute_average(sensor_data['t_c'])
        rh_avg = compute_average(sensor_data['rh'])

        # Get the current timestamp (use the one from input or current time)
        current_timestamp = timestamp if timestamp > 0 else int(time.time() * 1000)

        # Post the averaged data to each datapoint
        success = True
        if 't_c' in datapoint_tags:
            t_c_id = datapoint_tags['t_c']
            if not post_datapoint(t_c_id, f"{t_c_avg:.2f}", current_timestamp):
                success = False

        if 'rh' in datapoint_tags:
            rh_id = datapoint_tags['rh']
            if not post_datapoint(rh_id, f"{rh_avg:.2f}", current_timestamp):
                success = False

        if success:
            print(f"SUCCESS: Processed t_c={t_c_avg:.2f}, rh={rh_avg:.2f}")
        else:
            print("ERROR: Some updates failed", file=sys.stderr)
            sys.exit(1)

    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse input JSON: {str(e)}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"ERROR: Unexpected error: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

