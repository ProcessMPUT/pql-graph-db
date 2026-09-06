"""One thesis report for protocol 27, with a small confirmatory family and separate descriptive evidence."""
from collections import Counter, defaultdict
import csv
from datetime import datetime, timezone
from pathlib import Path
import statistics

from study_contract import RESOURCES, campaign_resources, cells, definitions, digest, file_manifest, identity, inventory, rows, write_json
from report_common import HYPOTHESIS, OPERATIONS, table, number
from study_figures import write_comparison, write_heatmap
from study_html import write_html
from study_analysis import primary_results, descriptive_results, latency_diagnostics, resource_results


CONTROLLED_AXES = {
    'selectivity-scaling': ('Selektywność filtrowania', 'Ślady spełniające warunek [%]',
        'Przy stałej liczbie zdarzeń zmienia się udział śladów zawierających zdarzenie docelowe. '
        'Wszystkie zdarzenia wybranego śladu mają attr_1 = hit, więc ten sam procent określa '
        'udział pasujących zdarzeń i śladów. Wybrane ślady są równomiernie rozłożone w logu. '
        'Badane są dwa zakresy warunku: filtrowanie zdarzeń oraz wybór całych śladów przez warunek '
        'zależny od zdarzeń. Na tych danych oba zapytania zliczają ten sam zbiór pasujących śladów. '
        'Jeden agregat w zakresie śladu utrzymuje stałą strukturę odpowiedzi; jego wartość zależy od liczby dopasowań.'),
    'trace-length-scaling': ('Długość śladów', 'Zdarzenia w śladzie',
        'Łączna liczba zdarzeń pozostaje stała, a zmienia się podział na ślady. '
        'Pobranie hierarchii zwraca dziesięć śladów po dziesięć zdarzeń; '
        'osobna agregacja obejmuje cały log. Stały zestaw nazw aktywności i cykl kosztów '
        'ograniczają wpływ rozkładu wartości na porównanie. '
        'Wynik dotyczy struktury hierarchii w tych dwóch operacjach.'),
    'response-window': ('Wielkość odpowiedzi', 'Zwracane zdarzenia / plan',
        'Na tym samym logu zmienia się wyłącznie okno pobieranej hierarchii. '
        'W tabeli podano rzeczywiste liczby elementów obu odpowiedzi, obok planowanego okna. '
        'Czas obejmuje wykonanie, rekonstrukcję i przesłanie danych; '
        'nie rozdziela kosztu serializacji od pracy bazy.')}

SERIES_NAMES = {'size-scaling': 'Rozmiar danych', 'variant-scaling': 'Liczba wariantów',
                'real-validation': 'Logi rzeczywiste',
                **{key: value[0] for key, value in CONTROLLED_AXES.items()}}


def response_window(query):
    if query['label'] == 'hierarchyWindow' or query['label'].startswith('responseWindow'):
        return query.get('expectedResponses', {}).get('variants-1', {}).get('events')
    return None


def controlled_points(times, datasets, queries):
    """Axis parameters come from the definitions recorded alongside these measurements."""
    data = {d['name']: d for d in datasets}
    query = {q['label']: q for q in queries}
    result = []
    for row in times:
        if row['metric'] != 'query':
            continue
        d, q = data[row['dataset']], query[row['query']]
        window = response_window(q) if d['name'] == 'variants-1' else None
        axis = 'response-window' if window else d['series']
        if axis not in CONTROLLED_AXES:
            continue
        if axis == 'selectivity-scaling':
            x = d['matchingTracePercent']
            events = d['traces']*d['eventsPerTrace']
            facet = f"{q['displayName']} — {events:,} zdarzeń".replace(',', ' ')
        elif axis == 'trace-length-scaling':
            x, facet = d['eventsPerTrace'], q['displayName']
        else:
            x, facet = window, d['name']
        order = (q['displayName'], events) if axis == 'selectivity-scaling' else (facet, 0)
        result.append(dict(row, axis=axis, x=x, facet=facet, facetOrder=order, operation=q['displayName']))
    return sorted(result, key=lambda r: (r['axis'], r['facetOrder'], r['x']))


def response_cardinalities(selected):
    groups = defaultdict(list)
    for job, _, evidence in selected.values():
        if job['phase'] not in ('coverage', 'pilot'):
            continue
        for r in evidence['queries']:
            if r['phase'] == 'warm':
                groups[r['datasetName'], r['queryLabel'], r['system']].append(r)
    def observed_range(records, field):
        values = [int(r[field]) for r in records if r.get(field) not in (None, '')]
        if len(values) != len(records):
            return '—'
        return str(min(values)) if min(values) == max(values) else f'{min(values)}–{max(values)}'
    return {key: '/'.join(observed_range(records, field) for field in ('logCount', 'traceCount', 'eventCount'))
            for key, records in groups.items()}


def controlled_sections(times, datasets, queries, selected, figures):
    points = controlled_points(times, datasets, queries)
    planned = {d['series'] for d in datasets} | ({'response-window'} if any(response_window(q) for q in queries) else set())
    if not planned.intersection(CONTROLLED_AXES):
        return []
    cards = response_cardinalities(selected)
    doc = ['## Kontrolowane czynniki obciążenia', '',
           'W każdej serii zmieniano jeden czynnik: odsetek dopasowań, długość śladów albo wielkość odpowiedzi. '
           'Wyniki opisują mediany i rozrzut czasów w jednym przygotowaniu środowiska. '
           'Nie dodaje się testów ani p-wartości do sześciu porównań hipotezy. '
           'Tabele obejmują wszystkie zebrane punkty. Liczby elementów odpowiedzi zapisano w kolejności '
           'logi/ślady/zdarzenia; zakres oznacza zmienność pomiędzy wywołaniami.', '']
    for axis, (title, parameter, explanation) in CONTROLLED_AXES.items():
        if axis not in planned:
            continue
        values = [r for r in points if r['axis'] == axis]
        if axis == 'selectivity-scaling':
            sizes = sorted({d['traces']*d['eventsPerTrace'] for d in datasets if d['series'] == axis})
            size_text = ', '.join(f'{n:,}'.replace(',', ' ') for n in sizes)
            explanation += f' Zaplanowane rozmiary logu: {size_text} zdarzeń.'
            if len(sizes) == 1:
                explanation += ' Wynik opisuje wpływ selektywności przy tym rozmiarze; nie określa, jak zmienia się on ze skalą danych.'
            explanation += (' Zapis odpowiedzi 1/1/0 oznacza jeden log i jeden element śladu zawierający '
                            'wynik agregacji, bez elementów zdarzeń. Liczba śladów zliczonych przez zapytanie '
                            'jest wartością agregatu, a nie liczbą elementów tej odpowiedzi.')
        doc += ['### '+title, '', explanation, '']
        if not values:
            doc += ['Brak ukończonych pomiarów tej osi.', '']
            continue
        doc += [table([parameter, 'Log', 'Operacja', 'Pary', 'L [ms]', 'R [ms]', 'R/L',
                       'Odpowiedź L; R (logi/ślady/zdarzenia)'], [
            [r['x'], r['dataset'], r['operation'], r['pairs'], number(r['local'], 1000),
             number(r['reference'], 1000), number(r['effect']),
             '; '.join(cards.get((r['dataset'], r['query'], system), '—') for system in ('local', 'reference'))]
            for r in values]), '']
        for caption, path in figures.get(axis, []):
            doc += [f'![{caption}]({path})', '']
    return doc


def write_csv(path, data):
    if not data: return
    fields = list(dict.fromkeys(k for r in data for k in r))
    with path.open('w',encoding='utf-8',newline='') as f:
        writer = csv.DictWriter(f,fieldnames=fields)
        writer.writeheader()
        writer.writerows(data)


