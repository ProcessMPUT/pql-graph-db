# Metodologia porównania interpreterów PQL

## 1. Cel badania

Eksperyment porównuje dwa kompletne systemy dostępne przez HTTP:

- **LOCAL** — interpreter PQL zbudowany w ramach projektu, wykorzystujący Neo4j;
- **REFERENCE** — oryginalny ProcessM wykorzystujący PostgreSQL.

Badanie nie porównuje Neo4j i PostgreSQL w izolacji. Obejmuje całą drogę żądania:
przyjęcie PQL przez API, analizę i kompilację zapytania, wykonanie w bazie,
rekonstrukcję hierarchii XES oraz serializację odpowiedzi. Wnioski dotyczą tych
dwóch aplikacji, wersji i konfiguracji zapisanych w artefaktach przebiegu.

Główna hipoteza brzmi:

> W badanej konfiguracji LOCAL osiąga niższe czasy odpowiedzi dla zapytań,
> które wymagają przechodzenia między poziomami hierarchii log–ślad–zdarzenie,
> niż relacyjna implementacja REFERENCE.

## 2. Pytania eksperymentalne

1. **Poprawność.** Czy oba systemy zwracają semantycznie równoważne odpowiedzi
   dla mierzonych zapytań i czy LOCAL zachowuje dane w cyklu import–eksport?
2. **Główna hipoteza.** Czy czasy zapytań korzystających z hierarchii XES różnią
   się między LOCAL i REFERENCE?
3. **Rozmiar i struktura.** Jak zmieniają się czasy, gdy rośnie liczba zdarzeń
   albo — przy stałym rozmiarze i kształcie hierarchii — liczba wariantów śladów?
4. **Koszt operacyjny.** Jak różnią się czas importu, pamięć, trwałe miejsce na
   dysku oraz obserwowane I/O kontenerów?

Poprawność jest bramką dla wydajności. Szybszy wynik nie jest dowodem przewagi,
jeżeli systemy wykonały różne obliczenia albo zwróciły różne dane.

## 3. Zapytania

Eksperyment nie wykonuje każdej konstrukcji PQL na każdym zbiorze. Każde
zapytanie ma z góry określoną rolę.

| Rola | Nazwa w raporcie | Etykieta | Co pokazuje |
|---|---|---|---|
| główne | Pobranie hierarchii log–ślad–zdarzenie | `hierarchyWindow` | odczyt ograniczonego wyniku obejmującego wszystkie trzy poziomy XES |
| główne | Selektywny warunek śladu zależny od zdarzeń | `hoistedPositive` | dodatni warunek między poziomami hierarchii, dopasowujący konkretną aktywność |
| główne | Grupowanie wariantów procesu | `variantGroupCount` | grupowanie sekwencji nazw z użyciem metadanych wariantu wyliczonych przy imporcie |
| główne | Grupowanie sekwencji bez bufora wariantu nazw | `genericVariantGroup` | grupowanie po sekwencji kosztów, które nie może użyć metadanych wariantu nazw |
| główne | Zliczanie elementów hierarchii log–ślad–zdarzenie | `hierarchyCardinality` | pełne zliczenie logów, śladów i zdarzeń przy stałym rozmiarze odpowiedzi |
| główne | Agregacja po wszystkich zdarzeniach | `globalEventAggregation` | pełne zliczenie zdarzeń oraz wyznaczenie skrajnych znaczników czasu |
| kontrolne | Sortowanie po obecnych atrybutach zdarzeń | `standardAttributesOrder` | sortowanie po czterech kluczach rzeczywiście obecnych w danych syntetycznych |
| kontrolne | Filtrowanie po równości nazwy zdarzenia | `eventEquality` | dokładne dopasowanie występujące w co dziesiątym zdarzeniu |
| kontrolne | LIKE bez dopasowań | `likeNoMatch` | koszt wykazania braku pasującej nazwy bez rekonstrukcji wyniku zdarzeń |
| kontrolne | LIKE z dopasowaniami | `likeMatching` | wyszukanie wielu rzeczywistych trafień i rekonstrukcja ograniczonej odpowiedzi |
| baseline | Minimalne okno odpowiedzi | `minimalWindow` | opisowy koszt najlżejszej pełnej odpowiedzi; nie testuje hipotezy |

