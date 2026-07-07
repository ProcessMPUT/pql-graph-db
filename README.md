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

> **Źródło prawdy o architekturze i regułach zmian to `AGENTS.md`** (w root oraz
> per-obszar: `scripts/AGENTS.md`, `src/benchmark/AGENTS.md`). Metodologia
> testów wydajnościowych: `src/benchmark/METODOLOGIA.md`.

## Model danych Neo4j

```
Nodes:
- Log (properties: id, name, attributes)
- Trace (properties: id, case_id, attributes)  
- Event (properties: id, activity, timestamp, resource, attributes)

Relationships:
- Log -[CONTAINS]-> Trace
- Trace -[HAS_EVENT]-> Event
- Event -[FOLLOWS]-> Event (sekwencja eventów w trace)
```

## Wymagania

- Java 21+
- Docker & Docker Compose
- Gradle 8.0+

## Uruchomienie środowiska deweloperskiego

### 1. Uruchomienie Neo4j

```bash
# Uruchomienie całego środowiska: Neo4j + referencyjny ProcessM + seed danych
docker-compose up -d

# Sprawdzenie statusu
docker-compose ps

# Logi Neo4j
docker-compose logs -f neo4j
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
docker-compose down

# Zatrzymanie z usunięciem volumes (UWAGA: usuwa dane!)
docker-compose down -v
```

## API Endpoints

### Zarządzanie logami

```http
POST /api/logs/upload
Content-Type: multipart/form-data
# Parametry: file (MultipartFile), logId (opcjonalny)
# Upload pliku XES

POST /api/logs/load-sample
# Parametry: resourcePath (domyślnie: logs/sample_process.xes), logId (opcjonalny)
# Ładuje przykładowy log z zasobów aplikacji

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
# Parametry: deleteAllData (domyślnie: false) — usuwa też traces i events
# Usunięcie logu

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
{
  "query": "select e:name, e:timestamp where e:name = 'Task A'",
  "logId": "log-123"
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
# Porównuje wyniki z ProcessM (wymaga skonfigurowanego serwera ProcessM)
{
  "query": "select e:name",
  "logId": "log-123",
  "logName": "My Log",
  "includeTraces": true,
  "includeEvents": true
}
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
`scripts/run-compatibility-report.ps1`.

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
│   └── static/       # UI porównawcze (index.html + app.js)
├── test/             # testy; podpakiet .../processm/* to porty z oryginalnego ProcessM
└── benchmark/        # osobny source set: benchmark do pracy (patrz src/benchmark/AGENTS.md)
```

### Dodawanie nowych funkcji

Nie używamy Spring Data Neo4j (`@Node` / `Neo4jRepository`) — dostęp do bazy idzie
bezpośrednio przez sterownik (`org.neo4j.driver.Driver`) w klasach `neo4j/`.

1. **Semantyka PQL**: rozszerz fazy w `pql/semantics` (Resolver/Validator/Planner);
   zmiany walidacji sprawdzaj z oryginałem (`Query.kt` w lokalnym checkoucie ProcessM).
2. **Generacja Cypher**: dodaj/zmień renderer w `pql/cypher` (bez interpolacji
   wartości — tylko parametry).
3. **Persystencja**: repozytoria i zapis/odczyt w `neo4j/`.
4. **REST**: cienki kontroler w `web/` delegujący do usługi aplikacyjnej.
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
docker-compose logs neo4j

# Sprawdź czy port 7687 jest wolny
netstat -an | findstr 7687
```

### Problemy z pamięcią
```bash
# Zwiększ limity pamięci w docker-compose.yml
NEO4J_server_memory_heap_max__size: "2G"
```

### Błędy połączenia
- Sprawdź czy Neo4j jest uruchomiony: `docker-compose ps`
- Sprawdź konfigurację w `application.yml`
- Sprawdź czy hasło jest poprawne (password123)
