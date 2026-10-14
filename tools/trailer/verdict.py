"""Проверка монтажа трейлера по данным игры: каждая склейка — PASS или FAIL с причиной, без просмотра кадров.

Монтаж — список склеек (`Cut`: откуда в дубле, сколько, что за склейка), звук — что из звуков дублей куда легло в
ролике (`Placed`), музыка — где и как громко играет (`Music`). Дубли — `takes.Take` (кадры с камерой и проверкой
каждого кадра, звуки клиента, удары, люди). Правила — по роду склейки (`Cut.kind`):

- `hit` — сцена одного удара: удар объекта в склейке, после подлёта (`lead`), виден в кадре; склейка (или идущие за
  ней склейки того же дубля) держится, пока звук удара не дошёл до камеры, и ещё `tail`; звук удара лёг в ролик;
- `montage` — нарезка (холодное начало, пик): удар объекта в склейке и виден; звук удара доходит позже — он лёг в
  ролик поверх следующих склеек (J/L), а не пропал и не оборвался тишиной;
- `flight` — полёт (FPV за снарядом, пролёт): объект в кадре и виден в доле кадров `need`, не мельче нормы;
- `launch` — пуск: звук старта в склейке, снаряд потом в кадре;
- `siren` — тревога: сирена слышна с начала, держится `siren_hold` секунд без музыки;
- `text` — текст на экране (чат, тревога, пульт): каждая строка — не меньше времени чтения;
- `scene` — спокойный план: только общие правила; `onboard` — с борта снаряда (поворот камеры — его, без пределов);
- `nuke`, `wave` — подрыв и волна (тряску камеры даёт модель мода, без пределов поворота; дым — сам объект);
- `fill` — чёрный или белый кадр.

Общие правила для склейки с дублем: у объекта есть проверка кадров (у живого плана без неё — FAIL), камера без рывков
и объект на экране без дрожи (вторые разности по кадрам), мир построен, LOD DH со своей глубиной, кадр не в дыму;
`fight` у склейки — идёт бой: бойцы ближе, чем слышен выстрел, — выстрелы должны быть в ролике. Окно дубля не повторяется в двух
склейках. Пороги — `RULES` (у склейки — свои в `Cut.need`).
"""
import math
from dataclasses import dataclass, field

import takes as T

FREE_CAMERA = ("onboard", "nuke", "wave")   # камера, которую ведёт снаряд или модель мода

RULES = {
    "lead": 0.5,            # hit: удар не раньше стольких секунд от начала склейки (виден подлёт)
    "tail": 0.5,            # hit: после прихода звука удара склейка держится ещё столько
    "montage_lead": 0.08,   # montage: удар не раньше (вспышка — не первый кадр)
    "montage_after": 0.15,  # montage: после удара в склейке не меньше (огненный шар успевает читаться)
    "key_window": 0.6,      # удар виден: кадры [удар, удар + столько] в склейке
    "key_share": 0.8,       # …в такой доле
    "flight_share": 0.7,    # flight: объект в кадре и виден в такой доле кадров склейки
    "min_share": 0.012,     # объект не мельче (доля высоты кадра), медиана по видимым кадрам
    "hero_share": 0.04,     # главный объект (need.hero) — не мельче
    "hero_light": 0.3,      # …освещён не слабее (FrameQa light, 0–1)
    "hero_dark": 0.3,       # …в темноте или в своём дыму — не дольше такой доли кадров
    "launch_lead": 0.05,    # launch: звук старта не раньше стольких секунд от начала склейки (склейка — от звука старта)
    "launch_after": 0.6,    # launch: после старта в склейке не меньше
    "siren_pre": 0.5,       # siren: сирена началась не позже стольких секунд после начала склейки
    "siren_hold": 5.0,      # siren: сирена держится столько без музыки
    "music_quiet": 0.06,    # громкость музыки, которая считается тишиной
    "read_base": 0.6,       # text: строка держится не меньше read_base + символов / read_cps
    "read_cps": 15.0,
    "read_min": 1.5,
    "cam_speed": 120.0,     # курс и тангаж камеры: 95-й процентиль скорости поворота, °/с
    "cam_jerk": 1.2,        # рывок взгляда: 95-й процентиль второй разности по кадрам, °/кадр²
    "jitter": 4.0,          # дрожь объекта на экране: 95-й процентиль второй разности, % полукадра/кадр²
    "ready": 0.98,          # мир построен в такой доле кадров (0 — не проверять, облако)
    "smoky": 0.25,          # кадров, где больше половины кадра за дымом, — не больше доли
    "fight_people": 3,      # fight: бойцов ближе, чем слышен выстрел, — с такого числа бой слышен у камеры
    "gun_earshot": 80.0,    # …слышен: дальность звука выстрела АК Point Blank (журнал клиента: range 80, линейно)
    "fight_frames": 0.5,    # …в такой доле кадров
    "gun_rate": 1.0,        # fight: выстрелов в ролике не меньше стольких в секунду склейки
    "hit_sound_gain": 0.003,  # звук удара считается легшим в ролик, если громкость у камеры выше
}