Zapytania główne wynikają bezpośrednio z hipotezy. Kontrolne ograniczają jej
interpretację: eksperyment ma pokazać, gdzie graf pomaga, a nie udowodnić, że
LOCAL jest szybszy dla każdej operacji. Baseline nie jest odejmowany od innych
czasów, ponieważ poszczególne zapytania mogą przechodzić innymi ścieżkami kodu.

`hierarchyCardinality` i `globalEventAggregation` są wykonywane na osi rozmiaru
oraz logach rzeczywistych. Nie należą do osi wariantów, ponieważ przy stałej
liczbie elementów zmiana samych sekwencji aktywności nie zmienia ich pracy.
Zapytania zależne od wartości generatora (`hoistedPositive`,
`genericVariantGroup` i kontrole) są ograniczone do danych syntetycznych.

Dokładny tekst PQL, nazwa dla czytelnika, rola i uzasadnienie są wersjonowane w
`src/benchmark/resources/benchmark-queries.json` i zapisywane w `queries.csv`.

## 4. Dane

### 4.1 Skalowanie rozmiaru

Siedem deterministycznych logów syntetycznych ma po 10 zdarzeń na ślad, 10
aktywności, jeden wariant i 5 atrybutów niestandardowych na zdarzenie. Zmieniana
jest liczba śladów, a więc łączna liczba zdarzeń: 1 tys., 5 tys., 20 tys.,
100 tys., 200 tys., 500 tys. i 1 mln.

Każdy z pięciu kluczy niestandardowych ma jedną stałą wartość w całej serii.
Jest to celowe: oś nie dokłada wraz z liczbą zdarzeń rosnącej kardynalności
wartości, która byłaby drugą zmienną. Unikatowe pozostają nazwy śladów i kolejne
timestampy. Pliki są pakowane gzip z poziomem 9; kompresja zmienia tylko bajty
transportowe, a oba systemy otrzymują tę samą treść XES po dekompresji.

Seria odpowiada na pytanie o wzrost kosztu wraz z rozmiarem danych. Nie jest
interpretowana jako czysty efekt liczby śladów, ponieważ równocześnie rośnie
liczba zdarzeń i rozmiar XES.

### 4.2 Różnorodność wariantów przy stałym rozmiarze

Trzy logi mają dokładnie po 100 tys. zdarzeń, 2000 śladów po 50 zdarzeń,
48 aktywności i 5 atrybutów niestandardowych na zdarzenie. Zmieniana jest tylko
liczba różnych sekwencji `concept:name`:

- 1 wariant, powtórzony w 2000 śladach;
- 100 wariantów, każdy powtórzony 20 razy;
- 2000 wariantów, każdy występujący raz.

Generator koduje numer wariantu deterministycznie w pierwszych dwóch nazwach
aktywności, a w pozostałych 48 pozycjach umieszcza cały wspólny alfabet.
Dzięki temu rozmiar, długość śladu, liczba aktywności i liczba atrybutów nie są
konfundowane z liczbą wariantów. Seria nie jest modelem kompletnego „naturalnego
logu"; izoluje jedną konkretną cechę struktury. Wykonuje się na niej baseline,
okno hierarchii i grupowanie wariantów nazw. Pozostałe zapytania nie mają
związku przyczynowego z tą osią.

### 4.3 Walidacja na logach rzeczywistych

Dwanaście opublikowanych logów sprawdza, czy wynik z kontrolowanych danych
syntetycznych utrzymuje się dla nierównych długości śladów, wielu wariantów,
różnych alfabetów aktywności, timestampów i atrybutów:

