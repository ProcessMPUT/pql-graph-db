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
- **PQL Specification:** [docs/pql.md](https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md)

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

System składa się z następujących komponentów:
- **Neo4j Database** - graf baza danych do przechowywania logów XES
- **Spring Boot Application** - REST API dla operacji CRUD i wykonywania zapytań PQL
- **PQL Parser** - parser języka zapytań PQL (integracja z ProcessM)
- **Query Model** - obiektowa reprezentacja zapytań PQL → Cypher
- **XES Loader** - moduł do ładowania plików XES do Neo4j
- **XES Writer** - moduł do eksportu wyników zapytań do formatu XES (zgodność z IEEE 1849-2016)

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
# Uruchomienie Neo4j z docker-compose
docker-compose up -d neo4j

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

# Upload pliku XES
```

```http
GET /api/logs/{logId}
# Pobranie metadanych logu
```

```http
DELETE /api/logs/{logId}
# Usunięcie logu
```

### Wykonywanie zapytań PQL

```http
POST /api/query/execute
Content-Type: application/json

{
  "query": "SELECT * FROM log WHERE activity = 'Task A'",
  "logId": "log-123"
}
```

```http
POST /api/query/validate
Content-Type: application/json

{
  "query": "SELECT * FROM log WHERE activity = 'Task A'"
}
```

## Przykłady PQL

### Podstawowe
```sql
-- Wybierz wszystkie atrybuty zdarzeń gdzie aktywność to 'Task A'
SELECT * FROM event WHERE concept:name = 'Task A'

-- Wybierz ID śladu i liczbę zdarzeń
SELECT t:caseId, count(e:eventId) GROUP BY t:caseId
```

### Zaawansowane
```sql
-- Filtrowanie po czasie i koszcie
SELECT t:caseId, e:concept:name, e:cost:total
WHERE e:time:timestamp > '2023-01-01T00:00:00Z' AND e:cost:total > 100

-- Sortowanie i limitowanie (Scoped Limits)
SELECT t:caseId, e:concept:name
ORDER BY t:caseId ASC, e:time:timestamp DESC
LIMIT l:10, t:5  -- 10 logów, 5 śladów na log
```

## Konfiguracja

Główne ustawienia znajdują się w `src/main/resources/application.properties`:

```properties
# Neo4j
spring.neo4j.uri=bolt://localhost:7687
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=password123

