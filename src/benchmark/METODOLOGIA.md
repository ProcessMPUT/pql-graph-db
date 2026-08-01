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

   **Pamięć jest limitowana na poziomie całej aplikacji**, nie pojedynczego JVM:
   `docker-compose.benchmark.yml` przydziela LOCAL łącznie 3328 MiB
   (interpreter 1280 MiB + Neo4j 2048 MiB) oraz REFERENCE 3328 MiB w jego
   pojedynczym kontenerze aplikacja+PostgreSQL. `memswap_limit` jest równy
   `mem_limit`, więc swap nie rozszerza żadnego budżetu. Przy zarejestrowanym
   budżecie VM 7936 MiB pozostaje 1280 MiB (16,1%) dla systemu VM i Dockera.
   Jest to jawna decyzja eksperymentalna: LOCAL musi podzielić swój równy budżet
   między dwie usługi, bo taki jest koszt jego architektury. Wcześniejszy wariant
   `unlimited` nie był neutralny — trzy JVM reklamowały łącznie ponad 14 GiB sterty
   w VM 7,75 GiB i pełny blok `20260801-145312` zakończył się ubiciem JVM
   REFERENCE przez OOM. Każdy przebieg zapisuje faktyczne limity, efektywne sterty,
   `OOMKilled`, `RestartCount` i obecność procesów JVM; OOM, restart lub brak JVM
   unieważnia blok.
5. **Świeży stan dla pomiarów storage.** Finalne pomiary rozmiaru bazy
   wykonywane są na stacku postawionym od zera (`docker compose down -v`),
   żeby uniknąć fragmentacji i pozostałości po wcześniejszych eksperymentach.
6. **Równoważność odpowiedzi.** Dla każdej pary (dataset, zapytanie) ostatnie
   odpowiedzi warm obu systemów są porównywane dwustopniowo: najpierw liczności
   logów/trace'ów/zdarzeń, następnie pełna struktura XES-JSON tym samym ścisłym
   komparatorem co raport kompatybilności. Dowolny rozjazd unieważnia wszystkie
   próbki czasowe tej pary (status `MISMATCH`). Raport kompatybilności pozostaje
   niezależnym, szerszym warunkiem publikacji (wymóg: zero problemów ścisłych).

## 3. Zbiory danych