@dataclass
class Cut:
    start: float            # время ролика, с
    dur: float
    kind: str
    take: str = None        # имя дубля (план); None — заливка
    src: float = 0.0        # откуда в дубле, с
    rate: float = 1.0
    plan: int = 0
    label: str = ""
    need: dict = field(default_factory=dict)
    fight: bool = False
    text: tuple = ()        # строки на экране (text)

    @property
    def end(self):
        return self.start + self.dur

    @property
    def src_end(self):
        return self.src + self.dur * self.rate

    def at(self, take_t):
        """Время ролика для момента дубля."""
        return self.start + (take_t - self.src) / self.rate

    def rule(self, name):
        return self.need.get(name, RULES[name])


@dataclass
class Placed:
    """Звук дубля в ролике: от t0 до t1 (время ролика), громкость у камеры (наибольшая)."""
    take: str
    sound: int
    event: str
    kind: str
    t0: float
    t1: float
    gain: float
    src_t0: float           # когда звук начался в дубле


@dataclass
class Music:
    t0: float
    t1: float
    gain: float


@dataclass
class Finding:
    level: str              # FAIL | WARN
    rule: str
    text: str


@dataclass
class CutVerdict:
    cut: Cut
    findings: list = field(default_factory=list)
    data: dict = field(default_factory=dict)

    @property
    def ok(self):
        return not any(f.level == "FAIL" for f in self.findings)

    def fail(self, rule, text):
        self.findings.append(Finding("FAIL", rule, text))

    def warn(self, rule, text):
        self.findings.append(Finding("WARN", rule, text))


def visible(q):
    """Объект кадра снят, как у `FrameQa.Frame.visible` и его итога: в кадре, виден лучами, не в облаках и тумане, в
    построенной секции, не за дымом. Строка HudQa (живой план) — любой снаряд плана в кадре и открыт."""
    if q is None:
        return False
    if "flights" in q:
        return any(f.get("in") and f.get("open") for f in q["flights"])
    return (bool(q.get("in")) and q.get("seen", 0) >= 0.4 and not q.get("cloud") and not q.get("far")
            and q.get("drawn", True) and q.get("clear", 1) >= 0.5)


def p95(v):
    v = sorted(abs(x) for x in v)
    return v[int(0.95 * (len(v) - 1))] if v else 0.0


