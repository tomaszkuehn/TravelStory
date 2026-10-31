# Podróżnicze Slideshow

Aplikacja Android do prezentowania zdjęć i krótkich filmów z podróży na tablecie/TV, z animacjami geograficznymi i muzyką skojarzoną z państwem zdjęcia.

## Funkcje

- **Skanowanie folderu** (wybór przez systemowy picker, w tym podfolderów) — zdjęcia i filmy; odczyt GPS i daty z EXIF (z uwzględnieniem strefy czasowej `OffsetTimeOriginal`), przy potrzebie także z surowych tagów DMS oraz XMP (zdjęcia edytowane w Google Photos/Samsung); dla filmów z metadanych kontenera (ISO-6709).
- **Mapa przed zdjęciem z lokalizacją**:
  - zmiana państwa → mapa świata z podświetlonym państwem (kafelki OSM + wyróżnienie granic GeoJSON), animowane przybliżenie na państwo, znacznik głównego punktu państwa, potem mapa lokalizacji zdjęcia — powolne przybliżenie na miejsce (wypełnia czas mapy lokalizacji, łagodnie zwalnia przy celu), po nim tylko 1 s postoju i przejście do zdjęć,
  - to samo państwo, ale odległość od poprzedniego zdjęcia z GPS ≥ próg (domyślnie 5 km) → mapa lokalizacji,
  - odległość < próg lub brak GPS → samo zdjęcie (bez mapy).
