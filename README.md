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
 F:\MUZYKA   ── sync39 w tle ──→       Music/…  (dokładna kopia PC)     ── SMB/WebDAV ──→ strumień + pamięć LRU
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
* **Istniejąca kopia na karcie SD** (np. `/storage/XXXX-XXXX/Music`) jest wykorzystywana – aplikacja dopasowuje
  pliki po ścieżce względnej i nie pobiera ich drugi raz. Nie musisz już kopiować całości ręcznie.
* **Przejęty folder na karcie** (Ustawienia → Muzyka na karcie → Przejmij folder, wymaga „Dostępu do wszystkich
  plików”): aplikacja utrzymuje kopię sama – pobrane utwory trafiają do niego w układzie folderów z PC, pliki
  zmienione na PC (inny rozmiar, np. nowe tagi) są pobierane ponownie, przy braku miejsca znikają najrzadziej słuchane
  (spoza reguł offline – są na NAS i PC), a pliki, których nie ma już w bibliotece PC (przemianowane, usunięte), są
  usuwane dopiero po potwierdzeniu w „Porządkuj folder…” (lista plików, dwa kliknięcia).
* **Miejsce na dane**: biblioteka z PC, okładki, pamięć podręczna strumieni i pobrane – domyślnie na **karcie SD**,
  do zmiany na pamięć wewnętrzną (Ustawienia → Miejsce na dane). Dziennik zmian zostaje w pamięci telefonu.
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
  **Android Auto** (Do odsłuchania, Najlepsze, Polubione, Playlisty, Ostatnio dodane) i polecenia głosowe
  („odtwórz DECO*27 w 39droid” – wykonawca vocaP, wokalista, album albo wyszukiwanie utworów).
* **Simple Scrobbler 1.7.0**: sesja multimediów z metadanymi jak w tagach (wykonawca „X feat. Y” – scrobble łączą się
  z historią z 39player) oraz opcjonalny broadcast SLS API.
* **Kontynuuj z PC** (karta na ekranie głównym) i **Kontynuuj z telefonu** (pasek stanu 39player): kolejka, utwór
  i pozycja przechodzą między urządzeniami, a urządzenie, które grało, dostaje polecenie pauzy.
* **Na bieżąco z PC**: gdy aplikacja jest na ekranie albo gra, co 45 s sprawdza, czy na NAS jest nowa biblioteka
  z PC (i wysyła swoje zmiany) – zmiany z 39player trafiają na telefon bez ręcznej synchronizacji.

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

## Na start bez NAS – biblioteka z pliku

Zanim NAS będzie gotowy, aplikacja działa z samą kopią biblioteki na karcie SD:

1. Na PC (folder `pc`): `python -m sync39 export --out D:\39droid.db.gz` – zapisuje sam plik biblioteki
   (ok. 12 MB), także przy włączonym 39player.
2. Skopiuj plik na telefon (np. kablem do folderu Pobrane).
3. W 39droid: **Wczytaj z pliku** na ekranie głównym (albo Ustawienia → Wczytaj bibliotekę z pliku…).
4. Karta **Kopia biblioteki na karcie** proponuje folder z muzyką (np. `/storage/0123-4567/Music`) –
   **Użyj i skanuj**, zezwól na odczyt muzyki. Utwory z karty grają od razu, bez sieci. Żeby aplikacja sama
   utrzymywała ten folder: Ustawienia → Muzyka na karcie → **Przejmij folder**.

Zmiany z telefonu (serduszka, odsłuchy, playlisty) czekają w aplikacji i trafią na PC przy pierwszej synchronizacji
z NAS. Gdy NAS zacznie działać, migawka z NAS zastąpi wczytany plik.

## Konfiguracja NAS (Ugreen DXP4800 Plus, UGOS Pro)

1. **Folder muzyki na NAS**, np. `\\192.168.1.20\personal_folder\Music` – **dokładna kopia** `F:\MUZYKA`
   (ta sama struktura folderów). PC dalej gra z własnego dysku SSD; kopię na NAS utrzymuje **sync39 w tle**
   (niżej): każde dodanie, edycja tagów, zmiana nazwy czy usunięcie na PC trafia na NAS po ok. 20 s ciszy, a co
   30 min jest pełne porównanie. Zmiana nazwy pliku lub folderu to przeniesienie na NAS, bez ponownego wysyłania.
   Gdy w jednym przebiegu miałoby zniknąć z NAS więcej niż 2% plików (albo na PC nagle jest ich dużo mniej, np.
   odłączony dysk), usuwanie czeka na potwierdzenie – w 39player na pasku stanu („NAS: N do usunięcia”) albo
   `python -m sync39 mirror --confirm`. Istniejąca kopia na NAS jest dopasowywana po rozmiarze – nie jest wysyłana
   drugi raz.
