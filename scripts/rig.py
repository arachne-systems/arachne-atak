#!/usr/bin/env python3
"""Talk to the iroh test-rig controller (crates/arachne-rig) instead of adb.

Debug Arachne builds dial the controller by key when ATAK starts, so no adb
serial, IP or port is needed:

    rig.py list
    rig.py HEWN state endpoint=false
    rig.py JOSA join link=@.cache/link.txt member=JOSA
    rig.py all ping                      # every connected device

Values starting with @ are read from a file. Start the controller with
`target/release/arachne-rig` (it listens on 127.0.0.1:47101).
"""
import json
import socket
import sys

API = ('127.0.0.1', 47101)


def request(message, timeout=120):
    with socket.create_connection(API, timeout=timeout) as connection:
        connection.sendall((json.dumps(message) + '\n').encode())
        data = b''
        while not data.endswith(b'\n'):
            chunk = connection.recv(65536)
            if not chunk:
                break
            data += chunk
    return json.loads(data)


def devices():
    return [d['device'].get('callsign') for d in request({'list': True})['devices']]


def command(device, op, pairs, timeout_s=90):
    args = {}
    for pair in pairs:
        key, _, value = pair.partition('=')
        if value.startswith('@'):
            with open(value[1:]) as handle:
                value = handle.read().strip()
        args[key] = value
    return request({'device': device, 'op': op, 'args': args, 'timeout_s': timeout_s}, timeout=timeout_s + 10)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == 'list':
        print(json.dumps(request({'list': True}), indent=2))
        return
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    targets = devices() if sys.argv[1] == 'all' else [sys.argv[1]]
    results = {target: command(target, sys.argv[2], sys.argv[3:]) for target in targets}
    print(json.dumps(results if len(results) > 1 else results[targets[0]], indent=2))


if __name__ == '__main__':
    main()
