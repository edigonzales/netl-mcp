-- Adapted from schema-jobs/shared/privileges/recreate_role.sql.
-- Roles were created for this run; never reset pre-existing roles here.
GRANT USAGE ON SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_read;
GRANT SELECT ON ALL TABLES IN SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_read;
GRANT USAGE ON SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_write;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_write;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_write;
