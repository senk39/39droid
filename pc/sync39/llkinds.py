"""Rodzaje utworów w kategorii Love Live! – do domyślnych filtrów 39droid.

Domyślnie telefon pokazuje w Love Live! tylko „podstawowe” utwory, a ukrywa:
  * variant – wersję solową / subunitu utworu, który ma wersję śpiewaną przez całą grupę („Honoka Mix”,
    „(Chika Takami ver.)”, solowe koncerty Aqours…),
  * ost      – utwory z albumów ze ścieżką dźwiękową (muzyka tła i wersje piosenek z OST), chyba że to piosenka,
               której poza albumami OST w bibliotece nie ma (wtedy ost_unique),
  * inst     – wersje bez wokalu (off vocal, instrumental, karaoke) – z indeksu 39player,
  * short    – skrócone (TV size, movie size, short ver.),
  * live     – nagrania z koncertów (LIVE ver., albumy „Live Sound CD”),
  * remix    – remiksy i aranżacje (Remix, Acoustic Arrange, Piano / Orchestra Ver., Rearrange Album).
Każdy rodzaj można na telefonie włączyć z powrotem w filtrach Love Live!."""

from __future__ import annotations

import re
from dataclasses import dataclass, field

OST_FOLDER = re.compile(r"soundtrack|^ost$|\bost\b|サウンドトラック", re.I)

# dopisek w nawiasie / po tyldzie na końcu tytułu: „Title (TV Size) [Episode 8 Insert Song]”, „Title ~Acoustic Arrange~”
SUFFIX = re.compile(r"\s*[(\[（【～~].*$")
SHORT = re.compile(r"\b(tv|movie|game|anime)\s*size\b|\bshort\b|\bmovie edit\b|tvサイズ|ショート", re.I)
LIVE_TITLE = re.compile(r"\blive\s*(ver\.?|version|edition|mix)\b|[(\[（]\s*live\s*[)\]）]|ライブ", re.I)
LIVE_FOLDER = re.compile(r"\blive\s*(sound\s*cd|cd|album|recording|音源)\b|\blive at\b", re.I)
REMIX = re.compile(
    r"re-?mix|\bremixed\b|\bmixture\b|\b(re)?arrange\b|\bacoustic\b|\borchestra(l)?\b|\bpiano\s*(ver|version)\b|"
    r"\beurobeat\b|\bfunkot\b|\bbossa\b|\bjazz\s*(ver|version)\b|\bunplugged\b", re.I)
REMIX_FOLDER = re.compile(r"rearrange album|remix album|\barrange album\b", re.I)


@dataclass
class Track:
    id: int
    title: str
    rel: list[str]                       # foldery od korzenia biblioteki (bez nazwy pliku)
    chars: set[str] = field(default_factory=set)
    units: set[str] = field(default_factory=set)
    groups: set[str] = field(default_factory=set)
    inst: bool = False
    ost: bool = False                    # muzyka tła (z indeksu 39player)


@dataclass
class Kinds:
    variant: bool = False
    ost: bool = False
    ost_unique: bool = False
    short: bool = False
    live: bool = False
    remix: bool = False


def base_title(title: str) -> str:
    """Tytuł bez dopisków wersji, do porównań: „Snow halation (HONOKA Mix)” → „snowhalation”."""
    t = SUFFIX.sub("", title or "").strip() or (title or "")
    return re.sub(r"[\W_]+", "", t.casefold())


def suffix(title: str) -> str:
    m = SUFFIX.search(title or "")
    return m.group(0) if m else ""


def in_ost_album(t: Track) -> bool:
    return any(OST_FOLDER.search(x) for x in t.rel)


def whole_group(t: Track) -> bool:
    """Śpiewa cała grupa (w wykonawcach grupa, bez wymienionych postaci i subunitów)."""
    return bool(t.groups) and not t.chars and not t.units and not t.inst and not t.ost


def classify(tracks: list[Track]) -> dict[int, Kinds]:
    group_songs: set[str] = set()          # tytuły utworów śpiewanych przez całą grupę
    outside_ost: set[str] = set()          # piosenki (z wokalem) spoza albumów OST
    for t in tracks:
        b = base_title(t.title)
        if not b:
            continue
        if whole_group(t):
            group_songs.add(b)
        if not t.inst and not t.ost and not in_ost_album(t):
            outside_ost.add(b)
    out: dict[int, Kinds] = {}
    for t in tracks:
        k = Kinds()
        b = base_title(t.title)
        suf = suffix(t.title)
        k.variant = bool(t.chars or t.units) and not whole_group(t) and b in group_songs
        album_ost = in_ost_album(t)
        k.ost = t.ost or album_ost
        k.ost_unique = album_ost and not t.ost and not t.inst and bool(t.chars or t.units or t.groups) \
            and b not in outside_ost
        k.short = bool(SHORT.search(suf))
        k.live = bool(LIVE_TITLE.search(suf)) or any(LIVE_FOLDER.search(x) for x in t.rel)
        k.remix = bool(REMIX.search(suf)) or any(REMIX_FOLDER.search(x) for x in t.rel)
        out[t.id] = k
    # piosenka z OST obecna tylko w wersjach skróconych (TV size) – jedna z nich zostaje widoczna
    only_short: dict[str, list[int]] = {}
    for t in tracks:
        k = out[t.id]
        if k.ost_unique:
            only_short.setdefault(base_title(t.title), []).append(t.id)
    for ids in only_short.values():
        if all(out[i].short for i in ids):
            out[min(ids)].short = False
    return out
