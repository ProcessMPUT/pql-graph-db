"""Offline regressions for the actual split latency/resource collection layout."""
from copy import deepcopy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import study_analysis as A
import study_contract as C
import study_report as R
from test_study import fixture, csv, S


class StudyAnalysisTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.plan = C.read_json(C.RESOURCES/'study-plan.json')

    def prepare(self, name):
        job = next(j for j in C.schedule(self.plan) if j['id'] == name)
        root = self.root/name
        fixture(job, root)
        return job, root

    def test_active_memory_sums_simultaneous_samples_and_ignores_obsolete_summary(self):
        job, root = self.prepare('resources-size')
        raw = C.rows(root/'memory-results.csv')
        dataset, query = C.cells(job)[0]
        for row in raw:
            if (row['datasetName'], row['operationLabel'], row['activeSystem']) == (dataset, query, 'local'):
                tick = int(row['timestamp'][-2])
                if row['component'] == 'processm-interpreter': row['bytes'] = [100, 1, 100][tick]
                if row['component'] == 'processm-neo4j': row['bytes'] = [1, 100, 100][tick]
        outside = dict(raw[0], timestamp='2026-01-01T00:00:09Z', bytes=999999, withinWindow='false')
        csv(root/'memory-results.csv', raw+[outside])
        (root/'memory-summary.csv').unlink()
        evidence = C.validate_job(job, root)
        result = A.resource_results({job['id']: (job, root, evidence)})
        queries = [r for r in result if r['phase'] == 'queries']
        self.assertEqual(2*len(C.cells(job)), len(queries))
        local = next(r for r in queries if (r['dataset'], r['query'], r['system']) == (dataset, query, 'local'))
        self.assertEqual(101, local['medianBytes'])  # median(sum) differs from sum(medians)=200
        self.assertEqual(200, local['peakBytes'])
        self.assertEqual(3, local['samples'])
        self.assertEqual(1, local['medianGapSeconds'])
        reference = next(r for r in queries if r['system'] == 'reference')
        self.assertEqual(100, reference['medianBytes'])
        imports = [r for r in result if r['phase'] == 'import-resource']
        self.assertTrue(all(r['samples'] == 0 and r['peakBytes'] is None for r in imports))
        self.assertIn('brak obserwacji', R.resource_tables({job['id']: (job, root, evidence)}))

    def test_resource_raw_counts_and_duplicates_are_checked(self):
        job, root = self.prepare('resources-real-sepsis')
        raw = C.rows(root/'memory-results.csv')
        csv(root/'memory-results.csv', raw+[raw[0]])
        with self.assertRaisesRegex(C.ScriptError, 'Duplicated active'):
            C.validate_job(job, root)
        csv(root/'memory-results.csv', raw)
        journal = (root/'execution.jsonl').read_text().replace('"samples": 3', '"samples": 4', 1)
        (root/'execution.jsonl').write_text(journal)
        with self.assertRaisesRegex(C.ScriptError, 'sample count differs'):
            C.validate_job(job, root)

    def test_resource_windows_do_not_require_latency_rows_when_warmups_are_zero(self):
        job = next(j for j in C.schedule(self.plan) if j['id'] == 'resources-real-sepsis')
        job = dict(job, warmups=0)
        root = self.root/job['id']
        fixture(job, root)
        evidence = C.validate_job(job, root)
        self.assertEqual([], evidence['queries'])
        result = A.resource_results({job['id']: (job, root, evidence)})
        self.assertEqual(2*len(C.cells(job)), sum(r['phase'] == 'queries' for r in result))

    def test_latency_diagnostics_never_invent_resource_windows_and_use_recorded_order(self):
        selected = {}
        for name in ('coverage-size', 'resources-size'):
            job, root = self.prepare(name)
            selected[name] = (job, root, C.validate_job(job, root))
        diagnostics = A.latency_diagnostics(selected)
        self.assertEqual(1, len(diagnostics))
        self.assertEqual('coverage-size', diagnostics[0]['task'])
        self.assertEqual(61, diagnostics[0]['orderChecked'])
        self.assertEqual(61, diagnostics[0]['driftChecked'])
        # CSV order is grouped by system here; executionIndex still gives the actual LR/RL order.
        job, root, evidence = selected['coverage-size']
        for r in evidence['queries']:
            if r['phase'] == 'warm' and r['system'] == 'reference' and int(r['run']) % 2 == 0:
                r['seconds'] = .04
        evidence['queries'].sort(key=lambda r: r['system'])
        self.assertEqual(61, A.latency_diagnostics(selected)[0]['orderWarnings'])
        for r in evidence['queries']: r.pop('executionIndex', None)
        self.assertEqual(0, A.latency_diagnostics(selected)[0]['orderChecked'])

    def test_descriptive_quartiles_and_single_import_do_not_manufacture_dispersion(self):
        records = [dict(system=s, run=str(i+1), status='OK', seconds=v)
                   for s, values in [('local', [1, 2, 3, 8]), ('reference', [2, 4, 6, 16])]
                   for i, v in enumerate(values)]
        summary = A.timing_summary(records, 4)
        self.assertEqual((1.75, 4.25), (summary['localQ1'], summary['localQ3']))
        self.assertEqual(2, summary['effect'])
        single = A.timing_summary([r for r in records if r['run'] == '1'], 1)
        self.assertIsNone(single['localQ1'])
        self.assertIsNone(single['referenceQ3'])

    def test_frozen_forecast_is_bound_to_all_design_parameters(self):
        plan = dict(self.plan, status='frozen', pilotEvidence=dict(estimatedSeconds=1, manifestSha256='a'*64,
                                                               designSha256=C.design_identity(self.plan)))
        C.validate_plan(plan, True)
        changed = deepcopy(plan)
        changed['latency']['warmups'] += 100
        with self.assertRaisesRegex(C.ScriptError, 'budgeted design'):
            C.validate_plan(changed, True)

    def test_offline_replay_uses_saved_definitions_without_loading_current_draft(self):
        job = C.schedule(self.plan, True)[0]
        fixture(job, self.root/'pilot/result')
        C.write_json(self.root/'plan.json', self.plan)
        state = dict(planSha256=C.identity(self.plan), mode='pilot', elapsedSeconds=1,
                     tasks={'pilot': [dict(status='COMPLETED', directory='pilot', manifest=C.file_manifest(self.root/'pilot/result'))]})
        C.write_json(self.root/'state.json', state)
        with patch.object(sys, 'argv', ['benchmark-study.py', 'report', '--out', str(self.root), '--plan', '/missing-draft.json']):
            S.main()
        report = next((self.root/'reports').glob('*/benchmark-report.md')).read_text()
        self.assertIn('Pamięć i I/O', report)


if __name__ == '__main__':
    unittest.main()
