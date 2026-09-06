#!/usr/bin/env python3
"""Plan, pilot, run/resume, audit and publish the protocol-27 thesis study.

Read-only by default. Only pilot/run with BOTH --execute and
--confirm-destroy-volumes may contact and recreate the measured Docker stack.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
from functools import partial
import os
from pathlib import Path
import signal
import statistics
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from study_contract import (RESOURCES, campaign_resources, cells, definitions, design_identity, digest, events, file_manifest, identity, inventory, paired,
                            read_json, require, rows, schedule, stable_environment, validate_job, validate_plan,
                            verify_manifest, write_json)
from _common import ScriptError, main_guard, repo_root, run_capture
from study_progress import write_progress


def stamp(): return datetime.now(timezone.utc).isoformat()


class StudyLock:
    """One controller owns the stack. A crashed controller can be replaced, never a live one."""
    def __init__(self, path): self.path = path
    def __enter__(self):
        if self.path.exists():
            pid = read_json(self.path)['pid']
            try: os.kill(pid, 0)
            except ProcessLookupError: self.path.unlink()
            else: raise ScriptError(f'A study controller still owns the stack (PID {pid})')
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open('x') as f:
            import json
            json.dump(dict(pid=os.getpid(),at=stamp()),f)
        return self
    def __exit__(self, *_): self.path.unlink(missing_ok=True)


def completed(root, state, jobs):
    selected = {}
    preparations = set()
    environment = None
    data_hashes = {}
    for job in jobs:
        attempts = state['tasks'].get(job['id'], [])
        accepted = [a for a in attempts if a['status'] == 'COMPLETED']
        require(len(accepted) <= 1, 'Two accepted attempts for the same task')
        if not accepted: continue
        a = accepted[0]
        out = root / a['directory'] / 'result'
        verify_manifest(out, a['manifest'])
        result = validate_job(job,out)
        if 'environment' in result:
            signature = stable_environment(result['environment'])
            if environment is None: environment = signature
            require(signature == environment, 'Different measured versions or hardware across tasks')
            proof = result['preparationId']
            require(proof not in preparations, 'Reused fresh-stack proof')
            preparations.add(proof)
            for d in result.get('datasets',[]):
                old = data_hashes.setdefault(d['datasetName'],d['fileSha256'])
                require(old == d['fileSha256'], 'Different input bytes across tasks')
        selected[job['id']] = (job,out,result)
    for job,out,result in selected.values():
        if job['kind'] != 'storage': continue
        require(environment is not None, 'Storage has no measured anchor')
        source = read_json(out/'storage-input.json')
        name = job['datasetNames'][0]
        require(source['datasetSha256'] == data_hashes.get(name), 'Storage uses different dataset bytes')
        for row in result['storage']:
            require(row['gitCommit'] == environment['source'], 'Storage uses different measurement code')
            for column,component in (('localAppImageId','processm-interpreter'),('localDbImageId','processm-neo4j'),('referenceImageId','processm-server')):
                require(row[column] == environment['containers'][component]['imageId'], 'Storage uses different images')
        proof = result['storage'][0]['stackPreparationId']
        require(proof not in preparations, 'Reused storage preparation')
        preparations.add(proof)
    return selected


def run_tasks(plan, root, runner, pilot=False, retry_reason=None, pilot_environment=None):
    """State machine shared by the real controller and offline failure/resume tests."""
    root.mkdir(parents=True, exist_ok=True)
    plan_path, state_path = root/'plan.json', root/'state.json'
    if plan_path.exists(): require(read_json(plan_path) == plan, 'Cannot resume with a changed plan')
    else: write_json(plan_path,plan)
    if state_path.exists(): state = read_json(state_path)
    else: state = dict(planSha256=identity(plan),mode='pilot' if pilot else 'final',elapsedSeconds=0.0,tasks={},createdAt=stamp())
    require(state['planSha256'] == identity(plan) and state['mode'] == ('pilot' if pilot else 'final'), 'Wrong campaign identity')
    progress_path = root/'progress.json'
    if progress_path.exists():
        progress = read_json(progress_path)
        require(progress['planSha256'] == identity(plan), 'Different progress identity')
        state['elapsedSeconds'] = max(state['elapsedSeconds'],progress['elapsedSeconds'])
    jobs = schedule(plan,pilot)
    budget = plan['pilot'].get('budgetSeconds', plan['budgetSeconds']) if pilot else plan['budgetSeconds']
    selected = completed(root,state,jobs)
    def check_pilot_environment(evidence):
        if pilot_environment is not None and 'environment' in evidence:
            require(stable_environment(evidence['environment']) == stable_environment(pilot_environment),
                    'Final environment differs from the budgeted pilot; verify the changed configuration first')
    for _,_,evidence in selected.values(): check_pilot_environment(evidence)
    for job in jobs:
        if job['id'] in selected: continue
        attempts = state['tasks'].setdefault(job['id'],[])
        if attempts:
            # A killed controller cannot silently turn its partially measured task into a new sample.
            if attempts[-1]['status'] == 'RUNNING': attempts[-1]['status'] = 'INTERRUPTED'
            write_json(state_path,state)
            require(retry_reason and retry_reason.strip(), f"{job['id']} needs --retry-failed '<infrastructure failure reason>'")
            prior = root/attempts[-1]['directory']/'result'
            if (prior/'execution.jsonl').exists():
                require(not any(e['kind'] == 'failure' and e['data'].get('retryable') is False for e in events(prior)),
                        'A semantic/control failure cannot be retried until it passes; investigate it and version the experiment')
            require(len(attempts) < plan['maxAttemptsPerJob'], f"Attempt limit reached for {job['id']}")
        remaining = None if budget is None else budget - state['elapsedSeconds']
        require(remaining is None or remaining > 0, 'Execution budget exhausted; evidence remains saved, no further tasks started')
        attempt = dict(status='RUNNING',directory=f"tasks/{job['id']}/attempt-{len(attempts)+1:02}",
                       startedAt=stamp(),reason=retry_reason if attempts else 'predeclared first attempt')
        attempts.append(attempt)
        directory = root/attempt['directory']
        directory.mkdir(parents=True,exist_ok=False)
        write_json(directory/'job.json',job)
        write_json(state_path,state)
        start = time.monotonic()
        base_elapsed = state['elapsedSeconds']
        last_saved = start - 5
        activity = dict(phase='starting', phaseStartedAt=stamp())
        def heartbeat(force=False, **update):
            nonlocal last_saved
            if update.get('phase') and update['phase'] != activity['phase']:
                activity['phaseStartedAt'] = stamp()
            activity.update(update)
            state['elapsedSeconds'] = base_elapsed + time.monotonic() - start
            if force or time.monotonic()-last_saved >= 5:
                # Never repeatedly serialize the growing evidence manifest during measured requests.
                snapshot = dict(planSha256=identity(plan), elapsedSeconds=state['elapsedSeconds'],
                                updatedAt=stamp(), taskId=job['id'], taskIndex=jobs.index(job)+1,
                                taskCount=len(jobs), attempt=len(attempts), status=attempt['status'],
                                budgetSeconds=budget, **activity)
                if attempt.get('error'): snapshot['error'] = attempt['error']
                log = Path(activity['activeLog']) if activity.get('activeLog') else None
                if log is not None and log.exists():
                    modified = log.stat().st_mtime
                    snapshot.update(lastOutputAt=datetime.fromtimestamp(modified, timezone.utc).isoformat(),
                                    lastOutputAgeSeconds=max(0, time.time()-modified))
                collector = directory/'result/live-progress.json'
                if collector.exists(): snapshot['collector'] = read_json(collector)
                write_progress(root, snapshot)
                last_saved = time.monotonic()
        cap = 'no total runtime cap' if remaining is None else f'remaining budget {remaining/60:.0f} min'
        print(f"[{len(selected)+1}/{len(jobs)}] {job['id']} — {cap}", flush=True)
        try:
            heartbeat(force=True)
            runner(job,directory,remaining,heartbeat,selected)
            heartbeat(force=True, phase='validating-evidence', processId=None)
            check_pilot_environment(validate_job(job,directory/'result'))
            attempt['manifest'] = file_manifest(directory/'result')
            attempt['status'] = 'COMPLETED'
            attempt['finishedAt'] = stamp()
            # This checks cross-task identity immediately, not hours later at publication.
            selected = completed(root,state,jobs)
        except BaseException as error:
            attempt['status'] = 'INTERRUPTED' if isinstance(error,KeyboardInterrupt) else 'FAILED'
            attempt['error'] = str(error)
            raise
        finally:
            attempt['elapsedSeconds'] = time.monotonic()-start
            heartbeat(force=True, processId=None)
            write_json(state_path,state)
    return state


def stop_process(process):
    if process.poll() is not None: return
    if os.name == 'nt':
        subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
    else:
        os.killpg(process.pid,signal.SIGTERM)
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid,signal.SIGKILL)
    process.wait()


def command(args, log, deadline, heartbeat, timeout_seconds=None):
    require(deadline is None or time.monotonic() < deadline, 'Execution budget exhausted before command')
    command_deadline = time.monotonic()+timeout_seconds if timeout_seconds is not None else None
    with log.open('ab') as output:
        process = subprocess.Popen([str(x) for x in args],cwd=repo_root(),stdout=output,stderr=subprocess.STDOUT,
                                   start_new_session=os.name != 'nt')
        try:
            heartbeat(force=True, phase=log.stem, activeLog=str(log), processId=process.pid)
            while process.poll() is None:
                heartbeat()
                if deadline is not None and time.monotonic() >= deadline:
                    raise ScriptError(f'Execution budget reached; interrupted task is saved in {log.parent}')
                if command_deadline is not None and time.monotonic() >= command_deadline:
                    raise ScriptError(f'Command timeout after {timeout_seconds}s; see {log}')
                time.sleep(.5)
            require(process.returncode == 0, f'Command failed ({process.returncode}); see {log}')
        finally: stop_process(process)


def live_runner(job, directory, remaining, heartbeat, selected, pilot_environment=None):
    deadline = None if remaining is None else time.monotonic()+remaining
    gradle = repo_root()/('gradlew.bat' if os.name == 'nt' else 'gradlew')
    # No Gradle build or daemon activity overlaps a latency block.
    command([gradle,'prepareBenchmark','--offline'],directory/'build.log',deadline,heartbeat,timeout_seconds=1800)
    if job['kind'] == 'storage':
        require('coverage-size' in selected, 'Storage requires the complete synthetic coverage task')
        anchor = selected['coverage-size'][1]
        out = directory/'result'
        out.mkdir()
        dataset = next(d for d in rows(anchor/'datasets.csv') if d['datasetName'] == job['datasetNames'][0])
        write_json(out/'storage-input.json',dict(datasetSha256=dataset['fileSha256'],anchorJob='coverage-size'))
        command([sys.executable,repo_root()/'scripts/benchmarks/measure-storage-scaling.py',
                 '--datasets-dir',anchor/'generated-datasets','--dataset',job['datasetNames'][0],
                 '--out-csv',out/'storage-scaling.csv','--confirm-destroy-volumes'],directory/'collector.log',deadline,heartbeat)
        return
    prepare = [sys.executable,repo_root()/'scripts/benchmarks/prepare-benchmark-stack.py','--confirm-destroy-volumes']
    # The first task builds the application image. Later tasks reuse its immutable identity.
    previous = next((v for v in selected.values() if 'environment' in v[2]),None)
    if previous:
        prepare += ['--reuse-local-image-id',previous[2]['environment']['containers']['processm-interpreter']['imageId']]
    elif pilot_environment is not None:
        prepare += ['--reuse-local-image-id',pilot_environment['containers']['processm-interpreter']['imageId']]
    started = time.monotonic()
    command(prepare,directory/'preparation.log',deadline,heartbeat)
    preparation_seconds = time.monotonic()-started
    java = (repo_root()/'build/benchmark-study-java.txt').read_text()
    classpath = (repo_root()/'build/benchmark-study-classpath.txt').read_text()
    command([java,'-cp',classpath,'com.processm.processminterpreter.benchmark.StudyJobRunnerKt',
             directory/'job.json',directory/'result',directory.parents[2]/'inputs'],directory/'collector.log',deadline,heartbeat)
    timing = dict(preparationSeconds=preparation_seconds)
    if job['kind'] == 'pilot':
        image = read_json(directory/'result/stack-preparation.json')['imageIds']['processm-interpreter']
        started = time.monotonic()
        command(prepare+['--reuse-local-image-id',image],directory/'preparation-reuse.log',deadline,heartbeat)
        timing['reusePreparationSeconds'] = time.monotonic()-started
    write_json(directory/'result/timing.json',timing)


def forecast(plan, pilot_root):
    """Approximate cost forecast from diagnostic pairs, separate from final inference."""
    state = read_json(pilot_root/'state.json')
    pilot_plan = read_json(pilot_root/'plan.json')
    candidate = dict(plan,status=pilot_plan['status'],pilotEvidence=pilot_plan['pilotEvidence'])
    require(candidate == pilot_plan, 'Pilot settings differ from the proposed final design')
    selected = completed(pilot_root,state,schedule(pilot_plan,True))
    require('pilot' in selected, 'Complete the boundary-case pilot before freezing')
    _,root,evidence = selected['pilot']
    raw = evidence['queries']
    ds,_ = definitions(root)
    by_name = {d['name']:d for d in ds}
    measured_cost, warmup_cost = {}, {}
    for d,q in cells(schedule(pilot_plan,True)[0]):
        for phase, count, target in [('warm', pilot_plan['pilot']['pairs'], measured_cost),
                                     ('warmup', pilot_plan['pilot']['warmups'], warmup_cost)]:
            block = [r for r in raw if r['datasetName'] == d and r['queryLabel'] == q and r['phase'] == phase]
            paired(block, count)
            # Budget elapsed work, not the median estimand used for scientific comparisons.
            target[d,q] = sum(float(r['seconds']) for r in block) / count if count else 0
    def cost(observations,d,q):
        if (d,q) in observations: return observations[d,q]
        candidates = [t for (name,label),t in observations.items()
                      if label == q and by_name[name]['series'] == by_name[d]['series']]
        require(candidates, f'Pilot has no cost observation for {q} in {by_name[d]["series"]}')
        return max(candidates)
    import_times = {d:sum(paired([r for r in evidence['imports'] if r['datasetName'] == d],1).values()) for d in pilot_plan['pilot']['datasets']}
    global_cost = {}
    for e in events(root):
        if e['kind'] == 'global-warmup': global_cost.setdefault(e['data']['query'],[]).append(sum(r['seconds'] for r in e['data']['samples'].values()))
    timing = read_json(root/'timing.json')
    preparation = timing['reusePreparationSeconds']
    def import_cost(name):
        if name in import_times: return import_times[name]
        d = by_name[name]
        if d['series'] == 'size-scaling':
            fraction = (d['traces']*d['eventsPerTrace']-1000)/(1000000-1000)
            return max(import_times['size-1k'],import_times['size-1k']+fraction*(import_times['size-1m']-import_times['size-1k']))
        candidates = [t for n,t in import_times.items() if by_name[n]['series'] == d['series']]
        require(candidates, f'Pilot has no import observation for {d["series"]}')
        return max(candidates)
    per_phase = Counter()
    components = Counter()
    per_job = []
    for j in schedule(plan):
        parts = Counter(preparation=preparation, bookkeeping=15)
        if j['kind'] == 'compatibility': parts['compatibility'] = 600
        elif j['kind'] == 'storage':
            parts['imports'] = import_cost(j['datasetNames'][0])
            parts['storageProbes'] = 30
        else:
            parts['imports'] = sum(import_cost(d)*j['importPairs'] for d in j['datasetNames'])
            require(set(j['queryLabels']) <= set(global_cost), 'Pilot omits global warmup costs for a planned query')
            parts['globalWarmup'] = j['globalWarmupRounds']*sum(statistics.mean(global_cost[q]) for q in j['queryLabels'])
            for d,q in cells(j):
                parts['blockWarmup'] += cost(warmup_cost,d,q)*j['warmups']
                if j['kind'] == 'latency': parts['measuredQueries'] += cost(measured_cost,d,q)*j['pairs']
                if j['kind'] == 'resources': parts['resourceWindowsAndProbes'] += 2*(j['resourceWindowSeconds']+5)+cost(measured_cost,d,q)
        reserved = {k:v*1.25 for k,v in parts.items()}
        seconds = sum(reserved.values())
        per_phase[j['phase']] += seconds
        components.update(reserved)
        per_job.append(dict(task=j['id'],estimatedSeconds=seconds,componentSeconds=reserved))
    return dict(estimatedSeconds=sum(per_phase.values()),phaseSeconds=dict(per_phase),componentSeconds=dict(components),
                tasks=per_job,reserveFactor=1.25,
                assumption='The short diagnostic pilot does not establish stable warmed-up performance. Query costs use separate observed means for warmup and measurement; unmeasured cells inherit the slowest example of the same query AND dataset series. Size-series imports interpolate event count; other imports use the slowest example of their series. Compatibility allowance is 10 minutes. All components include 25% reserve; these are estimates, not upper bounds.',
                designSha256=design_identity(plan),pilotPlanSha256=identity(pilot_plan),
                manifestSha256=identity(file_manifest(root)),pilotPath=str(pilot_root))


def review_campaign(root, command_name):
    """Replay only the campaign's recorded definitions, independently of the current draft."""
    plan = read_json(root/'plan.json')
    state = read_json(root/'state.json')
    require(state.get('planSha256') == identity(plan), 'Campaign state belongs to a different plan')
    source = campaign_resources(root, plan, state)
    validate_plan(plan, resource_root=source)
    jobs = schedule(plan, state['mode'] == 'pilot', resource_root=source)
    selected = completed(root, state, jobs)
    if command_name == 'audit':
        print(f'Validated {len(selected)}/{len(jobs)} tasks; elapsed {state["elapsedSeconds"]/60:.1f} min')
        require(len(selected) == len(jobs), 'The study is incomplete')
    else:
        from study_report import publish
        publish(root, plan, state, jobs, selected)


