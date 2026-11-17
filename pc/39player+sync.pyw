"""Uruchamia 39player z synchronizacją telefonu (dwuklik, bez okna konsoli):
zmiany z telefonu → 39player, potem 39player, a po jego zamknięciu eksport biblioteki na NAS.

Dziennik: pc/sync39.log. Błędy pokazywane są w okienku."""
import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.chdir(HERE)

log = open(os.path.join(HERE, "sync39.log"), "a", encoding="utf-8", buffering=1)
sys.stdout = log
sys.stderr = log

try:
    import time
    print(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} – 39player + synchronizacja ===")
    from sync39.__main__ import main
    rc = main(["run"])
    if rc:
        raise SystemExit(rc)
except BaseException as e:                                   # noqa: BLE001
    traceback.print_exc()
    if not (isinstance(e, SystemExit) and e.code in (0, None)):
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            None, f"Synchronizacja z telefonem nie powiodła się:\n{e}\n\nSzczegóły: {os.path.join(HERE, 'sync39.log')}",
            "39player – synchronizacja", 0x30)