def median(v):
    v = sorted(v)
    return v[len(v) // 2] if v else 0.0


def music_gain(music, t0, t1):
    return max((m.gain for m in music if m.t0 < t1 and m.t1 > t0), default=0.0)


def ts(x):
    return f"{int(x // 60)}:{x % 60:05.2f}"


class Checker:
    def __init__(self, cuts, takes, placed=(), music=(), silences=(), ready=None):
        self.cuts = sorted(cuts, key=lambda c: c.start)
        self.takes = takes
        self.placed = list(placed)
        self.music = list(music)
        self.silences = sorted(silences)
        if ready is not None:
            RULES["ready"] = ready

    def run(self):
        out = [self.check(i, c) for i, c in enumerate(self.cuts)]
        self.repeats(out)
        return out

    # ------------------------------------------------------------ общие
    def check(self, i, c):
        v = CutVerdict(c)
        if c.kind == "fill" or c.take is None:
            return v
        t = self.takes.get(c.take)
        if t is None:
            v.fail("data", f"нет дубля {c.take}")
            return v
        frames = t.frames_in(c.src, c.src_end)
        v.data["frames"] = len(frames)
        need_frames = max(1, int(c.dur * T.FPS * 0.9))
        if len(frames) < need_frames:
            v.fail("data", f"в дубле {len(frames)} кадров окна {c.src:.2f}–{c.src_end:.2f} с, нужно {need_frames}")
        subject_kinds = ("hit", "montage", "flight", "launch")
        has_qa = any(f.qa for f in frames)
        if c.kind in subject_kinds and not has_qa:
            v.fail("data", "нет проверки кадров объекта (живой план без проверки)")
        if frames:
            self.motion(v, c, t, frames)
            self.world(v, c, t, frames)
        getattr(self, "k_" + c.kind, lambda *a: None)(v, c, t, frames)
        if c.fight:
            self.fight(v, c, t, frames)
        return v

    def motion(self, v, c, t, frames):
        """Камера: скорость поворота и рывок по кадрам; объект: дрожь на экране, пока он в кадре. Камера с борта
        снаряда (`onboard`) и планы подрыва и волны (тряску даёт модель мода) — без пределов поворота."""
        speed, jerk, jit = [], [], []
        for p, q in zip(frames, frames[1:]):
            dt = (q.t - p.t) or 1 / T.FPS
            dy = (q.cam[3] - p.cam[3] + 180) % 360 - 180
            dp = q.cam[4] - p.cam[4]
            speed.append(math.hypot(dy, dp) / dt)
        for p, q, r in zip(frames, frames[1:], frames[2:]):
            dy0 = (q.cam[3] - p.cam[3] + 180) % 360 - 180
            dy1 = (r.cam[3] - q.cam[3] + 180) % 360 - 180
            jerk.append(math.hypot(dy1 - dy0, (r.cam[4] - q.cam[4]) - (q.cam[4] - p.cam[4])))
            if p.qa and q.qa and r.qa and p.qa.get("in") and q.qa.get("in") and r.qa.get("in") and "x" in p.qa:
                jit.append(100 * math.hypot(r.qa["x"] - 2 * q.qa["x"] + p.qa["x"], r.qa["y"] - 2 * q.qa["y"] + p.qa["y"]))
        v.data.update(cam_speed=p95(speed), cam_jerk=p95(jerk), jitter=p95(jit))
        if c.kind in FREE_CAMERA:
            speed, jerk = [], []
        if speed and p95(speed) > c.rule("cam_speed"):
            v.fail("camera", f"камера поворачивается {p95(speed):.0f}°/с (95-й процентиль), предел {c.rule('cam_speed'):.0f}")
        if jerk and p95(jerk) > c.rule("cam_jerk"):
            v.fail("camera", f"рывки взгляда {p95(jerk):.2f}°/кадр², предел {c.rule('cam_jerk'):.2f}")
        if len(jit) >= 5 and p95(jit) > c.rule("jitter"):
            v.fail("jitter", f"объект дрожит на экране {p95(jit):.1f} % полукадра/кадр², предел {c.rule('jitter'):.1f}")

    def world(self, v, c, t, frames):
        qa = [f.qa for f in frames if f.qa and "ready" in f.qa]
        if not qa:
            return
        if c.rule("ready") > 0:
            ready = sum(1 for q in qa if q.get("ready")) / len(qa)
            if ready < c.rule("ready"):
                v.fail("world", f"мир достраивается в кадре ({100 * (1 - ready):.0f} % кадров)")
        dh = sum(1 for q in qa if q.get("dh", 0) < 0)
        if dh:
            v.fail("world", f"LOD DH с глубиной мира в {dh} кадрах")
        smoky = [q for q in qa if q.get("smoke", 0) > 0.5]
        if c.kind not in ("nuke", "wave") and len(smoky) / len(qa) > c.rule("smoky"):
            v.fail("smoke", f"больше половины кадра за дымом в {100 * len(smoky) / len(qa):.0f} % кадров")

    # ------------------------------------------------------------ удар
    def subject_impact(self, c, t):
        """Удар объекта склейки: у вывода — удар объекта плана, у живого плана — первый удар в окне склейки."""
        imps = t.impacts()
        subj = [i for i in imps if i.subject]
        if subj:
            return subj[0], imps
        inside = [i for i in imps if c.src <= i.t <= c.src_end]
        return (inside[0] if inside else None), imps

    def key_visible(self, c, t, imp, window):
        """Доля кадров [удар, удар + window] в склейке, где удар виден (FrameQa: объект — удар после тика удара;
        HudQa: удар в кадре и открыт)."""
        fr = t.frames_in(imp.t, min(imp.t + window, c.src_end))
        if not fr:
            return 0.0, 0
        ok = 0
        for f in fr:
            q = f.qa or {}
            if "impacts" in q:
                near = [h for h in q["impacts"] if imp.pos is None or T.dist(h["at"], imp.pos) < 6]
                ok += any(h.get("in") and h.get("open") for h in near)
            else:
                ok += visible(q)
        return ok / len(fr), len(fr)

    def sound_placed(self, c, t, imp):
        """Звук удара в ролике: звук взрыва этого дубля у места удара, начатый не раньше удара, с громкостью."""
        got = []
        for p in self.placed:
            if p.take != c.take or p.kind != "blast" or p.gain < RULES["hit_sound_gain"]:
                continue
            s = next((s for s in t.sounds if s.id == p.sound), None)
            if s is None or s.t < imp.t - 0.05:
                continue
            # взрыв мода звучит у слушателя со стороны удара: свой — у места удара или к приходу фронта
            near = imp.pos is None or T.dist(s.pos, imp.pos) <= 8
            timed = imp.arrival is not None and abs(s.t - imp.arrival) <= T.ARRIVAL_SLACK
            if near or timed:
                got.append(p)
        return got

    def next_silence(self, after):
        return next((s for s in self.silences if s > after + 1e-6), None)

    def group_end(self, c):
        """Конец цепочки склеек того же дубля подряд (сцена в несколько склеек), время дубля."""
        end, last = c.src_end, c
        for d in self.cuts:
            if d.start < c.start + 1e-6:
                continue
            if abs(d.start - last.end) < 1e-3 and d.take == c.take and d.src >= last.src_end - 0.05:
                end, last = d.src_end, d
            elif d.start >= last.end - 1e-3:
                break
        return end, last

    def k_hit(self, v, c, t, frames):
        imp, _ = self.subject_impact(c, t)
        if imp is None:
            v.fail("event", "в дубле нет удара объекта")
            return
        v.data["impact"] = imp.t
        lead = c.rule("lead")
        end, last = self.group_end(c)   # сцена удара бывает в несколько склеек того же дубля подряд
        if not (c.src + lead - 1e-6 <= imp.t <= c.src_end + 1e-6) or imp.t > end - 0.3 + 1e-6:
            v.fail("event", f"удар на {imp.t - c.src:+.2f} с от начала склейки длиной {c.dur:.2f} с "
                            f"(нужно от {lead:.2f} с до {min(c.dur, end - c.src - 0.3):.2f} с)")
            return
        share, n = self.key_visible(c, t, imp, c.rule("key_window"))
        if share < c.rule("key_share"):
            v.fail("visible", f"удар виден в {100 * share:.0f} % кадров после него ({n})")
        if imp.arrival is None:
            v.fail("sound", "время прихода звука удара неизвестно (нет камеры у дубля)")
            return
        v.data["arrival"] = imp.arrival
        need = imp.arrival + c.rule("tail")
        if end < need:
            v.fail("hold", f"склейка кончается за {need - end:.2f} с до прихода звука удара и хвоста "
                           f"(удар в {imp.distance or 0:.0f} блоках, звук через {imp.arrival - imp.t:.2f} с)")
        if not self.sound_placed(c, t, imp):
            v.fail("sound", f"звука удара нет в ролике (должен прийти в {ts(c.at(imp.arrival))})")

    def k_montage(self, v, c, t, frames):
        imp, imps = self.subject_impact(c, t)
        inside = [i for i in imps if c.src + c.rule("montage_lead") <= i.t <= c.src_end - c.rule("montage_after")]
        if imp is None or imp not in inside:
            near = [i for i in imps if c.src - 1 <= i.t <= c.src_end + 1]
            where = (f"удар объекта на {imp.t - c.src:+.2f} с от начала склейки длиной {c.dur:.2f} с" if imp is not None
                     else "у дубля нет удара объекта")
            other = f"; других ударов в окне: {len(inside)}" if inside else ""
            v.fail("event", where + other + ("" if near or imp is not None else "; рядом ударов нет"))
            return
        v.data["impact"] = imp.t
        share, n = self.key_visible(c, t, imp, min(c.rule("key_window"), c.src_end - imp.t))
        if share < c.rule("key_share"):
            v.fail("visible", f"удар виден в {100 * share:.0f} % кадров после него ({n})")
        if imp.arrival is not None:
            v.data["arrival"] = imp.arrival
            arrive = c.at(imp.arrival)
            silence = self.next_silence(c.start)
            if silence is not None and arrive > silence:
                v.fail("sound", f"звук удара придёт в {ts(arrive)}, после обрыва в тишину в {ts(silence)}")
            elif not self.sound_placed(c, t, imp):
                v.fail("sound", f"звука удара нет в ролике (должен прийти в {ts(arrive)}, удар в {imp.distance or 0:.0f} блоках)")

    def k_flight(self, v, c, t, frames):
        qa = [f.qa for f in frames if f.qa and ("in" in f.qa or "flights" in f.qa)]
        if not qa:
            return
        good = [q for q in qa if visible(q)]
        share = len(good) / len(qa)
        v.data["seen"] = share
        if share < c.rule("flight_share"):
            v.fail("visible", f"объект виден в {100 * share:.0f} % кадров склейки, нужно {100 * c.rule('flight_share'):.0f} %")
        self.size(v, c, good)

    def size(self, v, c, good):
        shares = [q["share"] for q in good if "share" in q]   # у HudQa величины нет
        if not shares:
            return
        if c.need.get("hero"):
            self.lit(v, c, good)
        m = median(shares)
        v.data["size"] = m
        lim = c.rule("hero_share") if c.need.get("hero") else c.rule("min_share")
        if m < lim:
            v.fail("size", f"объект {100 * m:.1f} % высоты кадра (медиана), нужно не меньше {100 * lim:.1f} %")

    def lit(self, v, c, good):
        """Главный объект различим глазом: освещён (свет на нём, 0–1: блоки и небо с учётом времени суток) и не
        утонул в своём дыме (свет сквозь дым его же следа, без огня у сопла). Рамка в кадре и лучи по блокам этого не
        видят: МБР v5 ночью (p41, p42) была в кадре и крупной, а на экране — тёмный силуэт в дыму и огонь сопла."""
        rows = [q for q in good if "share" in q]
        if not all("light" in q and "own" in q for q in rows):
            v.fail("data", "у главного объекта нет меры света и своего дыма (проверка кадров до 14.10)")
            return
        dark = sum(1 for q in rows if q["light"] < c.rule("hero_light")) / len(rows)
        smoked = sum(1 for q in rows if q["own"] < 0.5) / len(rows)
        v.data.update(dark=dark, own_smoke=smoked)
        if dark > c.rule("hero_dark"):
            v.fail("light", f"главный объект в темноте в {100 * dark:.0f} % кадров (свет ниже {c.rule('hero_light'):.2f})")
        if smoked > c.rule("hero_dark"):
            v.fail("smoke", f"главный объект в своём дыму в {100 * smoked:.0f} % кадров")

    def k_launch(self, v, c, t, frames):
        starts = [s for s in t.launches() if c.src + c.rule("launch_lead") - 0.02 <= s[0] <= c.src_end - c.rule("launch_after")]
        if not starts:
            any_ = [s[0] - c.src for s in t.launches()]
            inside = [x for x in any_ if -0.02 <= x <= c.dur]
            if inside:
                v.fail("event", f"старт на {inside[0]:+.2f} с, а после старта в склейке {c.dur - inside[0]:.2f} с, "
                                f"нужно {c.rule('launch_after'):.2f} с: схода снаряда не видно")
            else:
                v.fail("event", "звука старта в склейке нет" + (f" (старты на {', '.join(f'{x:+.2f}' for x in any_[:4])} с)" if any_ else ""))
            return
        t0 = starts[0][0]
        after = [f.qa for f in frames if f.t >= t0 and f.qa and "in" in f.qa]
        if after:
            good = [q for q in after if visible(q)]
            if len(good) / len(after) < 0.6:
                v.fail("visible", f"снаряд после старта виден в {100 * len(good) / len(after):.0f} % кадров")
            self.size(v, c, good)

    def k_siren(self, v, c, t, frames):
        sirens = sorted((s for s in t.sounds if s.kind == "siren"), key=lambda s: s.t)
        if not sirens:
            v.fail("event", "сирены в дубле нет")
            return
        s0 = sirens[0]
        if s0.t > c.src + c.rule("siren_pre") or s0.t < c.src - 0.3 and c.src - s0.t > 0.3:
            v.fail("event", f"сирена начинается на {s0.t - c.src:+.2f} с от начала склейки: слышна не с начала")
        start = c.at(max(s0.t, c.src))
        # тревога — эта склейка и следующие склейки тревоги подряд
        end, last = c.end, c
        for d in self.cuts:
            if d.start > c.start and abs(d.start - last.end) < 1e-3 and d.kind == "siren":
                end, last = d.end, d
        hold = end - start
        v.data["siren_hold"] = hold
        if hold < c.rule("siren_hold") - 1e-6:
            v.fail("hold", f"сирена в ролике {hold:.2f} с, нужно {c.rule('siren_hold'):.1f} с")
        loud = music_gain(self.music, start, start + min(hold, c.rule("siren_hold")))
        if loud > c.rule("music_quiet"):
            first = min((m.t0 for m in self.music if m.t0 < start + c.rule("siren_hold") and m.t1 > start and m.gain > c.rule("music_quiet")),
                        default=start)
            v.fail("music", f"музыка входит в {ts(max(first, start))}, через {max(first, start) - start:.2f} с после начала сирены")
        if not any(p.take == c.take and p.kind == "siren" and p.gain > 0.01 for p in self.placed):
            v.fail("sound", "сирены нет в ролике")

    def k_text(self, v, c, t, frames):
        if not c.text:
            return
        # строки текста склейки — вся цепочка склеек этой сцены с тем же дубль-планом (вперемешку с другими — нет)
        for line in c.text:
            need = max(c.rule("read_min"), c.rule("read_base") + len(line) / c.rule("read_cps"))
            if c.dur + 1e-6 < need:
                v.fail("text", f"строка «{line[:40]}» на экране {c.dur:.2f} с, для чтения нужно {need:.1f} с")

    def fight(self, v, c, t, frames):
        """Бой у камеры: бойцы журнала ближе, чем слышен выстрел (дальность его звука в дубле, иначе gun_earshot), —
        игра у камеры сыграла бы выстрелы, в кадре стрелок или нет, поэтому они должны быть в ролике. Дальше —
        выстрелов не слышно и в игре (v5: p31, p32 — 310–430 блоков, p39 — 190–230)."""
        reach = max((s.range for s in t.sounds if s.kind == "gun" and s.linear), default=c.rule("gun_earshot"))
        heard = 0
        for f in frames[::3]:
            n = sum(1 for name, p in t.people_at(f.t).items() if name != "Sokol" and T.dist(f.cam, p[:3]) <= reach)
            heard += n >= c.rule("fight_people")
        share = heard / max(1, len(frames[::3]))
        v.data["fighters"] = share
        if share < c.rule("fight_frames"):
            return
        guns = [p for p in self.placed if p.kind == "gun" and p.t0 < c.end and p.t1 > c.start and p.gain > 0.01]
        rate = len(guns) / max(c.dur, 1e-3)
        v.data["gun_rate"] = rate
        if rate < c.rule("gun_rate"):
            v.fail("gunfire", f"бой ближе {reach:.0f} блоков от камеры ({100 * share:.0f} % кадров), а выстрелов в ролике "
                              f"{len(guns)} ({rate:.1f}/с, нужно {c.rule('gun_rate'):.1f}/с)")

    # ------------------------------------------------------------ весь монтаж
    def repeats(self, out):
        """Одно и то же окно дубля в двух склейках — зритель видит повтор (пик v5: стадион p39a пять раз)."""
        seen = {}
        for v in out:
            c = v.cut
            if c.take is None:
                continue
            for d, w in seen.get(c.take, []):
                lo, hi = max(c.src, d.src), min(c.src_end, d.src_end)
                if hi - lo > 0.1:
                    v.fail("repeat", f"те же кадры {c.take} {lo:.2f}–{hi:.2f} с уже были в {ts(d.start)}")
            seen.setdefault(c.take, []).append((c, v))


def report(verdicts, title="Проверка монтажа"):
    """Таблица: время, склейка, итог, причины."""
    rows = [f"# {title}", "", "| Время | План | Склейка | Род | Итог | Почему |", "|---|---|---|---|---|---|"]
    for v in verdicts:
        c = v.cut
        why = "; ".join(f.text for f in v.findings if f.level == "FAIL") or ""
        warn = "; ".join(f.text for f in v.findings if f.level == "WARN")
        if warn:
            why = (why + "; " if why else "") + "внимание: " + warn
        rows.append(f"| {ts(c.start)}–{ts(c.end)} | {c.plan} | {c.label or c.take or '—'} | {c.kind} | "
                    f"{'PASS' if v.ok else 'FAIL'} | {why} |")
    n = sum(1 for v in verdicts if v.cut.take)
    bad = sum(1 for v in verdicts if v.cut.take and not v.ok)
    rows += ["", f"Склеек с дублем: {n}, FAIL: {bad}."]
    return "\n".join(rows) + "\n"
