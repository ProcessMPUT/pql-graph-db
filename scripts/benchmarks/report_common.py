"""Shared PQL operation descriptions and Markdown formatting; no analysis or file IO."""

def number(value, scale=1):
    if value in (None, ""):
        return "—"
    return f"{float(value) * scale:.4g}".replace(".", ",")


def table(headers, data):
    def line(values):
        return "| " + " | ".join(str(v).replace("|", "&#124;") for v in values) + " |"
    return "\n".join([line(headers), line(["---"] * len(headers)), *[line(v) for v in data]])


HYPOTHESIS = ("Implementacja języka Process Query Language wykorzystująca grafową bazę danych osiąga niższe czasy "
              "odpowiedzi dla zapytań, które wymagają przechodzenia między poziomami hierarchii XES, niż implementacja "
              "referencyjna wykorzystująca bazę relacyjną.")

OPERATIONS = {
    "minimalWindow": ("Minimalna odpowiedź", "Zapytanie pobiera jeden log, jeden ślad i jedno zdarzenie. "
        "Jest punktem odniesienia dla pełnej obsługi HTTP, wraz z wykonaniem zapytania i rekonstrukcją odpowiedzi."),
    "hierarchyWindow": ("Pobranie ograniczonej hierarchii", "Zapytanie pobiera log, do dziesięciu śladów i do dwudziestu "
        "zdarzeń na ślad. W serii rozmiaru odpowiedź zawsze obejmuje 100 zdarzeń: rośnie log źródłowy, "
        "a wielkość odpowiedzi pozostaje stała."),
    "hoistedPositive": ("Warunek śladu zależny od zdarzenia", "Zapytanie wybiera ślady zawierające aktywność activity-10. "
        "W serii rozmiaru warunek spełnia 100% śladów. Zmienia się rozmiar logu przy stałym odsetku dopasowań."),
    "variantGroupCount": ("Grupowanie wariantów", "Zapytanie grupuje ślady według sekwencji nazw zdarzeń i zwraca liczności "
        "do trzech największych grup. W serii wariantów grupy mają odpowiednio 2000, 20 lub 1 ślad. LOCAL korzysta "
        "z identyfikatorów wariantów obliczonych przy imporcie, więc wynik obejmuje tę optymalizację, "
        "a nie wyłącznie reprezentację grafową."),
    "genericVariantGroup": ("Grupowanie sekwencji kosztów", "Zapytanie grupuje ślady według sekwencji wartości cost:total, "
        "bez identyfikatora wariantu nazw. W serii rozmiaru każdy ślad ma tę samą sekwencję kosztów. Zmiana "
        "atrybutu względem grupowania nazw oznacza, że różnica czasów nie izoluje wkładu samej optymalizacji."),
    "hierarchyCardinality": ("Zliczanie hierarchii", "Zapytanie zwraca liczby logów, śladów i zdarzeń. "
        "Zakres agregacji rośnie z logiem, a odpowiedź zachowuje stałą strukturę."),
    "globalEventAggregation": ("Agregacja wszystkich zdarzeń", "Zapytanie zlicza wszystkie zdarzenia w logu i wyznacza "
        "najwcześniejszy oraz najpóźniejszy znacznik czasu. Agregaty obejmują cały log, choć limity ograniczają "
        "liczbę elementów w odpowiedzi."),
    "standardAttributesOrder": ("Sortowanie atrybutów", "Zapytanie sortuje zdarzenia według czasu, nazwy, kosztu i attr_1, "
        "a następnie pobiera ograniczoną odpowiedź. Wszystkie klucze występują w danych syntetycznych, "
        "lecz attr_1 ma stałą wartość."),
    "realStandardAttributesOrder": ("Sortowanie na logach rzeczywistych", "Zapytanie sortuje zdarzenia według czasu, "
        "nazwy, grupy organizacyjnej i zasobu, po czym pobiera ograniczoną odpowiedź. Logi różnią się obecnością "
        "tych atrybutów i liczbą zwracanych zdarzeń."),
    "eventEquality": ("Równość z dopasowaniami", "Zapytanie wybiera zdarzenia o nazwie activity-5 i zwraca ograniczoną "
        "odpowiedź. W serii rozmiaru pasuje co dziesiąte zdarzenie; wraz z logiem rośnie liczba dopasowań."),
    "likeMatching": ("LIKE z dopasowaniami", "Zapytanie wyszukuje wzorzec %activity-5% w nazwach zdarzeń. "
        "W badanych danych wybiera te same zdarzenia co warunek równości, czyli 10% zdarzeń logu."),
    "likeNoMatch": ("LIKE bez dopasowań", "Zapytanie wyszukuje wzorzec %zzq%, nieobecny w danych syntetycznych. "
        "Wynik pokazuje koszt wyszukiwania bez rekonstrukcji zdarzeń."),
    "realLikeNoMatch": ("LIKE bez dopasowań na logach rzeczywistych", "Zapytanie szuka wzorca %zzq% w opublikowanych logach. "
        "Kontrola liczby zdarzeń potwierdza brak dopasowań, więc wynik opisuje wyszukiwanie bez rekonstrukcji zdarzeń."),
}
