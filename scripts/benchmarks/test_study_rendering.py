"""Offline checks of standalone reports and linear comparison chart semantics."""
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from study_figures import write_comparison, write_heatmap
from study_html import write_html
from study_report import controlled_points, controlled_sections, make_figures


class StudyRenderingTest(unittest.TestCase):
    def test_effect_axis_is_linear_from_zero_with_equality_at_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'plot.svg'
            write_comparison(path, 'A < B', [dict(label=str(i), local=10, reference=10*i) for i in range(4)])
            svg = ET.parse(path)
            ns = {'s': 'http://www.w3.org/2000/svg'}
            self.assertEqual(svg.getroot().attrib['data-scale'], 'linear')
            axis = svg.find('.//s:g[@data-axis="effect"]', ns)
            self.assertEqual(float(axis.attrib['data-min']), 0)
            dots = svg.findall('.//s:circle[@data-kind="effect"]', ns)
            self.assertEqual([float(p.attrib['data-value']) for p in dots], [0, 1, 2, 3])
            xs = [float(p.attrib['cx']) for p in dots]
            self.assertGreater(xs[1], xs[0])
            for i in (1, 2):
                self.assertAlmostEqual(xs[i+1] - xs[i], xs[1] - xs[0], places=1)
            equality = svg.find('.//s:line[@data-reference="1"]', ns)
            self.assertAlmostEqual(float(equality.attrib['x1']), xs[1], places=1)
            self.assertEqual(svg.find('s:title', ns).text, 'A < B')

    def test_rows_preserve_labels_values_pql_and_descriptive_quartiles(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'rows.svg'
            pql = "where [e:attr_1] = '<hit>&'"
            rows = [dict(label=label, local=10*i, reference=20*i, localQ1=8*i, localQ3=12*i,
                         referenceQ1=18*i, referenceQ3=22*i, detail=pql)
                    for i, label in enumerate(('1%', '10%', '100%'), 1)]
            write_comparison(path, 'Selektywność', rows, unit='ms')
            svg = ET.parse(path)
            ns = {'s': 'http://www.w3.org/2000/svg'}
            rendered = svg.findall('.//s:g[@data-row]', ns)
            self.assertEqual([r.attrib['data-label'] for r in rendered], ['1%', '10%', '100%'])
            for expected, actual in zip(rows, rendered):
                self.assertIn(expected['label'], ''.join(actual.itertext()))
                for key in ('local', 'reference'):
                    self.assertEqual(float(actual.attrib['data-'+key]), expected[key])
                    self.assertIn(str(expected[key]), ''.join(actual.itertext()))
                    quartiles = actual.find(f'.//*[@data-kind="iqr"][@data-system="{key}"]')
                    self.assertEqual(float(quartiles.attrib['data-low']), expected[key+'Q1'])
                    self.assertEqual(float(quartiles.attrib['data-high']), expected[key+'Q3'])
                    self.assertTrue(''.join(quartiles.itertext()).strip())
                self.assertIn(pql, ''.join(actual.itertext()))
            self.assertEqual(svg.findall('.//*[@data-kind="ci"]'), [])
            self.assertEqual(svg.findall('.//*[@data-interval="confidence"]'), [])

    def test_primary_preserves_declared_effect_and_confidence_interval(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'primary.svg'
            # A median of independent effects need not equal the ratio of the displayed medians.
            write_comparison(path, 'Hipoteza', [dict(label='Z1', local=10, reference=20,
                                                    effect=3, low=2.5, high=4)])
            svg = ET.parse(path)
            ns = {'s': 'http://www.w3.org/2000/svg'}
            row = svg.find('.//s:g[@data-row]', ns)
            self.assertEqual(float(row.attrib['data-effect']), 3)
            self.assertEqual(float(row.find('.//s:circle[@data-kind="effect"]', ns).attrib['data-value']), 3)
            interval = row.find('.//*[@data-kind="ci"]')
            self.assertEqual(float(interval.attrib['data-low']), 2.5)
            self.assertEqual(float(interval.attrib['data-high']), 4)
            self.assertEqual(svg.findall('.//*[@data-kind="iqr"]'), [])

    def test_invalid_values_and_incomplete_intervals_fail_instead_of_becoming_zeros(self):
        with tempfile.TemporaryDirectory() as tmp:
            for update in (dict(local=float('nan')), dict(reference=float('inf')),
                           dict(effect=float('nan')), dict(local=-1), dict(local=0),
                           dict(localQ1=1), dict(localQ1=11, localQ3=12),
                           dict(low=1), dict(low=3, high=4)):
                with self.subTest(update=update), self.assertRaises(ValueError):
                    write_comparison(Path(tmp)/'bad.svg', 'q', [dict(label='q', local=10, reference=20) | update])

    def test_missing_comparison_values_remain_visible_without_an_invented_effect(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'missing.svg'
            write_comparison(path, 'Braki', [dict(label='brak LOCAL', local=None, reference=2),
                                            dict(label='brak REFERENCE', local=1, reference=None)])
            svg = ET.parse(path)
            ns = {'s': 'http://www.w3.org/2000/svg'}
            rows = svg.findall('.//s:g[@data-row]', ns)
            self.assertEqual([r.attrib['data-label'] for r in rows], ['brak LOCAL', 'brak REFERENCE'])
            for row in rows:
                self.assertIn('—', ''.join(row.itertext()))
                self.assertNotIn(row.attrib.get('data-effect'), ('0', '1'))
                self.assertEqual(row.findall('.//s:circle[@data-kind="effect"]', ns), [])

    def test_html_embeds_tables_and_svg_and_rejects_missing_figure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_comparison(root/'plot.svg', 'q', [dict(label='x', local=2, reference=4,
                                                       localQ1=1, localQ3=3)])
            markdown = '# Raport\n\n## Wyniki\n\n| PQL | Mediana |\n| --- | --- |\n| `count(e:name)` | 2 |\n\n![Mediana i kwartyle](plot.svg)\n'
            write_html(root, markdown, root/'report.html')
            html = (root/'report.html').read_text()
            self.assertIn('<svg ', html)
            self.assertIn('<code>count(e:name)</code>', html)
            self.assertIn('<a href="#wyniki">', html)
            self.assertEqual(html.count('<table>'), html.count('</table>'))
            self.assertNotIn('src="plot.svg"', html)
            (root/'plot.svg').unlink()
            with self.assertRaises(FileNotFoundError):
                write_html(root, markdown, root/'missing.html')

    def test_heatmap_preserves_effects_and_marks_missing_cells_instead_of_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'heatmap.svg'
            write_heatmap(path, 'A < B', ['Log A', 'Log B'], [('q1', 'Z1'), ('q2', 'Z2')],
                          {('Log A', 'q1'): 0, ('Log A', 'q2'): 1, ('Log B', 'q1'): None})
            svg = ET.parse(path)
            ns = {'s': 'http://www.w3.org/2000/svg'}
            cells = {(c.attrib['data-row'], c.attrib['data-column']): c
                     for c in svg.findall('.//*[@data-column]')}
            self.assertEqual(len(cells), 4)
            for column, value in [('q1', 0), ('q2', 1)]:
                cell = cells['Log A', column]
                self.assertEqual(float(cell.attrib['data-effect']), value)
                self.assertNotEqual(cell.attrib.get('data-missing'), 'true')
            for column in ('q1', 'q2'):
                cell = cells['Log B', column]
                self.assertEqual(cell.attrib['data-missing'], 'true')
                self.assertIn('—', ''.join(cell.itertext()))
                self.assertNotIn(cell.attrib.get('data-effect'), ('0', '1'))
            self.assertEqual(svg.find('s:title', ns).text, 'A < B')
            with self.assertRaises(ValueError):
                write_heatmap(path, 'Błąd', ['Log A'], [('q1', 'Z1')], {('Log A', 'q1'): float('nan')})

    def test_response_sweep_reuses_middle_block_and_shows_observed_cardinalities(self):
        datasets = [dict(name='variants-1', series='variant-scaling')]
        queries = [dict(label=label, displayName=label,
                        expectedResponses={'variants-1': dict(logs=1, traces=10, events=size)})
                   for label, size in [('responseWindow20', 20), ('hierarchyWindow', 200), ('responseWindow600', 600)]]
        times = [dict(metric='query', dataset='variants-1', query=q['label'], pairs=30,
                      local=1, reference=2, effect=2) for q in queries]
        points = controlled_points(times, datasets, queries)
        self.assertEqual([p['x'] for p in points], [20, 200, 600])
        records = [dict(phase='warm', datasetName='variants-1', queryLabel='responseWindow600',
                        system=system, logCount='1', traceCount='30', eventCount=events)
                   for system in ('local', 'reference') for events in ('599', '600')]
        # Deliberately divergent observed counts must be printed, never replaced by the planned 600.
        selected = {'coverage': (dict(phase='coverage'), Path('.'), dict(queries=records))}
        text = '\n'.join(controlled_sections(times, datasets, queries, selected, {}))
        self.assertEqual(text.count('### Wielkość odpowiedzi'), 1)
        self.assertIn('1/30/599–600; 1/30/599–600', text)
        self.assertIn('Nie dodaje się testów ani p-wartości', text)
        self.assertEqual(controlled_sections([], datasets, [dict(label='hierarchyWindow')], {}, {}), [])

    def test_figures_use_recorded_axes_and_do_not_connect_different_dataset_sizes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            recorded, output = root/'recorded', root/'report'
            recorded.mkdir()
            output.mkdir()
            datasets = [dict(name=f'd-{events}-{percent}', series='selectivity-scaling',
                             traces=events//10, eventsPerTrace=10, matchingTracePercent=percent)
                        for events in (100_000, 1_000_000) for percent in (1, 10, 100)]
            queries = [dict(label='selectivityEventFilter', displayName='Zapisana nazwa',
                            measurementSeries=['selectivity-scaling'], scalingSeries=['selectivity-scaling'])]
            (recorded/'benchmark-datasets.json').write_text(json.dumps({'full': datasets}))
            (recorded/'benchmark-queries.json').write_text(json.dumps(queries))
            times = [dict(metric='query', dataset=d['name'], query='selectivityEventFilter',
                          local=1, localQ1=.8, localQ3=1.2, reference=2, referenceQ1=1.8, referenceQ3=2.2)
                     for d in datasets]
            selected = {'coverage': (dict(kind='latency', phase='coverage'), recorded,
                                     dict(environment={}, datasets=[dict(d, datasetName=d['name']) for d in datasets]))}
            with patch('study_report.RESOURCES', root/'absent-current-catalog'), \
                 patch('study_report.descriptive_results', return_value=times):
                figures = make_figures(output, selected)
            paths = [output/path for _, path in figures['selectivity-scaling']]
            self.assertEqual(len(set(paths)), 2)
            ns = {'s': 'http://www.w3.org/2000/svg'}
            for path, size in zip(paths, ('100 000', '1 000 000')):
                svg = ET.parse(path)
                self.assertEqual(svg.findall('.//s:svg', ns), [])
                self.assertEqual(len(svg.findall('.//s:g[@data-row]', ns)), 3)
                self.assertIn('Zapisana nazwa — '+size+' zdarzeń', svg.find('s:title', ns).text)

    def test_figure_payloads_use_observed_data_units_and_independent_primary_effects(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            recorded, output = root/'recorded', root/'report'
            recorded.mkdir()
            output.mkdir()
            datasets = [dict(name='size-observed', series='size-scaling', traces=100, eventsPerTrace=10),
                        dict(name='size-unmeasured', series='size-scaling', traces=500, eventsPerTrace=10),
                        dict(name='real-hospital', series='real-validation', collection='bpi-challenge', collectionOrder=2011),
                        dict(name='real-bpic12', series='real-validation', collection='bpi-challenge', collectionOrder=2012)]
            queries = [dict(label='hierarchyWindow', displayName='Hierarchia', query='limit l:1, t:10, e:20',
                            measurementSeries=['size-scaling', 'real-validation'], scalingSeries=['size-scaling']),
                       dict(label='realLikeNoMatch', displayName='Brak dopasowań', query="where e:name like '%zzq%'",
                            measurementSeries=['real-validation'], scalingSeries=[])]
            (recorded/'benchmark-datasets.json').write_text(json.dumps({'full': datasets}))
            (recorded/'benchmark-queries.json').write_text(json.dumps(queries))
            times = [dict(metric='query', dataset='size-observed', query='hierarchyWindow',
                          local=.002, reference=.004, localQ1=.001, localQ3=.003,
                          referenceQ1=.003, referenceQ3=.005, effect=2),
                     dict(metric='query', dataset='real-hospital', query='hierarchyWindow',
                          local=.01, reference=.02, effect=2),
                     dict(metric='import', dataset='size-observed', query='import',
                          local=2, reference=4, localQ1=1, localQ3=3, effect=2),
                     dict(metric='setup', dataset='size-observed', query='import',
                          local=999, reference=999, effect=1)]
            memory = [dict(phase='queries', dataset='size-observed', query=query, system=system,
                           medianBytes=median*2**20, peakBytes=peak*2**20)
                      for query, system, median, peak in [('q1', 'local', 100, 120), ('q2', 'local', 300, 400),
                                                        ('q1', 'reference', 50, 60), ('q2', 'reference', 150, 160)]]
            memory.append(dict(phase='import-resource', dataset='size-observed', query='import',
                               system='local', medianBytes=9999*2**20, peakBytes=9999*2**20))
            # Observed CSV fields are strings; unmeasured definitions retain their numeric types.
            selected = {'evidence': (dict(kind='storage'), recorded, dict(
                datasets=[dict(datasetName='real-hospital', collectionOrder='2011')], storage=[
                dict(datasetName='size-observed', system='local', deltaBytes=3*2**20),
                dict(datasetName='size-observed', system='reference', deltaBytes=6*2**20)]))}
            primary = [dict(dataset='size-observed', query='hierarchyWindow', status='OK', n=12,
                            local=.003, reference=.006, effect=2.5, low=2.1, high=2.9, holmPValue=.003),
                       dict(dataset='size-unmeasured', query='hierarchyWindow', status='INCOMPLETE')]
            with patch('study_report.RESOURCES', root/'absent-current-catalog'), \
                 patch('study_report.descriptive_results', return_value=times), \
                 patch('study_report.resource_results', return_value=memory), \
                 patch('study_report.primary_results', return_value=primary), \
                 patch('study_report.write_comparison') as comparison, \
                 patch('study_report.write_heatmap') as heatmap:
                make_figures(output, selected, resource_root=recorded, plan={'primary': {'queries': ['hierarchyWindow']}})
            calls = {call.args[0].stem: call for call in comparison.call_args_list}
            query = calls['hierarchyWindow-size-scaling']
            self.assertEqual(query.kwargs['unit'], 'ms')
            self.assertEqual(query.kwargs['subtitle'], queries[0]['query'])
            self.assertEqual(query.args[2], [dict(label='1 000 zdarzeń', local=2, reference=4,
                                                localQ1=1, localQ3=3, referenceQ1=3, referenceQ3=5, effect=2)])
            imports = calls['import-effect']
            self.assertEqual(imports.kwargs['unit'], 's')
            self.assertEqual(imports.args[2], [dict(label='1 000 zdarzeń', local=2, reference=4,
                                                  localQ1=1, localQ3=3, effect=2)])
            confirmation = calls['primary-hierarchyWindow'].args[2]
            self.assertEqual(len(confirmation), 1)
            self.assertEqual({k: confirmation[0][k] for k in ('local', 'reference', 'effect', 'low', 'high')},
                             dict(local=3, reference=6, effect=2.5, low=2.1, high=2.9))
            summary = calls['resource-memory']
            self.assertEqual(summary.kwargs['unit'], 'MiB')
            self.assertEqual([(r['local'], r['reference']) for r in summary.args[2]], [(200, 100), (400, 160)])
            self.assertEqual([(r['local'], r['reference']) for r in calls['resource-memory-datasets'].args[2]], [(200, 100)])
            storage = calls['resource-storage']
            self.assertEqual(storage.kwargs['unit'], 'MiB')
            self.assertEqual(storage.args[2], [dict(label='1 000 zdarzeń', local=3, reference=6)])
            heatmap.assert_called_once()
            self.assertEqual(heatmap.call_args.args[2], ['BPIC11 (Hospital)', 'BPIC12'])
            self.assertEqual(dict(heatmap.call_args.args[3]),
                             {'hierarchyWindow': 'Pobranie hierarchii', 'realLikeNoMatch': 'LIKE bez dopasowań'})
            self.assertEqual(heatmap.call_args.args[4], {('BPIC11 (Hospital)', 'hierarchyWindow'): 2})


if __name__ == '__main__':
    unittest.main()
