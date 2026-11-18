"""sync39 w tle (bez okna) – uruchamiane przez zadanie „39sync” z Harmonogramu zadań po zalogowaniu.

Lustro folderu muzyki PC → NAS, eksport biblioteki dla 39droid, zmiany z telefonu przy zamkniętym 39player.
Dziennik i stan: %USERPROFILE%\\.39sync (sync39.log, status.json)."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.chdir(HERE)

if __name__ == "__main__":
    from sync39 import daemon
    sys.exit(daemon.main(console=False))
