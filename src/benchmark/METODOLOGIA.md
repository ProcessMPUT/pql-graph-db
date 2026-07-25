# Metodologia testów wydajnościowych

Dokument opisuje założenia, zakres i protokół pomiarowy porównania wydajności
dwóch interpreterów PQL dla hierarchicznych logów zdarzeń XES:

- **LOCAL** — niniejsza implementacja (Kotlin/Spring Boot + **Neo4j**, model grafowy),
- **REFERENCE** — oryginalny ProcessM (JVM + **PostgreSQL**, model relacyjny),
  uruchamiany z oficjalnego obrazu `processm/processm-server-full`.

Wyniki generowane według tej metodologii trafiają bezpośrednio do pracy
magisterskiej (artefakt `thesis-report.md` + tabele `thesis-tables.tex`).

## 1. Pytania badawcze

1. **Q1 (import):** Jak skaluje się czas importu logu XES względem liczby
   trace'ów, zdarzeń i atrybutów?
2. **Q2 (zapytania):** Jak różnią się opóźnienia zapytań PQL w podziale na
   klasy operacji (okno hierarchii, filtrowanie, sortowanie, grupowanie,
   agregacje, hoisting, atrybuty niestandardowe)?
3. **Q3 (zasobożerność):** Ile zasobów (miejsce na dysku zajmowane przez bazę,
   pamięć operacyjna) potrzebuje każdy z systemów do obsłużenia tych samych
   danych i obciążenia? Szybszy system, który potrzebuje wielokrotnie więcej
   zasobów, nie jest jednoznacznie lepszy — Q3 jest równorzędne z Q1/Q2.
4. **Q4 (poprawność):** Czy mierzone odpowiedzi obu systemów są semantycznie
   równoważne? Pomiar szybkości błędnych odpowiedzi jest bezwartościowy.

## 2. Zasady uczciwości porównania (fair play)

Te założenia są nadrzędne wobec wygody pomiaru; naruszenie któregokolwiek
dyskwalifikuje przebieg:

1. **Zero modyfikacji systemów pod benchmark.** Testowany jest dokładnie ten
   kod, który stanowi produkcyjną wersję każdego z systemów. W szczególności:
   *usunięto* wcześniejszy cache metadanych datastore po stronie LOCAL
   (`Neo4jDataStoreReadCache`), który — przy repetycjach zapytań strzelanych w
   odstępach milisekund — omijał rundy do bazy nieobecne po stronie REFERENCE i
   zawyżał wyniki LOCAL o oszacowane 10–30% na zapytaniach rzędu milisekund.
2. **Czarna skrzynka przez HTTP.** Oba systemy odpytywane są wyłącznie przez
   swoje publiczne API REST, tym samym klientem HTTP, z identycznymi
   parametrami — żaden opcjonalny parametr (`includeTraces`, `includeEvents`)
   nie jest wysyłany, więc oba systemy odpowiadają swoimi domyślnymi, pełnymi
   hierarchiami. Mierzony jest pełny czas end-to-end (kompilacja zapytania +
   wykonanie + serializacja odpowiedzi).
3. **Identyczna polityka rozgrzewki.** Każde zapytanie poprzedzone jest tą samą
   liczbą nierejestrowanych wykonań w obu systemach; wewnętrzne cache silników
   baz (page cache PostgreSQL/Neo4j) traktujemy jako integralną część systemu.
4. **Parytet środowiska.** Oba systemy działają na tej samej maszynie, z
   jawnie zadeklarowanymi limitami zasobów kontenerów; przebiegi obu systemów
   nie nakładają się w czasie (pomiar naprzemienny, sekcja 5). Konfiguracja
   pamięci obu baz jest udokumentowana w `environment.json` każdego przebiegu.
5. **Świeży stan dla pomiarów storage.** Finalne pomiary rozmiaru bazy
   wykonywane są na stacku postawionym od zera (`docker compose down -v`),
   żeby uniknąć fragmentacji i pozostałości po wcześniejszych eksperymentach.
