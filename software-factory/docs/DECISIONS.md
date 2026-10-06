# Decisions log

Choices made while building Phase 1 unattended, newest at the bottom.

| # | Decision | Why |
|---|----------|-----|
| 1 | The project lives in `software-factory/` instead of the repo root. | This repo is also the owner's GitHub profile (root `README.md`) and a GitHub Pages site (`index.html`, `pages.yml`). Writing the factory to the root would replace the profile README. The CI workflow has to live at `.github/workflows/factory-ci.yml` and uses `working-directory: software-factory`. |
| 2 | Docker check: the daemon was not running at first. `dockerd` started fine, so **Testcontainers (real PostgreSQL 16) is used for every DB test**, not H2. | The brief asked to check and note the result. Real Postgres means `FOR UPDATE SKIP LOCKED` and partial unique indexes are tested for real. |
| 3 | Docker Hub returned `429 Too Many Requests` in this sandbox. Images were pulled from `mirror.gcr.io` and tagged locally (`postgres:16-alpine`, `testcontainers/ryuk:0.12.0`). | Only affects this build machine. GitHub Actions runners pull from Docker Hub normally, so no workaround is committed. |
| 4 | Spring Boot 3.5.16 (latest 3.5.x on Maven Central at build time). Testcontainers version is managed by the Boot BOM. | Brief fixed the 3.5.x line. |
| 5 | Persistence uses Spring `JdbcClient` and hand-written SQL, not JPA. | The job claim (`FOR UPDATE SKIP LOCKED`) and the guarded state updates (`UPDATE ... WHERE state = ?`) are clearer and more predictable as explicit SQL. Fewer moving parts. |
| 6 | CI runs on every push and pull request (no path filter), as the brief says. | Literal reading of "every push and PR". |
