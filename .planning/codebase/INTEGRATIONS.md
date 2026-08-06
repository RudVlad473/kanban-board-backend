# External Integrations

**Analysis Date:** 2026-07-31

## APIs & External Services

**API Documentation:**
- Swagger UI / OpenAPI
  - Accessible at: `/api/docs`
  - Auto-generated from SpringDoc OpenAPI plugin
  - Public endpoints (no auth required)

**No active third-party API integrations detected** - The application does not currently integrate with external APIs (Stripe, AWS, etc.)

## Data Storage

**Databases:**
- **PostgreSQL** (production)
  - Connection: Environment variables (`DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`)
  - Client: PostgreSQL JDBC driver
  - ORM: Spring Data JPA with Hibernate
  - Port: Default 5432
  - Naming strategy: CamelCase to underscores conversion

- **PostgreSQL 16, via Testcontainers** (testing only)
  - Connection: `@ServiceConnection`-wired, one static container shared for the whole JVM run
  - Mode: Test profile only (`application-test.properties`)
  - Schema: Built by Flyway V1-V4, Hibernate at `ddl-auto=validate` (creates nothing)

**Session Storage:**
- **JDBC-backed Sessions** (Spring Session)
  - Stored in database: `spring_session` and `spring_session_attributes` tables
  - Timeout: 180 minutes
  - Auto-initialization: `spring.session.jdbc.initialize-schema=always`

**File Storage:**
- Not configured - Application does not use file storage (local or cloud)

**Caching:**
- Not detected - No caching layer (Redis, Memcached, etc.) configured

## Authentication & Identity

**Auth Provider:**
- Custom authentication (built into the application)
  - Implementation: Spring Security with username/password authentication
  - Password hashing: BCrypt (inferred from Spring Security default)
  - Session-based: HTTP sessions stored in PostgreSQL
  - User details: `UserEntity` implements `UserDetails` interface
  - Authentication provider: `UserAuthenticationProvider` (`src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java`)

**Session Configuration:**
- Max concurrent sessions per user: 2 (prevents login on third session)
- Session fixation protection: New session created after login
- Cookie settings:
  - Name: `JSESSIONID`
  - HTTP-only: Enabled (no JavaScript access)
  - Secure flag: Disabled (not enforced for HTTPS)
  - Same-site: Strict (CSRF protection)
  - Max age: 600 seconds (10 minutes)

**Public Endpoints (no auth required):**
- `/api/auth/signin` - User login
- `/api/auth/signup` - User registration
- `/api/docs` - Swagger documentation
- `/api/swagger-ui/*` - Swagger UI resources

## Monitoring & Observability

**Error Tracking:**
- Not detected - No external error tracking service configured

**Logs:**
- Spring Boot default logging (SLF4J with Logback)
- No external log aggregation detected
- `spring.jpa.show-sql=false` - SQL logging disabled
- `spring.jpa.properties.hibernate.generate_statistics=true` - Statistics enabled for tests only

**Exception Handling:**
- Global exception handler: `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java`
- Handles: `BadCredentialsException` and other Spring Security exceptions

## CI/CD & Deployment

**Hosting:**
- AWS EC2 (via GitHub Actions deployment)

**Container Registry:**
- Docker Hub
  - Repository: `rudenkovladimir/kanban-board-backend`
  - Authentication: `DOCKERHUB_TOKEN` secret

**CI Pipeline:**
- GitHub Actions (`.github/workflows/deploy.yml`)
  - Trigger: Push to `master` branch
  - Stages:
    1. Setup - Create Docker Hub image name variable
    2. Run tests - Execute Gradle tests and Spotless formatting checks
    3. Build Docker image - Multi-stage build (Gradle build + JDK runtime)
    4. Push to Docker Hub - Tag with commit SHA (7 chars)
    5. Deploy to EC2 - SSH deployment to EC2 instance
    6. Cleanup - Remove old Docker images from Docker Hub

**Deployment Details:**
- SSH key-based authentication to EC2 (`.ssh/id_rsa`)
- Container port mapping: 8080 → 80 (HTTP)
- Automatic Docker installation on first deploy
- Previous container cleanup: Stop and remove old container
- Image pruning: Remove dangling images after deployment

## Environment Configuration

**Required Environment Variables:**
- `DB_HOST` - PostgreSQL server hostname
- `DB_NAME` - Database name to use
- `DB_USER` - Database user for authentication
- `DB_PASS` - Database password for authentication

**GitHub Secrets (for CI/CD):**
- `DOCKERHUB_TOKEN` - Docker Hub authentication token
- `DOCKERHUB_USER` - Docker Hub username (hardcoded as `rudenkovladimir`)
- `EC2_SSH_KEY` - SSH private key for EC2 deployment
- `EC2_HOST` - EC2 instance hostname/IP
- `EC2_USER` - EC2 SSH user
- `DB_HOST` - Database host (for deployment)
- `DB_NAME` - Database name (for deployment)
- `DB_USER` - Database user (for deployment)
- `DB_PASS` - Database password (for deployment)

**Secrets Location:**
- GitHub Actions secrets (`.github/workflows/deploy.yml` references `secrets.*`)
- Environment variables passed at runtime to Docker container

## Webhooks & Callbacks

**Incoming:**
- Not configured - No webhook endpoints detected

**Outgoing:**
- Not configured - No external webhook callbacks

## Cross-Cutting Services

**No Additional Integrations:**
- Authentication: Custom/internal
- Authorization: Spring Security method-level annotations (`@PreAuthorize`)
- Email: Not integrated
- SMS: Not integrated
- Payment processing: Not integrated
- Real-time communication: Not integrated (WebSocket, etc.)
- AI/ML services: Not integrated
- CDN: Not configured

---

*Integration audit: 2026-07-31*
