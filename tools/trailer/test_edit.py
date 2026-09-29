#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
#     "scipy>=1.11",
#     "soundfile>=0.12",
#     "pytest>=8",
#     "av>=12",
# ]
# ///
"""Тесты монтажа трейлера (tools/trailer/edit.py): время плана (мир ↔ видео, замедление, стоп-кадр), якоря, сетка
склеек, звук в замедлении, музыка, звуки монтажа. Запись для тестов — синтетическая (make_recording: кадры PNG
и timeline.jsonl так же, как их пишет Recorder).

    uv run tools/trailer/test_edit.py                       # юнит-тесты
    AIRSTRIKE_E2E=1 uv run tools/trailer/test_edit.py       # и полный черновик по синтетической записи (минуты)
    uv run tools/trailer/test_edit.py make ПАПКА             # только записать синтетическую запись
"""
import json
import math
import os
import subprocess
import sys

import numpy as np
import pytest
import soundfile as sf
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edit  # noqa: E402

FPS, TPS = edit.FPS, edit.TPS


# ---------------------------------------------------------------- синтетическая запись

def simulate(n, want, freeze_at=None, freeze_frames=0, cam_speed=0.35):
    """Часы плана как у Recorder: скорость сглаживается (×0.12 за кадр), мир замирает на целом тике freeze_at на
    freeze_frames кадров. Возвращает [(k, t, ct, [отметки после кадра])]."""
    world = cam = 0.0
    speed = want(0)
    frozen = 0
    freeze_tick = None if freeze_at is None else math.ceil(freeze_at)
    out = []
    for k in range(n):
        marks = []
        was = frozen > 0
        step = 0.0 if frozen > 0 else TPS * speed / FPS
        if freeze_tick is not None and frozen == 0:
            step = min(step, max(0.0, freeze_tick - world))
        cstep = TPS * cam_speed / FPS if frozen > 0 else TPS * speed / FPS
        out.append([k, world, cam, marks])
        world += step
        cam += cstep
        if frozen > 0:
            frozen -= 1
        speed += (want(k) - speed) * 0.12
        if freeze_tick is not None and world >= freeze_tick - 1e-6:
            freeze_tick = None
            frozen = freeze_frames
            marks.append("freeze")
        elif was and frozen == 0:
            marks.append("unfreeze")
    return out


def shot_lines(name, n, want=lambda k: 1.0, speed=1.0, hud=False, freeze_at=None, freeze_frames=0,
               marks=(), sounds=(), lang="en_us"):
    """Строки журнала одного плана. marks — [(k, что)], sounds — [(k, событие, файл, loop, stop_k)]."""
    lines = [{"type": "shot", "shot": name, "speed": speed, "hud": hud, "lang": lang}]
    by_k = {}
    for k, what in marks:
        by_k.setdefault(k, []).append(("mark", what))
    for i, (k, ev, f, loop, stop) in enumerate(sounds):
        by_k.setdefault(k, []).append(("sound", (i, ev, f, loop)))
        if stop is not None:
            by_k.setdefault(stop, []).append(("stop", i))
    for k, t, ct, auto in simulate(n, want, freeze_at, freeze_frames):
        lines.append({"type": "frame", "shot": name, "k": k, "t": round(t, 3), "ct": round(ct, 3),
                      "cam": [0.0, 64.0, 0.0, 0.0, 0.0]})
        for what in auto:
            lines.append({"type": "mark", "shot": name, "t": round(t, 3), "what": what})
        for kind, x in by_k.get(k, []):
            if kind == "mark":
                lines.append({"type": "mark", "shot": name, "t": round(t, 3), "what": x})
            elif kind == "sound":
                i, ev, f, loop = x
                lines.append({"type": "sound", "shot": name, "t": round(t, 3), "id": i, "event": ev, "file": f,
                              "pos": [0.0, 64.0, 20.0], "volume": 1.0, "pitch": 1.0, "range": 400.0,
                              "relative": False, "linear": True, "loop": loop, "stream": False, "lp": [1, 1]})
            else:
                lines.append({"type": "stop", "shot": name, "t": round(t, 3), "id": x})
    lines.append({"type": "end", "shot": name, "frames": n})
    return lines