2. **Folder synchronizacji** `39sync` obok (np. `\\192.168.1.20\personal_folder\39sync`; tworzy go sync39).
3. Telefon łączy się przez **SMB** (to, co już działa w LAN) albo **WebDAV** (Panel sterowania → Usługi plików →
   WebDAV; porty 5005 HTTP / 5006 HTTPS). Konto: najlepiej osobny użytkownik NAS tylko do odczytu muzyki
   i zapisu w `39sync`.
4. **Poza domem – Tailscale** (Ugreen Link obsługuje tylko aplikacje UGREEN). Nic nie jest wystawiane do
   internetu ani przekierowywane na routerze; ruch idzie szyfrowanym WireGuardem tylko między Twoimi urządzeniami.
   1. Konto na [tailscale.com](https://tailscale.com) → **Access controls** → wklej
      [`nas/tailscale/tailnet-policy.hujson`](nas/tailscale/tailnet-policy.hujson) (telefon i PC → NAS tylko
      port 445; NAS nie może łączyć się z niczym).
   2. UGOS Pro → **Docker → Projekt → Utwórz**, wklej
      [`nas/tailscale/docker-compose.yml`](nas/tailscale/docker-compose.yml), uruchom. W logach kontenera
      `tailscale-39` otwórz link logowania. Kontener udostępnia przez Tailscale **wyłącznie SMB** (bez panelu UGOS,
      SSH itd.), działa bez uprawnień (userspace, `cap_drop: ALL`, system plików tylko do odczytu), a klucz węzła
      trzyma w wolumenie Dockera – nie w udostępnionym folderze.
   3. Telefon: aplikacja **Tailscale** z Google Play, to samo konto. W 39droid w polu adresów dopisz po przecinku
      adres NAS-a z Tailscale (`100.x.y.z` z listy Machines albo `nas-39.<tailnet>.ts.net`):
      `192.168.1.20, 100.x.y.z` – w domu odpowiada LAN, poza domem Tailscale.
   4. Android pozwala na **jedną aplikację VPN naraz** – gdy działa Tailscale, inny VPN (np. Proton VPN) jest
      wyłączony.

   Ochrona hasła w obcych sieciach: 39droid zapamiętuje tożsamość serwera SMB przy pierwszym połączeniu w domu i nie
   loguje się do innego serwera pod adresem `192.168.1.20` (np. w hotelowym Wi-Fi). Transmisja SMB jest szyfrowana
   (SMB 3). Zalecane jest osobne konto NAS dla telefonu z długim losowym hasłem i dostępem tylko do `Music` i
   `39sync`.

W aplikacji: **Ustawienia → NAS** (protokół, adresy, udział, foldery muzyki i synchronizacji, użytkownik, hasło –
szyfrowane kluczem z Android Keystore) → **Testuj połączenie** → **Synchronizuj teraz**. Dla
`\\192.168.1.20\personal_folder\Music` i `…\39sync`: SMB, adres `192.168.1.20`, udział `personal_folder`,
folder biblioteki `Music`, folder synchronizacji `39sync`.

> **Android 17:** aplikacje z targetSdk 37 potrzebują zgody na dostęp do sieci lokalnej (`ACCESS_LOCAL_NETWORK`,
> grupa „Urządzenia w pobliżu”) – bez niej połączenia z NAS w LAN kończą się przekroczeniem czasu. 39droid prosi
> o nią przy starcie (gdy NAS jest skonfigurowany) i w Ustawieniach; odmowę można cofnąć w Ustawieniach systemu →
> Aplikacje → 39droid → Uprawnienia → Urządzenia w pobliżu.

## Synchronizacja z 39player (PC) – `pc/sync39`

Jednorazowo (z folderu `pc`, Pythonem z PyQt6 i mutagen – tym samym, który uruchamia 39player):

```
python -m sync39 setup --sync-dir \\192.168.1.20\personal_folder\39sync --music-dir \\192.168.1.20\personal_folder\Music
python -m sync39 mirror --dry-run --list
python -m sync39 autostart --start
```

`setup` zapisuje wspólne ustawienia w `%USERPROFILE%\.39sync\sync39.json` (czyta je też 39player); źródło lustra
to pierwszy folder muzyki z ustawień 39player (np. `F:\MUZYKA`), inne – `--music-src`. `autostart` dodaje zadanie
**39sync** w Harmonogramie zadań (po zalogowaniu, bez okna) – to **sync39 w tle**:

* **lustro muzyki** PC → NAS po każdej zmianie w folderze muzyki (powiadomienia Windows) i co 30 min,
* **biblioteka dla telefonu** (migawka + okładki) po zmianach w 39player – najczęściej co 10 min, a po
  zastosowaniu zmian z telefonu od razu (telefon dostaje potwierdzenie),
* **zmiany z telefonu przy zamkniętym 39player** – działający 39player (od wersji 1.3.62) stosuje je sam co 30 s,
  tym samym kodem (`player39/droid_link.py`).

Stan i dziennik: `%USERPROFILE%\.39sync` (`status.json`, `sync39.log`, `pending-deletions.txt` – lista plików
czekających na potwierdzenie usunięcia). 39player pokazuje go w **Narzędzia → Telefon (39droid) → Stan
synchronizacji…**.

**Telefon ↔ PC na żywo** (folder `39sync/state`): 39player i 39droid zapisują, co grają (kolejka, utwór, pozycja).
**Kontynuuj z telefonu** na pasku stanu 39player przejmuje kolejkę z telefonu (telefon dostaje polecenie pauzy),
a **Kontynuuj z PC** w 39droid – odwrotnie.

| Polecenie | Co robi |
|---|---|
| `python -m sync39 mirror` | jeden przebieg lustra PC → NAS (`--dry-run [--list]` – tylko plan, `--confirm` – potwierdza wstrzymane usuwanie, `--no-delete` – bez usuwania). `music` – to samo. |
| `python -m sync39 watch` | sync39 w tle w oknie konsoli (podgląd dziennika; zadanie 39sync robi to samo bez okna) |
| `python -m sync39 autostart [--start] [--remove]` | zadanie 39sync w Harmonogramie zadań (po zalogowaniu) |
| `python -m sync39 export --out PLIK` | sam plik biblioteki (.db.gz) do skopiowania na telefon – bez NAS. |
| `python -m sync39 export` | migawka biblioteki + miniatury okładek → NAS. Działa na kopii bazy, więc można go uruchomić także przy włączonym 39player. Pierwszy eksport okładek (~7 tys. albumów) trwa kilka minut, kolejne tylko nowe. |
| `python -m sync39 import` | zmiany z telefonu → 39player przy zamkniętym 39player (serduszka trafiają do `_REKOMENDACJE Z PC` / `_NAJLEPSZE` jak kliknięcia w programie; odtworzenia, oznaczenia „Do odsłuchania”, playlisty). `--dry-run` pokazuje, co zostałoby zastosowane. |
| `python -m sync39 run` | import → uruchom 39player → po zamknięciu export (dwuklik na **`pc/39player+sync.pyw`**) – zbędne przy sync39 w tle. |
| `python -m sync39 status` | stan migawki, potwierdzone i oczekujące zmiany z telefonów |

Każde zdarzenie z telefonu ma numer; PC zapisuje, do którego numeru zastosował zmiany (`39sync/state/applied.json`),
a migawka przekazuje to telefonowi – zmiany nie zginą ani nie zostaną zastosowane dwa razy. Do czasu potwierdzenia
telefon pokazuje je u siebie (np. nowe ♥ jest widoczne od razu). Zmiany, których PC nie mógł zastosować (np. dodanie
do playlisty usuniętej na PC, serduszko dla utworu, którego na PC już nie ma), trafiają z powodem do
`39sync/state/rejected-<telefon>.json` – telefon ostrzega na ekranie głównym i wymienia je w Ustawieniach →
Synchronizacja z 39player. Eksport bez zmian w bibliotece nie publikuje nowej migawki (skrót treści w manifeście).

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
  sync/                   SMB (smbj) i WebDAV, połączenie z wyborem adresu, synchronizacja, offline, WorkManager,
                          LiveLink (na bieżąco z PC, Kontynuuj z PC, polecenie pauzy)
  files/                  miejsce na dane (karta SD / pamięć wewnętrzna), pobrane, kopia na karcie i przejęty folder
  media/                  usługa odtwarzania (Media3), źródło danych lokalne/NAS, Simple Scrobbler, kontroler UI
  art/                    miniatury okładek (NAS, plik lokalny, pamięć)
  ui/                     Compose: biblioteka, kategorie z półkami i filtrami Love Live!, listy, odtwarzacz, ustawienia
app/src/main/assets/ll_icons/   ikony postaci Love Live! (z 39player)
pc/sync39/                eksport migawki, okładki, zmiany z telefonu (przez 39player), lustro muzyki, sync39 w tle
pc/39sync.pyw             sync39 w tle bez okna (zadanie 39sync z Harmonogramu zadań)
```

Wymagania budowania: JDK 21, Android SDK (platforma 37, build-tools 37), Gradle 9.9 (wrapper), AGP 9.5,
Kotlin 2.4, Compose BOM 2026.09, Media3 1.11, smbj 0.15.
