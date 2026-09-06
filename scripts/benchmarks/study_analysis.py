"""Offline estimates and diagnostics from validated study evidence; no presentation or services."""
from collections import defaultdict
from datetime import datetime
import math
import statistics

from study_contract import cells, paired, require


def sign_summary(effects):
    """Exact two-sided sign test and a distribution-free pointwise median interval.

    Zero log effects are assigned conservatively against the observed direction.
    The observations are independent preparations, never individual HTTP pairs.
    """
    require(effects and all(math.isfinite(v) and v > 0 for v in effects), 'Invalid independent effect')
    x = sorted(math.log(v) for v in effects)
    n = len(x)
    plus,minus = sum(v > 0 for v in x),sum(v < 0 for v in x)
    ties = n-plus-minus
    def cdf(k): return sum(math.comb(n,i) for i in range(k+1))/2**n
    p = min(1,2*cdf(min(plus,minus)+ties))
    k = max((k for k in range(1,n//2+1) if 2*cdf(k-1) <= .05),default=0)
    return dict(effect=math.exp(statistics.median(x)),rawPValue=p,
                low=math.exp(x[k-1]) if k else None,high=math.exp(x[n-k]) if k else None,
                intervalCoverage=1-2*cdf(k-1) if k else None,positive=plus,negative=minus,ties=ties)


def primary_results(plan, selected):
    result = []
    for d in plan['primary']['datasets']:
        for q in plan['primary']['queries']:
            observations = []
            for index in range(plan['primary']['replicates']):
                source = selected.get(f'primary-{index+1:02}')
                if source:
                    job,root,evidence = source
                    block = [r for r in evidence['queries'] if r['datasetName'] == d and r['queryLabel'] == q and r['phase'] == 'warm']
                    med = paired(block,job['pairs'])
                    observations.append(med)
            valid = len(observations) == plan['primary']['replicates']
            stats = sign_summary([v['reference']/v['local'] for v in observations]) if valid else {}
            result.append(dict(dataset=d,query=q,n=len(observations),status='OK' if valid else 'INCOMPLETE',
                               local=statistics.median(v['local'] for v in observations) if valid else None,
                               reference=statistics.median(v['reference'] for v in observations) if valid else None,
                               holmPValue=None,verdict='INCOMPLETE',**stats))
    order = sorted(range(len(result)),key=lambda i: result[i].get('rawPValue',1))
    previous = 0
    for position,index in enumerate(order):
        r = result[index]
        previous = min(1,max(previous,r.get('rawPValue',1)*(len(result)-position)))
        if r['status'] != 'OK': continue
        r['holmPValue'] = previous
        r['verdict'] = ('LOCAL_FASTER' if r['effect'] > 1 else 'REFERENCE_FASTER') if previous < .05 else 'INCONCLUSIVE'
    return result


def timing_summary(records, pairs):
    medians = paired(records, pairs)
    result = dict(pairs=pairs, **medians, effect=medians['reference']/medians['local'])
    for system in ('local', 'reference'):
        values = [float(r['seconds']) for r in records if r['system'] == system]
        quartiles = statistics.quantiles(values, n=4, method='inclusive') if len(values) > 1 else None
        result[system+'Q1'] = quartiles[0] if quartiles else None
        result[system+'Q3'] = quartiles[2] if quartiles else None
    return result


def descriptive_results(selected):
    result = []
    for job, root, evidence in selected.values():
        if job['phase'] not in ('coverage', 'imports', 'pilot'):
            continue
        if job['phase'] in ('coverage', 'pilot'):
            for dataset, query in cells(job, root):
                block = [r for r in evidence['queries'] if r['datasetName'] == dataset and
                         r['queryLabel'] == query and r['phase'] == 'warm']
                result.append(dict(metric='query', dataset=dataset, query=query,
                                   **timing_summary(block, job['pairs'])))
        for dataset in job['datasetNames']:
            imports = [r for r in evidence['imports'] if r['datasetName'] == dataset]
            result.append(dict(metric='import' if job['phase'] == 'imports' else 'setup',
                               dataset=dataset, query='import', **timing_summary(imports, job['importPairs'])))
    return result


def latency_diagnostics(selected):
    """Describe measured pairs only. Missing order evidence is not zero order effect."""
    result = []
    for job, root, evidence in selected.values():
        if job['kind'] not in ('latency', 'pilot'):
            continue
        order_checked = order_warnings = drift_checked = drift_warnings = 0
        blocks = cells(job, root)
        for dataset, query in blocks:
            pairs = defaultdict(list)
            for row in evidence['queries']:
                if (row['datasetName'], row['queryLabel'], row['phase']) == (dataset, query, 'warm'):
                    pairs[int(row['run'])].append(row)
            ordered = [pairs[n] for n in sorted(pairs)]

            def effect(groups):
                medians = {s: statistics.median(float(r['seconds']) for pair in groups for r in pair if r['system'] == s)
                           for s in ('local', 'reference')}
                return medians['reference']/medians['local']

            indices = [int(r.get('executionIndex') or 0) for pair in ordered for r in pair]
            if indices and min(indices) > 0 and len(set(indices)) == len(indices):
                subsets = {s: [pair for pair in ordered if min(pair, key=lambda r: int(r['executionIndex']))['system'] == s]
                           for s in ('local', 'reference')}
                if all(subsets.values()):
                    order_checked += 1
                    a, b = (effect(v) for v in subsets.values())
                    order_warnings += max(a, b)/min(a, b) > 1.1
            if len(ordered) >= 9:
                drift_checked += 1
                n = len(ordered)//3
                a, b = effect(ordered[:n]), effect(ordered[-n:])
                drift_warnings += max(a, b)/min(a, b) > 1.1
        result.append(dict(task=job['id'], blocks=len(blocks), orderChecked=order_checked,
                           orderWarnings=order_warnings, driftChecked=drift_checked, driftWarnings=drift_warnings))
    return result


def resource_results(selected):
    """One active-system total per planned window; imports retain explicit missing observations."""
    result = []
    for job, _, evidence in selected.values():
        if job['kind'] not in ('resources', 'pilot'):
            continue
        windows = [(w['dataset'], w['query'], 'queries', w['system'], w) for w in evidence['resourceWindows']]
        import_datasets = [job['resourceProbe']['dataset']] if job.get('resourceProbe') else job['datasetNames']
        windows += [(d, 'import', 'import-resource', s, {}) for d in import_datasets for s in ('local', 'reference')]
        for dataset, query, phase, system, window in windows:
            samples = evidence['memoryTotals'].get((dataset, query, phase, system), [])
            values = [value for _, value in samples]
            times = sorted(datetime.fromisoformat(t.replace('Z', '+00:00')) for t, _ in samples)
            gaps = [(b-a).total_seconds() for a, b in zip(times, times[1:])]
            result.append(dict(task=job['id'], dataset=dataset, query=query, phase=phase, system=system,
                               samples=len(values), medianBytes=statistics.median(values) if values else None,
                               peakBytes=max(values) if values else None,
                               medianGapSeconds=statistics.median(gaps) if gaps else None,
                               completedRequests=window.get('completedRequests'),
                               windowSeconds=(window['finishedNanos']-window['startedNanos'])/1e9 if window else None))
    return result
