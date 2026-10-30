# Podróżnicze Slideshow

Aplikacja Android do prezentowania zdjęć i krótkich filmów z podróży na tablecie/TV, z animacjami geograficznymi i muzyką skojarzoną z państwem zdjęcia.

## Funkcje

- **Skanowanie folderu** (wybór przez systemowy picker, w tym podfolderów) — zdjęcia i filmy; odczyt GPS i daty z EXIF (z uwzględnieniem strefy czasowej `OffsetTimeOriginal`), przy potrzebie także z surowych tagów DMS oraz XMP (zdjęcia edytowane w Google Photos/Samsung); dla filmów z metadanych kontenera (ISO-6709).
- **Mapa przed zdjęciem z lokalizacją**:
  - zmiana państwa → mapa świata z podświetlonym państwem (kafelki OSM + wyróżnienie granic GeoJSON), animowane przybliżenie na państwo, znacznik głównego punktu państwa, potem mapa lokalizacji zdjęcia,
  - to samo państwo, ale odległość od poprzedniego zdjęcia z GPS ≥ próg (domyślnie 5 km) → mapa lokalizacji,
  - odległość < próg lub brak GPS → samo zdjęcie (bez mapy).
- **Muzyka wg państwa** (hybryda): własne pliki z wybranego folderu (podfoldery np. `PL`/`POL`/`Poland`, `US`/`USA`, lub `_default` — patrz [Struktura muzyki](#struktura-muzyki)) — start i każda zmiana państwa zaczynają od **losowego utworu** (playlista tasowana przy każdym uruchomieniu); gdy brak plików, generowany jest instrumentalny podkład ambietowy (proceduralny WAV, unikatowy dla państwa).
- **Trasa między zdjęciami**: gdy oba zdjęcia mają GPS i odległość ≥ próg z ustawień (domyślnie 5 km) → sekwencja: powrót na mapę wycentrowaną na punkcie (ten sam zoom co przy dotarciu, krótki chwyt) → płynny **zoom-out przy stałym środku na punkcie** (docelowy zoom zależny od długości trasy — stała prędkość przeglądu) → **rysowanie trasy drogowej** wyznaczanej przez [OSRM](https://router.project-osrm.org) (fallback: linia prosta bez internetu) z symbolem samolotu na czubku linii skierowanym zgodnie z kierunkiem, mapa podąża za czubkiem → po dotarciu **czerwona pinezka** na miejscu docelowym → przybliżenie na cel. Fazy przyspieszane tak, aby zawsze domknąć się w skonfigurowanym czasie mapy. Trasa pobierana z wyprzedzeniem, by nie czekać na OSRM w momencie przejścia.
- **Pinezki odwiedzonych punktów**: czerwone pinezki z lądowań (i lokalizacji nowych państw) zostają na mapie przez cały pokaz — ślad podróży; wcześniej narysowane trasy też zostają (ta sama linia i kolor).
- **Przejście mapa↔zdjęcie**: płynny crossfade (czas regulowany w ustawieniach, 0.2–5 s).
- **Sterowanie**: tap = pauza/wznowienie (obsługiwany też na mapie i zdjęciu; na zdjęciu podwójne tapnięcie resetuje powiększenie), pasek dolny: ▶/⏸, ⏭ następne, ⏮ poprzednie, ⏪/⏩ poprzedni/następny dzień (skok do planszy dnia lub map prowadzących do jego pierwszego zdjęcia), replay bieżącego państwa, restart całości, ustawienia. Przesunięcie w lewo na zdjęciu = następne, w prawo = poprzednie; cofanie najpierw powtarza blok map danego zdjęcia (mapa lokalizacji/trasa/wstęp państwa), a dopiero potem wraca do poprzedniego zdjęcia. Ustawienia dostępne zawsze (ikona w prawym górnym rogu).
- **Ręczne przeglądanie mapy i zdjęć**: pan/zoom na mapie oraz powiększanie zdjęcia (i filmu) dwoma palcami z przesuwaniem (do 5×, podwójne tapnięcie = reset) działa zawsze — w trakcie przeglądania automat nie przeskakuje dalej; powrót do sekwencji po 5 s od ostatniej akcji użytkownika.
- **Zatrzymanie skanowania** w trakcie — do pokazu trafiają zdjęcia przeskanowane do tej pory.
- **Wąskie zdjęcia** (pionowe na poziomym ekranie oraz każde, które dopasowane do ekranu zajęłoby najwyżej połowę jego szerokości): zdjęcie w całości na tle swojej rozmytej, przyciemnionej kopii (rozmycie od Androida 12), z powolnym przybliżeniem o 12% i lekkim dryfem w dół (efekt Ken Burns).
- **Filmy**: krótkie klipy (mp4/mov/3gp/mkv/webm/m4v) wplatane między zdjęcia (ExoPlayer, bez kontrolek); lokalizacja i data z metadanych filmu; czas = długość klipu, ucinany do limitu z ustawień; muzyka ściszana na czas filmu.
- **Nazwy miejsc**: reverse geocoding [Nominatim](https://nominatim.openstreetmap.org) (1 zapytanie/s, siatka ~1 km, cache na dysku `places.json`) — nazwa miejscowości obok flagi na mapie i zdjęciu.
- **Płynna zmiana muzyki**: przy zmianie państwa poprzedni utwór wycisza się, a nowy narasta (crossfade ~1.8 s) zamiast twardego przełączenia.
- **Plansze dni**: przy zmianie dnia kalendarzowego (czas lokalny z EXIF) karta „Dzień 3 — Florencja” z datą na tle półprzezroczystego pierwszego zdjęcia z tego dnia (wąskie zdjęcie powiększone do min. 70% szerokości ekranu); numeracja zaczyna się od nowa po przerwie dłuższej niż 3 dni (osobne wyjazdy w jednym folderze).
- **Plakietka w lewym górnym rogu**: flaga państwa i nazwa miejscowości (gdy znana) na mapie lokalizacji, trasie i zdjęciu.
- **Opcje**: przerwa między zdjęciami, czas mapy lokalizacji, czas fazy „mapa świata”, próg dystansu (km), głośność, kolejność (od najstarszych/najnowszych), pomijanie zdjęć bez GPS, czas przejścia mapa↔zdjęcie, filmy on/off i ich maks. długość, plansze dni, nazwy miejsc.

## Wymagania

- Android 9+ (API 28+); rozmycie tła wąskich zdjęć od Androida 12.
- Internet: kafelki map OSM, trasy OSRM i nazwy miejsc Nominatim (zapamiętywane w cache). Granice państw są wbudowane w aplikację; bez sieci pokaz działa z linią prostą zamiast trasy i bez nowych nazw miejsc.
- Budowanie: Android Studio (Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17/21) lub `gradlew.bat :app:assembleDebug`.

## Struktura muzyki

```
<Muzyka>/
  PL/  *.mp3
  IT/  *.mp3
  US/  *.mp3            # USA: US, USA lub „United States of America”
  _default/  *.mp3      # fallback dla państw bez folderu
```

Rozpoznawane: kod ISO-2 (`PL`), ISO-3 (`POL`), angielska nazwa państwa (`Poland`); wielkość liter bez znaczenia; nazwa państwa musi być zgodna z mapą (`assets/world/countries.geo.json`). Foldery państw muszą leżeć bezpośrednio w folderze muzyki, a pliki bezpośrednio w nich. Formaty: mp3, ogg, wav, m4a, flac, aac.

## Uruchomienie

1. Uruchom aplikację → wybierz folder ze zdjęciami (Ustawienia lub przycisk na ekranie startowym).
2. (Opcjonalnie) wybierz folder z muzyką.
3. „Wczytaj zdjęcia” → „▶ Start”.

Podgląd map podczas pokazu jest animowany; mapę można ręcznie przeglądać, a zdjęcia powiększać dwoma palcami — pokaz rusza dalej 5 s po ostatnim geście. Panel ustawień sam zatrzymuje pokaz i wznawia po zamknięciu.

## Szczegóły techniczne

- Jetpack Compose (Material 3), ViewModel + StateFlow, korutyny (maszyna stanów pokazu na segmentach).
- Mapy: [osmdroid](https://github.com/osmdroid/osmdroid) (kafelki Mapnik OSM), granice państw: `assets/world/countries.geo.json` (world.geo.json, ISO-3) + point-in-polygon (ray casting; rozstrzyganie enklaw przez najmniejszy bounding box).
- Zdjęcia: Storage Access Framework (DocumentsContract) + `androidx.exifinterface`; koordynaty GPS z EXIF (wartości rational DMS) lub XMP (`exif:GPSLatitude/Longitude`).
- Odtwarzacz muzyki: `MediaPlayer` z play-listą, crossfade przy zmianie państwa i ściszaniem (ducking) na czas filmów; fallback: generator ambientu (sine pad + pentatonika, 22.05 kHz mono WAV).
- Filmy: `androidx.media3` (ExoPlayer + PlayerView), metadane przez `MediaMetadataRetriever`.
- Zdjęcia w UI: Coil; efekt Ken Burns i zoom gestami przez `graphicsLayer`.
- Nazwy miejsc: `PlaceResolver` — Nominatim reverse (`zoom=10`, `accept-language=pl`), limit 1 zapytanie/s, własny User-Agent, cache `files/places.json` (także wyniki „brak nazwy”).
- Plansze dni: dzień liczony z czasu lokalnego zdjęcia; nowa numeracja po przerwie > 3 dni.