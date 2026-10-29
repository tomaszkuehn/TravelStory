# Podróżnicze Slideshow

Aplikacja Android do prezentowania zdjęć z podróży na tablecie/TV, z animacjami geograficznymi i muzyką skojarzoną z państwem zdjęcia.

## Funkcje

- **Skanowanie folderu** (wybór przez systemowy picker, w tym podfolderów) — odczyt GPS i daty z EXIF; przy potrzebie także z surowych tagów DMS oraz XMP (zdjęcia edytowane w Google Photos/Samsung).
- **Mapa przed zdjęciem z lokalizacją**:
  - zmiana państwa → mapa świata z podświetlonym państwem (kafelki OSM + wyróżnienie granic GeoJSON), animowane przybliżenie na państwo, znacznik głównego punktu państwa, potem mapa lokalizacji zdjęcia,
  - to samo państwo, ale odległość od poprzedniego zdjęcia z GPS ≥ próg (domyślnie 5 km) → mapa lokalizacji,
  - odległość < próg lub brak GPS → samo zdjęcie (bez mapy).
- **Muzyka wg państwa** (hybryda): własne pliki z wybranego folderu (podfoldery `PL`, `POL`, `Poland` lub `_default`) — start i każda zmiana państwa zaczynają od **losowego utworu** (playlista tasowana przy każdym uruchomieniu); gdy brak plików, generowany jest instrumentalny podkład ambietowy (proceduralny WAV, unikatowy dla państwa).
- **Trasa między zdjęciami**: gdy oba zdjęcia mają GPS i odległość > 1 km → sekwencja: powrót na mapę wycentrowaną na punkcie (ten sam zoom co przy dotarciu, krótki chwyt) → płynny **zoom-out przy stałym środku na punkcie** (docelowy zoom zależny od długości trasy — stała prędkość przeglądu) → **rysowanie trasy drogowej** wyznaczanej przez [OSRM](https://router.project-osrm.org) (fallback: linia prosta bez internetu) z symbolem samolotu na czubku linii skierowanym zgodnie z kierunkiem, mapa podąża za czubkiem → po dotarciu **czerwona pinezka** na miejscu docelowym → przybliżenie na cel. Fazy przyspieszane tak, aby zawsze domknąć się w skonfigurowanym czasie mapy. Trasa pobierana z wyprzedzeniem, by nie czekać na OSRM w momencie przejścia.
- **Pinezki odwiedzonych punktów**: czerwone pinezki z lądowań (i lokalizacji nowych państw) zostają na mapie przez cały pokaz — ślad podróży; wcześniej narysowane trasy też zostają (ta sama linia i kolor).
- **Przejście mapa↔zdjęcie**: płynny crossfade (czas regulowany w ustawieniach, 0.2–5 s).
- **Sterowanie**: tap = pauza/wznowienie (obsługiwany też na mapie i zdjęciu), pasek dolny: ▶/⏸, ⏭ następne, ⏮ poprzednie, replay bieżącego państwa, restart całości, ustawienia. Przesunięcie w lewo na zdjęciu = następne, w prawo = poprzednie; cofanie najpierw powtarza blok map danego zdjęcia (mapa lokalizacji/trasa/wstęp państwa), a dopiero potem wraca do poprzedniego zdjęcia. Ustawienia dostępne zawsze (ikona w prawym górnym rogu).
- **Ręczne przeglądanie mapy**: pan/zoom na mapie działa zawsze — w trakcie przeglądania automat nie przeskakuje dalej; powrót do sekwencji po 5 s od ostatniej akcji na mapie.
- **Zatrzymanie skanowania** w trakcie — do pokazu trafiają zdjęcia przeskanowane do tej pory.
- **Opcje**: przerwa między zdjęciami, czas mapy lokalizacji, czas fazy „mapa świata”, próg dystansu (km), głośność, kolejność (od najstarszych/najnowszych), pomijanie zdjęć bez GPS, czas przejścia mapa↔zdjęcie.

## Wymagania

- Android 9+ (API 28+), dostęp do internetu (kafelki map OSM oraz… nic więcej — granice państw są wbudowane w aplikację).
- Budowanie: Android Studio (Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17/21) lub `gradlew.bat :app:assembleDebug`.

## Struktura muzyki

```
<Muzyka>/
  PL/  *.mp3
  IT/  *.mp3
  _default/  *.mp3      # fallback dla państw bez folderu
```

Rozpoznawane: kod ISO-2 (`PL`), ISO-3 (`POL`), angielska nazwa państwa (`Poland`); formay: mp3, ogg, wav, m4a, flac, aac.

## Uruchomienie

1. Uruchom aplikację → wybierz folder ze zdjęciami (Ustawienia lub przycisk na ekranie startowym).
2. (Opcjonalnie) wybierz folder z muzyką.
3. „Wczytaj zdjęcia” → „▶ Start”.

Podgląd map podczas pokazu jest animowany; na mapie można ręcznie przeglądać kafelki gestami. Panel ustawień sam zatrzymuje pokaz i wznawia po zamknięciu.

## Szczegóły techniczne

- Jetpack Compose (Material 3), ViewModel + StateFlow, korutyny (maszyna stanów pokazu na segmentach).
- Mapy: [osmdroid](https://github.com/osmdroid/osmdroid) (kafelki Mapnik OSM), granice państw: `assets/world/countries.geo.json` (world.geo.json, ISO-3) + point-in-polygon (ray casting; rozstrzyganie enklaw przez najmniejszy bounding box).
- Zdjęcia: Storage Access Framework (DocumentsContract) + `androidx.exifinterface`; koordynaty GPS z EXIF (wartości rational DMS) lub XMP (`exif:GPSLatitude/Longitude`).
- Odtwarzacz: `androidx.media`/`MediaPlayer` z play-listą i głośnością; fallback: generator ambientu (sine pad + pentatonika, 22.05 kHz mono WAV).