"""Скачивает с Modrinth моды для запусков из Gradle (Create, Sable, Aeronautics, Lithium) — для CI и машин без инстанса.

Версии — как в сборке «Incoming Pack» (zentixua/incoming-pack, pack/mods); файлы проверяются по sha512 из Modrinth.
Потом: ./gradlew runGameTestServer -PmcModsDir=<папка> [-PwithLithium]
(Lithium идёт в запуск только с -PwithLithium — проверка совместимости с ним, как у хоста.)
Папка — только для этих модов: другие jar в ней (прежние версии) убираются, иначе запуск взял бы обе версии мода.

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
    "sable": "2.0.6+mc1.21.1",
    "create-aeronautics": "1.3.2+mc1.21.1",
    "lithium": "mc1.21.1-0.15.4-neoforge",
}
API = "https://api.modrinth.com/v2"
HEADERS = {"User-Agent": "zentixua/incoming (github.com/zentixua/incoming)"}


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
    pinned = set()
    for project, number in PINNED.items():
        f = version_file(project, number)
        pinned.add(f["filename"])
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
    for name in sorted(os.listdir(out)):
        if name.endswith(".jar") and name not in pinned:
            os.remove(os.path.join(out, name))
            print(f"убран   {name}")
    print(out)


if __name__ == "__main__":
    main()
