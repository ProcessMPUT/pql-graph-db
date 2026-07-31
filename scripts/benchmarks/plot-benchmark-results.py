#!/usr/bin/env python3
"""Generate dependency-free SVG charts from benchmark CSV outputs."""

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from typing import Callable
from pathlib import Path


WIDTH = 960
HEIGHT = 560
PAD_LEFT = 92
PAD_RIGHT = 58
PAD_TOP = 52
PAD_BOTTOM = 88
COLORS = {
    "local": "#2563eb",
    "reference": "#dc2626",
}
RIBBON_OPACITY = 0.16
# Grey band drawn behind every Q2 chart at the dataset-independent cost of a
# request (transport, auth, parse, plan). Without it a reader cannot tell how
# much of a 6 ms bar belongs to the storage engine at all.
FLOOR_FILL = "#94a3b8"


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def number(value: str | None) -> float:
    if value is None or value == "":
        return math.nan
    try:
        return float(value.replace(",", "."))
    except ValueError:
        return math.nan


def nice_ticks(lo: float, hi: float, target: int = 6) -> list[float]:
    """Ticks on a 1-2-2.5-5 x 10^k lattice covering [lo, hi].

    Replaces the previous "six equal fractions of the maximum", which produced
    axis labels like 6.84 / 5.47 / 4.10 / 2.74 / 1.37 — numbers that carry no
    meaning and make two charts impossible to compare by eye.
    """
    if not (math.isfinite(lo) and math.isfinite(hi)):
        return []
    if hi <= lo:
        hi = lo + 1.0
    raw = (hi - lo) / max(1, target - 1)
    magnitude = 10.0 ** math.floor(math.log10(raw)) if raw > 0 else 1.0
    step = magnitude
    for multiplier in (1.0, 2.0, 2.5, 5.0, 10.0):
        step = multiplier * magnitude
        if step >= raw:
            break
    start = math.floor(lo / step) * step
    ticks: list[float] = []
    value = start
    while value <= hi + step * 1e-9:
        if value >= lo - step * 1e-9:
            ticks.append(round(value, 10))
        value += step
    return ticks


def log_ticks(lo: float, hi: float) -> list[float]:
    """Decade ticks, subdivided by 2 and 5 when the range spans few decades."""
    if lo <= 0 or hi <= 0 or hi < lo:
        return []
    low_decade = math.floor(math.log10(lo))
    high_decade = math.ceil(math.log10(hi))
    decades = high_decade - low_decade
    multipliers = (1,) if decades > 3 else (1, 2, 5)
    ticks = []
    for decade in range(int(low_decade), int(high_decade) + 1):
        for multiplier in multipliers:
            value = multiplier * 10.0 ** decade
            if lo * 0.999 <= value <= hi * 1.001:
                ticks.append(value)
    return ticks or [lo, hi]


def format_count(value: float) -> str:
    """Counts are integers: 100, not '100.0'; 10 000, not '10000.0'."""
    if not math.isfinite(value):
        return ""
    if abs(value - round(value)) < 1e-9:
        return f"{round(value):,}".replace(",", " ")
    return f"{value:.6g}"


def format_tick(value: float) -> str:
    """Tick label without trailing zeros — for the 1-2-5 lattice of a log axis."""
    if not math.isfinite(value):
        return ""
    if abs(value) >= 1000:
        return f"{value:,.0f}".replace(",", " ")
    return f"{value:.4g}"


def format_num(value: float) -> str:
    if not math.isfinite(value):
        return ""
    if abs(value) >= 1000:
        return f"{value:,.0f}".replace(",", " ")
    if abs(value) >= 100:
        return f"{value:.0f}"
    if abs(value) >= 10:
        return f"{value:.1f}"
    if abs(value) >= 1:
        return f"{value:.2f}"
    return f"{value:.3g}"


def fit_power_law(points: list[tuple[float, float]]) -> tuple[float, float] | None:
    """Least-squares slope and R^2 of log10(y) against log10(x).

    The slope is the scaling exponent alpha of `t ~ n^alpha`; on a log-log chart
    it is literally the slope of the drawn line, which is the whole reason the
    scaling figures use log-log axes.
    """
    usable = [(x, y) for x, y in points if x > 0 and y > 0 and math.isfinite(x) and math.isfinite(y)]
    if len(usable) < 3:
        return None
    xs = [math.log10(x) for x, _ in usable]
    ys = [math.log10(y) for _, y in usable]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    sxx = sum((x - mean_x) ** 2 for x in xs)
    if sxx <= 0:
        return None
    slope = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / sxx
    intercept = mean_y - slope * mean_x
    ss_tot = sum((y - mean_y) ** 2 for y in ys)
    ss_res = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    return slope, r2


def fit_linear(points: list[tuple[float, float]]) -> tuple[float, float, float] | None:
    """Intercept, slope and R^2 of `y = a + b*x` (Q3 disk-growth model)."""
    usable = [(x, y) for x, y in points if math.isfinite(x) and math.isfinite(y)]
    if len(usable) < 3:
        return None
    xs = [x for x, _ in usable]
    ys = [y for _, y in usable]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    sxx = sum((x - mean_x) ** 2 for x in xs)
    if sxx <= 0:
        return None
    slope = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / sxx
    intercept = mean_y - slope * mean_x
    ss_tot = sum((y - mean_y) ** 2 for y in ys)
    ss_res = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    return intercept, slope, r2