def write_recording(root, shots, size=(64, 36), every=4):
    """Папка записи: timeline.jsonl и кадры (каждый every-й — монтаж берёт ближайший снятый до нужного)."""
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, "timeline.jsonl"), "w", encoding="utf-8") as f:
        for i, lines in enumerate(shots):
            name = lines[0]["shot"]
            d = os.path.join(root, "frames", name)
            os.makedirs(d, exist_ok=True)
            n = lines[-1]["frames"]
            for k in range(0, n, every):
                hue = (i * 37 + k) % 255
                img = Image.new("RGB", size, (hue, 90 + (k * 3) % 120, 255 - hue))
                img.paste((250, 250, 250), (k % size[0], 10, k % size[0] + 6, 26))   # движущийся брусок
                img.save(os.path.join(d, f"{k:05d}.png"), compress_level=1)
            for ln in lines:
                f.write(json.dumps(ln) + "\n")


def snd(name):
    return f"airstrike:sounds/{name}.ogg"


def full_recording():
    """Все планы, что берут трейлер и тизер, с нужными им отметками и звуками."""
    slow = lambda lo, a, b: (lambda k: lo if a <= k < b else 1.0)  # noqa: E731
    S = []
    S.append(shot_lines("cold_open", 300, slow(0.3, 120, 170), marks=[(230, "gone:airstrike:drone")],
                        sounds=[(0, "airstrike:drone.engine", snd("drone_engine"), True, 232),
                                (236, "airstrike:blast.near", snd("blast_near_1"), False, None)]))
    for name in ("dawn_city", "dawn_tower", "operator", "ruins"):
        S.append(shot_lines(name, 300))
    S.append(shot_lines("scope", 240, hud=True))
    S.append(shot_lines("map", 420, hud=True))
    S.append(shot_lines("launch_missile", 360, sounds=[(120, "airstrike:launch.booster", snd("launch_booster_1"), False, None)]))
    S.append(shot_lines("missile_tower", 600, slow(0.2, 150, 999), freeze_at=60, freeze_frames=170,
                        sounds=[(0, "airstrike:missile.engine", snd("missile_engine"), True, 300),
                                (290, "airstrike:blast.near", snd("blast_near_2"), False, None)]))
    S.append(shot_lines("launch_drone", 360, speed=0.5, want=lambda k: 0.5,
                        sounds=[(100, "airstrike:launch.booster", snd("launch_booster_2"), False, None)]))
    S.append(shot_lines("boost", 200, speed=0.4, want=lambda k: 0.4,
                        sounds=[(0, "airstrike:booster.engine", snd("booster_engine"), True, None)]))
    S.append(shot_lines("drone_city", 300, sounds=[(0, "airstrike:drone.engine", snd("drone_engine"), True, None)]))
    S.append(shot_lines("impact_drone", 600, slow(0.3, 100, 999), freeze_at=60, freeze_frames=150,
                        sounds=[(290, "airstrike:blast.near", snd("blast_near_3"), False, None)]))
    S.append(shot_lines("loiter_launch", 300, speed=0.5, want=lambda k: 0.5,
                        sounds=[(90, "airstrike:loiter.launch", snd("loiter_launch_1"), False, None)]))
    S.append(shot_lines("loiter_strike", 360, speed=0.8, want=lambda k: 0.8, marks=[(250, "gone:airstrike:loiter")],
                        sounds=[(252, "airstrike:blast.near", snd("blast_near_4"), False, None)]))
    S.append(shot_lines("rocket_launch", 300, speed=0.7, want=lambda k: 0.7,
                        sounds=[(80, "airstrike:rocket.launch", snd("rocket_launch_1"), False, None)]))
    S.append(shot_lines("rocket_impact", 360, speed=0.6, want=lambda k: 0.6, marks=[(150, "gone:airstrike:rocket")],
                        sounds=[(152, "airstrike:rocket.blast", snd("rocket_blast_1"), False, None)]))
    S.append(shot_lines("missile_camera", 400, speed=0.2, want=lambda k: 0.2, hud=True,
                        marks=[(60, "video"), (200, "close"), (380, "exit")]))
    S.append(shot_lines("bomb_bay", 400, speed=0.25, want=lambda k: 0.25, marks=[(200, "release")]))
    S.append(shot_lines("bomb_impact", 600, lambda k: 0.3 if k >= 100 else 0.75, speed=0.75, freeze_at=50, freeze_frames=150,
                        sounds=[(300, "airstrike:bomb.impact", snd("bomb_impact"), False, None)]))
    S.append(shot_lines("swarm_night", 360, marks=[(150, "gone:airstrike:drone"), (200, "gone:airstrike:drone")]))
    S.append(shot_lines("grad_night", 300, speed=0.7, want=lambda k: 0.7, marks=[(120, "gone:airstrike:rocket")]))
    S.append(shot_lines("barrage", 480, marks=[(100 + 40 * i, "gone:airstrike:cruise_missile") for i in range(5)],
                        sounds=[(102 + 40 * i, "airstrike:blast.far", snd(f"blast_far_{i + 1}"), False, None) for i in range(5)]))
    S.append(shot_lines("siren", 300, hud=True, sounds=[(0, "airstrike:siren", snd("siren"), True, None)]))
    S.append(shot_lines("icbm", 600, sounds=[(60, "airstrike:nuke.launch", snd("nuke_launch"), False, None)]))
    S.append(shot_lines("flash", 300, hud=True, marks=[(40, "detonation")]))
    S.append(shot_lines("wave_street", 220, speed=0.5, want=lambda k: 0.5,
                        sounds=[(48, "airstrike:nuke.roar", snd("nuke_roar"), False, None)]))
    S.append(shot_lines("wave_port", 220, speed=0.5, want=lambda k: 0.5,
                        sounds=[(48, "airstrike:nuke.roar", snd("nuke_roar"), False, None)]))
    S.append(shot_lines("wave_hill", 300, hud=True, sounds=[(60, "airstrike:nuke.crack", snd("nuke_crack"), False, None)]))
    S.append(shot_lines("mushroom", 400, speed=4, want=lambda k: 4.0))
    S.append(shot_lines("fallout", 340, hud=True, sounds=[(20 * i, "airstrike:geiger.click", snd(f"geiger_click_{i % 4 + 1}"), False, None)
                                                            for i in range(16)]))
    return S


