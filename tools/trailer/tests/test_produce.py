"""Конвейер Newisle (produce.py) на придуманной машине: часы, место, память, процессы клиента — подделка, миры —
маленькие папки во временном каталоге. Каждое ограждение срабатывает и конвейер после него идёт дальше.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import produce as P  # noqa: E402

LIMITS = {"retries": 2, "stall_seconds": 300, "heap_share": 0.6, "take_factor": 2.0, "night_hours": 9.5, "disk_floor_gb": 4,
          "ram_floor_gb": 3, "client_gb": 10, "pause_minutes": 30, "flights_wait_s": 120}


def takes_json(path):
    data = {"limits": LIMITS, "takes": [
        {"id": "A", "from": "chain", "minutes": 5, "shots": [{"plans": ["a1"], "seconds": 4, "cover": {"a1": {"subject": "scene"}}}]},
        {"id": "B", "from": "chain", "minutes": 5, "artem": {"stand": [0, 0], "look": [1, 1], "hints": ["x"]},
         "shots": [{"plans": ["b1"], "seconds": 4, "cover": {"b1": {"subject": "scene"}}}]},
        {"id": "C", "from": "branch:B", "minutes": 5, "shots": [{"plans": ["c1"], "seconds": 4, "cover": {"c1": {"subject": "scene"}}}]},
        {"id": "D", "from": "chain", "minutes": 5, "shots": [{"plans": ["d1"], "seconds": 4, "cover": {"d1": {"subject": "scene"}}}]},
    ]}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)


class FakeProc:
    def __init__(self, machine, plan, env, log):
        self.m, self.plan, self.env, self.log = machine, plan, env, log
        self.start = machine.t
        self.code = None
        self.killed = False

    def finish(self):
        rec = self.env.get("INCOMING_TRAILER_DIR")
        with open(self.log, "a", encoding="utf-8") as f:
            f.write(self.plan.get("log", "") + "\n")
        if "render" in self.plan:
            os.makedirs(os.path.join(rec, "render"), exist_ok=True)
            with open(os.path.join(rec, "render", "best.txt"), "w", encoding="utf-8") as f:
                for shot in self.env["INCOMING_FILM_SHOTS"].split(","):
                    verdict = self.plan["render"].get(shot, "PASS")
                    f.write(f"{shot} {rec}/render/{shot}/own {verdict}\n")
        elif "result" in self.plan:
            with open(self.env["INCOMING_NEWISLE_RESULT"], "w", encoding="utf-8") as f:
                json.dump(self.plan["result"], f)
        self.code = self.plan.get("code", 0)

    def poll(self):
        if self.code is not None or self.killed:
            return self.code
        if self.plan.get("hang"):
            if self.plan.get("pulse"):
                with open(self.log, "a", encoding="utf-8") as f:
                    f.write("NEWISLE heap 11% (1100 МБ из 10000)\n")
            return None
        if self.m.t - self.start >= self.plan.get("seconds", 60):
            self.finish()
        return self.code

    def kill(self, hard=False):
        self.killed = True
        self.code = -9 if hard else -15


class FakeMachine(P.Machine):
    """Часы идут только сном конвейера; клиент ведёт себя по сценарию теста (ключ — дубль, вид, камера)."""

    def __init__(self, script):
        self.t = 1000.0
        self.script = script
        self.free, self.mem = 100.0, 30.0
        self.game_until = None
        self.client_until = None
        self.starts = []
        self.procs = []
        self.copies = []
        self.cmds = []
        self.head = "abc123def"

    def git_head(self, repo):
        return self.head

    def now(self):
        return self.t

    def sleep(self, s):
        self.t += s

    def disk_free_gb(self, path):
        return self.free

    def mem_available_gb(self):
        return self.mem

    def running(self, patterns):
        if self.game_until is not None and self.t < self.game_until:
            return "4242", "prism"
        return None

    def client_running(self):
        # «градл-демон» клиента жив до client_until: переживает убийство запускателя и ещё дописывает мир
        if self.client_until is not None and self.t < self.client_until:
            return "9", "org.gradle.launcher.daemon.bootstrap.GradleDaemon"
        return None

    def tree_gb(self, path):
        return 1.0

    def copy_tree(self, src, dst):
        self.copies.append((src, dst))
        super().copy_tree(src, dst)

    def newest_mtime(self, paths):
        p = self.procs[-1] if self.procs else None
        if p and p.plan.get("silent"):
            return p.start
        if p and p.plan.get("pulse"):      # растёт только лог (пульс кучи), папка дубля стоит
            return self.t if p.log in paths else p.start
        return self.t

    def start(self, cmd, cwd, env, log):
        kind = "render" if "INCOMING_FILM_SHOTS" in env else "record"
        take = env.get("INCOMING_NEWISLE_TAKE") or env.get("INCOMING_NEWISLE_TRIAL", "").replace(":", "-")
        if cmd[0] == "cut":
            take = "cut"
        elif cmd[0] == "triage":
            take = "triage"
        elif kind == "render":
            take = os.path.basename(os.path.dirname(env["INCOMING_TRAILER_DIR"]))
        key = (take, kind, int(env.get("INCOMING_NEWISLE_VARIANT", 0)))
        self.starts.append((key, dict(env)))
        self.cmds.append(list(cmd))
        plan = self.script.get(key) or self.script.get((take, kind)) or default(kind, take, env)
        if callable(plan):
            plan = plan(env)
        p = FakeProc(self, plan, env, log)
        self.procs.append(p)
        return p


def ok_result(take, plans, saved=True):
    return {"take": take, "status": "done", "saved": saved,
            "shots": [{"plans": [p], "replay": p, "moment": True, "selected": True, "strikes": []} for p in plans]}


def default(kind, take, env):
    if take in ("cut", "triage"):
        return {"seconds": 60}
    if kind == "render":
        return {"seconds": 120, "render": {}}
    plans = {"A": ["a1"], "B": ["b1"], "C": ["c1"], "D": ["d1"]}.get(take, [])
    return {"seconds": 300, "result": ok_result(take, plans), "log": "NEWISLE heap 30% (3000 МБ из 10000)"}


class ProduceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = self.tmp.name
        self.master = os.path.join(root, "master")
        os.makedirs(os.path.join(self.master, "region"))
        with open(os.path.join(self.master, "level.dat"), "w") as f:
            f.write("master")
        self.takes = os.path.join(root, "takes.json")
        takes_json(self.takes)
        self.cfg = {"repo": root, "master": self.master, "work": os.path.join(root, "work"), "saves": os.path.join(root, "saves"),
                    "protected": [os.path.join(root, "golden")], "record": ["record"], "render": ["render"],
                    "cut": ["cut"], "pause_if_running": ["prism"], "fault_kill_s": 90}
        os.makedirs(self.cfg["saves"])

    def tearDown(self):
        self.tmp.cleanup()

    def conveyor(self, script=None, faults=None):
        m = FakeMachine(script or {})
        script = m.script
        script.setdefault(("cut", "record"), {"seconds": 60})
        return P.Conveyor(self.cfg, machine=m, takes_file=self.takes, faults=faults, log=lambda *_: None), m

    def status(self):
        with open(os.path.join(self.cfg["work"], "status.txt"), encoding="utf-8") as f:
            return f.read().splitlines()

    def test_full_chain_branch_and_cleanup(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run())
        st = c.state["takes"]
        self.assertEqual("passed", st["A"]["status"])
        self.assertEqual("chained", st["B"]["status"], "дубль с Артёмом в цепочке — только удары")
        self.assertEqual("passed", st["C"]["status"])
        self.assertEqual("passed", st["D"]["status"])
        envs = {k: e for k, e in m.starts}
        self.assertEqual("-", envs[("B", "record", 0)]["INCOMING_TRAILER_SHOTS"], "без записи")
        # ветка C — на копии снимка B; D — цепочка от B, не от C
        world_b = st["B"]["snapshot"]
        self.assertEqual(world_b, "nw-B-a0")
        # снимки, что больше не вход, — в очереди на удаление (лежат, пока место есть: перевывод по замечанию)
        # вход B (снимок A) держится до живого дубля Артёма
        self.assertEqual("nw-A-a0", st["B"]["before"])
        self.assertEqual(["nw-C-a0", "nw-B-a0"], c.state["released"])
        self.assertEqual(["nw-A-a0", "nw-B-a0", "nw-C-a0", "nw-D-a0"], sorted(os.listdir(self.cfg["saves"])))
        lines = self.status()
        self.assertEqual(5, len(lines))
        self.assertEqual("stage: done", lines[0])
        self.assertTrue(lines[1].startswith("takes: сняты 4, не сняты 0, осталось 0"), lines[1])
        self.assertIn("чистовой ждёт", lines[4], "лист аниматика — настоящий, его планов тут нет: только черновик")
        self.assertEqual(["cut", "--draft"], m.cmds[-1])
        self.assertTrue(os.path.exists(os.path.join(self.master, "level.dat")), "мастер цел")

    def test_killed_client_is_retaken_with_spare_camera(self):
        c, m = self.conveyor(faults={"A": "kill"})
        self.assertEqual(0, c.run(["A"]))
        tries = c.state["takes"]["A"]["attempts"]
        self.assertEqual(2, len(tries))
        self.assertIn("без итога", tries[0]["reason"])
        self.assertEqual(1, tries[1]["variant"])
        v = P.smoke_verdict(c, {"A": "kill"})
        self.assertTrue(v["pass"], v)

    def test_killed_clients_world_removed_after_gradle_gone(self):
        # запускатель убивается сразу, а JVM-клиент — под одиночным градл-демоном вне группы сессии и уходит
        # секундами позже, дописывая мир: копия мира удаляется только после него, иначе rmtree ловил
        # «Directory not empty» (ночь 2026-10-14)
        c, m = self.conveyor(faults={"A": "kill"})
        m.client_until = m.t + 90 + 6              # демон переживает убийство запускателя на 6 с
        gone = []
        m.remove_tree = lambda path: (gone.append((os.path.basename(path), m.t)),
                                      P.Machine.remove_tree(m, path))
        self.assertEqual(0, c.run(["A"]))
        first = next(t for name, t in gone if name == "nw-A-a0")
        self.assertGreaterEqual(first, m.client_until, "копия мира — после ухода демона")
        self.assertTrue(P.smoke_verdict(c, {"A": "kill"})["pass"])

    def test_disk_pause_then_go_on(self):
        c, m = self.conveyor(faults={"A": "disk"})
        t0 = m.t
        self.assertEqual(0, c.run(["A"]))
        self.assertTrue(any(e[0] == "pause" and "мало места" in e[2] for e in c.events))
        self.assertGreaterEqual(m.t - t0, 30 * 60, "пауза — сном, не падением")
        self.assertTrue(P.smoke_verdict(c, {"A": "disk"})["pass"])

    def test_bad_camera_fails_check_then_spare_passes(self):
        bad = {"seconds": 300, "result": ok_result("A", ["a1"]), "log": "WARN SCENARIO film camera a1: камера в блоках"}
        c, m = self.conveyor({("A", "record", 0): bad}, faults={"A": "camera"})
        self.assertEqual(0, c.run(["A"]))
        envs = [e for k, e in m.starts if k[1] == "record"]
        self.assertEqual("camera", envs[0].get("INCOMING_NEWISLE_FAULT"))
        self.assertNotIn("INCOMING_NEWISLE_FAULT", envs[1], "запасная камера — без порчи")
        self.assertTrue(P.smoke_verdict(c, {"A": "camera"})["pass"])

    def test_heap_pulse_alone_is_no_movement(self):
        # клиент на экране «Failed to load world»: в лог идёт только пульс кучи — сторож снимает его, не ждёт срока
        c, m = self.conveyor({("A", "record", 0): {"hang": True, "pulse": True}})
        self.assertEqual(0, c.run(["A"]))
        first = c.state["takes"]["A"]["attempts"][0]
        self.assertIn("нет движения", first["reason"])
        self.assertEqual("passed", c.state["takes"]["A"]["status"], "пересъёмка снимает дубль")

    def test_search_stall_stops_that_search(self):
        c, m = self.conveyor({("boat", "record"): {"hang": True, "pulse": True}})
        with self.assertRaises(P.Stopped) as e:
            c.trial("boat")
        self.assertIn("нет движения", str(e.exception))
        self.assertLess(m.t - 1000, 3600, "не до срока пробы")

    def test_silent_client_and_heap_are_cut_off(self):
        script = {("A", "record", 0): {"hang": True, "silent": True},
                  ("A", "record", 1): {"hang": True, "log": "NEWISLE heap 75% (7500 МБ из 10000)"}}
        c, m = self.conveyor(script)
        # куча видна сторожу, пока клиент жив: строка — сразу в лог
        def heap_now(env):
            with open(os.path.join(env["INCOMING_TRAILER_DIR"], "record.log"), "a") as f:
                f.write("NEWISLE heap 75% (7500 МБ из 10000)\n")
            return {"hang": True}
        m.script[("A", "record", 1)] = heap_now
        self.assertEqual(0, c.run(["A"]))
        reasons = [a["reason"] for a in c.state["takes"]["A"]["attempts"]]
        self.assertIn("нет движения", reasons[0])
        self.assertIn("куча", reasons[1])
        self.assertEqual("passed", c.state["takes"]["A"]["status"])

    def test_take_time_limit(self):
        c, m = self.conveyor({("A", "record", 0): {"hang": True}})
        self.assertEqual(0, c.run(["A"]))
        r = c.state["takes"]["A"]["attempts"][0]["reason"]
        self.assertIn("срок 10 мин", r, "2 × оценка 5 мин до первого замера")

    def test_retries_then_skip_and_chain_goes_on_from_struck_world(self):
        bad = {"seconds": 300, "result": dict(ok_result("A", ["a1"]), shots=[{"replay": "a1", "moment": False, "selected": True}]),
               "log": ""}
        c, m = self.conveyor({("A", "record"): bad})
        self.assertEqual(0, c.run(["A", "B"]))
        a = c.state["takes"]["A"]
        self.assertEqual("skipped", a["status"])
        self.assertEqual(3, len(a["attempts"]), "две пересъёмки, потом пропуск")
        self.assertEqual("nw-A-a0", a["snapshot"], "цепочка — от мира, где удары легли")
        b_env = [e for k, e in m.starts if k[0] == "B"][0]
        self.assertEqual("nw-B-a0", b_env["INCOMING_WORLD"])

    def test_render_fail_is_retaken(self):
        c, m = self.conveyor({("A", "render"): lambda env: {"seconds": 60, "render": {"a1": "FAIL"}}
                              if len([s for s in m.starts if s[0][1] == "render"]) == 1 else {"seconds": 60, "render": {}}})
        self.assertEqual(0, c.run(["A"]))
        tries = c.state["takes"]["A"]["attempts"]
        self.assertFalse(tries[0]["ok"])
        self.assertIn("a1", tries[0]["reason"])
        self.assertTrue(tries[1]["ok"])

    def test_reset_take_keeps_its_recorded_attempt_world_and_releases_the_old_spare(self):
        # 17.10: запись T24 сброшена в state.json с прежним kept; попытка 1 записана, но не прошла вывод — её мир
        # удалили на старте попытки 2, и перевыводы из её повтора шли без LOD DH. Прежний kept — не из попыток этого
        # прогона: в очередь удаления, запасной — мир записанной попытки 1
        os.makedirs(os.path.join(self.cfg["saves"], "nw-A-old"))
        c, m = self.conveyor({("A", "render"): lambda env: {"seconds": 60, "render": {"a1": "FAIL"}}
                              if len([s for s in m.starts if s[0][1] == "render"]) == 1 else {"seconds": 60, "render": {}}})
        c.state["takes"]["A"] = {"attempts": [], "kept": "nw-A-old"}
        c.save()
        seen = []
        orig = c.attempt

        def attempt(take, n, variant, src, **kw):
            seen.append((c.state["takes"][take].get("kept"), sorted(os.listdir(self.cfg["saves"]))))
            return orig(take, n, variant, src, **kw)
        c.attempt = attempt
        self.assertEqual(0, c.run(["A"]))
        kept_before_second, saves_before_second = seen[1]
        self.assertEqual("nw-A-a0", kept_before_second, "мир записанной попытки 1 — запасной снимок")
        self.assertIn("nw-A-a0", saves_before_second, "мир попытки 1 на месте, когда идёт попытка 2")
        self.assertIn("nw-A-old", c.state["released"], "прежний запасной — в очередь удаления")
        self.assertIn("nw-A-old", os.listdir(self.cfg["saves"]), "и лежит, пока место есть")
        self.assertEqual("passed", c.state["takes"]["A"]["status"])

    def test_failed_attempt_with_passed_plans_keeps_its_world_in_the_release_queue(self):
        # попытка 2 записана, план a1 выведен с PASS, но дубль не прошёл (второй план); запасной уже есть (попытка 1):
        # мир попытки 2 — в очередь удаления, а не прочь, — черновик берёт её a1
        with open(self.takes, encoding="utf-8") as f:
            data = json.load(f)
        data["takes"][0]["shots"] = [{"plans": ["a1", "a2"], "seconds": 4,
                                      "cover": {"a1": {"subject": "scene"}, "a2": {"subject": "scene"}}}]
        with open(self.takes, "w", encoding="utf-8") as f:
            json.dump(data, f)
        script = {("A", "record"): {"seconds": 300, "result": ok_result("A", ["a1", "a2"])}}
        renders = []

        def render(env):
            renders.append(1)
            return {"seconds": 60, "render": {"a2": "FAIL"} if len(renders) < 3 else {}}
        script[("A", "render")] = render
        c, m = self.conveyor(script)
        self.assertEqual(0, c.run(["A"]))
        tries = c.state["takes"]["A"]["attempts"]
        self.assertFalse(tries[1]["ok"])
        self.assertEqual("PASS", tries[1]["plans"]["a1"][0])
        self.assertIn(tries[1]["world"], c.state["released"])
        self.assertIn(tries[1]["world"], os.listdir(self.cfg["saves"]))

    def test_hand_reset_with_kept_equal_to_snapshot_still_keeps_the_new_recording(self):
        # сброс руками, где snapshot и kept из прошлого прогона совпадают: прежний — в очередь, мир попытки 1 цел
        os.makedirs(os.path.join(self.cfg["saves"], "nw-A-old"))
        c, m = self.conveyor({("A", "render"): lambda env: {"seconds": 60, "render": {"a1": "FAIL"}}
                              if len([s for s in m.starts if s[0][1] == "render"]) == 1 else {"seconds": 60, "render": {}}})
        c.state["takes"]["A"] = {"attempts": [], "kept": "nw-A-old", "snapshot": "nw-A-old"}
        c.save()
        seen = []
        orig = c.attempt

        def attempt(take, n, variant, src, **kw):
            seen.append(c.state["takes"][take].get("kept"))
            return orig(take, n, variant, src, **kw)
        c.attempt = attempt
        self.assertEqual(0, c.run(["A"]))
        self.assertEqual("nw-A-a0", seen[1])
        self.assertIn("nw-A-old", c.state["released"])

    def test_retake_releases_the_old_spare_snapshot(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        os.makedirs(os.path.join(self.cfg["saves"], "nw-C-spare"))
        c.state["takes"]["C"]["kept"] = "nw-C-spare"
        c.save()
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("C\n")
        self.assertEqual(0, c.run(["C"]))
        self.assertIn("nw-C-spare", c.state["released"])
        self.assertNotEqual("nw-C-spare", c.state["takes"]["C"].get("kept"))

    def test_stopped_take_stops_and_waits(self):
        c, m = self.conveyor({("A", "record"): {"seconds": 10, "result": {"status": "stopped", "reason": "нет поз камер a1"}}})
        self.assertEqual(P.STOPPED_EXIT, c.run())
        lines = self.status()
        self.assertTrue(lines[0].startswith("stage: stopped, waiting"), lines[0])
        self.assertIn("нет поз камер a1", lines[3])
        self.assertEqual("waits for: решения человека", lines[4])

    def test_game_running_pauses_and_interrupts_without_counting(self):
        c, m = self.conveyor()
        m.game_until = m.t + 10        # игра Артёма запущена: пауза до начала
        orig = m.start

        def start(cmd, cwd, env, log):
            p = orig(cmd, cwd, env, log)
            if len(m.starts) == 1:
                m.game_until = m.t + 100   # игра запустилась посреди записи: клиент снят, попытка не в счёт
            return p
        m.start = start
        self.assertEqual(0, c.run(["A"]))
        tries = c.state["takes"]["A"]["attempts"]
        self.assertTrue(tries[0].get("interrupted"))
        self.assertEqual(0, tries[1]["variant"], "та же камера: снятие не по вине дубля")
        self.assertTrue(any(e[0] == "pause" and "игра" in e[2] for e in c.events))

    def test_resume_skips_done_takes(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A"]))
        c2, m2 = self.conveyor()
        self.assertEqual(0, c2.run(["A", "B"]))
        self.assertFalse(any(k[0] == "A" for k, _ in m2.starts), "снятое не переснимается")
        self.assertEqual(os.path.join(self.cfg["saves"], "nw-A-a0"), m2.copies[0][0], "B — со снимка A из прошлого запуска")

    def test_night_limit(self):
        c, m = self.conveyor()
        c.limits["night_hours"] = 0.1      # 6 минут: второй дубль уже не начинается, черновик — из снятого
        self.assertEqual(0, c.run())
        self.assertIn("ночь кончилась, не дошли B, C, D", self.status()[4])
        self.assertEqual("passed", c.state["takes"]["A"]["status"])
        self.assertNotIn("B", c.state["takes"])
        self.assertEqual(["cut", "--draft"], m.cmds[-1])

    def test_game_at_night_end_stops_without_cut(self):
        c, m = self.conveyor()
        c.limits["night_hours"] = 0.1
        m.game_until = m.t + 10 ** 6     # утро: Артём играет до конца ночи
        self.assertEqual(P.STOPPED_EXIT, c.run())
        self.assertIn("ночь кончилась на паузе", self.status()[3])
        self.assertFalse(m.cmds)

    def test_wrong_commit_or_unknown_take_stops_before_copying(self):
        self.cfg["commit"] = "abc123"
        c, m = self.conveyor()
        m.head = "fff000"
        self.assertEqual(P.STOPPED_EXIT, c.run(["A"]))
        self.assertIn("коммит fff000", self.status()[3])
        self.assertEqual([], m.copies)
        c, m = self.conveyor()
        self.assertEqual(P.STOPPED_EXIT, c.run(["A", "Z9"]))
        self.assertIn("Z9", self.status()[3])

    def test_filming_copy_layout_copies_its_world_folder(self):
        film = os.path.join(self.tmp.name, "film")
        os.makedirs(os.path.join(film, "world", "region"))
        with open(os.path.join(film, "world", "level.dat"), "w") as f:
            f.write("film")
        with open(os.path.join(film, "DistantHorizons.toml"), "w") as f:
            f.write("")
        self.cfg["master"] = film
        c, m = self.conveyor()
        self.assertEqual(os.path.join(film, "world"), c.master, "мир — папка с level.dat, не вся копия для съёмки")
        self.assertEqual(0, c.run(["A"]))
        with self.assertRaises(P.Stopped):
            c.remove(os.path.join(film, "DistantHorizons.toml"))

    def test_live_take_after_the_night_starts_from_its_chain_input(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run())                           # ночь без Артёма: B в цепочке — только удары
        self.assertEqual(0, P.live(c, "B", standin=True))
        src, dst = m.copies[-1]
        self.assertEqual(os.path.join(self.cfg["saves"], "nw-A-a0"), src, "живой дубль — с мира, где цепочка вошла в B")
        self.assertEqual("nw-B-a100", os.path.basename(dst))
        self.assertNotIn("nw-A-a0", c.state["released"])
        self.assertEqual(0, c.run())                           # утро: вывод живого и монтаж, вход B — в очередь
        self.assertIsNotNone(c.state["live"]["B"]["attempts"][-1]["plans"])
        self.assertIn("nw-A-a0", c.state["released"])
        self.assertIn("nw-B-a100", c.state["released"])

    def test_live_take_before_any_chain_uses_master(self):
        c, m = self.conveyor()
        self.assertEqual(0, P.live(c, "B", standin=True))
        self.assertEqual(self.master, m.copies[-1][0])

    def test_soft_check_retakes_then_passes_with_warning_on_last_try(self):
        orig = P.take_checks.record_problems
        P.take_checks.record_problems = lambda rec, take, result: ["пуски в один тик"] if take["id"] == "A" else []
        try:
            c, m = self.conveyor()
            self.assertEqual(0, c.run(["A"]))
        finally:
            P.take_checks.record_problems = orig
        tries = c.state["takes"]["A"]["attempts"]
        self.assertEqual("passed", c.state["takes"]["A"]["status"], "мягкая проверка не выбрасывает дубль")
        self.assertEqual(3, len(tries), "пересъёмки, пока попытки есть")
        self.assertEqual(["пуски в один тик"], tries[-1]["warnings"])

    def test_server_lag_and_siren_warn_without_retakes(self):
        orig = P.take_checks.record_warnings
        P.take_checks.record_warnings = lambda rec, take, result: ["n1: сервер 84.0 мс/тик (тики 49..58)",
                                                                   "n1: сирена не слышна (первый удар в тик 227)"]
        try:
            c, m = self.conveyor()
            self.assertEqual(0, c.run(["A"]))
        finally:
            P.take_checks.record_warnings = orig
        tries = c.state["takes"]["A"]["attempts"]
        self.assertEqual("passed", c.state["takes"]["A"]["status"])
        self.assertEqual(1, len(tries), "отставание и сирена пересъёмку не тратят")
        self.assertEqual(2, len(tries[0]["warnings"]))

    def test_frame_check_fail_goes_in_with_warning_without_retake(self):
        c, m = self.conveyor({("A", "render"): lambda env: {"seconds": 60, "render": {"a1": "FAIL"}}})
        orig = os.path.isdir
        P.os.path.isdir = lambda path: path.endswith(os.path.join("frames", "a1")) or orig(path)
        try:
            self.assertEqual(0, c.run(["A"]))
        finally:
            P.os.path.isdir = orig
        st = c.state["takes"]["A"]
        self.assertEqual("passed", st["status"], "выведенный план — в черновик, не заливка")
        self.assertEqual(1, len(st["attempts"]), "события записаны — без пересъёмки, камеру правит вывод")
        self.assertIn("предупреждение", st["attempts"][-1]["plans"]["a1"][1])

    def test_stage_is_the_word_after_stage(self):
        self.assertEqual("done", P.stage("stage: done\nfails: none; view: running\n"))
        self.assertEqual("stopped", P.stage("stage: stopped, waiting\ntakes: сняты 1\n"))
        self.assertEqual("recording", P.stage("stage: recording — T16 попытка 2/3 (камера 1)\n"))
        self.assertEqual("", P.stage(""))
        self.assertEqual("", P.stage("takes: сняты 6\nstage: done\n"), "этап — только в первой строке")

    def test_requeue_brings_back_a_skipped_take(self):
        c, m = self.conveyor()
        c.state["takes"]["C"] = {"status": "skipped", "attempts": [{"ok": False, "reason": "пропущен до ночи"}]}
        self.assertEqual(0, c.run(["A", "B", "C"]))
        self.assertEqual("skipped", c.state["takes"]["C"]["status"], "без файла — пропуск остаётся")
        with open(os.path.join(self.cfg["work"], "requeue.txt"), "w") as f:
            f.write("C\nA\n")
        self.assertEqual(0, c.run(["A", "B", "C"]))
        self.assertEqual("passed", c.state["takes"]["C"]["status"])
        self.assertEqual(1, len(c.state["takes"]["A"]["attempts"]), "снятый не переснимается")
        self.assertFalse(os.path.exists(os.path.join(self.cfg["work"], "requeue.txt")))

    def test_passed_take_renders_a_plan_covered_after_recording(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A"]))
        a = c.state["takes"]["A"]["attempts"][-1]
        a["plans"] = {}                                        # запись без вывода: cover плану дали позже
        c.save()
        self.assertEqual(0, c.run(["A"]))
        self.assertEqual("PASS", c.state["takes"]["A"]["attempts"][-1]["plans"]["a1"][0], "вывод из той же записи")
        self.assertEqual(1, len(c.state["takes"]["A"]["attempts"]), "без пересъёмки")

    def test_requeued_take_keeps_its_old_world_as_chain_input(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A"]))
        c.state["takes"]["A"] = {"status": "skipped", "snapshot": "nw-A-a0", "kept": "nw-A-a0",
                                 "attempts": [{"ok": False, "reason": "пропущен ночью"}]}
        c.state["chain"] = "nw-A-a0"                           # пропущенный дубль оставил цепочке свой мир
        c.save()
        with open(os.path.join(self.cfg["work"], "requeue.txt"), "w") as f:
            f.write("A\n")
        self.assertEqual(0, c.run(["A"]))
        src, dst = m.copies[-1]
        self.assertEqual(os.path.join(self.cfg["saves"], "nw-A-a0"), src)
        self.assertEqual("nw-A-a0r1", os.path.basename(dst), "копия не поверх своего входа")
        self.assertEqual("passed", c.state["takes"]["A"]["status"])

    def test_requeue_is_read_at_each_take_boundary(self):
        c, m = self.conveyor()
        c.state["takes"]["A"] = {"status": "skipped", "attempts": [{"ok": False, "reason": "пропущен ночью"}]}
        orig = c.take

        def take(t):
            st = orig(t)
            if t == "B":                                   # постановку A починили, пока снимался B
                with open(os.path.join(self.cfg["work"], "requeue.txt"), "w") as f:
                    f.write("A\n")
            return st
        c.take = take
        self.assertEqual(0, c.run(["A", "B", "C"]))
        self.assertEqual("passed", c.state["takes"]["A"]["status"], "без перезапуска: на границе после B")
        self.assertTrue(os.path.exists(os.path.join(self.cfg["work"], "requeue.txt.done")))

    def test_retake_reshoots_a_passed_take_from_its_own_input(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        self.assertEqual("nw-D-a0", c.state["chain"])
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("C\n")
        self.assertEqual(0, c.run(["C"]))
        st = c.state["takes"]["C"]
        # вход — снимок базы ветки (B), каким был при первой съёмке, а не нынешняя голова цепочки (D)
        src, dst = m.copies[-1]
        self.assertEqual(os.path.join(self.cfg["saves"], "nw-B-a0"), src)
        self.assertEqual("nw-C-a0r1", os.path.basename(dst), "копия не поверх прежнего снимка C")
        self.assertEqual("passed", st["status"])
        self.assertEqual("nw-C-a0r1", st["snapshot"])
        self.assertEqual("nw-D-a0", c.state["chain"], "цепочка не сдвигается")
        self.assertIn("nw-C-a0r1", c.state["released"], "новый снимок — лист")
        self.assertIn("nw-C-a0", c.state["released"], "прежний снимок — в очередь удаления")
        self.assertTrue(os.path.exists(os.path.join(self.cfg["work"], "retake.txt.done")))

    def test_retake_keeps_the_old_recording_aside(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        rec = c.state["takes"]["C"]["attempts"][-1]["rec"]
        with open(os.path.join(rec, "mark"), "w") as f:
            f.write("вывод в монтаже")
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("C\n")
        self.assertEqual(0, c.run(["C"]))
        new = c.state["takes"]["C"]["attempts"][-1]["rec"]
        self.assertFalse(os.path.exists(os.path.join(new, "mark")), "новая запись — в чистой папке")
        aside = [d for d in os.listdir(os.path.dirname(rec)) if d.startswith(os.path.basename(rec) + "-")]
        self.assertEqual(1, len(aside), "прежняя запись — рядом под своим именем")
        self.assertTrue(os.path.exists(os.path.join(os.path.dirname(rec), aside[0], "mark")))

    def test_retake_copy_never_takes_the_name_of_a_protected_world(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        # прогон вслепую: снимок C из другой работы под защитой, а состояние о нём не знает
        self.cfg["protected"].append(os.path.join(self.cfg["saves"], "nw-C-a0r1"))
        c2, m2 = self.conveyor()
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("C\n")
        self.assertEqual(0, c2.run(["C"]))
        self.assertEqual("nw-C-a0r2", os.path.basename(m2.copies[-1][1]), "имя защищённого мира — занято")
        self.assertEqual("passed", c2.state["takes"]["C"]["status"])

    def test_copy_never_lands_on_a_world_it_does_not_know(self):
        # мир прерванной записи другой работы лежит под именем первой попытки: копия — под другим именем, мир цел
        stray = os.path.join(self.cfg["saves"], "nw-A-a0")
        os.makedirs(stray)
        with open(os.path.join(stray, "level.dat"), "w") as f:
            f.write("чужой")
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A"]))
        self.assertEqual("nw-A-a0r1", c.state["takes"]["A"]["snapshot"])
        with open(os.path.join(stray, "level.dat")) as f:
            self.assertEqual("чужой", f.read())

    def test_retake_of_a_chain_take_uses_the_previous_chain_snapshot(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("D\n")
        self.assertEqual(0, c.run(["D"]))
        src, dst = m.copies[-1]
        self.assertEqual(os.path.join(self.cfg["saves"], "nw-B-a0"), src,
                         "вход — снимок прошлого дубля цепочки (B), ветка C его не двигала")
        self.assertEqual("nw-D-a0r1", c.state["takes"]["D"]["snapshot"])
        self.assertEqual("nw-D-a0r1", c.state["chain"], "переснят головной дубль — замена на месте")

    def test_retake_keeps_the_live_take_input_world(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        c.state["released"].append("nw-A-a0")            # прошлый прогон успел поставить вход живого дубля в очередь
        c.save()
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("A\n")
        self.assertEqual(0, c.run(["A"]))
        src, dst = m.copies[-1]
        self.assertEqual(self.master, src, "первый дубль цепочки — мастер")
        self.assertEqual("nw-A-a0r1", c.state["takes"]["A"]["snapshot"])
        self.assertNotIn("nw-A-a0", c.state["released"], "вход живого дубля B — не в очереди на удаление")
        self.assertTrue(os.path.exists(os.path.join(self.cfg["saves"], "nw-A-a0", "level.dat")))
        self.assertIn("nw-A-a0r1", c.state["released"])

    def test_retake_without_input_world_skips_and_logs(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        starts = len(m.starts)
        import shutil
        shutil.rmtree(os.path.join(self.cfg["saves"], "nw-B-a0"))
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("C\n")
        self.assertEqual(0, c.run(["C"]))
        self.assertEqual("passed", c.state["takes"]["C"]["status"], "без входа дубль остаётся снятым")
        self.assertEqual(starts, len(m.starts), "ничего не переснимается")
        with open(os.path.join(self.cfg["work"], "produce.log"), encoding="utf-8") as f:
            self.assertIn("C: пересъёмка пропущена — нет входного мира", f.read())

    def test_retake_of_a_take_that_is_not_shot_is_skipped(self):
        c, m = self.conveyor()
        c.state["takes"]["A"] = {"status": "skipped", "attempts": [{"ok": False, "reason": "пропущен ночью"}]}
        c.save()
        with open(os.path.join(self.cfg["work"], "retake.txt"), "w") as f:
            f.write("A\n")
        self.assertEqual(0, c.run(["A", "B", "C"]))
        self.assertEqual("skipped", c.state["takes"]["A"]["status"], "не снятый не переснимается — это requeue")

    def test_search_and_live_stop_without_a_world(self):
        os.remove(os.path.join(self.master, "level.dat"))
        c, m = self.conveyor()
        with self.assertRaises(P.Stopped) as e:
            P.searches(c, ["boat"])
        self.assertIn("нет мира", str(e.exception))
        with self.assertRaises(P.Stopped):
            P.live(c, "B", standin=True)
        self.assertEqual([], m.copies, "без мира — ни одной копии и ни одного клиента")

    def test_crash_twice_stops(self):
        crash = {"seconds": 300, "result": ok_result("A", ["a1"]), "log": "Exception in server tick loop"}
        c, m = self.conveyor({("A", "record"): crash})
        self.assertEqual(P.STOPPED_EXIT, c.run(["A"]))
        self.assertEqual(2, len(c.state["takes"]["A"]["attempts"]))
        self.assertIn("падает второй раз", self.status()[3])

    def test_killed_render_resumes_from_best(self):
        def render(env):
            first = len([k for k, _ in m.starts if k[1] == "render"]) == 1
            return {"hang": True, "silent": True} if first else {"seconds": 60, "render": {}}
        c, m = self.conveyor()
        m.script[("A", "render")] = render
        self.assertEqual(0, c.run(["A"]))
        renders = [e for k, e in m.starts if k[1] == "render"]
        self.assertEqual(2, len(renders))
        self.assertEqual(1, len(c.state["takes"]["A"]["attempts"]), "дубль не переснимается — вывод продолжен")
        self.assertTrue(any(e[0] == "render-resume" for e in c.events))

    def test_final_cut_when_every_plan_passed(self):
        c, m = self.conveyor()
        c.missing_plans = lambda: []
        self.assertEqual(0, c.run())
        self.assertEqual(["cut", "--final"], m.cmds[-1])
        self.assertIn("чистовой готов", self.status()[4])

    def test_final_waits_for_artem_when_machine_says_so(self):
        self.cfg["final"] = False
        c, m = self.conveyor()
        c.missing_plans = lambda: []
        self.assertEqual(0, c.run())
        self.assertEqual(["cut", "--draft"], m.cmds[-1])
        self.assertIn("чистовой — по слову Артёма", self.status()[4])

    def test_eta_from_measured_rate(self):
        c, m = self.conveyor()
        c.plan_order = ["A", "B", "C", "D"]
        c.state["spent"] = {"A": 900}         # 15 мин на дубль в 5 мин оценки — темп 3
        self.assertAlmostEqual(3 * 4 * 300, c.eta(["A", "B", "C", "D"]))

    def test_blind_run_checks_data_without_render_or_cut(self):
        self.cfg["render"] = None
        c, m = self.conveyor()
        self.assertEqual(0, c.run())
        self.assertFalse(any(k[1] == "render" for k, _ in m.starts))
        self.assertFalse(any(cmd[0] == "cut" for cmd in m.cmds))
        self.assertEqual("passed", c.state["takes"]["D"]["status"])

    def test_game_is_a_java_process_not_the_shell_that_names_it(self):
        import subprocess
        pattern = "instances/Airstrike Pack"
        java = os.path.join(self.tmp.name, "java")
        os.symlink(sys.executable, java)
        nap = "import time; time.sleep(30)"
        shell = subprocess.Popen([sys.executable, "-c", nap, pattern])
        try:
            self.assertIsNone(P.Machine().running([pattern]), "оболочка с подстрокой — не игра")
            game = subprocess.Popen([java, "-c", nap, pattern])
            try:
                found = P.Machine().running([pattern])
                self.assertIsNotNone(found)
                self.assertEqual(str(game.pid), found[0])
            finally:
                game.kill()
                game.wait()
        finally:
            shell.kill()
            shell.wait()

    def night(self, script=None, faults=None):
        m = FakeMachine(script or {})
        code = P.night(self.cfg, ["A", "D"], faults or {"A": "kill", "D": "disk"}, machine=m, takes_file=self.takes,
                       log=lambda *_: None)
        return code, m

    def test_night_probe_then_whole_chain(self):
        code, m = self.night()
        self.assertEqual(0, code)
        smoke = P.load_json(os.path.join(self.cfg["work"], "smoke.json"))
        self.assertTrue(smoke["pass"], smoke)
        state = P.load_json(os.path.join(self.cfg["work"], "state.json"))
        self.assertEqual(["A", "B", "C", "D"], [t for t in state["takes"]], "после пробы — вся цепочка в папке работы")
        first_full = [e for k, e in m.starts if k[1] == "record"][3]   # проба: A дважды (убит), D; дальше — цепочка
        self.assertEqual("nw-A-a0", first_full["INCOMING_WORLD"])
        self.assertEqual(os.path.join(self.cfg["work"], "takes", "A", "a0"), first_full["INCOMING_TRAILER_DIR"])
        self.assertFalse(any("smoke" in w for w in os.listdir(self.cfg["saves"])))
        self.assertEqual(["nw-A-a0", "nw-B-a0", "nw-C-a0", "nw-D-a0"], sorted(os.listdir(self.cfg["saves"])),
                         "от пробы миров не осталось")
        # перезапуск ночи: проба прошла — не повторяется, снятое не переснимается
        code, m2 = self.night()
        self.assertEqual(0, code)
        self.assertFalse(any(k[1] == "record" and k[0] != "cut" for k, _ in m2.starts))

    def test_night_failed_probe_stops_before_chain(self):
        bad = {"seconds": 300, "result": ok_result("A", ["a1"]), "log": "WARN SCENARIO film camera a1: камера в блоках"}
        code, m = self.night({("A", "record"): bad}, faults={"A": "camera"})
        self.assertEqual(P.STOPPED_EXIT, code)
        self.assertTrue(self.status()[0].startswith("stage: stopped, waiting"))
        self.assertIn("проба не прошла: A=camera", self.status()[3])
        self.assertFalse(os.path.exists(os.path.join(self.cfg["work"], "state.json")), "цепочка не начиналась")
        self.assertEqual(["nw-A-a0", "nw-D-a0"], sorted(os.listdir(self.cfg["saves"])),
                         "миры неудавшейся пробы остаются: пересъёмке после перезапуска нужен вход цепочки")

    def test_night_failed_probe_restart_reshoots_from_first_failed(self):
        # ночь 2026-10-14: проба провалилась на первом дубле — перезапуск той же строкой обязан переснять его и
        # всё после него (цепочка), а не застрять навсегда с терминальными пропусками
        bad = {"seconds": 300, "result": ok_result("A", ["a1"]), "log": "WARN SCENARIO film camera a1: камера в блоках"}
        code, m = self.night({("A", "record"): bad}, faults={"A": "camera", "D": "disk"})
        self.assertEqual(P.STOPPED_EXIT, code)
        code, m2 = self.night({("A", "record", 0): bad}, faults={"A": "camera", "D": "disk"})
        self.assertEqual(0, code, self.status())
        smoke = P.load_json(os.path.join(self.cfg["work"], "smoke.json"))
        self.assertTrue(smoke["pass"], smoke)
        smoke_state = P.load_json(os.path.join(self.cfg["work"], "smoke", "state.json"))
        self.assertEqual("passed", smoke_state["takes"]["A"]["status"])
        self.assertEqual(2, len(smoke_state["takes"]["A"]["attempts"]), "сброшенный дубль переснят с нуля")
        self.assertTrue(any(k == ("D", "record", 0) and "smoke" in e["INCOMING_TRAILER_DIR"] for k, e in m2.starts),
                        "дубль после сброшенного тоже переснят (цепочка пробы)")
        self.assertTrue(any(os.path.basename(src) == "nw-A-a1" and os.path.basename(dst) == "nw-D-a0"
                            for src, dst in m2.copies), "пересъёмка D — со снимка A")

    def test_night_restart_keeps_passed_prefix_and_events(self):
        # проба: A прошёл (порча kill — пересъёмка), D провалился; перезапуск переснимает только D, а вердикт A
        # держится на событиях ограждений из состояния — процесс-то уже другой
        bad = {"seconds": 300, "result": ok_result("D", ["d1"]), "log": "WARN SCENARIO film camera d1: камера в блоках"}
        code, m = self.night({("D", "record"): bad}, faults={"A": "kill", "D": "disk"})
        self.assertEqual(P.STOPPED_EXIT, code)
        smoke_state = P.load_json(os.path.join(self.cfg["work"], "smoke", "state.json"))
        self.assertTrue(any(e[0] == "fault-kill" for e in smoke_state["events"]), "события — в состоянии пробы")
        code, m2 = self.night(faults={"A": "kill", "D": "disk"})
        self.assertEqual(0, code, self.status())
        smoke = P.load_json(os.path.join(self.cfg["work"], "smoke.json"))
        self.assertTrue(smoke["pass"], smoke)
        self.assertTrue(smoke["checks"]["A=kill"]["ok"], "пройденный дубль не переснимался — вердикт по событиям из состояния")
        self.assertFalse(any(k[0] == "A" and k[1] == "record" and "smoke" in e["INCOMING_TRAILER_DIR"]
                             for k, e in m2.starts), "пройденный дубль пробы не переснимается")
        self.assertTrue(any(os.path.basename(src) == "nw-A-a1" and os.path.basename(dst) == "nw-D-a0"
                            for src, dst in m2.copies),
                        "пересъёмка сброшенного дубля — со снимка последнего пройденного")

    def test_heap_kill_share_sits_above_ruins_budget(self):
        # ядерные дубли легитимно держат живое старшее поколение до бюджета руин (HeapWatch.LIMIT 0.75, выход до
        # ~0.88 — ночь 2026-10-14, T24): порог убийства конвейера выше, иначе сторож снимает здоровый дубль
        with open(os.path.join(P.SCENES, "newisle-takes.json"), encoding="utf-8") as f:
            limits = json.load(f)["limits"]
        self.assertGreater(float(limits["heap_share"]), 0.9, limits["heap_share"])

    def test_status_heartbeat_during_long_record(self):
        c, m = self.conveyor({("A", "record"): {"seconds": 1200, "result": ok_result("A", ["a1"])}})
        stamps = []
        orig = c.status

        def status(state, note=""):
            stamps.append(m.t)
            orig(state, note)
        c.status = status
        self.assertEqual(0, c.run(["A"]))
        gaps = [b - a for a, b in zip(stamps, stamps[1:])]
        self.assertLessEqual(max(gaps), 305, "статус свежее 5 минут и посреди 20-минутной записи")
        self.assertTrue(os.path.getsize(os.path.join(self.cfg["work"], "produce.log")) > 0)

    def test_skipped_take_gets_triage_without_waiting(self):
        self.cfg["triage"] = ["triage", "-o", "{out}", "-a", "{rec}/record.log", "take {take}: {reason}"]
        bad = {"seconds": 300, "result": ok_result("A", ["a1"]), "log": "WARN SCENARIO film camera a1: камера в блоках"}
        c, m = self.conveyor({("A", "record"): bad})
        self.assertEqual(0, c.run(["A", "D"]))
        self.assertEqual("skipped", c.state["takes"]["A"]["status"])
        rec = c.state["takes"]["A"]["attempts"][-1]["rec"]
        tri = [cmd for cmd in m.cmds if cmd[0] == "triage"]
        self.assertEqual(1, len(tri))
        self.assertEqual(os.path.join(rec, "triage.md"), tri[0][2])
        self.assertTrue(tri[0][5].startswith("take A: a1: камера в блоках"))
        self.assertEqual("passed", c.state["takes"]["D"]["status"], "разбор не держит цепочку")

    def test_gpu_lock_held_by_other_job_pauses(self):
        lock = os.path.join(self.tmp.name, "gpu.lock")
        self.cfg["gpu_lock"] = lock
        c, m = self.conveyor()
        held = P.Machine().lock(lock)          # другая задача (GLM) держит видеокарту
        orig = m.sleep

        def sleep(sec):
            orig(sec)
            if sec >= 60:
                P.Machine().unlock(held)        # отпустила за время паузы
        m.sleep = sleep
        m.lock, m.unlock = P.Machine().lock, P.Machine().unlock
        self.assertEqual(0, c.run(["A"]))
        self.assertTrue(any(e[0] == "pause" and "видеокарта занята" in e[2] for e in c.events))
        self.assertEqual("passed", c.state["takes"]["A"]["status"])
        again = P.Machine().lock(lock)
        self.assertIsNotNone(again, "конвейер отпускает замок после клиента")
        P.Machine().unlock(again)

    def test_guard_refuses_protected_and_foreign_paths(self):
        c, m = self.conveyor()
        with self.assertRaises(P.Stopped):
            c.remove(self.master)
        with self.assertRaises(P.Stopped):
            c.copy_world(self.master, os.path.join("..", "golden", "x"))
        with self.assertRaises(P.Stopped):
            c.guard.check("/etc", "запись в")
        # мастер под папкой работы — всё равно защищён
        cfg = dict(self.cfg, master=os.path.join(self.cfg["saves"], "master"))
        c3 = P.Conveyor(cfg, machine=m, takes_file=self.takes, log=lambda *_: None)
        with self.assertRaises(P.Stopped):
            c3.remove(cfg["master"])

    def test_low_disk_prunes_released_worlds_before_pausing(self):
        c, m = self.conveyor()
        self.assertEqual(0, c.run(["A", "B", "C"]))            # ветка C — лист, в очереди на удаление
        self.assertEqual(["nw-C-a0"], c.state["released"])
        m.free = 10.0                                          # порог 4 + копия 1 — хватает
        frees = iter([4.5, 30.0])                              # до D: мало, после удаления C — хватает
        m.disk_free_gb = lambda path: next(frees, 30.0)
        self.assertEqual(0, c.run(["A", "B", "C", "D"]))
        self.assertTrue(any(e[0] == "prune" and e[2] == "nw-C-a0" for e in c.events))
        self.assertFalse(any(e[0] == "pause" for e in c.events), "место нашлось без паузы")
        self.assertFalse(os.path.exists(os.path.join(self.cfg["saves"], "nw-C-a0")))
        self.assertTrue(os.path.exists(os.path.join(self.cfg["saves"], "nw-A-a0")), "вход дубля Артёма цел")

    def test_low_memory_pauses(self):
        c, m = self.conveyor()
        m.mem = 5.0
        orig = m.sleep

        def sleep(s):
            orig(s)
            m.mem = 30.0
        m.sleep = sleep
        self.assertEqual(0, c.run(["A"]))
        self.assertTrue(any(e[0] == "pause" and "памяти" in e[2] for e in c.events))

    def test_check_take_reads_eta_and_crash(self):
        r = ok_result("A", ["a1"])
        r["shots"][0]["strikes"] = [{"weapon": "missile", "eta": 3.0, "bound": True, "actual": 4.6},
                                    {"weapon": "drone", "eta": 2.0, "bound": False}]
        problems = P.check_take(r, "Exception in server tick loop", {})
        self.assertTrue(any("tick loop" in p for p in problems))
        self.assertTrue(any("4.6" in p for p in problems))
        self.assertTrue(any("не найден" in p for p in problems))

    def test_check_take_does_not_time_a_strike_the_sam_shot_down(self):
        # шахед сбит ЗРК над озером (n17): удара нет, его «срок» не к чему мерить
        r = ok_result("A", ["a1"])
        r["shots"][0]["strikes"] = [{"weapon": "drone", "eta": 9.0, "bound": True, "intercepted": True, "down": 1.4},
                                    {"weapon": "drone", "eta": 3.0, "bound": True, "actual": 4.6}]
        problems = [p for p in P.check_take(r, "", {}) if "вместо" in p]
        self.assertEqual(1, len(problems), problems)
        self.assertIn("4.6", problems[0])

    def test_check_take_accepts_client_clock_when_server_reads_early(self):
        # серверное actual отстаёт от клиентского на период синхронизации часов (T13, 15.10):
        # удар в допуске хотя бы на одних часах — не отказ
        r = ok_result("A", ["a1"])
        r["shots"][0]["strikes"] = [{"weapon": "missile", "eta": 1.0, "bound": True,
                                     "actual": -0.1, "actual_client": 1.0}]
        self.assertEqual([], [p for p in P.check_take(r, "", {}) if "вместо" in p])
        r["shots"][0]["strikes"] = [{"weapon": "missile", "eta": 1.0, "bound": True,
                                     "actual": -0.1, "actual_client": -0.2}]
        problems = P.check_take(r, "", {})
        self.assertTrue(any("клиент -0.2" in p for p in problems), "оба часа мимо — отказ с клиентским временем")

    def test_check_take_reads_pass_time_from_the_journal(self):
        # часы постановщика под нагрузкой прыгают на синхронизации времени (T10 на netcup, 15.10: 2.85 с у точки
        # читались как 6.4) — момент пролёта берётся из events.jsonl (часы реплея, одни у меток и кадров)
        def rec_with_events(rows):
            rec = tempfile.mkdtemp()
            os.makedirs(os.path.join(rec, "replays"))
            with open(os.path.join(rec, "replays", "a1.events.jsonl"), "w", encoding="utf-8") as f:
                for r in rows:
                    f.write(json.dumps(r) + "\n")
            return rec

        def rows(times_x, mark_t=100):
            out = [{"t": mark_t, "mark": "plan:a1"}]
            for t, x in times_x:
                out.append({"t": t, "f": [[0, x, 100.0, 0.0, 4, 0]]})
            return out

        take = {"shots": [{"plans": ["a1"], "strikes": [
            {"weapon": "drone", "pass": {"at": [0, 0], "above": 40}, "eta": 3.0}]}]}
        strike = {"weapon": "drone", "eta": 3.0, "bound": True, "actual": 6.4}
        r = ok_result("A", ["a1"])
        r["shots"][0]["strikes"] = [dict(strike)]
        # путь у точки (0,0) на тике 160 = 3.0 с от метки 100 — в допуске, вопреки отчёту «6.4»
        rec = rec_with_events(rows([(140, -40.0), (160, 0.0), (180, 40.0)]))
        self.assertEqual([], [p for p in P.check_take(r, "", take, rec) if "вместо" in p])
        # путь опоздал на 3 с — отказ с временем и дистанцией из журнала
        rec = rec_with_events(rows([(200, -40.0), (220, 0.0), (240, 40.0)]))
        problems = [p for p in P.check_take(r, "", take, rec) if "вместо" in p]
        self.assertEqual(1, len(problems))
        self.assertIn("по журналу", problems[0])
        self.assertIn("6.0 с", problems[0])
        # журнала нет (rec без файла) — прежние правила по отчёту
        rec = rec_with_events([])
        problems = [p for p in P.check_take(r, "", take, rec) if "вместо" in p]
        self.assertTrue(any("6.4" in p for p in problems))

    def test_searches_pick(self):
        s = {"terrace": {"place": "hit", "probes": ["g", "h"], "glass_broken_min": 0.5, "house_kept_min": 0.97},
             "grad": {"plan": "n41", "mspt_max": 50}}
        results = {
            "terrace": [{"trial": {"distance": 40, "hit": [1, 2, 3]}, "probes": {"g": {"before": 10, "after": 0}, "h": {"before": 100, "after": 80}}},
                        {"trial": {"distance": 60, "hit": [4, 5, 6]}, "probes": {"g": {"before": 10, "after": 2}, "h": {"before": 100, "after": 99}}},
                        {"trial": {"distance": 85, "hit": [7, 8, 9]}, "probes": {"g": {"before": 10, "after": 9}, "h": {"before": 100, "after": 100}}}],
            "grad": [{"trial": {"size": 8, "mspt_peak": 20}}, {"trial": {"size": 16, "mspt_peak": 44}}, {"trial": {"size": 24, "mspt_peak": 70}}],
        }
        found = {"places": {}}
        P.pick(s, results, found)
        self.assertEqual([4, 5, 6], found["places"]["hit"], "ближайший удар, где стекло выбито, а дом стоит")
        self.assertEqual({"plan": "n41", "size": 16}, found["grad"])


if __name__ == "__main__":
    unittest.main()
