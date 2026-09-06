"""Operational status page; its heartbeat is not evidence of completed measurements."""
from datetime import datetime, timezone
from html import escape
import json
from pathlib import Path
import tempfile


def _duration(value):
    if value is None:
        return '—'
    seconds = max(0, int(float(value)))
    return f'{seconds // 3600} h {(seconds % 3600) // 60:02} min {seconds % 60:02} s'


def _age(timestamp, now):
    if not timestamp:
        return None
    try:
        moment = datetime.fromisoformat(str(timestamp).replace('Z', '+00:00'))
        return (now - moment).total_seconds()
    except (ValueError, TypeError):
        return None


def _atomic(path, content):
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                     prefix=path.name + '.', suffix='.tmp', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(content)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def write_progress(root, snapshot):
    """Atomically replace the JSON snapshot and an escaped, self-contained HTML view."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc)
    collector = snapshot.get('collector') or {}
    current = collector.get('current') or {}
    completed = collector.get('lastCompleted') or {}

    def safe(value):
        return escape('—' if value is None else str(value), quote=True)

    def position(value, total):
        return f"{value if value is not None else '—'} / {total if total is not None else '—'}"

    def table(items):
        return '<table>' + ''.join(f'<tr><th>{safe(k)}</th><td>{safe(v)}</td></tr>'
                                  for k, v in items) + '</table>'

    controller_rows = [
        ('Stan kontrolera', snapshot.get('status')),
        ('Zadanie', snapshot.get('taskId')),
        ('Postęp zadań', position(snapshot.get('taskIndex'), snapshot.get('taskCount'))),
        ('Próba', snapshot.get('attempt')), ('Etap', snapshot.get('phase')),
        ('Początek etapu', snapshot.get('phaseStartedAt')),
        ('Czas bieżącego etapu', _duration(_age(snapshot.get('phaseStartedAt'), now))),
        ('Czas kampanii', _duration(snapshot.get('elapsedSeconds'))),
        ('Limit kampanii', _duration(snapshot['budgetSeconds']) if snapshot.get('budgetSeconds') else 'bez limitu'),
        ('Ostatni sygnał kontrolera', snapshot.get('updatedAt')),
        ('PID procesu etapu', snapshot.get('processId')), ('Dziennik etapu', snapshot.get('activeLog')),
    ]
    if snapshot.get('lastOutputAt') is not None or snapshot.get('lastOutputAgeSeconds') is not None:
        controller_rows += [('Ostatni zapis dziennika', snapshot.get('lastOutputAt')),
                            ('Czas od zapisu dziennika', _duration(snapshot.get('lastOutputAgeSeconds')))]
    operation = table([
        ('Etap operacji', current.get('phase')), ('Zbiór', current.get('dataset')),
        ('Zapytanie', current.get('query')), ('System', current.get('system')),
        ('Powtórzenie', position(current.get('repetition'), current.get('total'))),
        ('Początek operacji', current.get('startedAt')),
        ('Czas bieżącej operacji', _duration(_age(current.get('startedAt'), now))),
    ]) if current else '<p>Brak aktywnej operacji kolektora. Stan wykonania wskazuje kontroler powyżej.</p>'
    work = table([
        ('Stan kolektora', collector.get('status')),
        ('Ukończone operacje w zadaniu', collector.get('completedOperations')),
        ('Ostatnia ukończona operacja', completed.get('phase')),
        ('Zakończenie ostatniej operacji', completed.get('completedAt')),
        ('Czas od ukończenia operacji', _duration(_age(completed.get('completedAt'), now))),
    ])
    failures = [value for value in (snapshot.get('error'), snapshot.get('failure'), collector.get('lastFailure'),
                                   collector.get('failure')) if value]
    failure_html = ''.join('<pre>' + safe(json.dumps(value, ensure_ascii=False, indent=2)) + '</pre>'
                           for value in failures)
    page = f'''<!doctype html>
<html lang="pl"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<meta http-equiv="refresh" content="5"><title>Postęp badania PQL</title>
<style>body{{font:16px/1.5 system-ui,sans-serif;max-width:950px;margin:32px auto;padding:0 20px;color:#17212b;background:#f6f8fa}}
table{{border-collapse:collapse;width:100%;background:white}}th,td{{padding:8px 12px;border-bottom:1px solid #dde3e8;text-align:left;vertical-align:top;overflow-wrap:anywhere}}th{{width:35%}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;background:#fff1f0;padding:16px}}h2{{margin-top:28px}}</style>
<h1>Postęp badania PQL</h1>
<p>Strona odświeża się co 5 sekund. Sygnał kontrolera potwierdza działanie pętli nadzoru;
postęp pracy potwierdzają ukończone operacje. Brak nowych wpisów podczas kompilacji
lub przygotowania Dockera może wynikać z buforowania dziennika i sam nie dowodzi zawieszenia.</p>
{table(controller_rows)}<h2>Bieżąca operacja</h2>{operation}
<h2>Ukończona praca</h2>{work}
<p>Czasy obliczono przy zapisie strony: {safe(now.isoformat())}.
Jeśli znacznik sygnału kontrolera przestaje się zmieniać, odświeżanie strony nie oznacza dalszej pracy.</p>
{('<h2>Błąd</h2>' + failure_html) if failures else ''}</html>'''
    _atomic(root / 'progress.json', json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n')
    _atomic(root / 'progress.html', page)
