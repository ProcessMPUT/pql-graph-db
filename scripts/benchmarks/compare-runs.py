#!/usr/bin/env python3
"""Cross-run repeatability report for the thesis benchmark (METODOLOGIA §5 pkt 6).

A single benchmark run cannot answer "are these numbers reproducible?", and the
methodology requires the whole experiment to be repeated at least three times,
the representative run to be chosen by a fixed rule, and the run-to-run spread
to be reported. This tool computes exactly that from several run directories.

    python3 scripts/benchmarks/compare-runs.py tmp/benchmark-results/<runA> <runB> <runC>

Writes `repeatability.csv` and `repeatability.md` into the representative run's
directory (override with --out-dir). Standard library only.
"""

from __future__ import annotations

from pathlib import Path
from statistics import median
import argparse
import csv
import sys

# A run must measure the LOCAL interpreter as well as both databases; a series
# covering only the Neo4j container understates LOCAL and is invalid for Q3
# (METODOLOGIA §5.6). The interpreter appears as the container `processm-interpreter`
# (both systems then measured with docker stats) or, in the development setup, as
# `local-jvm` (host RSS) — the latter is accepted but flagged, because the two sides
# are then measured with different probes.
REQUIRED_MEMORY_COMPONENTS = {"processm-neo4j", "processm-server"}
LOCAL_APP_COMPONENTS = ("processm-interpreter", "local-jvm")
MS = 1000.0


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def query_medians(run: Path) -> dict[tuple[str, str, str], float]:
    """(dataset, query, system) -> median latency in ms, from warm samples."""
    out: dict[tuple[str, str, str], float] = {}
    for row in read_csv(run / "query-summary.csv"):
        try:
            value = float(row["medianSeconds"]) * MS
        except (KeyError, TypeError, ValueError):
            continue
        out[(row["datasetName"], row["queryLabel"], row["system"])] = value
    return out


def memory_components(run: Path) -> set[str]:
    return {row["component"] for row in read_csv(run / "memory-results.csv") if row.get("component")}


def memory_medians(run: Path) -> dict[str, float]:
    """component -> median MiB during the query phase."""
    out: dict[str, float] = {}
    for row in read_csv(run / "memory-summary.csv"):
        if row.get("phase") != "queries":
            continue
        try:
            out[row["component"]] = float(row["medianBytes"]) / (1024 * 1024)
        except (KeyError, TypeError, ValueError):
            continue
    return out


