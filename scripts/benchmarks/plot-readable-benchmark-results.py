#!/usr/bin/env python3
"""Generate protocol-aware linear-scale SVG figures for one benchmark report.

The script reads only versioned CSV artifacts from one run directory. It uses
the Python standard library and never changes raw measurements. Protocol 23+
adds two BPI Challenge figures to the historical eight-figure contract.
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
        '.title{font-size:22px;font-weight:700}.subtitle{font-size:13px;fill:#64748b}'
        '.label{font-size:12px}.small{font-size:10px;fill:#64748b}.axis{stroke:#64748b;stroke-width:1}'
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
) -> None:
    valid = [r for r in data if number(r, "ratioReferenceToLocal") is not None]
    groups: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in valid:
        groups[(row.get("displayName") or row["operationLabel"], row["operationLabel"])].append(row)
    query_by_label = query_by_label or {}
    width, left, right = 1200, 370, 55
    height = max(
        260,
        120 + sum(
            48 + 14 * len(query_lines(query_by_label.get(label))) + 28 * len(values)
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
        text(30, 57, f"Efekt = mediana REFERENCE / mediana LOCAL; wspólna liniowa oś X: 0–{xmax:.2f}", "subtitle"),
        text(30, 76, "Kropka = efekt; kreska = sparowany 95% CI; zielony = LOCAL szybszy; pomarańczowy = REFERENCE szybszy; niebieski = brak rozstrzygnięcia lub wynik opisowy", "subtitle"),
    ]
    y = 114
    for (group, label), values in groups.items():
        values.sort(key=lambda r: (
            (dataset_order or {}).get(r["datasetName"], 2**31 - 1),
            natural_sort_key(r["datasetName"]),
        ))
        body.append(text(30, y, group, "label"))
        y += 15
        for line in query_lines(query_by_label.get(label)):
            body.append(text(30, y, line, "small"))
            y += 14
        y += 3
        for value in x_ticks:
            x = scale(value)
            body.append(f'<line x1="{x:.1f}" y1="{y-5}" x2="{x:.1f}" y2="{y + 28*len(values)-8}" class="grid"/>')
            body.append(text(x, y + 28 * len(values) + 7, axis_label(value), "small", "middle"))
        if xmax >= 1:
            x_unity = scale(1)
            body.append(f'<line x1="{x_unity:.1f}" y1="{y-5}" x2="{x_unity:.1f}" y2="{y + 28*len(values)-8}" class="unity"/>')
        for row in values:
            cy = y + 10
            point = number(row, "ratioReferenceToLocal") or 0
            low = number(row, "confidenceLow") or point
            high = number(row, "confidenceHigh") or point
            color = GREEN if row.get("verdict") == "LOCAL_FASTER" else ORANGE if row.get("verdict") == "REFERENCE_FASTER" else BLUE
            body.append(text(left - 12, cy + 4, dataset_label(row["datasetName"]), "label", "end"))
            body.append(f'<line x1="{scale(low):.1f}" y1="{cy}" x2="{scale(high):.1f}" y2="{cy}" stroke="{color}" stroke-width="3"/>')
            body.append(f'<circle cx="{scale(point):.1f}" cy="{cy}" r="5" fill="{color}"/>')
            y += 28
        y += 35
    out.write_text(svg_document(width, height, title, body), encoding="utf-8")


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
    cell_w, cell_h, left, top = 145, 42, 235, 105
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
            body.append(text(x, 82 + line_index * 12, line, "small", "middle"))
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
    contextual = protocol >= 23
    effect_forest(
        [r for r in comparisons if r.get("metric") == "query" and r.get("series") == "size-scaling" and r.get("role") != "baseline"],
        "Efekt zapytań — wzrost rozmiaru danych", figures / "fig-01-query-effect-size.svg", query_by_label,
    )
    latency_panels(
        comparisons,
        rows(run_dir / "query-summary.csv"),
        figures / "fig-02-query-latency-size.svg",
        query_by_label,
    )
    effect_forest(
        [r for r in comparisons if r.get("metric") == "query" and r.get("series") == "variant-scaling" and r.get("role") == "primary"],
        "Efekt zapytań — 1, 100 i 2000 wariantów przy 100 tys. zdarzeń", figures / "fig-03-query-effect-variants.svg", query_by_label,
    )
    effect_forest(
        [r for r in comparisons if r.get("metric") == "query" and r.get("series") == "real-validation" and r.get("role") != "baseline"],
        "Efekt zapytań — logi rzeczywiste", figures / "fig-04-query-effect-real.svg", query_by_label,
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
        effect_forest(
            bpi_rows,
            "Efekt zapytań — BPI Challenge", figures / "fig-05-query-effect-bpi.svg", query_by_label,
            bpi_order,
        )
        effect_heatmap(
            bpi_rows, "Mapa efektów — BPI Challenge",
            figures / "fig-06-query-effect-bpi-heatmap.svg", bpi_order,
        )
        figure_offset = 2
    effect_forest(
        [r for r in comparisons if r.get("metric") == "import" and r.get("series") == "size-scaling"],
        "Efekt czasu importu — wzrost rozmiaru danych", figures / f"fig-{5 + figure_offset:02d}-import-effect.svg",
    )

    memory = rows(run_dir / "memory-summary.csv")
    def memory_values(component: str, key: str) -> list[float]:
        return [
            value for row in memory
            if row.get("component") == component and row.get("phase") == "queries"
            for value in [number(row, key)] if value is not None
        ]
    local_medians = memory_values("local-total", "medianBytes")
    reference_medians = memory_values("reference-total", "medianBytes")
    local_peaks = memory_values("local-total", "peakBytes")
    reference_peaks = memory_values("reference-total", "peakBytes")
    mem_local = statistics.median(local_medians) if local_medians else 0
    mem_reference = statistics.median(reference_medians) if reference_medians else 0
    peak_local = max(local_peaks, default=0)
    peak_reference = max(reference_peaks, default=0)
    mib = 1024 * 1024
    bar_chart(
        ["mediana", "maksimum"],
        [mem_local / mib, peak_local / mib],
        [mem_reference / mib, peak_reference / mib],
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
    print(f"Wrote {8 + figure_offset} linear-scale SVG figures to {figures}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_dir", type=Path)
    args = parser.parse_args()
    generate(args.result_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
