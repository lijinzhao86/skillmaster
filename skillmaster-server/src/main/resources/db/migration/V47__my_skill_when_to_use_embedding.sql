-- v59 P2: one vector per sentence of the caller's own when-to-use sentence.
--
-- **Kept apart from `skill_embedding`.** That table holds the author's public text and is read by
-- every search; this one is private to its author and only the library-scope semantic path reads
-- it. Merging them would either expose personal text to public retrieval or force every public
-- query to filter by owner.
--
-- **Sentences, not one vector.** A personal sentence often contains two or three situations; a
-- single average vector blurs them. The writer splits at sentence punctuation and stores at most
-- five rows, and a query takes the best cosine over them.
--
-- The foreign key makes a rewrite or a clear remove the old vectors in the same statement.
CREATE TABLE my_skill_when_to_use_embedding (
  user_id     TEXT NOT NULL,
  skill_id    TEXT NOT NULL,
  sentence_no INTEGER NOT NULL,
  model       TEXT NOT NULL,
  embedding   vector(1024) NOT NULL,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (user_id, skill_id, sentence_no, model),
  FOREIGN KEY (user_id, skill_id)
    REFERENCES my_skill_when_to_use (user_id, skill_id) ON DELETE CASCADE,
  CHECK (sentence_no BETWEEN 0 AND 4)
);

COMMENT ON TABLE my_skill_when_to_use_embedding IS
  'Sentence vectors of a private when-to-use sentence (v59 P2). Never read by public search; a query only compares against the caller''s own rows.';
COMMENT ON COLUMN my_skill_when_to_use_embedding.sentence_no IS
  'Zero-based position after splitting at sentence punctuation; at most five sentences.';
COMMENT ON COLUMN my_skill_when_to_use_embedding.model IS
  'The embedding model that produced the row, so a model change can replace rows without mixing spaces.';
COMMENT ON COLUMN my_skill_when_to_use_embedding.updated_at IS
  'RFC3339 UTC; used to find rows that need recomputing after a provider change.';