def primary_table(result):
    verdict = dict(LOCAL_FASTER='LOCAL',REFERENCE_FASTER='REFERENCE',INCONCLUSIVE='brak rozstrzygnięcia',INCOMPLETE='brak kompletu')
    return table(['Log','Zapytanie','n','L [ms]','R [ms]','E','PU E (≥95%)','p','p Holma','Wynik'],[
        [r['dataset'],r['query'],r['n'],number(r['local'],1000),number(r['reference'],1000),number(r.get('effect')),
         f"{number(r.get('low'))}–{number(r.get('high'))}",number(r.get('rawPValue')),number(r['holmPValue']),verdict[r['verdict']]] for r in result])


def time_table(data, unit='ms'):
    scale = {'ms': 1000, 's': 1}[unit]
    return table(['Log', 'Operacja', 'Pary', f'L [{unit}]', f'L Q1–Q3 [{unit}]',
                  f'R [{unit}]', f'R Q1–Q3 [{unit}]', 'R/L'], [
        [r['dataset'], r['query'], r['pairs'], number(r['local'], scale),
         f"{number(r['localQ1'], scale)}–{number(r['localQ3'], scale)}", number(r['reference'], scale),
         f"{number(r['referenceQ1'], scale)}–{number(r['referenceQ3'], scale)}", number(r['effect'])] for r in data])


def compatibility_info_explanation(records):
    causes = Counter()
    other = []
    for row in records:
        if row['Status'] != 'INFO':
            continue
        details = row.get('Details', '')
        if ('NONDETERMINISTIC_MATCH: remote trace window is contained in expanded LOCAL result;' in details and
                details.endswith('unstable ProcessM trace-variant ordering')):
            causes['trace_window'] += 1
        elif (details.endswith('unstable ProcessM grouped-event ordering') and
              any(message in details for message in (
                  'NONDETERMINISTIC_MATCH: remote trace/event windows are contained in expanded LOCAL result;',
                  'NONDETERMINISTIC_MATCH: attributes and event multisets match;'))):
            causes['event_order'] += 1
        else:
            other.append([row['Log'], row['Query'], details or 'Brak zapisanego wyjaśnienia.'])
    doc = []
    if causes['trace_window']:
        doc += [f"Liczba przypadków INFO dotyczących wyboru okna śladów: {causes['trace_window']}. "
                'Różna kolejność grup wariantów przy limicie liczby śladów prowadziła do wyboru innych grup. '
                'Po diagnostycznym zwiększeniu limitu LOCAL odnaleziono w jego odpowiedzi wszystkie ślady '
                'zwrócone przez REFERENCE.', '']
    if causes['event_order']:
        doc += [f"Liczba przypadków INFO dotyczących kolejności zdarzeń po grupowaniu: {causes['event_order']}. "
                'Po pominięciu kolejności potwierdzono zgodność atrybutów i liczności wystąpień zdarzeń albo '
                'zawieranie okna śladów i zdarzeń REFERENCE w rozszerzonej odpowiedzi LOCAL.', '']
    if other:
        doc += ['Pozostałe przypadki INFO zachowują zapisane wyjaśnienia:', '',
                table(['Magazyn', 'Przypadek', 'Wyjaśnienie'], other), '']
    if causes:
        doc += ['Kontrole diagnostyczne wyjaśniają różnice kolejności i wyboru okna; '
                'nie zmieniają wyniku ścisłego porównania pierwotnych odpowiedzi na MATCH.', '']
    return doc


def resource_tables(selected):
    observations = resource_results(selected)
    memory = [r for r in observations if r['phase'] == 'queries']
    imports = [r for r in observations if r['phase'] == 'import-resource']
    io = [r for _, _, e in selected.values() for r in e.get('io', [])]
    return '\n\n'.join([
        'Każdy wiersz pamięci dotyczy systemu wykonującego operację. Dla LOCAL sumowane są '
        'próbki interpretera i Neo4j z tego samego odpytywania, a następnie wyznaczana jest '
        'mediana tych sum. Próbki poza oknem oraz próbki nieaktywnego systemu nie wchodzą do wyniku. '
        'Odstęp jest medianą faktycznych odstępów odpytywania. Maksimum próbek nie jest gwarantowanym szczytem.',
        table(['Log', 'Zapytanie', 'System', 'Próbki', 'Mediana [MiB]', 'Maksimum próbek [MiB]',
               'Odstęp [s]', 'Żądania', 'Okno [s]'], [
            [r['dataset'], r['query'], r['system'], r['samples'], number(r['medianBytes'], 1/2**20),
             number(r['peakBytes'], 1/2**20), number(r['medianGapSeconds']), r['completedRequests'], number(r['windowSeconds'])]
            for r in memory]),
        'Import może być krótszy od odpytywania. Brak próbki oznacza brak obserwacji pamięci, a nie zerowe użycie.',
        table(['Log', 'System', 'Próbki importu', 'Mediana [MiB]', 'Maksimum próbek [MiB]', 'Zakres wniosku'], [
            [r['dataset'], r['system'], r['samples'], number(r['medianBytes'], 1/2**20), number(r['peakBytes'], 1/2**20),
             'za mało do oceny maksimum' if r['samples'] < 2 else 'maksimum zaobserwowanych próbek'] for r in imports]),
        'I/O przedstawia przyrost liczników w całym oknie, wraz z liczbą żądań. Próbkowanie sieci '
        'jest wymagane na granicy aplikacji; brak dostępnego licznika oznaczono kreską.',
        table(['Log', 'Operacja', 'Komponent', 'Odczyt [MiB]', 'Zapis [MiB]', 'Sieć RX [MiB]', 'Sieć TX [MiB]',
               'Żądania', 'Okno [s]', 'Stan'], [
            [r['datasetName'], r['operationLabel'], r['component'], number(r['blockReadBytes'], 1/2**20),
             number(r['blockWriteBytes'], 1/2**20), number(r.get('networkReceiveBytes'), 1/2**20),
             number(r.get('networkTransmitBytes'), 1/2**20), r['completedOperations'],
             number((int(r['windowFinishedNanos'])-int(r['windowStartedNanos']))/1e9), r['status']] for r in io])])


