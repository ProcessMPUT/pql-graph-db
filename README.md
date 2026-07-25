# ProcessM Interpreter - Neo4j Implementation

**Alternatywny interpreter języka PQL** (Process Query Language) dla systemu [ProcessM](https://processm.cs.put.poznan.pl), wykorzystujący **Neo4j graph database** zamiast PostgreSQL do wydajniejszego przechowywania i przetwarzania hierarchicznych logów procesów w formacie XES.

## 🎯 Cel Projektu

Stworzenie **standalone REST component** dla ProcessM, który:
- Zastępuje nieefektywny PostgreSQL-based interpreter
- Wykorzystuje graph database (Neo4j) lepiej dopasowaną do hierarchical event data
- Udostępnia operacje CRUD na logach XES
- Interpretuje i wykonuje zapytania PQL
- **Zwraca wyniki w formacie XES** (zgodnie z IEEE 1849-2016)

**Original ProcessM Interpreter (PostgreSQL):**
- Repository: [TranslatedQuery.kt](https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/log/hierarchical/TranslatedQuery.kt)
- Problem: tłumaczy PQL na serię zapytań SQL → nieefektywne dla hierarchical data

**Ten projekt:** Wykorzystuje Neo4j Cypher dla native graph operations

## 🔗 ProcessM References

### ProcessM System
- **Official Website:** https://processm.cs.put.poznan.pl
- **Main Repository:** https://github.com/ProcessMPUT/processm
- **PQL Specification:** [ProcessM PQL specification](https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md)

### ProcessM Implementation (Reference)
- **Parser Grammar (ANTLR4):** [processm.core/...​/querylanguage](https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/main/antlr4/processm/core/querylanguage)
- **Query Model (Kotlin):** [Query.kt](https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/querylanguage/Query.kt)
- **Original Interpreter (PostgreSQL):** [TranslatedQuery.kt](https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/log/hierarchical/TranslatedQuery.kt)

### ProcessM Tests (Required for Compatibility)
- **Parser Tests:** [processm.core/.../querylanguage](https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/test/kotlin/processm/core/querylanguage)
  - AttributeTests, FunctionTests, LiteralTests, OrderDirectionTests, **QueryTests** (71 tests), ScopeTests
- **Interpreter Tests:** [processm.core/.../hierarchical](https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/test/kotlin/processm/core/log/hierarchical)
  - DBHierarchicalXESInputStreamTests, WithQueryTests, WithSelectQueryTests, WithWhereQueryTests

### Data & Standards
- **Example XES Logs:** [processm/xes-logs](https://github.com/ProcessMPUT/processm/tree/master/xes-logs)
  - BPIC series, Hospital, Road Traffic Fine, Sepsis Cases, CoSeLoG WABO (100+ files)
- **XES Standard (IEEE 1849-2016):** http://www.xes-standard.org/
- **OpenXES Library:** http://www.openxes.org/

## Architektura

Kod jest zorganizowany **wg funkcji (package-by-feature)**, nie warstwowo.
Każdy pakiet skupia jeden obszar odpowiedzialności:

- **`pql`** — cała obsługa języka PQL: ujednolicone AST (`pql.ast`), katalog
  atrybutów/funkcji (`pql.catalog`, `pql.semantics`), plan logiczny
  (`pql.plan`), adapter ANTLR (`pql.parser`), generacja Cypher (`pql.cypher`)
  oraz `PqlQueryService` (wykonanie / walidacja / eksport / metadane).
- **`neo4j`** — dostęp do bazy: repozytoria, import XES, schemat,
  `Neo4jQueryPlanExecutor`, rekonstrukcja hierarchii wyników (`neo4j.query.result`).
- **`xes`** — model logu/datastore i usługi (`LogService`, `DataStoreService`),
  wejście/wyjście XES XML (`xes.io`).
- **`processm`** — klient zdalnego ProcessM, formatowanie XES-JSON (`processm.json`),
  porównywanie/weryfikacja zgodności (`processm.compat`).
- **`web`** — kontrolery REST i DTO (kontrakt API).

Potok wykonania zapytania (bez wzorca Visitor — to zwykłe fazy kompilatora):

```
PQL string
  -> AntlrPqlParser        (pql.parser)      surface AST (PqlExpression / PqlQuery)
  -> Resolver              (pql.semantics)   nazwy, typy, hoisting w miejscu
  -> Validator             (pql.semantics)   reguły semantyczne (parytet z ProcessM)
  -> Planner               (pql.semantics)   plan logiczny (LogicalPlan)
  -> CypherCodegen         (pql.cypher)      parametryzowany Cypher
  -> Neo4jQueryPlanExecutor(neo4j.query)     wykonanie na Neo4j
  -> HierarchyReconstructor(neo4j.query.result) wynik jako zagnieżdżony XesLog
```

Interfejsy (porty) istnieją tylko na realnych granicach podmiany:
`LogRepository`, `DataStoreRepository` (Neo4j) i `RemoteProcessMGateway` (HTTP).
Reszta to konkretne klasy — bez ceremonii warstwowej.

> **Źródło prawdy o architekturze i regułach zmian to `AGENTS.md`** — w root oraz
> trzy przewodniki per-obszar: `scripts/AGENTS.md`, `src/benchmark/AGENTS.md`
> i `src/test/kotlin/com/processm/processminterpreter/processm/AGENTS.md`
> (testy portowane z oryginalnego ProcessM). Są to jedyne wersjonowane
> instrukcje dla agentów i celowo niezależne od narzędzia — pliki notatek
> specyficzne dla konkretnego asystenta są gitignorowane (sekcja
> `### AI Agents ###` w `.gitignore`), więc nie przetrwają świeżego klonu.
> Metodologia testów wydajnościowych: `src/benchmark/METODOLOGIA.md`.

## Model danych Neo4j

```
Nodes:
- DataStore (properties: dataStoreId, name)
- Log        (properties: logId, name, classifiers, extensions,
              traceGlobals, eventGlobals, atrybuty niestandardowe)
- Trace      (properties: traceId, caseId, atrybuty niestandardowe)
- Event      (properties: eventId, activity, timestamp, resource,
              atrybuty niestandardowe)

Relationships:
- DataStore -[CONTAINS_LOG]-> Log
- Log       -[CONTAINS]->     Trace
- Trace     -[HAS_EVENT]->    Event
- Event     -[FOLLOWS]->      Event (sekwencja zdarzeń w śladzie)
```

**DataStore jest korzeniem zakresu zapytań.** Każde zapytanie PQL startuje od
`MATCH (:DataStore {dataStoreId: $dataStoreId})-[:CONTAINS_LOG]->(log:Log)`, co
odwzorowuje model ProcessM: jeden datastore może zawierać wiele logów, a
zapytania wielologowe (`select l:name limit l:10`) działają w obrębie datastore'u.

## Wymagania

- **JDK 25** — `build.gradle.kts` ustawia `jvmToolchain(25)`; Gradle pobierze
  odpowiedni toolchain, jeśli nie masz go lokalnie
- Docker + Docker Compose (v2, `docker compose`)
- Python 3 — skrypty operacyjne w `scripts/` (tylko biblioteka standardowa,
  bez `pip install`)
- Gradle **nie jest wymagany globalnie** — używaj wrappera (`./gradlew`,
  na Windows `.\gradlew.bat`), który przypina wersję 9.5.0

Na świeżym klonie na macOS/Linux nadaj wrapperowi prawo wykonywania:
`chmod +x gradlew`.

## Uruchomienie środowiska deweloperskiego

### 1. Uruchomienie Neo4j

```bash
# Uruchomienie całego środowiska: Neo4j + referencyjny ProcessM + seed danych
docker compose up -d

# Sprawdzenie statusu
docker compose ps

# Logi Neo4j
docker compose logs -f neo4j
```

Neo4j będzie dostępne pod adresami:
- **Neo4j Browser**: http://localhost:7474
- **Bolt Protocol**: bolt://localhost:7687
- **Credentials**: neo4j / password123

### 2. Uruchomienie aplikacji

```bash
# Kompilacja i uruchomienie
./gradlew bootRun

# Lub w trybie deweloperskim z hot reload
./gradlew bootRun --continuous
```

Aplikacja będzie dostępna pod adresem: http://localhost:8080/api

### 3. Zatrzymanie środowiska

```bash
# Zatrzymanie wszystkich serwisów
docker compose down

# Zatrzymanie z usunięciem volumes (UWAGA: usuwa dane!)
docker compose down -v
```

## API Endpoints

### Datastore'y (zakres zapytań)

```http
POST /api/data-stores
Content-Type: application/json
{ "name": "My Datastore" }
# Tworzy datastore; zwraca { "id": ... } używane dalej jako dataStoreId

GET /api/data-stores
# Lista datastore'ów

GET /api/data-stores/{dataStoreId}
# Metadane pojedynczego datastore'u

GET /api/data-stores/{dataStoreId}/log-summaries
# Skrócone podsumowania logów w datastorze

PATCH /api/data-stores/{dataStoreId}
Content-Type: application/json
{ "name": "Nowa nazwa" }
# Zmiana nazwy

DELETE /api/data-stores/{dataStoreId}
# Usunięcie datastore'u wraz z zawartością

POST /api/data-stores/{dataStoreId}/logs
Content-Type: multipart/form-data
# Parametry: file (MultipartFile) — .xes lub .xes.gz
# Import logu XES do datastore'u (ścieżka zgodna z API ProcessM)

GET /api/data-stores/{dataStoreId}/logs
# Parametry: query (PQL, domyślnie ""), includeTraces, includeEvents
# Wykonanie zapytania PQL w zakresie datastore'u — endpoint zgodny z ProcessM

DELETE /api/data-stores/{dataStoreId}/logs/{logId}
# Usunięcie pojedynczego logu z datastore'u
```

### Zarządzanie logami

```http
POST /api/logs/upload
Content-Type: multipart/form-data
# Parametry: file (MultipartFile), logId (opcjonalny)
# Upload pliku XES

POST /api/logs/load-sample
# Parametry: resourcePath (domyślnie: logs/sample_process.xes), logId, dataStoreId
# Ładuje przykładowy log z zasobów aplikacji

GET /api/logs/samples
# Lista przykładowych logów dostępnych w zasobach (src/main/resources/logs)

POST /api/logs
Content-Type: application/json
{ "logId": "my-log", "name": "My Log", "attributes": {} }
# Tworzy nowy pusty log

GET /api/logs
# Parametry: includeStatistics (domyślnie: false)
# Lista wszystkich logów

GET /api/logs/{logId}
# Pobranie metadanych logu

GET /api/logs/{logId}/statistics
# Statystyki logu (liczba traces, events)

POST /api/logs/search
Content-Type: application/json
{ "name": "...", "createdAfter": "...", "attributeKey": "...", "attributeValue": "..." }
# Wyszukiwanie logów po kryteriach

PUT /api/logs/{logId}
Content-Type: application/json
{ "name": "New Name", "attributes": {} }
# Aktualizacja metadanych logu

DELETE /api/logs/{logId}
# Parametry: deleteAllData (domyślnie: TRUE) — usuwa też traces i events
# Usunięcie logu. Uwaga: domyślne zachowanie jest destrukcyjne — żeby zostawić
# dane potomne, trzeba jawnie przekazać deleteAllData=false

HEAD /api/logs/{logId}
# Sprawdzenie czy log istnieje (200 / 404)

GET /api/logs/generate-id
# Generuje unikalny logId
```

### Wykonywanie zapytań PQL

```http
POST /api/query/execute?format=json
Content-Type: application/json
# Parametr format: "json" (domyślnie) lub "xes" — XES jako JSON structure
# Zakres: podaj dataStoreId (zalecane, zgodne z ProcessM) albo logId
{
  "query": "select e:name, e:timestamp where e:name = 'Task A'",
  "dataStoreId": "ds-123",
  "logId": null,
  "timeout": null,
  "maxResults": null
}

POST /api/query/execute-xes
Content-Type: application/json
# Parametry: compress (domyślnie: false — gzip), logName (domyślnie: "Query Result Log")
# Zwraca wyniki jako plik XES XML do pobrania (Content-Disposition: attachment)
{
  "query": "select e:name, e:timestamp",
  "logId": "log-123"
}

POST /api/query/validate
Content-Type: application/json
{ "query": "select e:name" }
# Walidacja składni PQL bez wykonywania

GET /api/query/statistics
# Statystyki wykonanych zapytań (liczba, czasy, błędy)

GET /api/query/features
# Lista obsługiwanych funkcji PQL, operatorów i ograniczeń

POST /api/query/verify
Content-Type: application/json
# Parametry: format ("full" domyślnie lub "light")
# Porównuje wyniki z ProcessM (wymaga skonfigurowanego serwera ProcessM).
# To endpoint, na którym opiera się raport kompatybilności — porównanie jest
# semantyczne, a nie po statusie HTTP czy rozmiarze odpowiedzi.
{
  "query": "select e:name",
  "dataStoreId": "lokalny-ds",
  "remoteDataStoreId": "ds-w-referencyjnym-processm",
  "logId": null,
  "logName": null,
  "includeTraces": true,
  "includeEvents": true
}

POST /api/query/processm/upload
Content-Type: multipart/form-data
# Parametry: file (MultipartFile), logName
# Wysyła log do referencyjnego ProcessM (przygotowanie porównania)

GET /api/query/processm/data-stores
# Lista datastore'ów po stronie referencyjnego ProcessM
```

## Przykłady PQL

PQL nie ma klauzuli `FROM` — zakres (log/trace/event) wynika z prefiksu atrybutu
(`l:` / `t:` / `e:`). Limity są hierarchiczne (`l:` / `t:` / `e:`).

### Podstawowe
```sql
-- Nazwy zdarzeń z okna hierarchii: 1 log, 10 śladów, 20 zdarzeń
select e:name limit l:1, t:10, e:20

-- Liczba śladów i zdarzeń w logu
select count(t:name), count(e:name)

-- Filtr po nazwie logu
where l:name = 'teleclaims.mxml'
```

### Zaawansowane
```sql
-- Częstość aktywności: grupowanie po nazwie zdarzenia, sortowanie po liczniku
select e:name, count(e:name) group by e:name order by count(e:name) desc

-- Agregaty czasowe
select min(e:timestamp), max(e:timestamp), count(e:name)

-- Dopasowanie podłańcucha (semantyka PostgreSQL `~`, jak w oryginale)
where e:name matches 'consult'

-- Grupowanie po atrybucie zdarzenia podniesionym do zakresu śladu (hoisting)
select count(e:name) group by ^e:name order by count(e:name) desc
```

## Konfiguracja

Główne ustawienia znajdują się w `src/main/resources/application.yml`:

```yaml
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: password123
  servlet:
    multipart:
      max-file-size: 500MB

processm:
  xes:
    upload:
      max-file-size: 500MB
  query:
    timeout: PT5M
    max-results: 10000
  api:
    url: http://localhost:80/api
```

## Testowanie

Jeden task uruchamia cały suite — testy jednostkowe i integracyjne (te ostatnie
same podnoszą kontener Neo4j przez Testcontainers, więc wymagany jest Docker):

```bash
./gradlew test
```

Bramka zgodności semantycznej z oryginałem (raport kompatybilności, wymagane zero
problemów ścisłych) opisana jest w `AGENTS.md` i uruchamiana skryptem
`scripts/run-compatibility-report.py`.

## Rozwój

### Struktura projektu

```
src/
├── main/kotlin/com/processm/processminterpreter/
│   ├── pql/          # AST, semantyka, plan, parser ANTLR, generacja Cypher, PqlQueryService
│   ├── neo4j/        # repozytoria, import XES, wykonanie zapytań, rekonstrukcja wyników
│   ├── xes/          # model logu/datastore, usługi, wejście/wyjście XES XML (xes.io)
│   ├── processm/     # klient zdalnego ProcessM, XES-JSON, weryfikacja zgodności
│   └── web/          # kontrolery REST + DTO
├── main/resources/
│   ├── logs/         # przykładowe logi XES (gzip); listowane przez GET /api/logs/samples
│   └── static/       # UI porównawcze: index.html + js/ (app.js, json-viewer.js) + css/
├── test/             # testy; podpakiet .../processm/* to porty z oryginalnego ProcessM
└── benchmark/        # osobny source set: benchmark do pracy (patrz src/benchmark/AGENTS.md)

scripts/             # narzędzia operacyjne (Python 3, tylko stdlib) — patrz scripts/AGENTS.md
```

Lista zapytań w rozwijanym menu UI (`static/index.html`, `#sampleQueriesSelect`)
jest domyślnym źródłem zapytań raportu kompatybilności — dodanie tam zapytania
automatycznie obejmuje je testem zgodności.

### Dodawanie nowych funkcji

Nie używamy Spring Data Neo4j (`@Node` / `Neo4jRepository`) — dostęp do bazy idzie
bezpośrednio przez sterownik (`org.neo4j.driver.Driver`) w klasach `neo4j/`.

1. **Semantyka PQL**: rozszerz fazy w `pql/semantics` (Resolver/Validator/Planner);
   zmiany walidacji sprawdzaj z oryginałem (`Query.kt`). Repozytorium referencyjne
   ProcessM wskazuje zmienna `PROCESSM_REFERENCE_REPO`, checkout obok tego repo
   (`../processm`) albo GitHub — szczegóły w `AGENTS.md`.
2. **Generacja Cypher**: dodaj/zmień renderer w `pql/cypher` (bez interpolacji
   wartości — tylko parametry).
3. **Persystencja**: repozytoria i zapis/odczyt w `neo4j/`.
4. **REST**: cienki kontroler w `web/` delegujący do usługi feature'owej
   (`PqlQueryService`, `LogService`, `DataStoreService`).
5. **Testy**: jednostkowe w pakiecie feature; parytet semantyczny w `test/.../processm`.

Przed każdą zmianą przeczytaj `AGENTS.md` — opisuje dyscyplinę zmian i bramkę
zgodności (raport kompatybilności = zero problemów ścisłych).

### Debugowanie Neo4j

```bash
# Połączenie z Neo4j CLI (nazwa kontenera: processm-neo4j)
docker exec -it processm-neo4j cypher-shell -u neo4j -p password123
```

## Troubleshooting

### Neo4j nie startuje
```bash
# Sprawdź logi
docker compose logs neo4j

# Sprawdź czy port 7687 jest wolny (macOS/Linux)
lsof -nP -iTCP:7687 -sTCP:LISTEN

# Windows
netstat -ano | findstr 7687
```

### Problemy z pamięcią
```bash
# Zwiększ limity pamięci w docker-compose.yml
NEO4J_server_memory_heap_max__size: "2G"
```

### Błędy połączenia
- Sprawdź czy Neo4j jest uruchomiony: `docker compose ps`
- Sprawdź konfigurację w `application.yml`
- Sprawdź czy hasło jest poprawne (password123)
