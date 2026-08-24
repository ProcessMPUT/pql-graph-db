import csv
from pathlib import Path
import runpy
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("measure-storage-scaling.py")


class StorageScalingDatasetSelectionTest(unittest.TestCase):
    def test_protocol_12_size_series_is_selected(self):
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
                    ),
                )
            (datasets_dir / "size-1k.xes.gz").write_bytes(b"fixture")

            self.assertEqual(namespace["dataset_names"](datasets_dir), ["size-1k"])


if __name__ == "__main__":
    unittest.main()
