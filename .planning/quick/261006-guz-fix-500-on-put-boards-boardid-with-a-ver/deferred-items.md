# Deferred items from quick task 261006-guz

## Flaky ColumnControllerTest update test (pre-existing, out of scope)

`ColumnControllerTest.UpdateById.testWithAuthenticatedUser_shouldUpdateColumn_whenColumnExists`
failed once in the first full-suite run (`version Expected: 1 got: 0`) and passed on rerun.

Likely cause, from reading the code (not reproduced): the test renames the column to a random word
of length `MIN_COLUMN_NAME_LENGTH + 2` and expects the version to rise by one. The fixture column
(`AbstractAppTest.mockPopulatedColumn`) is also a random word of a similar length. When the two
words are equal, nothing is dirty, Hibernate issues no UPDATE and the version stays put (WR-02).

Possible fix for a later task: draw the new name until it differs from the current one, or use a
fixed name that cannot equal a generated word. Not touched here because this task fixes only the
board path.
