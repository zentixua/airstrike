#!/usr/bin/env python3
"""Боевой клиент со всей сборкой хоста — без Prism и без окна на рабочем столе.

Копирует из инстанса mods/ (кроме Airstrike), config/, options.txt, shaderpacks/, resourcepacks/ и, по желанию, один
мир в отдельный каталог; кладёт тестовую сборку мода со сценариями (./gradlew scenarioJar) и запускает Minecraft так,
как его запускает Prism: те же библиотеки из каталога Prism и ForgeWrapper для NeoForge (meta/*.json). Клиент идёт во
вложенном KWin (tools/nested_kwin.sh). Инстанс, его миры и настройки не меняются.

  tools/prod_client.py <сценарий> [--world "New World (7)"] [--dir mod/run/prod] [--prop airstrike.frametimes=true]
  tools/prod_client.py --world "New World (7)" --quickplay [--without-airstrike | --airstrike-jar F] --seconds 180   (вход, A/B, готовый jar)
  tools/prod_client.py commands --no-copy --dir mod/run/film/instance/minecraft --world greenfield-film \
      --prop "airstrike.commands=dh pregen status;chunky"   (готовая копия без перекопирования: проверить моды командами)
      в airstrike.commands через «;»: команды без «/», wait:N — ещё N тиков, 0…72000 (к паузе 40 после команды и 20
      после снимка; другое N — строка в лог и шаг пропущен), shot:имя — снимок screenshots/имя_тик.png; в конце «SCENARIO done» и выход
  tools/prod_client.py --world /путь/к/миру --dir D --copy-only   (только копия: поправить её настройки до запуска --no-copy)
  tools/prod_client.py commands --no-copy --dir D --world мир --size 1920x1080 --video D/take.mkv --prop "airstrike.commands=…"
      (окно игры — в видео в реальном времени, tools/x11_record.py; звук — D/audio.wav, 48 кГц)
  → <dir>/logs/latest.log, <dir>/screenshots/, <dir>/crash-reports/

Сценарий — как у tools/client_scenario.sh (свойство airstrike.scenario); с --world сценарий получает имя мира
в свойстве airstrike.world и открывает его вместо нового. --world — имя мира игрока или путь к каталогу мира (копируется
под своим именем каталога).
"""
import argparse
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import uuid
import hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402


def maven_path(name):
    """group:artifact:version[:classifier][@ext] → путь в каталоге библиотек."""
    ext = "jar"
    if "@" in name:
        name, ext = name.split("@", 1)
    parts = name.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return os.path.join(*group.split("."), artifact, version, f"{artifact}-{version}{classifier}.{ext}")


def allowed(lib):
    """Правила библиотеки Prism/Mojang для Linux: без правил — да, иначе последнее подходящее."""
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        os_name = r.get("os", {}).get("name")
        if os_name is None or os_name == "linux":
            ok = r["action"] == "allow"
    return ok


