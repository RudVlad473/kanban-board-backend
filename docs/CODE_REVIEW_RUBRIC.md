# Code review rubric

Reviewer instructions: when a diff adds or changes Java, tests included, grade the changed lines
against the rules below. Report each violation as a finding that names the rule number, the file
and line, and the deciding test it fails. Do not report a passing line, or anything a build gate
already enforces: [CODE_STYLE.md](CODE_STYLE.md) marks those rules Enforced. Grade only lines
the diff touches. Much existing code predates these rules (measured counts sit under the rules
they affect), and rewriting untouched code belongs in a change of its own.

Numbers and headings match CODE_STYLE.md. Rules 3, 6, 7, 10 and 11 are fully enforced by tools
and have no entry here.

### 2. Load entities through the ownership-verified loader, never `repository.findById` directly

The four domain services (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) must resolve every entity through their own `findById(userId, id)` method, which delegates to `ownershipVerifierService.verifyOwnershipOf...` — never through a direct call to `repository.findById(id)`. For example, `TaskService.findById(userId, taskId)` calls `ownershipVerifierService.verifyOwnershipOfTask(userId, taskId)` and returns `pair.getSecond()`; every other method that needs a task goes through it instead of touching `taskRepository.findById` itself. Once an entity has been verified this way, any downstream repository call made later in the same method must be built from the verified entity's own id (`pair.getSecond().getId()`), not from the raw path-variable parameter that was passed in. `OwnershipVerifierService` (the root of the ownership chain) and `UserService` (the identity root, with no owner above it) are the only two places a direct repository `findById` is sanctioned.

**Why:** this is the entire access-control model of the application, and nothing in the type system enforces it — a direct repository load compiles cleanly, passes a naive test, and silently removes the ownership check it was supposed to go through; re-deriving the downstream id from the verified entity, rather than reusing the raw parameter, also guarantees that the id which was actually authorised is the id that gets used.

`TaskService.findById` and `TaskService.findAllByColumnId` are the reference implementations of this pattern.

**Deciding test:** After the ownership check, does a later repository call take the raw path-variable id instead of the verified entity's id? Does the method load an entity through a repository query other than `findById` (a hand-written `findByX`) without the ownership chain? Either is a finding; LayeringArchTest sees neither.

### 12. An optional String field that rejects blank carries `@OptionalNotBlank`, not `@NotBlank`

Validation alone does not make such a field safe: the service that consumes it must treat a null
value as "leave this field unchanged", as `TaskService.updateById` and `SubtaskService.updateById`
do with a presence guard. `BoardService.updateById` lacked that guard until quick task 261006-guz,
and a version-only board `PUT` returned 500.

**Deciding test:** Is the field optional (null means "leave unchanged") and blank meaningless for it? Then `@NotBlank` on it is a finding, and it needs `@OptionalNotBlank` beside its composed annotation. A field where blank is legitimate, such as `UpdateTaskRequestDTO.description`, needs neither. Does the consuming service treat a null field as unchanged? If not, that is a finding.