def publish_campaign(root, plan, state, jobs, selected):
    """Collection completeness does not imply that report generation succeeded."""
    from study_report import publish
    progress = read_json(root/'progress.json')
    progress.pop('error', None)
    progress.update(status='RUNNING', phase='publishing', phaseStartedAt=stamp(), updatedAt=stamp(),
                    activeLog=None, processId=None)
    write_progress(root, progress)
    try:
        report = publish(root,plan,state,jobs,selected)
        progress['reportPath'] = str(report/'benchmark-report.html')
        if state['mode'] == 'pilot':
            estimate = forecast(plan,root)
            write_json(root/'forecast.json',estimate)
            cap = 'none' if plan['budgetSeconds'] is None else f"{plan['budgetSeconds']/3600:.1f} h"
            print(f"Pilot forecast: {estimate['estimatedSeconds']/3600:.2f} h (execution cap: {cap})")
        progress.update(status='COMPLETED', phase='completed')
    except BaseException as error:
        progress.update(status='FAILED', error=str(error))
        raise
    finally:
        progress['updatedAt'] = stamp()
        write_progress(root, progress)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['plan','pilot','freeze','run','audit','report'])
    parser.add_argument('--plan',type=Path,default=RESOURCES/'study-plan.json')
    parser.add_argument('--out',type=Path)
    parser.add_argument('--pilot',type=Path,help='completed pilot campaign directory, required for freeze')
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--confirm-destroy-volumes',action='store_true')
    parser.add_argument('--retry-failed',metavar='REASON')
    args = parser.parse_args()
    if args.command in ('audit', 'report'):
        require(args.out is not None, '--out is required')
        review_campaign(args.out.resolve(), args.command)
        return 0
    plan = read_json(args.plan)
    validate_plan(plan,args.command == 'run')
    if args.command == 'plan':
        import json
        print(json.dumps(inventory(plan),indent=2))
        return 0
    require(args.out is not None, '--out is required')
    out = args.out.resolve()
    if args.command == 'freeze':
        require(args.pilot is not None, '--pilot is required')
        evidence = forecast(plan,args.pilot.resolve())
        require(plan['budgetSeconds'] is None or evidence['estimatedSeconds'] <= plan['budgetSeconds'],
                f"Pilot forecast {evidence['estimatedSeconds']/3600:.2f} h exceeds the configured budget. Revise the design before collecting final data.")
        frozen = dict(plan,status='frozen',pilotEvidence=evidence)
        validate_plan(frozen,True)
        require(not out.exists(), 'Frozen plan output already exists')
        out.parent.mkdir(parents=True,exist_ok=True)
        write_json(out,frozen)
        print(f'Frozen plan: {out}; forecast {evidence["estimatedSeconds"]/3600:.2f} h')
        return 0
    if args.command in ('pilot','run'):
        require(args.execute and args.confirm_destroy_volumes, 'Live work requires both --execute and --confirm-destroy-volumes; no services were touched')
        if args.command == 'pilot':
            require(plan['pilot'].get('resourceProbe') is not None,
                    'The diagnostic pilot requires one explicit resource probe; no services were touched')
        code,dirty = run_capture(['git','-C',str(repo_root()),'status','--porcelain'],timeout=30)
        require(code == 0 and not dirty.strip(), 'Commit source changes before live collection; no services were touched')
        pilot_environment = None
        if args.command == 'run':
            evidence = plan['pilotEvidence']
            pilot_root = Path(evidence['pilotPath'])
            pstate = read_json(pilot_root/'state.json')
            proot = pilot_root/pstate['tasks']['pilot'][-1]['directory']/'result'
            require(identity(file_manifest(proot)) == evidence['manifestSha256'], 'Pilot evidence is missing or changed')
            pilot_environment = read_json(proot/'environment.json')
            code,revision = run_capture(['git','-C',str(repo_root()),'rev-parse','HEAD'],timeout=30)
            require(code == 0 and revision.strip() == pilot_environment['source']['gitCommit'],
                    'Measurement code changed after the pilot; verify this version before collecting final data')
        with StudyLock(repo_root()/'tmp/benchmark-study-controller.lock'):
            state = run_tasks(plan,out,partial(live_runner,pilot_environment=pilot_environment),
                              args.command == 'pilot',args.retry_failed,pilot_environment)
        jobs = schedule(plan,args.command == 'pilot')
        selected = completed(out,state,jobs)
        publish_campaign(out,plan,state,jobs,selected)
        print(f'Collection completed: {out}')
        return 0


if __name__ == '__main__':
    main_guard(main)
