"""Minimaler RCON-Client fuer den Server-Smoke-Test: python3 rcon.py "befehl 1" "befehl 2" ..."""
import socket
import struct
import sys


def packet(request_id, kind, body):
    data = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    length = struct.unpack("<i", sock.recv(4))[0]
    data = b""
    while len(data) < length:
        data += sock.recv(length - len(data))
    request_id, kind = struct.unpack("<ii", data[:8])
    return request_id, data[8:-2].decode("utf-8", "replace")


with socket.create_connection(("127.0.0.1", 25575), timeout=30) as sock:
    sock.sendall(packet(1, 3, "smoketest"))
    if read(sock)[0] == -1:
        sys.exit("RCON-Login fehlgeschlagen")
    for i, command in enumerate(sys.argv[1:], start=2):
        sock.sendall(packet(i, 2, command))
        print(f"> {command}\n{read(sock)[1]}")
