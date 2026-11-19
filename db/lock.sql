-- Serialize migration callers. Temporary tables and advisory locks are connection-scoped.
USE mindrails;
CREATE TEMPORARY TABLE migration_lock (acquired TINYINT CHECK (acquired=1));
INSERT INTO migration_lock VALUES (COALESCE(GET_LOCK('mindrails.schema',60),0));
DROP TEMPORARY TABLE migration_lock;
