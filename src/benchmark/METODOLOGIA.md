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
| trace-scaling | 100 / 500 / 2000 / 10 000 / 50 000 trace'ów (10 zdarzeń/trace, 5 atrybutów/zdarzenie) | skalowanie po liczbie trace'ów (Q1, Q2) |
| event-scaling | 5 / 10 / 50 / 200 / 1000 zdarzeń/trace (100 trace'ów) | skalowanie po głębokości trace'a |
| attribute-scaling | 1 / 2 / 5 / 10 / 20 / 50 atrybutów/zdarzenie (100×10) | koszt atrybutów niestandardowych |
| **shape-scaling** | 1000×10, 500×20, 100×100, 20×500, 10×1000 — **zawsze 10 000 zdarzeń** | kształt logu przy stałej objętości |
| real-validation | sample_process, JournalReview, Sepsis, Hospital_log | realne rozkłady atrybutów i długości trace'ów |

Syntetyczne datasety generuje `XesDatasetGenerator` (deterministyczny seed —
identyczne pliki XES dla obu systemów). Profil SMOKE (test dymny) używa
podzbioru; wyniki do pracy pochodzą wyłącznie z profilu FULL.

**Dlaczego seria `shape-scaling`.** Trzy pierwsze serie nie są ortogonalne: każda
z nich, zmieniając swój parametr, zmienia zarazem łączną objętość danych. Z samych
tych serii nie da się więc oddzielić „kosztu śladu” od „kosztu zdarzenia”. Seria
`shape-scaling` trzyma objętość stałą (10 000 zdarzeń w każdym zbiorze) i zmienia
wyłącznie proporcję ślady : zdarzenia — każda różnica w niej jest czystym efektem
**kształtu** logu, przy zerowym efekcie rozmiaru. To jest eksperyment najsilniej
różnicujący model grafowy od relacyjnego.

**Punkt przecięcia serii = kontrola replikacji.** `trace-100`, `event-10`
i `attr-5` to **ten sam zbiór** (100×10×5) pod trzema nazwami; wygenerowane pliki
różnią się wyłącznie wartością `concept:name` logu. Nie jest to redundancja, tylko
celowy pomiar tej samej wielkości trzy razy w różnych pozycjach sekwencji — patrz
warunek ważności przebiegu w §5.

**Profil SCALING** (`./gradlew runBenchmarkScaling`) dokłada głęboką drabinę
10^4…10^6 zdarzeń do rozdziału o skalowaniu. Powód: przy 10^5 zdarzeń wszystkie
czasy leżą jeszcze na podłodze narzutu transportowego (jednostki milisekund), a
reżim, w którym systemy się różnią, zaczyna się mniej więcej tam, gdzie kończyła
się poprzednia drabina — `real-hospital` (150 291 zdarzeń) daje po stronie
REFERENCE 6 076 ms tam, gdzie `trace-10000` dawał 9 ms.

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

| Etykieta | PQL (schemat) | Klasa operacji | Klasa obciążenia |
|---|---|---|---|
| minimalWindow | `limit l:1, t:1, e:1` | najmniejsze możliwe okno | **podłoga** |
| hierarchyWindow | `limit l:1, t:10, e:20` | okno hierarchii | okno |
| eventNameFilter | `where e:name is not null limit ...` | filtr po atrybucie standardowym | okno |
| customAttrFilter | `where [e:attr_1] is not null limit ...` | filtr po atrybucie niestandardowym | okno |
| timestampNameOrder | `order by e:timestamp, e:name limit ...` | sortowanie wieloatrybutowe | okno |
| eventNameGroup | `group by e:name order by e:name limit ...` | grupowanie w obrębie trace'a | okno |
| hoistedWhere | `where ^e:name is not null limit ...` | filtr hoistowany (EXISTS/JOIN) | okno |
| timestampAggregates | `select min(e:timestamp), max(e:timestamp), count(e:name)` | agregacje w obrębie śladu | okno |
| customAttributesProjection | `select [e:attr_1], [e:attr_5] limit ...` | projekcja atrybutów niestandardowych | okno |
| hoistedGroup | `group by ^e:name order by count(t:name) desc` | warianty procesu | **pełny przebieg** |
| globalEventCount | `select l:name, count(t:name), count(^^e:name) group by l:name ...` | agregat globalny | **pełny przebieg** |
| variantGroupCount | `select count(t:name), count(^e:name) group by ^e:name ...` | warianty z licznościami | **pełny przebieg** |
| absentAttrScan | `where [e:attr_absent_benchmark_probe] is not null limit ...` | filtr bez dopasowań po atrybucie niestandardowym | **pełny przebieg** |
| likeScan | `where e:name like '%zzq%' order by ... limit ...` | filtr `like` bez dopasowań | **pełny przebieg** |

Rejestrowane: czas ściany każdej próbki, rozmiar odpowiedzi (bajty), liczności
(logi/trace'y/zdarzenia).

> **Domyślne limity API i wynikający z nich podział na klasy obciążenia.**
> Oba API stosują domyślne limity hierarchiczne **10 logów / 30 śladów /
> 90 zdarzeń**, którymi ograniczany jest także jawny `limit`. Po stronie LOCAL
> odwzorowuje to `processm.compatibility.default-limits`, wprost powielając
> `LogsService.applyLimits()` systemu ProcessM — porównanie pozostaje więc
> **symetryczne**, co potwierdza parytet liczności odpowiedzi (Q4): dla każdej
> pary obie strony zwracają identyczne `logi/ślady/zdarzenia`.
>
> Konsekwencja dla planu eksperymentu jest jednak zasadnicza: **rozmiar odpowiedzi
> nie zależy od rozmiaru zbioru**. Zapytanie `hierarchyWindow` zwraca dokładnie
> 36 612 bajtów zarówno przy 100, jak i przy 10 000 śladach. Zapytanie, które
> silnik potrafi obsłużyć z ograniczonego okna, kosztuje **O(okno)**, a nie O(n) —
> i usunięcie klauzuli `limit` tego nie zmienia, bo limit domyślny wchodzi wtedy
> na jej miejsce. Dopasowany wykładnik potęgowy takich zapytań wynosi α ≈ 0 przy
> R² ≈ 0, czyli rysunek skalowania pokazuje wyłącznie szum.
>
> Dlatego o tym, czy zapytanie może wykazać skalowanie, decyduje jego **semantyka**,
> nie okno. Klasa **pełny przebieg** obejmuje zapytania, których wynik wymaga
> przejścia całego logu, zanim okno da się w ogóle zastosować: agregaty globalne
> (`^^e:`), grupowanie śladów w warianty (`group by ^e:`) oraz predykaty, które
> **nie mają dopasowań** — te ostatnie zmuszają do pełnego skanu i zwracają wynik
> pusty, więc ich koszt zależy od danych, a rozmiar odpowiedzi nie. Wzorzec ten
> nie jest hipotezą: potwierdza go pomiar `customAttrFilter` na `real-hospital`,
> gdzie atrybut `attr_1` nie występuje — obie strony zwracają `0/0/0`, a czasy
> wynoszą 131,7 ms (LOCAL) wobec 6 076,2 ms (REFERENCE).
>
> **Rysunki skalowania generowane są wyłącznie dla klasy „pełny przebieg”**;
> dla klasy „okno” raportuje się dopasowane α i R² w tabeli, wraz z uwagą, że
> α ≈ 0 wynika z planu eksperymentu, a nie z pomiaru.

> **Podłoga pomiaru.** Zapytanie `minimalWindow` zwraca stały, najmniejszy możliwy
> wynik, więc jego czas jest niezależny od danych i mierzy koszt obecny w każdej
> innej liczbie sekcji Q2: transport HTTP, uwierzytelnienie, parsowanie
> i planowanie. Na zbiorach syntetycznych profilu FULL koszt ten stanowił rzędu
> 30–100 % mierzonej wartości (najtańszy pomiar całego przebiegu: 1,52 ms przy
> medianie wszystkich median 6,09 ms). Podłoga nanoszona jest jako linia
> odniesienia na każdy wykres Q2; różnicę między systemami wolno przypisywać
> silnikowi składowania dopiero po jej odjęciu.

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
- **Dysk:** rozmiar danych bazy odczytywany wewnątrz kontenera — przed importem
  i po imporcie każdego datasetu. Datastore'y benchmarku **nie są czyszczone
  między datasetami** (usuwane są dopiero po całym przebiegu), więc kolejne
  importy narastają na sobie. Zakres plików różni się między systemami:
  po stronie LOCAL sumowane są `/data/databases` **oraz** `/data/transactions`
  (czyli razem z logami transakcji), po stronie REFERENCE mierzy się katalog
  danych PostgreSQL.

  **Dlaczego per-dataset raportujemy wyłącznie dla REFERENCE.** Powodem jest
  brak wymuszonego checkpointu po stronie LOCAL, a nie reużycie stron:
  przed każdym pomiarem REFERENCE jest checkpointowany (`CHECKPOINT;` przez
  psql), natomiast Neo4j w wersji Community **nie udostępnia ręcznego
  checkpointu**.

  Jest to ograniczenie **licencyjne, a nie wersyjne**: procedura
  `db.checkpoint()` istnieje, lecz dokumentacja Neo4j oznacza ją etykietą
  `enterprise-edition`, czyli jest dostępna wyłącznie w edycji Enterprise
  (*Operations Manual → Built-in procedures*, wpis `db.checkpoint()`).
  Etykieta ta występuje **zarówno w dokumentacji linii 5.x, jak i w najnowszym
  wydaniu kalendarzowym (2026.07)**, a `db.checkpoint()` jest w spisie procedur
  wbudowanych jedyną procedurą związaną z checkpointem — aktualizacja Community
  do nowszego wydania nie zmieniłaby więc niczego. Potwierdza to test na
  używanej instancji: Neo4j 5.26.25 Community nie rejestruje żadnej procedury
  zawierającej „checkpoint”, a wywołanie kończy się błędem
  `There is no procedure with the name db.checkpoint`. Przejścia na
  Enterprise świadomie nie dokonujemy: byłby to inny produkt (m.in. dodatkowe
  polityki checkpointowania `continuous` i `volumetric`, niedostępne
  w Community), co zmieniłoby mierzony system i unieważniło zebrane wyniki.
  Zamiast tego sonda wymusza checkpoint **restartem kontenera** — zamknięcie
  silnika wykonuje checkpoint — co jest jedynym sposobem dostępnym w tej edycji. Świeżo zaimportowane
  dane pozostają więc w pamięci i logach transakcji, a pliki store'u rosną
  dopiero przy naturalnym checkpoincie silnika — który wypada w losowym
  momencie serii. Skutek widać w danych: dla LOCAL większość datasetów ma
  status `BELOW_ALLOCATION_GRANULARITY` (delta ≤ 0), a pojedyncze wykazują
  skokowy przyrost rzędu dziesiątek MiB, przypisany temu importowi, w trakcie
  którego checkpoint akurat nastąpił — nie kosztowi jego danych. Takich
  wartości nie wolno interpretować jako współczynnika ekspansji, dlatego
  kolumny LOCAL w tabeli raportu są oznaczone jako nieraportowane.

  **Współczynnik ekspansji (pytanie badawcze 5)** mierzy dedykowana sonda
  `scripts/benchmarks/measure-storage-scaling.py`: świeży stack, sekwencyjny
  import serii skalujących **bez czyszczenia** (żaden store nie maleje, więc
  kolejne delty są przypisywalne konkretnym datasetom), checkpoint wymuszany
  po stronie Neo4j czystym restartem kontenera (checkpoint przy zamknięciu),
  po stronie PostgreSQL `CHECKPOINT;`. Rozmiary porównują dane trwałe bez
  WAL po obu stronach: Neo4j `/data/databases` (bez logów transakcji),
  PostgreSQL `sum(pg_database_size(...))` (bez pg_wal). Wyniki trafiają do
  `storage-scaling.csv` i na wykresy `storage_scaling_*` w raporcie.

  **Ekspansję raportuje się jako koszt krańcowy z regresji, nie jako iloraz
  delta/XES w pojedynczym punkcie.** Silniki prealokują: po stronie REFERENCE
  pierwsze importy dokładają ~13 MiB niezależnie od rozmiaru zbioru
  (13,96 / 13,20 / 13,96 / 13,28 / 13,92 MiB dla pięciu najmniejszych zbiorów).
  Iloraz `delta / xesBytes` jest wtedy hiperbolą `a/n + b`, a nie własnością
  formatu: dla tego samego formatu daje 32,9× przy 100 śladach i 3,8× przy
  10 000, co czyta się jak poprawę wraz z rozmiarem. Dopasowuje się więc

  ```
  delta = a + b · (liczba zdarzeń)
  ```

  i raportuje **b** (koszt krańcowy), **a** (prealokacja) oraz R². Na danych
  profilu FULL model ten daje ekspansję krańcową **1,30× dla LOCAL** wobec
  **3,53× dla REFERENCE** przy R² = 1,00, ze stałą 12,45 MiB po stronie
  REFERENCE — i serie `trace-scaling` oraz `event-scaling` potwierdzają się
  wzajemnie (1,30 vs 1,22 oraz 3,53 vs 3,54).

  **Statusy pomiaru dysku.** Przyrost jest pomiarem wyłącznie wtedy, gdy jest
  **ściśle dodatni**. Trzy tryby niepowodzenia mają osobne statusy, bo mają różne
  przyczyny i różne konsekwencje:

  | Status | Znaczenie |
  |---|---|
  | `OK` | delta > 0 — pomiar przypisywalny, wchodzi do tabel, wykresów i dopasowania |
  | `BELOW_ALLOCATION_GRANULARITY` | delta = 0 — import nie przesunął store'u przez granicę alokacji |
  | `CONTAMINATED_NEGATIVE_DELTA` | delta < 0 — baza **skurczyła się** w trakcie importu (autovacuum, reużycie stron, recykling WAL) |
  | `UNAVAILABLE` | sonda nie zwróciła rozmiaru |

  Rozdzielenie tych statusów nie jest kosmetyką. Traktowanie ujemnej delty jako
  `OK` sprawiało, że wykres prowadził linię przez wartość **−19 746 816 B**
  (REFERENCE/`trace-2000`), którą tabela obok tej samej komórki opisywała jako
  „poniżej granulacji” — ta sama wielkość była w jednym dokumencie
  przedstawiona na dwa sprzeczne sposoby. Obecnie tabele i wykresy stosują
  **jeden wspólny filtr ważności**; pomiar nieważny rysowany jest jako przerwa
  w linii z pustym znacznikiem, nigdy jako liczba.

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
2. **Globalna rozgrzewka** (FULL: 40 rund całego zestawu zapytań na zbiorze
   wyrzucanym, naprzemiennie między systemami), zanim cokolwiek jest zapisywane.

   Uzasadnienie: rozgrzewki per zapytanie nie wystarczają, bo horyzont rozgrzewki
   obejmuje **cały przebieg**, a nie pojedynczą parę. Dowodzą tego zbiory
   replikacyjne (§Zbiory danych): ten sam zbiór 100×10×5 zmierzony jako 1., 6.
   i 10. w sekwencji dawał w przebiegach sprzed tej poprawki mediany różniące się
   do ×2,4 po stronie LOCAL (import: ×10,4), monotonicznie malejące z pozycją.
   Faza ta pochłania również prealokację stron obu baz, dzięki czemu nie jest ona
   doliczana do zbioru, który akurat wypadł pierwszy.
3. Pomiar pamięci spoczynkowej obu systemów (60 s próbkowania).
4. Dla każdego datasetu: import do REFERENCE i LOCAL (pomiar Q1 + przyrost
   dysku Q3), naprzemiennie: L, R, L, R…

   **Kolejność zbiorów jest kontrbalansowana między przebiegami serii.** Protokół
   naprzemienny L,R,L,R usuwa bias kolejności *systemów*, ale nie bias kolejności
   *zbiorów* — a to w nim mieszka reszta rozgrzewki, uderzająca mocniej w LOCAL
   (dwie JVM) niż w jedno-JVM-owy REFERENCE, czyli **przeciwko** badanej
   implementacji. Trzy przebiegi serii uruchamia się więc jako:

   ```bash
   BENCHMARK_DATASET_ORDER=declared ./gradlew runBenchmarkFull
   BENCHMARK_DATASET_ORDER=reversed ./gradlew runBenchmarkFull
   BENCHMARK_DATASET_ORDER=random BENCHMARK_DATASET_ORDER_SEED=20260728 ./gradlew runBenchmarkFull
   ```

   Użyta kolejność i ziarno trafiają do `environment.json`, więc przebieg losowy
   jest odtwarzalny.
5. Faza zapytań: dla każdej pary (dataset, zapytanie) — rozgrzewka
   (FULL: 3 wykonania), potem repetycje (FULL: **30**) **naprzemiennie
   między systemami** (A,B,A,B…), z próbkowaniem pamięci w tle.
   Pierwsza próbka po imporcie raportowana osobno jako `cold`; dla **pierwszego**
   zbioru w sekwencji raportowana jest w osobnej tabeli „pierwsze dotknięcie”
   i **bez ilorazu L/R** — mierzy ładowanie klas i JIT, a nie zimny cache, więc
   iloraz porównywałby rozruch JVM z rozgrzanym już procesem.
6. Sprzątanie datastore'ów `bench-*`.
7. **Cały eksperyment powtarzany ≥3 razy** (osobne uruchomienia w różnym
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

   **Warunki ważności przebiegu.**

   1. *Kompletność składników pamięci.* `memory-results.csv` musi zawierać obie
      strony: aplikację i bazę LOCAL oraz `processm-server`. W konfiguracji
      docelowej (obie aplikacje w kontenerach, §7) są to `processm-interpreter`
      i `processm-neo4j` wobec `processm-server`, wszystkie mierzone tą samą sondą
      `docker stats`. Obecność składnika **`local-jvm`** oznacza konfigurację
      **deweloperską** — interpreter na hoście, mierzony RSS procesu — czyli obie
      strony zmierzone różnymi sondami; taki przebieg jest **nieważny dla Q3**.

   2. *Zgodność replikatów.* Zbiory `trace-100`, `event-10` i `attr-5` to ten sam
      eksperyment (100 śladów × 10 zdarzeń × 5 atrybutów) pod trzema nazwami —
      punkt przecięcia trzech serii skalowania; wygenerowane pliki różnią się
      wyłącznie nazwą logu. Ich rozbieżność jest więc **czystym błędem pomiaru**.
      Jeżeli rozrzut median między replikatami przekracza **×1,25**, przebieg
      zmierzył własną rozgrzewkę, a nie różnicę między systemami, i nie wolno go
      cytować jako dowodu przewagi żadnego z nich. Bramkę liczy i raportuje sam
      generator raportu (sekcja „Kontrola replikacji”), a jej wynik jest podany
      w nagłówku raportu przed jakąkolwiek liczbą.

   Kontrola replikacyjna jest mocniejsza od porównania międzyprzebiegowego z punktu
   wyżej: tamto porównuje ten sam zbiór na tej samej **pozycji w sekwencji**, więc
   nie widzi biasu pozycji; replikaty zmieniają pozycję i właśnie ten bias mierzą.

### Statystyka

Dla każdej pary (dataset, zapytanie, system) raportuje się **medianę** i
**kwartyle Q1–Q3** z 30 repetycji. Różnicę między systemami opisują trzy liczby:

1. **Efekt** — iloraz median podany jako czynnik ≥ 1 wraz z kierunkiem
   (`×2,66 (REFERENCE)`). Jedna kolumna nie miesza wtedy 0,02 z 4,49, a
   najmocniejszy wynik pracy nie ukrywa się pod zapisem „0,02”.
2. **Przedział ufności efektu** — 95% percentylowy bootstrap ilorazu median
   (10 000 losowań, ziarno wyprowadzone z nazwy pary, więc przedział jest
   odtwarzalny z artefaktów).
3. **Test istotności** — Manna–Whitneya (dwustronny, przybliżenie normalne
   z poprawką na wiązania i ciągłość) z korektą **Holma–Bonferroniego** na całą
   rodzinę porównań przebiegu. Bez korekty przy ~130 porównaniach oczekiwać
   należy ~7 fałszywych odkryć przy α = 0,05.

**Werdykt „istotna” wymaga jednocześnie** p < 0,05 po korekcie, przedziału
ufności nieobejmującego 1,00 **oraz** efektu przekraczającego zmierzony błąd
pomiaru (rozrzut replikatów, §5 pkt 7). Efekt spełniający dwa pierwsze warunki,
a nie trzeci, opisywany jest jako **„poniżej błędu pomiaru”** — jest wtedy
realny, ale mniejszy niż to, o ile waha się sam pomiar, więc nie jest dowodem
o systemach.

> **Reguła rozłącznych IQR została wycofana.** IQR mierzy rozrzut *próby*, a nie
> niepewność *mediany*: jego rozłączność nie kontroluje żadnego błędu I rodzaju
> i zależy od `n` tylko przez estymator kwantyla. Na danych profilu FULL reguła
> ta uznawała za istotne **86 % wszystkich par** (113 ze 132), z czego 74 % miało
> efekt poniżej ×2 — czyli poniżej błędu, jaki replikaty wykazują na *identycznych*
> danych. Kryterium odpalające prawie zawsze nie niesie informacji.

**p95 podaje się wyłącznie przy `n ≥ 200`.** Przy `n = 30` kwantyl 0,95 jest
interpolacją między 28. a 29. statystyką pozycyjną — opisuje konkretne losowanie,
nie ogon rozkładu.

**Zastrzeżenie o niezależności.** Repetycje wykonywane są seryjnie na tym samym
rozgrzanym procesie, więc próbki są autoskorelowane; podane p są z tego powodu
optymistyczne i pełnią rolę kryterium pomocniczego. Rozstrzygający jest efekt
zestawiony z błędem pomiaru.

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

- **asymetria budżetu pamięci warstwy bazodanowej.** Obie aplikacje JVM dostają
  tę samą stertę (`-Xmx`, zob. `environment.json`), ale bazy — nie. Neo4j ma
  jawnie ustawiony heap i page cache (`NEO4J_server_memory_heap_max__size`,
  `NEO4J_server_memory_pagecache_size`), podczas gdy PostgreSQL wewnątrz obrazu
  `processm-server-full` działa na wartościach domyślnych (`shared_buffers`
  rzędu 128 MB). Wpływa to nie tylko na Q3, ale i na Q2: przy 1 GB page cache
  Neo4j komplet danych zbioru 10^5 zdarzeń mieści się w pamięci, podczas gdy
  PostgreSQL polega na cache'u systemu gospodarza. **Kierunek obciążenia: na
  korzyść LOCAL.** Zrównanie budżetów wymagałoby ingerencji w obraz REFERENCE,
  co naruszałoby zasadę „zero modyfikacji systemów pod benchmark” (sekcja 2
  pkt 1), dlatego asymetria jest raportowana, a nie usuwana — i musi być podana
  w pracy przy każdym wniosku Q3-pamięć.

- **domyślne limity odpowiedzi ograniczają zakres pytania Q2.** Oba API
  przycinają wynik do 10 logów / 30 śladów / 90 zdarzeń (§Q2). Q2 mierzy więc
  **opóźnienie ograniczonego okna**, a nie przepustowość; pytania o skalowanie
  rozstrzyga wyłącznie klasa zapytań „pełny przebieg”. Jest to ograniczenie
  zakresu wniosku, nie błąd pomiaru — symetria między systemami jest zachowana
  i potwierdzona parytetem liczności (Q4).

- **autokorelacja próbek.** Repetycje wykonywane są seryjnie na tym samym
  rozgrzanym procesie, więc nie są niezależne; podane wartości p są z tego
  powodu optymistyczne (§5, *Statystyka*). Rozstrzygający jest efekt zestawiony
  ze zmierzonym błędem pomiaru, nie sama wartość p.

- **bias pozycji w sekwencji zbiorów.** Rozgrzewka JVM obejmuje cały przebieg,
  nie pojedyncze zapytanie, a protokół naprzemienny L,R,L,R jej nie neutralizuje.
  Przeciwdziałają temu globalna rozgrzewka (§5 pkt 2) i kontrbalansowanie
  kolejności zbiorów między przebiegami (§5 pkt 4); skalę residualnego biasu
  mierzy kontrola replikacji (§5 pkt 7) i jest ona raportowana w każdym raporcie.
