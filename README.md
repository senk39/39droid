# 39droid

Mobilny port [39player](https://github.com/senk39/39player) na Androida – zoptymalizowany pod Samsunga Galaxy A55
(One UI 9.0, Android 17 / API 37), z interfejsem w duchu PowerAmp (ciemny motyw, duża okładka, kolor akcentu z okładki).

Organizacja biblioteki (tagi, podpinanie VocaDB, zatwierdzanie „Nie przesłuchane”) zostaje w 39player na PC.
Telefon dostaje gotowy obraz biblioteki – te same kategorie, playlisty, serduszka, dane VocaDB, skróty i filtry –
i odsyła na PC to, co zmieniło się podczas słuchania.

## Jak to działa – model hybrydowy

```
 PC (39player)                         NAS (DXP4800 Plus)                         telefon (39droid)
 ─────────────                         ──────────────────                         ─────────────────
 library.db, config.json,   export →   39sync/manifest.json                       migawka biblioteki (SQLite)
 playlisty m3u8                        39sync/snapshot/library-*.db.gz   ──────→  + lokalna nakładka zmian
                                       39sync/art/xx/<klucz>.jpg (okładki)
                            ← import   39sync/inbox/<telefon>/j-*.json   ←──────  dziennik zmian (♥, odsłuchy,
                                                                                  oznaczenia, playlisty)
 F:\MUZYKA   ── robocopy (music) ──→   MUZYKA/…  (kopia biblioteki)     ── SMB/WebDAV ──→ strumień + pamięć LRU
                                                                                  pobrane „offline”: ♥♥, ♥,
                                                                                  Do odsłuchania, wybrane
                                                                                  playlisty i kategorie
```

* **Na telefonie zawsze**: obraz biblioteki (ok. 12 MB do pobrania przy 63 tys. utworów), okładki oglądanych
  albumów oraz utwory z reguł „offline” – domyślnie Najlepsze ♥♥ (ok. 2,6 tys. utworów) i Do odsłuchania.
  Polubione ♥ (14 tys., ok. 140 GB), playlisty i kategorie dołączasz jednym przełącznikiem (playlista lub kategoria:
  menu ⋮ → „Zawsze na telefonie”). Pierwsze pobieranie uruchamiasz sam (Ustawienia → Pobierz teraz – widać, ile GB
  zajmie); potem można włączyć „Pobieraj automatycznie po synchronizacji”.
* **Z sieci**: reszta biblioteki (np. 392 GB folderu Vocaloid) jest strumieniowana z NAS i trafia do pamięci
  podręcznej LRU na karcie (domyślnie 4 GB, do 64 GB).
* **Istniejąca kopia na karcie SD** (np. `/storage/XXXX-XXXX/MUZYKA`) jest wykorzystywana bez zmian – aplikacja
  dopasowuje pliki po ścieżce względnej i nie pobiera ich drugi raz. Nie musisz już kopiować całości ręcznie.
* **Bez sieci** grają utwory pobrane i z karty; pozostałe są wyszarzone (ikona chmury).

Kolejność źródła przy odtwarzaniu: plik pobrany → kopia na karcie → NAS (przez pamięć podręczną).

## Funkcje

* Kategorie automatyczne jak w 39player: foldery (Anime, Gry, Muzyka azjatycka, Składanki Vocaloid, Touhou, Utaite…),
  **vocaP**, **Wokaliści** (sekcje, warianty banków głosu, pigułki silników syntezy), **Silniki syntezy**.
* **Serie** w kategorii Anime: Love Live!, BanG Dream!, Project Sekai, THE IDOLM@STER, D4DJ, K-On!, DEN-ON-BU
  z jednostkami (μ's, Aqours, Nijigasaki, Liella!, Hasunosora, „Pozostałe”…).
* **Filtry Love Live!**: Grupa, Postać (z ikonami), Subunit, Tylko solowe, Instrumentalne i OST (Tak/Bez/Tylko);
  w folderze grupy (np. Aqours) grupa jest wybrana od razu.
* **Albumy** składanek (EXIT TUNES PRESENTS, Magical Mirai…) jako siatka okładek; półki vocaP
  („Najwięcej utworów”, „Często polubieni”).
* **VocaDB** (tylko do odczytu, bez internetu): ID utworu i albumu, typ (Original/Cover/Remix…), tytuły japoński,
  romaji i angielski, artyści z rolami, tagi z kategoriami, oznaczenie niezgodności. Wyszukiwarka znajduje utwory
  także po nazwach z VocaDB i tagach. Widok **Tagi (VocaDB)** i **Ulubione** (gwiazdki z 39player).
* **Serduszka** ♥ / ♥♥ jak w 39player (także przycisk ♥ w powiadomieniu). Na liście **Do odsłuchania** serduszko jest
  oznaczeniem, a „Przesłuchane” zaznacza się samo po 51% faktycznego słuchania; zatwierdzasz na PC.
* Licznik odtworzeń po wysłuchaniu 50% (jak w 39player), Ostatnio dodane / odtwarzane, Ostatnio zatwierdzone.
* Playlisty z PC + dodawanie utworów i tworzenie nowych playlist na telefonie.
* Odtwarzanie w tle (Media3): powiadomienie, ekran blokady, słuchawki/Bluetooth, wznowienie po podłączeniu,
  **Android Auto** (Do odsłuchania, Najlepsze, Polubione, Playlisty, Ostatnio dodane).
* **Simple Scrobbler 1.7.0**: sesja multimediów z metadanymi jak w tagach (wykonawca „X feat. Y” – scrobble łączą się
  z historią z 39player) oraz opcjonalny broadcast SLS API.

## Instalacja na telefonie

APK buduje się poleceniem (JDK 21, Android SDK z platformą 37):

```
gradlew assembleRelease
```

Plik: `app/build/outputs/apk/release/app-release.apk`. GitHub Actions buduje wersję debug przy każdym pushu
(zakładka Actions → artefakt `39droid-debug`). Instalacja przez USB (debugowanie USB włączone w
Opcjach programisty): `adb install -r app/build/outputs/apk/release/app-release.apk`, albo skopiuj plik na telefon
i otwórz go (zezwól na instalację z tego źródła). Wersja release jest podpisana kluczem debug tego komputera,
więc kolejne wersje instalują się jako aktualizacje.

## Konfiguracja NAS (Ugreen DXP4800 Plus, UGOS Pro)

1. **Udział na muzykę**, np. `Muzyka`, a w nim folder `MUZYKA` – kopia `F:\MUZYKA` (ta sama struktura folderów).
   Pierwsze kopiowanie i kolejne aktualizacje (tylko nowe i zmienione pliki, nic nie jest usuwane):
   ```
   cd pc
   python -m sync39 music
   ```
   (`--dry-run` – tylko lista, `--purge` – usuwa z NAS pliki, których nie ma już na PC). Pierwsza kopia ~600 GB
   przez 1 Gb/s trwa kilka godzin; robocopy można przerwać i wznowić.
2. **Folder synchronizacji** `39sync` w tym samym udziale (tworzy go sync39).
3. Telefon łączy się przez **SMB** (to, co już działa w LAN) albo **WebDAV** (Panel sterowania → Usługi plików →
   WebDAV; porty 5005 HTTP / 5006 HTTPS). Konto: najlepiej osobny użytkownik NAS tylko do odczytu muzyki
   i zapisu w `39sync`.
4. **Poza domem**: Ugreen Link obsługuje tylko aplikacje UGREEN, więc do dostępu z zewnątrz zalecany jest
   **Tailscale** (aplikacja w Centrum aplikacji UGOS Pro albo kontener Docker + aplikacja Tailscale na telefonie).
   W ustawieniach 39droid wpisz adresy po przecinku: najpierw LAN (np. `192.168.1.20`), potem nazwę z Tailscale
   (np. `dxp4800plus.tail1234.ts.net`) – aplikacja użyje pierwszego, który odpowiada. NAS nie musi być wystawiony
   do internetu.

W aplikacji: **Ustawienia → NAS** (protokół, adresy, udział, foldery `MUZYKA` i `39sync`, użytkownik, hasło –
szyfrowane kluczem z Android Keystore) → **Testuj połączenie** → **Synchronizuj teraz**.

## Synchronizacja z 39player (PC) – `pc/sync39`

Ustawienia: skopiuj `pc/sync39.example.json` do `pc/sync39.json` i uzupełnij ścieżki
(`player39_dir`, `sync_dir` – np. `\\DXP4800PLUS\Muzyka\39sync`, `music_dir`). Polecenia (z folderu `pc`,
Pythonem z PyQt6 i mutagen – tym samym, który uruchamia 39player):

| Polecenie | Co robi |
|---|---|
| `python -m sync39 export` | migawka biblioteki + miniatury okładek → NAS. Działa na kopii bazy, więc można go uruchomić także przy włączonym 39player. Pierwszy eksport okładek (~7 tys. albumów) trwa kilka minut, kolejne tylko nowe. |
| `python -m sync39 import` | zmiany z telefonu → 39player (serduszka trafiają do `_REKOMENDACJE Z PC` / `_NAJLEPSZE` jak kliknięcia w programie; odtworzenia, oznaczenia „Do odsłuchania”, playlisty). **39player musi być zamknięty.** `--dry-run` pokazuje, co zostałoby zastosowane. |
| `python -m sync39 run` | import → uruchom 39player → po zamknięciu export. To samo robi dwuklik na **`pc/39player+sync.pyw`** (bez okna konsoli, dziennik w `pc/sync39.log`) – wygodne jako skrót zamiast `39player.lnk`. |
| `python -m sync39 status` | stan migawki, potwierdzone i oczekujące zmiany z telefonów |
| `python -m sync39 music` | kopiowanie muzyki na NAS (robocopy) |

Każde zdarzenie z telefonu ma numer; PC zapisuje, do którego numeru zastosował zmiany (`39sync/state/applied.json`),
a migawka przekazuje to telefonowi – zmiany nie zginą ani nie zostaną zastosowane dwa razy. Do czasu potwierdzenia
telefon pokazuje je u siebie (np. nowe ♥ jest widoczne od razu).

Testy: `cd pc && python -m unittest discover -s tests -v` (działają na kopii danych 39player).

## Simple Scrobbler

Simple Scrobbler 1.7.0 śledzi sesje multimediów (dostęp do powiadomień) – tak samo jak przy PowerAmp. Przy pierwszym
odtworzeniu w 39droid pojawi się jego powiadomienie o nowej aplikacji: zezwól na 39droid na liście aplikacji
muzycznych. Opcjonalnie (Ustawienia → Simple Scrobbler) aplikacja wysyła też broadcast SLS API
(`com.adam.aslfms.notify.playstatechanged`) – włącz go tylko, gdy Simple Scrobbler nie widzi sesji, inaczej utwory
mogą zostać zescrobblowane dwa razy. Jak w 39player: wykonawca wysyłany dokładnie jak w tagu, bez Wykonawcy albumu
(opcja), z możliwością pominięcia „feat.”.

## Struktura

```
app/src/main/java/io/github/senk39/droid39/
  App.kt                  obiekty aplikacji (biblioteka, NAS, pliki, synchronizacja, pamięć podręczna)
  core/                   ustawienia, szyfrowanie hasła, normalizacja tekstu (jak names.sort_key w 39player)
  data/                   migawka z PC (SnapshotDb), nakładka zmian (Library), lokalna baza (LocalDb), modele
  sync/                   SMB (smbj) i WebDAV, połączenie z wyborem adresu, synchronizacja, offline, WorkManager
  files/                  pobrane pliki i kopia biblioteki na karcie (MediaStore)
  media/                  usługa odtwarzania (Media3), źródło danych lokalne/NAS, Simple Scrobbler, kontroler UI
  art/                    miniatury okładek (NAS, plik lokalny, pamięć)
  ui/                     Compose: biblioteka, kategorie z półkami i filtrami Love Live!, listy, odtwarzacz, ustawienia
app/src/main/assets/ll_icons/   ikony postaci Love Live! (z 39player)
pc/sync39/                eksport migawki, okładki, import zmian z telefonu, kopiowanie muzyki
```

Wymagania budowania: JDK 21, Android SDK (platforma 37, build-tools 37), Gradle 9.9 (wrapper), AGP 9.5,
Kotlin 2.4, Compose BOM 2026.09, Media3 1.11, smbj 0.15.
