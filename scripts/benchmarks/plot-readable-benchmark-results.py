#!/usr/bin/env python3
"""Generate protocol-aware SVG figures for one benchmark report.

The script reads only versioned CSV artifacts from one run directory. It uses
the Python standard library and never changes raw measurements. Protocol 25
gives every size-series query its own effect chart, independently selected
linear axis and exact LOCAL/REFERENCE medians in milliseconds. Older protocol
layouts remain reproducible.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import statistics
from collections import defaultdict
from html import escape
from pathlib import Path
from textwrap import wrap


BLUE = "#2563eb"
ORANGE = "#ea580c"
GREEN = "#16a34a"
GRID = "#dbe3ec"
TEXT = "#1f2937"
MUTED = "#64748b"


def nice_ticks_from_zero(maximum: float, target_intervals: int = 5) -> list[float]:
    """Return readable linear ticks that cover zero and the observed maximum."""
    if not math.isfinite(maximum) or maximum <= 0:
        return [0.0, 1.0]
    raw_step = maximum / target_intervals
    magnitude = 10 ** math.floor(math.log10(raw_step))
    step = 10 * magnitude
    for multiplier in (1, 2, 2.5, 5, 10):
        candidate = multiplier * magnitude
        if candidate >= raw_step:
            step = candidate
            break
    axis_maximum = math.ceil(maximum / step) * step
    return [index * step for index in range(round(axis_maximum / step) + 1)]


def axis_label(value: float) -> str:
    if abs(value - round(value)) < 1e-9:
        return str(round(value))
    if abs(value) >= 10:
        return f"{value:.1f}"
    return f"{value:.2f}".rstrip("0")


def rows(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def number(row: dict[str, str], key: str) -> float | None:
    try:
        return float(row[key]) if row.get(key, "") != "" else None
    except ValueError:
        return None


def container_io_total(
    io: list[dict[str, str]],
    system: str,
    phase: str,
    key: str,
    app_boundary: bool = False,
) -> float:
    selected = [
        row for row in io
        if row.get("status") != "COUNTER_RESET"
        and row.get("system") == system
        and row.get("phase") == phase
    ]
    if app_boundary and system == "local" and any(
        row.get("component") == "processm-interpreter" for row in selected
    ):
        selected = [row for row in selected if row.get("component") == "processm-interpreter"]
    # Sum each counter independently. A Docker stats network omission on an
    # internal container must not discard exact cgroup Block I/O from that row.
    values = [number(row, key) for row in selected]
    return sum(value for value in values if value is not None)


def container_io_block_median(
    io: list[dict[str, str]], system: str, phase: str, key: str, app_boundary: bool = False,
) -> float:
    selected = [
        row for row in io
        if row.get("status") != "COUNTER_RESET"
        and row.get("system") == system
        and row.get("phase") == phase
    ]
    if app_boundary and system == "local" and any(
        row.get("component") == "processm-interpreter" for row in selected
    ):
        selected = [row for row in selected if row.get("component") == "processm-interpreter"]
    blocks: dict[tuple[str, str, str], list[dict[str, str]]] = defaultdict(list)
    for row in selected:
        blocks[(row.get("datasetName", ""), row.get("operationLabel", ""), row.get("run", ""))].append(row)
    totals = []
    for block in blocks.values():
        values = [number(row, key) for row in block]
        usable = [value for value in values if value is not None]
        if usable:
            totals.append(sum(usable))
    return statistics.median(totals) if totals else 0.0


def svg_document(width: int, height: int, title: str, body: list[str]) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" role="img" aria-label="{escape(title)}">\n'
        '<style>text{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:#1f2937}'
        '.title{font-size:26px;font-weight:700}.subtitle{font-size:17px;fill:#64748b}'
        '.label{font-size:18px}.small{font-size:15px;fill:#64748b}.axis{stroke:#64748b;stroke-width:1}'
        '.grid{stroke:#dbe3ec;stroke-width:1}.unity{stroke:#111827;stroke-width:1.5;stroke-dasharray:5 4}'
        '</style>\n<rect width="100%" height="100%" fill="white"/>\n'
        + "\n".join(body)
        + "\n</svg>\n"
    )


def text(x: float, y: float, value: object, cls: str = "label", anchor: str = "start") -> str:
    return f'<text x="{x:.1f}" y="{y:.1f}" class="{cls}" text-anchor="{anchor}">{escape(str(value))}</text>'


def query_lines(query: str | None, width: int = 54) -> list[str]:
    if not query:
        return []
    return wrap(f"({query})", width=width, break_long_words=False, break_on_hyphens=False)


def natural_sort_key(value: str) -> tuple[object, ...]:
    size = re.fullmatch(r"size-(\d+)([km])", value.casefold())
    if size:
        multiplier = 1_000 if size.group(2) == "k" else 1_000_000
        return (0, int(size.group(1)) * multiplier)
    return (1,) + tuple(
        (0, int(part)) if part.isdigit() else (1, part.casefold())
        for part in re.split(r"(\d+)", value)
        if part
    )


def dataset_label(dataset: str) -> str:
    labels = {
        "real-sepsis": "Sepsis",
        "real-bpic15-1": "BPIC15-1",
        "real-bpic15-2": "BPIC15-2",
        "real-bpic15-3": "BPIC15-3",
        "real-bpic15-4": "BPIC15-4",
        "real-bpic15-5": "BPIC15-5",
        "real-hospital": "BPIC11 (Hospital)",
        "real-bpic12": "BPIC12",
        "real-bpic13-incidents": "BPIC13 (incidents)",
        "real-bpic13-closed-problems": "BPIC13 (closed problems)",
        "real-bpic13-open-problems": "BPIC13 (open problems)",
        "real-hospital-billing": "Hospital Billing",
        "real-road-traffic": "Road Traffic",
        "real-bpic17": "BPIC17",
        "variants-1": "1 wariant",
        "variants-100": "100 wariantów",
        "variants-2000": "2000 wariantów",
    }
    return labels.get(dataset, dataset)


def query_figure_name(label: str) -> str:
    safe = re.sub(r"[^a-z0-9]+", "-", label.casefold()).strip("-")
    if not safe:
        raise ValueError(f"query label cannot form a figure name: {label!r}")
    return f"fig-query-size-{safe}.svg"


def split_query_figure_name(series: str, label: str) -> str:
    series_names = {
        "variant-scaling": "variants",
        "real-validation": "real",
    }
    if series not in series_names:
        raise ValueError(f"unsupported split series: {series}")
    safe = re.sub(r"[^a-z0-9]+", "-", label.casefold()).strip("-")
    if not safe:
        raise ValueError(f"query label cannot form a figure name: {label!r}")
    return f"fig-query-{series_names[series]}-{safe}.svg"


def size_event_count(dataset: str) -> int:
    match = re.fullmatch(r"size-(\d+)([km])", dataset.casefold())
    if not match:
        raise ValueError(f"not a size dataset: {dataset}")
    return int(match.group(1)) * (1_000 if match.group(2) == "k" else 1_000_000)


def event_axis_label(value: float) -> str:
    if value >= 1_000_000 and value % 1_000_000 == 0:
        return f"{value / 1_000_000:.0f} mln"
    if value >= 1_000 and value % 1_000 == 0:
        return f"{value / 1_000:.0f} tys."
    return axis_label(value)


def effect_forest(
    data: list[dict[str, str]],
    title: str,
    out: Path,
    query_by_label: dict[str, str] | None = None,
    dataset_order: dict[str, int] | None = None,
    show_absolute_medians: bool = False,
    absolute_unit: str = "ms",
) -> None:
    if absolute_unit not in {"ms", "s"}:
        raise ValueError(f"unsupported absolute-time unit: {absolute_unit}")
    valid = [r for r in data if number(r, "ratioReferenceToLocal") is not None]
    groups: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in valid:
        groups[(row.get("displayName") or row["operationLabel"], row["operationLabel"])].append(row)
    query_by_label = query_by_label or {}
    width, left, right = 1200, 500 if show_absolute_medians else 370, 55
    height = max(
        260,
        130 + sum(
            58
            + 20 * len(query_lines(query_by_label.get(label)))
            + (24 if show_absolute_medians else 0)
            + 34 * len(values)
            for (_name, label), values in groups.items()
        ),
    )
    observed_xmax = max(
        1.25,
        max(
            (number(row, "confidenceHigh") or number(row, "ratioReferenceToLocal") or 1 for row in valid),
            default=1,
        ) * 1.08,
    )
    x_ticks = nice_ticks_from_zero(observed_xmax)
    xmax = x_ticks[-1]
    plot_width = width - left - right
    scale = lambda value: left + value / xmax * plot_width
    body = [
        text(30, 34, title, "title"),
        text(
            30,
            57,
            f"Efekt E = mediana REFERENCE / mediana LOCAL; oś X: 0–{xmax:.2f}"
            + (f"; mediany w {absolute_unit}" if show_absolute_medians else ""),
            "subtitle",
        ),
        text(30, 76, "Punkt = E; kreska = 95% CI; zielony = LOCAL szybciej; pomarańczowy = REFERENCE szybciej; niebieski = brak rozstrzygnięcia", "subtitle"),
    ]
    y = 122
    for (group, label), values in groups.items():
        values.sort(key=lambda r: (
            (dataset_order or {}).get(r["datasetName"], 2**31 - 1),
            natural_sort_key(r["datasetName"]),
        ))
        body.append(text(30, y, group, "label"))
        y += 21
        for line in query_lines(query_by_label.get(label)):
            body.append(text(30, y, line, "small"))
            y += 20
        y += 3
        if show_absolute_medians:
            body.append(text(230, y, "Zbiór", "small", "end"))
            body.append(text(340, y, f"LOCAL [{absolute_unit}]", "small", "end"))
            body.append(text(460, y, f"REFERENCE [{absolute_unit}]", "small", "end"))
            body.append(text(left, y, "Efekt R/L", "small"))
            y += 24
        for value in x_ticks:
            x = scale(value)
            body.append(f'<line x1="{x:.1f}" y1="{y-5}" x2="{x:.1f}" y2="{y + 34*len(values)-8}" class="grid"/>')
            body.append(text(x, y + 34 * len(values) + 12, axis_label(value), "small", "middle"))
        if xmax >= 1:
            x_unity = scale(1)
            body.append(f'<line x1="{x_unity:.1f}" y1="{y-5}" x2="{x_unity:.1f}" y2="{y + 34*len(values)-8}" class="unity"/>')
        for row in values:
            cy = y + 13
            point = number(row, "ratioReferenceToLocal") or 0
            low = number(row, "confidenceLow") or point
            high = number(row, "confidenceHigh") or point
            color = GREEN if row.get("verdict") == "LOCAL_FASTER" else ORANGE if row.get("verdict") == "REFERENCE_FASTER" else BLUE
            if show_absolute_medians:
                unit_factor = 1000 if absolute_unit == "ms" else 1
                local_time = (number(row, "localMedianSeconds") or 0) * unit_factor
                reference_time = (number(row, "referenceMedianSeconds") or 0) * unit_factor
                body.append(text(230, cy + 4, dataset_label(row["datasetName"]), "label", "end"))
                body.append(text(340, cy + 4, f"{local_time:.2f}", "label", "end"))
                body.append(text(460, cy + 4, f"{reference_time:.2f}", "label", "end"))
            else:
                body.append(text(left - 12, cy + 4, dataset_label(row["datasetName"]), "label", "end"))
            body.append(f'<line x1="{scale(low):.1f}" y1="{cy}" x2="{scale(high):.1f}" y2="{cy}" stroke="{color}" stroke-width="3"/>')
            body.append(f'<circle cx="{scale(point):.1f}" cy="{cy}" r="5" fill="{color}"/>')
            y += 34
        y += 42
    out.write_text(svg_document(width, height, title, body), encoding="utf-8")


def generate_split_query_figures(run_dir: Path, series: str) -> list[Path]:
    """Write one primary-query effect figure for each operation in a series."""
    if series not in {"variant-scaling", "real-validation"}:
        raise ValueError(f"unsupported split series: {series}")
    comparisons = rows(run_dir / "comparison-results.csv")
    query_rows = rows(run_dir / "queries.csv")
    query_by_label = {
        row["queryLabel"]: row["pql"]
        for row in query_rows
        if row.get("queryLabel") and row.get("pql")
    }
    selected = [
        row for row in comparisons
        if row.get("metric") == "query"
        and row.get("series") == series
        and row.get("role") == "primary"
        and row.get("status") == "OK"
    ]
    labels = [row["queryLabel"] for row in query_rows if row.get("queryLabel")]
    labels.extend(
        row["operationLabel"] for row in selected
        if row.get("operationLabel") not in labels
    )
    figures = run_dir / "figures"
    figures.mkdir(parents=True, exist_ok=True)
    title_prefix = "Wpływ liczby wariantów" if series == "variant-scaling" else "Logi rzeczywiste"
    written = []
    for label in labels:
        data = [row for row in selected if row.get("operationLabel") == label]
        if not data:
            continue
        output = figures / split_query_figure_name(series, label)
        effect_forest(
            data,
            f"{title_prefix} — {data[0].get('displayName') or label}",
            output,
            query_by_label,
            show_absolute_medians=True,
        )
        written.append(output)
    return written


def latency_panels(
    comparisons: list[dict[str, str]],
    summaries: list[dict[str, str]],
    out: Path,
    query_by_label: dict[str, str] | None = None,
) -> None:
    data = [
        r for r in comparisons
        if r.get("metric") == "query"
        and r.get("series") == "size-scaling"
        and r.get("status") == "OK"
        and number(r, "localMedianSeconds") is not None
    ]
    groups: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in data:
        groups[(row.get("displayName") or row["operationLabel"], row["operationLabel"])].append(row)
    query_by_label = query_by_label or {}
    summary_by_key = {
        (row.get("system"), row.get("datasetName"), row.get("queryLabel")): row
        for row in summaries
    }
    pair_counts = sorted({int(r["pairs"]) for r in data if r.get("pairs", "").isdigit()})
    pair_note = (
        f"n={pair_counts[0]} sparowanych powtórzeń na punkt"
        if len(pair_counts) == 1
        else "liczba sparowanych powtórzeń jest podana w tabeli"
    )
    names = list(groups)
    width, height = 1200, 128 + ((len(names) + 1) // 2) * 295
    body = [
        text(30, 34, "Mediany czasu zapytań wraz ze wzrostem danych", "title"),
        text(30, 57, f"Obie osie są liniowe; oś X pokazuje rzeczywistą liczbę zdarzeń; {pair_note}", "subtitle"),
        text(30, 76, "Baseline: niepołączone punkty i IQR (środkowe 50% czasów); pozostałe linie tylko pomagają śledzić serię", "subtitle"),
    ]
    for index, (name, label) in enumerate(names):
        col, row_index = index % 2, index // 2
        x0, y0, panel_w, panel_h = 55 + col * 585, 108 + row_index * 295, 520, 225
        values = sorted(groups[(name, label)], key=lambda r: natural_sort_key(r["datasetName"]))
        maximum_events = max(size_event_count(item["datasetName"]) for item in values)
        x_ticks = nice_ticks_from_zero(maximum_events, target_intervals=4)
        x_maximum = x_ticks[-1]
        observed_values = [
            max(number(r, "localMedianSeconds") or 0, number(r, "referenceMedianSeconds") or 0)
            for r in values
        ]
        if values[0].get("role") == "baseline":
            observed_values.extend(
                number(summary_by_key.get((system, item["datasetName"], label), {}), "q3Seconds") or 0
                for item in values
                for system in ("local", "reference")
            )
        observed_ymax = max(observed_values) * 1.05 or 1
        y_ticks = nice_ticks_from_zero(observed_ymax, target_intervals=4)
        ymax = y_ticks[-1]
        body.append(text(x0, y0, name, "label"))
        panel_query_lines = query_lines(query_by_label.get(label), width=66)
        for line_index, line in enumerate(panel_query_lines):
            body.append(text(x0, y0 + 14 + line_index * 13, line, "small"))
        chart_top = y0 + 22 + len(panel_query_lines) * 13
        chart_bottom = y0 + panel_h - 28
        chart_left = x0 + 48
        chart_right = x0 + panel_w
        for value in x_ticks:
            x = chart_left + value / x_maximum * (chart_right - chart_left)
            body.append(f'<line x1="{x:.1f}" y1="{chart_top}" x2="{x:.1f}" y2="{chart_bottom}" class="grid"/>')
            body.append(text(x, chart_bottom + 17, event_axis_label(value), "small", "middle"))
        for value in y_ticks:
            y = chart_bottom - value / ymax * (chart_bottom - chart_top)
            body.append(f'<line x1="{chart_left}" y1="{y:.1f}" x2="{chart_right}" y2="{y:.1f}" class="grid"/>')
            body.append(text(x0 + 42, y + 3, f"{value*1000:.0f}", "small", "end"))
        for system, key, color in (("LOCAL", "localMedianSeconds", BLUE), ("REFERENCE", "referenceMedianSeconds", ORANGE)):
            points = []
            for item in values:
                x = chart_left + size_event_count(item["datasetName"]) / x_maximum * (chart_right - chart_left)
                value = number(item, key) or 0
                y = chart_bottom - value / ymax * (chart_bottom - chart_top)
                points.append((x, y))
            is_baseline = values[0].get("role") == "baseline"
            if not is_baseline:
                body.append('<polyline fill="none" stroke="%s" stroke-width="2.5" points="%s"/>' % (color, " ".join(f"{x:.1f},{y:.1f}" for x, y in points)))
            else:
                system_key = system.lower()
                for item, (x, _y) in zip(values, points):
                    summary = summary_by_key.get((system_key, item["datasetName"], label), {})
                    q1 = number(summary, "q1Seconds")
                    q3 = number(summary, "q3Seconds")
                    if q1 is None or q3 is None:
                        continue
                    y_low = chart_bottom - q1 / ymax * (chart_bottom - chart_top)
                    y_high = chart_bottom - q3 / ymax * (chart_bottom - chart_top)
                    body.append(f'<line class="baseline-iqr" x1="{x:.1f}" y1="{y_high:.1f}" x2="{x:.1f}" y2="{y_low:.1f}" stroke="{color}" stroke-width="2"/>')
                    body.append(f'<line class="baseline-iqr" x1="{x-4:.1f}" y1="{y_high:.1f}" x2="{x+4:.1f}" y2="{y_high:.1f}" stroke="{color}" stroke-width="2"/>')
                    body.append(f'<line class="baseline-iqr" x1="{x-4:.1f}" y1="{y_low:.1f}" x2="{x+4:.1f}" y2="{y_low:.1f}" stroke="{color}" stroke-width="2"/>')
            body.extend(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{color}"/>' for x, y in points)
        body.append(text(x0 + 5, (chart_top + chart_bottom) / 2, "ms", "small"))
    body.append(f'<line x1="930" y1="50" x2="955" y2="50" stroke="{BLUE}" stroke-width="3"/>')
    body.append(text(960, 54, "LOCAL", "small"))
    body.append(f'<line x1="1035" y1="50" x2="1060" y2="50" stroke="{ORANGE}" stroke-width="3"/>')
    body.append(text(1065, 54, "REFERENCE", "small"))
    out.write_text(svg_document(width, height, "Mediany czasu zapytań", body), encoding="utf-8")


def bar_chart(
    labels: list[str],
    local: list[float],
    reference: list[float],
    title: str,
    unit: str,
    out: Path,
    subtitle: str = "Suma delt w całym przebiegu",
) -> None:
    width, height, left, top, bottom = 1100, 430, 100, 80, 350
    y_ticks = nice_ticks_from_zero(max(local + reference + [1]) * 1.05)
    ymax = y_ticks[-1]
    body = [text(30, 34, title, "title"), text(30, 57, f"{subtitle}; skala liniowa; jednostka: {unit}", "subtitle")]
    plot_w = width - left - 50
    for value in y_ticks:
        y = bottom - value / ymax * (bottom - top)
        body.append(f'<line x1="{left}" y1="{y:.1f}" x2="{width-50}" y2="{y:.1f}" class="grid"/>')
        body.append(text(left - 8, y + 4, axis_label(value), "small", "end"))
    group_w = plot_w / max(1, len(labels))
    for i, label in enumerate(labels):
        center = left + group_w * (i + 0.5)
        for offset, value, color in ((-18, local[i], BLUE), (18, reference[i], ORANGE)):
            h = value / ymax * (bottom - top)
            body.append(f'<rect x="{center+offset-14:.1f}" y="{bottom-h:.1f}" width="28" height="{h:.1f}" fill="{color}"/>')
        body.append(text(center, bottom + 20, label, "small", "middle"))
    body.append(f'<rect x="780" y="48" width="14" height="10" fill="{BLUE}"/>')
    body.append(text(800, 57, "LOCAL", "small"))
    body.append(f'<rect x="890" y="48" width="14" height="10" fill="{ORANGE}"/>')
    body.append(text(910, 57, "REFERENCE", "small"))
    out.write_text(svg_document(width, height, title, body), encoding="utf-8")


def resource_ratio_chart(
    values: list[tuple[str, float, float]],
    title: str,
    subtitle: str,
    unit: str,
    out: Path,
    logarithmic_ratio_axis: bool = False,
) -> None:
    """Show exact resource values beside their REFERENCE/LOCAL ratio."""
    width, left, right = 1200, 560, 55
    row_height = 31
    height = 154 + row_height * len(values)
    ratios = [reference / local for _label, local, reference in values if local > 0]
    if not ratios:
        raise ValueError("resource chart requires at least one positive LOCAL value")

    if logarithmic_ratio_axis:
        ticks = [1.0]
        while ticks[-1] < max(ratios) * 1.05:
            ticks.append(ticks[-1] * 2)
        xmax = ticks[-1]
        scale = lambda value: left + math.log2(max(value, 1)) / math.log2(xmax) * (width - left - right)
        axis_note = "logarytmiczna oś ilorazu (log₂)"
    else:
        ticks = nice_ticks_from_zero(max(1.05, max(ratios) * 1.08))
        xmax = ticks[-1]
        scale = lambda value: left + value / xmax * (width - left - right)
        axis_note = "liniowa oś ilorazu"

    body = [
        text(30, 34, title, "title"),
        text(30, 57, f"{subtitle}; {axis_note}", "subtitle"),
        text(30, 76, "Iloraz = REFERENCE / LOCAL; zielony = mniejsza wartość LOCAL; pomarańczowy = mniejsza wartość REFERENCE", "subtitle"),
        text(245, 105, "Seria / punkt", "small", "end"),
        text(365, 105, f"LOCAL [{unit}]", "small", "end"),
        text(500, 105, f"REFERENCE [{unit}]", "small", "end"),
        text(left, 105, "Iloraz R/L", "small"),
    ]
    plot_bottom = 118 + row_height * len(values)
    for tick in ticks:
        x = scale(tick)
        body.append(f'<line x1="{x:.1f}" y1="112" x2="{x:.1f}" y2="{plot_bottom:.1f}" class="grid"/>')
        body.append(text(x, plot_bottom + 17, axis_label(tick), "small", "middle"))
    unity = scale(1)
    body.append(f'<line x1="{unity:.1f}" y1="112" x2="{unity:.1f}" y2="{plot_bottom:.1f}" class="unity"/>')

    for index, (label, local, reference) in enumerate(values):
        y = 132 + index * row_height
        ratio = reference / local
        color = GREEN if ratio > 1 else ORANGE if ratio < 1 else BLUE
        body.append(text(245, y, label, "label", "end"))
        body.append(text(365, y, f"{local:.1f}", "label", "end"))
        body.append(text(500, y, f"{reference:.1f}", "label", "end"))
        body.append(f'<line x1="{unity:.1f}" y1="{y-4:.1f}" x2="{scale(ratio):.1f}" y2="{y-4:.1f}" stroke="{color}" stroke-width="3"/>')
        body.append(f'<circle cx="{scale(ratio):.1f}" cy="{y-4:.1f}" r="5" fill="{color}"/>')
        body.append(text(scale(ratio) + (8 if ratio >= 1 else -8), y, f"{ratio:.2f}", "small", "start" if ratio >= 1 else "end"))
    out.write_text(svg_document(width, height, title, body), encoding="utf-8")


def memory_rollup(run_dir: Path) -> tuple[float, float, float, float]:
    memory = rows(run_dir / "memory-summary.csv")

    def values(component: str, key: str) -> list[float]:
        return [
            value for row in memory
            if row.get("component") == component and row.get("phase") == "queries"
            for value in [number(row, key)] if value is not None
        ]

    local_medians = values("local-total", "medianBytes")
    reference_medians = values("reference-total", "medianBytes")
    local_peaks = values("local-total", "peakBytes")
    reference_peaks = values("reference-total", "peakBytes")
    mib = 1024 * 1024
    return (
        statistics.median(local_medians) / mib if local_medians else 0,
        statistics.median(reference_medians) / mib if reference_medians else 0,
        max(local_peaks, default=0) / mib,
        max(reference_peaks, default=0) / mib,
    )


def generate_resource_summary_figures(
    size_run: Path,
    variant_run: Path,
    real_run: Path,
    size_memory_run: Path | None = None,
) -> list[Path]:
    """Create compact report-style storage and cross-series memory figures."""
    figures = size_run / "figures"
    figures.mkdir(parents=True, exist_ok=True)
    mib = 1024 * 1024
    storage_rows = rows(size_run / "storage-scaling.csv")
    storage_by_dataset: dict[str, dict[str, float]] = defaultdict(dict)
    for row in storage_rows:
        delta = number(row, "deltaBytes")
        if row.get("datasetName") and row.get("system") and delta is not None:
            storage_by_dataset[row["datasetName"]][row["system"]] = delta / mib
    storage_values = [
        (event_axis_label(size_event_count(dataset)), systems["local"], systems["reference"])
        for dataset, systems in sorted(storage_by_dataset.items(), key=lambda item: natural_sort_key(item[0]))
        if "local" in systems and "reference" in systems
    ]
    if not storage_values:
        raise ValueError(f"no paired storage values in {size_run / 'storage-scaling.csv'}")

    storage_out = figures / "fig-resource-storage.svg"
    resource_ratio_chart(
        storage_values,
        "Trwały rozmiar danych — wzrost rozmiaru logu",
        "Przyrost plików po imporcie na osobno odtworzonym stosie; dokładne wartości w MiB",
        "MiB",
        storage_out,
        logarithmic_ratio_axis=True,
    )

    memory_values: list[tuple[str, float, float]] = []
    for label, run_dir in (
        ("Rozmiar", size_memory_run or size_run),
        ("Warianty", variant_run),
        ("Logi rzeczywiste", real_run),
    ):
        local_median, reference_median, local_peak, reference_peak = memory_rollup(run_dir)
        if min(local_median, reference_median, local_peak, reference_peak) <= 0:
            raise ValueError(f"incomplete query-memory summary in {run_dir}")
        memory_values.extend([
            (f"{label} — mediana", local_median, reference_median),
            (f"{label} — maksimum", local_peak, reference_peak),
        ])
    memory_out = figures / "fig-resource-memory.svg"
    resource_ratio_chart(
        memory_values,
        "Pamięć kontenerowa podczas zapytań",
        "Mediana median bloków oraz maksimum wskazania docker stats; dokładne wartości w MiB",
        "MiB",
        memory_out,
    )
    return [storage_out, memory_out]


def effect_heatmap(
    data: list[dict[str, str]], title: str, out: Path, dataset_order: dict[str, int] | None = None,
) -> None:
    valid = [row for row in data if number(row, "ratioReferenceToLocal") is not None]
    datasets = sorted(
        {row["datasetName"] for row in valid},
        key=lambda name: ((dataset_order or {}).get(name, 2**31 - 1), natural_sort_key(name)),
    )
    operations = []
    for row in valid:
        key = (row.get("displayName") or row["operationLabel"], row["operationLabel"])
        if key not in operations:
            operations.append(key)
    cell_w, cell_h, left, top = 160, 48, 285, 120
    width = max(900, left + cell_w * max(1, len(operations)) + 40)
    height = max(260, top + cell_h * max(1, len(datasets)) + 75)
    body = [
        text(30, 34, title, "title"),
        text(30, 57, "Liczba = REFERENCE / LOCAL; kolor pokazuje log₂ efektu, szary = brak poprawnego porównania", "subtitle"),
    ]
    by_key = {(row["datasetName"], row["operationLabel"]): row for row in valid}
    for column, (display, _label) in enumerate(operations):
        x = left + column * cell_w + cell_w / 2
        for line_index, line in enumerate(wrap(display, width=19)):
            body.append(text(x, 84 + line_index * 17, line, "small", "middle"))
    for row_index, dataset in enumerate(datasets):
        y = top + row_index * cell_h
        body.append(text(left - 10, y + 26, dataset_label(dataset), "label", "end"))
        for column, (_display, label) in enumerate(operations):
            item = by_key.get((dataset, label))
            ratio = number(item or {}, "ratioReferenceToLocal")
            x = left + column * cell_w
            if ratio is None or ratio <= 0:
                color = "#e5e7eb"
                label_text = "—"
            else:
                strength = min(1.0, abs(math.log2(ratio)) / 4.0)
                base = (22, 163, 74) if ratio > 1 else (234, 88, 12)
                color = "#%02x%02x%02x" % tuple(round(245 + (channel - 245) * strength) for channel in base)
                label_text = f"{ratio:.2f}×"
            body.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{cell_w-4}" height="{cell_h-4}" rx="3" fill="{color}"/>')
            body.append(text(x + (cell_w - 4) / 2, y + 25, label_text, "label", "middle"))
    out.write_text(svg_document(width, height, title, body), encoding="utf-8")


def generate(run_dir: Path) -> None:
    comparisons = rows(run_dir / "comparison-results.csv")
    if not comparisons:
        raise SystemExit(f"error: {run_dir}/comparison-results.csv is missing or empty")
    figures = run_dir / "figures"
    figures.mkdir(parents=True, exist_ok=True)
    query_by_label = {
        row["queryLabel"]: row["pql"]
        for row in rows(run_dir / "queries.csv")
        if row.get("queryLabel") and row.get("pql")
    }
    environment_path = run_dir / "environment.json"
    protocol = 1
    if environment_path.is_file():
        try:
            protocol = int(json.loads(environment_path.read_text(encoding="utf-8")).get("benchmarkProtocolVersion", 1))
        except (ValueError, TypeError, json.JSONDecodeError):
            protocol = 1
    if protocol >= 25:
        for stale_figure in figures.glob("*.svg"):
            stale_figure.unlink()
    contextual = protocol >= 23
    size_rows = [
        row for row in comparisons
        if row.get("metric") == "query" and row.get("series") == "size-scaling"
    ]
    if protocol >= 25:
        ordered_labels = list(query_by_label)
        ordered_labels.extend(
            row["operationLabel"] for row in size_rows
            if row.get("operationLabel") not in ordered_labels
        )
        for label in ordered_labels:
            selected = [row for row in size_rows if row.get("operationLabel") == label]
            if not selected:
                continue
            display_name = selected[0].get("displayName") or label
            effect_forest(
                selected,
                f"Efekt wraz ze wzrostem danych — {display_name}",
                figures / query_figure_name(label),
                query_by_label,
                show_absolute_medians=True,
            )
    else:
        effect_forest(
            [row for row in size_rows if row.get("role") != "baseline"],
            "Efekt zapytań — wzrost rozmiaru danych",
            figures / "fig-01-query-effect-size.svg",
            query_by_label,
        )
        latency_panels(
            comparisons,
            rows(run_dir / "query-summary.csv"),
            figures / "fig-02-query-latency-size.svg",
            query_by_label,
        )
    variant_rows = [
        r for r in comparisons
        if r.get("metric") == "query" and r.get("series") == "variant-scaling" and r.get("role") == "primary"
    ]
    if protocol < 25 or variant_rows:
        effect_forest(
            variant_rows,
            "Efekt zapytań — 1, 100 i 2000 wariantów przy 100 tys. zdarzeń",
            figures / "fig-03-query-effect-variants.svg", query_by_label,
            show_absolute_medians=True,
        )
    real_rows = [
        r for r in comparisons
        if r.get("metric") == "query" and r.get("series") == "real-validation" and r.get("role") != "baseline"
    ]
    if protocol < 25 or real_rows:
        effect_forest(
            real_rows,
            "Efekt zapytań — logi rzeczywiste", figures / "fig-04-query-effect-real.svg", query_by_label,
            show_absolute_medians=True,
        )
    figure_offset = 0
    if contextual:
        dataset_rows = rows(run_dir / "datasets.csv")
        bpi_names = {
            row["datasetName"] for row in dataset_rows
            if row.get("collection") == "bpi-challenge"
        }
        bpi_order = {
            row["datasetName"]: int(row["collectionOrder"])
            for row in dataset_rows
            if row.get("datasetName") in bpi_names and row.get("collectionOrder", "").isdigit()
        }
        bpi_rows = [
            row for row in comparisons
            if row.get("metric") == "query" and row.get("datasetName") in bpi_names and row.get("role") != "baseline"
        ]
        if protocol < 25 or bpi_rows:
            effect_forest(
                bpi_rows,
                "Efekt zapytań — BPI Challenge", figures / "fig-05-query-effect-bpi.svg", query_by_label,
                bpi_order,
                show_absolute_medians=True,
            )
            effect_heatmap(
                bpi_rows, "Mapa efektów — BPI Challenge",
                figures / "fig-06-query-effect-bpi-heatmap.svg", bpi_order,
            )
        figure_offset = 2
    import_rows = [
        r for r in comparisons
        if r.get("metric") == "import" and r.get("series") == "size-scaling"
    ]
    if protocol < 25 or import_rows:
        effect_forest(
            import_rows,
            "Efekt czasu importu — wzrost rozmiaru danych", figures / f"fig-{5 + figure_offset:02d}-import-effect.svg",
            show_absolute_medians=True,
            absolute_unit="s",
        )

    mem_local, mem_reference, peak_local, peak_reference = memory_rollup(run_dir)
    mib = 1024 * 1024
    bar_chart(
        ["mediana", "maksimum"],
        [mem_local, peak_local],
        [mem_reference, peak_reference],
        "Pamięć kontenerów podczas zapytań",
        "MiB",
        figures / f"fig-{6 + figure_offset:02d}-container-memory.svg",
        subtitle=("Mediana median bloków i maksimum wskazania docker stats" if contextual else
                  "Mediana i maksimum wskazania docker stats w fazie zapytań"),
    )

    io = rows(run_dir / "container-io.csv")
    phases = ["import", "query"]
    io_metric = container_io_block_median if contextual else container_io_total
    bar_chart(
        phases,
        [
            (io_metric(io, "local", phase, "blockReadBytes") +
             io_metric(io, "local", phase, "blockWriteBytes")) / mib
            for phase in phases
        ],
        [
            (io_metric(io, "reference", phase, "blockReadBytes") +
             io_metric(io, "reference", phase, "blockWriteBytes")) / mib
            for phase in phases
        ],
        "Block I/O kontenerów", "MiB (odczyt + zapis)", figures / f"fig-{7 + figure_offset:02d}-container-block-io.svg",
        subtitle="Mediana delty porównywalnego bloku" if contextual else "Suma delt w całym przebiegu",
    )
    bar_chart(
        phases,
        [
            (io_metric(io, "local", phase, "networkReceiveBytes", True) +
             io_metric(io, "local", phase, "networkTransmitBytes", True)) / mib
            for phase in phases
        ],
        [
            (io_metric(io, "reference", phase, "networkReceiveBytes", True) +
             io_metric(io, "reference", phase, "networkTransmitBytes", True)) / mib
            for phase in phases
        ],
        "Network I/O na granicy aplikacji", "MiB (RX + TX)", figures / f"fig-{8 + figure_offset:02d}-container-network-io.svg",
        subtitle="Mediana delty porównywalnego bloku" if contextual else "Suma delt w całym przebiegu",
    )
    figure_count = len(list(figures.glob("*.svg")))
    print(f"Wrote {figure_count} SVG figures to {figures}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_dir", type=Path)
    parser.add_argument(
        "--split-series",
        action="append",
        choices=("variant-scaling", "real-validation"),
        default=[],
        help="add one exact-median/effect figure per primary query for the selected series",
    )
    parser.add_argument(
        "--resource-summary-runs",
        nargs=2,
        type=Path,
        metavar=("VARIANT_RUN", "REAL_RUN"),
        help="add compact storage and cross-series memory figures, using result_dir as the size run",
    )
    parser.add_argument(
        "--size-memory-run",
        type=Path,
        help="optional size-series run supplying memory data when storage comes from a separate fresh-stack run",
    )
    args = parser.parse_args()
    generate(args.result_dir)
    for series in args.split_series:
        written = generate_split_query_figures(args.result_dir, series)
        print(f"Wrote {len(written)} split {series} SVG figures to {args.result_dir / 'figures'}")
    if args.resource_summary_runs:
        written = generate_resource_summary_figures(
            args.result_dir,
            args.resource_summary_runs[0],
            args.resource_summary_runs[1],
            args.size_memory_run,
        )
        print(f"Wrote {len(written)} resource summary SVG figures to {args.result_dir / 'figures'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