def write_svg(
    path: Path,
    title: str,
    x_label: str,
    y_label: str,
    series: dict[str, list[tuple]],
    log_x: bool = False,
    log_y: bool = False,
    floor: dict[str, float] | None = None,
    annotate_fit: bool = False,
    x_is_count: bool = True,
) -> None:
    """Line chart with quartile ribbons, gaps at invalid points and nice axes.

    A point is `(x, y)` or `(x, y, lo, hi)`; a non-finite `y` marks a measurement
    that exists but must not be read as a value (a negative disk delta, a pair
    invalidated by MISMATCH). Such points **break the line** and are drawn as a
    hollow marker on the axis instead of being interpolated over or, worse,
    plotted as zero — which is how a database that shrank by 19,7 MB once became
    a V-shaped dip in a chart whose own table called the same cell unmeasurable.

    `floor` draws a horizontal band at the dataset-independent request cost.
    `annotate_fit` prints the fitted alpha and R^2 per series, so a flat noisy
    line is labelled as such instead of inviting a trend reading.
    """

    def unpack(point: tuple) -> tuple[float, float, float | None, float | None]:
        x, y = point[0], point[1]
        lo = point[2] if len(point) > 2 else None
        hi = point[3] if len(point) > 3 else None
        return x, y, lo, hi

    cleaned: dict[str, list[tuple[float, float, float | None, float | None]]] = {}
    for name, points in series.items():
        usable = []
        for point in points:
            x, y, lo, hi = unpack(point)
            if not math.isfinite(x):
                continue
            if log_x and x <= 0:
                continue
            usable.append((x, y, lo, hi))
        if usable:
            cleaned[name] = sorted(usable, key=lambda item: item[0])
    if not cleaned:
        return

    valid_ys = [
        v
        for points in cleaned.values()
        for _, y, lo, hi in points
        for v in (y, lo, hi)
        if v is not None and math.isfinite(v) and (not log_y or v > 0)
    ]
    if not valid_ys:
        return
    xs = [x for points in cleaned.values() for x, *_ in points]
    x_min, x_max = min(xs), max(xs)
    if x_min == x_max:
        x_min, x_max = x_min - 1, x_max + 1
    if log_y:
        y_min, y_max = min(valid_ys), max(valid_ys)
        if y_min == y_max:
            y_min, y_max = y_min / 2, y_max * 2
    else:
        # A zero baseline is the honest default for magnitudes; it is kept for
        # linear charts and abandoned only on log axes, where zero has no place.
        y_min, y_max = 0.0, max(valid_ys)
        if y_max <= 0:
            y_max = 1.0
    if floor:
        finite_floor = [v for v in floor.values() if math.isfinite(v) and v > 0]
        if finite_floor and not log_y:
            y_max = max(y_max, max(finite_floor))

    def tx(value: float) -> float:
        return math.log10(value) if log_x else value

    def ty(value: float) -> float:
        return math.log10(value) if log_y else value

    # Breathing room in transformed space, so the extreme points are not drawn
    # half-outside the frame and their axis labels are not clipped by the edge.
    MARGIN = 0.04
    tx_min, tx_max = tx(x_min), tx(x_max)
    x_pad = (tx_max - tx_min) * MARGIN
    tx_min, tx_max = tx_min - x_pad, tx_max + x_pad
    ty_min, ty_max = ty(y_min), ty(y_max)
    y_pad = (ty_max - ty_min) * MARGIN
    ty_max += y_pad
    if log_y:
        ty_min -= y_pad

    def sx(value: float) -> float:
        span = tx_max - tx_min
        if span == 0:
            return PAD_LEFT
        return PAD_LEFT + (tx(value) - tx_min) / span * (WIDTH - PAD_LEFT - PAD_RIGHT)

    def sy(value: float) -> float:
        span = ty_max - ty_min
        if span == 0:
            return HEIGHT - PAD_BOTTOM
        return HEIGHT - PAD_BOTTOM - (ty(value) - ty_min) / span * (HEIGHT - PAD_TOP - PAD_BOTTOM)

    lines: list[str] = []
    lines.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}">')
    lines.append('<rect width="100%" height="100%" fill="white"/>')
    lines.append(f'<text x="{WIDTH / 2}" y="28" text-anchor="middle" font-family="Arial" font-size="21" font-weight="700">{escape(title)}</text>')

    y_ticks = log_ticks(y_min, y_max) if log_y else nice_ticks(y_min, y_max)
    fmt_y = format_tick if log_y else format_num
    for value in y_ticks:
        py = sy(value)
        lines.append(f'<line x1="{PAD_LEFT - 6}" y1="{py:.2f}" x2="{WIDTH - PAD_RIGHT}" y2="{py:.2f}" stroke="#e5e7eb"/>')
        lines.append(f'<text x="{PAD_LEFT - 10}" y="{py + 4:.2f}" text-anchor="end" font-family="Arial" font-size="12">{fmt_y(value)}</text>')

    distinct_xs = sorted({x for points in cleaned.values() for x, *_ in points})
    x_ticks = distinct_xs if len(distinct_xs) <= 9 else (log_ticks(x_min, x_max) if log_x else nice_ticks(x_min, x_max))
    fmt_x = format_count if x_is_count else format_num
    for value in x_ticks:
        px = sx(value)
        lines.append(f'<line x1="{px:.2f}" y1="{HEIGHT - PAD_BOTTOM}" x2="{px:.2f}" y2="{HEIGHT - PAD_BOTTOM + 6}" stroke="#111827"/>')
        lines.append(f'<text x="{px:.2f}" y="{HEIGHT - PAD_BOTTOM + 24}" text-anchor="middle" font-family="Arial" font-size="12">{fmt_x(value)}</text>')

    if floor:
        for name, value in sorted(floor.items()):
            if not (math.isfinite(value) and value > 0):
                continue
            if log_y and value < y_min:
                continue
            py = sy(value)
            colour = COLORS.get(name, FLOOR_FILL)
            lines.append(
                f'<line x1="{PAD_LEFT}" y1="{py:.2f}" x2="{WIDTH - PAD_RIGHT}" y2="{py:.2f}" '
                f'stroke="{colour}" stroke-width="1.5" stroke-dasharray="7,5" opacity="0.75"/>'
            )
            lines.append(
                f'<text x="{WIDTH - PAD_RIGHT - 4}" y="{py - 5:.2f}" text-anchor="end" font-family="Arial" '
                f'font-size="11" fill="{colour}">podłoga {name}: {format_num(value)}</text>'
            )

    fit_notes: list[tuple[str, str]] = []
    for name, points in cleaned.items():
        colour = COLORS.get(name, "#16a34a")

        def plottable(value: float | None) -> bool:
            return value is not None and math.isfinite(value) and (not log_y or value > 0)

        # Quartile ribbon: the spread the tables print, drawn instead of left in
        # a column. Overlapping ribbons are what tell a reader "no effect here".
        ribbon_runs: list[list[tuple[float, float, float]]] = []
        current: list[tuple[float, float, float]] = []
        for x, y, lo, hi in points:
            if plottable(lo) and plottable(hi):
                current.append((x, lo, hi))
            elif current:
                ribbon_runs.append(current)
                current = []
        if current:
            ribbon_runs.append(current)
        for run in ribbon_runs:
            if len(run) < 2:
                continue
            top = " ".join(f"{sx(x):.2f},{sy(hi):.2f}" for x, _, hi in run)
            bottom = " ".join(f"{sx(x):.2f},{sy(lo):.2f}" for x, lo, _ in reversed(run))
            lines.append(f'<polygon points="{top} {bottom}" fill="{colour}" opacity="{RIBBON_OPACITY}"/>')

        segments: list[list[tuple[float, float]]] = []
        segment: list[tuple[float, float]] = []
        for x, y, _, _ in points:
            if plottable(y):
                segment.append((x, y))
            else:
                if segment:
                    segments.append(segment)
                segment = []
                lines.append(
                    f'<circle cx="{sx(x):.2f}" cy="{HEIGHT - PAD_BOTTOM:.2f}" r="5" fill="white" '
                    f'stroke="{colour}" stroke-width="2" stroke-dasharray="2,2"/>'
                )
        if segment:
            segments.append(segment)
        for part in segments:
            if len(part) > 1:
                polyline = " ".join(f"{sx(x):.2f},{sy(y):.2f}" for x, y in part)
                lines.append(f'<polyline fill="none" stroke="{colour}" stroke-width="3" points="{polyline}"/>')
            for x, y in part:
                lines.append(f'<circle cx="{sx(x):.2f}" cy="{sy(y):.2f}" r="4" fill="{colour}"/>')

        if annotate_fit:
            fit = fit_power_law([(x, y) for x, y, _, _ in points if plottable(y)])
            if fit:
                slope, r2 = fit
                verdict = "brak zależności" if abs(slope) < 0.05 or r2 < 0.30 else ""
                note = f"{name}: α={slope:+.2f}, R²={r2:.2f}"
                if verdict:
                    note += f" ({verdict})"
                fit_notes.append((name, note))

    for index, (name, note) in enumerate(fit_notes):
        colour = COLORS.get(name, "#16a34a")
        lines.append(
            f'<text x="{PAD_LEFT + 8}" y="{PAD_TOP + 4 + index * 17}" font-family="Arial" font-size="12" '
            f'fill="{colour}" font-weight="600">{escape(note)}</text>'
        )

    legend_y = HEIGHT - 30
    for index, name in enumerate(cleaned):
        x = PAD_LEFT + index * 150
        colour = COLORS.get(name, "#16a34a")
        lines.append(f'<rect x="{x}" y="{legend_y - 12}" width="18" height="4" fill="{colour}"/>')
        lines.append(f'<text x="{x + 26}" y="{legend_y - 6}" font-family="Arial" font-size="13">{escape(name)}</text>')
    has_gap = any(
        not (y is not None and math.isfinite(y) and (not log_y or y > 0))
        for points in cleaned.values()
        for _, y, _, _ in points
    )
    if has_gap:
        gx = PAD_LEFT + len(cleaned) * 150
        lines.append(f'<circle cx="{gx + 8}" cy="{legend_y - 10}" r="5" fill="white" stroke="#111827" stroke-width="2" stroke-dasharray="2,2"/>')
        lines.append(f'<text x="{gx + 20}" y="{legend_y - 6}" font-family="Arial" font-size="12">pomiar nieważny</text>')

    lines.append(f'<text x="{WIDTH / 2}" y="{HEIGHT - 8}" text-anchor="middle" font-family="Arial" font-size="14">{escape(x_label)}</text>')
    lines.append(
        f'<text x="22" y="{HEIGHT / 2}" text-anchor="middle" font-family="Arial" font-size="14" transform="rotate(-90 22 {HEIGHT / 2})">{escape(y_label)}</text>'
    )
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def escape(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def join_dataset(rows: list[dict[str, str]], datasets: dict[str, dict[str, str]]) -> list[dict[str, str]]:
    joined = []
    for row in rows:
        dataset = datasets.get(row.get("datasetName", ""), {})
        merged = dict(dataset)
        merged.update(row)
        joined.append(merged)
    return joined


def line_series(
    rows: list[dict[str, str]],
    x_field: str,
    y_field: str,
    series_field: str = "system",
    y_scale: float = 1.0,
    low_field: str | None = None,
    high_field: str | None = None,
    valid: "Callable[[dict[str, str]], bool] | None" = None,
) -> dict[str, list[tuple]]:
    """Points for [write_svg], optionally carrying a quartile band.

    `valid` is the single place a row is judged plottable. The tables apply the
    same rule, so a value a table refuses to print can no longer appear as a
    point on a chart: an invalid row is kept (its x position is real) but its y
    becomes NaN, which draws a gap rather than an interpolation.
    """
    series: dict[str, list[tuple]] = defaultdict(list)
    for row in rows:
        x = number(row.get(x_field))
        ok = valid(row) if valid else True
        y = number(row.get(y_field)) * y_scale if ok else math.nan
        low = number(row.get(low_field)) * y_scale if (ok and low_field) else math.nan
        high = number(row.get(high_field)) * y_scale if (ok and high_field) else math.nan
        series[row.get(series_field, "unknown")].append((x, y, low, high))
    return dict(series)
def write_grouped_bars_svg(
    path: Path,
    title: str,
    categories: list[str],
    series: dict[str, list[float]],
    y_label: str,
    log_scale: bool = False,
) -> None:
    """Grouped vertical bars; series values are aligned with categories (NaN = skip)."""
    if not categories or not series:
        return
    finite = [v for values in series.values() for v in values if math.isfinite(v) and v > 0]
    if not finite:
        return
    y_max = max(finite)
    y_min = min(finite) if log_scale else 0.0

    def ty(v: float) -> float:
        return math.log10(v) if log_scale else v

    top, bottom = ty(y_max), (ty(y_min) if log_scale else 0.0)
    if log_scale:
        bottom = math.floor(bottom)
        top = math.ceil(top)
    if top == bottom:
        top += 1

    def sy(v: float) -> float:
        return HEIGHT - PAD_BOTTOM - (ty(v) - bottom) / (top - bottom) * (HEIGHT - PAD_TOP - PAD_BOTTOM)

    plot_w = WIDTH - PAD_LEFT - PAD_RIGHT
    group_w = plot_w / len(categories)
    bar_w = min(42.0, group_w * 0.8 / max(1, len(series)))

    lines: list[str] = []
    lines.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}">')
    lines.append('<rect width="100%" height="100%" fill="white"/>')
    lines.append(f'<text x="{WIDTH / 2}" y="28" text-anchor="middle" font-family="Arial" font-size="22" font-weight="700">{escape(title)}</text>')
    lines.append(f'<line x1="{PAD_LEFT}" y1="{HEIGHT - PAD_BOTTOM}" x2="{WIDTH - PAD_RIGHT}" y2="{HEIGHT - PAD_BOTTOM}" stroke="#111827"/>')
    lines.append(f'<line x1="{PAD_LEFT}" y1="{PAD_TOP}" x2="{PAD_LEFT}" y2="{HEIGHT - PAD_BOTTOM}" stroke="#111827"/>')

    if log_scale:
        decade = int(bottom)
        while decade <= top:
            v = 10 ** decade
            py = sy(v)
            lines.append(f'<line x1="{PAD_LEFT - 6}" y1="{py:.2f}" x2="{WIDTH - PAD_RIGHT}" y2="{py:.2f}" stroke="#e5e7eb"/>')
            lines.append(f'<text x="{PAD_LEFT - 10}" y="{py + 4:.2f}" text-anchor="end" font-family="Arial" font-size="12">{format_num(v)}</text>')
            decade += 1
    else:
        for tick in range(6):
            v = bottom + (top - bottom) * tick / 5
            py = sy(v) if not log_scale else sy(10 ** v)
            lines.append(f'<line x1="{PAD_LEFT - 6}" y1="{py:.2f}" x2="{WIDTH - PAD_RIGHT}" y2="{py:.2f}" stroke="#e5e7eb"/>')
            lines.append(f'<text x="{PAD_LEFT - 10}" y="{py + 4:.2f}" text-anchor="end" font-family="Arial" font-size="12">{format_num(v)}</text>')

    for ci, cat in enumerate(categories):
        gx = PAD_LEFT + ci * group_w + group_w / 2
        total_bars_w = bar_w * len(series)
        for si, (name, values) in enumerate(series.items()):
            v = values[ci] if ci < len(values) else math.nan
            if not (math.isfinite(v) and v > 0):
                continue
            color = COLORS.get(name, "#16a34a")
            x = gx - total_bars_w / 2 + si * bar_w
            y = sy(v)
            lines.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{bar_w - 3:.2f}" height="{HEIGHT - PAD_BOTTOM - y:.2f}" fill="{color}"/>')
            lines.append(
                f'<text x="{x + (bar_w - 3) / 2:.2f}" y="{y - 5:.2f}" text-anchor="middle" font-family="Arial" font-size="10">{format_num(v)}</text>'
            )
        label = cat if len(cat) <= 20 else cat[:18] + "…"
        lines.append(
            f'<text x="{gx:.2f}" y="{HEIGHT - PAD_BOTTOM + 14}" text-anchor="end" font-family="Arial" font-size="11" '
            f'transform="rotate(-30 {gx:.2f} {HEIGHT - PAD_BOTTOM + 14})">{escape(label)}</text>'
        )

    legend_y = HEIGHT - 20
    for index, name in enumerate(series):
        x = PAD_LEFT + index * 150
        color = COLORS.get(name, "#16a34a")
        lines.append(f'<rect x="{x}" y="{legend_y - 10}" width="14" height="14" fill="{color}"/>')
        lines.append(f'<text x="{x + 20}" y="{legend_y + 2}" font-family="Arial" font-size="13">{escape(name)}</text>')
    lines.append(
        f'<text x="22" y="{HEIGHT / 2}" text-anchor="middle" font-family="Arial" font-size="14" transform="rotate(-90 22 {HEIGHT / 2})">{escape(y_label)}</text>'
    )
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_speedup_svg(path: Path, title: str, items: list[tuple[str, float]]) -> None:
    """Horizontal log-scale bars of reference/local ratio; >1 means local is faster."""
    items = [(label, ratio) for label, ratio in items if math.isfinite(ratio) and ratio > 0]
    if not items:
        return
    lo = min(min(r for _, r in items), 1.0)
    hi = max(max(r for _, r in items), 1.0)
    left, right = math.floor(math.log10(lo)), math.ceil(math.log10(hi))
    if left == right:
        right += 1
    pad_left = 220
    row_h = 34
    height = PAD_TOP + row_h * len(items) + 70
    plot_w = WIDTH - pad_left - PAD_RIGHT

    def sx(r: float) -> float:
        return pad_left + (math.log10(r) - left) / (right - left) * plot_w

    lines: list[str] = []
    lines.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{height}" viewBox="0 0 {WIDTH} {height}">')
    lines.append('<rect width="100%" height="100%" fill="white"/>')
    lines.append(f'<text x="{WIDTH / 2}" y="28" text-anchor="middle" font-family="Arial" font-size="22" font-weight="700">{escape(title)}</text>')
    decade = left
    while decade <= right:
        px = sx(10 ** decade)
        lines.append(f'<line x1="{px:.2f}" y1="{PAD_TOP}" x2="{px:.2f}" y2="{height - 52}" stroke="#e5e7eb"/>')
        lines.append(f'<text x="{px:.2f}" y="{height - 36}" text-anchor="middle" font-family="Arial" font-size="12">x{format_num(10 ** decade)}</text>')
        decade += 1
    one_x = sx(1.0)
    lines.append(f'<line x1="{one_x:.2f}" y1="{PAD_TOP}" x2="{one_x:.2f}" y2="{height - 52}" stroke="#111827" stroke-width="2" stroke-dasharray="5,4"/>')
    for i, (label, ratio) in enumerate(items):
        y = PAD_TOP + i * row_h + 6
        color = "#16a34a" if ratio >= 1 else "#dc2626"
        x0, x1 = sorted((one_x, sx(ratio)))
        lines.append(f'<rect x="{x0:.2f}" y="{y:.2f}" width="{max(1.0, x1 - x0):.2f}" height="{row_h - 12:.2f}" fill="{color}"/>')
        lines.append(f'<text x="{pad_left - 8}" y="{y + row_h / 2 - 1:.2f}" text-anchor="end" font-family="Arial" font-size="13">{escape(label)}</text>')
        tx = x1 + 6 if ratio >= 1 else x0 - 6
        anchor = "start" if ratio >= 1 else "end"
        lines.append(f'<text x="{tx:.2f}" y="{y + row_h / 2 - 1:.2f}" text-anchor="{anchor}" font-family="Arial" font-size="12" font-weight="700">x{ratio:.2f}</text>')
    lines.append(f'<text x="{WIDTH / 2}" y="{height - 12}" text-anchor="middle" font-family="Arial" font-size="13">reference / local (&gt;1 = local szybszy, skala log)</text>')
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def median(values: list[float]) -> float:
    clean = sorted(v for v in values if math.isfinite(v))
    if not clean:
        return math.nan
    mid = len(clean) // 2
    return clean[mid] if len(clean) % 2 else (clean[mid - 1] + clean[mid]) / 2


