"""Скачивает с Modrinth моды для запусков из Gradle (Create, Sable, Aeronautics) — для CI и машин без инстанса.

Версии совпадают со сборкой «All of Create Aeronautics»; файлы проверяются по sha512 из Modrinth.
Потом: ./gradlew runGameTestServer -PmcModsDir=<папка>

    python3 tools/fetch_runtime_mods.py [папка]   (по умолчанию mod/run/ci-mods)
"""
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

from paths import MOD

MC_VERSION = "1.21.1"
# проект Modrinth → номер версии
PINNED = {
    "create": "6.0.10+mc1.21.1",
    "sable": "2.0.5+mc1.21.1",
    "create-aeronautics": "1.3.2+mc1.21.1",
}
API = "https://api.modrinth.com/v2"
HEADERS = {"User-Agent": "zentixua/airstrike (github.com/zentixua/airstrike)"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60) as r:
        return r.read()


def version_file(project, number):
    query = urllib.parse.urlencode({"loaders": '["neoforge"]', "game_versions": f'["{MC_VERSION}"]'})
    versions = json.loads(get(f"{API}/project/{project}/version?{query}"))
    for v in versions:
        if v["version_number"] == number:
            return next((f for f in v["files"] if f["primary"]), v["files"][0])
    raise SystemExit(f"{project} {number} нет на Modrinth")


def main():
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(MOD, "run", "ci-mods"))
    os.makedirs(out, exist_ok=True)
    for project, number in PINNED.items():
        f = version_file(project, number)
        path = os.path.join(out, f["filename"])
        sha = f["hashes"]["sha512"]
        if os.path.exists(path) and hashlib.sha512(open(path, "rb").read()).hexdigest() == sha:
            print(f"есть    {f['filename']}")
            continue
        data = get(f["url"])
        if hashlib.sha512(data).hexdigest() != sha:
            raise SystemExit(f"{f['filename']}: sha512 не совпал")
        with open(path, "wb") as fh:
            fh.write(data)
        print(f"скачан  {f['filename']} ({len(data) // 1024} КБ)")
    print(out)


if __name__ == "__main__":
    main()
