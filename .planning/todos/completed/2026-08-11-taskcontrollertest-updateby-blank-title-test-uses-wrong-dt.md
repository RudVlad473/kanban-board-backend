---
created: 2026-08-11T00:00:00.000Z
resolved: 2026-08-13
title: TaskControllerTest.UpdateById's "data is invalid" test builds its body from the wrong DTO type, masking the title-blank assertion it claims to make
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
---

## Problem

`TaskControllerTest.UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid`
(around line 236-255 as of quick task 260811-qru's audit) builds its PUT request body like this:

```java
var updateDto = SaveTaskRequestDTO.builder().title("").build();

mockMvc.perform(
                put(url).with(user(userId))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
        .andExpect(status().isBadRequest());
```

The test targets `PUT /boards/{boardId}/columns/{columnId}/tasks/{taskId}` — an *update*
endpoint whose real request DTO is `UpdateTaskRequestDTO`, not `SaveTaskRequestDTO`. Because
`SaveTaskRequestDTO` has no `version` field, Jackson serializes the body with no `version` key at
all. When Spring deserializes that JSON into the controller's actual `UpdateTaskRequestDTO`
parameter, the missing `version` field trips `UpdateTaskRequestDTO.version`'s `@NotNull`
constraint — and *that* 400 is what the test observes and passes against, not a title-blank
violation. `UpdateTaskRequestDTO.title` (annotated `@TaskTitle`, no `@NotBlank` — see the related
todo about whitespace-only values, filed separately from this one) is never actually exercised by
this test at all: the test would still pass, unchanged, even if title validation on update were
silently broken.

**Confirmed, not assumed:** empirically verified during the 260811-qru DTO/controller audit by
constructing both DTOs directly against a `jakarta.validation.Validator` and comparing violation
sets — an `UpdateTaskRequestDTO` with a blank title and a valid `version` produces a title
violation as expected; a `SaveTaskRequestDTO`-shaped body serializes with no `version` key and
trips the version constraint instead, regardless of the title's content.

## Solution

Rewrite the test to build its body from `UpdateTaskRequestDTO` (matching every other test in this
`UpdateById` nested class), with a valid `version` and a title value that is actually invalid for
`UpdateTaskRequestDTO.title`'s constraint. Since `UpdateTaskRequestDTO.title` currently has no
`@NotBlank` (only `@TaskTitle`'s `@Size` — see the separate whitespace-only-value todo), the most
useful invalid title for this test right now is one that violates `@Size` directly — e.g. a
1-2 character title, below `ValidationConstants.MIN_TASK_TITLE_LENGTH` — so the test continues to
prove *something* real about update-time title validation rather than accidentally testing
`version`'s `@NotNull` a second time (which `shouldReturnBadRequest_whenVersionIsMissing`,
elsewhere in the same nested class, already covers).

If the separate whitespace-only-value gap (`UpdateTaskRequestDTO.title` accepting a whitespace-only
value) is resolved before this todo is picked up, prefer testing that condition instead — it is
the more interesting invalid case and would then also be a true regression test for that fix.

## Resolution

Resolved by quick task 260813-euo. The whitespace-only gap this todo flagged as a preferred
retarget was confirmed already closed and already tested (`shouldReturnBadRequest_whenTitleIsWhitespaceOnly`,
same nested class) — retargeting there would have duplicated existing coverage, not fixed
anything, so the todo's own fallback (a too-short title exercising `@TaskTitle`'s `@Size` minimum)
was used instead: the test now builds an `UpdateTaskRequestDTO` with a valid `version` and a
2-character `title`. Falsified before trusting it: temporarily removed `@TaskTitle` from
`UpdateTaskRequestDTO.title`, observed the test fail (the short title validated, the update
succeeded, no 400 arrived), restored the annotation, observed green again. `git diff --stat
src/main` was zero throughout the falsification cycle.