import re


def strip_injected_blocks(text: str) -> str:
    """Removes both legacy end-dump and per-section figure blocks (idempotent re-runs)."""
    text = re.sub(r"\n*<!-- plots:start -->.*?<!-- plots:end -->\n*", "\n", text, flags=re.S)
    text = re.sub(r"\n*<!-- plots:block:[^>]* -->.*?<!-- /plots:block -->\n*", "\n", text, flags=re.S)
    return text


def figure_block(key: str, result_dir: Path, figures: list[tuple[str, str]]) -> str:
    """Markdown block of figures (file, caption); an empty file name emits the
    caption as a raw markdown line (used for grouping sub-headings). Missing
    SVGs are skipped."""
    rows = []
    for name, caption in figures:
        if not name:
            rows.append(f"{caption}\n")
        elif (result_dir / "plots" / name).exists():
            rows.append(f"![{Path(name).stem}](plots/{name})\n\n*{caption}*\n")
    if not any(row.startswith("![") for row in rows):
        return ""
    return f"<!-- plots:block:{key} -->\n\n" + "\n".join(rows) + f"\n<!-- /plots:block -->\n"


def inject_markdown_block(result_dir: Path, prefix: str, block: str) -> None:
    """Inserts a marker-delimited markdown block at the end of a named section.

    Used for content that must be computed from CSVs the runner never sees (the
    sequential storage probe), so the report keeps a single source of truth per
    number instead of the reader meeting two different disk figures.
    """
    if not block:
        return
    report = result_dir / "thesis-report.md"
    if not report.exists():
        return
    lines = report.read_text(encoding="utf-8").split("\n")
    headers = [
        (i, len(m.group(1)), m.group(2).strip())
        for i, line in enumerate(lines)
        if (m := re.match(r"^(#{1,3}) (.*)$", line))
    ]
    for pos, (index, level, title) in enumerate(headers):
        if not title.startswith(prefix):
            continue
        end = len(lines)
        for j, jlevel, _ in headers[pos + 1:]:
            if jlevel <= level:
                end = j
                break
        lines[end:end] = ["", block]
        report.write_text("\n".join(lines), encoding="utf-8")
        return


