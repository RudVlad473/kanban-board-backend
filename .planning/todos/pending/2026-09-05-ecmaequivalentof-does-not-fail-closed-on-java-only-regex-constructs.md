---
created: 2026-09-05T17:20:00.000Z
title: ecmaEquivalentOf does not fail closed on Java-only regex constructs
area: api
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java
  - src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java
---

## Problem

`ComposedConstraintPropertyCustomizer.ecmaEquivalentOf` translates a Java `@Pattern` into the
ECMA-262 dialect JSON Schema's `pattern` is defined against, and its Javadoc promises it "returns
empty when no translation can be PROVEN equivalent -- publishing nothing is always safer than
publishing a WRONG pattern".

It does not keep that promise. The guard rejects only a non-`DOTALL` `flags()` value and an
unrecognised `(`-construct; **every escape other than `\s`/`\S` is copied through verbatim**. Found
independently by two reviewers of `3156968` on 2026-09-05, both confirming by running the same
source through Java's engine and node v24:

| Java source | value | Java | emitted string under ECMA |
|---|---|---|---|
| `\Qa.b\E` | `a.b` | accepts | **rejects** (stricter) |
| `\p{L}+` | `abc` | accepts | **rejects** (stricter) |
| `a\vb` | `a\nb` | accepts | **rejects** (stricter) |
| `\h+` | two spaces | accepts | **rejects** (stricter) |
| `\A\w+\z` | `abc` | accepts | **rejects** (stricter) |
| `[a-z&&[^aeiou]]+` | `bcd` | accepts | **rejects** (stricter) |
| `[^]]+` | `abc` | accepts | **rejects** (stricter) |
| `a*+` | `aaa` | accepts | **`SyntaxError: Nothing to repeat`** |

Every row is the document-stricter-than-enforcer direction the whole bean exists to prevent: a
generated client would refuse to send a request the server accepts. One row produces a pattern that
is not a valid regex at all.

A second, related defect in the same parser: the `[`/`]` state machine (`hasUnsupportedGroupConstruct`
and `translateDotAndWhitespaceShorthand`) closes a character class on the **first** `]`, so a
`]`-first negated class such as `[^]a.b]` leaves `inCharClass` wrong for the rest of the class --
under `DOTALL` a class-literal `.` would then be rewritten to `[\s\S]`, producing a different regex.

## Why this was deferred rather than fixed in 260904-ss1

**Latent, not live.** All four `@Pattern` values in this codebase today (`@BoardName`,
`@ColumnColor`, `@Password`, `@OptionalNotBlank`) translate correctly, verified head-to-head in both
engines by both reviewers, and the two published multi-pattern conjunctions give the right verdict
under both search and full-match semantics. Nothing in the served document is wrong today.

It bites whoever adds the next composed `@Pattern` using any construct above -- silently, since the
Javadoc tells them the method fails closed.

## Suggested approach

- Replace the permissive pass-through with an explicit **whitelist** of escapes known to mean the
  same thing in both dialects (`\d \D \w \W \n \r \t \f` and the already-handled `\s \S`, plus
  escaped punctuation), returning `Optional.empty()` for anything else -- `\Q`, `\p{...}`, `\v`,
  `\h`, `\A`, `\z`, `\Z`, `\G`, and `\b` inside a class.
- Reject possessive quantifiers (`*+`, `++`, `?+`, `{n,m}+`) and character-class intersection
  (`&&`), neither of which ECMA-262 has.
- Fix the `]`-first character class case, or reject a class whose first member is `]` rather than
  parsing it.
- Cover each with a case asserting `Optional.empty()`, in the shape the existing tests use. The
  guard is cheap to test directly because `ecmaEquivalentOf` is already package-private for exactly
  this reason.
