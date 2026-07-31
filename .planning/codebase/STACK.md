# Technology Stack

**Analysis Date:** 2026-07-31

## Languages

**Primary:**
- Java 21 - Backend application code

## Runtime

**Environment:**
- JDK 21 (via Gradle wrapper and Docker)

**Package Manager:**
- Gradle 8.7
- Lockfile: Not applicable (Gradle manages versioning via build.gradle)

## Frameworks

**Core:**
- Spring Boot 3.5.0 - Application framework and HTTP server

**Web:**
- Spring Web - REST API and HTTP request handling (`org.springframework.boot:spring-boot-starter-web`)

**Data Access:**
- Spring Data JPA - ORM and database abstraction (`org.springframework.boot:spring-boot-starter-data-jpa`)

**Security:**
- Spring Security - Authentication and authorization (`org.springframework.boot:spring-boot-starter-security`)

**Validation:**
- Jakarta Validation - Bean validation and constraint annotations (`org.springframework.boot:spring-boot-starter-validation`)

**Session Management:**
- Spring Session JDBC - Server-side session storage in database

**API Documentation:**
- SpringDoc OpenAPI 2.8.8 - Swagger/OpenAPI documentation generation and UI (`org.springdoc:springdoc-openapi-starter-webmvc-ui`)

**Testing:**
- Spring Boot Test - Testing framework with JUnit 5 (`org.springframework.boot:spring-boot-starter-test`)
- Spring Security Test - Security-specific testing utilities (`org.springframework.security:spring-security-test`)
- REST Assured 5.5.5 - REST API testing library (`io.rest-assured:rest-assured`)
- JUnit Platform Launcher - Test platform discovery and execution

**Code Quality & Formatting:**
- Spotless 7.0.2 - Code formatting plugin using Google Java Format (AOSP variant)

## Key Dependencies

**Critical:**
- MapStruct 1.5.3 - DTO mapping and object transformation (`org.mapstruct:mapstruct` and `mapstruct-processor`)
- Lombok 1.18.36 - Boilerplate reduction (annotations for getters, setters, constructors)
- ULID Creator 5.2.0 - Unique ID generation (`com.github.f4b6a3:ulid-creator`)

**Infrastructure:**
- PostgreSQL Driver - PostgreSQL database client (`org.postgresql:postgresql`)
- H2 Database 1.4.200+ - In-memory database for testing (`com.h2database:h2`)

**Utilities:**
- Vavr 0.10.4 - Functional programming utilities
- Guava 32.0.1-android - Google collections and utilities
- Apache Commons Lang 3 - String and utility functions (test dependency)
- Apache Commons Collections 4.5.0 - Collection utilities
- DataFactory 0.8 - Test data generation

## Configuration

**Environment:**
- Environment variables for database connection:
  - `DB_HOST` - PostgreSQL server hostname
  - `DB_NAME` - Database name
  - `DB_USER` - Database user
  - `DB_PASS` - Database password

**Build:**
- `build.gradle` - Gradle build configuration
- `gradle/wrapper/gradle-wrapper.properties` - Gradle wrapper version specification
- `Dockerfile` - Multi-stage Docker build configuration

**Runtime Configuration:**
- `src/main/resources/application.properties` - Main application configuration
  - Server context path: `/api`
  - Swagger docs path: `/docs`
  - JPA/Hibernate naming strategy: CamelCase to underscores
  - Session management: JDBC-backed with 180m timeout
  - Session cookies: HTTP-only, same-site strict, 1m server timeout
- `src/main/resources/application-test.properties` - Test profile configuration
  - Uses in-memory H2 database instead of PostgreSQL
  - Hibernate DDL: `create-drop` (schema auto-created/dropped per test)

## Platform Requirements

**Development:**
- Java 21 JDK
- Gradle 8.7 (via wrapper)

**Production:**
- Docker - Container deployment
- PostgreSQL 12+ - Database server
- Linux environment (from Docker image: openjdk:21-jdk-slim)
- Port 8080 exposed from container (mapped to port 80 on host)

**CI/CD:**
- GitHub Actions - Automated testing and deployment
- Docker Hub - Container registry
- AWS EC2 - Deployment target

---

*Stack analysis: 2026-07-31*
