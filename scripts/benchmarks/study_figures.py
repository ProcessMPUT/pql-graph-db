"""Standalone, linear comparison figures generated from recorded study results."""
from html import escape
import math
from pathlib import Path
import textwrap


LOCAL_COLOR = '#197657'
REFERENCE_COLOR = '#b45431'
NEUTRAL_COLOR = '#687580'


def _number(value):
    if value is None:
        return '—'
    if 0 < abs(value) < .0001:
        return f'{value:.2e}'.replace('.', ',')
    decimals = 1 if abs(value) >= 100 else 2 if abs(value) >= 10 else 3 if abs(value) >= 1 else 4
    whole, _, fraction = f'{value:,.{decimals}f}'.partition('.')
    fraction = fraction.rstrip('0')
    return whole.replace(',', ' ') + (',' + fraction if fraction else '')


def _finite(value, field):
    if value is None:
        return None
    try:
        value = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f'{field} must be a finite nonnegative number or None') from error
    if not math.isfinite(value) or value < 0:
        raise ValueError(f'{field} must be finite and nonnegative')
    return value


def _lines(value, width):
    return [line for paragraph in str(value).splitlines()
            for line in textwrap.wrap(paragraph, width=width)] or ['']


def _text(x, y, value, size=15, color='#26333f', anchor='start', attrs=''):
    return (f'<text x="{x:g}" y="{y:g}" font-size="{size}" fill="{color}" '
            f'text-anchor="{anchor}" {attrs}>{escape(str(value))}</text>')


def _start(title, width, height):
    return [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" role="img" data-scale="linear">',
            f'<title>{escape(title)}</title>',
            f'<rect width="{width}" height="{height}" fill="white"/>',
            '<g font-family="Arial, Helvetica, sans-serif">']


def _save(path, svg):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text('\n'.join(svg + ['</g>', '</svg>']), encoding='utf-8')


def _direction(effect):
    return LOCAL_COLOR if effect > 1 else REFERENCE_COLOR if effect < 1 else NEUTRAL_COLOR


def _axis(maximum):
    target = max(1.0, maximum) * 1.08
    raw_step = target / 4
    magnitude = 10 ** math.floor(math.log10(raw_step))
    step = next(n * magnitude for n in (1, 2, 2.5, 5, 10) if n * magnitude >= raw_step)
    end = math.ceil(target / step) * step
    return end, [i * step for i in range(round(end / step) + 1)]


