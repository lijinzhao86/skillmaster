-- The machine name the person types on the login-request screen, for a first-party CLI login. v4.
--
-- **A column rather than a key in `attributes`.** `attributes` is the framework's bag: V6's comment
-- there forbids *pruning* it, which is not the same as it being ours to file things in. And this value
-- is not only displayed — it is what a repeated login is found by ("same machine, same name, end the
-- previous one"), so it is read with a predicate:
-- `WHERE principal_name = :user AND label = :label AND revoked_at IS NULL`. A row lookup that had to
-- decode the framework's JSON first is one nobody would want to keep.
--
-- **`label`, not `name`, although `api_key` calls its column that.** On this table a `name` column
-- would sit beside `registered_client_id`, whose own row has `oauth_client.name` — 「SkillMaster CLI」,
-- the *client's* name — while this is the *machine's*. The confusion is one word away, so the two get
-- different words.
--
-- **Safe against the framework's re-saves.** `TokenStore.upsertAuthorization`'s
-- `ON CONFLICT (id) DO UPDATE SET` names four columns and deliberately leaves `created_at` alone for
-- exactly this reason: a column the conflict clause does not name survives every later save. This one
-- is written once, when the sign-in starts, and read for as long as the row lives.
--
-- **NULL for every row the framework writes**, which is every third-party authorization. That is an
-- answer rather than a gap: those logins are not a named machine's, and the console builds its second
-- segment from what it does have.
ALTER TABLE oauth_authorization ADD COLUMN label TEXT;

COMMENT ON COLUMN oauth_authorization.label IS
  'The machine name typed on the login-request screen, for a first-party CLI login. NULL for third-party authorizations, which have none. Not a credential: it is what the 「登录设备」 row shows after the device kind, and how a repeated login finds the one it replaces.';