| Log | DOI źródła | Powód włączenia |
|---|---|---|
| Sepsis Cases | `10.4121/uuid:915d2bfb-7e84-49ad-a286-dc35f063a460` | mały log medyczny o dużej różnorodności wariantów |
| BPIC15 Municipality 1 | `10.4121/uuid:a0addfda-2044-4541-a450-fdcc9fe16d17` | wiele aktywności i prawie unikatowe warianty |
| BPIC15 Municipality 2 | `10.4121/uuid:63a8435a-077d-4ece-97cd-2c76d394d99c` | drugi niezależny proces gminny z tej samej edycji |
| BPIC15 Municipality 3 | `10.4121/uuid:ed445cdd-27d-4d77-a1f7-59fe7360cfbe` | trzeci proces gminny umożliwiający ocenę zmienności wewnątrz edycji |
| BPIC15 Municipality 4 | `10.4121/uuid:679b11cf-47cd-459e-a6de-9ca614e25985` | czwarty proces gminny o odrębnej strukturze śladów |
| BPIC15 Municipality 5 | `10.4121/uuid:b32c6fe5-f212-4286-9774-58dd53511cf8` | piąty proces gminny domykający pełną rodzinę BPIC15 |
| Hospital Log | `10.4121/uuid:d9769f3d-0ab0-4fb8-803b-0d1120ffcf54` | bardzo długie i nierówne ślady oraz szeroki alfabet aktywności |
| BPI Challenge 2012 | `10.4121/uuid:3926db30-f712-4394-aebc-75976070e91f` | proces kredytowy o średnim rozmiarze i tysiącach wariantów |
| BPIC13 Incidents | `10.4121/uuid:500573e6-accc-4b0c-9576-aa5468b10cee` | log zarządzania incydentami o dużej liczbie zgłoszeń |
| BPIC13 Closed Problems | `10.4121/uuid:c2c3b154-ab26-4b31-a0e8-8f2350ddac11` | zamknięte problemy z tego samego środowiska usługowego |
| BPIC13 Open Problems | `10.4121/uuid:3537c19d-6c64-4b1d-815d-915ab0e479da` | mały log zarządzania problemami o innej domenie i strukturze niż procesy medyczne oraz kredytowe |
| Road Traffic Fine Management | `10.4121/uuid:270fd440-1057-4fb9-89a9-b699b47990f5` | ponad 150 tys. krótkich śladów, ale niewiele wariantów |

Dobór jest celowy, aby pokryć różne rejony cech strukturalnych, a nie losowy.
Nie stanowi reprezentatywnej próby wszystkich logów procesowych. `JournalReview`
nie jest już klasyfikowany jako log rzeczywisty: repozytorium ProcessM używa go
jako fixture'a, a nie znaleziono dla niego źródła pozwalającego uczciwie wykazać
pochodzenie danych rzeczywistych.

Niezmodyfikowany `LogsService` REFERENCE ogranicza czytany strumień wejściowy do 5 MiB,
niezależnie od wyższego limitu multipart ustawionego dla API. Dlatego pełne
Hospital Billing (5 952 122 B gzip) i BPIC17 (26 515 956 B gzip) nie należą do
wspólnej domeny importu i nie są mierzone. Nie zastępuje się ich niejawnie
próbkami ani zmodyfikowaną wersją REFERENCE. Największym logiem rzeczywistym w
badaniu pozostaje Road Traffic: 561 470 zdarzeń i 150 370 śladów. Runner przerywa
przed importem, jeżeli wybrany plik przekracza ten limit.

Lokalne repozytorium referencyjnego ProcessM dostarcza również `BPIC14_f`, pełne
BPIC17 i BPIC19. Pliki są zachowane poza classpath, w
`benchmark-data/bpi/archive/`, i nie należą do aktywnej macierzy: BPIC14 ma
wyłącznie wariant oznaczony jako filtrowany, natomiast
skompresowane BPIC17 i BPIC19 przekraczają limit 5 MiB. ProcessM nie zawiera
logów BPIC16 ani BPIC20, a BPIC18 pozostawił jedynie identyfikator usuniętego
obiektu Git LFS. Kopia Open Problems dostarczona przez ProcessM jest zachowana
z sufiksem `_processm` i to ona należy do aktywnej macierzy. Różni się bajtowo
od wcześniej wersjonowanego pliku, który pozostaje nietknięty dla odtwarzalności
historycznych przebiegów.

Przed pomiarem inspektor zapisuje dla każdego logu liczbę śladów i zdarzeń,
średnią, medianę, p95 i maksimum długości śladu, liczbę wartości
`event concept:name`, liczbę wariantów tych nazw, średnią liczbę atrybutów
zdarzenia, DOI oraz SHA-256 dokładnego pliku przekazanego obu systemom. Te cechy opisują próbę; nie są dodatkowymi
zmiennymi niezależnymi w jednym modelu statystycznym.

Ręcznie przygotowany `sample_process.xes` jest używany wyłącznie w profilu
SMOKE do sprawdzenia kompletności pipeline'u. Nie jest przedstawiany jako log
rzeczywisty ani włączany do wnioskowania profilu FULL.

