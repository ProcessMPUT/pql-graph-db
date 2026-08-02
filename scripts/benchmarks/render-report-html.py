#!/usr/bin/env python3
"""Render a benchmark run's thesis-report.md into ONE self-contained HTML file
with every chart inlined as SVG — no dependency on the plots/ directory, so the
file can be shared, opened in a browser, or printed to PDF as-is.

Run the chart generator first so thesis-report.md has its figures embedded:

    python3 scripts/benchmarks/plot-benchmark-results.py tmp/benchmark-results/<runId>
    python3 scripts/benchmarks/render-report-html.py     tmp/benchmark-results/<runId>

Standard library only — no `pip install`. The Markdown it converts is produced
by ThesisReportWriter.kt and the plot injector, so it is a small, fixed subset
(headings, pipe tables, bullet lists, `![](…svg)` figures, inline `**bold**`,
`*italic*`, `` `code` ``, and HTML comments); this renders exactly that subset
rather than pulling in a general Markdown engine. Output defaults to
`<runId>/thesis-report.html`.
"""
from __future__ import annotations

from html import escape
from pathlib import Path
import argparse
import csv
import json
import re
import sys

STYLE = """
  :root { color-scheme: light; }
  body { font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif;
    max-width: 1040px; margin: 0 auto; padding: 32px 24px 96px;
    color: #1f2933; line-height: 1.55; background: #fff; }
  h1 { font-size: 30px; border-bottom: 3px solid #2563eb; padding-bottom: 12px; }
  h2 { font-size: 23px; margin-top: 44px; border-bottom: 1px solid #e5e7eb; padding-bottom: 6px; }
  h3 { font-size: 18px; margin-top: 32px; color: #334155; }
  table { border-collapse: collapse; width: 100%; margin: 16px 0; font-size: 13.5px; }
  .table-wrap { width: 100%; overflow-x: auto; }
  th, td { border: 1px solid #d1d5db; padding: 6px 10px; text-align: right; }
  th { background: #f1f5f9; text-align: center; font-weight: 600; }
  td:first-child, th:first-child { text-align: left; }
  tr:nth-child(even) td { background: #f8fafc; }
  figure.chart { margin: 20px 0 8px; text-align: center; }
  figure.chart svg { max-width: 100%; height: auto; border: 1px solid #e5e7eb;
    border-radius: 8px; background: #fff; }
  em { color: #64748b; }
  p > em { display: block; font-size: 13px; margin: 4px 0 24px; }
  code { background: #f1f5f9; padding: 1px 5px; border-radius: 4px; font-size: 90%; }
  blockquote { border-left: 4px solid #cbd5e1; margin: 16px 0; padding: 4px 16px;
    color: #475569; background: #f8fafc; }
"""

IMAGE_RE = re.compile(r"^!\[([^\]]*)\]\(([^)]+\.svg)\)\s*$", re.MULTILINE)
HEADING_RE = re.compile(r"^(#{1,3})\s+(.*)$")
COMMENT_RE = re.compile(r"^<!--.*-->\s*$")
CODE_SPAN_RE = re.compile(r"`([^`]+)`")
BOLD_RE = re.compile(r"\*\*([^*]+)\*\*")
ITALIC_RE = re.compile(r"\*([^*]+)\*")
SEPARATOR_CELL_RE = re.compile(r"^:?-{3,}:?$")


def inline(text: str) -> str:
    """Escape HTML, then apply the inline subset: code, bold, italic.

    Code spans are pulled out first so their contents are never treated as
    emphasis and a `*` inside a caption's code (e.g. `min(^e:*)`) can't break
    the surrounding italic.
    """
    spans: list[str] = []

    def stash(match: re.Match) -> str:
        spans.append(f"<code>{escape(match.group(1))}</code>")
        return f"\x00{len(spans) - 1}\x00"

    text = CODE_SPAN_RE.sub(stash, text)
    text = escape(text)
    text = BOLD_RE.sub(r"<strong>\1</strong>", text)
    text = ITALIC_RE.sub(r"<em>\1</em>", text)
    text = re.sub(r"\x00(\d+)\x00", lambda m: spans[int(m.group(1))], text)
    return text


def cell_alignment(separator_cells: list[str]) -> list[str]:
    aligns = []
    for cell in separator_cells:
        cell = cell.strip()
        left, right = cell.startswith(":"), cell.endswith(":")
        if left and right:
            aligns.append("center")
        elif right:
            aligns.append("right")
        elif left:
            aligns.append("left")
        else:
            aligns.append("")
    return aligns