def build(plan, state, jobs, selected, figures=None, resource_root=None):
    figures = figures or {}
    source = resource_root or next((root for _, root, e in selected.values() if 'environment' in e), RESOURCES)
    ds,qs = definitions(source)
    inv = inventory(plan, source)
    controlled = any(d['series'] in CONTROLLED_AXES for d in ds)
    size_count = sum(d['series'] == 'size-scaling' for d in ds)
    final = plan['status'] == 'frozen' and state.get('mode') == 'final' and len(selected) == len(jobs)
    primary = primary_results(plan,selected)
    times = descriptive_results(selected)
    data = {d['datasetName']:d for _,_,e in selected.values() for d in e.get('datasets',[])}
    compatibility = next((e['compatibility'] for _,_,e in selected.values() if 'compatibility' in e),[])
    storage = [r for _,_,e in selected.values() for r in e.get('storage',[])]
    budget = ('Bez limitu całkowitego czasu wykonania.' if plan['budgetSeconds'] is None else
              f"Budżet wykonania: {plan['budgetSeconds']/3600:.0f} h.")
    doc = ['# Porównanie interpreterów PQL — sprawozdanie z eksperymentu','',
           'Raport przedstawia porównanie opracowanego interpretera PQL z bazą Neo4j (LOCAL) '
           'i systemu ProcessM z PostgreSQL (REFERENCE). Obejmuje poprawność odpowiedzi, '
           'zachowanie danych XES, czas zapytań i importu oraz wykorzystanie pamięci i dysku.','',
           '**Komplet wyników badania.**' if final else '**Dokument roboczy. Brakujące pomiary pozostają jawne; nie jest to końcowy załącznik do pracy.**','',
           '## Cel badania','', 'Sprawdzano następującą hipotezę, postawioną w sekcji 1.2 pracy:', '',
           '> '+HYPOTHESIS,'',
           'Mierzono czas pełnej obsługi żądania HTTP: przygotowania żądania, '
           'translacji PQL, wykonania w bazie, rekonstrukcji i przesłania odpowiedzi. Wynik dotyczy tych implementacji '
           'w opisanym środowisku. Obejmuje pracę aplikacji i baz danych.','',
           'Eksperyment odpowiada na cztery pytania: o zgodność PQL i zachowanie XES, o czas operacji między poziomami '
           'hierarchii, o wpływ parametrów danych i zapytań oraz o koszt importu, pamięci, I/O i trwałego przechowywania. '
           'Czas, pamięć i rozmiar danych oceniano osobno.','',
           '## Jak czytać wyniki','',
           'Raport rozdziela sześć porównań służących weryfikacji hipotezy od pozostałych pomiarów opisowych. '
           'W pierwszej części powtarzano przygotowanie środowiska, aby sprawdzić powtarzalność przewagi. '
           'W części opisowej pokazano, jak czasy zmieniają się wraz z danymi i rodzajem zapytania.','',
           table(['Oznaczenie', 'Znaczenie'], [
               ['L, R', 'Wartości dla LOCAL i REFERENCE. Czasy zapytań podano w milisekundach, importu w sekundach, pamięć i dysk w MiB.'],
               ['R/L', 'Iloraz wartości REFERENCE i LOCAL. Dla czasu R/L = 2 oznacza dwukrotnie krótszy czas LOCAL, a R/L = 0,5 — dwukrotnie krótszy czas REFERENCE.'],
               ['Mediana i Q1–Q3', 'Po uporządkowaniu czasów mediana jest środkowym czasem lub średnią dwóch środkowych. Zakres między kwartylami Q1 i Q3 obejmuje środkowe 50% czasów i opisuje ich rozrzut.'],
               ['E', 'Efekt zbiorczy z niezależnych przygotowań środowiska. E > 1 oznacza krótszy czas LOCAL; wzór obliczenia podano przy weryfikacji hipotezy.'],
               ['n i PU', 'n to liczba niezależnych przygotowań. PU jest przedziałem ufności efektu E o pokryciu co najmniej 95%. Kwartyle czasów w jednym bloku nie są takim przedziałem.'],
               ['p Holma', 'p-wartość po korekcie uwzględniającej sześć testów. Wynik poniżej 0,05 pozwala odrzucić hipotezę o braku różnicy; kierunek wskazuje E.']]),'',
           'Dla pamięci i dysku mniejsza wartość oznacza mniejszy koszt. Wszystkie osie wykresów są liniowe. '
           'Kolor punktu wskazuje kierunek różnicy; w pomiarach opisowych nie oznacza istotności statystycznej.','',
           '## Projekt i jednostki pomiaru','',
           'Log jest jednostką danych. Blok oznacza jedno zapytanie na jednym logu. Para zawiera dwa sąsiednie wywołania, '
           'po jednym na system. W kolejnych parach zmieniano kolejność: najpierw LOCAL, potem REFERENCE (LR), '
           'a następnie odwrotnie (RL).','',
           'Niezależne przygotowanie oznacza uruchomienie procesów z pustymi wolumenami baz danych i ponowny import logów. '
           'W obrębie jednego przygotowania wykonywano wiele par żądań. Zadanie obejmuje pomiary w jednym '
           'tak przygotowanym środowisku; komplet zadań nazywany jest kampanią. '
           'Wariant procesu oznacza sekwencję nazw zdarzeń śladu.','',
           table(['Część','Zakres','Powtórzenia','Rola wyniku'],[
               ['Weryfikacja hipotezy','3 zapytania × 2 rozmiary','12 niezależnych przygotowań','6 testów statystycznych'],
               ['Skalowanie, czynniki obciążenia i logi rzeczywiste',f"{len(ds)} logów, {inv['blocksByPhase']['coverage']} bloków",'1 komplet, po '+str(plan['latency']['pairs'])+' par','mediany, rozrzut i diagnostyka'],
               ['Import',f'{size_count} rozmiarów','po '+str(plan['imports']['pairs'])+' par importów','opis kosztu i rozrzutu'],
               ['Pamięć i I/O',f"{inv['blocksByPhase']['resources']} bloków na {len(plan['resources']['datasets'])} logach",'po jednym oknie na system','opis zasobów'],
               ['Trwały rozmiar',f'{size_count} rozmiarów','nowe wolumeny dla każdego logu','izolowany przyrost'],
               ['Zgodność','69 przypadków × 4 logi i 6 przypadków wielologowych','osobne zadanie','odpowiedzi, odrzucenia, INFO i błędy']]),'',
           'Trzy operacje weryfikujące hipotezę to pobranie hierarchii, dodatni warunek śladu zależny od zdarzeń oraz '
           'zliczanie elementów hierarchii. Dwa rozmiary — 100 tys. i 1 mln zdarzeń — określono przed pomiarami końcowymi. '
           'Pierwszy reprezentuje pośrednie obciążenie osi, drugi jej górną granicę. Pozostałe operacje, w tym grupowanie '
           'z użyciem metadanych importu, zachowano w pełnym badaniu opisowym. Sześć testów nie obejmuje wszystkich '
           'logów ani konstrukcji PQL. Zapytania dobrano celowo, znając budowę porównywanych systemów; '
           'nie stanowią losowej próby wszystkich zastosowań PQL.','',
           '## Dane i zapytania','',
           'Seria rozmiaru zwiększa liczbę śladów przy 10 zdarzeniach w śladzie, jednym wariancie i pięciu dodatkowych '
           'atrybutach zdarzenia. Punkty to 1, 5, 20, 100, 200, 500 tys. oraz 1 mln zdarzeń. Seria wariantów zachowuje '
           '100 tys. zdarzeń, 2000 śladów po 50 zdarzeń i 48 aktywności; zmienia tylko liczbę wariantów: 1, 100 i 2000.','',
           'Generator jest deterministyczny. Czas zaczyna się 1 stycznia 2020 roku i rośnie o minutę między zdarzeniami, '
           'koszt ma deterministyczny cykl określony w definicji danych. '+
           ('Atrybut attr_1 w serii selektywności oznacza dopasowanie; pozostałe dodatkowe atrybuty mają stałe wartości. '
            if controlled else 'Dodatkowe atrybuty mają stałe wartości dla swoich kluczy. ')+
           'W serii '
           'wariantów początek śladu koduje numer wariantu, dalsze pozycje zapewniają zadany zestaw aktywności. '
           'Regularność umożliwia kontrolę parametrów i ogranicza reprezentatywność logów syntetycznych.','',
           'Dwanaście pełnych logów rzeczywistych obejmuje Hospital, BPI Challenge 2012, trzy logi BPI Challenge 2013, '
           'pięć logów BPI Challenge 2015, Sepsis oraz Road Traffic. Dobór jest celowy; powiązane logi z jednej edycji '
           'nie stanowią niezależnej próby dziedzin zastosowania. Oba systemy otrzymują identyczne bajty XES. '
           'Ich skróty SHA-256, parametry i źródłowe DOI są częścią wyników.','',
           'Tabela podaje liczby elementów odczytane po imporcie oraz parametry generatora.'+
           (' W niezebranych punktach syntetycznych pokazano wartości z planu.' if not final else ''),'',
           table(['Log','Seria','Zdarzenia','Ślady','Zdarzeń/ślad','Dopasowania [%]','Warianty','DOI'],[
               [d['name'],SERIES_NAMES.get(d['series'],d['series']),data.get(d['name'],{}).get('totalEvents',str((d.get('traces',0)*d.get('eventsPerTrace',0))) if d.get('traces') else 'do pomiaru'),
                data.get(d['name'],{}).get('traces',d.get('traces','do pomiaru')), d.get('eventsPerTrace','zmienne'),
                d.get('matchingTracePercent','—'), data.get(d['name'],{}).get('variantCount',d.get('variantCount',1) if d.get('traces') else 'do pomiaru'),
                d.get('sourceDoi','—')] for d in ds]),'',
           'Identyfikatory zapytań łączą poniższe teksty PQL z tabelami wyników i plikami CSV.','',
           table(['Identyfikator','Operacja','Zakres serii / logów','PQL'],[[q['label'],q['displayName'],
                 ', '.join(q.get('measurementDatasets') or [SERIES_NAMES.get(s,s) for s in q['measurementSeries']]),
                 f"`{q['query']}`"] for q in qs]),'',
           'Minimalne okno jest punktem odniesienia dla małej kompletnej odpowiedzi, bez testu przewagi. '
           'Nie mierzy samego narzutu HTTP i nie jest odejmowane od pozostałych czasów. Dodatnie kontrole wymagają '
           'niepustego wyniku; zgodność dwóch pustych odpowiedzi nie wystarcza. Kontrole bez dopasowań mają jawne '
           'maksimum liczby zdarzeń. Każda mierzona para jest porównywana semantycznie po bloku i zachowuje pełne odpowiedzi.','',
           '## Środowisko i procedura','',
           'Każde zadanie rozpoczyna się od nowego stosu. LOCAL ma łącznie 6 GiB pamięci kontenerowej dla aplikacji '
           'i Neo4j, REFERENCE 6 GiB dla wspólnego kontenera aplikacji i PostgreSQL; obie strony mają po 3 GiB sterty JVM '
           'i brak swapu w tych budżetach. Sprawdzane są działające kontenery, efektywne limity, niezmienne identyfikatory '
           'obrazów i czysta wersja kodu. Zmiana wersji, restart lub OOM uniemożliwiają przyjęcie zadania.','']
    env = next((e['environment'] for _,_,e in selected.values() if 'environment' in e),None)
    if env:
        host=env.get('host',{})
        doc += [f"Zapisany host: {host.get('cpuModel','—')}; procesory logiczne: {host.get('logicalProcessors','—')}; "
                f"pamięć fizyczna: {number(host.get('totalPhysicalMemoryBytes'),1/2**30)} GiB. "
                'Identyfikatory wersji kodu i obrazów kontenerów podano w dodatku C.','']
    doc += [f"Procedura obejmuje {plan['latency']['globalWarmupRounds']} rund globalnej rozgrzewki na osobnym logu, "
            f"następnie {plan['latency']['warmups']} wywołań na zapytanie i system oraz {plan['latency']['pairs']} par mierzonych. "
            'Rozgrzewka pozwala procesom wykonać badane operacje przed pomiarem; jej czasy są zapisane osobno '
            'i nie wchodzą do median. Kolejność logów i zapytań losowano z zapisanym ziarnem. '
            'Parametry ustalono po osobnym pilotażu, którego wyników nie włączono do tego badania.','',
            'Podczas bloków czasu nie działa próbkowanie Docker. Parsowanie liczników, porównanie odpowiedzi i zapis '
            'plików odbywają się poza zegarem żądania. Import obejmuje wysłanie pliku i wykrycie gotowości przez API; '
            'po negatywnym odczycie kolektor czeka 100 ms. Czas odczytu API powiększa ten odstęp, dlatego pomiar nie '
            'wyznacza dokładnego momentu zakończenia zapisu serwera.','',
            'Sprawdzano także, czy iloraz czasów zmieniał się w trakcie bloku (dryf) oraz czy zależał od '
            'kolejności wykonania systemów. W tym celu porównywano pierwszą i ostatnią trzecią część bloku '
            'oraz pary LR i RL. Różnica ilorazów większa niż 10% była ostrzeżeniem diagnostycznym. '
            'Wyniki z ostrzeżeniami zachowano; rozgrzewka nie gwarantuje pełnej stabilności czasów.','',
            '## Weryfikacja hipotezy','',
            'Każde z sześciu porównań wykonano w dwunastu niezależnych przygotowaniach środowiska. '
            'W przygotowaniu r obliczono E_r = mediana(R_r) / mediana(L_r). Cały blok par żądań wnosi '
            'więc jeden iloraz E_r do testu statystycznego. Efekt zbiorczy obliczono jako '
            'E = exp(mediana(log(E_r))). Kolumny L i R są medianami median czasów z przygotowań; '
            'ich iloraz może nieznacznie różnić się od E.','',
            'H0: mediana rozkładu log(E_r) wynosi 0, czyli typowy efekt w populacji przygotowań wynosi 1. '
            'H1: mediana tego rozkładu jest różna od 0. '
            'Dwustronny test znaków sprawdza, jak często przygotowania wskazują ten sam kierunek różnicy. '
            'P-wartość obliczono z rozkładu dwumianowego, na podstawie liczby dodatnich i ujemnych log(E_r). '
            'Przypadki E_r = 1 uwzględniano zachowawczo, przeciwko dominującemu kierunkowi.','',
            'Przedział ufności efektu wyznaczono z uporządkowanych obserwacji, ze statystyk pozycyjnych. '
            'Jego pokrycie wynosi co najmniej 95% przy niezależnych przygotowaniach tych samych warunków. '
            'Każdy przedział dotyczy jednego porównania; nie jest wspólną gwarancją dla wszystkich sześciu. '
            '[Opis testu znaków — NIST](https://www.itl.nist.gov/div898/software/dataplot/refman1/auxillar/signtest.htm).','',
            'Korekta Holma uwzględnia wykonanie sześciu testów i ogranicza ryzyko choć jednego fałszywego '
            'stwierdzenia różnicy w tej rodzinie do 0,05. Przy p Holma < 0,05 odrzuca się H0: '
            'E > 1 wskazuje przewagę LOCAL, a E < 1 przewagę REFERENCE. Pozostałe przypadki pozostają '
            'nierozstrzygnięte, co nie dowodzi równoważności systemów. Brakujące porównanie zachowuje miejsce '
            'w rodzinie testów jak p = 1.','',
            'Liczbę dwunastu przygotowań ustalono przed pomiarami jako kompromis między kosztem a możliwością '
            'wykrycia powtarzalnej różnicy. Nie gwarantuje ona wykrycia każdej różnicy; liczby przygotowań '
            'nie zwiększano po poznaniu p-wartości.','',primary_table(primary),'']
    for caption, path in figures.get('primary', []):
        doc += [f'![{caption}]({path})', '']
    doc += ['## Skalowanie i logi rzeczywiste','',
            'Ta część pochodzi z osobnych pomiarów opisowych: dla każdego bloku wykonano '
            f"{plan['latency']['pairs']} par w jednym przygotowaniu środowiska. Dlatego czasy na tych wykresach "
            'mogą różnić się od median z dwunastu przygotowań w poprzedniej sekcji. '
            'Mediany i ilorazy opisują zaobserwowaną różnicę, bez dodatkowych testów statystycznych. '
            'Zestaw logów jest celowy i nie stanowi losowej próby zastosowań PQL. '
            'Kwartyle Q1 i Q3 obliczono przez interpolację pozycji (n−1)p dla p = 0,25 i p = 0,75. '
            'Wykresy zestawiają wszystkie zmierzone punkty danej serii: mediany obu systemów, ich kwartyle '
            'i iloraz R/L na liniowej osi od zera. Linia przy 1 oznacza równe mediany. '
            'Kolor wskazuje kierunek różnicy, bez rozstrzygnięcia statystycznego. Każdy wykres ma własny zakres osi. '
            'Tabele przy operacjach pokazują krańce osi syntetycznych oraz najmniejszy i największy iloraz R/L '
            'wśród logów rzeczywistych. Komplet czasów, w tym minimalne okno, znajduje się w dodatku A.','']
    for q in qs:
        if q['label'].startswith('responseWindow') or set(q['measurementSeries']) <= set(CONTROLLED_AXES):
            continue
        title, explanation = OPERATIONS.get(q['label'],(q['displayName'],q['purpose']))
        values = [r for r in times if r['metric'] == 'query' and r['query'] == q['label']]
        doc += ['### '+title,'',explanation,'']
        size_points = [d for d in ds if d['series'] == 'size-scaling' and d['series'] in q['measurementSeries'] and
                       (not q.get('measurementDatasets') or d['name'] in q['measurementDatasets'])]
        if size_points and len(size_points) < size_count:
            doc += ['Dla tego zapytania zaplanowano pomiary dla rozmiarów: '+
                    ', '.join(dataset_label(d) for d in size_points)+'.', '']
        chosen=[]
        for group in ('size-scaling','variant-scaling','real-validation'):
            group_data=[r for r in values if data[r['dataset']]['series'] == group]
            if not group_data: continue
            key=(lambda r:r['effect']) if group == 'real-validation' else (lambda r:int(data[r['dataset']]['totalEvents'] if group == 'size-scaling' else data[r['dataset']]['variantCount']))
            ordered=sorted(group_data,key=key)
            chosen.extend([ordered[0]] if len(ordered)==1 else [ordered[0],ordered[-1]])
        if chosen: doc += [time_table(chosen),'']
        else: doc += ['Brak ukończonych pomiarów tej operacji.','']
        for caption,path in figures.get(q['label'],[]):
            doc += [f'![{caption}]({path})','']
    for caption, path in figures.get('bpi', []):
        doc += ['### Zestawienie BPI Challenge', '', f'![{caption}]({path})', '']
    doc += controlled_sections(times, ds, qs, selected, figures)
    doc += ['## Import, pamięć i trwały rozmiar','',
            f"Czas importu opisuje {plan['imports']['pairs']} par dla każdego z siedmiu rozmiarów. Każdy import wykonywano do nowego magazynu, "
            'przy działających procesach. Tabela i wykres przedstawiają mediany i kwartyle czasów; '
            'te wyniki mają charakter opisowy. Importy przygotowujące pozostałe etapy zestawiono osobno w dodatku A.','',
            time_table([r for r in times if r['metric']=='import'], unit='s'),'']
    for caption, path in figures.get('import', []):
        doc += [f'![{caption}]({path})', '']
    doc += [
            'Pamięć i operacje wejścia–wyjścia (I/O) obserwowano w oddzielnych zadaniach. '
            f"Po {plan['resources']['warmups']} wywołaniach przygotowujących każdy system powtarzał to samo zapytanie "
            f"przez co najmniej {plan['resources']['windowSeconds']} sekund. Ten okres, przedłużony do końca ostatniego żądania, "
            f"stanowi okno pomiaru zasobów. Wymagano co najmniej {plan['resources']['minimumSamples']} próbek na komponent "
            'wewnątrz okna. Próbki ukończone później zachowano, ale wyłączono z podsumowań. '
            'W każdym bloku zmierzono po jednym oknie dla LOCAL i REFERENCE.', '',
            'Zakres zasobów ustalono przed pomiarami: 1 tys., 100 tys. i 1 mln zdarzeń, 1 i 2000 wariantów oraz '
            'Sepsis i Hospital. Obejmuje krańce osi, pośredni rozmiar oraz dwa logi o różnych strukturach śladów. '
            'Pomiary czasu obejmują również pozostałe logi; ich użycia pamięci i I/O nie mierzono.','',
            'Pamięć oznacza użycie kontenera raportowane przez Docker po odjęciu części nieaktywnego cache. '
            'LOCAL sumuje aplikację i Neo4j z tego samego odpytywania, REFERENCE obejmuje wspólny kontener. '
            'Docelowy odstęp między rozpoczęciami odczytów wynosi sekundę. '
            'Jeśli sam odczyt trwa dłużej, rzeczywisty odstęp rośnie; '
            'rzeczywiste odstępy podano w dodatku B. '
            'Maksimum próbek nie jest gwarantowanym szczytem. Obserwacja podczas importu może nie uchwycić bardzo '
            'krótkiego importu; brak próbek pozostaje brakiem, a nie zerowym zużyciem pamięci.','']
    for caption, path in figures.get('memory', []):
        doc += [f'![{caption}]({path})', '']
    doc += [
            'I/O jest różnicą liczników kontenerów przed i po oknie. Towarzyszą mu długość okna i liczba ukończonych '
            'żądań. System szybszy może wykonać ich więcej, więc całkowitych bajtów okna nie należy utożsamiać '
            'z kosztem pojedynczego żądania. Zerowe odczyty przy ciepłym cache nie oznaczają braku kosztu bazy.','',
            'Przyrost trwałego rozmiaru danych zmierzono na nowych wolumenach dla każdego rozmiaru logu. '
            'Jest to różnica rozmiaru plików przed utworzeniem badanego magazynu i po imporcie. '
            'Dla Neo4j odczytano rozmiar katalogu baz po restarcie, a dla PostgreSQL sumę rozmiarów baz. '
            'Pomiar nie obejmuje dzienników transakcji Neo4j ani WAL PostgreSQL. Przydział plików przez silnik '
            'wpływa szczególnie na małe logi. Kolumna „Przyrost / XES” odnosi przyrost do rozmiaru nieskompresowanego XES.','',
            table(['Log','System','Przyrost [MiB]','Przyrost / XES'],[[r['datasetName'],r['system'],number(r['deltaBytes'],1/2**20),number(r['deltaToXesRatio'])] for r in storage]),'']
    for caption, path in figures.get('storage', []):
        doc += [f'![{caption}]({path})', '']
    doc += ['## Zgodność i zachowanie XES','']
    groups=defaultdict(list)
    for r in compatibility: groups[r['Log']].append(r)
    doc += [table(['Magazyn','Przypadki','Zgodne odpowiedzi','Zgodne odrzucenia','INFO','Problemy'],[
        [n,len(rs),sum(r['Status']=='MATCH' and r.get('LocalSuccess','').lower()=='true' and r.get('RemoteSuccess','').lower()=='true' for r in rs),
         sum(r['Status']=='MATCH' and r.get('LocalSuccess','').lower()=='false' and r.get('RemoteSuccess','').lower()=='false' for r in rs),
         sum(r['Status']=='INFO' for r in rs),sum(r['Status'] not in ('MATCH','INFO') for r in rs)] for n,rs in groups.items()]),'',
         'Zgodne odrzucenie dotyczy niepoprawnego zapytania; nie jest parą zwróconych odpowiedzi. INFO oznacza '
         'przypadek informacyjny wymagający wyjaśnienia, a nie ścisłą zgodność. '
         'Sześć dodatkowych przypadków sprawdza wybór logów i zakres zapytań w magazynie zawierającym '
         'jednocześnie JournalReview i Sepsis. Pozostałe przypadki badają konstrukcje PQL osobno na czterech logach.','']
    doc += compatibility_info_explanation(compatibility)
    trips={r['datasetName']:r['status'] for j,p,_ in selected.values() if j['phase']=='coverage' for r in rows(p/'roundtrip-results.csv')}
    doc += [f"Porównanie zawartości XES przed importem i po eksporcie: {sum(v=='MATCH' for v in trips.values())}/{len(ds)} logów z wynikiem MATCH. "
            'Sprawdzana jest hierarchia, typy i wartości atrybutów oraz metadane, nie identyczność tekstu XML. '
            'Szczegółowe odpowiedzi i ewentualne różnice pozostają w materiałach źródłowych.','',
            '## Ocena hipotezy i ograniczenia','']
    counts=Counter(r['verdict'] for r in primary)
    if not final: doc += ['Nie ma jeszcze kompletnej kampanii. Ocena hipotezy i końcowe wnioski pozostają otwarte.','']
    else:
        conclusion=('Hipoteza uzyskała potwierdzenie we wszystkich sześciu zaplanowanych porównaniach.' if counts['LOCAL_FASTER']==6 else
                    'Hipoteza uzyskała częściowe poparcie w zaplanowanych porównaniach.' if counts['LOCAL_FASTER'] else
                    'Zaplanowane porównania nie potwierdziły hipotezy o krótszym czasie LOCAL.')
        doc += [conclusion+f" Rozstrzygnięcia: {counts['LOCAL_FASTER']} LOCAL, {counts['REFERENCE_FASTER']} REFERENCE, "
                f"{counts['INCONCLUSIVE']} bez rozstrzygnięcia. Dotyczą trzech operacji na dwóch rozmiarach w zapisanym środowisku. "
                'Nie stanowią dowodu przewagi dla każdej konstrukcji PQL, każdego logu ani każdego komputera.','']
        largest = max((d for d in ds if d['series'] == 'size-scaling'),
                      key=lambda d: d['traces']*d['eventsPerTrace'], default=None)
        examples = [r for r in times if largest and r['metric'] == 'query' and r['dataset'] == largest['name']
                    and r['query'] in ('hierarchyWindow', 'hoistedPositive', 'eventEquality', 'likeMatching')]
        if examples:
            names = {q['label']: q['displayName'] for q in qs}
            doc += ['Wybrane wyniki opisowe dla największego logu syntetycznego ('+dataset_label(largest)+
                    ') zestawiają odczyt hierarchii z dodatnim filtrowaniem atrybutów. '
                    'Pokazują, dlaczego wniosek o czasie zależy od rodzaju zapytania.', '',
                    table(['Operacja', 'R/L', 'Niższa mediana czasu'], [
                        [names[r['query']], number(r['effect']), 'LOCAL' if r['effect'] > 1 else
                         'REFERENCE' if r['effect'] < 1 else 'równe mediany'] for r in examples]), '']
        memory = defaultdict(dict)
        for r in resource_results(selected):
            if r['phase'] == 'queries' and r['medianBytes'] is not None:
                memory[r['dataset'], r['query']][r['system']] = r['medianBytes']
        complete_memory = [v for v in memory.values() if set(v) == {'local', 'reference'}]
        if complete_memory:
            higher = sum(v['local'] > v['reference'] for v in complete_memory)
            doc += [f"W osobnych pomiarach zasobów mediana użycia pamięci LOCAL była większa w {higher} "
                    f"z {len(complete_memory)} bloków. Ocena krótszego czasu musi więc uwzględniać również koszt pamięci.", '']
    doc += ['Grupowanie nazw zdarzeń w LOCAL korzysta z metadanych przygotowanych przy imporcie. '
            'Jego wynik ocenia całą tę ścieżkę, a nie sam graf. Stałe okna odpowiedzi i regularna budowa danych '
            'syntetycznych również ograniczają zakres uogólnienia. Same czasy nie pozwalają przypisać różnicy '
            'konkretnemu indeksowi lub operatorowi bazy. '+
            ('Dalsze badania mogą rozszerzyć zakres kontrolowanych czynników, uwzględnić ich współdziałanie i sprawdzić inne środowiska. '
             if controlled else 'Dalsze badania mogą zmieniać selektywność i wielkość odpowiedzi oraz sprawdzać inne środowiska. ')+
            'Rozwój systemu powinien ograniczyć materializację całego logu podczas importu i eksportu.','',
            '## Dodatek A — komplet czasów i diagnostyka','',time_table([r for r in times if r['metric']=='query']),'',
            'Importy przygotowawcze są osobną kategorią i nie powiększają próby pięciu importów osi rozmiaru.','',
            time_table([r for r in times if r['metric']=='setup'], unit='s'),'']
    diagnostics = latency_diagnostics(selected)
    doc += [table(['Zadanie', 'Bloki czasu', 'Oceniona kolejność', 'Efekt kolejności >10%',
                   'Oceniony dryf', 'Dryf >10%'], [
        [r['task'], r['blocks'], r['orderChecked'], r['orderWarnings'], r['driftChecked'], r['driftWarnings']]
        for r in diagnostics]), '',
        'Kolumny „oceniona kolejność” i „oceniony dryf” podają liczby bloków, dla których dostępne są '
        'wymagane obserwacje. Zero ocenionych bloków oznacza brak oceny, nie brak efektu. '
        'Pokrycie pamięci dotyczy wyłącznie osobnych okien zasobów w dodatku B.', '',
        '## Dodatek B — zasoby', '', resource_tables(selected), '',
            '## Dodatek C — kompletność i odtworzenie','',
            f"Ukończone zadania: {len(selected)}/{len(jobs)}. Zapisany czas kampanii: "
            f"{number(state.get('elapsedSeconds',0)/60)} min. {budget}",'',
            table(['Zadanie','Część','Stan','Próby'],[[j['id'],j['phase'],'ukończone' if j['id'] in selected else 'brak',len(state.get('tasks',{}).get(j['id'],[]))] for j in jobs]),'',
            'Cała kampania stanowi jeden katalog: plan.json zawiera zamrożony projekt, state.json historię wszystkich '
            'prób, a tasks/ surowe wyniki, odpowiedzi, logi i dowody przygotowania. Nie wybiera się najszybszej próby. '
            'Przerwanie wymaga odnotowania przyczyny; ponawia się całe zadanie na świeżym stosie. Ukończonych etapów '
            'nie powtarza się, a pomiary pamięci, importu i rozmiaru nie zastępują wyników czasu zapytań.','',
            'Dokument, CSV i tabele LaTeX można odtworzyć bez uruchamiania baz poleceniem '
            '`python3 scripts/benchmarks/benchmark-study.py report --out <katalog-kampanii>`. '
            'Kontrola `audit` wymaga kompletnej macierzy, jednakowych wersji i niezmienionych plików.','']
    if env:
        doc += [f"Wersja kodu użyta w pomiarach: `{env['source']['gitCommit']}`. "
                'Wyniki zachowują identyfikatory przygotowań, obrazów kontenerów oraz skróty SHA-256 plików wejściowych.', '',
                table(['Kontener','Obraz','Limit [GiB]'],[
                    [n,c['imageId'],number(c.get('memoryLimitBytes'),1/2**30)] for n,c in env['containers'].items()]), '']
    return '\n'.join(doc),primary,times


