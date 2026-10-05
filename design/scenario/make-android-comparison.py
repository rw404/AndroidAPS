#!/usr/bin/env python3
"""Render a provenance-aware gallery from Android captures, without editing pixels.

Expected JSON: {sourceCommit, apkSha256, captures: [{name, file, sha256, ...}]}.
Copy raw PNGs into SCREENSHOT_DIR/before/ and SCREENSHOT_DIR/after/ first.
Default mode requires an actual, hash-checked after/home PNG. --allow-pending is
only for local preview, clearly labelled pending; it is not a publishable result.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import struct
from urllib.parse import quote

HEX40 = re.compile(r"^[0-9a-f]{40}$", re.I)
HEX64 = re.compile(r"^[0-9a-f]{64}$", re.I)
PNG = b"\x89PNG\r\n\x1a\n"
LABELS = {
    "home": "Сейчас", "main": "Сейчас", "now": "Сейчас",
    "food": "Еда и порция", "meal": "Еда и порция", "portion": "Порция",
    "wizard": "Штатный калькулятор", "calculator": "Штатный калькулятор",
    "confirmation": "Отдельное подтверждение", "confirm": "Отдельное подтверждение",
    "history": "История", "settings": "Настройки", "dark": "Тёмная тема",
    "home-fresh": "Сейчас · свежие данные",
    "home-stale": "Сейчас · устаревшие данные",
    "home-metrics": "Сейчас · показатели",
    "food-keyboard-normal": "Еда · открытая клавиатура",
    "food-results": "Еда · выбор продукта",
    "food-portion105g": "Еда · проверка порции",
    "meal-review": "Еда · сводка перед расчётом",
    "wizard-24g": "Калькулятор · рекомендация",
    "wizard-profile": "Калькулятор · профиль",
    "wizard-profile-details": "Калькулятор · параметры профиля",
    "native-final-confirmation-cancel": "Подтверждение · проверка отмены",
    "native-final-confirmation-virtual": "Подтверждение · VirtualPump",
    "native-history-virtual": "История · виртуальные записи",
    "native-settings": "Настройки",
    "native-settings-search": "Настройки · поиск",
    "home-dark": "Сейчас · тёмная тема",
    "native-home-dark": "Сейчас · тёмная тема",
    "home-font200": "Сейчас · шрифт 200%",
    "native-home-font200": "Сейчас · шрифт 200%",
    "native-home-font200-dark": "Сейчас · шрифт 200%, тёмная тема",
    "wizard-font200": "Калькулятор · шрифт 200%",
}


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def load_json(path: Path) -> dict:
    result = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(result, dict):
        raise ValueError(f"JSON must contain an object: {path}")
    return result


def relative_url(path: Path, output: Path) -> str:
    return quote(os.path.relpath(path, output.parent), safe="/._-")


def contains(root: Path, target: Path) -> bool:
    return target.resolve().is_relative_to(root.resolve())


def screenshot_path(record: dict, category: str, directory: Path) -> Path:
    raw = record.get("relativeFile") or record.get("repositoryFile") or record.get("file")
    if not isinstance(raw, str) or not raw:
        raise ValueError(f"{category} capture has no file path")
    value = Path(raw)
    candidates = []
    if value.is_absolute() and contains(directory, value):
        candidates.append(value)
    if not value.is_absolute():
        candidates.append(directory / value)
        # A repo-relative path can also identify a file beneath screenshot-dir.
        if directory.name in value.parts:
            position = value.parts.index(directory.name)
            candidates.append(directory.joinpath(*value.parts[position + 1:]))
    candidates.extend([directory / category / value.name, directory / value.name])
    existing = []
    for candidate in candidates:
        resolved = candidate.resolve()
        if contains(directory, resolved) and resolved.is_file() and resolved not in existing:
            existing.append(resolved)
    if not existing:
        raise ValueError(f"Raw PNG was not copied beneath screenshot-dir: {category}/{value.name}")
    expected = str(record.get("sha256") or record.get("imageSha256") or "").lower()
    if not HEX64.fullmatch(expected):
        raise ValueError(f"Capture must declare its SHA-256: {category}/{value.name}")
    matching = [p for p in existing if hashlib.sha256(p.read_bytes()).hexdigest() == expected]
    if len(matching) != 1:
        raise ValueError(f"Expected exactly one hash-matched raw PNG: {category}/{value.name}")
    return matching[0]


def captures(document: dict, category: str, directory: Path, output: Path) -> list[dict]:
    records = document.get("captures", document.get("screenshots", []))
    if not isinstance(records, list):
        raise ValueError(f"{category}: captures must be an array")
    result = []
    for index, raw in enumerate(records):
        if not isinstance(raw, dict):
            raise ValueError(f"{category}: capture {index} must be an object")
        path = screenshot_path(raw, category, directory)
        data = path.read_bytes()
        if len(data) < 24 or not data.startswith(PNG) or data[12:16] != b"IHDR":
            raise ValueError(f"Capture is not a PNG: {path.name}")
        width, height = struct.unpack(">II", data[16:24])
        if min(width, height) < 100:
            raise ValueError(f"PNG does not have Android-screen dimensions: {path.name}")
        source = str(raw.get("sourceCommit") or document.get("sourceCommit") or "").lower()
        apk_sha = str(raw.get("apkSha256") or document.get("apkSha256") or "").lower()
        if not HEX40.fullmatch(source) or not HEX64.fullmatch(apk_sha):
            raise ValueError(f"Exact source commit and APK SHA-256 are required: {category}/{path.name}")
        declared_source = str(document.get("sourceCommit") or "").lower()
        if declared_source and source != declared_source:
            raise ValueError(f"Capture source differs from report source: {category}/{path.name}")
        declared_apk = str(document.get("apkSha256") or "").lower()
        if declared_apk and apk_sha != declared_apk:
            raise ValueError(f"Capture APK differs from report APK: {category}/{path.name}")
        if category == "after":
            if raw.get("applicationEvidence") is not True:
                raise ValueError(f"After capture has no verified native Healfi evidence: {path.name}")
            foregrounds = [raw.get("nativeForegroundBefore"), raw.get("nativeForegroundAfter")]
            if any(not isinstance(value, str) or not value.startswith("app.healfi.androidaps/") for value in foregrounds):
                raise ValueError(f"After capture foreground is outside Healfi: {path.name}")
            if raw.get("captureStateChangedDuringCollection") is not False:
                raise ValueError(f"After capture was not verified stable during collection: {path.name}")
        name = str(raw.get("name") or raw.get("case") or path.stem)
        result.append({
            "name": name,
            "file": path,
            "url": relative_url(path, output),
            "sha256": hashlib.sha256(data).hexdigest(),
            "source": source,
            "apk_sha": apk_sha,
            "width": width,
            "height": height,
            "observed": str(raw.get("observed") or raw.get("caption") or ""),
            "timestamp": str(raw.get("capturedAtUTC") or raw.get("captureTimeUTC") or raw.get("timeUTC") or raw.get("timestampUTC") or "Время съёмки не указано отдельно в отчёте"),
            "session": str(raw.get("captureSession") or document.get("captureSession") or ""),
            "not_new": bool(raw.get("notNewCapture", category == "before")),
        })
    return result


def is_home(record: dict) -> bool:
    parts = re.split(r"[^a-zа-яё0-9]+", record["name"].lower())
    return bool({"home", "main", "now", "сейчас", "главная"}.intersection(parts))



def select_home(records: list[dict], preferred_name: str | None = None) -> dict | None:
    if preferred_name is not None:
        selected = [record for record in records if record["name"] == preferred_name]
        if len(selected) != 1 or not is_home(selected[0]):
            raise ValueError("The selected after/home name must uniquely identify a home capture")
        return selected[0]
    homes = [record for record in records if is_home(record)]
    def priority(record: dict) -> int:
        parts = set(re.split(r"[^a-zа-яё0-9]+", record["name"].lower()))
        if "fresh" in parts:
            return 0
        if record["name"].lower() in {"home", "main", "now", "сейчас", "главная"}:
            return 1
        if parts.intersection({"stale", "required", "missing", "outdated", "action"}):
            return 10
        return 2
    return min(homes, key=priority) if homes else None

def label(record: dict) -> str:
    name = record["name"].lower()
    if name in LABELS:
        return LABELS[name]
    for part in re.split(r"[^a-zа-яё0-9]+", name):
        if part in LABELS:
            return LABELS[part] + " · " + record["name"]
    return record["name"]


def card(record: dict, badge: str) -> str:
    source_link = "https://github.com/rw404/AndroidAPS/tree/" + record["source"]
    session = f'<p>{esc(record["session"])}</p>' if record["session"] else ""
    observed = f'<p class="observed">{esc(record["observed"])}</p>' if record["observed"] else ""
    archived = '<span class="archived">Архив предыдущей сессии</span>' if record["not_new"] else ""
    return f'''<figure class="capture">
        <figcaption><span class="badge">{esc(badge)}</span>{archived}<h3>{esc(label(record))}</h3>{observed}</figcaption>
        <a class="screen-link" href="{esc(record['url'])}" target="_blank" rel="noopener" aria-label="Открыть полный Android-скриншот: {esc(label(record))}">
            <img src="{esc(record['url'])}" alt="Реальный Android-скриншот: {esc(label(record))}" width="{record['width']}" height="{record['height']}" loading="lazy">
        </a>
        <details><summary>Источник снимка и сборки</summary>
            <dl><dt>Commit</dt><dd><a href="{esc(source_link)}">{esc(record['source'])}</a></dd>
            <dt>APK SHA-256</dt><dd><code>{esc(record['apk_sha'])}</code></dd>
            <dt>PNG SHA-256</dt><dd><code>{esc(record['sha256'])}</code></dd>
            <dt>Размер исходного PNG</dt><dd>{record['width']} × {record['height']}</dd>
            <dt>Время</dt><dd>{esc(record['timestamp'])}</dd></dl>{session}
        </details>
    </figure>'''


STYLE = '''
:root{color-scheme:light;--canvas:#F6F7F9;--surface:#fff;--ink:#17242E;--muted:#63717C;--accent:#246A77;--stroke:#E2E7EB}
*{box-sizing:border-box}body{margin:0;background:var(--canvas);color:var(--ink);font:16px/1.55 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}main{max-width:1180px;margin:auto;padding:38px 24px 56px}header{max-width:900px;margin-bottom:28px}.eyebrow{color:var(--accent);font-size:12px;font-weight:650;letter-spacing:1.5px;text-transform:uppercase}h1{font-size:34px;line-height:1.2;letter-spacing:-.8px;margin:12px 0 16px}h2{font-size:23px;margin:34px 0 8px}h3{font-size:18px;margin:10px 0 4px}p{margin:10px 0}.muted,.observed{color:var(--muted);font-size:14px}.notice{border:1px solid #DFCA9B;background:#FFF4DE;border-radius:14px;padding:16px 18px;color:#735017;font-size:14px}.comparison{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px;align-items:start}.steps{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:20px;align-items:start}.capture{margin:0;background:var(--surface);border:1px solid var(--stroke);border-radius:18px;padding:18px;min-width:0}.badge{display:inline-block;border-radius:7px;padding:4px 8px;font-size:11px;font-weight:650;background:#EAF2F3;color:var(--accent);letter-spacing:.3px}.archived{font-size:11px;color:var(--muted);margin-left:8px}.screen-link{display:block;margin:18px auto;max-width:360px;outline-offset:4px}.screen-link img{display:block;width:100%;height:auto;border:1px solid var(--stroke);border-radius:8px;object-fit:contain}.capture:has(img[width="1440"]){max-width:none}details{border-top:1px solid var(--stroke);padding-top:12px;font-size:12px;color:var(--muted)}summary{cursor:pointer;color:var(--accent);min-height:32px}dl{margin:12px 0}dt{font-weight:600;margin-top:9px}dd{margin:3px 0;overflow-wrap:anywhere}a{color:var(--accent)}code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:11px}.pending{border:2px dashed #CFAB67;background:#FFFAF0;padding:30px 20px;border-radius:18px;align-self:start}.pending h3{margin-top:0}.footer{margin-top:30px;font-size:13px;color:var(--muted)}.tag{font-size:12px;color:var(--muted)}@media(max-width:640px){main{padding:26px 16px 40px}h1{font-size:28px}.comparison{grid-template-columns:1fr}.capture{padding:14px}.screen-link{max-width:100%}}@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto}}'''


def generate(before_doc: dict, after_doc: dict, output: Path, directory: Path, allow_pending: bool, after_home_name: str | None = None, expected_after_source: str | None = None) -> dict:
    if expected_after_source is not None:
        expected_after_source = expected_after_source.lower()
        if not HEX40.fullmatch(expected_after_source):
            raise ValueError("Expected after source must be an exact 40-character commit")
        if str(after_doc.get("sourceCommit") or "").lower() != expected_after_source:
            raise ValueError("After report belongs to a different source; previous candidate screenshots cannot become final evidence")
    before = captures(before_doc, "before", directory, output)
    after = captures(after_doc, "after", directory, output)
    before_home = select_home(before)
    after_home = select_home(after, after_home_name)
    if before_home is None:
        raise ValueError("A hash-checked before/home PNG is required")
    if after_home is None and not allow_pending:
        raise ValueError("A hash-checked after/home PNG is required; pending galleries must not be published")
    pending = after_home is None
    title = "Healfi · реальные Android-скриншоты" + (" · ПОСЛЕ ЕЩЁ НЕ ГОТОВО" if pending else "")
    pending_banner = '<p class="notice"><strong>Локальный черновик: новые Android-скриншоты ещё не готовы.</strong> Этот файл не является завершённым сравнением до/после и не предназначен для публикации.</p>' if pending else ""
    after_panel = card(after_home, "ПОСЛЕ · ANDROID") if after_home else '<div class="pending"><h3>После: ожидается Android-съёмка</h3><p>Новый экран не показан. Макет и искусственно собранные изображения не подставляются вместо фактического скриншота.</p></div>'
    additional = [record for record in after if record is not after_home]
    steps = '<h2>Остальные снимки нового сценария</h2><p class="muted">Полный экран сохранён в каждом изображении. Нажмите на него, чтобы открыть исходный PNG; отдельные снимки не доказывают прохождение всех переходов.</p><section class="steps">' + "\n".join(card(record, "ПОСЛЕ · ANDROID") for record in additional) + '</section>' if additional else '<p class="muted">Другие новые Android-снимки пока не приложены.</p>'
    document = f'''<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{esc(title)}</title><style>{STYLE}</style></head>
<body><main><header><div class="eyebrow">Healfi / Реальный Android / Не концепция</div><h1>От состояния к подтверждённому действию</h1><p>Сравнение главной и фактические снимки нового сценария. Изображения сохранены без изменения пикселей.</p><p class="tag">VirtualPump и фиктивные данные. Настоящая помпа и сенсор не подключались.</p></header>
{pending_banner}
<p class="notice">Снимок «до» взят из предыдущей сессии <strong>4 октября 2026 года</strong>, Android 15 / API 35, сборка f54. Это архивный снимок с указанным источником, а не новая съёмка. Сессии и временные метки различаются; сравнение не подразумевает одинаковые текущие данные.</p>
<h2>Главная: до и после</h2><p class="muted">Для каждого снимка доступны exact commit, хеш APK и хеш исходного PNG.</p><section class="comparison">{card(before_home, 'ДО · ANDROID')}{after_panel}</section>
{steps}
<p class="footer">Снимки подтверждают внешний вид в указанной среде. Они не подтверждают работу OnePlus 15, настоящую связь с xDrip+ BG / Medtronic 722 / OrangePro или RileyLink и реальную доставку инсулина. Непройденные проверки перечисляются в отдельном отчёте.</p>
</main></body></html>'''
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(document, encoding="utf-8")
    return {"output": str(output), "beforeCaptures": len(before), "afterCaptures": len(after), "pending": pending, "publishableComparison": not pending, "pixelsChanged": False, "beforeSource": before_home["source"], "afterSource": after_home["source"] if after_home else None}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-json", required=True, type=Path)
    parser.add_argument("--after-json", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--screenshot-dir", required=True, type=Path)
    parser.add_argument("--expected-after-source", help="Exact production commit; reject after reports from previous candidates")
    parser.add_argument("--after-home-name", help="Exact capture name for the main comparison, e.g. home-fresh; defaults to preferring fresh home")
    parser.add_argument("--allow-pending", action="store_true", help="Local preview only: visibly mark missing after/home; never publish it")
    args = parser.parse_args()
    try:
        result = generate(load_json(args.before_json), load_json(args.after_json), args.output.resolve(), args.screenshot_dir.resolve(), args.allow_pending, args.after_home_name, args.expected_after_source)
    except (ValueError, OSError, json.JSONDecodeError) as error:
        parser.exit(2, f"Gallery not generated: {error}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
