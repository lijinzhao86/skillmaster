-- Empties everything a test can write, and nothing else.
--
-- The seed rows of V2 — three users, three namespaces, three owner memberships — are identity
-- data the tests read, not fixtures they create, so they stay. Everything below is created by
-- publishing, deleting, registering or logging in, and leaving it in place would make a test's
-- result depend on which tests ran before it.
--
-- RESTART IDENTITY is conventional rather than required: no test asserts on a particular id, and
-- every audit assertion counts rows instead. Resetting the counter only keeps a truncated table
-- indistinguishable from a freshly migrated one, which is what makes a failing row readable.
--
-- app_user and namespace are NOT here, although registration writes both. TRUNCATE ... CASCADE
-- follows foreign keys *pointing at* the table it empties, and both are pointed at widely:
-- app_user from namespace_member and skill, namespace from skill and namespace_member. Emptying
-- either would take the V2 seed with it, and every read-path test in this suite starts from that
-- seed. The accounts the tests below do create are left behind instead, and are harmless because
-- each one is created with a random phone number and a random username — nothing reads a row it
-- did not write. credential is emptied anyway, which leaves those accounts without a password: a
-- state AccountService already answers "no such credentials" to, and one that cannot affect a test
-- using identifiers of its own.
--
-- namespace_member is also NOT here, and for the same reason as namespace: V2's three owner
-- memberships are part of the seed, and the publish-path tests reach them through the ownership
-- check. Registration adds one more row (the personal namespace's owner) and it is left behind,
-- which is harmless because it is keyed by a random user id and nothing scans the table as a whole.
-- Named explicitly because it is the table this file's justification used to skip.
--
-- spring_session is here because two of the browser plane's tests are assertions about its rows —
-- that a login wrote one, and that a logout and a password reset removed them all. Left unemptied,
-- whichever of those ran second would count the other's sessions.
TRUNCATE TABLE
    blob_content,
    version_file,
    skill_version,
    skill,
    skill_stat,
    audit_event,
    blob,
    phone_verification,
    auth_throttle,
    captcha,
    credential,
    spring_session,
    spring_session_attributes
RESTART IDENTITY CASCADE;
