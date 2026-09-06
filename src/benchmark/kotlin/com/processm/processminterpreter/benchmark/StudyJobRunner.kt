package com.processm.processminterpreter.benchmark

import java.nio.file.Path

/** Execute one task supplied by benchmark-study.py. */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "Usage: StudyJobRunner <job.json> <new-output-directory> [study-input-cache]" }
    val job = StudyJob.load(Path.of(args[0]))
    val out = Path.of(args[1])
    val cache = args.getOrNull(2)?.let(Path::of) ?: out.resolve("inputs")
    StudyCollector(job, out, cache).run()
}