def inject_into_sections(
    result_dir: Path,
    anchors: list[tuple[str, list[tuple[str, str]], str]],
) -> None:
    """Inserts each figure block into the section whose header matches the anchor prefix.

    Placement `section-end` puts figures where the next same-or-higher-level header
    begins (right under the section's tables); `intro-end` puts them before the
    section's first subsection, i.e. directly after the introductory prose.
    Injected blocks are marker-delimited, so re-runs replace instead of duplicating.
    """
    report = result_dir / "thesis-report.md"
    if not report.exists():
        return
    text = strip_injected_blocks(report.read_text(encoding="utf-8"))
    lines = text.split("\n")

    header_positions: list[tuple[int, int, str]] = []
    for i, line in enumerate(lines):
        m = re.match(r"^(#{1,3}) (.*)$", line)
        if m:
            header_positions.append((i, len(m.group(1)), m.group(2).strip()))

    insertions: list[tuple[int, str]] = []
    for prefix, figures, placement in anchors:
        block = figure_block(prefix, result_dir, figures)
        if not block:
            continue
        for pos, (i, level, title) in enumerate(header_positions):
            if title.startswith(prefix):
                end = len(lines)
                for j, jlevel, _ in header_positions[pos + 1:]:
                    if jlevel <= level or (placement == "intro-end" and jlevel > level):
                        end = j
                        break
                insertions.append((end, block))
                break

    for end, block in sorted(insertions, key=lambda item: item[0], reverse=True):
        lines[end:end] = ["", block]
    report.write_text("\n".join(lines), encoding="utf-8")


