#!/bin/bash
# First-boot only (postgres image runs /docker-entrypoint-initdb.d on an empty volume).
# Creates the 'career' database, a least-privilege role for n8n workflows, and applies schema + seed.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname n8n <<SQL
CREATE ROLE career_app LOGIN PASSWORD '${CAREER_DB_PASSWORD}';
CREATE DATABASE career OWNER "$POSTGRES_USER";
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname career -f /career-sql/001_schema.sql -f /career-sql/002_seed.sql
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname career <<SQL
GRANT CONNECT ON DATABASE career TO career_app;
GRANT USAGE ON SCHEMA public TO career_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO career_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO career_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO career_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO career_app;
SQL