# Aplikacja
processm.xes.upload.max-file-size=100MB
processm.query.timeout=PT30S
processm.query.max-results=10000
```

## Testowanie

### Testy jednostkowe

```bash
./gradlew test
```

### Testy integracyjne z Testcontainers

```bash
./gradlew integrationTest
```

Testy automatycznie uruchamiają kontener Neo4j za pomocą Testcontainers.

## Rozwój

### Struktura projektu

```
src/main/kotlin/com/processm/processminterpreter/
├── config/          # Konfiguracja Spring
├── controller/      # REST Controllers
├── service/         # Logika biznesowa
├── repository/      # Repozytoria Neo4j
├── model/           # Modele danych (Node entities)
├── pql/             # Parser i translator PQL
├── xes/             # Obsługa plików XES
└── dto/             # Data Transfer Objects
```

### Dodawanie nowych funkcji

1. **Modele danych**: Dodaj nowe `@Node` entities w pakiecie `model`
```
### Data & Standards
- **Example XES Logs:** [processm/xes-logs](https://github.com/ProcessMPUT/processm/tree/master/xes-logs)
  - BPIC series, Hospital, Road Traffic Fine, Sepsis Cases, CoSeLoG WABO (100+ files)
- **XES Standard (IEEE 1849-2016):** http://www.xes-standard.org/
- **OpenXES Library:** http://www.openxes.org/

## Architektura

System składa się z następujących komponentów:
- **Neo4j Database** - graf baza danych do przechowywania logów XES
- **Spring Boot Application** - REST API dla operacji CRUD i wykonywania zapytań PQL
- **PQL Parser** - parser języka zapytań PQL (integracja z ProcessM)
- **Query Model** - obiektowa reprezentacja zapytań PQL → Cypher
- **XES Loader** - moduł do ładowania plików XES do Neo4j
- **XES Writer** - moduł do eksportu wyników zapytań do formatu XES (zgodność z IEEE 1849-2016)

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
# Uruchomienie Neo4j z docker-compose
docker-compose up -d neo4j

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

# Upload pliku XES
```

```http
GET /api/logs/{logId}
# Pobranie metadanych logu
```

```http
DELETE /api/logs/{logId}
# Usunięcie logu
```

### Wykonywanie zapytań PQL

```http
POST /api/query/execute
Content-Type: application/json

{
  "query": "SELECT * FROM log WHERE activity = 'Task A'",
  "logId": "log-123"
}
```

```http
POST /api/query/validate
Content-Type: application/json

{
  "query": "SELECT * FROM log WHERE activity = 'Task A'"
}
```

## Przykłady PQL

### Podstawowe
```sql
-- Wybierz wszystkie atrybuty zdarzeń gdzie aktywność to 'Task A'
SELECT * FROM event WHERE concept:name = 'Task A'

-- Wybierz ID śladu i liczbę zdarzeń
SELECT t:caseId, count(e:eventId) GROUP BY t:caseId
```

### Zaawansowane
```sql
-- Filtrowanie po czasie i koszcie
SELECT t:caseId, e:concept:name, e:cost:total
WHERE e:time:timestamp > '2023-01-01T00:00:00Z' AND e:cost:total > 100

-- Sortowanie i limitowanie (Scoped Limits)
SELECT t:caseId, e:concept:name
ORDER BY t:caseId ASC, e:time:timestamp DESC
LIMIT l:10, t:5  -- 10 logów, 5 śladów na log
```

## Konfiguracja

Główne ustawienia znajdują się w `src/main/resources/application.properties`:

```properties
# Neo4j
spring.neo4j.uri=bolt://localhost:7687
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=password123

# Aplikacja
processm.xes.upload.max-file-size=100MB
processm.query.timeout=PT30S
processm.query.max-results=10000
```

## Testowanie

### Testy jednostkowe

```bash
./gradlew test
```

### Testy integracyjne z Testcontainers

```bash
./gradlew integrationTest
```

Testy automatycznie uruchamiają kontener Neo4j za pomocą Testcontainers.

## Rozwój

### Struktura projektu

```
src/main/kotlin/com/processm/processminterpreter/
├── config/          # Konfiguracja Spring
├── controller/      # REST Controllers
├── service/         # Logika biznesowa
├── repository/      # Repozytoria Neo4j
├── model/           # Modele danych (Node entities)
├── pql/             # Parser i translator PQL
├── xes/             # Obsługa plików XES
└── dto/             # Data Transfer Objects
```

### Dodawanie nowych funkcji

1. **Modele danych**: Dodaj nowe `@Node` entities w pakiecie `model`
2. **Repozytoria**: Stwórz repozytoria dziedziczące z `Neo4jRepository`
3. **Serwisy**: Implementuj logikę biznesową w pakiecie `service`
4. **Kontrolery**: Dodaj REST endpoints w pakiecie `controller`
5. **Testy**: Napisz testy jednostkowe i integracyjne

### Debugowanie Neo4j

```bash
# Połączenie z Neo4j CLI
docker exec -it processm-interpreter-neo4j-1 cypher-shell -u neo4j -p password123
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
NEO4J_dbms_memory_heap_max__size: "2G"
```

### Błędy połączenia
- Sprawdź czy Neo4j jest uruchomiony: `docker-compose ps`
- Sprawdź konfigurację w `application.properties`
- Sprawdź czy hasło jest poprawne (password123)