def dataset_label(data):
    """Display names never determine which measurements belong to a chart."""
    if data['series'] == 'size-scaling':
        events = int(data.get('totalEvents') or data['traces']*data['eventsPerTrace'])
        return f'{events:,} zdarzeń'.replace(',', ' ')
    if data['series'] == 'variant-scaling':
        count = int(data['variantCount'])
        return '1 wariant' if count == 1 else f'{count:,} wariantów'.replace(',', ' ')
    name = data.get('name', data.get('datasetName'))
    return {'real-hospital': 'BPIC11 (Hospital)', 'real-sepsis': 'Sepsis',
            'real-road-traffic': 'Road Traffic', 'real-bpic12': 'BPIC12',
            'real-bpic13-closed-problems': 'BPIC13 (closed problems)',
            'real-bpic13-incidents': 'BPIC13 (incidents)',
            'real-bpic13-open-problems': 'BPIC13 (open problems)'}.get(
                name, name.removeprefix('real-').upper())


def make_figures(directory, selected, resource_root=None, plan=None):
    figures = defaultdict(list)
    source = resource_root or next((root for _, root, e in selected.values() if 'environment' in e), RESOURCES)
    recorded_datasets, queries = definitions(source)
    datasets = {d['name']: dict(d) for d in recorded_datasets}
    for _, _, evidence in selected.values():
        for d in evidence.get('datasets', []):
            datasets[d['datasetName']].update(d)
    by_label = {q['label']: q for q in queries}
    times = descriptive_results(selected)
    groups = defaultdict(list)
    series_titles = {'size-scaling': 'Liczba zdarzeń', 'variant-scaling': 'Liczba wariantów',
                     'real-validation': 'Logi rzeczywiste'}
    descriptive_note = ('Mediana oraz [Q1–Q3] czasów. R/L = mediana REFERENCE / mediana LOCAL. '
                        'Kwartyle opisują środkowe 50% czasów w jednym przygotowaniu, nie przedział ufności.')

    def converted(row, label, scale=1000):
        result = dict(label=label)
        for field in ('local', 'reference', 'localQ1', 'localQ3', 'referenceQ1', 'referenceQ3'):
            if row.get(field) is not None:
                result[field] = row[field]*scale
        if row.get('effect') is not None:
            result['effect'] = row['effect']
        return result

    def comparison(key, filename, title, values, caption, unit='ms', subtitle='', note=descriptive_note):
        if not values:
            return
        path = Path('figures')/(filename+'.svg')
        (directory/path).parent.mkdir(exist_ok=True)
        write_comparison(directory/path, title, values, unit=unit, subtitle=subtitle, note=note)
        figures[key].append((caption, str(path)))

    def dataset_order(name):
        d = datasets[name]
        if d['series'] == 'size-scaling':
            return (0, int(d.get('totalEvents') or d['traces']*d['eventsPerTrace']), '')
        if d['series'] == 'variant-scaling':
            return (1, int(d['variantCount']), '')
        return (2, int(d.get('collectionOrder') or 9999), name)

    # Every plotted row is an observed block; categorical rows avoid compressing small logs.
    for row in times:
        if row['metric'] != 'query':
            continue
        series = datasets[row['dataset']]['series']
        query = by_label[row['query']]
        if (series in query.get('scalingSeries', []) and series in series_titles) or (
                series == 'real-validation' and row['query'] != 'minimalWindow'):
            groups[row['query'], series].append(row)
    for (label, series), values in groups.items():
        query = by_label[label]
        values.sort(key=lambda r: dataset_order(r['dataset']))
        comparison(label, label+'-'+series, query['displayName'],
                   [converted(r, dataset_label(datasets[r['dataset']])) for r in values],
                   f"{query['displayName']} — {series_titles[series].lower()}. "
                   'Mediany, kwartyle czasów i iloraz R/L; jeden wiersz na zmierzony punkt.',
                   subtitle=query.get('query', ''), note=series_titles[series]+'. '+descriptive_note)

    controlled = defaultdict(list)
    for row in controlled_points(times, recorded_datasets, queries):
        controlled[row['axis'], row['facet']].append(row)
    for index, ((axis, facet), values) in enumerate(controlled.items(), 1):
        title, parameter, _ = CONTROLLED_AXES[axis]
        suffix = {'selectivity-scaling': '%', 'trace-length-scaling': ' zdarzeń/ślad',
                  'response-window': ' zdarzeń'}[axis]
        rows = [converted(r, f"{r['x']:,}".replace(',', ' ')+suffix) for r in values]
        subtitle = by_label[values[0]['query']].get('query', '')
        if axis == 'response-window':
            subtitle = 'Okno: liczba logów / śladów / zdarzeń w śladzie'
            for row, value in zip(rows, values):
                row['detail'] = by_label[value['query']].get('query', '')
        comparison(axis, f'{axis}-{index}', title+' — '+facet if axis == 'response-window' else facet, rows,
                   f'{title} — {facet}. Wszystkie zmierzone wartości czynnika, czasy obu systemów i R/L.',
                   subtitle=subtitle, note=parameter+'. '+descriptive_note)

    if plan:
        primary = primary_results(plan, selected)
        for label in plan['primary']['queries']:
            values = []
            for r in primary:
                if r['query'] != label or r['status'] != 'OK':
                    continue
                values.append(dict(converted(r, dataset_label(datasets[r['dataset']])),
                                   low=r['low'], high=r['high'],
                                   detail=f"n = {r['n']}; p Holma = {number(r['holmPValue'])}"))
            query = by_label[label]
            comparison('primary', 'primary-'+label, query['displayName'], values,
                       'Weryfikacja hipotezy — '+query['displayName']+'. Efekt E i punktowy przedział ufności '
                       'o pokryciu co najmniej 95%; n oznacza liczbę niezależnych przygotowań.',
                       subtitle=query.get('query', ''), note='E = exp(mediana(log(R/L) z przygotowań)). '
                       'L i R: mediany median czasów; ich iloraz może różnić się od E. Wąsy: przedział ufności E.')

    bpi = sorted((d['name'] for d in recorded_datasets if d.get('collection') == 'bpi-challenge'), key=dataset_order)
    bpi_columns = [('hierarchyWindow', 'Pobranie hierarchii'), ('variantGroupCount', 'Grupowanie wariantów'),
                   ('hierarchyCardinality', 'Zliczanie hierarchii'), ('globalEventAggregation', 'Agregacja zdarzeń'),
                   ('realStandardAttributesOrder', 'Sortowanie atrybutów'), ('realLikeNoMatch', 'LIKE bez dopasowań')]
    bpi_columns = [(key, title) for key, title in bpi_columns if key in by_label]
    bpi_values = {(dataset_label(datasets[r['dataset']]), r['query']): r['effect'] for r in times
                  if r['metric'] == 'query' and r['dataset'] in bpi and r['query'] in dict(bpi_columns)}
    if bpi_values:
        path = Path('figures')/'bpi-heatmap.svg'
        (directory/path).parent.mkdir(exist_ok=True)
        write_heatmap(directory/path, 'BPI Challenge — iloraz median czasów R/L',
                      [dataset_label(datasets[d]) for d in bpi], bpi_columns, bpi_values,
                      note='Kolor opisuje kierunek i wielkość ilorazu, bez oceny istotności statystycznej. '
                      'Mediany i kwartyle dla każdej operacji podano na wykresach powyżej.')
        figures['bpi'].append(('BPI Challenge. Każda komórka podaje R/L dla jednego logu i zapytania; '
                              'wartość powyżej 1 oznacza krótszy czas LOCAL.', str(path)))

    imports = sorted((r for r in times if r['metric'] == 'import'), key=lambda r: dataset_order(r['dataset']))
    comparison('import', 'import-effect', 'Czas importu',
               [converted(r, dataset_label(datasets[r['dataset']]), scale=1) for r in imports],
               'Import: mediany i kwartyle czasów w sekundach oraz R/L. Każdy punkt obejmuje pięć par importów.', unit='s')

    memory = [r for r in resource_results(selected) if r['phase'] == 'queries']
    memory_groups = defaultdict(list)
    for r in memory:
        memory_groups[datasets[r['dataset']]['series']].append(r)

    def memory_row(label, values, field, aggregate):
        row = dict(label=label)
        for system in ('local', 'reference'):
            observed = [r[field] for r in values if r['system'] == system and r[field] is not None]
            if observed:
                row[system] = aggregate(observed)/2**20
        row['detail'] = f"{len({(r['dataset'], r['query']) for r in values})} bloków"
        return row

    memory_summary = []
    for series, title in series_titles.items():
        if series not in memory_groups:
            continue
        values = memory_groups[series]
        title = {'size-scaling': 'Rozmiar', 'variant-scaling': 'Warianty'}.get(series, title)
        memory_summary += [memory_row(title+' — mediana', values, 'medianBytes', statistics.median),
                           memory_row(title+' — maksimum próbek', values, 'peakBytes', max)]
    memory_note = ('Mediana: mediana median aktywnych okien zapytań. Maksimum: największa próbka w tych oknach. '
                   'Maksimum próbek nie jest gwarantowanym szczytem.')
    comparison('memory', 'resource-memory', 'Pamięć podczas zapytań — podsumowanie serii', memory_summary,
               'Pamięć kontenerów w MiB. Mediana median okien oraz maksimum zaobserwowanych próbek w każdej serii. '
               'Podsumowanie obejmuje wyłącznie zmierzone okna zasobów.', unit='MiB', note=memory_note)
    comparison('memory', 'resource-memory-datasets', 'Pamięć podczas zapytań — poszczególne logi',
               [memory_row(dataset_label(datasets[d]), [r for r in memory if r['dataset'] == d], 'medianBytes', statistics.median)
                for d in sorted({r['dataset'] for r in memory}, key=dataset_order)],
               'Pamięć kontenerów: mediana median aktywnych okien dla każdego logu. '
               'Pełne wyniki poszczególnych zapytań i pokrycie próbkowania znajdują się w dodatku B.',
               unit='MiB', note=memory_note)

    storage = defaultdict(dict)
    for _, _, evidence in selected.values():
        for r in evidence.get('storage', []):
            storage[r['datasetName']][r['system']] = float(r['deltaBytes'])/2**20
    comparison('storage', 'resource-storage', 'Przyrost trwałego rozmiaru danych',
               [dict(label=dataset_label(datasets[d]), **storage[d]) for d in sorted(storage, key=dataset_order)],
               'Przyrost plików danych w MiB oraz R/L. Każdy rozmiar zmierzono na nowych wolumenach.',
               unit='MiB', note='Jeden izolowany pomiar na rozmiar i system. '
               'R/L > 1: mniejszy przyrost LOCAL. Zakres plików nie obejmuje WAL PostgreSQL i dzienników transakcji Neo4j.')
    return figures