TEX_MARKER_START = "% plots:figures:start"
TEX_MARKER_END = "% plots:figures:end"


def tex_escape(text: str) -> str:
    for a, b in [("\\", r"\textbackslash{}"), ("&", r"\&"), ("%", r"\%"), ("_", r"\_"),
                 ("#", r"\#"), ("{", r"\{"), ("}", r"\}"), ("^", r"\textasciicircum{}")]:
        text = text.replace(a, b)
    return text


def append_tex_figures(
    result_dir: Path,
    anchors: list[tuple[str, list[tuple[str, str]], str]],
) -> None:
    """Appends a marker-delimited block of LaTeX figure environments to
    thesis-tables.tex, one per chart the report embeds. pdflatex needs raster
    or PDF art, so `\\includegraphics` is written extension-less: drop PDF
    siblings next to the SVGs (a one-line `for f in plots/*.svg; do
    rsvg-convert -f pdf ...` — see src/benchmark/AGENTS.md), or load the `svg` package and
    swap `\\includegraphics` for `\\includesvg`. Re-runs replace the block."""
    tex = result_dir / "thesis-tables.tex"
    if not tex.exists():
        return
    plots_dir = result_dir / "plots"

    figures: list[str] = [TEX_MARKER_START, "% figury raportu; wymaga PDF-owych wersji wykresow (zob. src/benchmark/AGENTS.md)"]
    for _prefix, items, _placement in anchors:
        for name, caption in items:
            if not name or not (plots_dir / name).exists():
                continue
            stem = Path(name).stem
            clean = re.sub(r"^Rys\.[^:]*:\s*", "", caption).strip().rstrip(".")
            figures += [
                r"\begin{figure}[htbp]",
                r"  \centering",
                rf"  \includegraphics[width=\linewidth]{{plots/{stem}}}",
                rf"  \caption{{{tex_escape(clean)}}}",
                rf"  \label{{fig:bench-{stem.replace('_', '-')}}}",
                r"\end{figure}",
                "",
            ]
    figures.append(TEX_MARKER_END)
    block = "\n".join(figures)

    text = tex.read_text(encoding="utf-8")
    if TEX_MARKER_START in text and TEX_MARKER_END in text:
        head, rest = text.split(TEX_MARKER_START, 1)
        _, tail = rest.split(TEX_MARKER_END, 1)
        text = head.rstrip() + "\n\n" + block + tail
    else:
        text = text.rstrip() + "\n\n" + block + "\n"
    tex.write_text(text, encoding="utf-8")


