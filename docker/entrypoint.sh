#!/bin/sh
# Sizes the JVM heap the same way the reference implementation sizes its own
# (processm.launcher/src/main/docker/docker-start-processm.sh): half of the memory
# available to the container. Using their rule rather than a fixed number keeps the
# two systems on an equal footing — with a fixed -Xmx the reference had roughly twice
# our ceiling, which is exactly the kind of asymmetry the benchmark must not contain.
#
# Deliberately no -Xms: forcing an initial heap only inflates the idle baseline
# (measured ~200 MiB) without improving throughput, which would distort Q3.
set -e

if [ -n "$JVM_HEAP_OPTS" ]; then
    # Explicit override (e.g. for an experiment); recorded in environment.json.
    exec java $JVM_HEAP_OPTS -jar /app/application.jar
fi

if grep -qE '^[[:digit:]]+$' /sys/fs/cgroup/memory.max 2>/dev/null; then
    # cgroup v2 reports a byte count when a limit is set.
    mem=$(cat /sys/fs/cgroup/memory.max)
    mem=$((mem / 1024))
else
    # No limit set — fall back to total RAM, which /proc/meminfo reports in kB.
    mem=$(sed -E 's/^.* ([[:digit:]]*) .*$/\1/;q' < /proc/meminfo)
fi
mem=$((mem / 2))

echo "Sizing the JVM heap at ${mem} kB (half of the memory available to the container)"
exec java -Xmx"${mem}"k -jar /app/application.jar