# ---------------------------------------------------------------- время плана

@pytest.fixture
def ramp_shot(tmp_path):
    """План: 1.0 → замедление 0.25 с кадра 60, стоп-кадр на 30-м тике на 90 кадров."""
    lines = shot_lines("ramp", 400, lambda k: 0.25 if k >= 60 else 1.0, freeze_at=30, freeze_frames=90,
                       marks=[(20, "gone:airstrike:drone")],
                       sounds=[(10, "airstrike:blast.near", snd("blast_near_1"), False, None)])
    write_recording(str(tmp_path), [lines])
    return edit.load_recording(str(tmp_path))["ramp"]


def test_world_time_follows_frames(ramp_shot):
    s = ramp_shot
    assert s.count == 400
    # до замедления — 1/3 тика на кадр
    assert s.world(30 / FPS) == pytest.approx(10.0, abs=1e-3)
    assert s.rate(10 / FPS) == pytest.approx(1.0, abs=0.01)     # журнал пишет t с тремя знаками
    # в стоп-кадре время мира стоит, скорость 0
    f0, f1 = s.freezes()[0]
    mid = (f0 + f1) / 2
    assert s.rate(mid) == 0.0
    assert s.world(f0 + 0.1) == pytest.approx(30.0, abs=1e-3)
    assert s.world(f1 - 0.1) == pytest.approx(30.0, abs=1e-3)
    assert f1 - f0 == pytest.approx(90 / FPS, abs=1.5 / FPS)


def test_video_of_tick_first_and_last_on_freeze(ramp_shot):
    s = ramp_shot
    first = s.video_of_tick(30.0)
    last = s.video_of_tick(30.0, last=True)
    f0, f1 = s.freezes()[0]
    assert first == pytest.approx(f0, abs=1.5 / FPS)
    assert last == pytest.approx(f1 - 1 / FPS, abs=1.5 / FPS)
    # обратное отображение вне стоп-кадра
    for v in (0.2, 0.4, 5.0):
        assert s.video_of_tick(float(s.world(v))) == pytest.approx(v, abs=1e-6)


def test_freeze_marks_match_plateau(ramp_shot):
    """Стоп-кадр по отметкам совпадает со стоп-кадром по времени мира (запись без отметок)."""
    s = ramp_shot
    by_marks = s.freezes()
    s.marks = [m for m in s.marks if m[2] not in ("freeze", "unfreeze")]
    by_plateau = s.freezes()
    assert len(by_marks) == len(by_plateau) == 1
    assert by_marks[0][0] == pytest.approx(by_plateau[0][0], abs=1 / FPS)
    assert by_marks[0][1] == pytest.approx(by_plateau[0][1], abs=1 / FPS)


