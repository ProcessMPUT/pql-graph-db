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
) -> None:
    clean = {
        name: sorted((x, y) for x, y in points if math.isfinite(x) and math.isfinite(y))
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

    def sx(x: float) -> float:
        return PAD_LEFT + (x - x_min) / (x_max - x_min) * (WIDTH - PAD_LEFT - PAD_RIGHT)

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

    for tick in range(6):
        ratio = tick / 5
        x = x_min + (x_max - x_min) * ratio
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


def line_series(rows: list[dict[str, str]], x_field: str, y_field: str, series_field: str = "system") -> dict[str, list[tuple[float, float]]]:
    series: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for row in rows:
        series[row.get(series_field, "unknown")].append((number(row.get(x_field)), number(row.get(y_field))))
    return dict(series)


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

    for series_name, axis, label in [
        ("trace-scaling", "traces", "Number of traces"),
        ("event-scaling", "totalEvents", "Number of events"),
        ("attribute-scaling", "attributesPerEvent", "Attributes per event"),
    ]:
        import_rows = [r for r in imports if r.get("series") == series_name and r.get("status") == "OK"]
        write_svg(
            plots_dir / f"import_by_{axis}.svg",
            f"Import time by {label.lower()}",
            label,
            "Seconds",
            line_series(import_rows, axis, "seconds"),
        )

        storage_rows = [r for r in storage if r.get("series") == series_name and r.get("status") == "OK"]
        write_svg(
            plots_dir / f"storage_delta_by_{axis}.svg",
            f"Storage delta by {label.lower()}",
            label,
            "Bytes",
            line_series(storage_rows, axis, "deltaBytes"),
        )
        write_svg(
            plots_dir / f"storage_overhead_ratio_by_{axis}.svg",
            f"Storage overhead ratio by {label.lower()}",
            label,
            "Delta / XES bytes",
            line_series(storage_rows, axis, "deltaToXesRatio"),
        )

        labels = sorted({r.get("queryLabel", "") for r in queries if r.get("series") == series_name})
        for query_label in labels:
            query_rows = [
                r for r in queries
                if r.get("series") == series_name and r.get("queryLabel") == query_label
            ]
            write_svg(
                plots_dir / f"query_{query_label}_by_{axis}.svg",
                f"{query_label}: median query time",
                label,
                "Median seconds",
                line_series(query_rows, axis, "medianSeconds"),
            )

    print(f"Wrote SVG plots to {plots_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