- **Muzyka wg państwa** (hybryda): własne pliki z wybranego folderu (podfoldery np. `PL`/`POL`/`Poland`, `US`/`USA`, lub `_default` — patrz [Struktura muzyki](#struktura-muzyki)) — start i każda zmiana państwa zaczynają od **losowego utworu** (playlista tasowana przy każdym uruchomieniu); gdy brak plików, generowany jest instrumentalny podkład ambietowy (proceduralny WAV, unikatowy dla państwa).
- **Trasa między zdjęciami**: gdy oba zdjęcia mają GPS i odległość ≥ próg z ustawień (domyślnie 5 km) → sekwencja: mapa od razu (bez animacji oddalania) w **zoomie zależnym od długości trasy**, wycentrowana na punkcie startu — stała prędkość przeglądu, mapa podąża za czubkiem linii → **rysowanie trasy drogowej** wyznaczanej przez [OSRM](https://router.project-osrm.org) dla wybranego sposobu podróży (samochód / rower / pieszo; cache na dysku; gdy zdjęcie zrobiono z dala od drogi, odcinek od pinezki do drogi jest przerywany) albo **przerywanego łuku lotu** — dla skoków ≥ 700 km i tam, gdzie nie ma sensownej drogi (wyspy, inne kontynenty, objazd > 2,5× dłuższy niż w linii prostej); bez internetu łuk zastępczo, a trasa pobierana ponownie po 30 s — na czubku linii symbol środka transportu: samolot na lotach, samochód (widok z góry, obrócony zgodnie z kierunkiem jazdy) w trybie samochodowym, ikonka roweru lub pieszego w pozostałych → po dotarciu **czerwona pinezka** na miejscu docelowym → przybliżenie na cel. Fazy przyspieszane tak, aby zawsze domknąć się w skonfigurowanym czasie mapy. Trasa pobierana z wyprzedzeniem, by nie czekać na OSRM w momencie przejścia.
- **Pinezki odwiedzonych punktów**: czerwone pinezki z lądowań (i lokalizacji nowych państw) zostają na mapie przez cały pokaz — ślad podróży; każda ma w główce **numer kolejności** odwiedzenia (cyfry ok. połowy wysokości pinezki przy zbliżeniu, mniejsze przy oddaleniu; dłuższe numery poszerzają główkę); wcześniej narysowane trasy też zostają (ta sama linia i kolor).
- **Przejście mapa↔zdjęcie**: płynny crossfade (czas regulowany w ustawieniach, 0.2–5 s).
- **Sterowanie**: tap = pauza/wznowienie (obsługiwany też na mapie i zdjęciu; na zdjęciu podwójne tapnięcie resetuje powiększenie), pasek dolny: ▶/⏸, ⏭ następne, ⏮ poprzednie, ⏪/⏩ poprzedni/następny dzień (skok do pierwszej mapy lub zdjęcia danego dnia), replay bieżącego państwa, restart całości, ustawienia. Przesunięcie w lewo na zdjęciu = następne, w prawo = poprzednie; cofanie najpierw powtarza blok map danego zdjęcia (mapa lokalizacji/trasa/wstęp państwa), a dopiero potem wraca do poprzedniego zdjęcia. Ustawienia dostępne zawsze (ikona w prawym górnym rogu).
- **Ręczne przeglądanie mapy i zdjęć**: pan/zoom na mapie oraz powiększanie zdjęcia (i filmu) dwoma palcami z przesuwaniem (do 5×, podwójne tapnięcie = reset) działa zawsze — w trakcie przeglądania automat nie przeskakuje dalej; powrót do sekwencji po 5 s od ostatniej akcji użytkownika.
- **Zatrzymanie skanowania** w trakcie — do pokazu trafiają zdjęcia przeskanowane do tej pory.
- **Wąskie zdjęcia** (pionowe na poziomym ekranie oraz każde, które dopasowane do ekranu zajęłoby najwyżej połowę jego szerokości): zdjęcie w całości na tle swojej rozmytej, przyciemnionej kopii (rozmycie od Androida 12), z powolnym przybliżeniem o 12% i lekkim dryfem w dół (efekt Ken Burns).
- **Filmy**: krótkie klipy (mp4/mov/3gp/mkv/webm/m4v) wplatane między zdjęcia (ExoPlayer, bez kontrolek); lokalizacja i data z metadanych filmu; czas = długość klipu, ucinany do limitu z ustawień; muzyka wyciszana (płynnie) na czas filmu z dźwiękiem — film bez ścieżki audio gra z muzyką.
- **Nazwy miejsc**: reverse geocoding [Nominatim](https://nominatim.openstreetmap.org) (1 zapytanie/s, siatka ~1 km, cache na dysku `places.json`) — nazwa miejscowości obok flagi na mapie i zdjęciu.
- **Płynna zmiana muzyki**: przy zmianie państwa poprzedni utwór wycisza się, a nowy narasta (crossfade ~1.8 s) zamiast twardego przełączenia.
- **Ekran startowy**: po wczytaniu zdjęć wyświetla się i czeka na przycisk ▶ Start (po starcie zostaje jeszcze ok. 3 s, gdy rusza muzyka); zawiera konfigurowalny tytuł (domyślnie nazwa folderu ze zdjęciami), zakres dat wyjazdu i flagi odwiedzonych państw na tle półprzezroczystego, powoli przybliżanego kolażu do 6 zdjęć wybranych równomiernie z całego pokazu; po starcie gra już muzyka pierwszego państwa. Wyłączany w ustawieniach.
- **Informacja o dniu**: przy zmianie dnia kalendarzowego (czas lokalny z EXIF) pod flagą w lewym górnym rogu pojawia się mała etykieta „Dzień 3  14.07.2025” — na pierwszej mapie prowadzącej do pierwszego zdjęcia tego dnia (bez osobnej planszy) lub na samym zdjęciu; numeracja zaczyna się od nowa po przerwie dłuższej niż 3 dni (osobne wyjazdy w jednym folderze).
- **Plakietka w lewym górnym rogu**: flaga państwa i nazwa miejscowości (gdy znana) na mapie lokalizacji, trasie i zdjęciu.
- **Podsumowanie na końcu** (opcja): mapa przelatuje do całej trasy z pinezkami, a na dole pojawia się tytuł, daty, statystyki liczone od zera (km, dni, kraje, miejsca, zdjęcia, filmy) i flagi odwiedzonych krajów; muzyka cichnie przez ~9 s.
- **Licznik kilometrów** (opcja): podczas rysowania trasy w lewym dolnym rogu „+124 km · łącznie 1 380 km” (ikona samochodu/roweru/pieszego, ✈ dla lotu).
- **Zmiana zdjęć w rytm muzyki** (opcja, domyślnie wyłączona): tempo i faza utworu wykrywane na telefonie (pierwsze 45 s, wynik zapamiętywany); zdjęcie zmienia się na najbliższym uderzeniu (najwyżej pół taktu od zadanego czasu). Utwory bez wyraźnego rytmu i generowane tło ambient nie są synchronizowane.
- **Przejścia między zdjęciami** (opcja): przenikanie, przesunięcie w kierunku podróży (wg położenia GPS kolejnych zdjęć) z białym błyskiem przy zmianie dnia, albo mieszane (przenikanie/przesunięcie/zoom).
- **Ken Burns** (opcja): wyłączony / tylko wąskie zdjęcia / wszystkie; opcjonalnie przybliżanie w stronę twarzy (wbudowany `android.media.FaceDetector`, wykrywanie kilka zdjęć naprzód).
- **Styl mapy** (opcja): standardowa (OSM Mapnik), teren (OpenTopoMap), jasna i ciemna (kafelki OSM przekolorowane filtrem na telefonie), satelita (Esri World Imagery) — wszystkie bez klucza API.
- **Eksport do wideo (YouTube)**: przycisk „⏺ Nagraj wideo” na ekranie startowym i końcowym — pokaz odtwarza się od początku i jest nagrywany z ekranu (MediaProjection) razem z dźwiękiem aplikacji (przechwytywanie odtwarzania tylko z tej aplikacji, bez mikrofonu i innych aplikacji) do MP4 H.264 + AAC 48 kHz, 30 kl./s, maks. 1080p w proporcjach ekranu (~12 Mb/s). Pauzy (pasek sterowania, ustawienia, wyjście z aplikacji) są wycinane z filmu; nagranie kończy się samo na ostatnim zdjęciu lub po podsumowaniu (można też zakończyć z powiadomienia). Plik trafia do `Filmy/Podróżnicze Slideshow`, a okno po zapisie pozwala go od razu udostępnić (np. do aplikacji YouTube). Wymaga Androida 10+; bez zgody na nagrywanie dźwięku film jest niemy. Orientacja ekranu jest blokowana na czas nagrania.
- **Opcje**: przerwa między zdjęciami, czas mapy lokalizacji, czas fazy „mapa świata”, sposób podróży (samochód/rower/pieszo), próg dystansu (km), głośność, kolejność (od najstarszych/najnowszych), pomijanie zdjęć bez GPS, czas przejścia mapa↔zdjęcie, filmy on/off i ich maks. długość, ekran startowy i jego tytuł, informacja o dniu, nazwy miejsc.

## Wymagania

- Android 9+ (API 28+); rozmycie tła wąskich zdjęć od Androida 12.
- Internet: kafelki map OSM, trasy OSRM i nazwy miejsc Nominatim (zapamiętywane w cache). Granice państw są wbudowane w aplikację; bez sieci pokaz działa z łukiem zamiast niepobranych tras i bez nowych nazw miejsc.
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
- Mapy: [osmdroid](https://github.com/osmdroid/osmdroid) (kafelki Mapnik OSM; animacje mapy liczone od czasu na klatkach ekranu, pamięć 160 kafelków, wcześniejsze żądanie kafelków docelowego widoku i wzdłuż trasy, tło ładowania w kolorze stylu), granice państw: `assets/world/countries.geo.json` (world.geo.json, ISO-3) + point-in-polygon (ray casting; rozstrzyganie enklaw przez najmniejszy bounding box).
- Zdjęcia: Storage Access Framework (DocumentsContract) + `androidx.exifinterface`; koordynaty GPS z EXIF (wartości rational DMS) lub XMP (`exif:GPSLatitude/Longitude`).
- Odtwarzacz muzyki: `MediaPlayer` z play-listą, crossfade przy zmianie państwa i wyciszaniem na czas filmów ze ścieżką audio (`METADATA_KEY_HAS_AUDIO`); fallback: generator ambientu (sine pad + pentatonika, 22.05 kHz mono WAV).
- Filmy: `androidx.media3` (ExoPlayer + PlayerView), metadane przez `MediaMetadataRetriever`.
- Zdjęcia w UI: Coil; efekt Ken Burns (z punktem skupienia na twarzach z `FaceFocus`) i zoom gestami przez `graphicsLayer`.
- Rytm muzyki: `BeatAnalyzer` — dekodowanie `MediaExtractor`/`MediaCodec`, obwiednia narastania głośności, autokorelacja (70–180 BPM, preferencja ~120) i dopasowanie grzebienia; wynik w preferencjach `beat_grids`.
- Nazwy miejsc: `PlaceResolver` — Nominatim reverse (`zoom=10`, `accept-language=pl`), limit 1 zapytanie/s, własny User-Agent, cache `files/places.json` (także wyniki „brak nazwy”).
- Trasy: `RouteResolver` — OSRM (samochód: router.project-osrm.org, rower/pieszo: routing.openstreetmap.de `routed-bike`/`routed-foot`; `overview=full`), limit 1 zapytanie/s, uproszczenie Douglasa-Peuckera (tolerancja ~0,04% przekątnej odcinka), cache `files/routes/` (plik na odcinek i sposób podróży, także decyzja „brak drogi”); dojścia > 30 m od drogi do pinezek rysowane przerywaną linią; animacja linii proporcjonalna do odległości (stała prędkość czubka); łuk lotu = ortodroma z lekkim wygięciem ku biegunowi; błędy sieci/429 nie są zapamiętywane.
- Eksport wideo: `ScreenRecorder` — `VirtualDisplay` → `MediaCodec` (AVC, powtarzanie klatki przy statycznym ekranie), `AudioPlaybackCaptureConfiguration` (UID aplikacji) → AAC, `MediaMuxer` do pliku z `MediaStore`; usługa pierwszoplanowa typu `mediaProjection` (`RecordService`).
- Informacja o dniu: dzień liczony z czasu lokalnego zdjęcia; nowa numeracja po przerwie > 3 dni.