6. **Równoważność odpowiedzi.** Dla każdej pary (dataset, zapytanie) rejestrowane
   są liczności odpowiedzi (logi/trace'y/zdarzenia) obu systemów; rozjazd
   liczności oznacza unieważnienie pomiaru tej pary (status `MISMATCH`),
   a semantyczna równoważność pełnych odpowiedzi jest weryfikowana niezależnie
   raportem kompatybilności (282 przypadki, wymóg: zero problemów ścisłych).

## 3. Zbiory danych

| Seria | Datasety | Co bada |
|---|---|---|
| trace-scaling | 100 / 500 / 2000 / 10000 trace'ów (10 zdarzeń/trace, 5 atrybutów/zdarzenie) | skalowanie po liczbie trace'ów (Q1, Q2) |
| event-scaling | 5 / 10 / 50 / 200 zdarzeń/trace (100 trace'ów) | skalowanie po głębokości trace'a |
| attr-scaling | 1 / 5 / 20 atrybutów/zdarzenie (100×10) | koszt atrybutów niestandardowych |
| real | sample_process, JournalReview, Sepsis, Hospital_log | realne rozkłady atrybutów i długości trace'ów |

Syntetyczne datasety generuje `XesDatasetGenerator` (deterministyczny seed —
identyczne pliki XES dla obu systemów). Profil SMOKE (test dymny) używa
podzbioru; wyniki do pracy pochodzą wyłącznie z profilu FULL.

## 4. Mierzone wielkości

### Q1 — import
Czas ściany (sekundy) importu XES przez HTTP, od wysłania pliku do chwili,
w której zaimportowany log jest **widoczny na liście logów** datastore'u
(odpowiedź 2xx + polling listy co 1 s). Samo 2xx nie wystarcza, bo REFERENCE
importuje asynchronicznie; kwantyzacja pollingu (±1 s) obciąża oba systemy
symetrycznie. Jeden import na świeży datastore w każdym przebiegu benchmarku
(nowy datastore per przebieg, żeby uniknąć deduplikacji); wymagane ≥3 próbki
per dataset pochodzą z ≥3 osobnych przebiegów całego eksperymentu (§5 pkt 6) —
tabela importu pojedynczego przebiegu zawiera więc pojedyncze pomiary.

### Q2 — zapytania
Klasy zapytań (rozszerzone względem pierwotnych 6 o operacje najbardziej
różnicujące model grafowy od relacyjnego):

| Etykieta | PQL (schemat) | Klasa operacji |
|---|---|---|
| hierarchyWindow | `limit l:1, t:10, e:20` | okno hierarchii |
| eventNameFilter | `where e:name is not null limit ...` | filtr po atrybucie standardowym |
| customAttrFilter | `where [e:attr_1] is not null limit ...` | filtr po atrybucie niestandardowym |
| timestampNameOrder | `order by e:timestamp, e:name limit ...` | sortowanie wieloatrybutowe |
| eventNameGroup | `group by e:name order by e:name limit ...` | grupowanie w obrębie trace'a |
| hoistedGroup | `group by ^e:name order by count(t:name) desc` | grupowanie po hoistowanym atrybucie |
| hoistedWhere | `where ^e:name is not null limit ...` | filtr hoistowany (EXISTS/JOIN) |
| timestampAggregates | `select min(e:timestamp), max(e:timestamp), count(e:name)` | agregacje |
| customAttributesProjection | `select [e:attr_1], [e:attr_5] limit ...` | projekcja atrybutów niestandardowych |

Rejestrowane: czas ściany każdej próbki, rozmiar odpowiedzi (bajty), liczności
(logi/trace'y/zdarzenia).

> **DELETE celowo poza zakresem.** PQL ma instrukcję `delete`, ale w oryginalnym
> ProcessM nie jest ona dostępna przez API REST (endpoint zapytań jest tylko do
> odczytu; wykonawca `DBXESDeleter` używany jest wyłącznie w testach). Usuwanie
> logu przez API idzie osobnym zasobem (`DELETE /data-stores/{id}/logs/{logId}`,
> kasowanie całego logu po id) — inna operacja niż zapytanie PQL. Porównanie
> czarnoskrzynkowe DELETE-PQL byłoby więc niesymetryczne, dlatego ta klasa nie
> jest mierzona.

> **Indeksy właściwości Neo4j — zbadane i odrzucone (2026-07-04).** Sonda
> `PROFILE` na pełnym stanie bazy (381 742 zdarzenia) wykazała, że każda klasa
> zapytań z tabeli wyżej jest planowana od kotwicy `NodeUniqueIndexSeek` na
> `Log.logId` (ograniczenie unikalności) z ekspansją po relacjach
> `CONTAINS`/`HAS_EVENT`, a filtry/sortowania/grupowania po atrybutach zdarzeń
> wykonywane są za ekspansją. Po utworzeniu indeksów RANGE na `Event.activity`,
> `Event.timestamp` i `Trace.caseId` planner nie użył żadnego z nich w żadnym
> planie (plany identyczne). Wymuszenie `USING INDEX event:Event(activity)`
> pogorszyło zapytanie filtrujące ok. 8× (skan całego indeksu — 381 742 wpisy
> globalnie, bez zawężenia do loga — plus `NodeHashJoin`; ~1,51 mln db hits vs
> ~0,15 mln przy trawersie). Jedyny kształt, przy którym planner sięga po taki
> indeks naturalnie, to predykat równościowy po atrybucie zdarzenia — na
> rozgrzanym cache bez mierzalnego zysku względem trawersu (18 ms vs 29 ms przy
> ~3 tys. pasujących zdarzeń). Indeksy właściwości podnosiłyby natomiast koszt
> importu (utrzymanie przy każdym wstawieniu), rozmiar dysku i RAM. Decyzja:
> schemat pozostaje przy 4 ograniczeniach unikalności identyfikatorów; brak
> indeksów po atrybutach nie jest zaniedbaniem, lecz decyzją popartą pomiarem.

### Q3 — zasobożerność
- **Dysk:** rozmiar katalogu danych bazy (`du` wewnątrz kontenera, po flushu
  i ustabilizowaniu odczytu) — przed importem i po imporcie każdego datasetu.
  Przed każdym pomiarem REFERENCE jest checkpointowany (`CHECKPOINT;` przez
  psql), żeby nie raportować rozmiaru z nieutrwalonym stanem stron w pamięci.
  Neo4j w wersji community **nie udostępnia ręcznego checkpointu** (procedura
  `CALL db.checkpoint()` nie istnieje), więc rozmiary LOCAL w protokole
  benchmarku odzwierciedlają stan po naturalnych checkpointach silnika.

  **Ograniczenia protokołu benchmarku po stronie LOCAL:** Neo4j nie zmniejsza
  plików store'u po usunięciu danych i reużywa zwolnione strony przy
  kolejnych importach, więc przy protokole import → pomiar → czyszczenie →
  następny dataset delty per-dataset są strukturalnie bliskie zeru (status
  `BELOW_ALLOCATION_GRANULARITY`). Wyniki per-dataset z przebiegu benchmarku
  raportujemy wyłącznie dla REFERENCE.

  **Współczynnik ekspansji (pytanie badawcze 5)** mierzy dedykowana sonda
  `scripts/benchmarks/measure-storage-scaling.py`: świeży stack, sekwencyjny
  import serii skalujących **bez czyszczenia** (żaden store nie maleje, więc
  kolejne delty są przypisywalne konkretnym datasetom), checkpoint wymuszany
  po stronie Neo4j czystym restartem kontenera (checkpoint przy zamknięciu),
  po stronie PostgreSQL `CHECKPOINT;`. Rozmiary porównują dane trwałe bez
  WAL po obu stronach: Neo4j `/data/databases` (bez logów transakcji),
  PostgreSQL `sum(pg_database_size(...))` (bez pg_wal). Wyniki trafiają do
  `storage-scaling.csv` i na wykresy `storage_scaling_*` w raporcie;
  raportowany przyrost oraz współczynnik ekspansji względem rozmiaru XES
  (bajty bazy / bajty XES).

  **Zmiana narzędzia (wersja sondy):** sonda została przeniesiona z PowerShella
  (`measure-storage-scaling.ps1`) na Pythona, żeby uruchamiała się na każdym
  systemie bez dodatkowych zależności. **Metoda pomiaru pozostała niezmieniona:**
  te same polecenia w kontenerach (restart Neo4j i sumowanie `/data/databases`,
  `CHECKPOINT;` i `sum(pg_database_size(...))` po stronie PostgreSQL), ta sama
  lista i kolejność datasetów, ten sam zestaw kolumn `storage-scaling.csv` i ten
  sam format liczb. Zmienił się wyłącznie interpreter uruchamiający te
  polecenia, więc wyniki zebrane obiema wersjami są porównywalne; przy
  raportowaniu w pracy wystarczy odnotować, którą wersją zebrano dany przebieg.
- **Pamięć operacyjna:** próbkowanie co 1 s w trakcie fazy zapytań:
  - REFERENCE: `docker stats` kontenera `processm-server` (obejmuje aplikację
    i PostgreSQL — jeden kontener),
  - LOCAL: suma `docker stats` kontenera `processm-neo4j` + RSS procesu JVM
    aplikacji na hoście.
  Raportowane: mediana i szczyt (peak) w fazie zapytań oraz w spoczynku
  (baseline po starcie, przed importem). Zestawienie sum składników obu
  systemów jest jawnie opisane w raporcie (różna architektura procesów).

### Q4 — poprawność
- roundtrip XES (import → eksport → porównanie kanoniczne z oryginałem),
- parytet liczności odpowiedzi per zapytanie (sekcja 2 pkt 6),
- odwołanie do niezależnego raportu kompatybilności.

#### Kolejność zdarzeń a hoistowane warianty śladu (ustalenie interpretacyjne)

**Rozjazd liczności na zapytaniach `group by ^e:name` wynika z odstępstwa systemu
REFERENCE od specyfikacji PQL, a nie z błędu implementacji LOCAL.** Ustalenie jest
istotne dla interpretacji wyników Q4 i musi być przywołane w pracy.

Zapytania grupujące ślady po hoistowanym atrybucie zdarzenia dzielą je na warianty
procesu według **sekwencji** wartości tego atrybutu, więc wynik zależy wprost od
kolejności zdarzeń wewnątrz śladu. Specyfikacja PQL ustala tę kolejność jednoznacznie:

> By omitting the `order by` clause, the components are returned in the same order
> as provided by the data source.

— *ProcessM PQL specification*, `docs/pql.md`
(https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md).

Domyślną kolejnością jest zatem kolejność **ze źródła danych** (zapisu w pliku XES),
a nie chronologiczna według `time:timestamp`. LOCAL zachowuje kolejność źródłową
(`importOrder` nadawany przy imporcie, zgodny co do znaku z plikiem XES); REFERENCE
porządkuje zdarzenia po znaczniku czasu, a przy **równych** znacznikach — w kolejności
narzuconej przez plan zapytania bazy relacyjnej. Logi rzeczywiste zawierają zdarzenia
o identycznych znacznikach czasu w obrębie śladu, więc systemy budują wtedy różne
sekwencje wariantów, co zmienia podział śladów na grupy i łączną liczbę zdarzeń.

Zjawisko nie jest niedeterminizmem: obie strony są powtarzalne (wielokrotne wykonanie
daje po każdej stronie identyczne liczności), a różnica jest systematyczna. Nie
występuje na zbiorach syntetycznych, które mają ściśle rosnące znaczniki czasu.
Poprawność przechowywania danych potwierdza niezależnie roundtrip XES (`MATCH`, zero
różnic dla wszystkich zbiorów).

Zgodnie z zasadą „nie normalizujemy rozbieżności, by komparator zaraportował `MATCH`”
(sekcja 2) **nie dostosowujemy LOCAL do zachowania REFERENCE** — byłoby to odejście od
specyfikacji. Pary te są unieważniane i wykluczane z tabel czasów, a `thesis-report.md`
generuje dla nich dedykowaną sekcję „Kolejność zdarzeń w wariantach śladu — zgodność ze
specyfikacją PQL”.

## 5. Protokół pomiarowy

1. Świeży stack (`docker compose down -v && up -d`), zapis `environment.json`
   (wersje, limity, sprzęt, konfiguracja pamięci baz).
2. Pomiar pamięci spoczynkowej obu systemów (60 s próbkowania).
3. Dla każdego datasetu: import do REFERENCE i LOCAL (pomiar Q1 + przyrost
   dysku Q3), naprzemiennie: L, R, L, R…
4. Faza zapytań: dla każdej pary (dataset, zapytanie) — rozgrzewka
   (FULL: 3 wykonania), potem repetycje (FULL: **30**) **naprzemiennie
   między systemami** (A,B,A,B…), z próbkowaniem pamięci w tle.
   Pierwsza próbka po imporcie raportowana osobno jako `cold`.
5. Sprzątanie datastore'ów `bench-*`.
6. **Cały eksperyment powtarzany ≥3 razy** (osobne uruchomienia w różnym
   czasie); do pracy trafia przebieg środkowy względem mediany całkowitej,
   a rozrzut między przebiegami raportowany jest jako miara powtarzalności.

### Statystyka
Dla każdej pary (dataset, zapytanie, system): **mediana**, **IQR**, **p95**,
min/max z 30 repetycji. Deklarowana różnica między systemami uznawana jest za
istotną tylko, gdy przedziały IQR obu systemów są rozłączne — inaczej wynik
opisywany jest jako porównywalny. Uzasadnienie rygoru: zmierzony jitter
median run-to-run na tej samej wersji kodu sięga 2× przy zapytaniach ~10 ms.

Wszystkie kwantyle (mediana, Q1/Q3, p95) we wszystkich artefaktach —
`query-summary.csv`, tabelach `thesis-report.md`/`thesis-tables.tex`
i wykresach z nich generowanych — liczone są tym samym estymatorem typu 7
wg Hyndman & Fan (interpolacja liniowa między rangami; domyślny estymator
R/NumPy/Excela), więc ta sama wielkość ma identyczną wartość w tabeli
i na rysunku.

## 6. Artefakty wynikowe

Każdy przebieg zapisuje do `tmp/benchmark-results/<timestamp>/`:
- surowe CSV (import/query/storage/memory/roundtrip/cleanup — bez ręcznej edycji),
- `environment.json` / `environment.md`,
- **`thesis-report.md`** — kompletny raport po polsku: tabele wyników z
  medianą/IQR/p95, współczynniki ekspansji storage, tabele pamięci,
  omówienie metodologii w skrócie, gotowe do wklejenia do pracy,
- **`thesis-tables.tex`** — te same tabele w LaTeX (booktabs, etykiety
  `tab:bench-*`, polskie nagłówki), do bezpośredniego `\input{}` w pracy.

Wykresy generuje `scripts/benchmarks/plot-benchmark-results.py` z surowych CSV.

## 7. Zagrożenia trafności (threats to validity) — do rozdziału pracy

- oba systemy na jednej maszynie z systemem gospodarza (Windows + Docker
  Desktop/WSL2) — narzut wirtualizacji dotyka obu, ale niesymetrycznie
  (LOCAL: JVM na hoście, baza w kontenerze; REFERENCE: całość w kontenerze);
  łagodzone limitami zasobów i pomiarem naprzemiennym,
- REFERENCE mierzony jako całość (aplikacja+PostgreSQL w jednym kontenerze) —
  brak możliwości rozdzielenia składników bez modyfikacji obrazu,
- syntetyczne datasety mają jednostajne rozkłady — kompensowane serią `real`,
- pomiar `du` obejmuje WAL/logi transakcyjne baz — stabilizowany flushem
  i odczytem do ustalenia się wartości.
