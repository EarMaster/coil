#!/usr/bin/env python3
"""
Phoniebox v3 - connection probe

Checks against a running box:
  1. REQ/REP  (the way the web UI does it)
  2. DEALER   (with an empty delimiter frame - the approach planned for the app)
  3. Pipelining over DEALER (several outstanding requests at once)
  4. PubSub - records playerstatus and dumps the real field schema

Requires:  pip install pyzmq
Usage:     python3 probe_phoniebox.py phoniebox.local
"""

import json
import sys
import time
import uuid

import zmq

RPC_PORT = 5555
PUB_PORT = 5558
TIMEOUT_MS = 4000


def _payload(package, plugin, method=None, args=None, kwargs=None, as_thread=False):
    msg = {"id": str(uuid.uuid4()), "package": package, "plugin": plugin}
    if method:
        msg["method"] = method
    if args:
        msg["args"] = args
    if kwargs:
        msg["kwargs"] = kwargs
    if as_thread:
        msg["as_thread"] = True
    msg["tsp"] = time.time_ns()
    return msg


def _ok(text):
    print(f"  \033[32mOK\033[0m   {text}")


def _fail(text):
    print(f"  \033[31mFAIL\033[0m {text}")


# ---------------------------------------------------------------- Test 1: REQ

def test_req(ctx, host):
    print("\n[1] REQ/REP - reference behaviour, as used by the web UI")
    sock = ctx.socket(zmq.REQ)
    sock.setsockopt(zmq.LINGER, 0)
    sock.setsockopt(zmq.RCVTIMEO, TIMEOUT_MS)
    sock.connect(f"tcp://{host}:{RPC_PORT}")

    req = _payload("misc", "get_all_loaded_packages")
    sock.send_string(json.dumps(req))
    try:
        reply = json.loads(sock.recv())
    except zmq.Again:
        _fail("Timeout - is the box running? Is port 5555 reachable?")
        sock.close()
        return False

    if reply.get("id") != req["id"]:
        _fail(f"ID mismatch: {reply}")
        sock.close()
        return False

    _ok(f"Reply received, processing time "
        f"{reply.get('total_processing_time', -1):.2f} ms")
    sock.close()
    return True


# ------------------------------------------------------------- Test 2: DEALER

def test_dealer(ctx, host):
    print("\n[2] DEALER - with empty delimiter frame")
    sock = ctx.socket(zmq.DEALER)
    sock.setsockopt(zmq.LINGER, 0)
    sock.setsockopt(zmq.RCVTIMEO, TIMEOUT_MS)
    sock.connect(f"tcp://{host}:{PUB_PORT - 3}")  # 5555

    # player.ctrl.playerstatus, not core.version: `core.*` are published topics only, so
    # asking for that package over RPC answers with an error.
    req = _payload("player", "ctrl", "playerstatus")
    # The decisive part: an empty frame BEFORE the payload.
    sock.send(b"", zmq.SNDMORE)
    sock.send_string(json.dumps(req))

    try:
        frames = sock.recv_multipart()
    except zmq.Again:
        _fail("Timeout - the server does not accept the delimiter framing.")
        _fail("=> Plan for the REQ-with-global-mutex fallback.")
        sock.close()
        return False

    # Expected: [b'', b'{...}']
    if len(frames) != 2 or frames[0] != b"":
        _fail(f"Unexpected frame structure: {[f[:40] for f in frames]}")
        sock.close()
        return False

    reply = json.loads(frames[1])
    if reply.get("id") != req["id"]:
        _fail(f"ID mismatch: {reply}")
        sock.close()
        return False

    _ok(f"Delimiter framing accepted. playerstatus returned {type(reply.get('result')).__name__}")
    sock.close()
    return True


# ---------------------------------------------------------- Test 3: Pipelining

def test_pipelining(ctx, host):
    print("\n[3] DEALER - 5 requests outstanding at once")
    sock = ctx.socket(zmq.DEALER)
    sock.setsockopt(zmq.LINGER, 0)
    sock.setsockopt(zmq.RCVTIMEO, TIMEOUT_MS)
    sock.connect(f"tcp://{host}:{RPC_PORT}")

    sent = {}
    for _ in range(5):
        req = _payload("volume", "ctrl", "get_volume")
        sent[req["id"]] = req
        sock.send(b"", zmq.SNDMORE)
        sock.send_string(json.dumps(req))

    received = 0
    started = time.time()
    while received < 5 and time.time() - started < 10:
        try:
            frames = sock.recv_multipart()
        except zmq.Again:
            break
        reply = json.loads(frames[-1])
        if reply.get("id") in sent:
            received += 1

    sock.close()
    if received == 5:
        _ok("All 5 replies correlated correctly - pipelining holds.")
        return True
    _fail(f"Only {received}/5 replies. Serialise requests.")
    return False


# ------------------------------------------------------------- Test 4: PubSub

def test_pubsub(ctx, host, seconds=20):
    print(f"\n[4] PubSub - listening {seconds}s on port {PUB_PORT}")
    print("    -> Please start something on the box now (place a card, or use the web UI)\n")

    sock = ctx.socket(zmq.SUB)
    sock.setsockopt(zmq.LINGER, 0)
    sock.setsockopt(zmq.RCVTIMEO, 1000)
    # "core." is a prefix: the daemon publishes core.git_state and core.started_at, but
    # no core.version, whatever the older notes claimed.
    for topic in ("playerstatus", "volume.level", "core.",
                  "rfid.card_id", "host.temperature.cpu"):
        sock.setsockopt_string(zmq.SUBSCRIBE, topic)
    sock.connect(f"tcp://{host}:{PUB_PORT}")

    seen_topics = {}
    player_fields = {}
    started = time.time()

    while time.time() - started < seconds:
        try:
            topic_raw, payload_raw = sock.recv_multipart()
        except zmq.Again:
            continue
        except ValueError:
            continue

        topic = topic_raw.decode()
        seen_topics[topic] = seen_topics.get(topic, 0) + 1

        try:
            data = json.loads(payload_raw.decode())
        except json.JSONDecodeError:
            continue

        if topic == "playerstatus" and isinstance(data, dict):
            for key, value in data.items():
                player_fields.setdefault(key, type(value).__name__)

    sock.close()

    if not seen_topics:
        _fail("No messages received. Is port 5558 blocked?")
        return False

    _ok("Topics received:")
    for topic, count in sorted(seen_topics.items()):
        print(f"       {topic:32s} {count}x")

    if player_fields:
        print("\n  playerstatus - real field schema (for the Kotlin data model):")
        for key, kind in sorted(player_fields.items()):
            print(f"       {key:24s} {kind}")
    else:
        print("\n  No playerstatus seen - was anything playing during the test?")

    return True


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    host = sys.argv[1]
    print(f"Phoniebox probe against {host}")
    print("=" * 55)

    ctx = zmq.Context()
    results = {
        "REQ/REP": test_req(ctx, host),
        "DEALER": test_dealer(ctx, host),
    }
    if results["DEALER"]:
        results["Pipelining"] = test_pipelining(ctx, host)
    results["PubSub"] = test_pubsub(ctx, host)

    print("\n" + "=" * 55)
    print("Result:")
    for name, passed in results.items():
        print(f"  {name:14s} {'passed' if passed else 'FAILED'}")

    if results.get("DEALER"):
        print("\n=> DEALER approach confirmed. Continue with the Kotlin spike.")
    else:
        print("\n=> Fall back to REQ with a global mutex (see plan, risk 1).")

    ctx.term()


if __name__ == "__main__":
    main()
