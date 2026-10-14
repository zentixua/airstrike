"""Выдача плана: путь камеры по тикам для вывода (трейлер — `camera.jsonl`, превью — камера `path` плана replayscan)
и итог меры, с которым потом сверяется проверка кадров вывода."""
from __future__ import annotations

import json
import os

import numpy as np

from . import camera as C


def poses(result) -> list[dict]:
    out = []
    for i in range(len(result.P)):
        out.append({"t": int(result.spec.a + i), "pos": [round(float(v), 3) for v in result.P[i]],
                    "yaw": round(float(result.yaw[i]), 3), "pitch": round(float(result.pitch[i]), 3),
                    "roll": round(float(result.roll[i]), 3), "fov": round(float(result.fov), 2)})
    return out


def write_camera(result, path: str, header: dict | None = None):
    """camera.jsonl: строка на тик — место глаза, курс, тангаж, крен (плюс — влево, как ключи Flashback; у CineCamera
    трейлера плюс — вправо), угол кадра по вертикали; `header` — первой строкой (тело камеры для вывода)."""
    with open(path, "w", encoding="utf-8") as f:
        if header:
            f.write(json.dumps(header, ensure_ascii=False) + "\n")
        for p in poses(result):
            f.write(json.dumps(p) + "\n")


def path_camera(result, every: int = 1, aim_dist: float = 64.0) -> dict:
    """Камера `path` плана съёмки replayscan (README там): ключи — глаз, точка взгляда и крен через `every` тиков."""
    keys = []
    for i in range(0, len(result.P), every):
        f = C.forward(result.yaw[i], result.pitch[i])
        eye = result.P[i]
        keys.append({"t": int(result.spec.a + i), "eye": [round(float(v), 3) for v in eye],
                     "aim": [round(float(v), 3) for v in eye + f * aim_dist], "roll": round(float(result.roll[i]), 2)})
    return {"kind": "path", "fov": round(float(result.fov), 2), "keys": keys}


def predicted(result) -> list[dict]:
    """Что мера ждёт от каждого кадра (для сверки с qa.jsonl вывода): место объекта в кадре, доля, видимость."""
    p = result.parts
    out = []
    if "_u" not in p:
        return out
    for i in range(len(result.P)):
        if not p["_have"][i]:
            continue
        out.append({"t": int(result.spec.a + i), "u": round(float(p["_u"][0][i]), 4), "v": round(float(p["_v"][0][i]), 4),
                    "share": round(float(p["_share"][0][i]), 5), "vis": round(float(p["_vis"][0][i]), 3),
                    "occluded": round(float(p["_occ"][0][i]), 3), "smoke": round(float(p["_smoke"][0][i]), 3)})
    return out


def write_all(result, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    write_camera(result, os.path.join(out_dir, "camera.jsonl"))
    with open(os.path.join(out_dir, "path-camera.json"), "w", encoding="utf-8") as f:
        json.dump(path_camera(result), f)
    with open(os.path.join(out_dir, "predicted.jsonl"), "w", encoding="utf-8") as f:
        for row in predicted(result):
            f.write(json.dumps(row) + "\n")
    with open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8") as f:
        json.dump(jsonable(result.report), f, indent=1, ensure_ascii=False)


def jsonable(o):
    if isinstance(o, dict):
        return {k: jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    if isinstance(o, np.generic):
        return o.item()
    return o
