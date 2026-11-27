import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sync39.llkinds import Track, base_title, classify  # noqa: E402


def T(i, title, rel, chars=(), units=(), groups=(), inst=False, ost=False):
    return Track(i, title, list(rel), set(chars), set(units), set(groups), inst, ost)


class LlKindsTest(unittest.TestCase):
    def setUp(self):
        self.tracks = [
            T(1, "Snow halation", ["Love Live", "μ's"], groups={"μ's"}),
            T(2, "Snow halation (HONOKA Mix)", ["Love Live", "Solo Live! III"], chars={"Honoka Kosaka"}, groups={"μ's"}),
            T(3, "Aishiteru Banzai!", ["Love Live", "Solo Live! III"], chars={"Honoka Kosaka"}),   # brak wersji grupy
            T(4, "Bokura no LIVE Kimi to no LIFE (TV Size) [Episode 8 Insert Song]",
              ["Love Live", "μ's", "Original Soundtrack", "S1"], groups={"μ's"}),
            T(5, "Bokura no LIVE Kimi to no LIFE", ["Love Live", "μ's"], groups={"μ's"}),
            T(6, "Natsuiro Egao de 1,2,Jump!", ["Love Live", "μ's", "Original Soundtrack", "S1"], groups={"μ's"}),
            T(7, "Main theme", ["Love Live", "Original Soundtrack", "S1"], ost=True),
            T(8, "Snow halation (Off Vocal)", ["Love Live", "μ's", "off vocal"], groups={"μ's"}, inst=True),
            T(9, "Ijigen BIGBANG (LIVE Ver.)", ["Love Live", "Ijigen Fes Live Sound CD", "CD1"], groups={"Aqours"}),
            T(10, "Strawberry Trapper (Remix)", ["Love Live", "Aqours", "Guilty Kiss"], units={"Guilty Kiss"}),
            T(11, "HAPPY PARTY TRAIN (Mixture Rock Ver.)", ["Love Live", "Aqours Rock 'n' Roll Rearrange Album"],
              groups={"Aqours"}),
            T(12, "Mix shake!!", ["Love Live", "Cerise Bouquet"], units={"Cerise Bouquet"}),
            T(13, "Mix shake!! (Short Ver.)", ["Love Live", "Live Sound CD"], units={"Cerise Bouquet"}),
            T(14, "LIVE with a smile! (Aqours Ver.)", ["Love Live", "LIVE with a smile!"], groups={"Aqours"}),
            T(15, "Paradise Live", ["Love Live", "μ's"], groups={"μ's"}),
        ]
        self.k = classify(self.tracks)

    def test_base_title(self):
        self.assertEqual(base_title("Snow halation (HONOKA Mix)"), base_title("Snow halation"))
        self.assertEqual(base_title("Kimeta yo Hand in Hand ~Acoustic Arrange~"), base_title("Kimeta yo Hand in Hand"))

    def test_variant_only_when_group_version_exists(self):
        self.assertTrue(self.k[2].variant)
        self.assertFalse(self.k[3].variant)
        self.assertFalse(self.k[1].variant)
        self.assertFalse(self.k[12].variant)          # subunit bez wersji całej grupy

    def test_ost(self):
        self.assertTrue(self.k[4].ost and not self.k[4].ost_unique)     # jest poza OST
        self.assertTrue(self.k[6].ost and self.k[6].ost_unique)         # piosenka tylko na OST
        self.assertTrue(self.k[7].ost and not self.k[7].ost_unique)     # muzyka tła
        self.assertFalse(self.k[5].ost)

    def test_ost_song_only_in_tv_size_stays_once(self):
        k = classify([
            T(1, "SELF CONTROL!! (TV Size) [Ep 2]", ["Love Live", "Original Soundtrack"], units={"Saint Snow"}),
            T(2, "SELF CONTROL!! (TV Size) [Ep 7]", ["Love Live", "Original Soundtrack"], units={"Saint Snow"}),
        ])
        self.assertFalse(k[1].short)
        self.assertTrue(k[2].short)

    def test_short_live_remix(self):
        self.assertTrue(self.k[4].short)
        self.assertTrue(self.k[13].short)
        self.assertTrue(self.k[9].live)
        self.assertTrue(self.k[13].live)
        self.assertTrue(self.k[10].remix)
        self.assertTrue(self.k[11].remix)
        # „Live” / „Mix” w samym tytule to nie rodzaj wersji
        for i in (5, 12, 14, 15):
            self.assertFalse(self.k[i].live or self.k[i].remix or self.k[i].short, i)


if __name__ == "__main__":
    unittest.main()
