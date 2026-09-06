"""Offline end-to-end study contract: simulated collector evidence, failure, recovery and publication."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
import study_contract as C
import study_report as R
import study_analysis as A
spec=importlib.util.spec_from_file_location('study_controller',Path(__file__).with_name('benchmark-study.py'))
S=importlib.util.module_from_spec(spec)
spec.loader.exec_module(S)


def csv(path,data):
    if data: R.write_csv(path,data)
    else: path.write_text('empty\n')


def fixture(job,root):
    """Contract fixture only: this does not simulate database performance or semantic correctness."""
    root.mkdir(parents=True)
    if job['kind']=='storage':
        name=job['datasetNames'][0]
        source=name.encode()
        import hashlib
        C.write_json(root/'storage-input.json',dict(datasetSha256=hashlib.sha256(source).hexdigest()))
        csv(root/'storage-scaling.csv',[dict(datasetName=name,system=s,measurementMode='isolated-fresh-stack',stackPreparationId=job['id'],
            stackPreparedAtUtc='test',gitCommit='a'*40,localAppImageId='processm-interpreter',localDbImageId='processm-neo4j',referenceImageId='processm-server',
            beforeBytes=10,afterBytes=30,deltaBytes=20,xesBytes=10,xesGzBytes=5,deltaToXesRatio=2,deltaToGzipRatio=4) for s in ('local','reference')])
        return
    C.write_json(root/'job.json',job)
    for n in ('benchmark-queries.json','benchmark-datasets.json'): (root/n).write_bytes((C.RESOURCES/n).read_bytes())
    env=dict(benchmarkProtocolVersion=27,source=dict(gitCommit='a'*40,gitDirty=False),
             host=dict(cpuModel='test fixture',logicalProcessors=1,totalPhysicalMemoryBytes=1000),dockerEngine=dict(version='test'),
             containers={n:dict(imageId=n,memoryLimitBytes=100,memorySwapLimitBytes=100,nanoCpus=0,effectiveJvmHeap=50,
                                jvmVersion='test',memoryConfigEnv={},running=True,oomKilled=False,restartCount=0) for n in C.CONTAINERS})
    env.update(warmups=job['warmups'],repetitions=job['pairs'],importRepetitions=job['importPairs'],globalWarmupRounds=job['globalWarmupRounds'],
               datasetOrderSeed=job['seed'],resourceWindowSeconds=job['resourceWindowSeconds'],resourceMinimumSamples=job['resourceMinimumSamples'])
    C.write_json(root/'environment.json',env)
    C.write_json(root/'environment-after.json',env)
    C.write_json(root/'environment-before.json',env)
    C.write_json(root/'stack-preparation.json',dict(freshVolumes=True,preparationId=job['id'],gitCommit='a'*40,
                 imageIds={n:n for n in C.CONTAINERS}))
    C.write_json(root/'cleanup.json',[dict(status='DELETED')])
    log=[dict(kind='global-warmup',data=dict(round=i,query=q,samples={s:dict(seconds=.001) for s in ('local','reference')}))
         for i in range(1,job['globalWarmupRounds']+1) for q in job['queryLabels']]
    data=[]
    ds,qs=C.definitions()
    by_name={d['name']:d for d in ds}
    (root/'generated-datasets').mkdir()
    for name in job['datasetNames']:
        f=root/'generated-datasets'/(name+'.xes.gz')
        f.write_bytes(name.encode())
        source=by_name[name]
        data.append(dict(datasetName=name,series=source['series'],fileSha256=C.digest(f),traces=source.get('traces',100),
                         totalEvents=source.get('traces',100)*source.get('eventsPerTrace',10),variantCount=source.get('variantCount',1),
                         activityCount=10,sourceDoi=source.get('sourceDoi','')))
    csv(root/'datasets.csv',data)
    csv(root/'queries.csv',[dict(queryLabel=q['label'],pql=q['query'],displayName=q['displayName'],role=q['role'].lower(),
                               measurementSeries=';'.join(q['measurementSeries'])) for q in qs if q['label'] in job['queryLabels']])
    csv(root/'import-results.csv',[dict(datasetName=d,system=s,run=i,status='OK',seconds=t) for d in job['datasetNames']
        for i in range(1,job['importPairs']+1) for s,t in (('local',.01),('reference',.02))])
    csv(root/'roundtrip-results.csv',[dict(datasetName=d,status='MATCH') for d in job['datasetNames']] if job['phase'] in ('coverage','pilot') else [])
    (root/'responses').mkdir()
    import hashlib
    body=b'[{"log":{}}]'
    response='responses/'+hashlib.sha256(body).hexdigest()+'.json'
    (root/response).write_bytes(body)
    raw=[]
    queries_by_label = {q['label']:q for q in qs}
    for d,q in C.cells(job):
        expected = queries_by_label[q].get('expectedResponses', {}).get(d)
        counts = dict(logCount=1, traceCount=1, eventCount=1)
        current_response = response
        if expected:
            counts = dict(zip(('logCount','traceCount','eventCount'), (expected[k] for k in ('logs','traces','events'))))
            log_body = {'trace': [{'event': [{} for _ in range(expected['events'] // expected['traces'])]}
                                  for _ in range(expected['traces'])]}
            for key, attribute in expected.get('logAttributes', {}).items():
                log_body.setdefault(attribute['type'], []).append({'@key':key, '@value':attribute['value']})
            for key, attribute in expected.get('traceAttributes', {}).items():
                log_body['trace'][0].setdefault(attribute['type'], []).append({'@key':key, '@value':attribute['value']})
            controlled_body = json.dumps([{'log':log_body}], sort_keys=True).encode()
            current_response = 'responses/'+hashlib.sha256(controlled_body).hexdigest()+'.json'
            (root/current_response).write_bytes(controlled_body)
        for phase,n in [('warmup',job['warmups']),('warm',job['pairs'] if job['kind'] in ('latency','pilot') else 0)]:
            for i in range(1,n+1):
                for s,t in (('local',.01),('reference',.02)):
                    raw.append(dict(datasetName=d,queryLabel=q,system=s,run=i,phase=phase,status='OK',seconds=t,
                                    responsePath=current_response if phase=='warm' else '',**counts,
                                    executionIndex=(i-1)*2 + (1 if s == ('local' if i % 2 else 'reference') else 2)))
    csv(root/'query-results.csv',raw)
    memory,io,summary=[],[],[]
    if job['kind'] in ('resources','pilot'):
        for d,q in C.resource_cells(job):
            for s in ('local','reference'):
                log.append(dict(kind='resource-window-completed',data=dict(dataset=d,query=q,system=s,samples=3,completedRequests=5,
                     startedNanos=100,finishedNanos=100+job['resourceWindowSeconds']*10**9,status='OK')))
                components=['processm-interpreter','processm-neo4j'] if s=='local' else ['processm-server']
                # Docker samples all components, including the system that is currently inactive.
                for c in sorted(C.CONTAINERS):
                    for i in range(3): memory.append(dict(datasetName=d,operationLabel=q,phase='queries',activeSystem=s,component=c,
                        timestamp=f'2026-01-01T00:00:0{i}Z',bytes=100 if c in components else 9000,withinWindow='true'))
                for c in components:
                    io.append(dict(datasetName=d,operationLabel=q,phase='query',system=s,component=c,blockReadBytes=1,blockWriteBytes=2,
                         networkReceiveBytes=10,networkTransmitBytes=10,status='OK',completedOperations=5,windowStartedNanos=100,
                         windowFinishedNanos=100+job['resourceWindowSeconds']*10**9))
                summary.append(dict(datasetName=d,operationLabel=q,component=s+'-total',activeSystem=s,phase='queries',sampleCount=3,medianBytes=100,peakBytes=100))
    csv(root/'memory-results.csv',memory)
    csv(root/'memory-summary.csv',summary)
    csv(root/'container-io.csv',io)
    if job['kind']=='compatibility':
        from compatibility_query_set import thesis_queries,multi_log_compatibility_queries
        cases=[(n,q) for n in ('Hospital_log','JournalReview','Sepsis','teleclaims') for q in thesis_queries()]
        cases += [('JournalReview + Sepsis',q) for q in multi_log_compatibility_queries()]
        target=root/'compatibility/test'
        target.mkdir(parents=True)
        csv(target/'results.csv',[dict(Log=n,Query=q.label,Status='MATCH',LocalSuccess='true',RemoteSuccess='true') for n,q in cases])
    log.append(dict(kind='run-ended',data=dict(status='COMPLETED')))
    (root/'execution.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in log))


class StudyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.plan=C.read_json(C.RESOURCES/'study-plan.json')

    def failed_archive(self, mode):
        task = 'pilot' if mode == 'pilot' else 'compatibility'
        snapshot = self.root/'tasks'/task/'attempt-01/result'
        snapshot.mkdir(parents=True)
        for name in ('benchmark-datasets.json', 'benchmark-queries.json'):
            (snapshot/name).write_bytes((C.RESOURCES/name).read_bytes())
        queries = C.read_json(snapshot/'benchmark-queries.json')
        queries[0]['displayName'] = 'Archived operation name'
        C.write_json(snapshot/'benchmark-queries.json', queries)
        plan = deepcopy(self.plan)
        plan['queryDefinitionsSha256'] = C.digest(snapshot/'benchmark-queries.json')
        if mode == 'final':
            plan['status'] = 'frozen'
            plan['pilotEvidence'] = dict(estimatedSeconds=1, manifestSha256='a'*64,
                                         designSha256=C.design_identity(plan))
        state = dict(mode=mode, planSha256=C.identity(plan), elapsedSeconds=1,
                     tasks={task: [dict(status='FAILED', directory=str(snapshot.parent.relative_to(self.root)),
                                        error='Interrupted before completing the task')]})
        # A report must use the definition snapshot without trying to accept incomplete samples.
        (snapshot/'query-results.csv').write_text('incomplete sample data\n')
        C.write_json(self.root/'plan.json', plan)
        C.write_json(self.root/'state.json', state)
        return snapshot, plan, state

    def test_failed_pilot_report_uses_recorded_definitions_without_current_resources(self):
        snapshot, plan, state = self.failed_archive('pilot')
        before = C.file_manifest(snapshot)
        with patch.object(C, 'RESOURCES', self.root/'unavailable-current-draft'), \
                patch.object(R, 'RESOURCES', self.root/'unavailable-current-draft'):
            S.review_campaign(self.root, 'report')
        report = next((self.root/'reports').iterdir())
        self.assertIn('Pilotaż niekompletny', (report/'benchmark-report.md').read_text())
        provenance = C.read_json(report/'report-provenance.json')
        self.assertFalse(provenance['complete'])
        self.assertEqual({}, provenance['evidence'])
        self.assertEqual(C.identity(plan), provenance['planSha256'])
        self.assertEqual(before, C.file_manifest(snapshot))

    def test_failed_final_report_renders_recorded_definitions_without_current_resources(self):
        snapshot, _, _ = self.failed_archive('final')
        before = C.file_manifest(snapshot)
        with patch.object(C, 'RESOURCES', self.root/'unavailable-current-draft'), \
                patch.object(R, 'RESOURCES', self.root/'unavailable-current-draft'):
            S.review_campaign(self.root, 'report')
        report = next((self.root/'reports').iterdir())
        self.assertIn('Archived operation name', (report/'benchmark-report.md').read_text())
        self.assertTrue(all(r['n'] == '0' and r['status'] == 'INCOMPLETE'
                            for r in C.rows(report/'primary-comparisons.csv')))
        provenance = C.read_json(report/'report-provenance.json')
        self.assertFalse(provenance['complete'])
        self.assertEqual({}, provenance['evidence'])
        self.assertEqual(before, C.file_manifest(snapshot))

    def test_archive_rejects_changed_snapshot_and_different_current_definitions(self):
        snapshot, plan, state = self.failed_archive('pilot')
        (snapshot/'benchmark-datasets.json').write_text('{"full": []}')
        with self.assertRaisesRegex(C.ScriptError, 'No definition snapshot matches'):
            C.campaign_resources(self.root, plan, state)

    def test_controlled_axes_replace_intermediate_points_without_reducing_pairs(self):
        C.validate_plan(self.plan)
        inv=C.inventory(self.plan)
        self.assertEqual(168,inv['blocksByPhase']['coverage'])
        self.assertEqual(55,inv['blocksByPhase']['resources'])
        self.assertEqual(1100,inv['resourceMinimumSeconds'])
        self.assertEqual(6,inv['primaryTests'])
        self.assertEqual(12,inv['primaryPreparations'])
        self.assertEqual(36960,inv['latencyRequestsWithWarmup'])
        self.assertEqual(28,inv['datasetCount'])
        self.assertEqual(41,inv['jobs'])
        self.assertEqual(6,inv['coverageBlocksBySeries']['selectivity-scaling'])
        self.assertEqual(6,inv['coverageBlocksBySeries']['trace-length-scaling'])
        jobs=C.schedule(self.plan)
        self.assertTrue(all(j['pairs']==30 for j in jobs if j['kind']=='latency'))
        self.assertTrue(all(j['resourceWindowSeconds']==10 for j in jobs if j['kind']=='resources'))
        self.assertEqual(35,sum(j['importPairs']*len(j['datasetNames']) for j in jobs if j['kind']=='imports'))
        p=C.schedule(self.plan,True)[0]
        self.assertEqual(10,len(p['datasetNames']))
        self.assertEqual(2,p['pairs'])
        self.assertNotEqual(self.plan['latency']['pairs'],p['pairs'])
        with self.assertRaises(C.ScriptError): C.validate_plan(self.plan,True)

    def test_sparse_matrix_and_pilot_cover_every_query_series(self):
        jobs=C.schedule(self.plan)
        coverage=[cell for j in jobs if j['phase']=='coverage' for cell in C.cells(j)]
        self.assertEqual(len(coverage),len(set(coverage)))
        self.assertEqual({'size-1k','size-100k','size-1m'},
                         {d for d,q in coverage if q=='genericVariantGroup'})
        self.assertEqual(7,len({d for d,q in coverage if q=='eventEquality'}))
        self.assertEqual({'variants-1'}, {d for d,q in coverage if q.startswith('responseWindow')})
        changed=deepcopy(self.plan)
        changed['pilot']['datasets'].remove('variants-1')
        with self.assertRaisesRegex(C.ScriptError,'Pilot omits query/series'):
            C.validate_plan(changed)

    def test_short_pilot_keeps_coverage_but_only_one_resource_probe(self):
        job = C.schedule(self.plan, True)[0]
        self.assertEqual((1, 2, 2), (job['warmups'], job['pairs'], job['globalWarmupRounds']))
        self.assertEqual(47, len(C.cells(job)))
        self.assertEqual([('size-1k', 'hierarchyWindow')], C.resource_cells(job))
        self.assertFalse(any(d.startswith('selectivity-1m-') for d, _ in C.cells(job)))
        out = self.root/'pilot-probe'
        fixture(job, out)
        evidence = C.validate_job(job, out)
        self.assertEqual(2, len(evidence['resourceWindows']))
        selected = {'pilot': (job, out, evidence)}
        self.assertTrue(all(r['dataset'] == 'size-1k' for r in A.resource_results(selected)))
        text, primary, _ = R.pilot_report(self.plan, selected)
        self.assertEqual([], primary)
        self.assertIn('Nie wchodzą do końcowych wyników', text)
        self.assertIn('nie potwierdza stabilności', text)
        self.assertNotIn('Poprzednie 10 [ms]', text)
        self.assertTrue(all(r['n'] == 0 for r in A.primary_results(self.plan, selected)))
        log = C.events(out)
        log = [e for e in log if not (e['kind'] == 'resource-window-completed' and e['data']['system'] == 'reference')]
        (out/'execution.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in log))
        with self.assertRaisesRegex(C.ScriptError, 'Incomplete resource windows'):
            C.validate_job(job, out)
        changed = deepcopy(self.plan)
        changed['pilot']['resourceProbe']['dataset'] = 'selectivity-100k-1pct'
        with self.assertRaisesRegex(C.ScriptError, 'resource probe must belong'):
            C.validate_plan(changed)

    def test_selectivity_report_scopes_conclusion_to_recorded_sizes(self):
        datasets, queries = C.definitions()
        text = '\n'.join(R.controlled_sections([], datasets, queries, {}, {}))
        self.assertIn('Zaplanowane rozmiary logu: 100 000 zdarzeń.', text)
        self.assertIn('nie określa, jak zmienia się on ze skalą danych', text)

    def test_live_pilot_requires_its_probe_before_any_process_starts(self):
        plan = deepcopy(self.plan)
        plan['pilot'].pop('resourceProbe')
        path = self.root/'missing-probe.json'
        C.write_json(path, plan)
        with patch.object(sys, 'argv', ['benchmark-study.py', 'pilot', '--plan', str(path), '--out', str(self.root),
                                      '--execute', '--confirm-destroy-volumes']), patch.object(S, 'run_capture') as process:
            with self.assertRaisesRegex(C.ScriptError, 'explicit resource probe'):
                S.main()
            process.assert_not_called()

    def test_both_systems_agreeing_on_wrong_aggregate_cannot_complete_a_block(self):
        job=next(j for j in C.schedule(self.plan) if j['id']=='coverage-selectivity-scaling')
        out=self.root/'controlled'
        fixture(job,out)
        C.validate_job(job,out)
        raw=C.rows(out/'query-results.csv')
        target=next(r for r in raw if r['phase']=='warm')
        original=target['responsePath']
        body=C.read_json(out/original)
        body[0]['log']['trace'][0]['int'][0]['@value']='0'
        encoded=json.dumps(body).encode()
        import hashlib
        forged='responses/'+hashlib.sha256(encoded).hexdigest()+'.json'
        (out/forged).write_bytes(encoded)
        for r in raw:
            if r['responsePath']==original: r['responsePath']=forged
        csv(out/'query-results.csv',raw)
        with self.assertRaisesRegex(C.ScriptError,'expected trace aggregate'):
            C.validate_job(job,out)

    def test_final_pair_count_cannot_be_reduced_in_plan_or_job(self):
        for count in (20, 28, 32):
            changed = deepcopy(self.plan)
            changed['latency']['pairs'] = count
            with self.assertRaisesRegex(C.ScriptError, 'exactly 30 pairs'):
                C.schedule(changed)
            changed.update(status='frozen', pilotEvidence=dict(estimatedSeconds=1, manifestSha256='a'*64,
                                                             designSha256=C.design_identity(changed)))
            with self.assertRaisesRegex(C.ScriptError, 'exactly 30 pairs'):
                C.validate_plan(changed, True)
        job = next(j for j in C.schedule(self.plan) if j['id'] == 'coverage-real-sepsis')
        job = dict(job, pairs=20)
        out = self.root/'shortened'
        fixture(job, out)
        with self.assertRaisesRegex(C.ScriptError, 'exactly 30 pairs'):
            C.validate_job(job, out)
        plan_path = self.root/'shortened-plan.json'
        C.write_json(plan_path, changed)
        with patch.object(sys, 'argv', ['benchmark-study.py', 'run', '--plan', str(plan_path),
                                      '--out', str(self.root), '--execute', '--confirm-destroy-volumes']), \
             patch.object(S, 'run_capture') as process, patch.object(S, 'live_runner') as live:
            with self.assertRaisesRegex(C.ScriptError, 'exactly 30 pairs'):
                S.main()
            process.assert_not_called()
            live.assert_not_called()

    def test_twenty_recorded_pairs_cannot_complete_a_thirty_pair_block(self):
        job = next(j for j in C.schedule(self.plan) if j['id'] == 'coverage-real-sepsis')
        out = self.root/'truncated'
        fixture(job, out)
        C.validate_job(job, out)
        raw = C.rows(out/'query-results.csv')
        csv(out/'query-results.csv', [r for r in raw if r['phase'] != 'warm' or int(r['run']) <= 20])
        with self.assertRaisesRegex(C.ScriptError, 'Missing or duplicated paired observation'):
            C.validate_job(job, out)

    def test_exact_test_resolution_and_holm_family(self):
        effect=A.sign_summary([2]*11+[.5])
        self.assertEqual(26/4096,effect['rawPValue'])
        self.assertLess(6*effect['rawPValue'],.05)
        self.assertGreaterEqual(effect['intervalCoverage'],.95)
        self.assertEqual(1,A.sign_summary([1]*12)['rawPValue'])
        self.assertEqual(1,A.sign_summary([2]*6+[.5]*6)['effect'])

    def test_complete_campaign_report_is_reproducible_and_missing_task_stays_visible(self):
        self.plan.update(status='frozen',pilotEvidence=dict(estimatedSeconds=1,manifestSha256='a'*64,designSha256=C.design_identity(self.plan)))
        jobs=C.schedule(self.plan)
        state=dict(planSha256=C.identity(self.plan),mode='final',elapsedSeconds=10,tasks={})
        for job in jobs:
            out=self.root/job['id']/'result'
            fixture(job,out)
            state['tasks'][job['id']]=[dict(status='COMPLETED',directory=job['id'],manifest=C.file_manifest(out))]
        C.write_json(self.root/'state.json',state)
        selected=S.completed(self.root,state,jobs)
        self.assertEqual(len(jobs),len(selected))
        report,primary,_=R.build(self.plan,state,jobs,selected)
        self.assertEqual(6,len(primary))
        self.assertEqual({'LOCAL_FASTER'},{r['verdict'] for r in primary})
        self.assertEqual({12},{r['n'] for r in primary})
        self.assertEqual(report,R.build(self.plan,state,jobs,selected)[0])
        self.assertIn('oraz 30 par mierzonych', report)
        self.assertNotIn('pilotaż', report.partition('## Cel badania')[0].lower())
        self.assertIn('## Jak czytać wyniki', report)
        imports = report.partition('## Import, pamięć i trwały rozmiar')[2].partition('## Zgodność')[0]
        self.assertIn('L [s]', imports)
        self.assertNotIn('L [ms]', imports)
        publication=R.publish(self.root,self.plan,state,jobs,selected)
        self.assertTrue((publication/'benchmark-report.html').is_file())
        self.assertTrue((publication/'thesis-primary-results.tex').is_file())
        self.assertTrue(C.read_json(publication/'report-provenance.json')['complete'])
        selected.pop('primary-12')
        partial,primary,_=R.build(self.plan,state,jobs,selected)
        self.assertEqual({'INCOMPLETE'},{r['verdict'] for r in primary})
        self.assertIn('Nie ma jeszcze kompletnej kampanii',partial)
        self.assertNotIn('Hipoteza uzyskała potwierdzenie we wszystkich',partial)

    def test_missing_raw_resource_and_foreign_storage_cannot_be_accepted(self):
        job=next(j for j in C.schedule(self.plan) if j['id']=='resources-real-sepsis')
        out=self.root/'resource'
        fixture(job,out)
        C.validate_job(job,out)
        data=C.rows(out/'memory-results.csv')
        csv(out/'memory-results.csv',data[1:])
        with self.assertRaisesRegex(C.ScriptError,'memory coverage'): C.validate_job(job,out)
        jobs=[j for j in C.schedule(self.plan) if j['id'] in ('coverage-size','storage-size-1k')]
        state=dict(tasks={})
        for j in jobs:
            target=self.root/j['id']/'result'
            fixture(j,target)
            if j['kind']=='storage':
                C.write_json(target/'storage-input.json',dict(datasetSha256='foreign input'))
            state['tasks'][j['id']]=[dict(status='COMPLETED',directory=j['id'],manifest=C.file_manifest(target))]
        with self.assertRaisesRegex(C.ScriptError,'different dataset bytes'): S.completed(self.root,state,jobs)

    def test_missing_warmup_and_changed_response_fail(self):
        job=C.schedule(self.plan,True)[0]
        out=self.root/'pilot'
        fixture(job,out)
        C.validate_job(job,out)
        response=next((out/'responses').iterdir())
        original=response.read_bytes()
        response.write_text('[]')
        with self.assertRaisesRegex(C.ScriptError,'semantic response'): C.validate_job(job,out)
        response.write_bytes(original)
        log=(out/'execution.jsonl').read_text().splitlines()
        (out/'execution.jsonl').write_text('\n'.join(log[1:])+'\n')
        with self.assertRaisesRegex(C.ScriptError,'global warmup'): C.validate_job(job,out)

    def test_resume_retains_completed_task_and_requires_recorded_reason(self):
        jobs=C.schedule(self.plan)[:2]
        calls=[]
        def runner(job,directory,*unused):
            calls.append(job['id'])
            if len(calls)==2: raise C.ScriptError('simulated controller failure')
            fixture(job,directory/'result')
        with patch.object(S,'schedule',return_value=jobs):
            with self.assertRaisesRegex(C.ScriptError,'simulated'): S.run_tasks(self.plan,self.root,runner)
            with self.assertRaisesRegex(C.ScriptError,'retry-failed'): S.run_tasks(self.plan,self.root,runner)
            S.run_tasks(self.plan,self.root,runner,retry_reason='simulated network interruption resolved')
        self.assertEqual([jobs[0]['id'],jobs[1]['id'],jobs[1]['id']],calls)
        state=C.read_json(self.root/'state.json')
        self.assertEqual(1,len(state['tasks'][jobs[0]['id']]))
        self.assertEqual(['FAILED','COMPLETED'],[a['status'] for a in state['tasks'][jobs[1]['id']]])
        result=self.root/state['tasks'][jobs[0]['id']][0]['directory']/'result'
        (result/'job.json').write_text('{}')
        with self.assertRaisesRegex(C.ScriptError,'evidence has changed'): S.completed(self.root,state,jobs)

    def test_budget_is_not_reset_on_resume(self):
        self.plan['budgetSeconds'] = 14400
        C.write_json(self.root/'plan.json',self.plan)
        C.write_json(self.root/'state.json',dict(planSha256=C.identity(self.plan),mode='final',elapsedSeconds=self.plan['budgetSeconds'],tasks={}))
        with self.assertRaisesRegex(C.ScriptError,'budget exhausted'):
            S.run_tasks(self.plan,self.root,lambda *args:self.fail('A command must not start'))

    def test_pilot_budget_is_separate_and_survives_resume(self):
        self.assertIsNone(self.plan['budgetSeconds'])
        self.assertEqual(1800, self.plan['pilot']['budgetSeconds'])
        def runner(job, directory, remaining, heartbeat, selected):
            self.assertEqual(1800, remaining)
            fixture(job, directory/'result')
        state = S.run_tasks(self.plan, self.root, runner, pilot=True)
        self.assertEqual('COMPLETED', state['tasks']['pilot'][0]['status'])
        self.assertEqual(1800, C.read_json(self.root/'progress.json')['budgetSeconds'])
        interrupted = self.root/'expired-pilot'
        interrupted.mkdir()
        C.write_json(interrupted/'state.json', dict(planSha256=C.identity(self.plan), mode='pilot', elapsedSeconds=1800, tasks={}))
        with self.assertRaisesRegex(C.ScriptError, 'budget exhausted'):
            S.run_tasks(self.plan, interrupted, lambda *args:self.fail('No new process after the pilot deadline'), pilot=True)
        for invalid in (-1, 0, True, float('inf')):
            changed = deepcopy(self.plan)
            changed['pilot']['budgetSeconds'] = invalid
            with self.assertRaisesRegex(C.ScriptError, 'Pilot budget'):
                C.validate_plan(changed)

    def test_unlimited_campaign_continues_after_four_hours_and_records_terminal_progress(self):
        self.plan['budgetSeconds'] = None
        C.write_json(self.root/'state.json',dict(planSha256=C.identity(self.plan),mode='final',elapsedSeconds=18000,tasks={}))
        job = C.schedule(self.plan)[0]
        def runner(job, directory, remaining, heartbeat, selected):
            self.assertIsNone(remaining)
            fixture(job, directory/'result')
        with patch.object(S,'schedule',return_value=[job]):
            state = S.run_tasks(self.plan,self.root,runner)
        progress = C.read_json(self.root/'progress.json')
        self.assertGreaterEqual(state['elapsedSeconds'], 18000)
        self.assertEqual('COMPLETED', progress['status'])
        self.assertEqual(job['id'], progress['taskId'])

    def test_unlimited_plan_still_requires_a_finite_pilot_forecast(self):
        frozen = dict(self.plan, budgetSeconds=None, status='frozen')
        frozen['pilotEvidence'] = dict(estimatedSeconds=90000,manifestSha256='a'*64,designSha256=C.design_identity(frozen))
        C.validate_plan(frozen, True)
        for value in (None, float('inf'), float('nan'), 0):
            frozen['pilotEvidence']['estimatedSeconds'] = value
            with self.assertRaisesRegex(C.ScriptError, 'finite pilot forecast'): C.validate_plan(frozen, True)

    def test_publication_failure_is_visible_after_successful_collection(self):
        C.write_json(self.root/'progress.json',dict(status='COMPLETED',elapsedSeconds=30))
        with patch('study_report.publish',side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'):
                S.publish_campaign(self.root,self.plan,dict(mode='final'),[],{})
        progress = C.read_json(self.root/'progress.json')
        self.assertEqual('FAILED', progress['status'])
        self.assertEqual('publishing', progress['phase'])
        self.assertEqual('disk full', progress['error'])
        with patch('study_report.publish',return_value=self.root):
            S.publish_campaign(self.root,self.plan,dict(mode='final'),[],{})
        progress = C.read_json(self.root/'progress.json')
        self.assertEqual('COMPLETED', progress['status'])
        self.assertNotIn('error', progress)

    def test_unlimited_campaign_keeps_individual_command_timeout(self):
        with self.assertRaisesRegex(C.ScriptError, 'Command timeout'):
            S.command([sys.executable,'-c','import time; time.sleep(60)'],
                      self.root/'build.log',None,lambda **unused:None,timeout_seconds=0)

    def test_live_cli_is_opt_in_and_plan_does_not_spawn_processes(self):
        with patch.object(sys,'argv',['benchmark-study.py','run','--out',str(self.root)]),patch.object(S,'live_runner') as live:
            with self.assertRaisesRegex(C.ScriptError,'pilot'): S.main()
            live.assert_not_called()
        plan=self.root/'frozen.json'
        C.write_json(plan,dict(self.plan,status='frozen',pilotEvidence=dict(estimatedSeconds=1,manifestSha256='a'*64,designSha256=C.design_identity(self.plan))))
        with patch.object(sys,'argv',['benchmark-study.py','run','--plan',str(plan),'--out',str(self.root)]),patch.object(S,'run_capture') as process:
            with self.assertRaisesRegex(C.ScriptError,'both --execute'): S.main()
            process.assert_not_called()
        with patch.object(sys,'argv',['benchmark-study.py','plan']),patch.object(S,'run_capture') as process:
            S.main()
            process.assert_not_called()

    def test_semantic_failure_is_not_retried_until_match(self):
        job=C.schedule(self.plan)[0]
        def failure(job,directory,*unused):
            root=directory/'result'
            root.mkdir()
            (root/'execution.jsonl').write_text(json.dumps(dict(kind='failure',data=dict(retryable=False)))+'\n')
            raise C.ScriptError('semantic mismatch')
        with patch.object(S,'schedule',return_value=[job]):
            with self.assertRaisesRegex(C.ScriptError,'semantic mismatch'): S.run_tasks(self.plan,self.root,failure)
            with self.assertRaisesRegex(C.ScriptError,'semantic/control failure'):
                S.run_tasks(self.plan,self.root,lambda *unused:self.fail('Must not retry'),retry_reason='try again')

    def test_final_environment_must_match_the_budgeted_pilot(self):
        job=C.schedule(self.plan)[0]
        fixture(job,self.root/'pilot')
        env=C.read_json(self.root/'pilot/environment.json')
        env['host']['logicalProcessors']=2
        with patch.object(S,'schedule',return_value=[job]):
            with self.assertRaisesRegex(C.ScriptError,'differs from the budgeted pilot'):
                S.run_tasks(self.plan,self.root,lambda j,d,*unused:fixture(j,d/'result'),pilot_environment=env)

    def test_pilot_forecast_is_bound_to_candidate_settings(self):
        job=C.schedule(self.plan,True)[0]
        out=self.root/'pilot/result'
        fixture(job,out)
        C.write_json(out/'timing.json',dict(preparationSeconds=10,reusePreparationSeconds=5))
        C.write_json(self.root/'plan.json',self.plan)
        C.write_json(self.root/'state.json',dict(mode='pilot',tasks={'pilot':[dict(status='COMPLETED',directory='pilot',manifest=C.file_manifest(out))]}))
        forecast=S.forecast(self.plan,self.root)
        self.assertGreater(forecast['estimatedSeconds'],1020)
        changed=deepcopy(self.plan)
        changed['latency']['pairs']=22
        with self.assertRaisesRegex(C.ScriptError,'settings differ'): S.forecast(changed,self.root)

    def test_forecast_separates_warmup_cost_and_does_not_mix_dataset_series(self):
        # Unequal synthetic warmup durations exercise arithmetic-mean extrapolation.
        self.plan['pilot']['warmups'] = 40
        job=C.schedule(self.plan,True)[0]
        out=self.root/'pilot/result'
        fixture(job,out)
        raw=C.rows(out/'query-results.csv')
        for r in raw:
            if r['system']!='reference' or r['queryLabel']!='variantGroupCount': continue
            if r['datasetName']=='size-1m': r['seconds']='100'
            if r['datasetName']=='variants-2000':
                r['seconds']=str((4 if r['run']=='1' else .4) if r['phase']=='warmup' else .2)
        csv(out/'query-results.csv',raw)
        C.write_json(out/'timing.json',dict(preparationSeconds=10,reusePreparationSeconds=5))
        C.write_json(self.root/'plan.json',self.plan)
        C.write_json(self.root/'state.json',dict(mode='pilot',tasks={'pilot':[
            dict(status='COMPLETED',directory='pilot',manifest=C.file_manifest(out))]}))
        result=S.forecast(self.plan,self.root)
        task=next(t for t in result['tasks'] if t['task']=='coverage-variants')
        # Two variant points observed, the middle inherits only its own series' slower point.
        self.assertAlmostEqual((.03+.21+.21+8*.03)*30*1.25,task['componentSeconds']['measuredQueries'])
        self.assertAlmostEqual((.03+.50+.50+8*.03)*40*1.25,task['componentSeconds']['blockWarmup'])
        self.assertAlmostEqual(result['estimatedSeconds'],sum(result['componentSeconds'].values()))
        self.assertAlmostEqual(result['estimatedSeconds'],sum(t['estimatedSeconds'] for t in result['tasks']))

    def test_response_control_rejects_wrong_shape_duplicate_and_mistyped_values(self):
        expected=dict(logs=1,traces=1,events=0,logAttributes={'count':dict(type='int',value='10')})
        correct=[{'log':{'trace':{},'int':{'@key':'count','@value':'10'}}}]
        C.response_control(expected,correct)
        for bad in ([],[{'log':{'trace':{},'string':{'@key':'count','@value':'10'}}}],
                    [{'log':{'trace':{},'int':[{'@key':'count','@value':'10'}]*2}}]):
            with self.assertRaises(C.ScriptError): C.response_control(expected,bad)

    def test_raw_xes_json_preserves_separated_attribute_runs_and_rejects_ambiguous_fields(self):
        path = self.root/'raw-response.json'
        path.write_text('[{"log":{"trace":{},"float":{"@key":"avg","@value":"3.5"},'
                        '"int":{"@key":"count","@value":"6"},'
                        '"float":[{"@key":"sum","@value":"21.0"}]}}]')
        body = C.read_xes_json(path)
        self.assertEqual(['avg', 'sum'], [a['@key'] for a in body[0]['log']['float']])
        C.response_control(dict(logs=1,traces=1,events=0,logAttributes={
            'avg':dict(type='float',value='3.5'), 'sum':dict(type='float',value='21.0')}), body)

        # Repeated child fields must not hide duplicate semantic attributes.
        path.write_text('[{"log":{"trace":{},"int":{"@key":"count","@value":"999"},'
                        '"int":{"@key":"count","@value":"6"}}}]')
        with self.assertRaises(C.ScriptError):
            C.response_control(dict(logs=1,traces=1,events=0,logAttributes={
                'count':dict(type='int',value='6')}), C.read_xes_json(path))

        path.write_text('[{"log":{"int":{"@key":"wrong","@key":"count","@value":"6"}}}]')
        with self.assertRaises(C.ScriptError): C.read_xes_json(path)

        path.write_text('[{"log":{"trace":{"event":null,"event":[null,{}]},"trace":{}}}]')
        body = C.read_xes_json(path)
        self.assertEqual(2, len(body[0]['log']['trace']))
        self.assertEqual([None,None,{}], body[0]['log']['trace'][0]['event'])

    def test_selectivity_control_rejects_agreed_wrong_total_and_wrong_aggregate_scope(self):
        _, queries = C.definitions()
        queries = [q for q in queries if q['label'] in ('selectivityEventFilter', 'selectivityTraceFilter')]
        self.assertEqual(2, len(queries))
        correct = [{'log': {'trace': {'int': {'@key': 'count(trace:concept:name)', '@value': '100'}}}}]
        for query in queries:
            expected = query['expectedResponses']['selectivity-100k-1pct']
            C.response_control(expected, correct)
            bad = deepcopy(correct)
            bad[0]['log']['trace']['int']['@value'] = '10000'
            for system in ('local', 'reference'):
                with self.subTest(query=query['label'], system=system), self.assertRaises(C.ScriptError):
                    C.response_control(expected, bad)
            bad_scope = [{'log': {'trace': {}, 'int': {'@key': 'count(trace:concept:name)', '@value': '100'}}}]
            with self.assertRaises(C.ScriptError): C.response_control(expected, bad_scope)
            duplicate = deepcopy(correct)
            duplicate[0]['log']['trace']['int'] = [correct[0]['log']['trace']['int']] * 2
            with self.assertRaises(C.ScriptError): C.response_control(expected, duplicate)
            mistyped = deepcopy(correct)
            mistyped[0]['log']['trace']['string'] = mistyped[0]['log']['trace'].pop('int')
            with self.assertRaises(C.ScriptError): C.response_control(expected, mistyped)
            multiple = deepcopy(correct)
            multiple[0]['log']['trace'] = [correct[0]['log']['trace']] * 2
            with self.assertRaises(C.ScriptError): C.response_control(expected, multiple)


if __name__=='__main__': unittest.main()