| Seria | Datasety | Co bada |
|---|---|---|
| trace-scaling | 100 / 500 / 2000 / 10 000 / 20 000 trace'ów (10 zdarzeń/trace, 5 atrybutów/zdarzenie) | skalowanie po liczbie trace'ów (Q1, Q2) |
| event-scaling | 5 / 10 / 50 / 200 / 1000 zdarzeń/trace (100 trace'ów) | skalowanie po głębokości trace'a |
| attribute-scaling | 1 / 2 / 5 / 10 / 20 / 50 atrybutów/zdarzenie (100×10) | koszt atrybutów niestandardowych |
| **shape-scaling** | 1000×10, 500×20, 100×100, 20×500, 10×1000 — **zawsze 10 000 zdarzeń** | kształt logu przy stałej objętości |
| real-validation | sample_process, JournalReview, Sepsis, Hospital_log | realne rozkłady atrybutów i długości trace'ów |

Syntetyczne datasety generuje `XesDatasetGenerator` (deterministyczny seed —
identyczne pliki XES dla obu systemów). Profil SMOKE (test dymny) używa
podzbioru; wyniki do pracy pochodzą wyłącznie z profilu FULL.

Górny punkt osi trace wynosi 20 000, ponieważ oficjalny obraz REFERENCE odrzuca
plik 50 000×10×5 odpowiedzią HTTP 400 „The file is not a valid XES file” także
w izolowanej próbie na pustym stosie (`20260801-122052`, bez OOM). Plik przechodzi
lokalny parser i ma poprawną strukturę XML, ale punkt spoza wspólnej dziedziny
importu obu aplikacji nie może być użyty do porównania czasu. Ograniczenie jest
cechą badanego zestawu aplikacja+wersja obrazu, a nie podstawą do przypisania
REFERENCE czasu zerowego lub do ekstrapolacji wyniku LOCAL.

**Dlaczego seria `shape-scaling`.** Trzy pierwsze serie nie są ortogonalne: każda
z nich, zmieniając swój parametr, zmienia zarazem łączną objętość danych. Z samych
tych serii nie da się więc oddzielić „kosztu śladu” od „kosztu zdarzenia”. Seria
`shape-scaling` trzyma stałe liczbę zdarzeń (10 000) i liczbę atrybutów na
zdarzenie, a zmienia proporcję ślady : zdarzenia. Nie jest to eksperyment przy
identycznym rozmiarze bajtowym: liczba obiektów trace i rozmiar XES zmieniają się
wraz z kształtem. Seria pozwala więc opisać zależność od kształtu przy stałej
liczbie zdarzeń, ale nie daje podstaw do nazwania różnicy „czystym efektem
modelu grafowego” bez dodatkowego eksperymentu.

**Punkt przecięcia serii = kontrola replikacji.** `trace-100`, `event-10`
i `attr-5` to **ten sam zbiór** (100×10×5) pod trzema nazwami; wygenerowane pliki
różnią się wyłącznie wartością `concept:name` logu. Nie jest to redundancja, tylko
celowy pomiar tej samej wielkości trzy razy w różnych pozycjach sekwencji — patrz
warunek ważności przebiegu w §5.

**Profil SCALING** (`./gradlew runBenchmarkScaling`) jest opcjonalną sondą
diagnostyczną dla drabiny 10^4…2×10^5 zdarzeń wspieranej przez oba systemy. Nie
zawiera pełnej macierzy FULL ani
kontroli replikacyjnej, dlatego jego pojedynczego przebiegu nie wolno łączyć z
serią FULL ani używać jako samodzielnego dowodu przewagi. Podstawowy raport pracy
opiera się na trzech kontrbalansowanych przebiegach profilu FULL.

## 4. Mierzone wielkości

### Q1 — import
Czas ściany (sekundy) importu XES przez HTTP, od wysłania pliku do chwili,
w której zaimportowany log jest **widoczny na liście logów** datastore'u
(odpowiedź 2xx + polling listy co 1 s). Samo 2xx nie wystarcza, bo REFERENCE
importuje asynchronicznie. Polling dodaje nieujemne opóźnienie mniejsze niż 1 s;
nie należy więc nazywać wyniku wyłącznie czasem uploadu ani traktować błędu jako
symetrycznego ±1 s. Jeden import na świeży datastore w każdym przebiegu benchmarku
(nowy datastore per przebieg, żeby uniknąć deduplikacji); wymagane ≥3 próbki
per dataset pochodzą z ≥3 osobnych przebiegów całego eksperymentu (§5 pkt 6) —
tabela importu pojedynczego przebiegu zawiera więc pojedyncze pomiary.

### Q2 — zapytania
Klasy zapytań (rozszerzone względem pierwotnych 6 o operacje obejmujące pełne
przebiegi danych i dodatkowe konstrukcje PQL):

| Etykieta | PQL (schemat) | Klasa operacji | Klasa obciążenia | Serie interpretowalne dla skalowania |
|---|---|---|---|---|
| minimalWindow | `limit l:1, t:1, e:1` | najmniejsze możliwe okno | punkt odniesienia | — |
| hierarchyWindow | `limit l:1, t:10, e:20` | okno hierarchii | okno | — |
| eventNameFilter | `where e:name is not null limit ...` | filtr po atrybucie standardowym | okno | — |
| customAttrFilter | `where [e:attr_1] is not null limit ...` | filtr po atrybucie niestandardowym | okno | — |
| standardAttributesOrder | `order by e:timestamp, e:name, e:org:group, e:org:resource limit ...` | sortowanie po standardowych atrybutach XES | okno | event, shape |
| eventNameGroup | `group by e:name order by e:name limit ...` | grupowanie w obrębie trace'a | okno | event, shape |
| hoistedWhere | `where ^e:name is not null limit ...` | filtr hoistowany (EXISTS/JOIN) | okno | — |
| timestampAggregates | `select min(e:timestamp), max(e:timestamp), count(e:name)` | agregacje w obrębie śladu | okno | event, shape |
| customAttributesProjection | `select [e:attr_1], [e:attr_5] limit ...` | projekcja atrybutów niestandardowych | okno | — |
| hoistedGroup | `group by ^e:name order by count(t:name) desc` | warianty procesu | **zależne od danych** | trace, event, shape |
| globalEventCount | `select l:name, count(t:name), count(^^e:name) group by l:name ...` | agregat globalny | **zależne od danych** | trace, event, shape |
| variantGroupCount | `select count(t:name), count(^e:name) group by ^e:name ...` | warianty z licznościami | **zależne od danych** | trace, event, shape |
| absentAttrScan | `where [e:attr_absent_benchmark_probe] is not null limit ...` | filtr bez dopasowań po atrybucie niestandardowym | **zależne od danych** | trace, event, attribute, shape |
| likeScan | `where e:name like '%zzq%' order by ... limit ...` | filtr `like` bez dopasowań | **zależne od danych** | trace, event, shape |

Zapytanie `standardAttributesOrder` używa czterech ogólnych kluczy zamiast tylko
timestampu i nazwy. W logach rzeczywistych para `(timestamp, name)` nie zawsze
jest unikatowa; pozostawienie takiego remisu powodowałoby, że ścisłe porównanie
tablic odpowiedzi sprawdza techniczny tie-break planu bazy, a nie semantykę ani
koszt zadeklarowanego sortowania. Dodatkowe klucze są standardowymi atrybutami
XES, a nie polem dobranym do jednego fixture'u. Jeżeli również wszystkie cztery
wartości będą równe i systemy zwrócą inny porządek, bramka Q4 nadal jawnie
unieważni tę parę — harness nie normalizuje kolejności po pomiarze.

Rejestrowane: czas ściany każdej próbki, rozmiar odpowiedzi (bajty), liczności
(logi/trace'y/zdarzenia).

> **Domyślne limity API i wynikający z nich podział na klasy obciążenia.**
> Oba API stosują domyślne limity hierarchiczne **10 logów / 30 śladów /
> 90 zdarzeń**, którymi ograniczany jest także jawny `limit`. Po stronie LOCAL
> odwzorowuje to `processm.compatibility.default-limits`, wprost powielając
> `LogsService.applyLimits()` systemu ProcessM — polityka okna jest więc
> **symetryczna**. Równość faktycznych odpowiedzi nie jest założeniem: dla każdej
> pary weryfikuje ją osobno bramka Q4, a rozjazd unieważnia pomiar czasu.
>
> Konsekwencja dla planu eksperymentu jest jednak zasadnicza: **rozmiar odpowiedzi
> nie zależy od rozmiaru zbioru**. Nie wynika z tego automatycznie stały koszt.
> `ORDER BY`, `GROUP BY` i agregacja mogą przeczytać wszystkie elementy niższego
> zakresu, zanim zostanie nałożony jego limit. Przykładowo agregaty timestampów są
> ograniczone liczbą zwracanych śladów, ale wewnątrz każdego z nich obejmują wszystkie
> zdarzenia. Skalowanie jest więc własnością pary **zapytanie–zmieniana oś**, a nie
> jednej etykiety workloadu.
>
> Klasa **zależne od danych** obejmuje zapytania, których wynik wymaga ustalenia
> własności wykraczającej poza zwracane okno: agregaty globalne (`^^e:`), grupowanie
> śladów w warianty (`group by ^e:`) oraz predykaty bez dopasowań. Nie przesądza to
> fizycznego planu: indeks może pozwolić jednemu systemowi wykazać brak wzrostu czasu.
> Nie oznacza też, że każda oś jest relewantna: zwiększenie liczby nieodczytywanych
> atrybutów nie stanowi hipotezy skalowania dla zapytania operującego wyłącznie na
> `e:name`.
>
> Pole `scalingSeries` w `benchmark-queries.json` deklaruje te pary przed pomiarem.
> Rysunki i wykładniki α powstają wyłącznie dla zadeklarowanych par. Dla serii
> trace/event/attribute α dopasowuje się osobno w każdym pełnym bloku; finalny raport
> nazywa wzrost stabilnym tylko przy dodatnim nachyleniu i R² ≥ 0,30 we wszystkich
> blokach oraz zmianie między końcami osi większej niż błąd replikacyjny zapytania.
> Seria shape
> ma stałą liczbę zdarzeń i jest interpretowana punktowo, bez wymuszania modelu
> potęgowego. Trzy bloki nie wystarczają do testu różnicy wykładników między systemami.

> **Najmniejsze obserwowane okno.** `minimalWindow` jest opisowym punktem
> odniesienia end-to-end. Nadal odczytuje i serializuje metadane logu, a jego
> ścieżka wykonania nie musi być wspólna z innymi zapytaniami. Nie jest zatem
> estymatą stałego narzutu transportu/planowania i nie wolno odejmować jego czasu
> od pozostałych pomiarów ani przypisywać różnicy po odjęciu samej bazie danych.
> Linia na wykresach pomaga jedynie rozpoznać, które wyniki są tego samego rzędu
> wielkości co najlżejsze zapytanie całej aplikacji.

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
- **Dysk:** finalny wynik pochodzi wyłącznie z
  `scripts/benchmarks/measure-storage-scaling.py`. Każdy dataset jest mierzony
  na osobnym stacku utworzonym od zera: usunięcie wolumenów, start obu systemów
  bez danych seedujących, pomiar bazowy, import tych samych bajtów XES, pomiar
  końcowy. Neo4j Community jest checkpointowany przez czysty restart kontenera,
  PostgreSQL przez `CHECKPOINT;`. Porównywany zakres to trwałe dane bez logów
  transakcyjnych: Neo4j `/data/databases` i suma `pg_database_size` PostgreSQL.

  Izolacja punktów eliminuje podstawowy confounder starej sondy sekwencyjnej:
  skok prealokacji lub recykling stron po wcześniejszym imporcie nie może zostać
  przypisany kolejnemu datasetowi. Plik `storage-scaling.csv` musi mieć dla
  każdego wiersza `measurementMode=isolated-fresh-stack`; generator odrzuca
  starsze dane sekwencyjne. Przyrost nieściśle dodatni przerywa sondę zamiast
  być zamieniany w zero albo używany w regresji.
  Każdy punkt zapisuje także commit Git oraz dokładne image ID interpretera, Neo4j
  i REFERENCE; commit i obrazy muszą być identyczne z blokiem kotwiczącym, inaczej
  sonda odmawia startu albo punkt jest odrzucany.

  Dla serii trace/event/attribute dopasowuje się osobno dla każdego systemu:

  ```text
  deltaBytes = a + b · xesBytes
  ```

  `b` jest krańcowym współczynnikiem ekspansji [B danych trwałych / B XES], `a`
  opisuje stały koszt utworzenia datastore'u i alokacji, a R² informuje o jakości
  dopasowania. Serii shape nie redukuje się do jednego współczynnika: ma stałą
  liczbę zdarzeń, lecz zmienne liczby trace'ów i bajtów XES, więc raportuje się
  jej punkty bez wniosku przyczynowego o samym modelu danych. Pomiary
  `storage-results.csv` z głównego benchmarku obejmują narastający stan, różne
  zakresy plików i brak symetrycznego checkpointu; pozostają diagnostycznym
  załącznikiem i nie służą do odpowiedzi na Q3-dysk.

- **Pamięć operacyjna:** sonda dąży do okresu 1 s w fazie zapytań, lecz
  `docker stats --no-stream` ma własne, zmienne opóźnienie. Autorytatywne są
  znaczniki czasu w `memory-results.csv`, a nie deklaracja „dokładnie co 1 s”.
  Obie strony mierzone są tą samą sondą:
  - REFERENCE: kontener `processm-server` (aplikacja i PostgreSQL razem),
  - LOCAL: suma kontenerów `processm-interpreter` (interpreter) i
    `processm-neo4j` (baza).

  Sumy systemowe tworzy się **per wspólny znacznik czasu**, a dopiero potem
  oblicza medianę i peak. Suma median składników byłaby inną wielkością i mogłaby
  zaniżać lub zawyżać typową pamięć całego systemu. Obecność starego składnika
  `local-jvm`, brak któregoś kontenera albo brak `local-total`/`reference-total`
  dyskwalifikuje przebieg jako dowód Q3. Pojedynczy blok pozostaje opisowy;
  kierunek różnicy pamięci musi być taki sam we wszystkich co najmniej trzech
  pełnych przebiegach.

### Q4 — poprawność
- roundtrip XES (import → eksport → porównanie kanoniczne z oryginałem),
- parytet liczności i ścisła równoważność semantyczna odpowiedzi per zapytanie
  (sekcja 2 pkt 6),
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

1. Przed pierwszym blokiem serii
   `scripts/benchmarks/prepare-benchmark-stack.py --confirm-destroy-volumes`
   buduje `bootJar` z bieżącego źródła i obraz LOCAL oznaczony commitem, a
   następnie usuwa wolumeny. Przed kolejnymi blokami ten sam skrypt jest
   wywoływany z `--reuse-local-image-id sha256:...`, gdzie wartością jest
   dokładne image ID z pierwszego `environment.json`. Tryb ponownego użycia
   odmawia startu, jeżeli tag wskazuje inny obraz, etykieta rewizji obrazu nie
   odpowiada bieżącemu commitowi albo drzewo Git jest brudne. Dzięki temu każdy
   blok ma świeże wolumeny, lecz żaden nie dostaje przypadkowo innego,
   niereprodukowalnego bitowo obrazu z kolejnego builda. Skrypt następnie
   uruchamia wyłącznie mierzone usługi z nakładką
   `docker-compose.benchmark.yml`, weryfikuje równy skończony budżet pamięci bez
   swapu i tworzy konto REFERENCE
   **bez** importu fixture'ów kompatybilności. Skrypt oraz runner wymagają, by
   oba API wystawiały zero datastore'ów. Skrypt tworzy jednorazowy marker,
   który runner zużywa i archiwizuje jako `stack-preparation.json`; agregator
   sprawdza świeże wolumeny, zerowe liczności API oraz zgodność commita i
   dokładnych image ID z `environment.json`. Zwykłe `docker compose up`, które
   uruchamia seed tylko po stronie REFERENCE, nie przygotowuje ważnego pomiaru.
2. Runner zapisuje `environment.json`: host, budżet Docker VM, dokładne image ID
   kontenerów, efektywne ustawienia pamięci, commit i stan dirty Git oraz hash
   konfiguracji datasetów i zapytań. Serię wolno łączyć wyłącznie przy zgodnym
   fingerprintcie, commicie, obrazach i budżecie Docker VM; drzewo musi być czyste.
3. **Globalna rozgrzewka** (FULL: 200 rund pełnego zestawu zapytań na wyrzucanym
   zbiorze 100×10×5) odbywa się przed rejestrowaniem próbek. Kolejność systemów
   zmienia się w każdej rundzie. Błąd importu, zapytania albo semantyczny mismatch
   przerywa przebieg — rozgrzewka nie może ukrywać niesprawnego workloadu.
   Datastore rozgrzewkowy pozostaje obecny do końcowego sprzątania w obu systemach;
   dzięki temu baseline i pomiary nie obejmują asymetrycznego kosztu kasowania ani
   ponownego wychłodzenia, ale Q3-pamięć dotyczy jawnie tego rozgrzanego stanu.
4. Po rozgrzewce zbierany jest 60-sekundowy baseline pamięci. Okno bezczynności
   celowo wychładza procesy, dlatego bezpośrednio po nim oba systemy przechodzą
   nierejestrowany cykl: utworzenie świeżego datastore'u → import wyrzucanego
   zbioru 100×10×5 → 200 rund aktywacyjnych → usunięcie datastore'u. Kolejność
   systemów w zapytaniach jest naprzemienna. Sonda pamięci nie ma wtedy aktywnej
   fazy: cykl nie zanieczyszcza ani baseline'u `idle`, ani serii `queries`.
   Datastore globalnej rozgrzewki pozostaje obecny, więc stan tła pamięci przed
   i po cyklu ma tę samą strukturę. Cykl płaci nie tylko powrót procesów z
   60-sekundowej bezczynności, ale też pierwszą ścieżkę importu i kasowania;
   pierwszy mierzony dataset wchodzi dzięki temu w taki sam stan lifecycle'u jak
   kolejne. Następnie każdy
   dataset trafia do osobnego datastore'u w obu systemach. Dla jednego datasetu
   wykonywany jest cały blok: import obu stron → zapytania → roundtrip LOCAL →
   usunięcie obu datastore'ów; dopiero potem dopuszczany jest następny dataset.
   Dzięki temu czas i pamięć zapytania nie zależą od obecności 24 niepowiązanych
   baz, a oba systemy konkurują o budżet Docker VM tylko z tym samym aktualnym
   zbiorem. System rozpoczynający import zmienia się co dataset (LOCAL→REFERENCE,
   potem REFERENCE→LOCAL). Czas Q1 obejmuje upload i oczekiwanie na widoczność
   logu. Przyrosty dysku z tej fazy są diagnostyczne, nie są finalną sondą
   Q3-dysk. Blok `reversed` odwraca także
   system rozpoczynający import konkretnego datasetu względem `declared` (dla
   nieparzystej liczby datasetów wymaga to jawnego przesunięcia parzystości).
5. **Kolejność datasetów jest kontrbalansowana między pełnymi przebiegami:**

   ```bash
   BENCHMARK_DATASET_ORDER=declared ./gradlew runBenchmarkFull
   BENCHMARK_DATASET_ORDER=reversed ./gradlew runBenchmarkFull
   BENCHMARK_DATASET_ORDER=random BENCHMARK_DATASET_ORDER_SEED=20260728 ./gradlew runBenchmarkFull
   ```

   Użyta kolejność i ziarno trafiają do `environment.json`; agregator wymaga
   dla bloku `random` z góry ustalonego ziarna `20260728` (jest ono również
   wartością domyślną runnera). Są to trzy bloki
   eksperymentu, nie trzy warianty, spośród których wybiera się najkorzystniejszy.
6. Dla każdej pary (dataset, zapytanie) wykonuje się po jednym opisowym pomiarze
   `cold`, trzy nieraportowane rozgrzewki i 30 repetycji warm. W każdym numerze
   repetycji oba systemy tworzą przyległy blok; kolejność zmienia się AB/BA, a
   system rozpoczynający pierwszą parę zmienia się między parami. Błąd HTTP
   przerywa przebieg. Liczności odpowiedzi są porównywane w każdej repetycji warm,
   a pełna semantyka XES-JSON — dla ostatniej odpowiedzi warm; mismatch zachowuje
   surowe czasy, ale wyklucza parę z porównania wydajności.
7. Runner usuwa parę datastore'ów po zakończeniu każdego datasetu i zawsze próbuje
   usunąć pozostałe datastore'y `bench-*` także po błędzie. Każda próba trafia do
   `cleanup-results.csv`. Po każdym pełnym bloku stack i tak jest odtwarzany od
   zera, aby drugi blok nie dziedziczył stron, WAL ani cache danych z pierwszego.
   Po sprzątaniu runner ponownie odczytuje stan wszystkich kontenerów i obecność
   procesów JVM; działający PID 1 lub wadliwy healthcheck nie zastępuje tej kontroli.
8. **Finalny eksperyment zawiera co najmniej trzy ważne bloki FULL** na tej samej
   wersji. Bieżący kontrakt zapisu ma `benchmarkProtocolVersion=10`; wersje poniżej
   2 nie mają pełnej kontroli semantycznej, a wersja 2 kumuluje wszystkie datasety
   w pamięci baz i może mierzyć presję wspólnej VM zamiast bieżącego workloadu.
   Wersja 3 izoluje datasety, lecz nie kompensuje wychłodzenia przez pomiar `idle`;
   wersja 4 aktywuje tylko zapytania na datastore utworzonym przed baseline'em,
   pozostawiając pierwszy świeży import w pozycji wyjątkowej; wersja 5 dodaje
   pełny cykl świeżego importu i kasowania, wersja 6 precyzuje odporną bramkę
   szerokiej niestabilności Q2 opisaną niżej, wersja 7 wprowadza skończony,
   równy budżet pamięci całych aplikacji, końcową kontrolę OOM/JVM i 200 rund
   globalnej rozgrzewki, a wersja 8 podnosi limit pamięci pojedynczej transakcji
   Neo4j z 256 do 512 MiB bez zmiany budżetu cgroup całej aplikacji LOCAL;
   wersja 9 zwiększa aktywację po 60-sekundowym oknie idle z 10 do 200 rund, a
   wersja 10 buduje LOCAL raz na serię i przy każdym kolejnym świeżym stacku
   wymaga ponownego użycia dokładnie tego samego obrazu.
   Żadna z wcześniejszych wersji nie jest finalnym dowodem. `compare-runs.py` waliduje kompletność macierzy, 30 repetycji,
   roundtrip, sprzątanie, pamięć, środowisko, fingerprint i trzy wymagane kolejności. Skrypt
   nie wybiera „reprezentatywnego wyniku”: wszystkie bloki są jednostkami dowodu.
   Jeden środkowy blok jest wskazywany jedynie jako kotwica dla szczegółowych
   tabel i wykresów, by nie powielać identycznej struktury raportu.

   Dla każdej pary skrypt podaje iloraz REFERENCE/LOCAL w każdym bloku, jego zakres
   i medianę. Kierunek przewagi jest wspierany tylko wtedy, gdy jest jednakowy we
   wszystkich blokach, a **najmniejszy** efekt przekracza największy błąd
   replikacyjny danego zapytania. Wyniki trafiają do `series-comparison.csv`,
   `series-import.csv`, `repeatability.csv`, `series-scaling.csv`,
   `thesis-report-series.md` oraz `thesis-tables-series.tex` (tabele
   międzyblokowe; pakiety `booktabs` i `longtable`):

   ```bash
   python3 scripts/benchmarks/compare-runs.py \
     tmp/benchmark-results/<declared> \
     tmp/benchmark-results/<reversed> \
     tmp/benchmark-results/<random>
   ```

   Przy trzech blokach reguła ta jest kryterium **powtarzalności na
   zarejestrowanym środowisku**, nie testem istotności dla populacji komputerów.
   Wnioski są warunkowe względem zapisanych wersji aplikacji, obrazów, limitów
   Docker VM, hosta i workloadu; nie są automatycznie uogólniane na inną
   konfigurację sprzętową lub inne logi.

   **Bramka replikacyjna.** `trace-100`, `event-10` i `attr-5` opisują ten sam
   eksperyment 100×10×5 pod trzema nazwami. Dla każdej pary
   `(zapytanie, system)` wylicza się rozrzut max/min. Jeżeli **górny kwartyl**
   tych rozrzutów przekracza **×1,25**, blok jest nieważny dla Q2. Maksimum nie
   znika: najgorszy rozrzut danego zapytania (po obu systemach) jest jego
   indywidualnym progiem efektu. Q1 ma osobną bramkę maksimum o tym samym progu,
   ponieważ jest pojedynczym pomiarem
   kwantowanym pollingiem; jej przekroczenie pozostawia Q1 nierozstrzygnięte, ale
   nie unieważnia zapytań, które mają własną kontrolę replikacji. Próg ×1,25 nie
   jest progiem efektu: do oceny praktycznej konkretnego zapytania używa się jego
   rzeczywiście zmierzonego, najbardziej konserwatywnego rozrzutu replikatów z
   całej serii; Q1 używa analogicznego rozrzutu replikatów importu i nie publikuje
   przewagi, gdy jego osobna bramka jakości nie przejdzie.

   Wartość ×1,25 i statystyka górnego kwartyla są **operacyjnym kryterium jakości
   ustalonym przed serią finalną**, a nie poziomem istotności ani minimalnym
   efektem badawczym. Maksimum z 28 współczynników (14 zapytań × 2 systemy) jest
   statystyką ekstremalną, szczególnie niestabilną dla komórek 2–8 ms: pojedyncza
   różnica poniżej 2 ms może dać iloraz ×1,4. Użycie go jednocześnie jako bramki
   całego bloku i per-zapytaniowego flooru liczyłoby ten sam lokalny problem
   podwójnie. Górny kwartyl odrzuca **szeroki** dryf protokołu, a maksimum nadal
   konserwatywnie ogranicza wniosek o konkretnym zapytaniu. W diagnostyce przed
   serią finalną protokół v4 dawał Q3 ×1,26 (max ×1,40), natomiast pełny
   cykl aktywacyjny v5 obniżył Q3 do ×1,20 (max ×1,39); brak globalnej rozgrzewki
   dawał maksimum do ×2,37 (Q2) i ×10,43 (Q1). Były to jednak diagnostyki
   zawierające wyłącznie trzy sąsiadujące replikaty. Pierwszy kompletny blok v6
   (`20260801-145312`) wykazał, że 40 rund nadal nie obejmuje horyzontu pełnej
   sekwencji: Q3 wyniosło ×1,57, a dla LOCAL mediana ilorazów 14 zapytań
   `trace-100/event-10` wyniosła ×1,404 i `trace-100/attr-5` ×1,555, podczas gdy
   późniejsze `event-10/attr-5` miało ×1,118 (maks. ×1,234). Ten sam blok ujawnił
   OOM JVM REFERENCE po ostatnim mierzonym datasetcie. Z tego powodu v7 zwiększa
   rozgrzewkę i wymusza budżety/stan runtime. Diagnostyczny przebieg v7
   (`20260801-162538`) potwierdził brak OOM i obniżył Q3 do ×1,15, lecz jego
   limit transakcji Neo4j 256 MiB przerwał eksport XES zbioru Hospital po
   osiągnięciu 254,5 MiB; v8 podnosi ten sufit do 512 MiB w niezmienionym
   budżecie LOCAL 3,25 GiB. Pierwszy kompletny blok v8 (`20260801-170734`)
   zakończył się technicznie poprawnie, ale po 10 rundach aktywacyjnych nadal
   wykazał szeroki dryf LOCAL: Q3 ×1,36 (maks. ×1,58), przy czym 13 z 14
   najgorszych rozrzutów zapytań dotyczyło LOCAL, a pierwszy `trace-100` był
   systematycznie wolniejszy od identycznych `event-10` i `attr-5`. Protokół v9
   wykonuje po idle pełne 200 rund; nie rozluźnia progu jakości ×1,25. Wersje
   v6–v8 nie są dopuszczane do serii finalnej.
   Niezależnie od bramki każdy
   zaakceptowany efekt musi przekroczyć faktycznie zmierzony floor swojej
   metryki, także wtedy, gdy jest on znacznie mniejszy lub większy niż ×1,25.

### Statystyka

Dla każdej pary (dataset, zapytanie, system) raportuje się **medianę** i
**kwartyle Q1–Q3** z 30 repetycji. Różnicę między systemami opisują trzy liczby:

1. **Efekt** — iloraz median podany jako czynnik ≥ 1 wraz z kierunkiem
   (`×2,66 (REFERENCE)`). Jedna kolumna nie miesza wtedy 0,02 z 4,49, a
   najmocniejszy wynik pracy nie ukrywa się pod zapisem „0,02”.
2. **Przedział ufności efektu** — 95% percentylowy bootstrap ilorazu median
   (10 000 losowań). Repetycje o tym samym numerze tworzą blok czasowy LOCAL /
   REFERENCE i są losowane wspólnie, co zachowuje wspólny dryf w czasie. Ziarno
   wyprowadza się z nazwy pary, więc przedział jest odtwarzalny z artefaktów.
3. **Test istotności** — sparowany test rang Wilcoxona (dwustronny,
   przybliżenie normalne z poprawką na wiązania i ciągłość) z korektą
   **Holma–Bonferroniego** na całą rodzinę porównań przebiegu. Test sparowany
   odpowiada protokołowi AB/BA; traktowanie obu serii jako niezależnych gubiłoby
   informację o wspólnym bloku czasowym.

**Werdykt „istotna” wymaga jednocześnie** p < 0,05 po korekcie, przedziału
ufności nieobejmującego 1,00 **oraz** efektu przekraczającego zmierzony błąd
pomiaru (rozrzut replikatów, bramka w §5). Efekt spełniający dwa pierwsze warunki,
a nie trzeci, opisywany jest jako **„poniżej błędu pomiaru”** — jest wtedy
realny, ale mniejszy niż to, o ile waha się sam pomiar, więc nie jest dowodem
o systemach.

> **Reguła rozłącznych IQR została wycofana.** IQR mierzy rozrzut *próby*, a nie
> niepewność *mediany*: jego rozłączność nie kontroluje żadnego błędu I rodzaju
> i zależy od `n` tylko przez estymator kwantyla. Na danych profilu FULL reguła
> ta uznawała za istotne **86 % wszystkich par** (113 ze 132), z czego 74 % miało
> efekt poniżej ×2 — czyli poniżej błędu, jaki replikaty wykazują na *identycznych*
> danych. Kryterium odpalające prawie zawsze nie niesie informacji.

**p95 podaje się w tabelach i wykresach pracy wyłącznie przy `n ≥ 200`.** Przy
`n = 30` kwantyl 0,95 jest interpolacją między 28. a 29. statystyką pozycyjną —
opisuje konkretne losowanie, nie ogon rozkładu. `query-summary.csv` zachowuje
wyliczoną wartość jako diagnostykę odtwarzalną z surowych próbek, ale generator
nie przenosi jej do narracji ani figur.

**Zastrzeżenie o niezależności.** Repetycje wykonywane są seryjnie na tym samym
rozgrzanym procesie, więc kolejne pary są autoskorelowane; sparowanie nie usuwa
tej zależności. Podane p są zatem kryterium pomocniczym. Jednostką niezależnej
replikacji dla wniosku końcowego jest pełny przebieg: rozstrzygający jest zgodny
kierunek w blokach i konserwatywny efekt zestawiony z błędem replikacyjnym.

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
- **`thesis-report.md`** — raport pojedynczego bloku po polsku; jest diagnostyką
  i źródłem szczegółowych tabel, nie finalnym dowodem serii,
- **`thesis-tables.tex`** — te same tabele w LaTeX (booktabs, etykiety
  `tab:bench-*`, polskie nagłówki), do bezpośredniego `\input{}` w pracy.

Po walidacji serii `compare-runs.py` tworzy w katalogu bloku kotwiczącego
`thesis-report-series.md` oraz CSV wnioskowania międzyblokowego. To jest finalny
raport Markdown: zaczyna się werdyktami całej serii, a tabele pojedynczego bloku
umieszcza w jawnie diagnostycznej części szczegółowej. `render-report-html.py`
wybiera go automatycznie i tworzy
samowystarczalny `thesis-report.html`; odmawia użycia pliku serii starszego niż
raport bazowy oraz odmawia finalnego renderu bez kompletnej sondy Q3-dysk,
sekcji werdyktu Q2, skalowania międzyblokowego, Q4 i wszystkich wskazanych
wykresów. Wykresy generuje
`plot-benchmark-results.py` z surowych CSV i — gdy dostępne są artefakty serii —
z median pełnych bloków; przy każdym uruchomieniu usuwa stare SVG, aby zmiana
workloadu nie pozostawiała nieaktualnych figur.

Finalny Q3-dysk wymaga dodatkowego `storage-scaling.csv` z izolowanej sondy.
Starszy plik bez `measurementMode=isolated-fresh-stack` jest jawnie oznaczany
jako nienadający się do wniosku, a nie reinterpretowany przez nowy generator.

## 7. Zagrożenia trafności (threats to validity) — do rozdziału pracy

- **Zakres porównania.** Wyniki dotyczą dwóch konkretnych aplikacji, obrazów,
  konfiguracji i publicznych API zapisanych w artefaktach. Nie izolują wpływu
  samego modelu grafowego od relacyjnego ani samej bazy od interpretera,
  serializacji i topologii wdrożenia. Wnioski formułuje się jako „LOCAL wobec
  REFERENCE w tym eksperymencie”, nie „Neo4j wobec PostgreSQL w ogólności”.

- **Wspólny host.** Systemy dzielą host i budżet Docker VM, ale żądania nie
  wykonują się równocześnie. AB/BA ogranicza krótkookresowy dryf, a trzy pełne
  bloki ujawniają zmienność międzyprzebiegową. Nie jest to jednak substytut
  replikacji na innych maszynach; wyniki bez `environment.json` nie są przenośne.

- **Różna architektura procesów.** REFERENCE zawiera aplikację i PostgreSQL w
  jednym kontenerze, LOCAL używa osobnych kontenerów interpretera i Neo4j oraz
  sieci kontenerowej między nimi. Mierzona jest rzeczywista architektura obu
  aplikacji, nie sztucznie wyrównany mikropomiar. W Q3 sumuje się dwa składniki
  LOCAL per timestamp, lecz REFERENCE nie da się rozdzielić bez modyfikacji obrazu.

- **Różne polityki pamięci baz.** Neo4j ma jawny heap i page cache, PostgreSQL w
  oficjalnym obrazie zachowuje ustawienia dostarczone przez REFERENCE. Ich
  „wyrównanie” wymagałoby zmiany jednego z produktów i również byłoby arbitralne.
  Dokładne wartości i efektywne sterty są częścią raportu środowiska; dlatego Q2
  i Q3 opisują dostarczone aplikacje z tymi konfiguracjami.

- **Ograniczone odpowiedzi.** Domyślne limity 10 logów / 30 śladów / 90 zdarzeń
  sprawiają, że zapytania klasy „okno” mierzą opóźnienie ograniczonej odpowiedzi,
  nie przepustowość po całym zbiorze. Nie wyklucza to pracy przed limitem niższego
  zakresu. Skalowanie wolno interpretować wyłącznie dla zadeklarowanych przed
  pomiarem par zapytanie–seria; płaski wynik może być skutkiem skutecznego indeksu,
  a nie dowodem braku zależności semantycznej.

- **Dane syntetyczne.** Deterministyczny generator ma regularne rozkłady i
  ściśle rosnące timestampy. Seria real-validation poszerza trafność ekologiczną,
  lecz cztery logi rzeczywiste nadal nie reprezentują wszystkich logów XES.
  Kształt, zagnieżdżone metadane i równe timestampy należy opisywać oddzielnie.

- **Autokorelacja.** Trzydzieści żądań warm zwiększa precyzję mediany wewnątrz
  bloku, ale nie jest trzydziestoma niezależnymi eksperymentami. Test Wilcoxona
  i bootstrap zachowują sparowanie czasowe, jednak wniosek końcowy wymaga
  zgodnego kierunku w pełnych blokach oraz efektu większego od replikacyjnego
  błędu pomiaru.

- **Import Q1.** Pomiar kończy się na pierwszym pollingu, w którym log jest
  widoczny. Kwantyzacja poniżej 1 s jest szczególnie istotna dla krótkich importów
  i ogranicza rozdzielczość porównań, mimo kontrbalansowania kolejności systemów.

- **Dysk Q3.** Restart Neo4j i `CHECKPOINT;` PostgreSQL są różnymi mechanizmami,
  a `pg_database_size` oraz rozmiar `/data/databases` odzwierciedlają natywne,
  nieidentyczne formaty. Izolacja każdego punktu usuwa zanieczyszczenie między
  datasetami, lecz stały koszt datastore'u pozostaje i jest modelowany wyrazem
  wolnym. Regresję wolno interpretować tylko przy wiarygodnym R² i dodatnich
  deltach; generator oznacza model jako interpretowalny przy dodatnim `b` i
  R² ≥ 0,30. Każdy dataset ma jeden izolowany pomiar storage; model jest opisowy
  i nie ma przedziału niepewności między powtórzeniami, bo powtórzenie całej
  drabiny wymagałoby wielokrotnego odtwarzania wolumenów dla każdego punktu.

- **Wiele porównań i selekcja workloadu.** Korekta Holma dotyczy testów
  wewnątrz bloku. Klasy zapytań i zbiorów są wersjonowane, a fingerprint blokuje
  łączenie różnych macierzy, co ogranicza dobieranie przypadków po obejrzeniu
  wyników. Nowy workload musi przejść smoke i pełną kontrolę semantyczną przed
  wejściem do serii finalnej.
