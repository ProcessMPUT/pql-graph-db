#!/usr/bin/env python3
"""Render a benchmark run's thesis-report.md into ONE self-contained HTML file
with every chart inlined as SVG — no dependency on the plots/ directory, so the
file can be shared, opened in a browser, or printed to PDF as-is.

Run the chart generator first so thesis-report.md has its figures embedded:

    python scripts/benchmarks/plot-benchmark-results.py tmp/benchmark-results/<runId>
    python scripts/benchmarks/render-report-html.py     tmp/benchmark-results/<runId>

Requires the `markdown` package (pip install markdown). Output defaults to
`<runId>/thesis-report.html`.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import markdown
except ModuleNotFoundError:
    sys.exit("This tool needs the 'markdown' package: pip install markdown")


STYLE = """
  :root { color-scheme: light; }
  body { font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif;
    max-width: 1040px; margin: 0 auto; padding: 32px 24px 96px;
    color: #1f2933; line-height: 1.55; background: #fff; }
  h1 { font-size: 30px; border-bottom: 3px solid #2563eb; padding-bottom: 12px; }
  h2 { font-size: 23px; margin-top: 44px; border-bottom: 1px solid #e5e7eb; padding-bottom: 6px; }
  h3 { font-size: 18px; margin-top: 32px; color: #334155; }
  table { border-collapse: collapse; width: 100%; margin: 16px 0; font-size: 13.5px; }
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


def build(run_dir: Path, out_path: Path) -> None:
    md_text = (run_dir / "thesis-report.md").read_text(encoding="utf-8")

    def inline_svg(match: re.Match) -> str:
        svg_path = run_dir / match.group(2)
        if not svg_path.exists():
            return match.group(0)
        svg = re.sub(r"^<\?xml[^>]*\?>\s*", "", svg_path.read_text(encoding="utf-8"))
        return f'<figure class="chart">{svg}</figure>'

    md_text = re.sub(r"!\[([^\]]*)\]\(([^)]+\.svg)\)", inline_svg, md_text)
    body = markdown.markdown(md_text, extensions=["tables", "sane_lists", "attr_list"])

    out_path.write_text(
        "<!DOCTYPE html>\n<html lang=\"pl\">\n<head>\n"
        "<meta charset=\"utf-8\">\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        f"<title>Raport benchmarku — {run_dir.name}</title>\n"
        f"<style>{STYLE}</style>\n</head>\n<body>\n{body}\n</body>\n</html>\n",
        encoding="utf-8",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size // 1024} KiB)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result_dir", type=Path)
    parser.add_argument("-o", "--output", type=Path, default=None)
    args = parser.parse_args()
    out = args.output or (args.result_dir / "thesis-report.html")
    build(args.result_dir, out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