def write_comparison(path, title, rows, unit='ms', subtitle='', note=''):
    """Show each observation's medians, optional quartiles and a linear R/L plot.

    Explicit effect values are preserved, including the primary estimand which
    need not equal the displayed ratio of summary times. Missing values remain
    missing. Only caller-supplied low/high values produce confidence intervals.
    """
    if not rows:
        raise ValueError('A comparison figure requires at least one row')
    prepared = []
    for row in rows:
        item = dict(row)
        for key in ('local', 'reference', 'effect', 'localQ1', 'localQ3', 'referenceQ1', 'referenceQ3', 'low', 'high'):
            item[key] = _finite(row.get(key), key)
        if 'effect' not in row:
            if item['local'] == 0:
                raise ValueError('Cannot calculate R/L with a zero LOCAL value')
            if item['local'] is not None and item['reference'] is not None:
                item['effect'] = _finite(item['reference'] / item['local'], 'effect')
        for center, low, high in (('local', 'localQ1', 'localQ3'), ('reference', 'referenceQ1', 'referenceQ3'),
                                  ('effect', 'low', 'high')):
            bounds = item[low], item[high]
            if bounds != (None, None) and (None in bounds or item[center] is None or not bounds[0] <= item[center] <= bounds[1]):
                raise ValueError(f'{low}/{high} must enclose {center}')
        item['labelLines'] = _lines(row['label'], 29)
        item['detailLines'] = _lines(row['detail'], 42) if row.get('detail') else []
        item['height'] = max(64, 22 + 19 * len(item['labelLines']) + 16 * len(item['detailLines']))
        prepared.append(item)

    width, left, right = 1200, 822, 1166
    title_lines, subtitle_lines = _lines(title, 90), _lines(subtitle, 142) if subtitle else []
    header_y = 38 + 27 * len(title_lines) + 19 * len(subtitle_lines) + 16
    body_top = header_y + 33
    body_bottom = body_top + sum(row['height'] for row in prepared)
    effect_label = 'E' if any(row['low'] is not None for row in prepared) else 'R/L'
    footer_lines = _lines(note, 144) if note else []
    height = body_bottom + 98 + 18 * len(footer_lines)
    maximum = max([1.0] + [r[key] for r in prepared for key in ('effect', 'high') if r[key] is not None])
    axis_max, ticks = _axis(maximum)
    def x(value): return left + value / axis_max * (right - left)

    svg = _start(title, width, height)
    for i, line in enumerate(title_lines):
        svg.append(_text(32, 36 + i * 27, line, 23, attrs='font-weight="600"'))
    for i, line in enumerate(subtitle_lines):
        svg.append(_text(32, 40 + len(title_lines) * 27 + i * 19, line, 13, NEUTRAL_COLOR))
    for xx, label in ((32, 'Zbiór / porównanie'), (416, f'LOCAL [{unit}]'), (610, f'REFERENCE [{unit}]'), (755, effect_label)):
        svg.append(_text(xx, header_y, label, 14, NEUTRAL_COLOR, 'start' if xx == 32 else 'middle'))
    svg.append(_text(left, header_y, f'Efekt {effect_label} · skala liniowa', 14, NEUTRAL_COLOR))
    y = body_top
    for i, row in enumerate(prepared):
        if i % 2 == 0:
            svg.append(f'<rect x="24" y="{y}" width="1152" height="{row["height"]}" rx="5" fill="#f5f7f9"/>')
        y += row['height']
    svg.append(f'<g data-axis="effect" data-min="0" data-max="{axis_max:g}">')
    for value in ticks:
        svg.append(f'<line x1="{x(value):.3f}" x2="{x(value):.3f}" y1="{body_top}" y2="{body_bottom}" stroke="#dfe5e9"/>')
        svg.append(_text(x(value), body_bottom + 23, _number(value), 12, NEUTRAL_COLOR, 'middle'))
    svg.append(f'<line data-reference="1" x1="{x(1):.3f}" x2="{x(1):.3f}" y1="{body_top}" '
               f'y2="{body_bottom}" stroke="#697882" stroke-width="1.5" stroke-dasharray="4 4"/>')
    if all(abs(t - 1) > .000001 for t in ticks):
        svg.append(_text(x(1), body_top - 9, '1', 12, NEUTRAL_COLOR, 'middle'))
    svg.append('</g>')
    y = body_top
    for i, row in enumerate(prepared):
        center = y + row['height'] / 2
        effect = row['effect']
        svg.append(f'<g data-row="{i}" data-label="{escape(str(row["label"]))}" '
                   f'data-local="{row["local"] if row["local"] is not None else ""}" '
                   f'data-reference="{row["reference"] if row["reference"] is not None else ""}" '
                   f'data-effect="{effect if effect is not None else ""}">')
        label_span = 19 * (len(row['labelLines']) - 1)
        if row['detailLines']:
            label_span += 19 + 16 * (len(row['detailLines']) - 1)
        label_y = center + 5 - label_span / 2
        for line in row['labelLines']:
            svg.append(_text(32, label_y, line, 15)); label_y += 19
        for line in row['detailLines']:
            svg.append(_text(32, label_y, line, 12, NEUTRAL_COLOR)); label_y += 16
        for system, xx in (('local', 416), ('reference', 610)):
            has_quartiles = row[system + 'Q1'] is not None
            svg.append(_text(xx, center - 3 if has_quartiles else center + 5, _number(row[system]), 16, anchor='middle'))
            if has_quartiles:
                low, high = row[system + 'Q1'], row[system + 'Q3']
                svg.append(_text(xx, center + 17, f'[{_number(low)}–{_number(high)}]', 12, NEUTRAL_COLOR, 'middle',
                                 f'data-kind="iqr" data-system="{system}" data-low="{low}" data-high="{high}"'))
        color = _direction(effect) if effect is not None else NEUTRAL_COLOR
        svg.append(_text(755, center + 5, _number(effect), 16, color, 'middle', 'font-weight="600"'))
        if effect is not None:
            if row['low'] is not None:
                low, high = row['low'], row['high']
                svg.append(f'<path data-kind="ci" data-interval="confidence" data-low="{low}" data-high="{high}" '
                           f'd="M {x(low):.3f} {center:g} H {x(high):.3f} M {x(low):.3f} {center-5:g} '
                           f'V {center+5:g} M {x(high):.3f} {center-5:g} V {center+5:g}" stroke="{color}" stroke-width="1.6" fill="none"/>')
            svg.append(f'<circle data-kind="effect" data-value="{effect}" cx="{x(effect):.3f}" cy="{center:g}" '
                       f'r="5" fill="{color}"><title>{escape(str(row["label"]))}: {effect_label} = {_number(effect)}</title></circle>')
        svg.append('</g>')
        y += row['height']
    meaning = 'krótszy czas' if unit in ('ms', 's') else 'mniejsza wartość'
    svg.append(_text(32, body_bottom + 52, f'{effect_label} > 1: {meaning} LOCAL. {effect_label} < 1: {meaning} REFERENCE. Kolor nie oznacza istotności statystycznej.', 12, NEUTRAL_COLOR))
    for i, line in enumerate(footer_lines):
        svg.append(_text(32, body_bottom + 77 + i * 18, line, 12, NEUTRAL_COLOR))
    _save(path, svg)


