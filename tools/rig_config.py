#!/usr/bin/env python3
"""Конфиг выделенного сервера проверок (tools/stress.sh, tools/mp_scenario.sh): без объявления в LAN.

NeoForge по умолчанию объявляет выделенный сервер в локальной сети (advertiseDedicatedServerToLan в
config/neoforge-server.toml): LanServerPinger держит UDP-сокет на 0.0.0.0 и раз в 1,5 с шлёт MOTD и порт на
224.0.2.60:4445. Серверу проверок сеть не нужна. Файл правится по месту (остальные ключи — как их записал NeoForge),
нет файла — пишется один ключ, остальное NeoForge допишет сам. Сторож devtest LoopbackGuard роняет сервер, если
объявление включено.
"""
import pathlib
import re
import sys

KEY = "advertiseDedicatedServerToLan"

cfg = pathlib.Path(sys.argv[1]) / "config" / "neoforge-server.toml"
cfg.parent.mkdir(parents=True, exist_ok=True)
text = cfg.read_text(encoding="utf-8") if cfg.exists() else ""
line = re.compile(rf"^(\s*{KEY}\s*=\s*).*$", re.M)
if line.search(text):
    text = line.sub(r"\1false", text)
else:
    # файл без перевода строки в конце: ключ — с новой строки, иначе он склеится с последней (TOML не прочтётся)
    text += ("" if text.endswith("\n") or not text else "\n") + f"{KEY} = false\n"
cfg.write_text(text, encoding="utf-8")