def split_row(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def is_separator(line: str) -> bool:
    return all(SEPARATOR_CELL_RE.match(c.strip()) for c in split_row(line))


class Renderer:
    def __init__(self, run_dir: Path) -> None:
        self.run_dir = run_dir
        self.out: list[str] = []

    def inline_svg(self, alt: str, rel_path: str) -> str:
        svg_path = self.run_dir / rel_path
        if not svg_path.is_file():
            return f"<p><em>[brakujący wykres: {escape(rel_path)}]</em></p>"
        svg = re.sub(r"^\s*<\?xml[^>]*\?>\s*", "", svg_path.read_text(encoding="utf-8"))
        return f'<figure class="chart">{svg}</figure>'

    def table(self, header: str, separator: str, body: list[str]) -> None:
        aligns = cell_alignment(split_row(separator))

        def style(index: int) -> str:
            align = aligns[index] if index < len(aligns) else ""
            return f' style="text-align:{align}"' if align else ""

        self.out.append('<div class="table-wrap"><table>')
        self.out.append("<thead><tr>")
        for i, cell in enumerate(split_row(header)):
            self.out.append(f"<th{style(i)}>{inline(cell)}</th>")
        self.out.append("</tr></thead>")
        self.out.append("<tbody>")
        for row in body:
            self.out.append("<tr>")
            for i, cell in enumerate(split_row(row)):
                self.out.append(f"<td{style(i)}>{inline(cell)}</td>")
            self.out.append("</tr>")
        self.out.append("</tbody></table></div>")

    def render(self, md: str) -> str:
        lines = md.splitlines()
        i, n = 0, len(lines)
        while i < n:
            line = lines[i]

            if not line.strip() or COMMENT_RE.match(line):
                i += 1
                continue

            heading = HEADING_RE.match(line)
            if heading:
                level = len(heading.group(1))
                self.out.append(f"<h{level}>{inline(heading.group(2).strip())}</h{level}>")
                i += 1
                continue

            image = IMAGE_RE.match(line)
            if image:
                self.out.append(self.inline_svg(image.group(1), image.group(2)))
                i += 1
                continue

            # table: a pipe row immediately followed by a separator row
            if line.lstrip().startswith("|") and i + 1 < n and is_separator(lines[i + 1]):
                header, separator = line, lines[i + 1]
                i += 2
                body = []
                while i < n and lines[i].lstrip().startswith("|"):
                    body.append(lines[i])
                    i += 1
                self.table(header, separator, body)
                continue

            # bullet list
            if line.lstrip().startswith("- "):
                self.out.append("<ul>")
                while i < n and lines[i].lstrip().startswith("- "):
                    self.out.append(f"<li>{inline(lines[i].lstrip()[2:])}</li>")
                    i += 1
                self.out.append("</ul>")
                continue

            # one or more Markdown quote lines
            if line.lstrip().startswith(">"):
                quoted = []
                while i < n and lines[i].lstrip().startswith(">"):
                    quoted.append(lines[i].lstrip()[1:].lstrip())
                    i += 1
                self.out.append(f"<blockquote><p>{inline(' '.join(quoted))}</p></blockquote>")
                continue

            # paragraph: gather until a blank line or a block starter
            para = []
            while i < n and lines[i].strip() and not COMMENT_RE.match(lines[i]):
                stripped = lines[i]
                if (
                    HEADING_RE.match(stripped)
                    or IMAGE_RE.match(stripped)
                    or stripped.lstrip().startswith("- ")
                    or stripped.lstrip().startswith(">")
                    or stripped.lstrip().startswith("|")
                ):
                    break
                para.append(stripped.strip())
                i += 1
            if para:
                self.out.append(f"<p>{inline(' '.join(para))}</p>")

        return "\n".join(self.out)


def build(run_dir: Path, out_path: Path) -> None:
    base = run_dir / "thesis-report.md"
    series = run_dir / "thesis-report-series.md"
    if series.is_file():
        if series.stat().st_mtime < base.stat().st_mtime:
            raise SystemExit(
                "error: thesis-report-series.md is older than thesis-report.md; "
                "rerun compare-runs.py after rebuilding plots",
            )
        md_text = series.read_text(encoding="utf-8")
        required_series_files = (
            "repeatability.csv",
            "series-comparison.csv",
            "series-cell-stability.csv",
            "series-import.csv",
            "series-import-comparison.csv",
            "series-scaling.csv",
            "series-scaling-exploratory.csv",
            "report-provenance.json",
            "storage-scaling.csv",
            "thesis-tables-series.tex",
        )
        missing_series_files = [name for name in required_series_files if not (run_dir / name).is_file()]
        if missing_series_files:
            raise SystemExit(
                "error: final report is missing cross-run artifacts: " + ", ".join(missing_series_files),
            )
        storage_rows = read_csv_rows(run_dir / "storage-scaling.csv")
        if not storage_rows or any(
            row.get("measurementMode") != "isolated-fresh-stack" for row in storage_rows
        ):
            raise SystemExit(
                "error: storage-scaling.csv is not an isolated-fresh-stack final Q3 probe",
            )
        provenance = read_json_object(run_dir / "report-provenance.json")
        required_provenance = (
            "measurementGitCommit", "generatorGitCommit", "generatorGitDirty", "compareRunsSha256",
        )
        missing_provenance = [key for key in required_provenance if key not in provenance]
        if missing_provenance:
            raise SystemExit(
                "error: report-provenance.json is incomplete: " + ", ".join(missing_provenance),
            )
        invalid_provenance = [
            key for key in ("measurementGitCommit", "generatorGitCommit")
            if not isinstance(provenance[key], str)
            or re.fullmatch(r"[0-9a-f]{40}", provenance[key]) is None
        ]
        if (
            not isinstance(provenance["compareRunsSha256"], str)
            or re.fullmatch(r"[0-9a-f]{64}", provenance["compareRunsSha256"]) is None
        ):
            invalid_provenance.append("compareRunsSha256")
        if not isinstance(provenance["generatorGitDirty"], bool):
            invalid_provenance.append("generatorGitDirty")
        if invalid_provenance:
            raise SystemExit(
                "error: report-provenance.json has invalid values: "
                + ", ".join(invalid_provenance),
            )
        required_final_sections = (
            "## Werdykt serii — Q2",
            "## Q2 — skalowanie między przebiegami",
            "## Q2 — eksploracyjne skalowanie poza predeklarowanym kontraktem",
            "## Q4 — zgodność odpowiedzi między przebiegami",
            "Kompletna izolowana sonda storage została dołączona",
            "Generator raportu:",
        )
        missing = [section for section in required_final_sections if section not in md_text]
        if missing:
            raise SystemExit(
                "error: thesis-report-series.md is not a complete final report; missing: "
                + ", ".join(missing),
            )
        missing_plots = [
            rel_path for _alt, rel_path in IMAGE_RE.findall(md_text)
            if not (run_dir / rel_path).is_file()
        ]
        if missing_plots:
            raise SystemExit("error: final report references missing plots: " + ", ".join(missing_plots))
    else:
        md_text = base.read_text(encoding="utf-8")
        md_text += (
            "\n\n## Analiza serii przebiegów\n\n"
            "*Brak ważnej serii — ten dokument opisuje tylko jeden blok eksperymentalny. "
            "Uruchom `compare-runs.py` na co najmniej trzech kontrbalansowanych przebiegach; "
            "pojedynczego bloku nie wolno wysyłać jako raportu końcowego.*\n"
        )

    body = Renderer(run_dir).render(md_text)
    out_path.write_text(
        "<!DOCTYPE html>\n<html lang=\"pl\">\n<head>\n"
        "<meta charset=\"utf-8\">\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        f"<title>Raport benchmarku — {escape(run_dir.name)}</title>\n"
        f"<style>{STYLE}</style>\n</head>\n<body>\n{body}\n</body>\n</html>\n",
        encoding="utf-8",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size // 1024} KiB)")


def read_csv_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def read_json_object(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"error: cannot read {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise SystemExit(f"error: {path.name} must contain a JSON object")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("result_dir", type=Path)
    parser.add_argument("-o", "--output", type=Path, default=None)
    args = parser.parse_args()
    if not (args.result_dir / "thesis-report.md").is_file():
        sys.exit(f"error: {args.result_dir}/thesis-report.md not found (run the benchmark first)")
    build(args.result_dir, args.output or (args.result_dir / "thesis-report.html"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
