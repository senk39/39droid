-- Nałożenie pliku stanu (dołączonego jako „st”) na pełną bibliotekę – 39droid: Protocol.APPLY_STATE.
-- Jedno zapytanie w linii. Sprawdzają to: ProtocolTest (Kotlin) i pc/tests (sync39, prawdziwa migawka).
UPDATE track SET heart = 0, play_count = 0, last_played = NULL;
UPDATE track SET heart = (SELECT s.heart FROM st.track_state s WHERE s.id = track.id), play_count = (SELECT s.play_count FROM st.track_state s WHERE s.id = track.id), last_played = (SELECT s.last_played FROM st.track_state s WHERE s.id = track.id) WHERE id IN (SELECT id FROM st.track_state);
UPDATE node SET likes = 0;
UPDATE node SET likes = (SELECT s.likes FROM st.node_likes s WHERE s.id = node.id) WHERE id IN (SELECT id FROM st.node_likes);
UPDATE tag SET likes = 0;
UPDATE tag SET likes = (SELECT s.likes FROM st.tag_likes s WHERE s.id = tag.id) WHERE id IN (SELECT id FROM st.tag_likes);
DELETE FROM playlist;
INSERT INTO playlist SELECT * FROM st.playlist;
DELETE FROM playlist_track;
INSERT INTO playlist_track SELECT * FROM st.playlist_track;
DELETE FROM unlistened;
INSERT INTO unlistened SELECT * FROM st.unlistened;
DELETE FROM commit_log;
INSERT INTO commit_log SELECT * FROM st.commit_log;
DELETE FROM star;
INSERT INTO star SELECT * FROM st.star;
DELETE FROM applied;
INSERT INTO applied SELECT * FROM st.applied;
UPDATE info SET value = (SELECT value FROM st.info WHERE key = 'created') WHERE key = 'created';
