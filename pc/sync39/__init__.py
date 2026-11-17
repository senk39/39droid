"""sync39 – wymiana danych między 39player (PC) a 39droid (telefon) przez folder na NAS.

PC → telefon:  migawka biblioteki (SQLite, gzip) + manifest.json + miniatury okładek (art/).
telefon → PC:  dziennik zmian (inbox/<urządzenie>/*.json): serduszka, oznaczenia „Do odsłuchania”,
               odtworzenia, zmiany playlist – stosowane przez API biblioteki 39player.
"""

VERSION = "0.1.0"
FORMAT = 1            # wersja formatu migawki i dziennika (telefon odrzuca nieznany format)