Profil PILOT korzysta z datasetów FULL, lecz wykonuje tylko jeden import i trzy
pary zapytań. Służy wyłącznie do kontroli wykonalności, pamięci, round-trip i
formatu raportu. Jego wyniki nie są łączone z FULL ani interpretowane
statystycznie.

Wszystkie parametry i ścieżki są wersjonowane w
`src/benchmark/resources/benchmark-datasets.json`. Te same bajty XES trafiają
do obu systemów.

## 5. Mierzone wielkości

### 5.1 Czas zapytania

Mierzony jest czas ściany od wysłania żądania HTTP do odebrania całego korpusu
odpowiedzi. Parsowanie odpowiedzi na potrzeby kontroli semantycznej odbywa się
poza mierzonym przedziałem.

Dla każdej pary dataset–zapytanie wykonuje się:

1. 40 niemierzonych rozgrzewek per system;
2. 30 mierzonych par LOCAL–REFERENCE;
3. naprzemienną kolejność LR, RL, LR, RL, aby żaden system nie był stale pierwszy.

Repetycje o tym samym numerze tworzą parę czasową. Wszystkie 30 surowych czasów
pozostaje w `query-results.csv`.

Przed pierwszym pomiarem profil FULL wykonuje 200 nierejestrowanych rund na
wyrzucanym datastorze. Liczbę ustalono na podstawie historycznego pilota, w
którym krótsza rozgrzewka nie usuwała szerokiego dryfu JVM. Eksperyment nie
wykonuje drugiej adaptacyjnej fazy aktywacyjnej.

Stałe 40 rozgrzewek per dataset–zapytanie ustalono w osobnej diagnostyce małych
datasetów. Wersja z 12 rozgrzewkami nadal wykazywała różnicę 12–27% między
początkiem i końcem części mierzonej. Nie stosuje się adaptacyjnego kończenia
rozgrzewki na podstawie obserwowanych czasów, aby reguła rozpoczęcia pomiaru nie
zależała od chwilowo korzystnego wyniku. Po ostatniej rozgrzewce blok czasowy
zaczyna się bez sondy Docker CLI i bez sztucznego okresu bezczynności.

### 5.2 Czas importu

Import jest mierzony od rozpoczęcia wysyłania pliku do chwili, w której log jest
widoczny przez API datastore'u. Odpowiedź 2xx nie kończy pomiaru, ponieważ
REFERENCE może zakończyć import asynchronicznie. Stan jest sprawdzany co 100 ms;
wynik ma więc dodatnią kwantyzację mniejszą niż 100 ms.

Profil FULL wykonuje 10 sparowanych importów każdego datasetu do świeżych
datastore'ów. Kolejność systemów zmienia się w kolejnych parach. Ostatnia para
pozostaje na czas pomiarów zapytań; pozostałe datastore'y są usuwane od razu.
Modularny profil BLOCK wykonuje jedną parę importu przygotowującą datastore,
ponieważ służy walidacji zapytań na jednym logu rzeczywistym. Nie dostarcza
wnioskowania o czasie importu; pełna rodzina importowa pozostaje związana z
kompletną osią `size-scaling`.

### 5.3 Pamięć

Po zakończeniu 30 par czasowych runner odtwarza tę samą kolejność 30 par jako
osobny blok zasobowy. Jego czas nie trafia do statystyki latencji. `docker stats`
jest próbkowane tylko podczas tego odtworzenia. Dla LOCAL sumuje się jednoczesne
wskazania kontenerów interpretera i Neo4j; dla REFERENCE używa się kontenera
ProcessM zawierającego aplikację i PostgreSQL. Runner potwierdza zatrzymanie
samplera przed przejściem do kolejnego bloku czasowego.

Każda próbka zachowuje nazwę datasetu i zapytania. Raport protokołu 23 podaje
medianę median równych bloków oraz największe zaobserwowane wskazanie, zamiast
łączyć próbki w jedną serię zależną od liczby datasetów w kampanii. Nie nazywa
tej metryki procesowym RSS, szczytem dokładnie podczas mierzonego żądania ani
minimalną pamięcią potrzebną do uruchomienia systemu.

