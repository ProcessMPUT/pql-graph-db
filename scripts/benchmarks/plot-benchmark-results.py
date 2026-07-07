#!/usr/bin/env python3
"""Generate dependency-free SVG charts from benchmark CSV outputs."""

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path


WIDTH = 960
HEIGHT = 560
PAD_LEFT = 86
PAD_RIGHT = 32
PAD_TOP = 52
PAD_BOTTOM = 82
COLORS = {
    "local": "#2563eb",
    "reference": "#dc2626",
}


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def number(value: str | None) -> float:
    if value is None or value == "":
        return math.nan
    return float(value.replace(",", "."))


def write_svg(
    path: Path,
    title: str,
    x_label: str,
    y_label: str,
    series: dict[str, list[tuple[float, float]]],
    log_x: bool = False,
) -> None:
    clean = {
        name: sorted(
            (x, y) for x, y in points
            if math.isfinite(x) and math.isfinite(y) and (not log_x or x > 0)
        )
        for name, points in series.items()
    }
    clean = {name: points for name, points in clean.items() if points}
    if not clean:
        return

    xs = [x for points in clean.values() for x, _ in points]
    ys = [y for points in clean.values() for _, y in points]
    x_min, x_max = min(xs), max(xs)
    y_min, y_max = 0.0, max(ys)
    if x_min == x_max:
        x_min -= 1
        x_max += 1
    if y_min == y_max:
        y_max += 1

    def tx(x: float) -> float:
        return math.log10(x) if log_x else x

    def sx(x: float) -> float:
        return PAD_LEFT + (tx(x) - tx(x_min)) / (tx(x_max) - tx(x_min)) * (WIDTH - PAD_LEFT - PAD_RIGHT)

    def sy(y: float) -> float:
        return HEIGHT - PAD_BOTTOM - (y - y_min) / (y_max - y_min) * (HEIGHT - PAD_TOP - PAD_BOTTOM)

    lines: list[str] = []
    lines.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}">')
    lines.append('<rect width="100%" height="100%" fill="white"/>')
    lines.append(f'<text x="{WIDTH / 2}" y="28" text-anchor="middle" font-family="Arial" font-size="22" font-weight="700">{escape(title)}</text>')
    lines.append(f'<line x1="{PAD_LEFT}" y1="{HEIGHT - PAD_BOTTOM}" x2="{WIDTH - PAD_RIGHT}" y2="{HEIGHT - PAD_BOTTOM}" stroke="#111827"/>')
    lines.append(f'<line x1="{PAD_LEFT}" y1="{PAD_TOP}" x2="{PAD_LEFT}" y2="{HEIGHT - PAD_BOTTOM}" stroke="#111827"/>')

    for tick in range(6):
        ratio = tick / 5
        y = y_min + (y_max - y_min) * ratio
        py = sy(y)
        lines.append(f'<line x1="{PAD_LEFT - 6}" y1="{py:.2f}" x2="{WIDTH - PAD_RIGHT}" y2="{py:.2f}" stroke="#e5e7eb"/>')
        lines.append(f'<text x="{PAD_LEFT - 10}" y="{py + 4:.2f}" text-anchor="end" font-family="Arial" font-size="12">{format_num(y)}</text>')

    distinct_xs = sorted({x for points in clean.values() for x, _ in points})
    x_ticks = distinct_xs if len(distinct_xs) <= 8 else [x_min + (x_max - x_min) * t / 5 for t in range(6)]
    for x in x_ticks:
        px = sx(x)
        lines.append(f'<line x1="{px:.2f}" y1="{HEIGHT - PAD_BOTTOM}" x2="{px:.2f}" y2="{HEIGHT - PAD_BOTTOM + 6}" stroke="#111827"/>')
        lines.append(f'<text x="{px:.2f}" y="{HEIGHT - PAD_BOTTOM + 24}" text-anchor="middle" font-family="Arial" font-size="12">{format_num(x)}</text>')

    for name, points in clean.items():
        color = COLORS.get(name, "#16a34a")
        polyline = " ".join(f"{sx(x):.2f},{sy(y):.2f}" for x, y in points)
        lines.append(f'<polyline fill="none" stroke="{color}" stroke-width="3" points="{polyline}"/>')
        for x, y in points:
            lines.append(f'<circle cx="{sx(x):.2f}" cy="{sy(y):.2f}" r="4" fill="{color}"/>')

    legend_x = PAD_LEFT
    legend_y = HEIGHT - 28
    for index, name in enumerate(clean):
        x = legend_x + index * 150
        color = COLORS.get(name, "#16a34a")
        lines.append(f'<rect x="{x}" y="{legend_y - 12}" width="18" height="4" fill="{color}"/>')
        lines.append(f'<text x="{x + 26}" y="{legend_y - 6}" font-family="Arial" font-size="13">{escape(name)}</text>')

    lines.append(f'<text x="{WIDTH / 2}" y="{HEIGHT - 24}" text-anchor="middle" font-family="Arial" font-size="14">{escape(x_label)}</text>')
    lines.append(
        f'<text x="22" y="{HEIGHT / 2}" text-anchor="middle" font-family="Arial" font-size="14" transform="rotate(-90 22 {HEIGHT / 2})">{escape(y_label)}</text>'
    )
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def escape(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def format_num(value: float) -> str:
    if abs(value) >= 1000:
        return f"{value:,.0f}".replace(",", " ")
    if abs(value) >= 10:
        return f"{value:.1f}"
    return f"{value:.3g}"


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
) -> dict[str, list[tuple[float, float]]]:
    series: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for row in rows:
        series[row.get(series_field, "unknown")].append(
            (number(row.get(x_field)), number(row.get(y_field)) * y_scale)
        )
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

    for series_name, axis, label, log_x in [
        ("trace-scaling", "traces", "Liczba trace'ów", True),
        ("event-scaling", "totalEvents", "Łączna liczba zdarzeń", True),
        ("attribute-scaling", "attributesPerEvent", "Atrybuty na zdarzenie", False),
    ]:
        axis_label = label + (" (skala log)" if log_x else "")
        import_rows = [r for r in imports if r.get("series") == series_name and r.get("status") == "OK"]
        write_svg(
            plots_dir / f"import_by_{axis}.svg",
            f"Import: skalowanie — {label.lower()}",
            axis_label,
            "Czas importu [s]",
            line_series(import_rows, axis, "seconds"),
            log_x=log_x,
        )

        storage_rows = [r for r in storage if r.get("series") == series_name and r.get("status") == "OK"]
        write_svg(
            plots_dir / f"storage_delta_by_{axis}.svg",
            f"Przyrost dysku po imporcie — {label.lower()}",
            axis_label,
            "Bajty",
            line_series(storage_rows, axis, "deltaBytes"),
            log_x=log_x,
        )
        write_svg(
            plots_dir / f"storage_overhead_ratio_by_{axis}.svg",
            f"Współczynnik ekspansji dysku (delta / XES) — {label.lower()}",
            axis_label,
            "Delta / bajty XES",
            line_series(storage_rows, axis, "deltaToXesRatio"),
            log_x=log_x,
        )

        labels = sorted({r.get("queryLabel", "") for r in queries if r.get("series") == series_name})
        for query_label in labels:
            query_rows = [
                r for r in queries
                if r.get("series") == series_name and r.get("queryLabel") == query_label
            ]
            write_svg(
                plots_dir / f"query_{query_label}_by_{axis}.svg",
                f"{query_label}: skalowanie mediany czasu zapytania",
                axis_label,
                "Mediana [ms]",
                line_series(query_rows, axis, "medianSeconds", y_scale=1000.0),
                log_x=log_x,
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
    grid_queries = [
        ("hierarchyWindow", "klauzula `limit` (okno hierarchii)"),
        ("customAttrFilter", "klauzula `where` po atrybucie niestandardowym"),
        ("timestampNameOrder", "klauzula `order by`"),
        ("eventNameGroup", "klauzula `group by`"),
        ("timestampAggregates", "klauzule `select` + agregacje min/max/count"),
    ]
    grid_axes = [
        ("traces", "a) liczba śladów"),
        ("totalEvents", "b) liczba zdarzeń"),
        ("attributesPerEvent", "c) liczba atrybutów na zdarzenie"),
    ]
    query_grid: list[tuple[str, str]] = []
    for axis, axis_caption in grid_axes:
        query_grid.append(("", f"**Skalowanie zapytań — {axis_caption}**"))
        for label, clause in grid_queries:
            query_grid.append(
                (f"query_{label}_by_{axis}.svg", f"Rys.: {label} ({clause}) — {axis_caption}."),
            )

    # Storage-scaling probe results (scripts/benchmarks/measure-storage-scaling.ps1):
    # sequential no-cleanup imports give attributable per-dataset disk deltas for
    # BOTH systems, unlike the benchmark's own storage rows (thesis question 5).
    storage_scaling = join_dataset(read_csv(result_dir / "storage-scaling.csv"), datasets)
    for row in storage_scaling:
        # Recompute the ratio from the integer byte fields — robust against
        # locale-formatted ratio columns from older probe versions.
        delta, xes = number(row.get("deltaBytes")), number(row.get("xesBytes"))
        row["deltaToXesRatio"] = str(delta / xes) if xes and math.isfinite(delta) else ""
    if storage_scaling:
        for series_name, axis, label, log_x in [
            ("trace-scaling", "traces", "Liczba trace'ów", True),
            ("event-scaling", "totalEvents", "Łączna liczba zdarzeń", True),
            ("attribute-scaling", "attributesPerEvent", "Atrybuty na zdarzenie", False),
        ]:
            axis_label = label + (" (skala log)" if log_x else "")
            rows = [r for r in storage_scaling if r.get("series") == series_name]
            write_svg(
                plots_dir / f"storage_scaling_delta_by_{axis}.svg",
                f"Przyrost dysku po imporcie — {label.lower()}",
                axis_label,
                "Przyrost [MiB]",
                line_series(rows, axis, "deltaBytes", y_scale=1 / (1024 * 1024)),
                log_x=log_x,
            )
            write_svg(
                plots_dir / f"storage_scaling_ratio_by_{axis}.svg",
                f"Ekspansja względem bazowego XML — {label.lower()}",
                axis_label,
                "Bajty bazy / bajty XES",
                line_series(rows, axis, "deltaToXesRatio"),
                log_x=log_x,
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
                ("storage_scaling_delta_by_traces.svg", "Rys. Q3a: przyrost dysku po imporcie — a) liczba śladów (sonda sekwencyjna, oba systemy)."),
                ("storage_scaling_delta_by_totalEvents.svg", "Rys. Q3b: przyrost dysku po imporcie — b) liczba zdarzeń."),
                ("storage_scaling_delta_by_attributesPerEvent.svg", "Rys. Q3c: przyrost dysku po imporcie — c) liczba atrybutów na zdarzenie."),
                ("storage_scaling_ratio_by_traces.svg", "Rys. Q3d: współczynnik ekspansji względem bazowego XML — a) liczba śladów."),
                ("storage_scaling_ratio_by_totalEvents.svg", "Rys. Q3e: współczynnik ekspansji względem bazowego XML — b) liczba zdarzeń."),
                ("storage_scaling_ratio_by_attributesPerEvent.svg", "Rys. Q3f: współczynnik ekspansji względem bazowego XML — c) liczba atrybutów na zdarzenie."),
                ("storage_delta_by_traces.svg", "Rys.: przyrost dysku mierzony w protokole benchmarku (REFERENCE; LOCAL poniżej granulacji alokacji — zob. METODOLOGIA §Q3)."),
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

    print(f"Wrote SVG plots to {plots_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
