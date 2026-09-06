"""Protocol-27 schedule and evidence contract. Pure/offline; no process or API calls."""
from collections import Counter, defaultdict
import csv
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from _common import ScriptError, repo_root

RESOURCES = repo_root() / 'src/benchmark/resources'
CONTAINERS = {'processm-interpreter', 'processm-neo4j', 'processm-server'}
PRIMARY_DATASETS = ['size-100k', 'size-1m']
PRIMARY_QUERIES = ['hierarchyWindow', 'hoistedPositive', 'hierarchyCardinality']
REQUIRED_QUERY_PAIRS = 30  # Supervisor requirement for every final latency block.
RESOURCE_DATASETS = ['size-1k','size-100k','size-1m','variants-1','variants-2000','real-sepsis','real-hospital']
MEMORY_COMPONENTS = {'local': {'processm-interpreter', 'processm-neo4j'}, 'reference': {'processm-server'}}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024*1024), b''): h.update(chunk)
    return h.hexdigest()


def identity(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def read_xes_json(path):
    """Preserve repeated XML child fields emitted by ProcessM's streaming JSON writer."""
    children = {'log', 'trace', 'event', 'extension', 'classifier', 'global',
                'string', 'date', 'int', 'float', 'boolean', 'id', 'list', 'container', 'values'}

    def collect(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                require(key in children, f'Ambiguous duplicate XES-JSON field: {key}')
                previous = result[key]
                result[key] = (previous if isinstance(previous, list) else [previous]) + \
                              (value if isinstance(value, list) else [value])
            else:
                result[key] = value
        return result

    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=collect)


def write_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    temporary.replace(path)


def rows(path):
    with Path(path).open(encoding='utf-8-sig', newline='') as f: return list(csv.DictReader(f))


def definitions(resource_root=RESOURCES):
    return read_json(resource_root / 'benchmark-datasets.json')['full'], read_json(resource_root / 'benchmark-queries.json')


def campaign_resources(root, plan, state):
    """Recover declared definitions without accepting a failed attempt's measurements."""
    attempts = [a for group in state['tasks'].values() for a in group]
    attempts.sort(key=lambda a: a['status'] != 'COMPLETED')
    candidates = [Path(root)/a['directory']/'result' for a in attempts] + [RESOURCES]
    expected = {'benchmark-datasets.json': plan['datasetDefinitionsSha256'],
                'benchmark-queries.json': plan['queryDefinitionsSha256']}
    for source in candidates:
        if all((source/name).is_file() and digest(source/name) == value for name, value in expected.items()):
            return source
    raise ScriptError('No definition snapshot matches the campaign plan; the current draft cannot replace missing recorded definitions')


def applies(query, dataset):
    return (dataset['series'] in query['measurementSeries'] and
            ('measurementDatasets' not in query or dataset['name'] in query['measurementDatasets']))


def design_identity(plan):
    return identity(dict(plan, status='draft', pilotEvidence=None))


def validate_plan(plan, frozen=False, resource_root=RESOURCES):
    require(plan.get('protocolVersion') == 27 and plan.get('analysis') == 'focused-sign-v1', 'Unsupported study protocol')
    require(plan.get('status') in ('draft', 'frozen'), 'Invalid plan status')
    if frozen or plan['status'] == 'frozen':
        require(plan['latency']['pairs'] == REQUIRED_QUERY_PAIRS, 'Final latency blocks require exactly 30 pairs (supervisor requirement)')
    require(plan.get('queryDefinitionsSha256') == digest(resource_root/'benchmark-queries.json'), 'Changed query definitions')
    require(plan.get('datasetDefinitionsSha256') == digest(resource_root/'benchmark-datasets.json'), 'Changed dataset definitions')
    require(plan['primary']['datasets'] == PRIMARY_DATASETS and plan['primary']['queries'] == PRIMARY_QUERIES,
            'The six primary comparisons are part of the protocol, not selected from results')
    require(plan['primary']['replicates'] == 12, 'Protocol 27 predeclares twelve focused preparations')
    require(plan['primary']['baseline'] == 'minimalWindow', 'Primary preparations require a descriptive baseline')
    require(plan['resources']['datasets'] == RESOURCE_DATASETS, 'Resource endpoints and representative real logs are predeclared')
    budget = plan['budgetSeconds']
    require(budget is None or (type(budget) is int and budget > 0), 'Execution budget must be null or a positive integer')
    pilot_budget = plan['pilot'].get('budgetSeconds')
    require(pilot_budget is None or (type(pilot_budget) is int and pilot_budget > 0),
            'Pilot budget must be null or a positive integer')
    require(plan['maxAttemptsPerJob'] in (1, 2), 'At most one documented replacement per task')
    for group in ('latency', 'pilot'):
        x = plan[group]
        require(x['warmups'] >= 0 and x['pairs'] > 0 and x['pairs'] % 2 == 0 and x['globalWarmupRounds'] >= 0, 'Invalid query counts')
    require(plan['resources']['windowSeconds'] > 0 and plan['resources']['minimumSamples'] >= 3 and
            plan['resources']['warmups'] >= 0 and plan['resources']['globalWarmupRounds'] >= 0, 'Invalid resource settings')
    require(plan['imports']['pairs'] > 0, 'Invalid import count')
    ds, qs = definitions(resource_root)
    names = {d['name'] for d in ds}
    for q in qs:
        if 'measurementDatasets' in q:
            chosen = q['measurementDatasets']
            require(chosen and len(chosen) == len(set(chosen)) and set(chosen) <= names,
                    f"Invalid dataset selection for {q['label']}")
            require(all(applies(q, d) for d in ds if d['name'] in chosen), 'Dataset selection conflicts with query series')
        require(set(q.get('expectedResponses', {})) <= {d['name'] for d in ds if applies(q, d)},
                f"Expected response outside measured matrix: {q['label']}")
    require(set(plan['pilot']['datasets']) <= {d['name'] for d in ds} and len(set(plan['pilot']['datasets'])) == len(plan['pilot']['datasets']), 'Invalid pilot datasets')
    require({'size-1k', 'size-1m', 'variants-2000', 'real-road-traffic'} <= set(plan['pilot']['datasets']), 'Pilot omits a required boundary case')
    selectivity = [d for d in ds if d['series'] == 'selectivity-scaling']
    largest = max((d['traces']*d['eventsPerTrace'] for d in selectivity), default=0)
    controlled_boundaries = {d['name'] for d in selectivity if d['traces']*d['eventsPerTrace'] == largest}
    controlled_boundaries |= {'trace-length-10', 'trace-length-1000'} & names
    require(controlled_boundaries <= set(plan['pilot']['datasets']), 'Pilot omits a controlled-series boundary case')
    required = {(d['series'], q['label']) for d in ds for q in qs if applies(q, d)}
    observed = {(d['series'], q['label']) for d in ds if d['name'] in plan['pilot']['datasets'] for q in qs if applies(q, d)}
    require(required <= observed, f'Pilot omits query/series combinations: {sorted(required - observed)}')
    resource_cells(schedule(plan, pilot=True, resource_root=resource_root)[0], resource_root)
    if frozen:
        require(plan['status'] == 'frozen' and isinstance(plan.get('pilotEvidence'), dict), 'Run a pilot and freeze the plan first')
        evidence = plan['pilotEvidence']
        estimate = evidence.get('estimatedSeconds')
        require(type(estimate) in (int, float) and math.isfinite(estimate) and estimate > 0, 'Missing finite pilot forecast')
        require(budget is None or estimate <= budget, 'Pilot forecast exceeds the study budget')
        require(len(evidence.get('manifestSha256', '')) == 64, 'Missing pilot evidence identity')
        require(evidence.get('designSha256') == design_identity(plan), 'Frozen parameters differ from the budgeted design')


def require(condition, message):
    if not condition: raise ScriptError(message)


def schedule(plan, pilot=False, resource_root=RESOURCES):
    if not pilot:
        require(plan['latency']['pairs'] == REQUIRED_QUERY_PAIRS, 'Final latency blocks require exactly 30 pairs (supervisor requirement)')
    ds, qs = definitions(resource_root)
    series = {name: [d['name'] for d in ds if d['series'] == name] for name in {d['series'] for d in ds}}
    by_name = {d['name']: d for d in ds}
    def job(name, kind, phase, datasets, selected=None, seed=None):
        query_labels = [q['label'] for q in qs if (selected is None or q['label'] in selected) and
                        any(applies(q, by_name[d]) for d in datasets)] if kind not in ('imports','storage') else []
        settings = plan['pilot'] if pilot else plan['latency']
        if kind == 'resources': settings = dict(plan['resources'], pairs=2)
        if kind in ('imports', 'compatibility', 'storage'): settings = dict(warmups=0, pairs=2, globalWarmupRounds=0)
        return dict(id=name,kind=kind,phase=phase,planSha256=identity(plan),planStatus=plan['status'],protocolVersion=27,
                    datasetNames=datasets,queryLabels=query_labels,seed=plan['seed'] if seed is None else seed,
                    warmups=settings['warmups'],pairs=settings['pairs'],globalWarmupRounds=settings['globalWarmupRounds'],
                    importPairs=plan['imports']['pairs'] if kind == 'imports' else 1,
                    resourceWindowSeconds=plan['resources']['windowSeconds'],resourceMinimumSamples=plan['resources']['minimumSamples'],
                    queryDefinitionsSha256=plan['queryDefinitionsSha256'],datasetDefinitionsSha256=plan['datasetDefinitionsSha256'])
    if pilot:
        pilot_job = job('pilot', 'pilot', 'pilot', plan['pilot']['datasets'])
        if 'resourceProbe' in plan['pilot']: pilot_job['resourceProbe'] = plan['pilot']['resourceProbe']
        return [pilot_job]
    result = [job('compatibility', 'compatibility', 'compatibility', [])]
    groups = [('size',series['size-scaling']), ('variants',series['variant-scaling'])]
    groups += [(name,series[name]) for name in ('selectivity-scaling','trace-length-scaling') if name in series]
    groups += [(n,[n]) for n in series['real-validation']]
    for index,(name,names) in enumerate(groups):
        result.append(job('coverage-'+name, 'latency', 'coverage', names, seed=plan['seed']+index))
    for r in range(plan['primary']['replicates']):
        result.append(job(f'primary-{r+1:02}', 'latency', 'primary', plan['primary']['datasets'],
                          plan['primary']['queries']+[plan['primary']['baseline']], plan['seed']+100+r))
    result.append(job('imports-size', 'imports', 'imports', series['size-scaling']))
    for index,(name,names) in enumerate(groups):
        chosen = [n for n in names if n in plan['resources']['datasets']]
        if chosen: result.append(job('resources-'+name, 'resources', 'resources', chosen, seed=plan['seed']+200+index))
    for name in series['size-scaling']: result.append(job('storage-'+name, 'storage', 'storage', [name]))
    return result


def cells(job, resource_root=RESOURCES):
    ds, qs = definitions(resource_root)
    by_name = {d['name']:d for d in ds}
    return [(d,q['label']) for d in job['datasetNames'] for q in qs
            if q['label'] in job['queryLabels'] and applies(q, by_name[d])]


def resource_cells(job, resource_root=RESOURCES):
    if job['kind'] not in ('resources', 'pilot'): return []
    measured = cells(job, resource_root)
    probe = job.get('resourceProbe')
    if probe is None: return measured
    require(job['kind'] == 'pilot' and isinstance(probe, dict) and set(probe) == {'dataset', 'query'},
            'Invalid pilot resource probe')
    selected = (probe['dataset'], probe['query'])
    require(selected in measured, 'Pilot resource probe must belong to its query matrix')
    return [selected]


def inventory(plan, resource_root=RESOURCES):
    jobs = schedule(plan, resource_root=resource_root)
    counts = Counter(j['phase'] for j in jobs)
    blocks = Counter()
    by_series = Counter()
    ds, _ = definitions(resource_root)
    series = {d['name']: d['series'] for d in ds}
    requests = 0
    global_requests = 0
    for j in jobs:
        blocks[j['phase']] += len(cells(j, resource_root))
        global_requests += j['globalWarmupRounds'] * len(j['queryLabels']) * 2
        if j['phase'] == 'coverage': by_series.update(series[d] for d, q in cells(j, resource_root))
        if j['kind'] == 'latency': requests += len(cells(j, resource_root))*2*(j['warmups']+j['pairs'])
    return dict(jobs=len(jobs),tasksByPhase=dict(counts),blocksByPhase=dict(blocks),latencyRequestsWithWarmup=requests,
                coverageBlocksBySeries=dict(by_series),datasetCount=len(ds),globalWarmupRequests=global_requests,
                primaryTests=len(PRIMARY_DATASETS)*len(PRIMARY_QUERIES),primaryPreparations=plan['primary']['replicates'],
                resourceMinimumSeconds=blocks['resources']*2*plan['resources']['windowSeconds'],budgetSeconds=plan['budgetSeconds'])


def events(root):
    result = []
    for line in (root/'execution.jsonl').read_text().splitlines():
        try: result.append(json.loads(line))
        except json.JSONDecodeError: break
    return result


def stable_environment(env):
    return dict(source=env.get('source',{}).get('gitCommit'),host=env.get('host'),docker=env.get('dockerEngine'),
                containers={name:{k:c.get(k) for k in ('imageId','memoryLimitBytes','memorySwapLimitBytes','nanoCpus',
                    'memoryConfigEnv','effectiveJvmHeap','jvmVersion')} for name,c in env.get('containers',{}).items()})


def check_environment(env):
    require(env.get('source',{}).get('gitCommit') and env.get('source',{}).get('gitDirty') is False, 'Uncommitted measurement code')
    require(set(env.get('containers',{})) == CONTAINERS, 'Incomplete measured runtime')
    for name,c in env['containers'].items():
        require(c.get('imageId') and c.get('running') and not c.get('oomKilled') and c.get('restartCount') == 0,
                f'Unhealthy container: {name}')


def paired(records, count):
    keys = [(r['system'],r['run']) for r in records]
    require(len(keys) == len(set(keys)) and set(keys) == {(s,str(n)) for s in ('local','reference') for n in range(1,count+1)},
            'Missing or duplicated paired observation')
    require(all(r['status'] == 'OK' and math.isfinite(float(r['seconds'])) and float(r['seconds']) > 0 for r in records),
            'Invalid timing or response')
    return {s:statistics.median(float(r['seconds']) for r in records if r['system'] == s) for s in ('local','reference')} if count else {}


def memory_totals(records):
    """Sum simultaneous active components before aggregation; never add component medians."""
    snapshots = defaultdict(dict)
    for row in records:
        system = row.get('activeSystem')
        if row.get('withinWindow') != 'true' or row['component'] not in MEMORY_COMPONENTS.get(system, set()):
            continue
        key = (row['datasetName'], row['operationLabel'], row['phase'], system, row['timestamp'])
        components = snapshots[key]
        require(row['component'] not in components, 'Duplicated active memory sample')
        value = int(row['bytes'])
        require(value >= 0, 'Negative memory sample')
        components[row['component']] = value
    totals = defaultdict(list)
    for key, components in sorted(snapshots.items()):
        if set(components) == MEMORY_COMPONENTS[key[3]]:
            totals[key[:4]].append((key[4], sum(components.values())))
    return dict(totals)


def response_control(expected, body):
    """Check the declared synthetic answer independently of LOCAL/REFERENCE agreement."""
    def nodes(value, sibling):
        if isinstance(value, list): return [n for v in value for n in nodes(v, sibling)]
        if isinstance(value, dict): return [value] + nodes(value.get(sibling), sibling)
        return []
    documents = body if isinstance(body, list) else [body]
    logs = [log for d in documents if isinstance(d, dict) for log in nodes(d.get('log'), 'log')]
    traces = [t for log in logs for t in nodes(log.get('trace'), 'trace')]
    events = [e for trace in traces for e in nodes(trace.get('event'), 'event')]
    require((len(logs), len(traces), len(events)) == (expected['logs'], expected['traces'], expected['events']),
            'Response violates expected hierarchy cardinality')
    for scope, components in (('log', logs), ('trace', traces)):
        for key, attribute in expected.get(scope + 'Attributes', {}).items():
            require(len(components) == 1, f'Aggregate control requires exactly one {scope}')
            found = [a for kind in ('int', 'float', 'date', 'string', 'boolean', 'id')
                     for a in nodes(components[0].get(kind), kind) if a.get('@key') == key]
            typed = [a for a in nodes(components[0].get(attribute['type']), attribute['type']) if a.get('@key') == key]
            require(len(found) == len(typed) == 1 and '@value' in typed[0], f'Missing, duplicated or mistyped {scope} aggregate: {key}')
            actual, wanted = typed[0]['@value'], attribute['value']
            try:
                if attribute['type'] == 'int':
                    equal = str(actual).lstrip('-').isdigit() and int(actual) == int(wanted)
                elif attribute['type'] == 'date':
                    equal = datetime.fromisoformat(str(actual).replace('Z', '+00:00')) == datetime.fromisoformat(wanted.replace('Z', '+00:00'))
                else:
                    equal = str(actual) == wanted
            except (ValueError, TypeError):
                equal = False
            require(equal, f'Response violates expected {scope} aggregate: {key}')


def validate_job(job, root):
    """Fail closed before an attempt becomes reusable. No automatic retry based on its effect."""
    root = Path(root)
    if job['kind'] == 'latency':
        require(job['pairs'] == REQUIRED_QUERY_PAIRS, 'Final latency blocks require exactly 30 pairs (supervisor requirement)')
    if job['kind'] == 'storage':
        data = rows(root/'storage-scaling.csv')
        require(len(data) == 2 and {(r['datasetName'],r['system']) for r in data} ==
                {(job['datasetNames'][0],s) for s in ('local','reference')}, 'Incomplete isolated storage point')
        require(all(r['measurementMode'] == 'isolated-fresh-stack' and int(r['deltaBytes']) > 0 for r in data), 'Invalid isolated storage')
        require(len({r['stackPreparationId'] for r in data}) == 1 and all(r['stackPreparationId'] for r in data), 'Missing storage preparation')
        return dict(storage=data)
    require(read_json(root/'job.json') == job, 'Collector executed a different job')
    require(digest(root/'benchmark-queries.json') == job['queryDefinitionsSha256'] and
            digest(root/'benchmark-datasets.json') == job['datasetDefinitionsSha256'], 'Changed collected definitions')
    log = events(root)
    terminal = [e for e in log if e['kind'] == 'run-ended']
    require(len(terminal) == 1 and terminal[0]['data']['status'] == 'COMPLETED', 'Incomplete task; completed CSV blocks alone are not a preparation')
    warmups = [e['data'] for e in log if e['kind'] == 'global-warmup']
    require(len(warmups) == job['globalWarmupRounds']*len(job['queryLabels']) and
            {(r['round'],r['query']) for r in warmups} == {(i,q) for i in range(1,job['globalWarmupRounds']+1) for q in job['queryLabels']},
            'Incomplete global warmup')
    env = read_json(root/'environment.json')
    require(env.get('benchmarkProtocolVersion') == 27, 'Wrong collected protocol')
    for field,value in (('warmups',job['warmups']),('repetitions',job['pairs']),('importRepetitions',job['importPairs']),
                        ('globalWarmupRounds',job['globalWarmupRounds']),('datasetOrderSeed',job['seed']),
                        ('resourceWindowSeconds',job['resourceWindowSeconds']),('resourceMinimumSamples',job['resourceMinimumSamples'])):
        require(env.get(field) == value, f'Collector settings differ from planned {field}')
    check_environment(env)
    after = read_json(root/'environment-after.json')
    check_environment(after)
    require(stable_environment(env) == stable_environment(after), 'Environment changed within task')
    proof = read_json(root/'stack-preparation.json')
    require(proof.get('freshVolumes') and proof.get('preparationId'), 'Missing fresh preparation proof')
    require(proof.get('gitCommit') == env['source']['gitCommit'] and proof.get('imageIds') ==
            {k:v['imageId'] for k,v in env['containers'].items()}, 'Prepared image differs from measured image')
    require(all(r['status'] == 'DELETED' for r in read_json(root/'cleanup.json')), 'Cleanup did not complete')
    result = dict(environment=env,preparationId=proof['preparationId'])
    if job['kind'] == 'compatibility':
        found = list((root/'compatibility').glob('*/results.csv'))
        require(len(found) == 1, 'Missing or ambiguous compatibility report')
        data = rows(found[0])
        from compatibility_query_set import multi_log_compatibility_queries, thesis_queries
        expected = {(n,q.label) for n in ('Hospital_log','JournalReview','Sepsis','teleclaims') for q in thesis_queries()}
        expected |= {('JournalReview + Sepsis',q.label) for q in multi_log_compatibility_queries()}
        keys = [(r['Log'],r['Query']) for r in data]
        require(len(keys) == len(set(keys)) and set(keys) == expected, 'Incomplete compatibility matrix')
        require(all(r['Status'] in ('MATCH','INFO') for r in data), 'Compatibility contains strict problems')
        return dict(result,compatibility=data)
    data = rows(root/'datasets.csv')
    require(len(data) == len(job['datasetNames']) and {d['datasetName'] for d in data} == set(job['datasetNames']), 'Changed dataset matrix')
    for d in data:
        path = root/'generated-datasets'/(d['datasetName']+'.xes.gz')
        require(path.is_file() and digest(path) == d['fileSha256'], 'Missing or changed input bytes')
    imports = rows(root/'import-results.csv')
    require({r['datasetName'] for r in imports} == set(job['datasetNames']), 'Unexpected import dataset')
    for name in job['datasetNames']: paired([r for r in imports if r['datasetName'] == name], job['importPairs'])
    raw = rows(root/'query-results.csv')
    _, query_definitions = definitions(root)
    query_by_label = {q['label']: q for q in query_definitions}
    records_expected = job['warmups'] > 0 or job['kind'] in ('latency', 'pilot')
    expected_cells = set(cells(job, root)) if records_expected else set()
    require({(r['datasetName'],r['queryLabel']) for r in raw} == expected_cells, 'Changed query matrix')
    for d,q in cells(job, root):
        block = [r for r in raw if (r['datasetName'],r['queryLabel']) == (d,q)]
        require(all(r['phase'] in ('warmup','warm') for r in block), 'Unexpected phase')
        paired([r for r in block if r['phase'] == 'warmup'], job['warmups'])
        warm = [r for r in block if r['phase'] == 'warm']
        paired(warm, job['pairs'] if job['kind'] in ('latency','pilot') else 0)
        for r in warm:
            relative = Path(r.get('responsePath',''))
            require(not relative.is_absolute() and '..' not in relative.parts and relative.parts[:1] == ('responses',), 'Invalid response path')
            require((root/relative).is_file() and digest(root/relative) == relative.stem, 'Missing or changed semantic response evidence')
            expected = query_by_label[q].get('expectedResponses', {}).get(d)
            if expected:
                require(tuple(int(r[k]) for k in ('logCount','traceCount','eventCount')) ==
                        tuple(expected[k] for k in ('logs','traces','events')), 'Recorded counts violate the expected response')
                response_control(expected, read_xes_json(root/relative))
    if job['phase'] in ('coverage','pilot'):
        trips = rows(root/'roundtrip-results.csv')
        require(len(trips) == len(data) and {r['datasetName'] for r in trips} == set(job['datasetNames']) and
                all(r['status'] == 'MATCH' for r in trips), 'Incomplete XES roundtrip verification')
    if job['kind'] in ('resources','pilot'):
        windows = [e['data'] for e in log if e['kind'] == 'resource-window-completed']
        expected = {(d,q,s) for d,q in resource_cells(job, root) for s in ('local','reference')}
        require(len(windows) == len(expected) and {(w['dataset'],w['query'],w['system']) for w in windows} == expected, 'Incomplete resource windows')
        memory = rows(root/'memory-results.csv')
        io = rows(root/'container-io.csv')
        totals = memory_totals(memory)
        for w in windows:
            require(w['status'] == 'OK' and w['samples'] >= job['resourceMinimumSamples'] and w['completedRequests'] > 0 and
                    (w['finishedNanos']-w['startedNanos'])/1e9 >= job['resourceWindowSeconds'], 'Insufficient resource coverage')
            components = MEMORY_COMPONENTS[w['system']]
            timestamps = []
            for component in components:
                samples = [r for r in memory if r['phase'] == 'queries' and r['datasetName'] == w['dataset'] and
                           r['operationLabel'] == w['query'] and r['activeSystem'] == w['system'] and r['component'] == component and r['withinWindow'] == 'true']
                require(len({r['timestamp'] for r in samples}) >= job['resourceMinimumSamples'], 'Missing raw memory coverage')
                timestamps.append({r['timestamp'] for r in samples})
                evidence = [r for r in io if r['phase'] == 'query' and r['datasetName'] == w['dataset'] and r['operationLabel'] == w['query'] and r['component'] == component]
                require(len(evidence) == 1 and evidence[0]['blockReadBytes'] != '' and evidence[0]['blockWriteBytes'] != '', 'Missing raw Block I/O')
                r = evidence[0]
                require(int(r['completedOperations']) == w['completedRequests'] and
                        int(r['windowStartedNanos']) == w['startedNanos'] and int(r['windowFinishedNanos']) == w['finishedNanos'],
                        'I/O accounting differs from its resource window')
                require(r['status'] != 'COUNTER_RESET' and int(r['blockReadBytes']) >= 0 and int(r['blockWriteBytes']) >= 0,
                        'Reset or negative resource counters')
                if component in ('processm-interpreter','processm-server'):
                    require(r.get('networkReceiveBytes','') != '' and r.get('networkTransmitBytes','') != '', 'Missing application-boundary Network I/O')
            require(len(set.intersection(*timestamps)) >= job['resourceMinimumSamples'], 'Missing simultaneous component memory coverage')
            observed = totals.get((w['dataset'], w['query'], 'queries', w['system']), [])
            require(len(observed) == w['samples'], 'Recorded memory sample count differs from raw evidence')
        result.update(memoryTotals=totals, resourceWindows=windows, io=io)
    return dict(result,datasets=data,queries=raw,imports=imports)


def file_manifest(root):
    return {str(p.relative_to(root)):digest(p) for p in sorted(root.rglob('*')) if p.is_file()}


def verify_manifest(root, manifest):
    require(file_manifest(root) == manifest, 'Previously completed evidence has changed; refusing to reuse it')