def test_legacy_recording_without_ct(tmp_path):
    lines = shot_lines("old", 120, lambda k: 0.5, speed=0.5)
    for ln in lines:
        ln.pop("ct", None)
    write_recording(str(tmp_path), [lines])
    s = edit.load_recording(str(tmp_path))["old"]
    assert np.allclose(s.ct, s.t)
    assert s.rate(1.0) == pytest.approx(0.5, abs=1e-3)


# ---------------------------------------------------------------- якоря

def test_anchors(ramp_shot):
    s = ramp_shot
    f0, f1 = s.freezes()[0]
    assert edit.anchor(s, "mark:freeze") == pytest.approx(f0 - 1 / FPS, abs=1e-9)
    # «freeze» не совпадает с «unfreeze» (раньше якорь искал подстроку)
    assert edit.anchor(s, "mark:unfreeze") == pytest.approx(f1 - 1 / FPS, abs=1e-9)
    assert edit.anchor(s, "mark:unfreeze+0.5") == pytest.approx(f1 - 1 / FPS + 0.5, abs=1e-9)
    assert edit.anchor(s, "mark:gone") == pytest.approx(20 / FPS)
    assert edit.anchor(s, "mark:gone:airstrike:drone") == pytest.approx(20 / FPS)
    assert edit.anchor(s, "mark:gone#-1-0.25") == pytest.approx(20 / FPS - 0.25)
    assert edit.anchor(s, "sound:blast-1") == pytest.approx(10 / FPS - 1)
    assert edit.anchor(s, "tick:5") == pytest.approx(15 / FPS, abs=1e-6)
    assert edit.anchor(s, "slowest") == pytest.approx(f0, abs=1.5 / FPS)
    assert edit.anchor(s, "end-1") == pytest.approx(400 / FPS - 1)
    assert edit.anchor(s, 1.25) == 1.25
    assert edit.anchor(s, "mark:detonation") is None
    assert edit.anchor(s, "mark:gone#2") is None
    with pytest.raises(ValueError):
        edit.anchor(s, "bogus anchor")


# ---------------------------------------------------------------- сетка и раскладка

def test_snap_units():
    assert edit.snap_units(10, [1, 1]) == [5, 5]
    assert sum(edit.snap_units(17, [2.8, 1.9, 1.9, 4.6, 0.5])) == 17
    assert min(edit.snap_units(5, [10, 0.01, 0.01, 0.01, 0.01])) == 1
    u = edit.snap_units(20, [1, 3])
    assert u == [5, 15]
    with pytest.raises(ValueError):
        edit.snap_units(2, [1, 1, 1])


def test_snap_to_grid():
    assert edit.snap_to_grid(1.0, 0.1, 0.5) == pytest.approx(1.1)
    assert edit.snap_to_grid(0.84, 0.1, 0.5) == pytest.approx(0.6)


def on_grid(t, t0, unit):
    return abs((t - t0) / unit - round((t - t0) / unit)) < 1e-6


@pytest.mark.parametrize("key", sorted(edit.MUSICS))
def test_trailer_layout_on_beats(key):
    m = edit.MUSICS[key]
    cut = edit.trailer_edit(m)
    beat = m.beat_len
    unit = m.snap * beat
    a_start = cut.music[0].trailer_t
    b_start = cut.music[1].trailer_t
    # вспышка и логотип — ровно на своих долях музыки части B
    seg3 = (m.b3[1] - m.b3[0]) * beat
    assert cut.marks["flash"] == pytest.approx(b_start + seg3 + (m.flash - m.b4) * beat)
    assert cut.marks["title"] == pytest.approx(b_start + seg3 + (m.title - m.b4) * beat)
    assert cut.marks["drop"] == pytest.approx(a_start + (m.drop - m.a) * beat)
    for it in cut.items:
        if a_start - 1e-9 <= it.start <= cut.music[0].trailer_t + (m.a_end - m.a) * beat + 1e-9:
            assert on_grid(it.start, a_start, unit), it
        if it.start >= b_start - 1e-9:
            assert on_grid(it.start, b_start, unit), it
    # смежность, длина ~1:40, отрезки не длиннее ~3 с (кроме тишины, вспышки и последнего кадра)
    for a, b in zip(cut.items, cut.items[1:]):
        assert b.start == pytest.approx(a.start + a.dur)
    assert 85 < cut.total < 115, cut.total
    long_ok = {"barrage", "blackout", "flash", "missile_tower", "fallout", "siren", "icbm"}
    for it in cut.items:
        if isinstance(it, edit.Clip) and it.shot not in long_ok:
            assert it.dur <= 3.1, (it.shot, it.dur)
    # куски B встык с наплывом на доле стыка
    p3, p4 = cut.music[1], cut.music[2]
    assert p4.trailer_t == pytest.approx(p3.trailer_t + seg3)
    assert p3.xout > 0 and p4.xin > 0


