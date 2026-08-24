package com.processm.processminterpreter.benchmark

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Writes the short, question-led report used by the current benchmark methodology. */
class BenchmarkReportWriter(
    private val outputDirectory: Path,
) {
    fun write(
        runId: String,
        settings: BenchmarkSettings,
        datasets: List<PreparedDataset>,
        querySpecs: List<BenchmarkQuerySpec>,
        imports: List<ImportBenchmarkResult>,
        queries: List<QueryBenchmarkResult>,
        comparisons: List<BenchmarkComparisonResult>,
        containerIo: List<ContainerIoBenchmarkResult>,
        memorySummaries: List<MemorySummary>,
        roundtrips: List<RoundtripBenchmarkResult>,
        storageScaling: List<IsolatedStorageScalingResult> = emptyList(),
    ) {
        outputDirectory.createDirectories()
        outputDirectory.resolve("benchmark-report.md").writeText(
            mainReport(
                runId, settings, datasets, querySpecs, imports, queries, comparisons, containerIo, memorySummaries,
                roundtrips, storageScaling,
            ),
        )
        outputDirectory.resolve("benchmark-appendix.md").writeText(
            appendix(runId, datasets, querySpecs, comparisons, containerIo, storageScaling),
        )
    }

    private fun mainReport(
        runId: String,
        settings: BenchmarkSettings,
        datasets: List<PreparedDataset>,
        querySpecs: List<BenchmarkQuerySpec>,
        imports: List<ImportBenchmarkResult>,
        queries: List<QueryBenchmarkResult>,
        comparisons: List<BenchmarkComparisonResult>,
        containerIo: List<ContainerIoBenchmarkResult>,
        memorySummaries: List<MemorySummary>,
        roundtrips: List<RoundtripBenchmarkResult>,
        storageScaling: List<IsolatedStorageScalingResult>,
    ): String {
        val queryRows = comparisons.filter { it.metric == "query" }
        val queryTextByLabel = querySpecs.associate { it.label to it.query }
        val importRows = comparisons.filter { it.metric == "import" }
        val sizeQueryRows = queryRows.filter { it.series == "size-scaling" }
        val variantQueryRows = queryRows.filter { it.series == "variant-scaling" }
        val realQueryRows = queryRows.filter { it.series == "real-validation" }
        val sizeImportRows = importRows.filter { it.series == "size-scaling" }
        val sizeDatasets = datasets.filter { it.series == "size-scaling" }.sortedBy { it.totalEvents }
        val variantDatasets = datasets.filter { it.series == "variant-scaling" }.sortedBy { it.variantCount }
        val realDatasets = datasets.filter { it.series == "real-validation" }
        val bpiDatasets = datasets.filter { it.collection == "bpi-challenge" }
            .sortedWith(compareBy({ it.collectionOrder ?: Int.MAX_VALUE }, { it.name }))
        val bpiNames = bpiDatasets.map { it.name }.toSet()
        val bpiQueryRows = queryRows.filter { it.datasetName in bpiNames }
        val contextualResources = settings.protocolVersion >= 23
        val sizeEndpointNames = listOfNotNull(sizeDatasets.firstOrNull()?.name, sizeDatasets.lastOrNull()?.name).toSet()
        val storageEvidence = storageEvidence(datasets, storageScaling)
        val storageProblem = storageScaling.isNotEmpty() && storageEvidence == null
        val isSmoke = settings.profile == BenchmarkProfile.SMOKE
        val isFinal = settings.profile in setOf(
            BenchmarkProfile.FULL,
            BenchmarkProfile.BLOCK,
            BenchmarkProfile.CAMPAIGN,
        )
        val invalid = comparisons.count { it.status != "OK" }
        val temporalInstability = queryRows.count { it.details.startsWith(TemporalStability.DETAILS_PREFIX) }
        val pairedTemporalDrift = queryRows.count {
            it.details.startsWith(TemporalStability.DIAGNOSTIC_DETAILS_PREFIX)
        }
        val descriptiveTemporalDrift = queryRows.count {
            it.details.startsWith(TemporalStability.DESCRIPTIVE_DETAILS_PREFIX)
        }
        val mismatchPairs = queries.filter { it.status == QUERY_STATUS_MISMATCH }
            .map { it.datasetName to it.queryLabel }.toSet().size
        val roundtripProblems = roundtrips.count { it.status != "MATCH" }
        val importErrors = imports.count { it.status != "OK" }
        val criticalIoFailures = containerIo.count {
            ContainerIoValidity.isCriticalFailure(it, settings.localAppContainer)
        }
        val diagnosticIoPartials = containerIo.count {
            ContainerIoValidity.isDiagnosticPartial(it, settings.localAppContainer)
        }
        val measuredSystems = imports.map { it.system }.toSet()
        val requiredMemory = buildSet {
            if ("local" in measuredSystems) add("local-total")
            if ("reference" in measuredSystems) add("reference-total")
        }
        val missingMemory = requiredMemory - memorySummaries
            .filter { it.phase == MEMORY_PHASE_QUERIES }
            .map { it.component }
            .toSet()
        val gatesPass = invalid == 0 && mismatchPairs == 0 && roundtripProblems == 0 && importErrors == 0 &&
            criticalIoFailures == 0 && missingMemory.isEmpty() && !storageProblem

        return buildString {
            appendLine("# Porównanie wydajności interpreterów PQL")
            appendLine()
            appendLine("Przebieg: `$runId`; wersja metodologii: ${settings.protocolVersion}; profil: `${settings.profile.name.lowercase()}`.")
            appendLine()
            appendLine("## Najważniejszy wynik")
            appendLine()
            appendLine(if (gatesPass && !isFinal) {
                if (settings.profile == BenchmarkProfile.DIAGNOSTIC) {
                    "Wewnętrzne bramki przebiegu przeszły, a diagnostyka dryfu małych datasetów została zapisana. " +
                        "Profil DIAGNOSTIC ma pełną liczbę powtórzeń, lecz celowo ograniczony zakres danych i zapytań; " +
                        "potwierdza gotowość do FULL, ale nie jest wynikiem całego badania."
                } else {
                    "Wewnętrzne bramki przebiegu przeszły, więc pipeline pomiarowy jest gotowy do uruchomienia profilu FULL. " +
                        "${settings.profile.name} ma za mało powtórzeń, aby formułować na jego podstawie wnioski o wydajności."
                }
            } else if (gatesPass) {
                "Wewnętrzne bramki przebiegu przeszły. Poniższe porównania czasów można interpretować dla tej konfiguracji po dołączeniu szerokiego raportu kompatybilności."
            } else {
                "Wewnętrzne bramki przebiegu **nie przeszły**. Wyników wydajności nie wolno traktować jako dowodu przewagi do czasu usunięcia problemów wskazanych niżej."
            })
            appendLine()
            appendLine(if (!isFinal) {
                "Wartości efektu i przedziały poniżej służą wyłącznie kontroli formatu oraz kompletności artefaktów; nie są wynikiem badania."
            } else {
                verdictSummary(queryRows)
            })
            if (isFinal) {
                appendLine()
                appendLine(patternSummary(queryRows))
                if (storageEvidence != null) {
                    appendLine()
                    appendLine(storageHeadline(storageEvidence))
                }
            }
            appendLine()
            appendLine("## Co porównano i jak czytać wynik")
            appendLine()
            appendLine(
                "LOCAL to badany interpreter z Neo4j, a REFERENCE to oryginalny ProcessM z PostgreSQL. " +
                    "Mierzono pełną obsługę żądania HTTP, nie same silniki baz danych. " +
                    "Liczba rejestrowanych par czasowych: zapytanie — ${settings.profile.repetitions} " +
                    "${pairNoun(settings.profile.repetitions)}, import — ${settings.profile.importRepetitions} " +
                    "${pairNoun(settings.profile.importRepetitions)}. W każdej parze oba systemy obsługują ten sam " +
                    "dataset i tę samą operację bezpośrednio po sobie, a kolejność zmienia się LR/RL: raz LOCAL–REFERENCE, " +
                    "raz REFERENCE–LOCAL. Przed rejestrowaniem każdy system wykonuje ${settings.queryWarmups} rozgrzewek " +
                    "tej samej pary dataset–zapytanie. Ogranicza to wpływ rozgrzewania procesu i powolnego dryfu obciążenia komputera.",
            )
            appendLine()
            appendLine(
                "Efekt to `REFERENCE / LOCAL`: wartość 2 oznacza dwukrotnie krótszą medianę LOCAL, " +
                    "a 0,5 — dwukrotnie krótszą medianę REFERENCE. Kropka na wykresie oznacza efekt, " +
                    "pozioma kreska jego 95% przedział ufności, a pionowa linia przy 1 brak różnicy. Zielony oznacza " +
                    "rozstrzygnięcie na korzyść LOCAL, pomarańczowy na korzyść REFERENCE, a niebieski brak rozstrzygnięcia " +
                    "lub wynik wyłącznie opisowy.",
            )
            appendLine()
            val primaryCount = querySpecs.count { it.role == BenchmarkQueryRole.PRIMARY }
            val controlCount = querySpecs.count { it.role == BenchmarkQueryRole.CONTROL }
            val sizeInferentialCount = querySpecs.count { it.isInferential && it.isMeasuredFor("size-scaling") }
            val realInferentialCount = querySpecs.count { it.isInferential && it.isMeasuredFor("real-validation") }
            val variantInferentialCount = querySpecs.count { it.isInferential && it.isMeasuredFor("variant-scaling") }
            appendLine("Testowane są $primaryCount zapytania główne i $controlCount kontrolne. Dwustronne testy Wilcoxona są parowane per dataset; korekta Holma obejmuje wszystkie zapytania inferencyjne wykonane na danym datasecie ($sizeInferentialCount dla rozmiaru, $realInferentialCount dla logów rzeczywistych i $variantInferentialCount dla osi wariantów). Minimalne okno jest wyłącznie opisowym baseline'em.")
            appendLine()
            appendLine("### Jak powstają liczby w tabelach")
            appendLine()
            appendLine(measurementGlossary(settings.protocolVersion))
            appendLine()
            appendLine("### Dokładne zapytania PQL")
            appendLine()
            appendLine(queryDefinitionTable(querySpecs))
            appendLine()
            appendLine("## Bramka poprawności")
            appendLine()
            appendLine(markdownTable(
                listOf("Kontrola", "Wynik"),
                listOf(
                    listOf("Błędy importu", importErrors.toString()),
                    listOf("Pary dataset–zapytanie z MISMATCH", mismatchPairs.toString()),
                    listOf("Serie czasowe wykluczone jako niestabilne", temporalInstability.toString()),
                    listOf("Ostrzeżenia o dryfie efektu parowanego", pairedTemporalDrift.toString()),
                    listOf("Opisowe baseline’y z ostrzeżeniem o dryfie", descriptiveTemporalDrift.toString()),
                    listOf("Nieważne porównania (łącznie)", invalid.toString()),
                    listOf("Problemy round-trip XES", roundtripProblems.toString()),
                    listOf("Krytyczne braki lub wyzerowania pomiarów I/O", criticalIoFailures.toString()),
                    listOf("Częściowe snapshoty I/O (diagnostyczne)", diagnosticIoPartials.toString()),
                    listOf("Brakujące sumaryczne serie pamięci", missingMemory.ifEmpty { setOf("brak") }.joinToString(", ")),
                    listOf("Izolowana sonda trwałego rozmiaru", storageGateLabel(isFinal, storageScaling, storageEvidence)),
                    listOf("Szeroki raport kompatybilności", "wymagany osobno; oczekiwane 0 strict problems"),
                ),
            ))
            appendLine()
            appendLine(validityGateExplanation(settings.protocolVersion))
            if (sizeQueryRows.isNotEmpty()) {
                appendLine()
                appendLine("## Wpływ rozmiaru danych")
                appendLine()
                appendLine(if (isSmoke) {
                    "Pilot obejmuje tylko punkt 1 tys. zdarzeń. Profil FULL zachowuje 10 zdarzeń na ślad, jeden wariant i 5 dodatkowych atrybutów, zmieniając liczbę zdarzeń od 1 tys. do 1 mln."
                } else {
                    "Seria zachowuje 10 zdarzeń na ślad, 10 aktywności, jeden wariant i 5 dodatkowych atrybutów o stałej wartości per klucz. ${sizeRangeSentence(sizeDatasets)} Jest to kontrolowana oś skali: pokazuje łączny efekt wzrostu liczby śladów, zdarzeń i rozmiaru XES, ale celowo nie udaje struktury logu rzeczywistego."
                })
                if (!isSmoke && sizeDatasets.size > 2) {
                    appendLine()
                    appendLine("Wykres efektu obejmuje wszystkie ${sizeDatasets.size} rozmiarów. Tabela w głównej części pokazuje tylko pierwszy i ostatni punkt, aby uwidocznić zmianę między krańcami bez powtarzania całego wykresu; wyniki pośrednie znajdują się w załączniku i CSV.")
                }
                appendLine()
                appendLine("![Efekty zapytań dla serii rozmiaru](figures/fig-01-query-effect-size.svg)")
                appendLine()
                appendLine("Na wykresie czasów baseline jest pokazany jako niepołączone punkty z IQR, ponieważ jego koszt powinien być prawie stały i łączenie drobnych wahań sugerowałoby nieistniejący trend. IQR obejmuje środkowe 50% surowych czasów. Pozostałe linie łączą mediany wyłącznie pomocniczo.")
                appendLine()
                appendLine("![Mediany czasów dla serii rozmiaru](figures/fig-02-query-latency-size.svg)")
                appendLine()
                appendLine(effectTable(sizeQueryRows.filter { it.datasetName in sizeEndpointNames }, queryTextByLabel))
            }
            if (variantQueryRows.isNotEmpty()) {
                appendLine()
                appendLine("## Wpływ różnorodności wariantów")
                appendLine()
                appendLine(if (isSmoke) {
                    "Pilot obejmuje środkowy punkt osi: 100 wariantów. Profil FULL porównuje 1, 100 i 2000 wariantów."
                } else if (variantDatasets.size < 3) {
                    "Ten diagnostyczny przebieg zawiera ${variantDatasets.joinToString { formatInteger(it.variantCount) }} wariantów. Pełna metodologia porównuje 1, 100 i 2000 przy stałych 100 tys. zdarzeń, 2000 śladów po 50 zdarzeń, 48 aktywnościach i 5 dodatkowych atrybutach o stałej wartości per klucz."
                } else {
                    "Każdy punkt ma dokładnie 100 tys. zdarzeń, 2000 śladów po 50 zdarzeń, 48 aktywności i 5 dodatkowych atrybutów o stałej wartości per klucz. Zmienia się wyłącznie liczba sekwencji `concept:name`: 1, 100 albo 2000. Ta oś izoluje koszt różnorodności wariantów; nierówne długości śladów i inne cechy naturalnych procesów bada osobna walidacja rzeczywista."
                })
                appendLine()
                appendLine("![Efekty zapytań dla różnej liczby wariantów](figures/fig-03-query-effect-variants.svg)")
                appendLine()
                appendLine(effectTable(variantQueryRows.filter { it.role == "primary" }, queryTextByLabel))
            }
            if (realQueryRows.isNotEmpty()) {
                appendLine()
                appendLine("## Walidacja na logach rzeczywistych")
                appendLine()
                appendLine("${publishedLogPhrase(realDatasets.size)} sprawdza przenoszalność obserwacji poza regularne dane syntetyczne. Dobór jest celowy: obejmuje różne rozmiary, długości śladów, liczby aktywności i wariantów; nie jest losową ani reprezentatywną próbą wszystkich procesów.")
                appendLine()
                appendLine("Ślad odpowiada jednej instancji procesu, a zdarzenie jednemu zarejestrowanemu krokowi. Aktywności to różne niepuste wartości `concept:name`. Wariant jest różną uporządkowaną sekwencją tych wartości w śladzie. Kolumna `Śr./med./p95/maks.` opisuje rozkład liczby zdarzeń przypadających na ślad; p95 oznacza, że 95% śladów ma nie więcej zdarzeń niż podana wartość.")
                appendLine()
                appendLine(realDatasetTable(realDatasets))
                appendLine()
                appendLine("![Efekty zapytań na logach rzeczywistych](figures/fig-04-query-effect-real.svg)")
                appendLine()
                appendLine("W komórkach: `L` — LOCAL szybszy, `R` — REFERENCE szybszy, `≈` — brak rozstrzygniętej różnicy; liczba to efekt R/L. Przedziały ufności i p-wartości są na wykresie oraz w załączniku.")
                appendLine()
                appendLine(realEffectMatrix(realQueryRows, querySpecs))
            }
            if (contextualResources && bpiQueryRows.isNotEmpty()) {
                appendLine()
                appendLine("## Walidacja przekrojowa BPI Challenge")
                appendLine()
                appendLine("Rodzina BPI Challenge jest analizowana jako zbiór heterogenicznych procesów, a nie szereg czasowy. Rok identyfikuje edycję konkursu; nie jest osią skalowania ani zmienną przyczynową. Każdy log zachowuje własną pełną rodzinę testów i bramkę zgodności odpowiedzi.")
                appendLine()
                appendLine(realDatasetTable(bpiDatasets))
                appendLine()
                appendLine("![Efekty zapytań dla logów BPI Challenge](figures/fig-05-query-effect-bpi.svg)")
                appendLine()
                appendLine("![Mapa efektów dla logów BPI Challenge](figures/fig-06-query-effect-bpi-heatmap.svg)")
                appendLine()
                appendLine(realEffectMatrix(bpiQueryRows, querySpecs))
            }
            if (sizeImportRows.isNotEmpty()) {
                appendLine()
                appendLine("## Import")
                appendLine()
                appendLine(if (!isFinal) {
                    "Przebieg diagnostyczny wykonuje ${settings.profile.importRepetitions} ${pairNoun(settings.profile.importRepetitions)} importu per dataset. Czas obejmuje wysłanie XES i oczekiwanie, aż log będzie widoczny w API; gotowość jest sprawdzana co 100 ms."
                } else {
                    "Czas importu obejmuje wysłanie XES i oczekiwanie, aż log będzie widoczny w API; gotowość jest sprawdzana co 100 ms. Statystyczna rodzina importu obejmuje ${sizeImportRows.size} punktów serii rozmiaru."
                })
                appendLine()
                appendLine("![Efekt dla czasu importu](figures/${if (contextualResources) "fig-07-import-effect.svg" else "fig-05-import-effect.svg"})")
                appendLine()
                appendLine(effectTable(sizeImportRows, queryTextByLabel))
            }
            if (isFinal) {
                appendLine()
                appendLine("## Trwały rozmiar danych")
                appendLine()
                if (storageEvidence == null) {
                    appendLine(if (storageScaling.isEmpty()) {
                        "Izolowana sonda nie została jeszcze dołączona. Każdy punkt musi powstać na osobnym świeżym stosie; pomiarów `storage-results.csv` z kolejnych importów nie wolno użyć jako wyniku końcowego."
                    } else {
                        "**Sonda jest niekompletna lub ma niespójną proweniencję.** Nie formułuje się na jej podstawie wniosku o trwałym rozmiarze danych; szczegóły pozostają w `storage-scaling.csv`."
                    })
                } else {
                    appendLine("Każdy dataset zaimportowano do osobno odtworzonego stosu. Delta oznacza różnicę rozmiaru trwałych plików między świeżą bazą a stanem po imporcie i checkpoint/restart. Dzięki temu wcześniejszy dataset nie wpływa na kolejny punkt.")
                    appendLine()
                    appendLine(storageInterpretation(storageEvidence))
                    appendLine()
                    appendLine(storageTable(storageEvidence, datasets))
                    appendLine()
                    appendLine("Dopasowanie liniowe jest opisowe: na każdy rozmiar przypada jeden świeży stos, więc nie wyznacza się przedziałów ufności ani p-wartości. Stały wyraz modelu przejmuje koszt inicjalizacji i prealokacji plików.")
                }
            }
            appendLine()
            appendLine("## Pamięć i I/O kontenerów")
            appendLine()
            appendLine("Pamięć to wskazanie kontenerowe `docker stats` podczas osobnego, niemierzonego powtórzenia ${settings.profile.repetitions} par tych samych zapytań po zakończeniu bloku czasowego: dla LOCAL sumuje interpreter i Neo4j, a dla REFERENCE obejmuje jego wspólny kontener aplikacji i PostgreSQL. Runner potwierdza zatrzymanie samplera podczas wszystkich próbek czasu, więc obserwacja zasobów nie konkuruje z kilkumilisekundowym żądaniem. ${if (contextualResources) "Próbki zachowują dataset i zapytanie; tabela agreguje porównywalne bloki, a nie jedną serię zależną od długości całej kampanii." else "Mediana opisuje typowy odczyt z całej fazy profilowania, a maksimum największą zaobserwowaną wartość."} MiB oznacza 2²⁰ bajtów.")
            appendLine()
            appendLine(if (contextualResources) {
                "Block read/write oraz liczby operacji są deltami liczników zmierzonymi przed i po każdym osobnym blokiem zasobowym. Tabela i wykresy pokazują medianę porównywalnych bloków o stałych ${settings.profile.repetitions} wykonaniach per system, więc dodanie kolejnego datasetu nie zwiększa wyniku mechanicznie. Blok powtarza zapytania i kolejność systemów z części czasowej, ale jego czasy nie trafiają do statystyki. Są to miary opisowe aktywności I/O całego wdrożenia, a nie czasów pojedynczej operacji dyskowej."
            } else {
                "Block read/write oraz liczby operacji są sumami nieujemnych różnic liczników kontenerowych zmierzonych przed i po tym samym osobnym bloku zasobowym. Blok powtarza zapytania i kolejność systemów z części czasowej, ale jego czasy nie trafiają do statystyki. Dzięki temu Docker CLI nie wydłuża ani nie poprzedza bezpośrednio raportowanej latencji. Są to miary opisowe aktywności I/O całego wdrożenia, a nie czasów pojedynczej operacji dyskowej."
            })
            if (diagnosticIoPartials > 0) {
                appendLine()
                appendLine(
                    "Częściowe snapshoty sieci wewnętrznego kontenera bazy LOCAL: $diagnosticIoPartials. " +
                        "Docker nie zwrócił w nich licznika sieci tego kontenera. " +
                        "Nie jest to licznik używany na wykresie Network I/O, który mierzy granicę kontenera aplikacji. " +
                        "Dokładne liczniki Block I/O z cgroup dla tych samych snapshotów są kompletne i pozostają w sumach. " +
                        "Surowe wiersze zachowują status `UNAVAILABLE`, aby częściowego odczytu nie ukrywać.",
                )
            }
            appendLine()
            val resourceOffset = if (contextualResources) 2 else 0
            appendLine("![Pamięć kontenerów podczas zapytań](figures/fig-0${6 + resourceOffset}-container-memory.svg)")
            appendLine()
            appendLine("![Block I/O kontenerów](figures/fig-0${7 + resourceOffset}-container-block-io.svg)")
            appendLine()
            appendLine("![Network I/O na granicy aplikacji](figures/fig-${(8 + resourceOffset).toString().padStart(2, '0')}-container-network-io.svg)")
            appendLine()
            appendLine(resourceTable(containerIo, memorySummaries, contextualResources))
            appendLine()
            appendLine("Network I/O opisuje wdrożoną topologię, nie czysty koszt bazy: LOCAL przesyła dane między kontenerami, podczas gdy aplikacja i PostgreSQL REFERENCE współdzielą kontener.")
            appendLine()
            appendLine("## Ograniczenia")
            appendLine()
            appendLine(
                "Wyniki dotyczą jednej maszyny, jednej konfiguracji kontenerów i zapisanych wersji systemów. Powtórzenia kwantyfikują zmienność czasową na tym hoście, a nie zmienność między komputerami. Są wykonywane w tych samych długo żyjących procesach i mogą być autokorelowane, dlatego p-wartości są kryterium pomocniczym i nie dowodzą powtarzalności między niezależnymi przebiegami. Logi rzeczywiste dobrano celowo, a nie losowo; niezmodyfikowany REFERENCE przyjmuje najwyżej 5 MiB skompresowanego XES, co ogranicza ich dobór. Kontrolowana oś wariantów nie odwzorowuje wszystkich zależności między strukturą śladów, atrybutami i rozkładem aktywności. Pamięć i liczniki I/O są opisowe; nie tworzy się z ich gęstych próbek p-wartości. " +
                    if (storageEvidence == null) {
                        "Trwały rozmiar danych wymaga osobnego eksperymentu ze świeżymi wolumenami per punkt."
                    } else {
                        "Sonda trwałego rozmiaru ma jeden świeży stos per punkt i opisuje przyrost plików całych baz, których formaty oraz stałe koszty inicjalizacji są różne."
                    },
            )
            appendLine()
            appendLine("Pełne porównania, definicje zapytań, surowe sumy I/O i dane sondy storage są w `benchmark-appendix.md`; źródłowe próbki pozostają w plikach CSV.")
        }
    }

    private fun appendix(
        runId: String,
        datasets: List<PreparedDataset>,
        querySpecs: List<BenchmarkQuerySpec>,
        comparisons: List<BenchmarkComparisonResult>,
        containerIo: List<ContainerIoBenchmarkResult>,
        storageScaling: List<IsolatedStorageScalingResult>,
    ): String = buildString {
        appendLine("# Załącznik do raportu benchmarku `$runId`")
        appendLine()
        appendLine("## Datasety")
        appendLine()
        appendLine(
            markdownTable(
                listOf("Zbiór", "Seria", "Kolekcja", "Pozycja", "Ślady", "Śr. zd./ślad", "Mediana", "p95", "Maks.", "Zdarzenia", "Aktywności", "Warianty", "Śr. atrybutów/zdarzenie", "XES [B]", "DOI", "SHA-256 pliku"),
                datasets.map {
                    listOf(
                        it.name, it.series, it.collection, it.collectionOrder, it.traces,
                        number(it.meanEventsPerTrace, 1), number(it.medianEventsPerTrace, 1),
                        it.p95EventsPerTrace, it.maxEventsPerTrace, it.totalEvents, it.activityCount,
                        it.variantCount, number(it.meanEventAttributes, 1), it.xesBytes, doiLink(it.sourceDoi), it.fileSha256,
                    )
                },
            ),
        )
        appendLine()
        appendLine("## Zapytania")
        appendLine()
        appendLine(
            markdownTable(
                listOf("Etykieta", "Nazwa", "PQL", "Rola", "Wykonywane serie", "Cel"),
                querySpecs.map {
                    listOf(
                        it.label, it.displayName, "`${it.query}`", roleLabel(it.role.name),
                        it.measurementSeries.joinToString(", "), it.purpose,
                    )
                },
            ),
        )
        appendLine()
        appendLine("## Wszystkie porównania")
        appendLine()
        appendLine(effectTable(comparisons, querySpecs.associate { it.label to it.query }))
        val stabilityRows = comparisons.filter { it.stabilityWindowSamples != null }
        if (stabilityRows.isNotEmpty()) {
            appendLine()
            appendLine("## Kontrola stabilności czasowej")
            appendLine()
            appendLine(stabilityTable(stabilityRows))
        }
        appendLine()
        appendLine("## I/O per blok i kontener")
        appendLine()
        appendLine(
            markdownTable(
                listOf("Faza", "Zbiór", "Operacja", "Run", "System", "Kontener", "Odczyt [B]", "Zapis [B]", "Odczyty [op]", "Zapisy [op]", "RX [B]", "TX [B]", "Status"),
                containerIo.map {
                    listOf(
                        it.phase, it.datasetName, it.operationLabel, it.run, it.system, it.component,
                        it.blockReadBytes, it.blockWriteBytes, it.blockReadOperations, it.blockWriteOperations,
                        it.networkReceiveBytes, it.networkTransmitBytes, it.status,
                    )
                },
            ),
        )
        if (storageScaling.isNotEmpty()) {
            appendLine()
            appendLine("## Izolowana sonda trwałego rozmiaru")
            appendLine()
            appendLine(
                markdownTable(
                    listOf("Zbiór", "System", "Tryb", "Przygotowanie stosu", "Przed [B]", "Po [B]", "Delta [B]", "XES [B]", "Delta/XES"),
                    storageScaling.map {
                        listOf(
                            it.datasetName, it.system.uppercase(), it.measurementMode, it.stackPreparationId,
                            it.beforeBytes, it.afterBytes, it.deltaBytes, it.xesBytes, number(it.deltaToXesRatio, 3),
                        )
                    },
                ),
            )
        }
    }

    private fun verdictSummary(rows: List<BenchmarkComparisonResult>): String {
        val tested = rows.filter { it.holmPValue != null && it.status == "OK" }
        if (tested.isEmpty()) return "Brak kompletnych porównań zapytań do podsumowania."
        val primary = tested.filter { it.role == "primary" }
        val controls = tested.filter { it.role == "control" }
        return listOfNotNull(
            verdictCounts("Zapytania główne", primary).takeIf { primary.isNotEmpty() },
            verdictCounts("Zapytania kontrolne", controls).takeIf { controls.isNotEmpty() },
        ).joinToString(" ") + " Zestawienie nie jest rankingiem; znaczenie mają konkretne zapytania i zbiory danych."
    }

    private data class StorageEvidence(
        val rows: List<IsolatedStorageScalingResult>,
        val localFit: LinearFit,
        val referenceFit: LinearFit,
    )

    private fun storageEvidence(
        datasets: List<PreparedDataset>,
        rows: List<IsolatedStorageScalingResult>,
    ): StorageEvidence? {
        if (rows.isEmpty()) return null
        val sizeDatasets = datasets.filter { it.series == "size-scaling" }.sortedBy { it.totalEvents }
        if (sizeDatasets.size < 3 || rows.size != sizeDatasets.size * 2) return null
        val expectedNames = sizeDatasets.map { it.name }.toSet()
        val byDataset = rows.groupBy { it.datasetName }
        if (byDataset.keys != expectedNames) return null
        if (rows.any {
                it.measurementMode != "isolated-fresh-stack" || it.deltaBytes <= 0L ||
                    it.afterBytes - it.beforeBytes != it.deltaBytes || !it.deltaToXesRatio.isFinite() ||
                    !it.deltaToGzipRatio.isFinite()
            }
        ) return null
        if (byDataset.values.any { datasetRows ->
                datasetRows.map { it.system }.toSet() != setOf("local", "reference") ||
                    datasetRows.map { it.stackPreparationId }.distinct().size != 1 ||
                    datasetRows.any { it.stackPreparationId.isBlank() }
            }
        ) return null
        if (byDataset.values.map { it.first().stackPreparationId }.distinct().size != sizeDatasets.size) return null
        if (rows.map { it.gitCommit }.distinct().size != 1 || rows.first().gitCommit.isBlank()) return null
        if (listOf(
                rows.map { it.localAppImageId }.toSet(),
                rows.map { it.localDbImageId }.toSet(),
                rows.map { it.referenceImageId }.toSet(),
            ).any { ids -> ids.size != 1 || ids.first().isBlank() }
        ) return null
        val datasetsByName = sizeDatasets.associateBy { it.name }
        if (rows.any { row -> datasetsByName[row.datasetName]?.xesBytes != row.xesBytes }) return null

        fun fit(system: String): LinearFit? {
            val systemRows = rows.filter { it.system == system }.associateBy { it.datasetName }
            return InferentialStatistics.fitLinear(
                xs = sizeDatasets.map { it.totalEvents.toDouble() },
                ys = sizeDatasets.map { systemRows.getValue(it.name).deltaBytes.toDouble() },
            )
        }

        val localFit = fit("local") ?: return null
        val referenceFit = fit("reference") ?: return null
        if (localFit.slope <= 0.0 || referenceFit.slope <= 0.0) return null
        return StorageEvidence(rows, localFit, referenceFit)
    }

    private fun storageGateLabel(
        isFinal: Boolean,
        rows: List<IsolatedStorageScalingResult>,
        evidence: StorageEvidence?,
    ): String = when {
        !isFinal -> "nie dotyczy przebiegu diagnostycznego"
        evidence != null -> "kompletna: ${evidence.rows.size / 2} świeżych stosów, ${evidence.rows.size} pomiarów"
        rows.isEmpty() -> "oczekuje na osobny pomiar"
        else -> "niekompletna lub niespójna"
    }

    private fun storageHeadline(evidence: StorageEvidence): String =
        "Trwały przyrost danych: model liniowy wskazuje ${bytesPerEvent(evidence.localFit.slope)} dla LOCAL i " +
            "${bytesPerEvent(evidence.referenceFit.slope)} dla REFERENCE, czyli ${number(evidence.referenceFit.slope / evidence.localFit.slope, 2)} raza większy przyrost marginalny REFERENCE."

    private fun storageInterpretation(evidence: StorageEvidence): String {
        val largest = evidence.rows.groupBy { it.datasetName }.values
            .maxBy { rows -> rows.first().xesBytes }
        val local = largest.single { it.system == "local" }
        val reference = largest.single { it.system == "reference" }
        return "Dla największego punktu `${local.datasetName}` trwały przyrost wyniósł " +
            "${mebibytes(local.deltaBytes)} MiB LOCAL i ${mebibytes(reference.deltaBytes)} MiB REFERENCE. " +
            "Nachylenie modelu wynosi ${bytesPerEvent(evidence.localFit.slope)} (R²=${number(evidence.localFit.r2, 3)}) " +
            "dla LOCAL oraz ${bytesPerEvent(evidence.referenceFit.slope)} (R²=${number(evidence.referenceFit.r2, 3)}) dla REFERENCE; " +
            "marginalny przyrost REFERENCE jest ${number(evidence.referenceFit.slope / evidence.localFit.slope, 2)} raza większy."
    }

    private fun storageTable(evidence: StorageEvidence, datasets: List<PreparedDataset>): String {
        val eventsByDataset = datasets.associate { it.name to it.totalEvents }
        return markdownTable(
            listOf("Zbiór", "Zdarzenia", "LOCAL delta [MiB]", "LOCAL/XES", "REFERENCE delta [MiB]", "REFERENCE/XES"),
            evidence.rows.groupBy { it.datasetName }.values
                .sortedBy { eventsByDataset.getValue(it.first().datasetName) }
                .map { rows ->
                    val local = rows.single { it.system == "local" }
                    val reference = rows.single { it.system == "reference" }
                    listOf(
                        local.datasetName, eventsByDataset.getValue(local.datasetName), mebibytes(local.deltaBytes),
                        number(local.deltaToXesRatio, 2), mebibytes(reference.deltaBytes),
                        number(reference.deltaToXesRatio, 2),
                    )
                },
        )
    }

    private fun bytesPerEvent(value: Double): String = "${number(value, 0)} B/zdarzenie"

    private fun verdictCounts(label: String, rows: List<BenchmarkComparisonResult>): String {
        val counts = rows.groupingBy { it.verdict }.eachCount()
        return "$label (${rows.size}): " +
            "LOCAL szybszy — ${counts["LOCAL_FASTER"] ?: 0}, REFERENCE szybszy — ${counts["REFERENCE_FASTER"] ?: 0}, " +
            "brak rozstrzygniętej różnicy — ${counts["NO_DIFFERENCE"] ?: 0}."
    }

    private fun patternSummary(rows: List<BenchmarkComparisonResult>): String {
        val valid = rows.filter { it.status == "OK" }
        val syntheticPrimary = valid.filter { it.role == "primary" && it.series in setOf("size-scaling", "variant-scaling") }
        val realPrimary = valid.filter { it.role == "primary" && it.series == "real-validation" }
        val like = valid.filter { it.operationLabel == "likeScan" }
        val ordering = valid.filter { it.operationLabel == "standardAttributesOrder" }
        return buildList {
            if (syntheticPrimary.isNotEmpty()) {
                add("Dane syntetyczne, zapytania główne: ${compactVerdicts(syntheticPrimary)}.")
            }
            if (realPrimary.isNotEmpty()) add("Logi rzeczywiste, zapytania główne: ${compactVerdicts(realPrimary)}.")
            if (like.isNotEmpty() || ordering.isNotEmpty()) {
                add(
                    "Kontrole: " + listOfNotNull(
                        "LIKE — ${compactVerdicts(like)}".takeIf { like.isNotEmpty() },
                        "sortowanie — ${compactVerdicts(ordering)}".takeIf { ordering.isNotEmpty() },
                    ).joinToString("; ") + ".",
                )
            }
        }.joinToString(" ")
    }

    private fun compactVerdicts(rows: List<BenchmarkComparisonResult>): String {
        val counts = rows.groupingBy { it.verdict }.eachCount()
        return "LOCAL ${counts["LOCAL_FASTER"] ?: 0}, REFERENCE ${counts["REFERENCE_FASTER"] ?: 0}, " +
            "nierozstrzygnięte ${counts["NO_DIFFERENCE"] ?: 0} (n=${rows.size})"
    }

    private fun datasetLabel(dataset: String): String = when (dataset) {
        "real-sepsis" -> "Sepsis"
        "real-bpic15-1" -> "BPIC15-1"
        "real-bpic15-2" -> "BPIC15-2"
        "real-bpic15-3" -> "BPIC15-3"
        "real-bpic15-4" -> "BPIC15-4"
        "real-bpic15-5" -> "BPIC15-5"
        "real-hospital" -> "BPIC11 (Hospital)"
        "real-bpic12" -> "BPIC12"
        "real-bpic13-incidents" -> "BPIC13 (incidents)"
        "real-bpic13-closed-problems" -> "BPIC13 (closed problems)"
        "real-bpic13-open-problems" -> "BPIC13 (open problems)"
        "real-hospital-billing" -> "Hospital Billing"
        "real-road-traffic" -> "Road Traffic"
        "real-bpic17" -> "BPIC17"
        else -> dataset
    }

    private fun realDatasetTable(datasets: List<PreparedDataset>): String = markdownTable(
        listOf("Log", "Ślady", "Zdarzenia", "Śr./med./p95/maks. zdarzeń na ślad", "Aktywności", "Warianty", "Śr. atrybutów zdarzenia", "Źródło"),
        datasets.sortedBy { it.totalEvents }.map {
            listOf(
                datasetLabel(it.name), it.traces, it.totalEvents,
                "${number(it.meanEventsPerTrace, 1)}/${number(it.medianEventsPerTrace, 1)}/${it.p95EventsPerTrace}/${it.maxEventsPerTrace}",
                it.activityCount, it.variantCount, number(it.meanEventAttributes, 1), doiLink(it.sourceDoi),
            )
        },
    )

    private fun realEffectMatrix(
        rows: List<BenchmarkComparisonResult>,
        querySpecs: List<BenchmarkQuerySpec>,
    ): String {
        val operations = querySpecs.filter { it.isInferential && it.isMeasuredFor("real-validation") }
        val byKey = rows.associateBy { it.datasetName to it.operationLabel }
        val datasetNames = rows.map { it.datasetName }.distinct().sortedBy(::datasetLabel)
        return markdownTable(
            listOf("Log") + operations.map { it.displayName },
            datasetNames.map { dataset ->
                listOf(datasetLabel(dataset)) + operations.map { operation ->
                    val row = byKey[dataset to operation.label]
                    if (row?.ratioReferenceToLocal == null || row.status != "OK") {
                        "—"
                    } else {
                        val marker = when (row.verdict) {
                            "LOCAL_FASTER" -> "L"
                            "REFERENCE_FASTER" -> "R"
                            else -> "≈"
                        }
                        "$marker ${number(row.ratioReferenceToLocal, 2)}×"
                    }
                }
            },
        )
    }

    private fun doiLink(doi: String?): String = doi ?: "—"

    private fun publishedLogPhrase(count: Int): String = when (count) {
        1 -> "Jeden opublikowany log"
        in 2..4 -> "$count opublikowane logi"
        else -> "$count opublikowanych logów"
    }

    private fun sizeRangeSentence(datasets: List<PreparedDataset>): String = when (datasets.size) {
        0 -> "W tym przebiegu nie ma punktu tej osi."
        1 -> "Ten diagnostyczny przebieg zawiera jeden punkt: ${formatInteger(datasets.single().totalEvents)} zdarzeń."
        else -> "W tym przebiegu liczba zdarzeń rośnie od ${formatInteger(datasets.first().totalEvents)} do ${formatInteger(datasets.last().totalEvents)} w ${datasets.size} punktach."
    }

    private fun formatInteger(value: Int): String = String.format(Locale.ROOT, "%,d", value).replace(',', ' ')

    private fun measurementGlossary(protocolVersion: Int): String = buildString {
        appendLine("- **`n` i para pomiarowa** — `n` to liczba poprawnych par użytych w danym wierszu. Para zawiera sąsiadujące wykonania tej samej operacji na tym samym datasecie: jedno LOCAL i jedno REFERENCE. Jeżeli którejś strony brakuje albo odpowiedzi tworzą MISMATCH, para dataset–zapytanie nie trafia do statystyki.")
        appendLine("- **`LOCAL [ms]` i `REFERENCE [ms]`** — mediany czasu pełnego żądania HTTP ze wszystkich poprawnych powtórzeń danego systemu. Mediana to wartość środkowa po uporządkowaniu czasów; jest mniej wrażliwa na pojedyncze skoki obciążenia niż średnia.")
        appendLine("- **`R/L` lub efekt** — iloraz `mediana REFERENCE / mediana LOCAL`. Efekt większy od 1 wskazuje krótszy czas LOCAL, mniejszy od 1 krótszy czas REFERENCE, a równy 1 identyczne mediany. Jest to mnożnik czasu, nie różnica w milisekundach.")
        appendLine("- **`95% CI` — 95% przedział ufności efektu** — określa niepewność oszacowania R/L, a nie zakres surowych czasów. Całe pary są losowane ze zwracaniem ${formatInteger(InferentialStatistics.BOOTSTRAP_RESAMPLES)} razy; dla każdej próby ponownie liczy się iloraz median, a granice stanowią percentyle 2,5% i 97,5%. W wielokrotnie powtarzanym eksperymencie około 95% przedziałów zbudowanych tą procedurą obejmowałoby rzeczywisty efekt.")
        appendLine("- **`p Holm`** — dwustronna p-wartość z parowanego testu rangowanych znaków Wilcoxona, skorygowana metodą Holma wewnątrz rodziny zapytań danego datasetu. Test wykorzystuje różnicę czasu wewnątrz każdej pary. Mała wartość oznacza, że dane są słabo zgodne z brakiem systematycznej różnicy; nie jest prawdopodobieństwem, że hipoteza zerowa jest prawdziwa.")
        appendLine("- **Werdykt** — kierunek jest rozstrzygnięty dopiero wtedy, gdy skorygowane `p Holm < ${number(InferentialStatistics.ALPHA, 2)}` oraz cały 95% CI leży po jednej stronie 1. W przeciwnym razie raport podaje brak rozstrzygniętej różnicy, nawet jeśli same mediany nie są równe.")
        appendLine("- **Rola zapytania** — zapytania główne odpowiadają na pytanie o wydajność badanego interpretera; kontrolne badają konkretne mechanizmy, takie jak sortowanie i `LIKE`; baseline opisowy pokazuje minimalny narzut żądania i nie otrzymuje p-wartości ani werdyktu przewagi.")
        appendLine("- **`MISMATCH`** — LOCAL i REFERENCE zwróciły semantycznie różne odpowiedzi. Taki przypadek pozostaje w surowych CSV do diagnozy, ale jego czas nie może być użyty do wniosku o wydajności.")
        appendLine(temporalStabilityExplanation(protocolVersion))
    }.trimEnd()

    private fun temporalStabilityExplanation(protocolVersion: Int): String = when {
        protocolVersion >= 22 ->
            "- **Diagnostyka stabilności czasowej** — raportowany efekt to `mediana czasu REFERENCE / mediana czasu LOCAL`. Przy co najmniej ${TemporalStability.MINIMUM_SAMPLES} parach ten sam efekt oblicza się osobno w pierwszej i ostatniej jednej trzeciej chronologicznej serii; środkowa część nie uczestniczy w tej kontroli. Kierunkowo niezależny iloraz tych dwóch efektów większy niż ${number(TemporalStability.MAX_EARLY_LATE_RATIO, 2)} daje jawne ostrzeżenie o dryfie, ale nie usuwa kompletnych par i nie zastępuje przedziału ufności, testu Wilcoxona ani korekty Holma. Stały próg 10% nie jest testem statystycznym i przy wielu porównaniach arbitralnie odrzucałby także wyniki zachowujące ten sam kierunek i duży efekt. Nie używa się mediany ilorazów pojedynczych par, ponieważ byłaby innym estymandem niż wynik i reagowałaby na dwa pasma kolejności LR/RL. Dryf bezwzględnych czasów obu systemów również pozostaje widoczny diagnostycznie."
        protocolVersion == 21 ->
            "- **Bramka stabilności czasowej** — raportowany efekt to `mediana czasu REFERENCE / mediana czasu LOCAL`. Przy co najmniej ${TemporalStability.MINIMUM_SAMPLES} parach ten sam efekt oblicza się osobno w pierwszej i ostatniej jednej trzeciej chronologicznej serii; środkowa część nie uczestniczy w kontroli. Kierunkowo niezależny iloraz tych dwóch efektów większy niż ${number(TemporalStability.MAX_EARLY_LATE_RATIO, 2)} unieważnia inferencyjne porównanie. Nie używa się mediany ilorazów pojedynczych par, ponieważ byłaby innym estymandem niż wynik i reagowałaby na dwa pasma kolejności LR/RL. Dryf bezwzględnych czasów obu systemów pozostaje widoczny diagnostycznie, lecz wspólne spowolnienie nie unieważnia stabilnego efektu parowanego. Dla opisowego baseline’u każdy dryf pozostaje tylko ostrzeżeniem widocznym obok IQR."
        protocolVersion == 20 ->
            "- **Bramka stabilności czasowej (historyczna)** — metodologia 20 porównuje medianę ilorazów `REFERENCE / LOCAL` z pierwszej i ostatniej jednej trzeciej par. Iloraz większy niż ${number(TemporalStability.MAX_EARLY_LATE_RATIO, 2)} unieważnia porównanie inferencyjne."
        else ->
            "- **Bramka stabilności czasowej (historyczna)** — osobno dla LOCAL i REFERENCE porównuje medianę pierwszej i ostatniej jednej trzeciej czasów. Iloraz większy niż ${number(TemporalStability.MAX_EARLY_LATE_RATIO, 2)} unieważnia porównanie inferencyjne."
    }

    private fun validityGateExplanation(protocolVersion: Int): String = if (protocolVersion >= 22) {
        "Bramka poprawności nie mierzy szybkości. Sprawdza kompletność danych, semantyczną porównywalność odpowiedzi, round-trip XES oraz dostępność pomiarów zasobów. Dryf pierwszej względem ostatniej części serii jest pokazany jako diagnostyka, ale stały próg 10% nie unieważnia poprawnych par. Baseline nie uczestniczy we wnioskowaniu: jego punkt jest pokazywany bez linii i z IQR."
    } else {
        "Bramka poprawności nie mierzy szybkości. Określa, czy dane z przebiegu są kompletne, semantycznie porównywalne i ustabilizowane w czasie. Trend przekraczający próg unieważnia zapytanie główne lub kontrolne. Baseline nie uczestniczy we wnioskowaniu: jego dryf pozostaje jawnym ostrzeżeniem, a punkt jest pokazywany bez linii i z IQR, lecz nie blokuje poprawnych porównań hipotez."
    }

    private fun queryDefinitionTable(querySpecs: List<BenchmarkQuerySpec>): String = markdownTable(
        listOf("Nazwa używana w raporcie", "Dokładne zapytanie PQL", "Rola"),
        querySpecs.map {
            listOf(it.displayName, "`${it.query}`", roleLabel(it.role.name))
        },
    )

    private fun effectTable(
        rows: List<BenchmarkComparisonResult>,
        queryTextByLabel: Map<String, String> = emptyMap(),
    ): String = markdownTable(
        listOf("Zbiór", "Operacja", "Rola", "n", "LOCAL [ms]", "REFERENCE [ms]", "R/L (95% CI)", "p Holm", "Werdykt"),
        rows.map {
            val exactQuery = queryTextByLabel[it.operationLabel]
            val operation = if (exactQuery == null) {
                "${it.displayName} (`${it.operationLabel}`)"
            } else {
                "${it.displayName} (`$exactQuery`)"
            }
            listOf(
                it.datasetName, operation, roleLabel(it.role), it.pairs,
                milliseconds(it.localMedianSeconds), milliseconds(it.referenceMedianSeconds),
                effect(it), pValue(it.holmPValue), verdictLabel(it.verdict),
            )
        },
    )

    private fun stabilityTable(rows: List<BenchmarkComparisonResult>): String = markdownTable(
        listOf(
            "Zbiór", "Operacja", "k", "LOCAL początek [ms]", "LOCAL koniec [ms]", "LOCAL iloraz",
            "REFERENCE początek [ms]", "REFERENCE koniec [ms]", "REFERENCE iloraz",
            "R/L początek", "R/L koniec", "R/L iloraz", "Wynik",
        ),
        rows.map {
            listOf(
                it.datasetName, it.displayName, it.stabilityWindowSamples,
                milliseconds(it.localEarlyMedianSeconds), milliseconds(it.localLateMedianSeconds),
                it.localEarlyLateRatio?.let { ratio -> number(ratio, 3) },
                milliseconds(it.referenceEarlyMedianSeconds), milliseconds(it.referenceLateMedianSeconds),
                it.referenceEarlyLateRatio?.let { ratio -> number(ratio, 3) },
                it.pairedEarlyMedianRatio?.let { ratio -> number(ratio, 3) },
                it.pairedLateMedianRatio?.let { ratio -> number(ratio, 3) },
                it.pairedEarlyLateRatio?.let { ratio -> number(ratio, 3) },
                when {
                    it.details.startsWith(TemporalStability.DETAILS_PREFIX) -> "NIESTABILNE — WYKLUCZONE"
                    it.details.startsWith(TemporalStability.DIAGNOSTIC_DETAILS_PREFIX) -> "DRYF — OSTRZEŻENIE"
                    it.details.startsWith(TemporalStability.DESCRIPTIVE_DETAILS_PREFIX) -> "DRYF OPISOWY"
                    it.details.startsWith(TemporalStability.COMMON_MODE_DETAILS_PREFIX) -> "EFEKT STABILNY — DRYF WSPÓLNY"
                    else -> "OK"
                },
            )
        },
    )

    private fun resourceTable(
        io: List<ContainerIoBenchmarkResult>,
        memory: List<MemorySummary>,
        contextual: Boolean,
    ): String {
        if (contextual) return contextualResourceTable(io, memory)
        val memoryBySystem = mapOf(
            "local" to memory.firstOrNull { it.component == "local-total" && it.phase == MEMORY_PHASE_QUERIES },
            "reference" to memory.firstOrNull { it.component == "reference-total" && it.phase == MEMORY_PHASE_QUERIES },
        )
        return markdownTable(
            listOf("System", "Mediana pamięci [MiB]", "Maks. pamięć [MiB]", "Block read [MiB]", "Block write [MiB]", "Read ops", "Write ops"),
            listOf("local", "reference").map { system ->
                val rows = io.filter { it.system == system && ContainerIoValidity.hasUsableBlockCounters(it) }
                val mem = memoryBySystem[system]
                listOf(
                    system.uppercase(), mebibytes(mem?.medianBytes), mebibytes(mem?.peakBytes),
                    mebibytes(rows.mapNotNull { it.blockReadBytes }.sum()),
                    mebibytes(rows.mapNotNull { it.blockWriteBytes }.sum()),
                    rows.mapNotNull { it.blockReadOperations }.sum(), rows.mapNotNull { it.blockWriteOperations }.sum(),
                )
            },
        )
    }

    private fun contextualResourceTable(
        io: List<ContainerIoBenchmarkResult>,
        memory: List<MemorySummary>,
    ): String {
        fun median(values: List<Long>): Long? = values.takeIf { it.isNotEmpty() }
            ?.map(Long::toDouble)
            ?.let { ThesisStatistics.quantile(it, 0.50).toLong() }
        fun ioBlockMedians(system: String): List<ContainerIoBenchmarkResult> = io.filter {
            it.system == system && it.phase == "query" && ContainerIoValidity.hasUsableBlockCounters(it)
        }
        return markdownTable(
            listOf(
                "System", "Mediana median pamięci bloków [MiB]", "Maks. pamięć [MiB]",
                "Mediana Block read/blok [MiB]", "Mediana Block write/blok [MiB]",
                "Mediana read ops/blok", "Mediana write ops/blok",
            ),
            listOf("local", "reference").map { system ->
                val component = if (system == "local") "local-total" else "reference-total"
                val memoryRows = memory.filter { it.component == component && it.phase == MEMORY_PHASE_QUERIES }
                val ioRows = ioBlockMedians(system)
                val blocks = ioRows.groupBy { it.datasetName to it.operationLabel }.values
                fun blockMedian(selector: (ContainerIoBenchmarkResult) -> Long?): Long? =
                    median(blocks.mapNotNull { rows -> rows.mapNotNull(selector).takeIf { it.isNotEmpty() }?.sum() })
                listOf(
                    system.uppercase(),
                    mebibytes(median(memoryRows.map { it.medianBytes })),
                    mebibytes(memoryRows.maxOfOrNull { it.peakBytes }),
                    mebibytes(blockMedian { it.blockReadBytes }),
                    mebibytes(blockMedian { it.blockWriteBytes }),
                    blockMedian { it.blockReadOperations },
                    blockMedian { it.blockWriteOperations },
                )
            },
        )
    }

    private fun effect(row: BenchmarkComparisonResult): String =
        if (row.ratioReferenceToLocal == null || row.confidenceLow == null || row.confidenceHigh == null) "—"
        else "${number(row.ratioReferenceToLocal, 2)} (${number(row.confidenceLow, 2)}–${number(row.confidenceHigh, 2)})"

    private fun milliseconds(seconds: Double?): String = seconds?.let { number(it * 1_000.0, 2) } ?: "—"
    private fun mebibytes(bytes: Long?): String = bytes?.let { number(it / (1024.0 * 1024.0), 1) } ?: "—"
    private fun number(value: Double?, digits: Int): String = value?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"
    private fun pValue(value: Double?): String = when {
        value == null -> "—"
        value < 0.0001 -> "<0.0001"
        else -> number(value, 4)
    }

    private fun pairNoun(count: Int): String {
        val lastTwo = count % 100
        val last = count % 10
        return when {
            count == 1 -> "para"
            last in 2..4 && lastTwo !in 12..14 -> "pary"
            else -> "par"
        }
    }

    private fun roleLabel(role: String): String = when (role.lowercase()) {
        "primary" -> "główne"
        "control" -> "kontrolne"
        "baseline" -> "baseline opisowy"
        "validation" -> "walidacja"
        "supplementary" -> "uzupełniające"
        else -> role
    }

    private fun verdictLabel(verdict: String): String = when (verdict) {
        "LOCAL_FASTER" -> "LOCAL szybszy"
        "REFERENCE_FASTER" -> "REFERENCE szybszy"
        "NO_DIFFERENCE" -> "brak rozstrzygniętej różnicy"
        "DESCRIPTIVE" -> "wynik opisowy"
        "INVALID" -> "wynik nieważny"
        else -> verdict
    }

    private fun markdownTable(headers: List<String>, rows: List<List<Any?>>): String = buildString {
        appendLine(headers.joinToString(" | ", prefix = "| ", postfix = " |") { escape(it) })
        appendLine(headers.joinToString(" | ", prefix = "| ", postfix = " |") { "---" })
        rows.forEach { row ->
            appendLine(row.joinToString(" | ", prefix = "| ", postfix = " |") { escape(it?.toString() ?: "—") })
        }
    }.trimEnd()

    private fun escape(value: String): String = value.replace("|", "\\|").replace("\n", " ")
}
