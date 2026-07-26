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
4. **Parytet środowiska.** Oba systemy działają na tej samej maszynie i **oba
   w kontenerach**, więc droga żądania jest po obu stronach identyczna: klient
   (host) → aplikacja (kontener, jedno przekroczenie granicy Dockera) → baza
   (wewnątrz sieci kontenerów, bez przekroczenia hosta). Przebiegi obu systemów
   nie nakładają się w czasie (pomiar naprzemienny, sekcja 5). Konfiguracja
   pamięci obu baz oraz sterty interpretera jest udokumentowana
   w `environment.json` każdego przebiegu.

   **Limity zasobów nie są narzucane** — kontenery współdzielą zasoby hosta,
   co `environment.json` odnotowuje jako `unlimited` dla każdego z nich.
   Parytet zapewnia symetryczna topologia i pomiar naprzemienny, a nie sztywne
   limity; narzucenie ich wymagałoby podziału budżetu między dwa kontenery
   LOCAL i jeden REFERENCE, co samo w sobie mogłoby zniekształcić wynik
   (np. zbyt mały page cache jednej z baz).
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
- **Dysk:** rozmiar danych trwałych bazy odczytywany wewnątrz kontenera —
  przed importem i po imporcie każdego datasetu. Pomiar **nie obejmuje logów
  transakcyjnych (WAL)** po żadnej ze stron: po stronie Neo4j sumowane są pliki
  store'u w `/data/databases` (bez `/data/transactions`), po stronie PostgreSQL
  używane jest `pg_database_size` (relacje, bez `pg_wal`). Dzięki temu
  porównywany jest trwały rozmiar danych, a nie chwilowy stan dzienników,
  którego rozmiar zależy od cyklu recyklingu segmentów.
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
- **Pamięć operacyjna:** próbkowanie co 1 s w trakcie fazy zapytań, **tą samą
  sondą (`docker stats`) po obu stronach**, więc wartości są porównywalne wprost:
  - REFERENCE: kontener `processm-server` (aplikacja i PostgreSQL razem),
  - LOCAL: suma kontenerów `processm-interpreter` (interpreter) i
    `processm-neo4j` (baza).

  Wcześniejsza wersja protokołu uruchamiała interpreter na hoście i mierzyła go
  jako RSS procesu (składnik `local-jvm`), czyli inną metryką niż REFERENCE.
  Przebiegów zebranych w tamtej konfiguracji nie należy używać do porównania Q3;
  rozpoznaje je obecność składnika `local-jvm` w `memory-results.csv`
  (`scripts/benchmarks/compare-runs.py` sygnalizuje to automatycznie).
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
   czasie), wszystkie na tej samej wersji kodu — zmiana kodu mierzonego
   systemu rozpoczyna nową serię i wcześniejszych przebiegów nie łączy się
   z nowymi.

   **Reguła wyboru przebiegu reprezentatywnego** (aby wybór nie był uznaniowy):
   dla każdego przebiegu liczona jest jedna liczba — mediana ze wszystkich
   median LOCAL par (dataset, zapytanie) z próbek warm; do pracy trafia
   przebieg, którego liczba jest medianą tych wartości między przebiegami
   (przy parzystej liczbie przebiegów — starszy z dwóch środkowych).
   Reguła jest ustalona z góry i nie zależy od tego, który przebieg wypada
   korzystniej dla LOCAL.

   **Raportowana powtarzalność:** dla każdej pary (dataset, zapytanie, system)
   podaje się rozrzut median między przebiegami (min–max oraz iloraz
   max/min); pary, dla których rozrzut przekracza deklarowaną istotność
   (sekcja *Statystyka*), nie mogą być podstawą wniosku o przewadze żadnego
   z systemów. Zarówno wybór przebiegu, jak i tabelę rozrzutu wylicza
   `scripts/benchmarks/compare-runs.py` (zapisuje `repeatability.csv`
   i `repeatability.md` w katalogu przebiegu reprezentatywnego), więc liczby
   podawane w pracy są odtwarzalne z artefaktów, a nie liczone ręcznie:

   ```bash
   python3 scripts/benchmarks/compare-runs.py tmp/benchmark-results/<runA> <runB> <runC>
   ```

   **Warunek ważności przebiegu:** przebieg wolno wykorzystać w pracy tylko,
   gdy `memory-results.csv` zawiera wszystkie trzy składniki
   (`processm-neo4j`, `local-jvm`, `processm-server`). Brak serii `local-jvm`
   oznacza, że strona LOCAL została policzona bez procesu aplikacji, a więc
   zaniżona — taki przebieg jest nieważny dla Q3.