def test_teaser_is_30s_and_on_beats():
    m = edit.MUSICS["eyes"]
    cut = edit.teaser_edit(m)
    assert cut.total == pytest.approx(30.0, abs=m.snap * m.beat_len)
    assert cut.marks["title"] == pytest.approx((m.title - m.teaser) * m.beat_len)
    for it in cut.items:
        assert on_grid(it.start, 0.0, m.snap * m.beat_len)


def test_music_grid_matches_measured_onsets():
    """Опорные доли «Eyes in the Void» — у сильных долей, найденных анализом онсетов файла (± 25 мс)."""
    m = edit.MUSICS["eyes"]
    measured = {m.hit: 46.312, m.drop: 48.170, m.flash: 182.950, m.title: 192.157, m.b4: 177.400, m.b3[0]: 159.858}
    for n, t in measured.items():
        assert m.t(n) == pytest.approx(t, abs=0.025), n


def test_resolve_with_real_anchors(tmp_path):
    write_recording(str(tmp_path), full_recording(), every=30)
    shots = edit.load_recording(str(tmp_path))
    m = edit.MUSICS["eyes"]
    cut = edit.sfx_layer(edit.resolve(edit.trailer_edit(m), shots), shots, m)
    mt = next(c for c in cut.items if isinstance(c, edit.Clip) and c.shot == "missile_tower")
    f0, f1 = shots["missile_tower"].freezes()[0]
    assert mt.src <= f0 <= mt.src + mt.dur
    kinds = [h.kind for h in cut.hits]
    for k in ("reverse", "drone", "impact", "sub", "whoosh", "riser", "braam", "tick"):
        assert k in kinds, k
    # стоп-кадр ракеты: обратный свист кончается в начале стоп-кадра, удар — на выходе
    t0, t1 = mt.trailer_time(f0), mt.trailer_time(f1)
    assert any(h.kind == "reverse" and h.at == pytest.approx(t0) for h in cut.hits)
    assert any(h.kind == "impact" and h.at == pytest.approx(t1) for h in cut.hits)
    for c in cut.items:
        if isinstance(c, edit.Clip):
            s = shots[c.shot]
            assert 0 <= c.src and c.src + c.dur * c.rate <= s.duration + 1e-6, c


# ---------------------------------------------------------------- звук

def dominant_freq(y, sr=edit.SR):
    spec = np.abs(np.fft.rfft(y * np.hanning(len(y))))
    return np.argmax(spec) * sr / len(y)


def sine_event(k=0):
    return {"k": k, "id": 0, "event": "test:sine", "file": "x", "pos": [0.0, 64.0, 10.0], "volume": 1.0, "pitch": 1.0,
            "range": 1000.0, "relative": True, "linear": False, "loop": True, "lp": [1, 1]}


def one_shot(tmp_path, want, n=600, **kw):
    write_recording(str(tmp_path), [shot_lines("s", n, want, **kw)], every=50)
    return edit.load_recording(str(tmp_path))["s"]


def render(shot, data, dur, src=0.0):
    c = edit.Clip("s", src, dur)
    c.src, c.start = src, 0.0
    r = edit.render_sound(shot, sine_event(), data, c, (0.0, dur))
    assert r is not None
    i0, left, right = r
    y = np.zeros(int(dur * edit.SR))
    y[i0:i0 + len(left)] = (left + right)[: len(y) - i0]
    return y


SINE = np.sin(2 * np.pi * 1000 * np.arange(edit.SR * 4) / edit.SR).astype(np.float32)


