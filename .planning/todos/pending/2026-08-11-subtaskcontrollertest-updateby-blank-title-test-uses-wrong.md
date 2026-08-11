---
created: 2026-08-11T00:00:00.000Z
title: SubtaskControllerTest.UpdateById's "data is invalid" test builds its body from the wrong DTO type, masking the title-blank assertion it claims to make
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java
---

## Problem

Identical defect to the one filed for `TaskControllerTest.UpdateById` (see the sibling todo),
found in the same audit pass. `SubtaskControllerTest.UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid`
(around line 246-266 as of quick task 260811-qru's audit) builds its PUT request body like this:

```java
var updateDto = SaveSubtaskRequestDTO.builder().title("").build();

mockMvc.perform(
                put(url).with(user(userId))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
        .andExpect(status().isBadRequest());
```

The test targets `PUT /.../subtasks/{subtaskId}` — an update endpoint whose real request DTO is
`UpdateSubtaskRequestDTO`, not `SaveSubtaskRequestDTO`. `SaveSubtaskRequestDTO` has no `version`
field, so the serialized JSON omits it entirely; deserializing into the controller's actual
`UpdateSubtaskRequestDTO` parameter then trips `version`'s `@NotNull` constraint, and that 400 is
what the test observes — not a title-blank violation. `UpdateSubtaskRequestDTO.title` is never
actually exercised: the test would still pass even if title validation on subtask update were
silently broken.

**Confirmed, not assumed:** empirically verified during the 260811-qru audit the same way as the
`TaskControllerTest` sibling — a `jakarta.validation.Validator` run against both DTO shapes
directly confirms the observed 400 traces to the missing `version` key, not the title content.

## Solution

Rewrite the test to build its body from `UpdateSubtaskRequestDTO` (matching every other test in
this `UpdateById` nested class), with a valid `version` and a title value that is actually invalid
for `UpdateSubtaskRequestDTO.title`'s constraint — e.g. a 1-2 character title, below
`ValidationConstants.MIN_SUBTASK_TITLE_LENGTH`, since `@SubtaskTitle` currently carries no
`@NotBlank` (see the separate whitespace-only-value todo).

If the separate whitespace-only-value gap (`UpdateSubtaskRequestDTO.title` accepting a
whitespace-only value) is resolved before this todo is picked up, prefer testing that condition
instead — it is the more interesting invalid case and would then also be a true regression test
for that fix.