### Statystyka
Dla każdej pary (dataset, zapytanie, system): **mediana**, **IQR**, **p95**,
min/max z 30 repetycji. Deklarowana różnica między systemami uznawana jest za
istotną tylko, gdy przedziały IQR obu systemów są rozłączne — inaczej wynik
opisywany jest jako porównywalny.

Uzasadnienie rygoru: mediana tej samej pary (dataset, zapytanie, system)
potrafi różnić się między przebiegami **na tej samej wersji kodu**, zwłaszcza
przy zapytaniach rzędu pojedynczych milisekund, gdzie stały narzut HTTP i
zmienny stan cache'y dominują nad kosztem samego zapytania. Skalę tego rozrzutu
raportuje się z danych powtarzalności (§5 pkt 6) i **nie podaje się jej jako
stałej z góry** — różnica median mniejsza niż zmierzony rozrzut run-to-run nie
jest odróżnialna od szumu i nie może być podstawą wniosku o przewadze systemu.

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

- oba systemy na jednej maszynie z systemem gospodarza i Dockerem — narzut
  konteneryzacji dotyka obu **symetrycznie** (sekcja 2 pkt 4). Konkretny system
  gospodarza i wersje zapisuje `environment.json` każdego przebiegu — przy
  raportowaniu w pracy należy podać je za tym plikiem, a nie za niniejszym
  dokumentem,

- **zmiana protokołu: interpreter przeniesiony na kontener (nowa wersja
  eksperymentu).** We wcześniejszej konfiguracji interpreter działał na hoście,
  a baza w kontenerze, co dawało asymetrię o nieustalonym kierunku: żądania
  klienta do REFERENCE przekraczały granicę Dockera, a do LOCAL nie — za to
  LOCAL przekraczał ją przy **każdej** rundzie zapytania do Neo4j. Koszt
  jednego przekroczenia zmierzono osobnym testem (identyczny trywialny serwer
  HTTP na hoście i w kontenerze): **+1,08 ms na żądanie** (0,29 ms → 1,37 ms),
  co przy zapytaniach rzędu pojedynczych milisekund jest wielkością istotną.
  Po przeniesieniu interpretera do kontenera obie strony mają tę samą
  topologię. Skutek pomiarowy jest znaczący: mediana czasów LOCAL spadła o ok.
  31%, bo rundy zapytań do bazy nie przekraczają już granicy hosta. Przebiegi
  sprzed i po tej zmianie **nie są porównywalne** i nie wolno ich łączyć
  w jednej serii,

- **rozmiar sterty JVM wyrównany z regułą referencji.** Interpreter dobiera
  `-Xmx` tym samym algorytmem, którego referencja używa dla siebie (połowa
  pamięci dostępnej kontenerowi — `processm.launcher/src/main/docker/`
  `docker-start-processm.sh`), co przy obecnej konfiguracji daje po obu
  stronach identyczne `-Xmx4063240k`; wartość faktycznie użytą przez każdy
  kontener zapisuje `environment.json` (`effectiveJvmHeap`), bo oba systemy
  wyliczają ją dopiero przy starcie. Wcześniejsza, arbitralna konfiguracja
  (`-Xms512m -Xmx2g`) była pod dwoma względami gorsza: dawała referencji
  dwukrotnie wyższy sufit, a wymuszone `-Xms` zawyżało pomiar pamięci
  interpretera o ok. 200 MiB **bez wpływu na przepustowość** (zmierzone
  w spoczynku: 589 MiB z `-Xms512m` wobec 389 MiB bez niego; sam sufit jest
  bez znaczenia — 389 MiB przy `-Xmx2g` wobec 393 MiB przy `-Xmx3968m`).
  Uwaga interpretacyjna: `docker stats` i RSS procesu mierzą tu praktycznie
  to samo (zmierzone równocześnie: 1246 MiB wobec 1213 MiB, różnica 2,7%),
  więc zmiany wyników pamięci między konfiguracjami **nie należy tłumaczyć
  zmianą metryki**,

