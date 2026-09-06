"""Render the study report's Markdown subset as self-contained HTML with embedded SVG."""
from html import escape, unescape
from pathlib import Path
import re
import unicodedata
from urllib.parse import urlsplit

STYLE = """
  :root { color-scheme: light; }
  body { font-family: Georgia, "Times New Roman", serif; max-width: 960px;
    margin: 0 auto; padding: 40px 28px 80px; color: #202020; background: white;
    line-height: 1.6; font-size: 17px; }
  h1, h2, h3 { line-height: 1.25; color: #171717; }
  h1 { font-size: 30px; margin-bottom: 30px; }
  h2 { font-size: 24px; margin-top: 42px; border-bottom: 1px solid #bbb;
    padding-bottom: 8px; }
  h3 { font-size: 19px; margin-top: 26px; }
  p { margin: 12px 0; }
  a { color: #244b68; text-underline-offset: 3px; }
  .toc { margin: 30px 0; padding: 18px 0; border-top: 1px solid #aaa;
    border-bottom: 1px solid #aaa; }
  .toc-title { font-size: 21px; font-weight: bold; }
  .toc ol { margin: 12px 0; padding-left: 25px; }
  .toc li { margin: 4px 0; }
  .report-section { scroll-margin-top: 20px; }
  .table-wrap { overflow-x: auto; margin: 20px 0; }
  table { border-collapse: collapse; width: 100%; font-size: 13px; line-height: 1.4; }
  caption { text-align: left; font-size: 14px; margin-bottom: 8px; }
  th, td { padding: 7px 8px; text-align: right; vertical-align: top; }
  thead { border-top: 1.5px solid #222; border-bottom: 1px solid #777; }
  tbody { border-bottom: 1.5px solid #222; }
  th:first-child, td:first-child { text-align: left; }
  tbody tr + tr { border-top: 1px solid #ddd; }
  figure.chart { margin: 26px 0; text-align: center; }
  figure.chart svg { max-width: 100%; height: auto; }
  figcaption { font-size: 14px; text-align: left; margin-top: 8px; }
  code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 0.85em;
    overflow-wrap: anywhere; }
  blockquote { margin: 18px 0; padding-left: 18px; border-left: 2px solid #999; }
  @media (max-width: 760px) { body { padding: 20px 14px; font-size: 16px; } }
  @media print {
    @page { size: A4; margin: 18mm; }
    body { max-width: none; padding: 0; font-size: 11pt; }
    h1 { font-size: 21pt; } h2 { font-size: 16pt; } h3 { font-size: 12pt; }
    h1, h2, h3, figcaption { break-after: avoid; }
    table { font-size: 8pt; }
    thead { display: table-header-group; }
    tr, figure { break-inside: avoid; }
    .table-wrap { overflow: visible; }
    a { color: inherit; text-decoration: none; }
  }
"""

IMAGE_RE = re.compile(r"^!\[([^\]]*)\]\(([^)]+\.svg)\)\s*$", re.MULTILINE)
HEADING_RE = re.compile(r"^(#{1,3})\s+(.*)$")
COMMENT_RE = re.compile(r"^<!--.*-->\s*$")
CODE_SPAN_RE = re.compile(r"`([^`]+)`")
LINK_RE = re.compile(r"\[([^\]\n]+)\]\(([^)\s]+)\)")
BOLD_RE = re.compile(r"\*\*([^*]+)\*\*")
ITALIC_RE = re.compile(r"\*([^*]+)\*")
SEPARATOR_CELL_RE = re.compile(r"^:?-{3,}:?$")


def heading_slug(title: str) -> str:
    """Return a stable, readable ASCII fragment identifier for a report heading."""
    plain = re.sub(r"[`*_]", "", title).strip().lower()
    plain = re.sub(r"(?<=\w)/(?=\w)", "", plain)
    plain = plain.translate(
        str.maketrans("ąćęłńóśźż", "acelnoszz"),
    )
    normalized = unicodedata.normalize("NFKD", plain)
    ascii_title = "".join(char for char in normalized if not unicodedata.combining(char))
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_title).strip("-")
    return slug or "sekcja"


