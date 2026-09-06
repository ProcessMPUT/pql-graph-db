"""Offline report checks; HTTP is mocked and no databases are used."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

SPEC = importlib.util.spec_from_file_location("compat_report", Path(__file__).with_name("run-compatibility-report.py"))
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)
from compatibility_query_set import Query, multi_log_compatibility_queries, thesis_queries


class CompatibilityReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.args = argparse.Namespace(save_full_snapshots=False, skip_failure_snapshots=True, measure_payload_size=False)
        self.report = REPORT.Report(self.args, Path(self.directory.name))
        self.response = dict(match=True, localSuccess=True, remoteSuccess=True, localCount=0, remoteCount=0,
                             comparisonStatus="MATCH", details="Comparison: Both empty", localResults=[], remoteResults=[])
        self.report.verify = Mock(return_value=self.response)
        self.case = dict(name="JournalReview + Sepsis", primaryLogName="JournalReview", secondaryLogName="Sepsis Cases - Event Log")

    def test_positive_filter_cannot_pass_on_two_empty_responses(self):
        query = next(q for q in multi_log_compatibility_queries() if q.label == "multiLogSecondaryWhere")
        query = REPORT.resolve_for_case(self.case, query)
        self.assertIn("Sepsis Cases - Event Log", query.query)
        result = self.report.check(self.case, query)
        self.assertEqual("ERROR", result["Status"])
        self.assertTrue(result["Match"])  # Endpoint comparison is preserved separately.
        self.assertIn("requires at least 1", result["Details"])
        self.response.update(localCount=1, remoteCount=1)
        self.assertEqual("MATCH", self.report.check(self.case, query)["Status"])

    def test_all_logs_requires_both_logs(self):
        self.response.update(localCount=1, remoteCount=1)
        query = multi_log_compatibility_queries()[0]
        self.assertEqual("ERROR", self.report.check(self.case, query)["Status"])
        self.response.update(localCount=2, remoteCount=2)
        self.assertEqual("MATCH", self.report.check(self.case, query)["Status"])

    def test_empty_negative_case_and_matching_rejection_still_pass(self):
        self.assertEqual("MATCH", self.report.check(self.case, Query("negative", "where 0=1"))["Status"])
        self.response.update(localSuccess=False, remoteSuccess=False)
        self.assertEqual("MATCH", self.report.check(self.case, Query("zero", "limit e:0"))["Status"])

    def test_full_evidence_and_payload_reuse_the_same_execution(self):
        self.args.save_full_snapshots = self.args.measure_payload_size = True
        self.response.update(comparisonStatus="NONDETERMINISTIC_MATCH", match=False)
        result = self.report.check(self.case, Query("ties", "group by ^e:name"))
        self.assertEqual("INFO", result["Status"])
        self.assertEqual(1, self.report.verify.call_count)
        self.assertEqual("full", self.report.verify.call_args.args[1])
        self.assertEqual("same-response", result["SnapshotKind"])
        self.assertEqual(self.response, json.loads(Path(result["SnapshotPath"]).read_text()))

    def test_light_failure_snapshot_is_labelled_as_replay(self):
        self.args.skip_failure_snapshots = False
        self.response.update(comparisonStatus="MISMATCH", match=False)
        result = self.report.check(self.case, Query("different", "select e:name"))
        self.assertEqual("MISMATCH", result["Status"])
        self.assertEqual("diagnostic-replay", result["SnapshotKind"])
        self.assertEqual(2, self.report.verify.call_count)

    def test_full_thesis_matrix_preserves_each_response_snapshot(self):
        self.args.save_full_snapshots = True
        queries = thesis_queries()
        checks = [
            (dict(name=name, localDataStoreId=f"local-{name}", remoteDataStoreId=f"remote-{name}"), query)
            for name in ("Hospital_log", "JournalReview", "Sepsis", "teleclaims")
            for query in queries
        ]
        checks += [(self.case, REPORT.resolve_for_case(self.case, query)) for query in multi_log_compatibility_queries()]
        self.assertEqual(282, len(checks))
        saved = []
        for index, (case, query) in enumerate(checks):
            response = dict(self.response, localCount=2, remoteCount=2,
                            localResults=[index], remoteResults=[index])
            self.report.verify.return_value = response
            result = self.report.check(case, query)
            self.assertEqual("same-response", result["SnapshotKind"])
            saved.append((Path(result["SnapshotPath"]), response))

        self.assertEqual(282, self.report.verify.call_count)
        self.assertEqual(282, len({path for path, _ in saved}))
        self.assertEqual(282, len(list(self.report.failure_directory.glob("*.json"))))
        for path, response in saved:
            self.assertEqual(response, json.loads(path.read_text(encoding="utf-8")))

    def test_colliding_labels_also_keep_separate_diagnostic_errors(self):
        self.args.skip_failure_snapshots = False
        queries = [query for query in thesis_queries() if query.label.startswith("whereHoisting")]
        self.assertEqual(2, len(queries))
        paths = []
        for query in queries:
            self.report.verify.side_effect = REPORT.ScriptError(f"unavailable: {query.query}")
            result = self.report.check(self.case, query)
            self.assertEqual("ERROR", result["Status"])
            paths.append(Path(result["SnapshotPath"]))
        self.assertEqual(2, len(set(paths)))
        for path, query in zip(paths, queries):
            self.assertEqual(f"unavailable: {query.query}", path.read_text(encoding="utf-8"))

    def test_frozen_workload_keeps_69_named_cases_and_68_texts(self):
        queries = thesis_queries()
        self.assertEqual(69, len(queries))
        self.assertEqual(69, len({q.label for q in queries}))
        self.assertEqual(68, len({q.query for q in queries}))
        self.assertTrue(all(q.comparison == "strict" for q in queries))

    def test_transport_failure_remains_an_error(self):
        self.report.verify.side_effect = REPORT.ScriptError("connection refused")
        result = self.report.check(self.case, Query("test", "select l:name"))
        self.assertEqual("ERROR", result["Status"])
        self.assertFalse(result["Match"])
        self.assertIn("connection refused", result["Details"])


if __name__ == "__main__":
    unittest.main()