def write_heatmap(path, title, row_labels, columns, values, note=''):
    """Show numeric R/L values; missing cells remain explicit and colors are linear."""
    if not row_labels or not columns:
        raise ValueError('A heatmap requires rows and columns')
    data = {(row, column): _finite(values.get((row, column)), 'effect') for row in row_labels for column, _ in columns}
    max_distance = max([1.0] + [abs(value - 1) for value in data.values() if value is not None])
    left, cell_width = 284, max(132, (1200 - 316) / len(columns))
    width = math.ceil(left + cell_width * len(columns) + 32)
    title_lines = _lines(title, 90)
    column_lines = [_lines(label, max(12, int(cell_width / 8))) for _, label in columns]
    top = 68 + len(title_lines) * 27 + max(map(len, column_lines)) * 18
    labels = [_lines(label, 29) for label in row_labels]
    heights = [max(55, 20 + 19 * len(lines)) for lines in labels]
    bottom = top + sum(heights)
    note_lines = _lines(note, max(80, int((width - 64) / 7))) if note else []
    svg = _start(title, width, bottom + 88 + 18 * len(note_lines))
    for i, line in enumerate(title_lines):
        svg.append(_text(32, 36 + i * 27, line, 23, attrs='font-weight="600"'))
    for i, lines in enumerate(column_lines):
        for j, line in enumerate(lines):
            svg.append(_text(left + cell_width * (i + .5), top - 18 * (len(lines) - j), line, 14, NEUTRAL_COLOR, 'middle'))
    y = top
    for row, lines, row_height in zip(row_labels, labels, heights):
        for j, line in enumerate(lines):
            svg.append(_text(32, y + row_height / 2 + 5 + (j - (len(lines) - 1) / 2) * 19, line, 15))
        for i, (column, _) in enumerate(columns):
            value = data[row, column]
            color = '#eef1f4'
            dark = False
            if value is not None:
                intensity = abs(value - 1) / max_distance
                target = (25, 118, 87) if value > 1 else (180, 84, 49) if value < 1 else (238, 241, 244)
                base = (244, 247, 249)
                color = '#'+''.join(f'{round(a + (b-a)*intensity):02x}' for a, b in zip(base, target))
                dark = intensity > .68
            xx = left + i * cell_width
            svg.append(f'<g data-row="{escape(str(row))}" data-column="{escape(str(column))}" '
                       f'data-effect="{value if value is not None else ""}" data-missing="{str(value is None).lower()}">')
            svg.append(f'<rect x="{xx:g}" y="{y:g}" width="{cell_width-5:g}" height="{row_height-5:g}" rx="5" fill="{color}"/>')
            svg.append(_text(xx + (cell_width-5)/2, y + row_height/2 + 4, _number(value), 17,
                             'white' if dark else _direction(value) if value is not None else NEUTRAL_COLOR, 'middle'))
            svg.append('</g>')
        y += row_height
    svg.append(_text(32, bottom + 30, 'R/L > 1: krótszy czas LOCAL. R/L < 1: krótszy czas REFERENCE. 1: równe mediany. —: brak pomiaru.', 12, NEUTRAL_COLOR))
    svg.append(_text(32, bottom + 49, 'Natężenie koloru rośnie liniowo z odległością R/L od 1; nie oznacza istotności statystycznej.', 12, NEUTRAL_COLOR))
    for i, line in enumerate(note_lines):
        svg.append(_text(32, bottom + 73 + 18*i, line, 12, NEUTRAL_COLOR))
    _save(path, svg)
