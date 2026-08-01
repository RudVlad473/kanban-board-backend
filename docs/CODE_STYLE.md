# Code Style Guide

This file records code-style preferences that AI coding agents and human contributors must follow when writing Java in this repository. It is additive: new rules are appended over time as they come up, never rewritten wholesale. It complements — does not replace — the Spotless / Google Java Format AOSP formatting already enforced by the build. Formatting is mechanical and enforced by `./gradlew spotlessCheck`; this file covers judgement-level choices Spotless cannot check.

## Rules

### 1. Prefer enums over magic int/String constants

When a value comes from a fixed, known-at-compile-time set, model it as an enum (a JDK/framework-provided one where it exists, otherwise a project enum under `com.vrudenko.kanban_board`) rather than as bare `int` or `String` literals scattered across call sites. HTTP status codes are the canonical case: use `org.springframework.http.HttpStatus`.

**Why:** the compiler enforces the closed set, so a typo or an out-of-range value fails at build time instead of runtime; switch statements can be checked for exhaustiveness; the value carries a self-documenting name at every call site; and the set has one authoritative definition to change instead of N literal sites to grep for.

Discouraged:

```java
@ExceptionHandler(AppEntityNotFoundException.class)
public ResponseEntity<String> handleAppEntityNotFound(AppEntityNotFoundException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatusCode.valueOf(404));
}

@ExceptionHandler(AppAccessDeniedException.class)
public ResponseEntity<String> handleAppAccessDenied(AppAccessDeniedException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatusCode.valueOf(404));
}
```

Preferred:

```java
import org.springframework.http.HttpStatus;

@ExceptionHandler(AppEntityNotFoundException.class)
public ResponseEntity<String> handleAppEntityNotFound(AppEntityNotFoundException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
}
```

`GlobalExceptionHandler` already follows this rule and is the reference to imitate. The rule generalises beyond HTTP status — any closed value set (roles, states, sort directions) should be an enum.

## Adding a rule

New rules are appended as a new `###` section under `## Rules`, numbered with the next integer. Each rule must carry the same three parts: a rule statement, a bolded **Why** line, and a bad-vs-good code example.