def selection_metric(medians: dict[tuple[str, str, str], float]) -> float | None:
    """One number per run: the median of every LOCAL per-pair median."""
    local = [v for (_, _, system), v in medians.items() if system == "local"]
    return median(local) if local else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dirs", type=Path, nargs="+", help="two or more benchmark run directories")
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument(
        "--significant-ratio",
        type=float,
        default=2.0,
        help="flag pairs whose max/min spread reaches this ratio (default 2.0)",
    )
    args = parser.parse_args()

    if len(args.run_dirs) < 2:
        sys.exit("error: need at least two run directories to compare")

    runs: list[tuple[str, Path, dict[tuple[str, str, str], float], set[str]]] = []
    for path in args.run_dirs:
        if not path.is_dir():
            sys.exit(f"error: not a directory: {path}")
        runs.append((path.name, path, query_medians(path), memory_components(path)))

    print("Ważność przebiegów (METODOLOGIA §5 pkt 6):")
    valid = []
    for name, path, medians, components in runs:
        missing = REQUIRED_MEMORY_COMPONENTS - components
        app_component = next((c for c in LOCAL_APP_COMPONENTS if c in components), None)
        metric = selection_metric(medians)
        if missing or app_component is None:
            absent = sorted(missing) + ([" | ".join(LOCAL_APP_COMPONENTS)] if app_component is None else [])
            print(f"  {name}: NIEWAŻNY — brak składników pamięci: {', '.join(absent)}")
            continue
        if app_component == "local-jvm":
            print(
                f"  {name}: UWAGA — interpreter mierzony na hoście (`local-jvm`), a REFERENCE przez "
                "docker stats; porównanie Q3 nie jest równorzędne",
            )
        if metric is None:
            print(f"  {name}: NIEWAŻNY — brak danych zapytań")
            continue
        print(f"  {name}: ważny, metryka {metric:.2f} ms ({sum(1 for k in medians if k[2] == 'local')} par LOCAL)")
        valid.append((name, path, medians, metric))

    if len(valid) < 2:
        sys.exit("error: fewer than two valid runs — cannot report repeatability")
    if len(valid) < 3:
        print("  UWAGA: metodologia wymaga ≥3 ważnych przebiegów; poniższe liczby są niepełne.")

    # Representative run: the one whose metric is the median across runs. With an
    # even count the methodology takes the older of the two middle runs, which is
    # the lower index after sorting by name (run ids are timestamps).
    by_metric = sorted(valid, key=lambda item: (item[3], item[0]))
    representative = by_metric[(len(by_metric) - 1) // 2]
    print(f"\nPrzebieg reprezentatywny: {representative[0]} (metryka {representative[3]:.2f} ms)")

    # Repeatability: spread of each pair's median across all valid runs.
    keys = set(valid[0][2])
    for _, _, medians, _ in valid[1:]:
        keys &= set(medians)

    rows = []
    for key in sorted(keys):
        values = [medians[key] for _, _, medians, _ in valid]
        low, high = min(values), max(values)
        if low <= 0:
            continue
        rows.append(
            {
                "datasetName": key[0],
                "queryLabel": key[1],
                "system": key[2],
                "runs": len(values),
                "minMs": f"{low:.3f}",
                "maxMs": f"{high:.3f}",
                "medianMs": f"{median(values):.3f}",
                "spreadRatio": f"{high / low:.3f}",
            }
        )

    if not rows:
        sys.exit("error: no (dataset, query, system) triples common to all runs")

    ratios = [float(r["spreadRatio"]) for r in rows]
    flagged = [r for r in rows if float(r["spreadRatio"]) >= args.significant_ratio]
    worst = max(rows, key=lambda r: float(r["spreadRatio"]))

    print("\nPowtarzalność (rozrzut median między przebiegami):")
    print(f"  par porównanych:        {len(rows)}")
    print(f"  mediana rozrzutu:       x{median(ratios):.2f}")
    print(f"  maksymalny rozrzut:     x{max(ratios):.2f}  ({worst['datasetName']}/{worst['queryLabel']}/{worst['system']})")
    print(f"  par z rozrzutem ≥x{args.significant_ratio:g}:   {len(flagged)}")

    out_dir = args.out_dir or representative[1]
    out_dir.mkdir(parents=True, exist_ok=True)

    csv_path = out_dir / "repeatability.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    md = [
        "# Powtarzalność pomiarów między przebiegami",
        "",
        f"- Porównane przebiegi: {', '.join(name for name, _, _, _ in valid)}",
        f"- Przebieg reprezentatywny (METODOLOGIA §5 pkt 6): **{representative[0]}**",
        "",
        "| Przebieg | Metryka wyboru [ms] | Reprezentatywny |",
        "| :--- | ---: | :--- |",
    ]
    for name, _, _, metric in by_metric:
        md.append(f"| {name} | {metric:.2f} | {'tak' if name == representative[0] else '—'} |")
    md += [
        "",
        "Metryka wyboru to mediana ze wszystkich median LOCAL par (dataset, zapytanie)",
        "z próbek warm; reprezentatywny jest przebieg o środkowej wartości tej metryki.",
        "",
        "## Rozrzut median między przebiegami",
        "",
        f"- Par porównanych: {len(rows)}",
        f"- Mediana rozrzutu (max/min): **×{median(ratios):.2f}**",
        f"- Maksymalny rozrzut: **×{max(ratios):.2f}** "
        f"({worst['datasetName']} / {worst['queryLabel']} / {worst['system']})",
        f"- Par z rozrzutem ≥×{args.significant_ratio:g}: **{len(flagged)}**",
        "",
    ]
    if flagged:
        md += [
            "Pary o rozrzucie przekraczającym próg — nie mogą być podstawą wniosku",
            "o przewadze któregokolwiek z systemów:",
            "",
            "| Dataset | Zapytanie | System | min [ms] | max [ms] | Rozrzut |",
            "| :--- | :--- | :--- | ---: | ---: | ---: |",
        ]
        for r in sorted(flagged, key=lambda r: -float(r["spreadRatio"])):
            md.append(
                f"| {r['datasetName']} | {r['queryLabel']} | {r['system']} "
                f"| {r['minMs']} | {r['maxMs']} | ×{r['spreadRatio']} |"
            )
    else:
        md.append(
            f"Żadna para nie osiągnęła progu ×{args.significant_ratio:g} — rozrzut run-to-run "
            "jest mniejszy niż różnice uznawane za istotne.",
        )
    md.append("")

    # Memory (Q3) needs the same treatment as latency: a single run cannot show
    # whether a difference between the systems is real. The application component in
    # particular depends on where GC cycles fall during the query phase, so a ranking
    # read off one run can invert between runs.
    mem_runs = [(name, memory_medians(path)) for name, path, _, _ in valid]
    components = sorted(set().union(*(set(m) for _, m in mem_runs))) if mem_runs else []
    if components:
        md += ["", "## Pamięć (Q3): rozrzut między przebiegami", "",
               "| Składnik | " + " | ".join(name for name, _ in mem_runs) + " | Rozrzut |",
               "| :--- | " + " | ".join("---:" for _ in mem_runs) + " | ---: |"]
        for comp in components:
            values = [m.get(comp) for _, m in mem_runs]
            cells = " | ".join(f"{v:.0f}" if v is not None else "—" for v in values)
            present = [v for v in values if v]
            spread = f"×{max(present) / min(present):.2f}" if len(present) > 1 and min(present) > 0 else "—"
            md.append(f"| {comp} | {cells} | {spread} |")
        md += ["", "Wartości to mediany [MiB] z fazy zapytań. Jeżeli rozrzut składnika między",
               "przebiegami jest porównywalny z różnicą między systemami, pomiar nie",
               "uprawnia do wniosku o przewadze żadnego z nich.", ""]

    md_path = out_dir / "repeatability.md"
    md_path.write_text("\n".join(md), encoding="utf-8")

    print(f"\nZapisano:\n  {csv_path}\n  {md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