SCALING_AXES = [
    ("trace-scaling", "traces", "Liczba trace'ów", True),
    ("event-scaling", "totalEvents", "Łączna liczba zdarzeń", True),
    ("attribute-scaling", "attributesPerEvent", "Atrybuty na zdarzenie", True),
    ("shape-scaling", "eventsPerTrace", "Zdarzeń na ślad (stała objętość logu)", True),
]


def load_query_specs(result_dir: Path) -> dict[str, dict[str, str]]:
    """Workload class and clause description per query, written by the runner.

    Falls back to an empty mapping for runs collected before `queries.csv`
    existed; callers then treat every query as unclassified rather than guessing.
    """
    return {row["queryLabel"]: row for row in read_csv(result_dir / "queries.csv") if row.get("queryLabel")}


def storage_row_is_valid(row: dict[str, str]) -> bool:
    """The single validity rule for disk deltas, matching the report's tables.

    Only `OK` with a strictly positive delta may be drawn. `CONTAMINATED_NEGATIVE_DELTA`
    (the store shrank across the import) and `BELOW_ALLOCATION_GRANULARITY` are
    measurements that exist but carry no per-dataset quantity — plotting them as
    numbers is what produced a line dipping below zero mid-series.
    """
    if row.get("status") != "OK":
        return False
    return number(row.get("deltaBytes")) > 0


def quartile_fields(result_dir: Path, queries: list[dict[str, str]]) -> tuple[str | None, str | None]:
    """Field names carrying Q1/Q3, or `(None, None)` when the run predates them.

    Newer runs write the quartiles into `query-summary.csv` with the same type-7
    estimator the tables use, so a ribbon and a printed range can never disagree.
    For older runs the columns are back-filled here from the raw samples using the
    same estimator.
    """
    if queries and "q1Seconds" in queries[0]:
        return "q1Seconds", "q3Seconds"
    raw = read_csv(result_dir / "query-results.csv")
    if not raw:
        return None, None
    grouped: dict[tuple[str, str, str], list[float]] = defaultdict(list)
    for row in raw:
        if row.get("phase") != "warm" or row.get("status") != "OK":
            continue
        key = (row.get("system", ""), row.get("datasetName", ""), row.get("queryLabel", ""))
        grouped[key].append(number(row.get("seconds")))
    for row in queries:
        key = (row.get("system", ""), row.get("datasetName", ""), row.get("queryLabel", ""))
        values = sorted(v for v in grouped.get(key, []) if math.isfinite(v))
        if not values:
            continue
        row["q1Seconds"] = str(quantile(values, 0.25))
        row["q3Seconds"] = str(quantile(values, 0.75))
    return "q1Seconds", "q3Seconds"


def quantile(sorted_values: list[float], p: float) -> float:
    """Hyndman & Fan type 7, identical to the estimator used by the report tables."""
    if not sorted_values:
        return math.nan
    h = (len(sorted_values) - 1) * p
    lower = math.floor(h)
    upper = min(lower + 1, len(sorted_values) - 1)
    return sorted_values[lower] + (h - lower) * (sorted_values[upper] - sorted_values[lower])


def measurement_floor(
    queries: list[dict[str, str]],
    query_specs: dict[str, dict[str, str]],
) -> dict[str, float]:
    """Median time of the `floor` query per system, in milliseconds.

    Drawn on every query chart as a dashed reference line: it is the cost of a
    request that touches almost no data, so the distance between a curve and this
    line is the only part attributable to the storage engine.
    """
    floor_labels = {label for label, spec in query_specs.items() if spec.get("workload") == "floor"}
    if not floor_labels:
        return {}
    by_system: dict[str, list[float]] = defaultdict(list)
    for row in queries:
        if row.get("queryLabel") in floor_labels:
            value = number(row.get("medianSeconds")) * 1000.0
            if math.isfinite(value):
                by_system[row.get("system", "")].append(value)
    return {
        system: quantile(sorted(values), 0.50)
        for system, values in by_system.items()
        if values
    }


