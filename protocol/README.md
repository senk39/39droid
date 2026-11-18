# Formaty plików wymienianych przez NAS

Wzorcowe przykłady każdego pliku, który 39droid (telefon), 39player i sync39 (PC) wymieniają przez folder
synchronizacji `\\NAS\personal_folder\39sync`. Te same pliki sprawdzają oba zestawy testów:

* **Kotlin** – `app/src/test/java/io/github/senk39/droid39/ProtocolTest.kt` (kod: `sync/Protocol.kt`, `DeviceState`,
  `PcStatus`, `RejectedChange`),
* **Python** – `pc/tests/test_protocol.py` (kod 39player `player39/droid_link.py` i sync39), a zapytania
  `apply-state.sql` dodatkowo `pc/tests/test_sync39.py` na prawdziwej migawce biblioteki.

Strona, która plik **zapisuje**, musi wytworzyć dokładnie taki kształt (klucze i typy, a gdzie się da – wartości).
Strona, która go **czyta**, musi poprawnie odczytać wzorzec. Zmiana formatu = zmiana pliku tutaj i obu stron – inaczej
któryś test nie przejdzie, zanim nowa wersja trafi na telefon albo PC.

| Plik | Na NAS | Zapisuje | Czyta |
|---|---|---|---|
| `journal.json` | `inbox/<telefon>/j-<od>-<do>.json` | 39droid (`Protocol.journal`) | 39player / sync39 (`droid_link.read_events`, `Applier`) |
| `now-phone.json` | `state/now-<telefon>.json` | 39droid (`Protocol.phoneState`) | 39player („Kontynuuj z telefonu”) |
| `now-pc.json` | `state/now-pc.json` | 39player (`droid_link.player_state`) | 39droid (`DeviceState.parse`, „Kontynuuj z PC”) |
| `cmd-from-phone.json` | `state/cmd-pc.json` | 39droid (`Protocol.pauseCommand`) | 39player (`droid_link.read_cmd`) |
| `cmd-from-pc.json` | `state/cmd-<telefon>.json` | 39player (`droid_link.write_cmd`) | 39droid (`Protocol.parseCommand`) |
| `rejected.json` | `state/rejected-<telefon>.json` | 39player / sync39 (`Applier.reject`, `flush_rejections`) | 39droid (`RejectedChange.parseAll`) |
| `pc-status.json` | `state/pc-status.json` | sync39 w tle (`Daemon.pc_status`) | 39droid (`PcStatus.parse`) |
| `manifest.json` | `manifest.json` | sync39 (`publish`) | 39droid (`Protocol.Manifest.parse`) |
| `snapshot-schema.sql` | (schemat `snapshot/library-*.db.gz`) | sync39 (`snapshot.SCHEMA`) | 39droid (`SnapshotDb`, `Protocol.APPLY_STATE`) |
| `state-schema.sql` | (schemat `snapshot/state-*.db.gz`) | sync39 (`snapshot._state_schema`) | 39droid (`Protocol.APPLY_STATE`) |
| `apply-state.sql` | – | 39droid (`Protocol.APPLY_STATE`) | test sync39 (wynik = nowa pełna biblioteka) |

Uruchomienie:

```
cd pc && python -m unittest tests.test_protocol -v
./gradlew testDebugUnitTest          # Git Bash, JAVA_HOME / ANDROID_HOME jak przy budowaniu
```