def offline_uuid(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = h[6] & 0x0F | 0x30
    h[8] = h[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


def copy_instance(dest, world):
    mc = paths.MC
    os.makedirs(dest, exist_ok=True)
    for d in ("mods", "config", "shaderpacks", "resourcepacks", "saves", "logs", "screenshots", "crash-reports"):
        shutil.rmtree(os.path.join(dest, d), ignore_errors=True)
    shutil.copytree(os.path.join(mc, "mods"), os.path.join(dest, "mods"),
                    ignore=lambda d, names: [n for n in names if n.startswith("airstrike-") or not (n.endswith(".jar") or os.path.isdir(os.path.join(d, n)))])
    for d in ("config", "shaderpacks", "resourcepacks"):
        if os.path.isdir(os.path.join(mc, d)):
            shutil.copytree(os.path.join(mc, d), os.path.join(dest, d))
    # клиент без окна: без паузы при потере фокуса и без экрана приветствия (ключи заменяются — повтор ломает загрузку)
    override = {"pauseOnLostFocus": "false", "onboardAccessibility": "false"}
    with open(os.path.join(mc, "options.txt")) as f:
        lines = [ln.rstrip("\n") for ln in f if ln.split(":", 1)[0] not in override]
    with open(os.path.join(dest, "options.txt"), "w") as f:
        f.write("\n".join(lines + [f"{k}:{v}" for k, v in override.items()]) + "\n")
    if world:
        src = world if os.sep in world else os.path.join(mc, "saves", world)
        shutil.copytree(src, os.path.join(dest, "saves", world_name(world)))


def world_name(world):
    """Имя мира в копии: --world — имя мира игрока или путь к каталогу мира."""
    return os.path.basename(os.path.normpath(world))


def launch_args(dest, props, username, game_extra, extra_jvm=()):
    prism = paths.PRISM
    libs = os.path.join(prism, "libraries")
    meta = lambda uid, v: json.load(open(os.path.join(prism, "meta", uid, f"{v}.json")))
    pack = json.load(open(os.path.join(os.path.dirname(paths.MC), "mmc-pack.json")))
    versions = {c["uid"]: c["version"] for c in pack["components"]}
    lwjgl, mcm, neo = meta("org.lwjgl3", versions["org.lwjgl3"]), meta("net.minecraft", versions["net.minecraft"]), meta("net.neoforged", versions["net.neoforged"])

    cp, seen = [], set()
    for lib in neo["libraries"] + mcm["libraries"] + lwjgl["libraries"]:
        if not allowed(lib):
            continue
        key = lib["name"].rsplit(":", 1)[0] if lib["name"].count(":") == 2 else lib["name"]
        if key in seen:
            continue
        seen.add(key)
        p = os.path.join(libs, maven_path(lib["name"]))
        if not os.path.isfile(p):
            sys.exit(f"нет библиотеки {p} — запусти инстанс один раз из Prism")
        cp.append(p)
    client_jar = os.path.join(libs, maven_path(mcm["mainJar"]["name"]))
    cp.append(client_jar)
    installer = next(os.path.join(libs, maven_path(f["name"])) for f in neo["mavenFiles"] if f["name"].endswith(":installer"))

    subst = {
        "auth_player_name": username, "version_name": versions["net.minecraft"], "game_directory": dest,
        "assets_root": os.path.join(prism, "assets"), "assets_index_name": mcm["assetIndex"]["id"],
        "auth_uuid": offline_uuid(username), "auth_access_token": "0", "user_type": "legacy", "version_type": "release",
    }
    game = []
    for a in neo["minecraftArguments"].split():
        for k, v in subst.items():
            a = a.replace("${" + k + "}", v)
        game.append(a)

    jvm = ["-Xms512m", "-Xmx8196m", "-Duser.language=en",
           f"-Dforgewrapper.librariesDir={libs}", f"-Dforgewrapper.installer={installer}", f"-Dforgewrapper.minecraft={client_jar}",
           f"-Xlog:gc:file={os.path.join(dest, 'logs', 'gc.log')}:time,uptime"]
    jvm += [f"-D{k}={v}" for k, v in props.items()]
    jvm += mcm.get("+jvmArgs", [])
    # свои аргументы — последними: из двух -Xmx JVM берёт последний
    jvm += list(extra_jvm)
    return jvm + ["-cp", os.pathsep.join(cp), neo["mainClass"]] + game + game_extra


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("scenario", nargs="?", help="сценарий (airstrike.scenario); без него клиент просто идёт в меню или в --quickplay")
    ap.add_argument("--world", help="скопировать этот мир игрока; со сценарием — открыть его")
    ap.add_argument("--quickplay", action="store_true", help="сразу войти в --world средствами игры (quickPlaySingleplayer)")
    ap.add_argument("--without-airstrike", action="store_true", help="сборка хоста без Airstrike (сравнение A/B)")
    ap.add_argument("--airstrike-jar", help="готовый jar мода (сборка CI или релиза) вместо тестовой сборки; без сценариев")
    ap.add_argument("--prop", action="append", default=[], metavar="KEY=VALUE", help="свойство JVM, например airstrike.frametimes=true")
    ap.add_argument("--jvm", action="append", default=[], metavar="ARG",
                    help="аргумент JVM поверх обычных, например --jvm=-Xmx12G --jvm=-XX:+UseZGC --jvm=-XX:+ZGenerational")
    ap.add_argument("--seconds", type=int, help="закрыть клиент через столько секунд (код выхода 143)")
    ap.add_argument("--dir", default=os.path.join(paths.MOD, "run", "prod"))
    ap.add_argument("--no-copy", action="store_true",
                    help="не копировать инстанс: запустить уже готовый каталог --dir (например копию для съёмки mod/run/film/instance/minecraft)")
    ap.add_argument("--user", default="Dev")
    ap.add_argument("--copy-only", action="store_true", help="только скопировать инстанс (и --world) в --dir и выйти")
    ap.add_argument("--size", default="1280x720", metavar="WxH", help="экран вложенного KWin")
    ap.add_argument("--video", metavar="FILE.mkv",
                    help="записать окно игры в реальном времени (tools/x11_record.py: куски FILE.NNN.mkv, отметки FILE.jsonl)")
    a = ap.parse_args()
    if (a.without_airstrike or a.airstrike_jar) and a.scenario:
        ap.error("сценарии идут из тестовой сборки Airstrike: без неё сценария нет")
    if a.quickplay and not a.world:
        ap.error("--quickplay нужен --world")
    if a.copy_only and a.no_copy:
        ap.error("--copy-only и --no-copy вместе не имеют смысла")
    width, _, height = a.size.partition("x")
    if not (width.isdigit() and height.isdigit()):
        ap.error("--size: ширина x высота, например 1920x1080")
    dest = os.path.abspath(a.dir)

    if a.no_copy:
        if not os.path.isdir(os.path.join(dest, "mods")):
            ap.error(f"--no-copy: в {dest} нет mods/")
        # прошлый запуск мог оставить свою сборку мода — ровно одна, та, что кладётся ниже
        for old in glob.glob(os.path.join(dest, "mods", "airstrike-*.jar")):
            os.remove(old)
    else:
        copy_instance(dest, a.world)
        if a.copy_only:
            return
    if a.airstrike_jar:
        shutil.copy2(a.airstrike_jar, os.path.join(dest, "mods"))
    elif not a.without_airstrike:
        subprocess.run([os.path.join(paths.MOD, "gradlew"), "-p", paths.MOD, "scenarioJar", "-q", "--console=plain"], check=True)
        jar = max(glob.glob(os.path.join(paths.MOD, "build", "scenario-libs", "airstrike-*-scenario.jar")), key=os.path.getmtime)
        shutil.copy2(jar, os.path.join(dest, "mods"))
    os.makedirs(os.path.join(dest, "logs"), exist_ok=True)

    props = dict(p.split("=", 1) for p in a.prop)
    if a.scenario:
        props["airstrike.scenario"] = a.scenario
        if a.world and not a.quickplay:
            props["airstrike.world"] = world_name(a.world)
    game_extra = ["--quickPlaySingleplayer", world_name(a.world)] if a.quickplay else []

    java = os.path.join(os.environ.get("JAVA_HOME") or paths.JAVA, "bin", "java")
    argfile = os.path.join(dest, "launch.args")
    with open(argfile, "w") as f:
        for arg in launch_args(dest, props, a.user, game_extra, a.jvm):
            f.write('"' + arg.replace("\\", "\\\\").replace('"', '\\"') + '"\n')
    # звук — как у client_scenario.sh: драйвер OpenAL Soft «wave» пишет всё, что слышит клиент, в <dest>/audio.wav
    # (звуковой сервер хоста клиенту закрыт nested_kwin.sh, так что только в файл)
    # для видео — 48 кГц и без прошлого audio.wav: x11_record.py отмечает, когда файл появился
    audio = os.path.join(dest, "audio.wav")
    alsoft = os.path.join(dest, "alsoft.conf")
    with open(alsoft, "w") as f:
        f.write(f"[general]\ndrivers = wave\nfrequency = {48000 if a.video else 22050}\nchannels = stereo\nsample-type = int16\n"
                f"[wave]\nfile = {audio}\n")
    tools = os.path.dirname(os.path.abspath(__file__))
    game = f"\"{java}\" @\"{argfile}\""
    if a.video:
        if os.path.exists(audio):
            os.remove(audio)
        game = f"python3 \"{os.path.join(tools, 'x11_record.py')}\" --out \"{os.path.abspath(a.video)}\" --audio \"{audio}\" -- {game}"
    socket = "wayland-airstrike-prod-" + os.path.basename(dest)
    cmd = f"sh -c 'cd \"{dest}\" && exec {game}'"
    sys.exit(run_in_group([os.path.join(tools, "nested_kwin.sh"), socket, width, height, cmd],
                          {**os.environ, "ALSOFT_CONF": alsoft}, a.seconds))


def run_in_group(argv, env, seconds):
    """Запуск в своей сессии; по сроку, Ctrl+C, обрыву терминала и внешнему TERM гасится вся группа.

    Клиент — внук nested_kwin.sh (dbus-run-session → kwin → sh → java): TERM одной обёртке оставлял его работать
    дальше, одновременно со следующей проверкой. Гасим группу: TERM, через 20 с — KILL оставшимся (как rig_trap в
    tools/rig_procs.sh). Код выхода: как у процесса; по сроку — 143; по сигналу — 128 + его номер.
    """
    for sig in STOP_SIGNALS:
        signal.signal(sig, _raise_stop)
    proc, code = None, None
    try:
        proc = subprocess.Popen(argv, env=env, start_new_session=True)
        code = proc.wait(timeout=seconds)
        if code < 0:
            code = 128 - code
    except subprocess.TimeoutExpired:
        code = 128 + signal.SIGTERM
    except Stop as e:
        code = 128 + e.signum
    finally:
        for sig in STOP_SIGNALS:
            signal.signal(sig, signal.SIG_IGN)  # повторный Ctrl+C не обрывает уборку
        if proc is not None:
            stop_group(proc)
    return code


STOP_SIGNALS = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)


class Stop(Exception):
    def __init__(self, signum):
        super().__init__(signum)
        self.signum = signum


def _raise_stop(signum, frame):
    raise Stop(signum)


def group_alive(pgid):
    """Есть ли в группе живой процесс: по /proc/*/stat, зомби (Z) не в счёт — лидер остаётся зомби до wait()."""
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/stat") as f:
                stat = f.read()
        except OSError:
            continue
        fields = stat[stat.rindex(")") + 2:].split()  # имя процесса в скобках может содержать пробелы
        if fields[0] != "Z" and int(fields[2]) == pgid:
            return True
    return False


def stop_group(proc):
    """TERM всей группе процесса, через 20 с — KILL оставшимся; лидер пожинается в конце, чтобы номер группы не ушёл."""
    for sig, wait in ((signal.SIGTERM, 20), (signal.SIGKILL, 5)):
        if not group_alive(proc.pid):
            break
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            break
        deadline = time.monotonic() + wait
        while group_alive(proc.pid) and time.monotonic() < deadline:
            time.sleep(0.5)
    proc.wait()


if __name__ == "__main__":
    main()
