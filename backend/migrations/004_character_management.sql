ALTER TABLE characters ADD COLUMN owner_id TEXT REFERENCES users(id);
ALTER TABLE characters ADD COLUMN deleted_at TEXT;
ALTER TABLE character_revisions ADD COLUMN name TEXT NOT NULL DEFAULT '';
UPDATE character_revisions SET name = (SELECT name FROM characters WHERE characters.id = character_revisions.character_id);
CREATE INDEX characters_by_owner ON characters(owner_id, deleted_at, created_at, id);
