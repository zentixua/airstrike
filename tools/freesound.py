#!/usr/bin/env python3
"""Оригиналы записей Freesound — файлы без потерь, как их загрузил автор, — для tools/build_sounds.py.

Превью Freesound — ogg с потерями; оригинал отдаётся только после входа в Freesound (OAuth2, API v2). Один раз:

  1. https://freesound.org/apiv2/apply — ключ API (Client id и Client secret);
  2. FREESOUND_CLIENT_ID=… FREESOUND_CLIENT_SECRET=… python3 tools/freesound.py login
     — печатает адрес входа; после входа Freesound показывает код, его вставить сюда.

Вход хранится вне репозитория: ~/.config/airstrike/freesound.json (или файл из FREESOUND_AUTH), токен живёт сутки
и обновляется сам, если заданы FREESOUND_CLIENT_ID и FREESOUND_CLIENT_SECRET. Скачанные оригиналы лежат
в tools/.sound-cache/orig/<id>.<тип>; когда оригинал уже скачан, вход не нужен.

  python3 tools/freesound.py fetch 251401 475780   — скачать оригиналы (для проверки и разбора записей)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://freesound.org/apiv2/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORIG = os.path.join(ROOT, "tools", ".sound-cache", "orig")
AUTH = os.environ.get("FREESOUND_AUTH") or os.path.expanduser("~/.config/airstrike/freesound.json")


def _client():
    cid, secret = os.environ.get("FREESOUND_CLIENT_ID"), os.environ.get("FREESOUND_CLIENT_SECRET")
    if not cid or not secret:
        sys.exit("нужны FREESOUND_CLIENT_ID и FREESOUND_CLIENT_SECRET (ключ API: https://freesound.org/apiv2/apply)")
    return cid, secret


def _grant(**fields):
    cid, secret = _client()
    body = urllib.parse.urlencode(dict(client_id=cid, client_secret=secret, **fields)).encode()
    with urllib.request.urlopen(API + "oauth2/access_token/", body) as r:
        tok = json.load(r)
    tok["expires_at"] = time.time() + tok["expires_in"] - 60
    os.makedirs(os.path.dirname(AUTH), exist_ok=True)
    fd = os.open(AUTH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump(tok, fh)
    return tok


def login():
    cid, _ = _client()
    print("Войдите в Freesound и разрешите доступ:")
    print(f"  {API}oauth2/authorize/?client_id={cid}&response_type=code")
    code = input("код со страницы Freesound: ").strip()
    _grant(grant_type="authorization_code", code=code)
    print("готово, вход сохранён в", AUTH)


def _token():
    if not os.path.exists(AUTH):
        sys.exit("нет входа в Freesound: python3 tools/freesound.py login (см. tools/freesound.py)")
    with open(AUTH) as fh:
        tok = json.load(fh)
    if time.time() > tok.get("expires_at", 0):
        tok = _grant(grant_type="refresh_token", refresh_token=tok["refresh_token"])
    return tok["access_token"]


def _get(path, token):
    """Запрос к API; на «слишком часто» (429) Freesound просит подождать — ждём и повторяем."""
    req = urllib.request.Request(API + path, headers={"Authorization": "Bearer " + token})
    for attempt in range(8):
        try:
            return urllib.request.urlopen(req)
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 7:
                raise
            wait = int(e.headers.get("Retry-After") or 60)
            print(f"  Freesound просит подождать {wait} с")
            time.sleep(wait)


def original(fid):
    """Путь к оригиналу записи fid; нет в кэше — скачать (нужен вход)."""
    os.makedirs(ORIG, exist_ok=True)
    for f in os.listdir(ORIG):
        if f.split(".")[0] == str(fid) and not f.endswith(".part"):
            return os.path.join(ORIG, f)
    token = _token()
    with _get(f"sounds/{fid}/?fields=type", token) as r:
        kind = json.load(r)["type"]
    path = os.path.join(ORIG, f"{fid}.{kind}")
    print("  скачиваю оригинал", fid)
    with _get(f"sounds/{fid}/download/", token) as r, open(path + ".part", "wb") as fh:
        while chunk := r.read(1 << 20):
            fh.write(chunk)
    os.replace(path + ".part", path)
    return path


if __name__ == "__main__":
    if sys.argv[1:2] == ["login"]:
        login()
    elif sys.argv[1:2] == ["fetch"]:
        for a in sys.argv[2:]:
            print(original(int(a)))
    else:
        print(__doc__)
