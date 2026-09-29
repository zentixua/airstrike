#!/usr/bin/env python3
"""Свободный TCP-порт на петле (127.0.0.1) для серверов проверок: tools/stress.sh, tools/mp_scenario.sh."""
import socket

with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    print(s.getsockname()[1])
