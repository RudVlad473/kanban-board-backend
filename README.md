# Kanban Board — Backend

REST API for a kanban board with boards, columns, tasks, and subtasks, with
session-based authentication and per-user data ownership.

## Why this exists

Built to go deeper on Spring Boot than CRUD tutorials usually cover: real
authentication and session management, ownership-based authorization (a user
can only touch their own boards), and a CI/CD pipeline that actually deploys
the result, rather than stopping at `./gradlew bootRun`.

## Architecture & key decisions

- **Layered architecture** (controller → service → repository) with shared
  base interfaces for entities and DTOs to cut down on repetition across
  boards/columns/tasks/subtasks
- **Session-based auth** via Spring Security, with `HttpSessionSecurityContextRepository`,
  session-fixation protection, and a 2-session-per-user cap to limit concurrent logins
- **Ownership verification as its own service**, so authorization logic
  (can this user touch this resource?) isn't duplicated across every controller
- **MapStruct** for entity↔DTO mapping instead of hand-written mappers, to keep
  the mapping boilerplate out of the service layer
- **Testing split deliberately by layer**: unit tests for services and DTOs
  (where the actual logic lives), integration tests for controllers (where
  routing, validation, and auth need to be proven end-to-end) — entities and
  repositories are intentionally left untested since they're boilerplate with
  no custom logic
- **CI/CD pipeline** runs the test suite and a formatting check (Spotless) on
  every push, then builds and pushes a Docker image and deploys it to an
  EC2 instance — old images get pruned automatically after a successful deploy

## Tech stack

Java 21, Spring Boot 3.5.0, Spring Security, Spring Data JPA, Hibernate,
PostgreSQL (H2 for tests), MapStruct, Docker, GitHub Actions, AWS EC2

## What's not done yet

No refresh-token-style session renewal — sessions are fixed-duration and expire
outright. No rate limiting on the auth endpoints yet.

## Running it locally

```bash
./gradlew bootRun
```
Tests run against an in-memory H2 database, no external setup required:
```bash
./gradlew test
```
Production deploys run via the included GitHub Actions workflow, building a
Docker image and pushing to an EC2 host.