Kolejne próbki czasowe są autokorelowane, dlatego nie tworzy się z nich
sztucznego przedziału ufności. Są opisem obserwowanego przebiegu.

### 5.4 I/O kontenerów

Poza mierzonym czasem wykonuje się snapshot przed i po każdym bloku importu.
Dla zapytań snapshoty obejmują opisane wyżej osobne odtworzenie 30 par, a nie
blok, z którego pochodzą czasy. Raportowane są nieujemne delty:

- bajty odczytane i zapisane w Block I/O (`io.stat` daje dokładne liczniki,
  a wartość `docker stats` jest fallbackiem, gdy host ich nie udostępnia);
- operacje odczytu i zapisu z `io.stat` cgroup, jeżeli host je udostępnia;
- bajty odebrane i wysłane przez interfejs sieciowy kontenera.

W kampanii modularnej delty są agregowane najpierw wewnątrz stałego bloku 30
wykonań danego zapytania, a następnie raportowana jest mediana bloków. Nie sumuje
się I/O wszystkich datasetów jako głównego wyniku, ponieważ taki wynik rósłby
mechanicznie wraz z długością kampanii.

### 5.5 Modularne bloki i kampanie

Profil BLOCK jest atomową jednostką finalnej walidacji rzeczywistego logu:
zaczyna na świeżym stosie, wybiera dokładnie jeden dataset, wykonuje całą
zadeklarowaną rodzinę zapytań, kontrolę semantyczną, round-trip oraz blok
zasobowy. Pojedyncze zapytanie nie może zostać wybrane jako finalny blok.

Kampania składa kompletne bloki z surowych CSV i ponownie oblicza statystyki.
Nie łączy gotowych p-wartości ani werdyktów. Składanie odrzuca różne wersje
protokołu, zapytania, commity, obrazy, limity zasobów, środowiska, nieczysty stan
Git, brakujące pary, błędy zgodności, niepełne zasoby i powtórzony dataset.
Złożony artefakt otrzymuje profil raportowy CAMPAIGN; nie można uruchomić go jako
profilu pomiarowego bez wejściowych bloków.
Kontrolowane osie rozmiaru i wariantów pozostają pojedynczymi pełnymi
przebiegami, aby dzień uruchomienia nie został skonfundowany z punktem osi.

Dla LOCAL raport może pokazywać składniki interpreter/Neo4j oraz ich sumę; dla
REFERENCE cały kontener. Network I/O odzwierciedla również różnicę topologii:
LOCAL przesyła dane między dwoma kontenerami, a aplikacja i PostgreSQL
REFERENCE współdzielą kontener. Jest to koszt wdrożonego systemu, nie izolowany
koszt silnika bazy. Kompletność Network I/O jest wymagana na raportowanej
granicy aplikacji: kontenerze interpretera dla LOCAL i wspólnym kontenerze
ProcessM dla REFERENCE. Jeżeli `docker stats` nie zwróci sieci wewnętrznego
kontenera Neo4j, wiersz pozostaje oznaczony jako częściowy w surowym CSV, lecz
nie unieważnia dokładnych liczników Block I/O odczytanych z cgroup ani wykresu
sieci na granicy aplikacji.

### 5.5 Trwałe miejsce na dysku

Finalny pomiar dysku pozostaje osobną sondą. Każdy punkt zaczyna się od świeżych
wolumenów, zapisuje rozmiar bazowy, importuje ten sam XES, wymusza dostępny dla
danego silnika mechanizm utrwalenia i zapisuje przyrost. Wynik z narastającego
stanu głównego benchmarku jest wyłącznie diagnostyczny.

Jeżeli punkt ma otrzymać przedział niepewności, całą operację świeży stack →
import → pomiar trzeba powtórzyć. Próbki z jednego narastającego wolumenu nie są
niezależnymi powtórzeniami.

## 6. Statystyka

### 6.1 Porównanie zapytań

Dla każdej poprawnej pary dataset–zapytanie raport podaje:

- medianę 30 czasów LOCAL i REFERENCE;
- iloraz median `REFERENCE / LOCAL`;
- 95% przedział ufności ilorazu z bootstrapu, który losuje całe pary czasowe;
- dwustronny test rangowanych znaków Wilcoxona dla par;
- p-wartość po korekcie Holma.

