import json
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from study_progress import write_progress


class StudyProgressTest(unittest.TestCase):
    def publish(self, snapshot):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_progress(root, snapshot)
            self.assertEqual(json.loads((root / 'progress.json').read_text()), snapshot)
            self.assertEqual({p.name for p in root.iterdir()}, {'progress.json', 'progress.html'})
            return (root / 'progress.html').read_text()

    def test_preparation_without_collector_preserves_snapshot(self):
        snapshot = dict(taskId='pilot', taskIndex=1, taskCount=1, attempt=1,
                        status='RUNNING', phase='preparation', elapsedSeconds=61,
                        budgetSeconds=None, updatedAt='2026-09-06T00:00:00Z',
                        activeLog='preparation.log', processId=123, custom={'keep': [True, None]})
        html = self.publish(snapshot)
        self.assertIn('preparation', html)
        self.assertIn('0 h 01 min 01 s', html)
        self.assertIn('bez limitu', html)
        self.assertIn('Brak aktywnej operacji kolektora', html)
        self.assertIn('buforowania dziennika', html)
        self.assertIn('Sygnał kontrolera', html)
        self.assertIn('http-equiv="refresh" content="5"', html)

    def test_operation_and_failure_details_are_escaped(self):
        html = self.publish(dict(status='FAILED', taskId='<script>alert(1)</script>', error='attempt <error>',
            failure='controller <failed>', collector=dict(status='FAILED', completedOperations=7,
                current=dict(phase='query', dataset='A&B', query='<img src=x onerror="bad">',
                             system='reference', repetition=3, total=30, startedAt='invalid'),
                lastCompleted=dict(phase='import', completedAt='invalid'),
                lastFailure=dict(type='Mismatch', message='<bad> & "quoted"'),
                failure=dict(type='Failure', message='terminal failure'))))
        self.assertNotIn('<script>', html)
        self.assertNotIn('<img ', html)
        for expected in ('&lt;script&gt;', 'A&amp;B', '&lt;bad&gt;', r'\&quot;quoted\&quot;',
                         'attempt &lt;error&gt;', 'controller &lt;failed&gt;', 'terminal failure', '3 / 30', '>7</td>'):
            self.assertIn(expected, html)

    def test_current_duration_and_completed_age_are_distinct(self):
        with patch('study_progress.datetime') as clock:
            clock.now.return_value = datetime(2026, 9, 6, 0, 10, tzinfo=timezone.utc)
            clock.fromisoformat.side_effect = datetime.fromisoformat
            html = self.publish(dict(lastOutputAt='2026-09-06T00:09:15Z', lastOutputAgeSeconds=45,
                budgetSeconds=14400, phaseStartedAt='2026-09-05T23:00:00Z', collector=dict(completedOperations=2,
                    current=dict(phase='query', startedAt='2026-09-06T00:07:57Z'),
                    lastCompleted=dict(phase='import', completedAt='2026-09-06T00:00:00Z'))))
        for expected in ('0 h 02 min 03 s', '0 h 10 min 00 s', '0 h 00 min 45 s', '4 h 00 min 00 s', '1 h 10 min 00 s'):
            self.assertIn(expected, html)

    def test_replaces_previous_failure_without_stale_status(self):
        with tempfile.TemporaryDirectory() as directory:
            write_progress(directory, {'status': 'FAILED', 'failure': 'old failure'})
            write_progress(directory, {'status': 'COMPLETED', 'elapsedSeconds': 3600})
            root = Path(directory)
            html = (root / 'progress.html').read_text()
            self.assertNotIn('old failure', html)
            self.assertIn('COMPLETED', html)
            self.assertIn('1 h 00 min 00 s', html)
            self.assertEqual(json.loads((root / 'progress.json').read_text())['status'], 'COMPLETED')


if __name__ == '__main__':
    unittest.main()
