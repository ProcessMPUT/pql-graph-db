from pathlib import Path
import runpy
import unittest


SCRIPT = Path(__file__).with_name("prepare-benchmark-stack.py")
MODULE = runpy.run_path(str(SCRIPT), run_name="prepare_benchmark_stack")


class PrepareBenchmarkStackHeapTest(unittest.TestCase):
    def test_parses_java_heap_units_from_process_commands(self) -> None:
        parse = MODULE["parse_effective_max_heap_bytes"]

        self.assertEqual(768 * 1024**2, parse("java -Xms512m -Xmx768m -jar neo4j.jar"))
        self.assertEqual(3 * 1024**3, parse("java -Xmx3145728k -jar launcher.jar"))
        self.assertEqual(3 * 1024**3, parse("java -Xmx3G -jar launcher.jar"))

    def test_rejects_missing_or_ambiguous_heap_limits(self) -> None:
        parse = MODULE["parse_effective_max_heap_bytes"]
        error = MODULE["ScriptError"]

        with self.assertRaises(error):
            parse("java -jar app.jar")
        with self.assertRaises(error):
            parse("java -Xmx768m -jar a.jar\njava -Xmx1g -jar b.jar")


if __name__ == "__main__":
    unittest.main()