- **residualna asymetria po stronie baz:** REFERENCE trzyma aplikację
  i PostgreSQL w jednym kontenerze (komunikacja lokalna), podczas gdy LOCAL
  łączy się z Neo4j przez sieć kontenerów. Pozostała różnica działa więc
  na **niekorzyść** LOCAL, co jest bezpiecznym kierunkiem dla wniosków
  o przewadze LOCAL, ale należy ją odnotować,
- REFERENCE mierzony jako całość (aplikacja+PostgreSQL w jednym kontenerze) —
  brak możliwości rozdzielenia składników bez modyfikacji obrazu,
- syntetyczne datasety mają jednostajne rozkłady — kompensowane serią `real`,
- **koszt odczytu metadanych logu po stronie LOCAL rośnie z liczbą atrybutów
  logu.** Log `Hospital_log` (3TU) przechowuje rozbudowane, zagnieżdżone
  statystyki na poziomie logu, co po spłaszczeniu daje ok. 3,4 tys. właściwości
  jednego węzła `Log`; Neo4j czyta je przez łańcuch właściwości węzła, podczas
  gdy PostgreSQL czyta zbiór wierszy. Odpowiada to za istotną część różnicy Q2
  na tym zbiorze i jest ograniczeniem modelu grafowego przy węzłach o tysiącach
  właściwości, a nie właściwością samego zapytania — przy interpretacji wyników
  Q2 dla logów bogatych w metadane należy to jawnie odnotować,
- **sonda sekwencyjna storage może przypisać ostatniemu datasetowi serii
  jednorazową prealokację pliku store.** Ponieważ sonda importuje kolejno bez
  czyszczenia, silnik może w dowolnym kroku powiększyć plik z zapasem; przy
  ostatnim zbiorze nie ma już kolejnych importów, które ten zapas
  zagospodarują, więc jego delta bywa zawyżona.

  **Przypadek potwierdzony pomiarem — `attr-20`.** W sekwencji sonda raportuje
  dla LOCAL przyrost 34 021 376 B, czyli ekspansję **×30,01**, podczas gdy
  pozostałe zbiory mieszczą się w przedziale ×1,2–1,9. Wartość jest w pełni
  powtarzalna (identyczna co do bajta w dwóch niezależnych sesjach sondy), więc
  nie jest szumem. Test kontrolny — import **wyłącznie** `attr-20` na świeżym
  Neo4j — daje przyrost 933 888 B przy pliku XES 1 133 493 B, czyli ekspansję
  **×0,82**. Różnica ×30,01 vs ×0,82 dowodzi, że w sekwencji do tego zbioru
  doliczana jest prealokacja wygenerowana przez wcześniejsze importy, a nie
  koszt jego własnych danych. Potwierdza to również arytmetyka: `attr-20` ma
  czterokrotnie więcej wartości atrybutów niż `attr-5`, co przy ×30 dawałoby
  ok. 1,7 KB na pojedynczą wartość.

  **Wniosek dla pracy:** punktu `attr-20` z serii sekwencyjnej **nie należy
  interpretować jako współczynnika ekspansji**; przy raportowaniu serii
  atrybutowej trzeba albo podać wartość z testu izolowanego, albo wykluczyć
  ostatni punkt serii i to odnotować. Surowego `storage-scaling.csv` nie
  edytujemy (sekcja 6) — korekta należy do warstwy interpretacji.