def storage_model_block(result_dir: Path, datasets: dict[str, dict[str, str]]) -> str:
    """Markdown table of the fitted disk-growth model from the sequential probe.

    `delta = a + b * events` separates what the allocator does (the intercept)
    from what the storage format costs (the slope). Reporting `delta / xesBytes`
    at a single point instead makes the same format look like a 32,9x expansion at
    100 traces and 3,8x at 10 000, because the constant term dominates the small
    datasets.
    """
    rows = join_dataset(read_csv(result_dir / "storage-scaling.csv"), datasets)
    if not rows:
        return ""
    lines = [
        "| Seria | System | Punkty | Stała a [MiB] | Koszt krańcowy b [B/zdarzenie] "
        "| Ekspansja krańcowa [B/B XES] | R² |",
        "| :--- | :--- | ---: | ---: | ---: | ---: | ---: |",
    ]
    emitted = False
    for series_name, _, _, _ in SCALING_AXES:
        for system in ("local", "reference"):
            points = [
                (number(r.get("totalEvents")), number(r.get("deltaBytes")), number(r.get("xesBytes")))
                for r in rows
                if r.get("series") == series_name and r.get("system") == system
            ]
            points = [p for p in points if all(math.isfinite(v) for v in p) and p[0] > 0 and p[1] > 0]
            fit = fit_linear([(events, delta) for events, delta, _ in points])
            if not fit:
                continue
            intercept, slope, r2 = fit
            xes_per_event = sum(p[2] for p in points) / sum(p[0] for p in points)
            expansion = slope / xes_per_event if xes_per_event > 0 else float("nan")
            lines.append(
                f"| {series_name} | {system} | {len(points)} | {intercept / (1024 * 1024):.2f} "
                f"| {slope:.0f} | {expansion:.2f} | {r2:.2f} |"
            )
            emitted = True
    if not emitted:
        return ""
    lines.append("")
    lines.append(
        "*Model dopasowany do wyników sondy sekwencyjnej (`storage-scaling.csv`) — pomiaru "
        "przypisywalnego per dataset dla **obu** systemów. Stała **a** opisuje prealokację "
        "silnika, współczynnik **b** — format składowania; to **b** jest wielkością, którą "
        "należy cytować jako ekspansję. Jeżeli nachylenie z tej tabeli zgadza się z nachyleniem "
        "modelu z protokołu benchmarku (sekcja Q3), oba niezależne pomiary potwierdzają się "
        "wzajemnie; rozjazd nachyleń jest sygnałem do weryfikacji importem w izolacji.*"
    )
    return "<!-- plots:block:storage-model -->\n\n" + "\n".join(lines) + "\n\n<!-- /plots:block -->\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=Path)
    args = parser.parse_args()

    result_dir = args.result_dir
    plots_dir = result_dir / "plots"
    plots_dir.mkdir(parents=True, exist_ok=True)

    datasets = {row["datasetName"]: row for row in read_csv(result_dir / "datasets.csv")}
    imports = join_dataset(read_csv(result_dir / "import-results.csv"), datasets)
    queries = join_dataset(read_csv(result_dir / "query-summary.csv"), datasets)
    storage = join_dataset(read_csv(result_dir / "storage-results.csv"), datasets)
    query_specs = load_query_specs(result_dir)
    quartiles = quartile_fields(result_dir, queries)
    floors = measurement_floor(queries, query_specs)

    for series_name, axis, label, log_x in SCALING_AXES:
        axis_label = label + (" (skala log)" if log_x else "")
        import_rows = [r for r in imports if r.get("series") == series_name and r.get("status") == "OK"]
        write_svg(
            plots_dir / f"import_by_{axis}.svg",
            f"Import: skalowanie — {label.lower()}",
            axis_label,
            "Czas importu [s] (skala log)",
            line_series(import_rows, axis, "seconds"),
            log_x=log_x,
            log_y=True,
            annotate_fit=log_x,
        )

        # One validity rule, shared with the tables: a delta is plottable only
        # when it is a strictly positive, attributable measurement. Everything
        # else keeps its x position and draws a gap.
        storage_rows = [r for r in storage if r.get("series") == series_name]
        write_svg(
            plots_dir / f"storage_delta_by_{axis}.svg",
            f"Przyrost dysku po imporcie — {label.lower()}",
            axis_label,
            "Przyrost [MiB] (skala log)",
            line_series(
                storage_rows, axis, "deltaBytes",
                y_scale=1 / (1024 * 1024), valid=storage_row_is_valid,
            ),
            log_x=log_x,
            log_y=True,
        )

        # Scaling figures are emitted only for queries whose semantics can depend
        # on dataset size. A `window` query is bounded by the API's default limits
        # on both sides, so its "scaling" chart would show noise around a constant
        # and invite a trend reading that the data cannot support.
        labels = [
            label_name
            for label_name in sorted({r.get("queryLabel", "") for r in queries if r.get("series") == series_name})
            if query_specs.get(label_name, {}).get("workload") != "window"
        ]
        for query_label in labels:
            query_rows = [
                r for r in queries
                if r.get("series") == series_name and r.get("queryLabel") == query_label
            ]
            write_svg(
                plots_dir / f"query_{query_label}_by_{axis}.svg",
                f"{query_label}: skalowanie mediany czasu zapytania",
                axis_label,
                "Mediana [ms] (skala log)",
                line_series(
                    query_rows, axis, "medianSeconds", y_scale=1000.0,
                    low_field=quartiles[0], high_field=quartiles[1],
                ),
                log_x=log_x,
                log_y=True,
                floor=floors,
                annotate_fit=log_x,
            )

    # Local-vs-reference comparison and speedup charts per dataset (log scale so
    # ms-vs-minutes differences stay readable), plus memory and import overviews.
    highlight_datasets = [
        name for name in datasets
        if datasets[name].get("series") == "real-validation" or name in ("trace-10000", "event-200")
    ]
    query_labels = sorted({r.get("queryLabel", "") for r in queries})
    embed: list[str] = []
    for ds_name in highlight_datasets:
        by_system: dict[str, list[float]] = {"local": [], "reference": []}
        ratios: list[tuple[str, float]] = []
        for label in query_labels:
            values = {}
            for system in ("local", "reference"):
                rows = [
                    number(r.get("medianSeconds")) * 1000
                    for r in queries
                    if r.get("datasetName") == ds_name and r.get("queryLabel") == label and r.get("system") == system
                ]
                values[system] = median(rows)
                by_system[system].append(values[system])
            if math.isfinite(values["local"]) and math.isfinite(values["reference"]) and values["local"] > 0:
                ratios.append((label, values["reference"] / values["local"]))
        write_grouped_bars_svg(
            plots_dir / f"query_compare_{ds_name}.svg",
            f"{ds_name}: mediana czasu zapytania",
            query_labels,
            by_system,
            "Mediana [ms], skala log",
            log_scale=True,
        )
        write_speedup_svg(
            plots_dir / f"query_speedup_{ds_name}.svg",
            f"{ds_name}: przewaga local nad reference",
            ratios,
        )
        embed += [f"query_compare_{ds_name}.svg", f"query_speedup_{ds_name}.svg"]

    import_cats, import_series = [], {"local": [], "reference": []}
    for ds_name in highlight_datasets:
        import_cats.append(ds_name)
        for system in ("local", "reference"):
            rows = [
                number(r.get("seconds"))
                for r in imports
                if r.get("datasetName") == ds_name and r.get("system") == system and r.get("status") == "OK"
            ]
            import_series[system].append(median(rows))
    write_grouped_bars_svg(
        plots_dir / "import_compare.svg",
        "Import: mediana czasu (najwieksze datasety)",
        import_cats,
        import_series,
        "Sekundy",
        log_scale=False,
    )
    embed.append("import_compare.svg")

    memory = read_csv(result_dir / "memory-summary.csv")
    mem_rows = [r for r in memory if r.get("phase") == "queries"]
    if mem_rows:
        mem_cats = [r.get("component", "?") for r in mem_rows]
        mem_series = {
            "mediana": [number(r.get("medianBytes")) / 1e9 for r in mem_rows],
            "peak": [number(r.get("peakBytes")) / 1e9 for r in mem_rows],
        }
        colors_backup = dict(COLORS)
        COLORS.update({"mediana": "#2563eb", "peak": "#f59e0b"})
        write_grouped_bars_svg(
            plots_dir / "memory_queries_phase.svg",
            "RAM w fazie zapytan (GB)",
            mem_cats,
            mem_series,
            "GB",
            log_scale=False,
        )
        COLORS.clear()
        COLORS.update(colors_backup)
        embed.append("memory_queries_phase.svg")

    # Five queries covering distinct PQL clauses (thesis question 4): limit,
    # where on a custom attribute, order by, group by, select with aggregations.
    # Which queries get a scaling figure is decided by their workload class, not
    # by a list kept here. The old hard-coded whitelist held five `window` queries
    # — all bounded by the API's default limits, so all flat — and omitted
    # `hoistedGroup`, the one query with a clean scaling law on both systems.
    grid_queries = [
        (label, spec.get("clause", ""))
        for label, spec in sorted(query_specs.items())
        if spec.get("workload") == "fullPass"
    ]
    grid_axes = [
        ("traces", "a) liczba śladów"),
        ("totalEvents", "b) liczba zdarzeń"),
        ("attributesPerEvent", "c) liczba atrybutów na zdarzenie"),
        ("eventsPerTrace", "d) kształt logu przy stałej objętości"),
    ]
    query_grid: list[tuple[str, str]] = []
    for axis, axis_caption in grid_axes:
        block = [
            (f"query_{label}_by_{axis}.svg", f"Rys.: {label} ({clause}) — {axis_caption}.")
            for label, clause in grid_queries
            if (plots_dir / f"query_{label}_by_{axis}.svg").exists()
        ]
        if not block:
            continue
        query_grid.append(("", f"**Skalowanie zapytań — {axis_caption}**"))
        query_grid.extend(block)
    if not grid_queries:
        query_grid.append(
            (
                "",
                "*Brak rysunków skalowania: przebieg nie zawiera zapytań klasy „pełny przebieg”. "
                "Zapytania ograniczone oknem nie mogą wykazać zależności od rozmiaru danych — "
                "zob. tabelę dopasowanych wykładników.*",
            ),
        )

    # Storage-scaling probe results (scripts/benchmarks/measure-storage-scaling.py):
    # sequential no-cleanup imports give attributable per-dataset disk deltas for
    # BOTH systems, unlike the benchmark's own storage rows (thesis question 5).
    storage_scaling = join_dataset(read_csv(result_dir / "storage-scaling.csv"), datasets)
    if storage_scaling:
        for series_name, axis, label, log_x in SCALING_AXES:
            axis_label = label + (" (skala log)" if log_x else "")
            rows = [r for r in storage_scaling if r.get("series") == series_name]
            # Only the delta is charted. The `delta / xesBytes` ratio is deliberately
            # no longer plotted: with a constant pre-allocation term it is the
            # hyperbola a/n + b, so its downward curve describes the allocator, not
            # the storage format, and reads as if the format improved with size. The
            # fitted model (a, b, R2) is reported as a table instead.
            write_svg(
                plots_dir / f"storage_scaling_delta_by_{axis}.svg",
                f"Przyrost dysku po imporcie — {label.lower()}",
                axis_label,
                "Przyrost [MiB] (skala log)",
                line_series(rows, axis, "deltaBytes", y_scale=1 / (1024 * 1024)),
                log_x=log_x,
                log_y=True,
                annotate_fit=log_x,
            )

    anchors: list[tuple[str, list[tuple[str, str]], str]] = [
        (
            "Import (Q1)",
            [
                ("import_by_traces.svg", "Rys. Q1a: skalowanie czasu importu — a) liczba śladów."),
                ("import_by_totalEvents.svg", "Rys. Q1b: skalowanie czasu importu — b) liczba zdarzeń."),
                ("import_by_attributesPerEvent.svg", "Rys. Q1c: skalowanie czasu importu — c) liczba atrybutów na zdarzenie."),
                ("import_compare.svg", "Rys. Q1d: mediany czasu importu dla największych datasetów."),
            ],
            "section-end",
        ),
        (
            "Zapytania (Q2)",
            query_grid,
            "intro-end",
        ),
        (
            "Przestrzeń dyskowa",
            [
                ("storage_scaling_delta_by_traces.svg", "Rys. Q3a: przyrost dysku po imporcie — a) liczba śladów (sonda sekwencyjna, oba systemy, skala log-log)."),
                ("storage_scaling_delta_by_totalEvents.svg", "Rys. Q3b: przyrost dysku po imporcie — b) liczba zdarzeń."),
                ("storage_scaling_delta_by_attributesPerEvent.svg", "Rys. Q3c: przyrost dysku po imporcie — c) liczba atrybutów na zdarzenie."),
            ],
            "section-end",
        ),
        (
            "Załącznik: pomiar dysku z protokołu benchmarku",
            [
                (
                    "storage_delta_by_traces.svg",
                    "Rys. Z1: przyrost dysku z protokołu benchmarku. Pomiary nieważne "
                    "(delta zerowa lub ujemna) rysowane są jako przerwa w linii z pustym "
                    "znacznikiem, nigdy jako wartość — te same komórki są oznaczone "
                    "w tabeli powyżej.",
                ),
            ],
            "section-end",
        ),
        (
            "Pamięć operacyjna",
            [
                ("memory_queries_phase.svg", "Rys. Q3c: zużycie RAM w fazie zapytań (mediana i peak, per komponent)."),
            ],
            "section-end",
        ),
    ]
    for ds_name in highlight_datasets:
        anchors.append(
            (
                f"Zapytania (Q2), dataset {ds_name}",
                [
                    (f"query_compare_{ds_name}.svg", f"Rys.: {ds_name} — mediany czasu odpowiedzi obu systemów (skala log)."),
                    (f"query_speedup_{ds_name}.svg", f"Rys.: {ds_name} — stosunek median REFERENCE/LOCAL; wartości powyżej ×1 oznaczają przewagę LOCAL."),
                ],
                "section-end",
            ),
        )
    inject_into_sections(result_dir, anchors)
    inject_markdown_block(result_dir, "Przestrzeń dyskowa", storage_model_block(result_dir, datasets))
    append_tex_figures(result_dir, anchors)

    print(f"Wrote SVG plots to {plots_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