def inline(text: str) -> str:
    """Escape HTML, then apply code, safe links, bold and italic.

    Code spans are pulled out first so their contents are never treated as
    emphasis and a `*` inside a caption's code (e.g. `min(^e:*)`) can't break
    the surrounding italic.
    """
    spans: list[str] = []

    def stash(html: str) -> str:
        spans.append(html)
        return f"\x00{len(spans) - 1}\x00"

    def emphasis(value: str) -> str:
        value = BOLD_RE.sub(r"<strong>\1</strong>", value)
        return ITALIC_RE.sub(r"<em>\1</em>", value)

    def link(match: re.Match) -> str:
        target = unescape(match.group(2))
        if any(ord(char) < 32 for char in target):
            return match.group(0)
        try:
            url = urlsplit(target)
        except ValueError:
            return match.group(0)
        if not (target.startswith('#') or (url.scheme in ('http', 'https') and url.netloc)):
            return match.group(0)
        return stash(f'<a href="{escape(target)}">{emphasis(match.group(1))}</a>')

    text = CODE_SPAN_RE.sub(lambda match: stash(f"<code>{escape(match.group(1))}</code>"), text.replace('\x00', '\ufffd'))
    text = escape(text)
    text = LINK_RE.sub(link, text)
    text = emphasis(text)
    # Link labels may contain an earlier stashed code span.
    for index in reversed(range(len(spans))):
        text = text.replace(f'\x00{index}\x00', spans[index])
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
        self.figure_number = 0
        self.table_number = 0
        self.current_heading = "Wyniki"

    def inline_svg(self, alt: str, rel_path: str) -> str:
        svg_path = self.run_dir / rel_path
        if not svg_path.is_file():
            raise FileNotFoundError(svg_path)
        svg = re.sub(r"^\s*<\?xml[^>]*\?>\s*", "", svg_path.read_text(encoding="utf-8"))
        self.figure_number += 1
        return (f'<figure class="chart">{svg}<figcaption>'
                f'Rysunek {self.figure_number}. {inline(alt)}</figcaption></figure>')

    def table(self, header: str, separator: str, body: list[str]) -> None:
        aligns = cell_alignment(split_row(separator))

        def style(index: int) -> str:
            align = aligns[index] if index < len(aligns) else ""
            return f' style="text-align:{align}"' if align else ""

        self.out.append('<div class="table-wrap"><table>')
        self.table_number += 1
        self.out.append(f'<caption>Tabela {self.table_number}. {inline(self.current_heading)}</caption>')
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
        self.out = []
        self.figure_number = 0
        self.table_number = 0
        self.current_heading = "Wyniki"
        heading_ids: dict[int, str] = {}
        toc_entries: list[tuple[str, str]] = []
        slug_counts: dict[str, int] = {}
        for line_index, line in enumerate(lines):
            heading = HEADING_RE.match(line)
            if not heading or len(heading.group(1)) != 2:
                continue
            title = heading.group(2).strip()
            base_slug = heading_slug(title)
            occurrence = slug_counts.get(base_slug, 0) + 1
            slug_counts[base_slug] = occurrence
            section_id = base_slug if occurrence == 1 else f"{base_slug}-{occurrence}"
            heading_ids[line_index] = section_id
            toc_entries.append((title, section_id))

        def append_toc() -> None:
            self.out.append('<nav class="toc" aria-label="Spis treści">')
            self.out.append('<div class="toc-title">Spis treści</div>')
            self.out.append("<ol>")
            for title, section_id in toc_entries:
                self.out.append(f'<li><a href="#{section_id}">{inline(title)}</a></li>')
            self.out.append("</ol>")
            self.out.append("</nav>")

        i, n = 0, len(lines)
        section_open = False
        toc_written = False
        while i < n:
            line = lines[i]

            if not line.strip() or COMMENT_RE.match(line):
                i += 1
                continue

            heading = HEADING_RE.match(line)
            if heading:
                level = len(heading.group(1))
                title = heading.group(2).strip()
                self.current_heading = title
                if level == 2:
                    if section_open:
                        self.out.append("</section>")
                    if not toc_written:
                        append_toc()
                        toc_written = True
                    section_id = heading_ids[i]
                    self.out.append(
                        f'<section class="report-section" id="{section_id}">'
                        f'<h2>{inline(title)}</h2>',
                    )
                    section_open = True
                else:
                    self.out.append(f"<h{level}>{inline(title)}</h{level}>")
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

        if section_open:
            self.out.append("</section>")
        return "\n".join(self.out)


def write_html(run_dir: Path, md_text: str, out_path: Path) -> None:
    """Render a complete report; callers validate their own input contract."""
    body = Renderer(run_dir).render(md_text)
    heading = next((match.group(2) for line in md_text.splitlines()
                    if (match := HEADING_RE.match(line)) and len(match.group(1)) == 1), 'Raport benchmarku')
    title = unescape(re.sub(r'<[^>]+>', '', inline(heading)))
    out_path.write_text(
        "<!DOCTYPE html>\n<html lang=\"pl\">\n<head>\n"
        "<meta charset=\"utf-8\">\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        f"<title>{escape(title)}</title>\n"
        f"<style>{STYLE}</style>\n</head>\n<body>\n{body}\n</body>\n</html>\n",
        encoding="utf-8",
    )