def pilot_report(plan, selected):
    times = descriptive_results(selected)
    doc = ['# Pilotaż procedury pomiarowej','',
           '**Dane diagnostyczne. Nie wchodzą do końcowych wyników badania.**','',
           'Pilotaż ukończony i zweryfikowany.' if 'pilot' in selected else
           '**Pilotaż niekompletny: brak zaakceptowanego zadania.**', '',
           'Celem jest sprawdzenie poprawności, działania kolektora i próbkowania oraz przybliżenie kosztu kampanii. '
           f"Logi: {', '.join(plan['pilot']['datasets'])}. "
           f"Wywołania rozgrzewające na przypadek i system: {plan['pilot']['warmups']}; pary LOCAL/REFERENCE: {plan['pilot']['pairs']}. "
           f"Liczba globalnych rund przygotowujących: {plan['pilot']['globalWarmupRounds']}. "
           f"Opis końcowy przewiduje {plan['latency']['pairs']} par, a sześć testów — dwanaście niezależnych przygotowań. "
           'Krótki pilotaż nie potwierdza stabilności czasów po rozgrzewce; jego prognoza nie jest gwarancją czasu wykonania.', '',
           '## Zaobserwowane czasy','',time_table([r for r in times if r['metric']=='query']),'',
           '## Import','',time_table([r for r in times if r['metric']=='setup'], unit='s'),'',
           '## Kontrole przygotowania i próbkowania','']
    probe = plan['pilot'].get('resourceProbe')
    if probe:
        doc += [f"Próbkowanie pamięci i I/O sprawdzane jest tylko dla {probe['dataset']}/{probe['query']}, "
                f"w jednym oknie {plan['resources']['windowSeconds']} s na system. Pozostałe przypadki pilotażu nie powtarzają tego pomiaru.", '']
    if plan['pilot'].get('budgetSeconds'):
        doc += [f"Limit wykonania pilotażu wynosi {plan['pilot']['budgetSeconds']/60:g} minut wraz z przygotowaniem środowiska. "
                'Przekroczenie kończy pilotaż błędem i zachowuje postęp; nie oznacza poprawnego zakończenia.', '']
    warmup,windows=[],[]
    for job,root,e in selected.values():
        for d,q in cells(job, root):
            for system in ('local','reference'):
                warm=sorted([r for r in e['queries'] if r['phase']=='warmup' and r['datasetName']==d and r['queryLabel']==q and r['system']==system],key=lambda r:int(r['run']))
                if len(warm)>=20:
                    a=statistics.median(float(r['seconds']) for r in warm[-20:-10])
                    b=statistics.median(float(r['seconds']) for r in warm[-10:])
                    warmup.append([d,q,system,number(a,1000),number(b,1000),number(b/a)])
        from study_contract import events
        for event in events(root):
            if event['kind']=='resource-window-completed':
                w=event['data']
                windows.append([w['dataset'],w['query'],w['system'],w['samples'],w['completedRequests'],w['status']])
    if warmup:
        doc += ['Dla bloków zawierających co najmniej 20 rozgrzewek tabela porównuje mediany ostatnich dwóch dziesiątek wywołań. '
                'Jest to diagnostyka, nie test stacjonarności.', '',
                table(['Log','Zapytanie','System','Poprzednie 10 [ms]','Ostatnie 10 [ms]','Iloraz'],warmup),'']
    doc += [table(['Log','Zapytanie','System','Próbki','Żądania','Stan'],windows),'',
            'Do zamrożenia planu potrzebny jest komplet poprawnych odpowiedzi, kontroli XES i próbek. '
            'Prognoza czasu korzysta także z ponownego przygotowania stosu bez budowy obrazu. '
            'Prognozę porównuje się z limitem kampanii końcowej, jeśli go ustawiono; limit samego pilotażu jest osobny. '
            'Kompletność plików nie dowodzi stabilności czasów.','']
    doc += ['## Pamięć i I/O', '', resource_tables(selected), '']
    return '\n'.join(doc),[],times


