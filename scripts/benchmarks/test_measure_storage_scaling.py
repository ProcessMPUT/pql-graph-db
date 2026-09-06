import csv
from pathlib import Path
import runpy
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("measure-storage-scaling.py")


class StorageScalingDatasetSelectionTest(unittest.TestCase):
    def test_only_current_size_series_is_selected(self):
        namespace = runpy.run_path(str(SCRIPT), run_name="measure_storage_scaling")

        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            datasets_dir = run_dir / "generated-datasets"
            datasets_dir.mkdir()
            with (run_dir / "datasets.csv").open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=("datasetName", "series"))
                writer.writeheader()
                writer.writerows(
                    (
                        {"datasetName": "size-1k", "series": "size-scaling"},
                        {"datasetName": "real-log", "series": "real-validation"},
                        {"datasetName": "old-axis", "series": "trace-scaling"},
                    ),
                )
            (datasets_dir / "size-1k.xes.gz").write_bytes(b"fixture")

            self.assertEqual(namespace["dataset_names"](datasets_dir), ["size-1k"])


class StoragePointTest(unittest.TestCase):
    def exercise(self, existing=False):
        from types import SimpleNamespace
        from unittest.mock import Mock, patch
        namespace = runpy.run_path(str(SCRIPT), run_name='storage_probe')
        main = namespace['main']
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/'size-1k.xes').write_bytes(b'plain input')
            (root/'size-1k.xes.gz').write_bytes(b'compressed')
            output = root/'storage.csv'
            if existing:
                output.write_text('preserved evidence')
            images = {name: 'sha256:'+name for name in namespace['IMAGE_COLUMNS'].values()}
            prepare = Mock(return_value=dict(gitCommit='commit', imageIds=images, preparationId='fresh', preparedAtUtc='now'))
            upload = Mock()
            args = SimpleNamespace(confirm_destroy_volumes=True, datasets_dir=root, dataset='size-1k', out_csv=output)
            with patch.dict(main.__globals__, parse_args=lambda: args, dataset_names=lambda _: ['size-1k'],
                            anchor_provenance=lambda _: (images, 'commit'), run_capture=lambda *a, **k: (0, 'commit'),
                            prepare_clean_stack=prepare, import_dataset=upload,
                            measure_local_bytes=Mock(side_effect=[100, 150]), measure_reference_bytes=Mock(side_effect=[200, 290])):
                if existing:
                    with self.assertRaises(namespace['ScriptError']):
                        main()
                    prepare.assert_not_called()
                    self.assertEqual('preserved evidence', output.read_text())
                else:
                    self.assertEqual(0, main())
                    prepare.assert_called_once_with(args, images['processm-interpreter'])
                    upload.assert_called_once_with(args, 'size-1k', root.resolve()/'size-1k.xes.gz')
                    with output.open() as f:
                        rows = list(csv.DictReader(f))
                    self.assertEqual(['local', 'reference'], [r['system'] for r in rows])
                    self.assertEqual(['50', '90'], [r['deltaBytes'] for r in rows])
                    self.assertEqual({'fresh'}, {r['stackPreparationId'] for r in rows})

    def test_one_fresh_point_records_both_deltas(self):
        self.exercise()

    def test_existing_result_fails_before_destructive_preparation(self):
        self.exercise(existing=True)


if __name__ == "__main__":
    unittest.main()