Korekta Holma obejmuje wszystkie inferencyjne zapytania wykonane na danym
datasecie: sześć w seriach rozmiaru i logów rzeczywistych oraz trzy w serii
wariantów. Baseline nie należy do tej rodziny i nie otrzymuje werdyktu
istotności.

Interpretacja ilorazu:

- wartość większa od 1 — krótsza mediana LOCAL;
- wartość mniejsza od 1 — krótsza mediana REFERENCE;
- wartość 1 — brak różnicy median.

Wynik jest rozstrzygnięty tylko wtedy, gdy skorygowana p-wartość jest mniejsza
od 0,05 oraz 95% przedział nie obejmuje 1. Raport zawsze pokazuje wielkość efektu
i przedział; sama istotność statystyczna nie opisuje znaczenia praktycznego.

Repetycje opisują stabilność na jednej maszynie i jednej konfiguracji. Nie są
próbą losową komputerów ani wersji baz, więc wyniku nie uogólnia się na całą
populację środowisk.

Stabilność czasowa jest oceniana diagnostycznie. Raportowany efekt
`mediana(REFERENCE) / mediana(LOCAL)` oblicza się osobno w pierwszej i ostatniej
jednej trzeciej 30 chronologicznych par; środkowa część nie uczestniczy w tej
kontroli. Jeżeli kierunkowo niezależny iloraz efektów z obu okien przekracza
1,10, raport pokazuje jawne ostrzeżenie o dryfie, ale nie usuwa kompletnych par
z testu ani wykresu. Stały próg 10% nie jest testem statystycznym i przy wielu
porównaniach arbitralnie odrzucałby także wyniki zachowujące kierunek i duży
efekt. O wniosku decydują kompletność oraz zgodność semantyczna par, 95% przedział
ufności, parowany test Wilcoxona i korekta Holma. Nie stosuje się mediany
ilorazów pojedynczych par: byłby to inny estymand niż raportowany iloraz median
i przy naprzemiennej kolejności LR/RL reagowałby na dwa pasma efektu kolejności.
Bezwzględne mediany każdego systemu z obu okien pozostają diagnostyką wspólnego
dryfu. Baseline nie tworzy hipotezy ani werdyktu: ostrzeżenie o jego dryfie jest
widoczne, ale punkt pozostaje opisany medianą i IQR bez linii trendu.

### 6.2 Import

Dziesięć importów tworzy pary według numeru repetycji. Raport stosuje ten sam
iloraz, sparowany bootstrap i test Wilcoxona. Korekta Holma obejmuje datasety
porównywane w głównej serii rozmiaru. Wyniki logów rzeczywistych i wariantów są
opisową walidacją pomocniczą, wyraźnie oznaczoną w raporcie.

### 6.3 Czego nie liczymy

- IQR opisuje rozrzut próbek, a nie niepewność mediany; nie jest testem różnicy.
- p95 nie jest interpretowany przy 30 powtórzeniach.
- nie dopasowuje się automatycznie potęgowego modelu do każdego zapytania.
- nie agreguje się wyników do jednego rankingu „który system jest lepszy”.
- nie tworzy się p-wartości ani CI z gęstych, autokorelowanych próbek pamięci.

## 7. Kontrola poprawności

W każdej ciepłej repetycji porównywane są liczby logów, śladów i zdarzeń.
Ostatnie odpowiedzi obu systemów są dodatkowo porównywane ścisłym komparatorem
XES-JSON. Rozjazd nadaje całej parze status `MISMATCH` i wyklucza jej czasy ze
statystyki, ale zachowuje surowe próbki do diagnozy.

Dodatkowo przed publikacją wyników wymagane są:

- zero ścisłych problemów w szerokim raporcie kompatybilności;
- poprawny round-trip XES dla mierzonych datasetów;
- zielony pełny zestaw testów, w tym testy portowane z ProcessM;
- brak błędów importu, OOM, restartów i brakujących procesów JVM.

## 8. Środowisko i kolejność

Oba systemy działają w kontenerach na tym samym hoście i otrzymują równy,
skończony budżet pamięci całej aplikacji bez swapu. Żądania nie wykonują się
równocześnie. Dokładne wersje, image ID, commit, limity i sprzęt trafiają do
`environment.json`.