def test_slow_motion_lowers_pitch(tmp_path):
    s = one_shot(tmp_path, lambda k: 0.5, speed=0.5)
    y = render(s, SINE, 3.0, src=1.0)
    assert dominant_freq(y[edit.SR // 2: edit.SR * 2]) == pytest.approx(500, rel=0.02)


def test_normal_and_fast_keep_pitch(tmp_path):
    s = one_shot(tmp_path, lambda k: 1.0)
    assert dominant_freq(render(s, SINE, 2.0)[1000:-1000]) == pytest.approx(1000, rel=0.01)
    s = one_shot(tmp_path / "fast", lambda k: 4.0, speed=4)
    assert dominant_freq(render(s, SINE, 2.0)[1000:-1000]) == pytest.approx(1000, rel=0.01)


def test_pitch_clamped_and_fades_below_quarter(tmp_path):
    s = one_shot(tmp_path, lambda k: 0.18, speed=0.18)
    y = render(s, SINE, 3.0, src=1.0)
    part = y[edit.SR: 2 * edit.SR]
    assert dominant_freq(part) == pytest.approx(250, rel=0.03)      # не ниже SLOW_MIN
    x = (0.18 - edit.SLOW_MUTE) / (edit.SLOW_MIN - edit.SLOW_MUTE)
    expect = x * x * (3 - 2 * x)
    # сумма каналов по центру — амплитуда ×√2, RMS синуса — амплитуда/√2
    assert np.sqrt(np.mean(part ** 2)) == pytest.approx(expect, rel=0.05)
    s = one_shot(tmp_path / "crawl", lambda k: 0.05, speed=0.05)
    assert np.max(np.abs(render(s, SINE, 2.0, src=1.0)[edit.SR:])) < 1e-6


def test_freeze_is_silent_and_resumes_in_place(tmp_path):
    """Стоп-кадр: звук молчит и после него продолжается с того же места файла (без пропуска)."""
    s = one_shot(tmp_path, lambda k: 1.0, n=500, freeze_at=40, freeze_frames=120)
    f0, f1 = s.freezes()[0]
    ramp = np.linspace(0, 1, edit.SR * 8).astype(np.float32)      # «звук» — сама позиция в файле
    c = edit.Clip("s", 0.0, 7.0)
    c.src, c.start = 0.0, 0.0
    e = dict(sine_event(), loop=False)
    i0, left, right = edit.render_sound(s, e, ramp, c, (0.0, 7.0))
    y = (left + right) / math.sqrt(2)          # относительный звук: громкость 1, панорама по центру
    t = (i0 + np.arange(len(y))) / edit.SR
    inside = (t > f0 + 0.15) & (t < f1 - 0.15)
    assert np.max(np.abs(y[inside])) < 1e-6
    # до и после стоп-кадра позиция в файле растёт как время мира: скачка нет (значение «звука» — позиция / 8 с)
    before = y[(t > f0 - 0.2) & (t < f0 - 0.15)].mean()
    after = y[(t > f1 + 0.15) & (t < f1 + 0.2)].mean()
    world_gap = (s.world(f1 + 0.175) - s.world(f0 - 0.175)) / TPS
    assert (after - before) * 8 == pytest.approx(world_gap, abs=0.03)


def test_slow_read_and_gain_curves():
    r = np.array([0.0, 0.05, 0.1, 0.2, 0.25, 0.5, 1.0, 2.0])
    assert list(edit.slow_read(r)) == pytest.approx([0.0, 0.05, 0.25, 0.25, 0.25, 0.5, 1.0, 1.0])
    g = edit.slow_gain(r)
    assert g[0] == g[1] == g[2] == 0.0 and g[4] == g[-1] == 1.0 and 0 < g[3] < 1


def test_read_cubic_exact_on_samples():
    x = np.random.default_rng(1).standard_normal(100).astype(np.float32)
    assert np.allclose(edit.read_cubic(x, np.arange(1, 98, dtype=np.float64)), x[1:98], atol=1e-6)
    assert edit.read_cubic(x, np.array([200.0]))[0] == 0.0


def test_music_placement_crossfade_and_mute():
    m = edit.MUSICS["eyes"]
    sr = edit.SR
    x = np.ones((sr * 60, 2), np.float32)
    tl = edit.Timeline(m)
    tl.add(edit.Black(1.0))
    tl.play((10, 20), (40, None), xfade=0.3)
    tl.run([edit.Black(2.0)] * 3)
    tl.mute(3.0, 3.5)
    tl.stop(fade=0.5, at=tl.t - 0.5)
    cut = tl.cut()
    n = int(cut.total * sr)
    y = edit.render_music(cut, x, n)[:, 0] * edit.mute_env(cut, n)
    assert np.max(np.abs(y[: int(0.99 * sr)])) == 0.0              # до музыки — тишина
    join = 1.0 + 10 * m.beat_len
    j = int(join * sr)
    # равная мощность: на стыке сумма квадратов огибающих — 1 (для некоррелированных фраз), здесь сумма = 2·sin(45°)
    assert y[j] == pytest.approx(math.sqrt(2), abs=0.01)
    assert np.max(np.abs(y[int(3.05 * sr): int(3.45 * sr)])) == 0.0
    assert abs(y[-1]) < 0.01


def test_sfx_synth_deterministic_and_library(tmp_path):
    for kind in ("whoosh", "reverse", "sub", "drone", "impact", "braam", "riser", "tick"):
        a = edit.synth_sfx(kind, 1.0, np.random.default_rng(5))
        b = edit.synth_sfx(kind, 1.0, np.random.default_rng(5))
        assert a.shape == (edit.SR, 2) and np.array_equal(a, b)
        assert 0.5 < np.max(np.abs(a)) <= 1.5
    sf.write(tmp_path / "w.wav", np.ones(24000, np.float32) * 0.5, 24000)
    (tmp_path / "manifest.json").write_text(json.dumps({"whoosh": [{"file": "w.wav", "gain": 0.5, "source": "test", "license": "CC0"}]}))
    lib = edit.SfxLibrary(str(tmp_path))
    y, sync, gain = lib.get("whoosh", 3)
    assert y.shape == (edit.SR, 2) and sync == edit.SR and gain == 0.5
    assert lib.get("braam", 0) is None
    cut = edit.Cut([], 2.0, [], [], hits=[edit.Hit(1.5, "whoosh", 0.7, 1.0, "end")])
    out = edit.render_hits(cut, lib, edit.SR * 2)
    assert out[int(0.6 * edit.SR), 0] == pytest.approx(0.25, abs=1e-3)   # файл кончается на склейке (1.5 с)
    assert out[int(1.6 * edit.SR), 0] == 0.0


def test_look_keeps_black_and_white():
    look = edit.Look(64, 36)
    assert look.lut[:, 0].tolist() == [0, 0, 0]
    assert look.lut[:, 255].tolist() == [255, 255, 255]
    mid = look.lut[:, 128]
    assert abs(int(mid[0]) - int(mid[2])) <= 4                      # оттенок средних тонов слабый


def test_credits_text():
    txt = edit.credits_text(edit.MUSICS["eyes"])
    assert "“Eyes in the Void” by Scott Buckley — www.scottbuckley.com.au, CC BY 4.0" in txt
    assert "Greenfield city map by the Greenfield team — https://www.greenfieldmc.net" in txt
    assert "planetminecraft.com" in txt and "SOUND-CREDITS.md" in txt


# ---------------------------------------------------------------- полный черновик

@pytest.mark.skipif(not os.environ.get("AIRSTRIKE_E2E"), reason="AIRSTRIKE_E2E=1 — полный черновик (минуты)")
def test_full_draft_render(tmp_path):
    import av
    rec = tmp_path / "rec"
    write_recording(str(rec), full_recording(), size=(160, 90), every=6)
    out = tmp_path / "t.mp4"
    subprocess.run([sys.executable, edit.__file__, "--draft", "--rec", str(rec), "--out", str(out), "--jobs", "4"], check=True)
    m = edit.MUSICS["eyes"]
    expect = {"t.mp4": ((960, 540), edit.resolve(edit.trailer_edit(m), edit.load_recording(str(rec))).total),
              "t-teaser.mp4": ((540, 960), edit.teaser_edit(m).total)}
    for name, (size, dur) in expect.items():
        with av.open(str(tmp_path / name)) as f:
            v = f.streams.video[0]
            a = f.streams.audio[0]
            assert (v.codec_context.width, v.codec_context.height) == size
            assert float(f.duration / av.time_base) == pytest.approx(dur, abs=0.1)
            assert a.codec_context.sample_rate == edit.SR and a.codec_context.channels == 2
            assert v.average_rate == FPS
    assert Image.open(tmp_path / "t-thumbnail.png").size == edit.THUMB
    assert "Scott Buckley" in (tmp_path / "t-credits.txt").read_text(encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "make":
        write_recording(sys.argv[2], full_recording(), size=(160, 90), every=6)
        print("синтетическая запись:", sys.argv[2])
        sys.exit(0)
    sys.exit(pytest.main([__file__, "-q", *sys.argv[1:]]))