def publish(root,plan,state,jobs,selected):
    source = campaign_resources(root, plan, state)
    directory=root/'reports'/datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
    directory.mkdir(parents=True)
    if state.get('mode') == 'pilot': text,primary,times=pilot_report(plan,selected)
    else:
        figures = make_figures(directory,selected,resource_root=source,plan=plan)
        text,primary,times=build(plan,state,jobs,selected,figures,resource_root=source)
    (directory/'benchmark-report.md').write_text(text,encoding='utf-8')
    write_csv(directory/'primary-comparisons.csv',primary)
    write_csv(directory/'descriptive-comparisons.csv',times)
    write_csv(directory/'resource-observations.csv',resource_results(selected))
    write_csv(directory/'latency-diagnostics.csv',latency_diagnostics(selected))
    write_html(directory,text,directory/'benchmark-report.html')
    def escape(s):
        return str(s).replace('\\','\\textbackslash{}').replace('_','\\_').replace('%','\\%').replace('&','\\&')
    query_codes = {q:f'Z{i+1}' for i,q in enumerate(plan['primary']['queries'])}
    tex=['% Protocol 27. Missing measurements are not replaced by historical numbers.',
         '% '+', '.join(code+' = '+q for q,code in query_codes.items()),
         '\\begingroup\\small\\setlength{\\tabcolsep}{3pt}',
         '\\begin{tabular}{llrrrrrr}', '\\toprule',
         'Log & Zapytanie & n & L [ms] & R [ms] & E & p & p Holma \\\\', '\\midrule']
    for r in primary:
        tex.append(' & '.join(escape(x) for x in [r['dataset'],query_codes[r['query']],r['n'],number(r['local'],1000),number(r['reference'],1000),number(r.get('effect')),number(r.get('rawPValue')),number(r['holmPValue'])])+' \\\\')
    tex += ['\\bottomrule','\\end{tabular}\\par', '\\smallskip',
            '; '.join(escape(code)+': '+escape(OPERATIONS[q][0]) for q,code in query_codes.items())+'.\\par', '\\endgroup']
    (directory/'thesis-primary-results.tex').write_text('\n'.join(tex)+'\n',encoding='utf-8')
    write_json(directory/'report-provenance.json',dict(planSha256=identity(plan),stateSha256=digest(root/'state.json'),
               generatorFiles={name:digest(Path(__file__).with_name(name)) for name in
                   ('study_report.py', 'study_analysis.py', 'study_contract.py', 'report_common.py',
                    'study_figures.py', 'study_html.py')},mode=state.get('mode'),
               complete=plan['status']=='frozen' and state.get('mode')=='final' and len(selected)==len(jobs),
               evidence={j:dict(jobSha256=identity(v[0]),manifestSha256=identity(file_manifest(v[1]))) for j,v in selected.items()}))
    print(directory/'benchmark-report.html')
    return directory