REFERENCE otrzymuje 6144 MiB we wspólnym kontenerze aplikacji i PostgreSQL, a
LOCAL łącznie 6144 MiB: po 3072 MiB dla interpretera i Neo4j. Oficjalny launcher
REFERENCE przeznacza połowę limitu kontenera na stertę JVM, czyli 3072 MiB. Po
stronie LOCAL stałe sufity 2304 MiB dla interpretera i 768 MiB dla Neo4j dają
taki sam łączny sufit stert 3072 MiB; pozostała pamięć obejmuje PostgreSQL albo
page cache i pamięć natywną Neo4j. Skrypt przygotowania odczytuje efektywne
parametry `-Xmx` z uruchomionych procesów i odrzuca asymetrię — sam tekst Compose
nie jest dowodem. Kontenery nie mogą korzystać ze swapu
(`memswap_limit = mem_limit`). Docker VM ma około 15,6 GiB RAM; efektywna pamięć
VM oraz wszystkie limity są zapisywane w artefaktach przebiegu.

Neo4j ma limity pamięci transakcji per baza i globalnie równe 1024 MiB. Wartość 512 MiB okazała się
sztuczną barierą dla kontrolnego eksportu całego logu 1 mln zdarzeń i Road
Traffic, mimo że zwykłe zapytania działały poprawnie. Podniesienie limitu nie
zmienia cgroup ani łącznego budżetu LOCAL; pozwala jedynie wykorzystać dostępną
pamięć kontenera podczas pełnego round-trip.

Datasety są wykonywane w jednej stałej kolejności zapisanej w konfiguracji.
Nie ma wariantów declared/reversed/random. Krótkookresowy dryf ogranicza
parowanie i naprzemienna kolejność systemów wewnątrz każdej repetycji.

Przebieg finalny wymaga czystego commita i świeżych wolumenów. Każdy BLOCK
wymaga nowego jednorazowego dowodu przygotowania stosu. Pilot służy
wyłącznie sprawdzeniu protokołu i nie jest łączony z wynikiem finalnym. Po
pilocie workload, liczby repetycji i reguły wnioskowania są zamrażane.

## 9. Raport

Główny raport ma odpowiadać na pytania, a nie odtwarzać całe CSV. Pełne dane
pozostają w załączniku. Protokół 23 generuje dziesięć figur, w tym osobny wykres
efektów i mapę cieplną dla rodziny BPI Challenge; raport historycznego protokołu
22 zachowuje osiem figur.

Każda sekcja wynikowa zawiera kolejno:

1. pytanie;
2. uzasadnienie wyboru danych i zapytania;
3. definicję miary;
4. instrukcję czytania wykresu;
5. wynik;
6. ostrożny wniosek i jego ograniczenia.

Główne wykresy używają osi liniowych. Dla pojedynczych oszacowań kropka oznacza
iloraz median, pozioma kreska 95% przedział ufności, a linia przy 1 brak różnicy.
Chmura surowych punktów może ilustrować najwyżej jeden wynik wyraźny i jeden
nierozstrzygnięty; nie zastępuje wykresu efektu ani testu.

Nazwy kodowe pojawiają się dopiero po nazwie opisowej. Każda tabela i figura
podaje jednostkę, liczbę powtórzeń, definicję efektu i zakres korekty p-wartości.
Fingerprinty, pełne środowisko, wszystkie pary i diagnostyka trafiają do
artefaktów lub załącznika, nie do głównej narracji.

## 10. Artefakty

Minimalny komplet nowego protokołu obejmuje:

- `datasets.csv`, `queries.csv`;
- `import-results.csv`, `query-results.csv`, `query-summary.csv`;
- `comparison-results.csv` — efekt, 95% CI, surowe i skorygowane p per para;
- `container-io.csv` — surowe delty Block/Network I/O i operacji;
- `memory-results.csv`, `memory-summary.csv`;
- `roundtrip-results.csv`, `cleanup-results.csv`;
- `environment.json`, `stack-preparation.json`;
- `benchmark-report.md` — krótki raport główny;
- `benchmark-appendix.md` — pełne tabele;
- `benchmark-report.html` i dziesięć figur protokołu 23 po renderowaniu.

Wyniki historycznych protokołów 10, 11 i 12 pozostają niezmienionymi dowodami
historycznymi. Nie wolno nadać im wersji 13 przez samo ponowne wygenerowanie
raportu.
