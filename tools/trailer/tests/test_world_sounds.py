"""Звук мира (WorldSounds → takes.world_sfx → edit.py) и сверка с живым клиентом (sound_compare) на синтетических данных.

    python3 -m unittest discover -s tools/trailer/tests
"""
import io
import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import sound_compare as C  # noqa: E402
import takes as T  # noqa: E402

try:  # крючок edit.py — с его пакетами (uv run), без них проверка пропускается
    import edit as E
except ImportError:
    E = None

A = 1000          # первый тик окна вывода (тик повтора)
CAM = (0.0, 70.0, 0.0, 0.0, 0.0)


def _w(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def _sound(t, event, pos, volume=1.0, **kw):
    """Строка журнала WorldSounds."""
    r = {"t": t, "gt": t + 50000, "sound": event, "source": "player", "pos": list(pos), "volume": volume, "pitch": 1.0,
         "range": max(volume, 1) * 16, "send": max(volume, 1) * 16, "heard": False}
    r.update(kw)
    return r


def _client(t, sid, event, pos, volume=1.0, rng=None, shot="p01", file=None):
    """Строка звука журнала клиента (Recorder): t — тики от начала плана."""
    return {"type": "sound", "shot": shot, "t": t, "id": sid, "event": event, "file": file or "", "pos": list(pos),
            "volume": volume, "pitch": 1.0, "range": rng or max(volume, 1) * 16, "relative": False, "linear": True,
            "loop": False}


def render_take(root, world, sounds=(), seconds=3.0, cam=CAM, name="p01"):
    """Кандидат вывода film/render/<план>/cam-a с журналом звука мира записи film/replays/<план>.sounds.jsonl."""
    d = os.path.join(root, "film", "render", name, "cam-a")
    rows = [{"type": "shot", "shot": name, "speed": 1.0}]
    for k in range(int(seconds * T.FPS)):
        rows.append({"type": "frame", "shot": name, "k": k, "t": k * T.TPS / T.FPS, "cam": list(cam)})
    rows += list(sounds)
    _w(os.path.join(d, "timeline.jsonl"), rows)
    _w(os.path.join(root, "film", "render", "report.jsonl"), [{"shot": name, "candidate": "cam-a", "a": A, "event": -1}])
    _w(os.path.join(root, "film", "replays", f"{name}.sounds.jsonl"), world)
    return d


class JavaRandomTest(unittest.TestCase):
    # java.util.Random(seed).nextInt(bound) — значения из JVM
    REF = {0: (0, 5, 0, 360, 784870680, 1569741360),
           42: (2, 1, 0, 130, 781215565, 1562431130),
           -1: (2, 3, 3, 913, 288774956, 577549913),
           123456789012345: (1, 0, 4, 174, 718490087, 1436980174),
           -8070450532247928832: (0, 5, 0, 360, 784870680, 1569741360)}   # старшие 16 бит зерна не в счёт
    BOUNDS = (3, 7, 10, 1000, 1 << 30, 0x7FFFFFFF)

    def test_first_int(self):
        for seed, want in self.REF.items():
            self.assertEqual(tuple(T.JavaRandom(seed).next_int(b) for b in self.BOUNDS), want, seed)

    def test_sequence(self):
        for seed, want in ((0, (5, 2, 4, 2, 4)), (42, (1, 5, 6, 3, 5))):
            r = T.JavaRandom(seed)
            self.assertEqual(tuple(r.next_int(7) for _ in range(5)), want)


def _defs():
    d = T.SoundDefs()
    d.add("minecraft", {
        "entity.test": {"sounds": ["test/a",
                                   {"name": "test/b", "weight": 3, "volume": 0.5, "attenuation_distance": 32},
                                   {"name": "entity.ref", "type": "event", "volume": 0.8, "pitch": 1.2}]},
        "entity.ref": {"sounds": [{"name": "ref/x", "pitch": 0.5, "attenuation_distance": 24}, "ref/y"]},
    })
    return d


class VariantTest(unittest.TestCase):
    def test_pick_by_seed(self):
        # вес: a 1, b 3, ссылка — весь вес entity.ref (2); Random(seed): nextInt(6), у ссылки — ещё nextInt(2)
        d = _defs()
        want = {0: ("minecraft:sounds/test/a.ogg", 16, 1.0, 1.0),
                1: ("minecraft:sounds/test/b.ogg", 32, 0.5, 1.0),
                6: ("minecraft:sounds/test/b.ogg", 32, 0.5, 1.0),
                2: ("minecraft:sounds/ref/x.ogg", 24, 0.8, 0.6),
                5: ("minecraft:sounds/ref/x.ogg", 24, 0.8, 0.6),
                7: ("minecraft:sounds/ref/y.ogg", 16, 0.8, 1.2)}
        for seed, (file, att, vol, pitch) in want.items():
            v = d.pick("minecraft:entity.test", seed)
            self.assertEqual((v.file, v.attenuation), (file, att), seed)
            self.assertAlmostEqual(v.volume, vol)
            self.assertAlmostEqual(v.pitch, pitch)

    def test_replace_and_unknown(self):
        d = _defs()
        d.add("minecraft", {"entity.ref": {"replace": True, "sounds": ["ref/z"]}})
        self.assertEqual(d.weight("minecraft:entity.test"), 1 + 3 + 1)
        # Point Blank: ресурсов нет — файл по имени события, затухание 16 (как в журналах клиента)
        v = d.pick("pointblank:ak47", 123)
        self.assertEqual((v.file, v.attenuation), ("pointblank:sounds/ak47.ogg", 16))
        self.assertIsNone(d.pick("minecraft:entity.unknown", 1))

    def test_mod_sounds_json(self):
        # варианты старта ускорителя — из sounds.json мода
        v = T.SoundDefs.default().pick("incoming:launch.booster", 5)
        self.assertTrue(v.file.startswith("incoming:sounds/launch_booster_"), v.file)


class EntryTest(unittest.TestCase):
    def test_range_and_format(self):
        d = _defs()
        ws = T.WorldSound(1040, 51040, "minecraft:entity.test", "hostile", (10, 70, 0), 4.0, 0.9, seed=1)
        e = T.world_entry(ws, 3, A, d)
        self.assertEqual(set(e), {"file", "event", "pos", "volume", "pitch", "range", "linear", "relative", "loop", "t", "id"})
        self.assertEqual((e["id"], e["t"], e["file"]), (T.WORLD_ID + 3, 40, "minecraft:sounds/test/b.ogg"))
        self.assertAlmostEqual(e["volume"], 2.0)          # 4 × 0.5 варианта
        self.assertAlmostEqual(e["range"], 64.0)          # max(2, 1) × 32
        ws.volume = 1.0
        self.assertAlmostEqual(T.world_entry(ws, 3, A, d)["range"], 32.0)   # max(0.5, 1) × 32
        # вариант, выбранный сервером записи, — как есть
        ws.file, ws.attenuation, ws.fv, ws.fp = "minecraft:sounds/test/a.ogg", 16, 1.0, 1.0
        self.assertEqual(T.world_entry(ws, 3, A, d)["file"], "minecraft:sounds/test/a.ogg")

    def test_gain_linear(self):
        ws = T.WorldSound(1000, 0, "pointblank:ak47", "player", (40, 70, 0), 5.0, 1.0, seed=7)
        e = T.world_entry(ws, 0, A)
        self.assertEqual((e["file"], e["range"]), ("pointblank:sounds/ak47.ogg", 80.0))
        s = T.Sound(e["id"], 0.0, e["event"], e["file"], tuple(e["pos"]), e["volume"], e["pitch"], e["range"],
                    e["relative"], e["linear"], e["loop"])
        self.assertAlmostEqual(s.gain_at(CAM), 0.5)       # min(5, 1) × (1 − 40/80)
        if E is not None:
            g, _, d = E._gain_pan(CAM, e["pos"], e["volume"], e["range"], e["relative"], e["linear"])
            self.assertAlmostEqual(g, 0.5)
            self.assertAlmostEqual(d, 40.0)


class WorldSfxTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        near = (10, 70, 0)
        self.world = [
            _sound(1020, "pointblank:ak47", (11, 70, 0), 5.0, heard=True),            # 0: клиент сыграл
            _sound(1040, "pointblank:ak47", (30, 70, 0), 5.0),                        # 1: не ушёл игроку записи
            _sound(1030, "pointblank:hit_light", near, 3.0, heard=True),              # 2: ушёл, а клиента нет
            _sound(1045, "pointblank:ak47", (500, 70, 0), 5.0),                       # 3: камере не слышно
            _sound(900, "pointblank:ak47", near, 5.0),                                # 4: до окна
            _sound(1020, "pointblank:ak47_other", near, 1.0),                         # 5: другое событие
            _sound(1050, "minecraft:entity.test", near, 1.0, seed=2),                 # 6: вариант по зерну
        ]
        self.client = [_client(20.5, 0, "pointblank:ak47", near, 5.0, file="pointblank:sounds/ak47.ogg")]
        self.d = render_take(self.root, self.world, self.client)

    def tearDown(self):
        self.tmp.cleanup()

    def test_dedupe(self):
        take = T.load_take("p01", self.d)
        self.assertEqual(len(take.world.sounds), 7)
        unplayed = {i for i, _ in T.world_unplayed(take)}
        self.assertEqual(unplayed, {1, 2, 3, 5, 6})
        got = {e["id"] - T.WORLD_ID: e for e in T.world_sfx(take, _defs())}
        self.assertEqual(set(got), {1, 2, 5, 6})
        self.assertEqual(got[1]["t"], 40)
        self.assertEqual(got[6]["file"], "minecraft:sounds/ref/x.ogg")
        self.assertAlmostEqual(got[6]["pitch"], 0.6)
        self.assertFalse(take.world.sounds[1].heard)
        self.assertTrue(take.world.sounds[2].heard)

    def test_hook(self):
        got = T.world_sfx_for("p01", self.d)
        self.assertEqual({e["id"] - T.WORLD_ID for e in got if e["event"].startswith("pointblank:")}, {1, 2, 5})
        # живой план (не вывод из повтора) — пусто
        self.assertEqual(T.world_sfx_for("p01", os.path.join(self.root, "film")), [])

    @unittest.skipIf(E is None, "нет пакетов edit.py (uv run)")
    def test_edit_load_recording(self):
        for k in range(3):
            os.makedirs(os.path.join(self.d, "frames", "p01"), exist_ok=True)
            open(os.path.join(self.d, "frames", "p01", f"{k:05d}.png"), "wb").close()
        shot = E.load_recording(self.d)["p01"]
        ids = {e["id"] for e in shot.sounds}
        self.assertIn(0, ids)
        self.assertTrue({T.WORLD_ID + 1, T.WORLD_ID + 2, T.WORLD_ID + 5} <= ids)

    @unittest.skipIf(E is None, "нет пакетов edit.py (uv run)")
    def test_bank_reads_other_mods_from_jars(self):
        # выстрел Point Blank: файла нет ни у мода, ни у ванили — он в jar каталога модов съёмки
        import numpy as np
        import soundfile as sf
        import zipfile
        with tempfile.TemporaryDirectory() as d:
            buf = io.BytesIO()
            sf.write(buf, (0.5 * np.sin(np.arange(4800) / 48000 * 2 * math.pi * 440)).astype(np.float32), 48000,
                     format="OGG", subtype="VORBIS")
            with zipfile.ZipFile(os.path.join(d, "pointblank-test.jar"), "w") as z:
                z.writestr("assets/pointblank/sounds/ak47_fire.ogg", buf.getvalue())
            old = os.environ.get("INCOMING_MODS_DIR")
            os.environ["INCOMING_MODS_DIR"] = d
            try:
                bank = E.SoundBank()
                x = bank.load("pointblank:sounds/ak47_fire.ogg")
                self.assertIsNotNone(x)
                self.assertAlmostEqual(len(x) / E.SR, 0.1, delta=0.01)
                self.assertIsNone(bank.load("pointblank:sounds/none.ogg"))
            finally:
                if old is None:
                    os.environ.pop("INCOMING_MODS_DIR")
                else:
                    os.environ["INCOMING_MODS_DIR"] = old

    def test_missing_triggers(self):
        world = self.world + [
            {"t": 1010, "gt": 51010, "trigger": "siren", "pos": [100, 70, 0], "radius": 350, "heard": False},
            {"t": 1011, "gt": 51011, "trigger": "siren", "pos": [100, 70, 0], "radius": 350, "heard": True},
            {"t": 1012, "gt": 51012, "trigger": "grid_failure", "pos": [400, 70, 0], "radius": 320, "heard": False},
            {"t": 1015, "gt": 51015, "flights": [["u1", "shahed", 50, 120, 0, 240, False], ["u2", "shahed", 50, 120, 0, 240, True]],
             "interceptors": []},
            {"t": 1020, "gt": 51020, "flights": [["u1", "shahed", 60, 120, 0, 240, False]], "interceptors": [[0, 90, 30, 400, False]]},
        ]
        d = render_take(self.root, world, self.client)
        got = [(m["kind"], round(m["t"], 2)) for m in T.missing_triggers(T.load_take("p01", d))]
        self.assertEqual(got, [("siren", 0.5), ("motor:shahed", 0.75), ("motor:interceptor", 1.0)])


class CompareTest(unittest.TestCase):
    """Живой клиент в точке L, журнал мира со сдвигом часов: тик клиента = t журнала − 60."""
    L = (0.0, 70.0, 0.0, 0.0, 0.0)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.rec = os.path.join(self.tmp.name, "film")
        self.world = [
            _sound(160, "minecraft:entity.generic.explode", (20, 70, 0), 4.0, file="minecraft:sounds/random/explode1.ogg",
                   attenuation=16),
            _sound(180, "pointblank:ak47", (40, 70, 0), 5.0),
            _sound(200, "pointblank:ak47", (40, 70, 0), 5.0),
            _sound(230, "pointblank:hit_light", (30, 70, 0), 3.0),      # клиенту слышно, а он не сыграл
            _sound(240, "pointblank:ak47", (400, 70, 0), 5.0),          # не слышно в точке
            {"t": 150, "gt": 50150, "trigger": "siren", "pos": [100, 70, 0], "radius": 350, "heard": False},
        ]
        _w(os.path.join(self.rec, "replays", "p01.sounds.jsonl"), self.world)

    def tearDown(self):
        self.tmp.cleanup()

    def live(self, extra=()):
        rows = [{"type": "shot", "shot": "p01", "speed": 1.0}]
        rows += [{"type": "frame", "shot": "p01", "k": k, "t": k / 3, "cam": list(self.L)} for k in range(600)]
        rows += [
            _client(100.0, 0, "minecraft:entity.generic.explode", (20, 70, 0), 4.0, file="minecraft:sounds/random/explode1.ogg"),
            _client(120.5, 1, "pointblank:ak47", (40, 70, 0), 5.0, file="pointblank:sounds/ak47.ogg"),
            _client(140.0, 2, "pointblank:ak47", (40.5, 70, 0), 5.0, rng=40, file="pointblank:sounds/ak47.ogg"),
            _client(95.0, 3, "incoming:blast.near", (5, 70, 0), 1.0),
        ]
        rows += list(extra)
        p = os.path.join(self.tmp.name, "live", "timeline.jsonl")
        _w(p, rows)
        return p

    def run_main(self, *args):
        out = io.StringIO()
        old, sys.stdout = sys.stdout, out
        try:
            code = C.main(list(args))
        finally:
            sys.stdout = old
        return code, out.getvalue()

    def test_aligned_and_clean(self):
        live = C.live_take(self.live(), "p01")
        world = T.read_world(os.path.join(self.rec, "replays", "p01.sounds.jsonl"))
        rows, offset, which = C.compare(live, world)
        self.assertEqual((offset, which), (-60, "t"))
        kinds = {}
        for r in rows:
            kinds.setdefault(r.kind, []).append(r.event)
        self.assertEqual(len(kinds["match"]), 3)
        self.assertEqual(kinds["unchecked"], ["incoming:blast.near"])
        self.assertEqual(sorted(kinds["extra"]), ["incoming:siren", "pointblank:hit_light"])
        self.assertNotIn("missing", kinds)
        # громкость: у второго выстрела клиент считал дальность 40, журнал мира — 80 (1 − 40.5/40 → 0: без разницы в дБ)
        diffs = [r.diff for r in rows if r.kind == "match" and r.diff is not None]
        self.assertTrue(all(abs(d) < 0.2 for d in diffs), diffs)
        code, text = self.run_main(self.live(), self.rec, "p01")
        self.assertEqual(code, 0, text)
        self.assertIn("сдвиг -60", text)

    def test_missing_audible(self):
        extra = [
            # выстрел, которого нет в журнале мира в это время, — слышно: код 1
            _client(150.0, 4, "pointblank:ak47", (10, 70, 0), 5.0, file="pointblank:sounds/ak47.ogg"),
            # сирена по пакету, который не ушёл игроку записи
            _client(91.0, 5, "incoming:siren", (20, 70, 0), 1.0, rng=320),
        ]
        code, text = self.run_main(self.live(extra), self.rec, "p01")
        self.assertEqual(code, 1, text)
        missing = [line for line in text.splitlines() if line.startswith("missing")]
        self.assertEqual(len(missing), 2, text)
        self.assertTrue(any("incoming:siren" in m and "не ушёл" in m for m in missing), text)

    def test_quiet_missing_passes(self):
        # пропущенный, но в точке не слышный (дальше дальности клиента) — не провал
        extra = [_client(150.0, 4, "pointblank:ak47", (300, 70, 0), 5.0)]
        code, text = self.run_main(self.live(extra), self.rec, "p01", "--offset", "-60")
        self.assertEqual(code, 0, text)
        self.assertTrue(any(line.startswith("missing") and "-inf" in line for line in text.splitlines()), text)

    def test_no_log(self):
        code, _ = self.run_main(self.live(), os.path.join(self.tmp.name, "nothing"), "p01")
        self.assertEqual(code, 2)

    def test_db(self):
        self.assertAlmostEqual(C.db(0.5), 20 * math.log10(0.5))
        self.assertEqual(C.db(0), -math.inf)


if __name__ == "__main__":
    unittest.main()
