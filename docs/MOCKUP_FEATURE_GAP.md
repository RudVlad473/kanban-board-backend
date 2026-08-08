# Mock-up vs. Backend Feature Gap

## Purpose and Provenance

This document compares what the Kanban design mock-ups ask for against what the
backend's actual REST surface currently provides, so a reader can see — without
opening either source separately — what the design expects, what the API delivers,
and where the two disagree.

- **Mock-up source:** `B:\downloads\claude_desktop\kanban-task-management-web-app.pdf`
  (115 MB, 73 pages). The PDF is **not** stored in this repository; the committed
  extraction below (`.planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/mockup-pages.txt`)
  is the reproducible record a future reader or reviewer can re-check without the
  original file.
- **Extraction method:** `.planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/extract-mockup-text.py`,
  using `pypdf` 6.15.0 (`PdfReader.pages[i].extract_text()`), which applies the
  document's 78 embedded `/ToUnicode` CMaps to resolve subsetted glyph indices to
  real characters. Confirmed complete: 73 pages, 78,808 characters extracted.
- **Extraction date:** 2026-08-08.
- **Backend commit compared against:** `a5c36e62ddeb4de3edfa2cbd880dce8ed943faf6`
  (`a5c36e6`), branch `master`.
- **Backend REST surface enumerated from:** the seven `@RestController` classes —
  `BoardController`, `ColumnController`, `TaskController`, `SubtaskController`,
  `TaskMoveController`, `ActivityController`, `AuthenticationController` — plus the
  declarative `/logout` route wired in `SecurityConfiguration`.

See [Appendix C](#appendix-c-method-and-limitations) for how visual (as opposed to
textual) confirmation of the mock-ups was — and was not — possible in this
environment; that constraint applies to every claim below, and is most relevant to
the Theming and Navigation/layout areas.

## Inventory Schema

Both the mock-up inventory (Appendix A) and the backend inventory (Appendix B) use
one identical column schema so their rows can be read and diffed side by side:

| Field | Meaning |
|-------|---------|
| **ID** | Stable row id: `MU-nn` for mock-up rows, `BE-nn` for backend rows |
| **Feature Area** | One of the nine areas: Auth and account; Boards; Columns; Tasks; Subtasks; Task movement and status; Navigation and layout; Theming; Activity log |
| **Action** | The affordance or capability, named the way a user or API consumer would refer to it |
| **Description** | What it does, in enough detail to compare against its counterpart |
| **Source** | For mock-up rows: a PDF page reference (`Page N`). For backend rows: `METHOD /path` — `ControllerClass` |

Each entry in the three gap sections below cites the inventory row id(s) it is
derived from, so a claim in the gap sections can always be traced back to its
evidence row.

## 1. Features in the mock-ups but missing or incomplete in the backend

**1.1 — Board creation has no exposed REST route.** *(MU-02, MU-05 vs. BE-01..BE-04)*
The mock-up sidebar offers a `+ Create New Board` affordance (Page 2, MU-02) that
opens an "Add New Board" modal (Page 8, MU-05) collecting a board name and an
initial list of columns, submitted via "Create New Board." `BoardController`
exposes only list (`GET /boards`), rename (`PUT /boards/{boardId}`), delete
(`DELETE /boards/{boardId}`), and add-column (`POST /boards/{boardId}/columns`) —
no route creates a board. The only code path that creates one is
`UserService.addBoardByUserId(String userId, SaveBoardRequestDTO boardDTO)`
(`src/main/java/com/vrudenko/kanban_board/service/UserService.java`), which no
controller calls; it is currently unreachable over HTTP. What would have to
change: add a `POST /boards` route to `BoardController` that delegates to this
method (or an equivalent moved onto `BoardService`), accepting
`SaveBoardRequestDTO { name }`. The modal's initial-columns list has no
matching request shape today either — the frontend would need either a
create-then-batch-add-columns sequence against the existing
`POST /boards/{boardId}/columns` route, or the board-creation DTO would need to
grow a columns list.

**1.2 — Column deletion has no route.** *(MU-06, MU-C4 vs. BE-C1, BE-C2)*
The Edit Board modal's `Board Columns` list (Page 9, MU-06/MU-C4) shows each
existing column with a remove control alongside `+ Add New Column`, implying a
column can be deleted independently of deleting the whole board. `ColumnController`
exposes only list (`GET /boards/{boardId}/columns`) and rename
(`PUT /boards/{boardId}/columns/{columnId}`) — no `DELETE` mapping exists for a
column. Today the only way to remove a column is to delete the entire board
(`DELETE /boards/{boardId}`, which cascades). What would have to change: add a
`DELETE /boards/{boardId}/columns/{columnId}` route to `ColumnController`,
presumably reassigning or deleting the column's tasks the same way board deletion
cascades to its columns.

**1.3 — No ordering or position field anywhere, so task/column reordering has no
backend representation.** *(MU-M3 vs. BE-M1, BE-C2)*
`MoveTaskRequestDTO` (`src/main/java/com/vrudenko/kanban_board/dto/task_dto/MoveTaskRequestDTO.java`)
carries exactly `targetColumnId` and `version` — it moves a task to a different
column with no notion of where in that column's task list it lands. No DTO in the
codebase (`SaveTaskRequestDTO`, `UpdateTaskRequestDTO`, `SaveColumnRequestDTO`,
`UpdateColumnRequestDTO`) carries an ordering/position/index field either, so
reordering tasks within a column or reordering columns within a board is equally
unsupported. A drag-and-drop-based reorder affordance would be the conventional
expectation for this exact kind of Kanban board (task cards inside labeled
columns, per MU-T1/MU-C1), but visual rendering of the populated board and every
plausible alternate route (Pages 3, 5, 24, 25) shows no grab handle, drag shadow,
drop placeholder, or insertion indicator anywhere on a task card or column
header — the design does not draw this affordance at all; the only task-movement
mechanism shown anywhere in the mock-up set is the `Current Status` dropdown
(MU-M2). What would have to change: add a
position/index field to the task (and optionally column) entity and its DTOs, and
extend `TaskMoveController`/`ColumnController` to accept and persist it.

**1.4 — No single nested "whole board" read; rendering one requires four separate
round trips.** *(MU-01 vs. BE-01, BE-C1, BE-T1, BE-Sub1)*
Switching to a board (Page 2, MU-01) requires the client to render every column,
every task, and every subtask that board contains. The API has no endpoint that
returns that in one response: a client must call `GET /boards` (or already have
the id), then `GET /boards/{boardId}/columns`, then
`GET /boards/{boardId}/columns/{columnId}/tasks` for each column, then
`GET .../tasks/{taskId}/subtasks` for each task — an N+1 fan-out proportional to
the board's column and task counts. `.planning/STATE.md` (line 199) records this
as a known, deliberate scope decision: `GET /boards/{boardId}/full` is deferred to
v2. This entry corroborates that decision against the mock-up rather than
introducing a new finding.

**1.5 — No persistence for a user's theme preference.** *(MU-Th1..MU-Th3 vs. no
backend row)*
The mock-up's design system page documents a complete light-mode and dark-mode
color palette together (Page 1, MU-Th1), and the same 10-screen desktop flow
recurs as two structurally identical page blocks (Pages 2-11 and 12-21, MU-Th2),
with the same duplication pattern repeating at the tablet and mobile breakpoints
(MU-Th3) — see Appendix C for why "structurally identical" rather than "visually
confirmed as light/dark." No entity, DTO, or endpoint anywhere in the backend
stores a per-user display preference of any kind — `UserResponseDTO`
(`id`, `email`, `displayName`) and `UserEntity` carry no theme field, and no route
reads or writes one. If the frontend needs the choice to persist across devices
or sessions rather than living in local client storage, a field and an endpoint to
read/write it would need to be added.

**1.6 — Subtask updates carry no optimistic-locking `version` field, unlike every
other mutable entity (lower confidence).** *(MU-S4 vs. BE-Sub3)*
`UpdateSubtaskRequestDTO` and `SubtaskResponseDTO`
(`src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/`) carry no `version`
field, while `UpdateColumnRequestDTO`, `UpdateTaskRequestDTO`, and
`MoveTaskRequestDTO` all require one and `GlobalExceptionHandler` maps a version
conflict to `409 Conflict`. The mock-up's subtask checkbox toggle (Page 5, MU-S4)
is exactly the kind of frequent, low-friction edit that two concurrent sessions
are most likely to race on. Flagged as lower confidence because the mock-up set
contains no direct evidence of a multi-session/collaboration scenario (no
presence indicators, no "last edited by" copy) — this is an internal-consistency
observation (subtasks are the one mutable entity in the update chain missing the
guard every sibling entity has), not a claim the design explicitly asked for it.

## 2. Backend features not reflected in the mock-ups

**2.1 — Paginated board activity log.** *(no MU row vs. BE-Act1)*
`GET /boards/{boardId}/activity` (`ActivityController.findAllByBoardId`) returns a
paginated `Page<ActivityLogResponseDTO>` of board events (`eventId`, `action`,
`detail`, `userId`, `createdAt`). No page in the extracted text or the page
structure suggests an activity/history feed screen anywhere in the mock-up set —
no occurrence of "activity," "history," or "log" appears in any of the 73 pages.
This is a design-has-no-screen-for-this gap, not a backend-internal detail: an
activity feed is a normal, user-facing product surface, so its absence from the
design set is worth flagging to whoever owns the mock-ups next, not treated as
correctly invisible.

**2.2 — Optimistic-locking `version` surface on Columns, Tasks, and task moves.**
*(no MU row vs. BE-C2, BE-T3, BE-M1)*
`UpdateColumnRequestDTO`, `UpdateTaskRequestDTO`, and `MoveTaskRequestDTO` each
require a client-supplied `version` long, and a mismatch surfaces as
`409 Conflict` (`GlobalExceptionHandler.handleOptimisticLockingFailure`). No mock-up
screen shows a version number, and none should — this is exactly the
backend-internal, correctly-invisible case: a frontend needs to round-trip the
value it was given, but the design has no reason to render it.

**2.3 — The full authentication flow (`signup`, `signin`, `logout`) has no
corresponding screens in this design set.** *(no MU row vs. BE-A1, BE-A2, BE-A3)*
`POST /signin`, `POST /signup`, and the declaratively-wired `POST /logout` are
fully implemented, session-cookie-issuing routes — `SignupRequestDTO` even
collects a `displayName` that nothing in the mock-up set has a field for. This is
squarely a design-has-no-screen-for-this gap, not a backend-internal one: every
board shown in the mock-ups is implicitly "my boards," so a real product needs
sign-up/sign-in screens somewhere; they are simply outside this particular
73-page export.

## 3. Features present in both

**3.1 — Boards.** *(hand-off map: mock-up screen → endpoint)*

| Mock-up screen / affordance | Backend endpoint |
|---|---|
| Sidebar board list + switcher, "ALL BOARDS (n)" (Page 2, MU-01) | `GET /boards` — `BoardController.findAllByUserId` (BE-01) |
| "Edit Board" modal, rename (Page 9, MU-06) | `PUT /boards/{boardId}` — `BoardController.updateById` (BE-02) |
| "Delete Board" confirmation (Page 10, MU-08), reached via board options menu (Page 24, MU-07) | `DELETE /boards/{boardId}` — `BoardController.deleteById` (BE-03) |
| "+ Add New Column" inside Add/Edit Board modals (Pages 8-9, MU-05/MU-06) | `POST /boards/{boardId}/columns` — `BoardController.addColumnByBoardId` (BE-04) |

**3.2 — Columns.**

| Mock-up screen / affordance | Backend endpoint |
|---|---|
| Column headers with live task counts, e.g. `T O D O ( 4 )` (Page 3, MU-C1) | `GET /boards/{boardId}/columns` — `ColumnController.findAllByBoardId` (BE-C1) |
| Column name field inside Edit Board modal (Page 9, MU-C3) | `PUT /boards/{boardId}/columns/{columnId}` — `ColumnController.updateById` (BE-C2) |

*(Column creation and deletion are covered under Boards §3.1 and Gap §1.2
respectively, since column-add is exposed on `BoardController` and column-delete
does not exist at all.)*

**3.3 — Tasks.**

| Mock-up screen / affordance | Backend endpoint |
|---|---|
| Task cards inside a column, subtask progress badge (Page 3, MU-T1) | `GET /boards/{boardId}/columns/{columnId}/tasks` — `TaskController.findAllByColumnId` (BE-T1) |
| "Add New Task" modal (Page 6, MU-T2) | `POST /boards/{boardId}/columns/{columnId}` — `ColumnController.addTaskByColumnId` (BE-T2) |
| "Edit Task" modal, Title/Description (Page 7, MU-T4) | `PUT /boards/{boardId}/columns/{columnId}/tasks/{taskId}` — `TaskController.updateById` (BE-T3) |
| "Delete this task?" confirmation (Page 11, MU-T6), reached via task options menu (Page 25, MU-T5) | `DELETE /boards/{boardId}/columns/{columnId}/tasks/{taskId}` — `TaskController.deleteById` (BE-T4) |

*(The View Task modal's `Current Status` dropdown, page 5/MU-T3, is a task-movement
affordance — see §3.5.)*

**3.4 — Subtasks.**

| Mock-up screen / affordance | Backend endpoint |
|---|---|
| Subtask checklist with progress count, e.g. "Subtasks (2 of 3)" (Page 5, MU-S1) | `GET /boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` — `SubtaskController.findAllByTaskId` (BE-Sub1) |
| "+ Add New Subtask" inside Add/Edit Task modals (Pages 6-7, MU-S2/MU-S3) | `POST /boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` — `TaskController.addSubtaskByTaskId` (BE-Sub2) |
| Subtask checkbox toggle / title edit (Page 5, MU-S4) | `PUT .../subtasks/{subtaskId}` — `SubtaskController.updateById` (BE-Sub3, see Gap §1.6 for its missing `version` guard) |
| Subtask removal (implied by "+ Add New Subtask" list management, Page 7) | `DELETE .../subtasks/{subtaskId}` — `SubtaskController.deleteById` (BE-Sub4) |

**3.5 — Task movement and status.**

| Mock-up screen / affordance | Backend endpoint |
|---|---|
| "Current Status" dropdown on View Task modal, Todo/Doing/Done (Page 5, MU-M2), matching the dropdown states cataloged on the design-system page (Page 1) | `PATCH /tasks/{taskId}/move` — `TaskMoveController.moveToColumn` (BE-M1) |
| Column membership itself as the visual status representation (Page 3, MU-M1) | Same route — moving a task's column *is* changing its status in this data model; there is no separate "status" field |

## Appendix A: Mock-up Inventory (full)

### Boards

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-01 | Boards | View board list / switch boards | Sidebar lists every board by name with a running count header (e.g. `ALL BOARDS ( 3 )`); clicking a name switches the active board panel | Page 2 |
| MU-02 | Boards | Create new board (sidebar affordance) | `+ Create New Board` control below the board list opens the Add New Board modal | Page 2 |
| MU-03 | Boards | Empty-board state | When the active board has no columns, the main panel shows "This board is empty. Create a new column to get started." plus a `+ Add New Column` call to action | Page 2 |
| MU-04 | Boards | Hide/show sidebar | `Hide Sidebar` control collapses the board-list sidebar | Page 2 |
| MU-05 | Boards | Add New Board modal | Collects a board `Name` plus an initial `Board Columns` list (each with a remove control, plus `+ Add New Column`), submitted via `Create New Board` | Page 8 |
| MU-06 | Boards | Edit Board modal | Pre-fills `Board Name` and existing `Board Columns` (editable/removable, plus `+ Add New Column`), submitted via `Save Changes` | Page 9 |
| MU-07 | Boards | Board options menu | An options menu on the board header exposes `Edit Board` / `Delete Board` | Page 24 |
| MU-08 | Boards | Delete board confirmation | "Are you sure you want to delete the '<name>' board? This action will remove all columns and tasks and cannot be reversed." with `Delete` / `Cancel` | Page 10 |

### Columns

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-C1 | Columns | View columns with live task counts | Column headers read e.g. `T O D O ( 4 )`, `D O I N G ( 6 )`, `D O N E ( 7 )` — count reflects that column's current task total | Page 3 |
| MU-C2 | Columns | Add new column (inline, board view) | `+ New Column` affordance at the end of the column row, outside the board-edit modal | Page 3 |
| MU-C3 | Columns | Rename column | Existing column names are editable text fields inside the Edit Board modal's `Board Columns` list | Page 9 |
| MU-C4 | Columns | Remove column | Each row in the Edit Board modal's `Board Columns` list has a remove control alongside `+ Add New Column` | Page 9 |

### Tasks

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-T1 | Tasks | View task card | Task cards inside a column show the title and a subtask progress badge, e.g. "0 of 3 substasks" | Page 3 |
| MU-T2 | Tasks | Add New Task modal | Collects `Title`, `Description`, an initial `Subtasks` list, and a `Status` dropdown (defaults to `Todo`), submitted via `Create Task` | Page 6 |
| MU-T3 | Tasks | View Task modal | Shows title, description, the subtask checklist with a progress count, and a `Current Status` dropdown | Page 5 |
| MU-T4 | Tasks | Edit Task modal | Pre-fills `Title`, `Description`, `Subtasks`, and `Status`; submitted via `Save Changes` | Page 7 |
| MU-T5 | Tasks | Task options menu | An options menu on the View Task modal exposes `Edit Task` / `Delete Task` | Page 25 |
| MU-T6 | Tasks | Delete task confirmation | "Are you sure you want to delete the '<title>' task and its subtasks? This action cannot be reversed." with `Delete` / `Cancel` | Page 11 |

### Subtasks

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-S1 | Subtasks | View subtask checklist | Checklist of subtasks with a running progress count, e.g. "Subtasks (2 of 3)" | Page 5 |
| MU-S2 | Subtasks | Add subtasks (task creation) | The Add New Task modal collects an initial list of subtask titles, each removable, plus `+ Add New Subtask` | Page 6 |
| MU-S3 | Subtasks | Add/edit subtasks (task edit) | The Edit Task modal lists existing named subtasks (e.g. "Define user model," "Add auth endpoints") as editable rows, plus `+ Add New Subtask` | Page 7 |
| MU-S4 | Subtasks | Toggle subtask completion | Visually confirmed. Clicking a subtask's checkbox toggles completion and updates the checklist's progress count (e.g. "Subtasks (2 of 3)") on the View Task modal. The idle checkbox is an empty square outline; the completed checkbox is a filled indigo/purple square with a white checkmark, paired with a strikethrough on the subtask's label text; the hovered state (design-system catalog) tints the row's background with a light lavender highlight while the checkbox itself stays unfilled. On the View Task modal (Page 5) both completed subtasks render the filled purple checkbox plus strikethrough label; the remaining incomplete subtask renders a plain outline checkbox with unstruck text | Page 1, Page 5 |

### Task movement and status

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-M1 | Task movement and status | Column membership as status | A task's column (Todo/Doing/Done in the sample data) is the visual representation of its status | Page 3 |
| MU-M2 | Task movement and status | Change status via dropdown | The View Task modal's `Current Status` dropdown lets a user change a task's status/column without a drag gesture; the same Todo/Doing/Done dropdown states are cataloged on the design-system page | Page 5 (states cataloged Page 1) |
| MU-M3 | Task movement and status | Drag-and-drop reorder (visually confirmed absent) | Conventional Kanban affordance for reordering task cards within or between columns. Visually rendered and examined on the populated board (Page 3) and every modal that could plausibly carry a drag cue (the View Task modal, Page 5; the board options menu, Page 24; the task options menu, Page 25): no grab handle, drag shadow, drop placeholder, or insertion indicator is drawn on any task card or column header. The affordance is not present in this design — status change is handled exclusively via the `Current Status` dropdown (MU-M2) | Page 3, Page 5, Page 24, Page 25 |

### Navigation and layout

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-N1 | Navigation and layout | Board header / top bar | Every board screen carries a consistent header (active board name, `+ Add New Task`) regardless of device tier | Page 2 |
| MU-N2 | Navigation and layout | Sidebar vs. mobile board switcher | PENDING-CALL-2 | PENDING-CALL-2 |
| MU-N3 | Navigation and layout | Three responsive breakpoints | Structurally confirmed via each page's PDF `mediabox` (canvas) size rather than visual rendering (see Appendix C): 1440×1024 for pages 2-33 (desktop), 768×1024 for pages 34-53 (tablet), and 375-wide pages (heights 667 or 970) for pages 54-73 (mobile). **This corrects the phase's planning-time page-range table**, which had labeled 22-33 as "Tablet" and 34-53 as "further desktop states" — the actual width break falls at page 34, not page 22 | Pages 2, 34, 54 (representative) |

### Theming

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-Th1 | Theming | Design-system color palette (light + dark) | Visually confirmed. Page 1 documents both a light palette (e.g. `F4F7FD`, `FFFFFF` backgrounds) and a dark palette (e.g. `000112`, `20212C` backgrounds) side by side, plus separately labeled "Light Version" / "Dark Version" catalogs of every interactive-element state: the Light Version catalog (Button Primary/Secondary/Destructive Idle+Hover, Subtask Checkbox Idle/Hovered/Completed, Text Field Idle/Active/Error, Dropdown Idle/Active) sits on a white card; the Dark Version catalog, with the same set of states, sits on its own dark charcoal panel directly below it | Page 1 |
| MU-Th2 | Theming | Duplicated desktop flow (light/dark pass) | PENDING-CALL-2 | Pages 2-21 |
| MU-Th3 | Theming | Duplicated flow at other breakpoints | PENDING-CALL-2 | Pages 54-73 |

### Auth and account

*No screens for this feature area were found anywhere in the 73-page mock-up set —
no sign-up, sign-in, log-out, or account-management screen occurs in the extracted
text. This absence is itself the finding; see Gap §2.3.*

### Activity log

*No screen for this feature area was found anywhere in the 73-page mock-up set —
the strings "activity," "history," and "log" do not occur in any of the 73 pages
of extracted text. This absence is itself the finding; see Gap §2.1.*

## Appendix B: Backend Inventory (full)

### Boards

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-01 | Boards | List boards for current user | Returns every board owned by the authenticated user, as `{ id, name }` — no nested columns/tasks | `GET /boards` — `BoardController.findAllByUserId` |
| BE-02 | Boards | Rename board | Updates a board's `name`. `UpdateBoardRequestDTO` carries only `name` — no `version` field, unlike the Column/Task update DTOs (no optimistic-locking guard on board rename) | `PUT /boards/{boardId}` — `BoardController.updateById` |
| BE-03 | Boards | Delete board (cascades columns/tasks/subtasks) | Deletes a board and all of its columns, tasks, and subtasks transactionally | `DELETE /boards/{boardId}` — `BoardController.deleteById` |
| BE-04 | Boards | Add column to board | Creates a new column under the given board | `POST /boards/{boardId}/columns` — `BoardController.addColumnByBoardId` |

### Columns

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-C1 | Columns | List columns for board | Returns every column on the given board, each carrying `id`, `name`, and `version` | `GET /boards/{boardId}/columns` — `ColumnController.findAllByBoardId` |
| BE-C2 | Columns | Rename column | Updates a column's `name`; requires the caller's `version` to match, or fails with `409 Conflict` | `PUT /boards/{boardId}/columns/{columnId}` — `ColumnController.updateById` |

*(No `DELETE` route exists for a column — see Gap §1.2.)*

### Tasks

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-T1 | Tasks | List tasks in column | Returns every task in the given column, each carrying `id`, `title`, `description`, `version` | `GET /boards/{boardId}/columns/{columnId}/tasks` — `TaskController.findAllByColumnId` |
| BE-T2 | Tasks | Create task in column | Creates a task under the given column from `title`/`description`; the route lives on `ColumnController` (mapped at the column's own URL) rather than `TaskController` | `POST /boards/{boardId}/columns/{columnId}` — `ColumnController.addTaskByColumnId` |
| BE-T3 | Tasks | Update task title/description | Requires at least one of `title`/`description`, plus the caller's `version`; does not accept a status/column change (see BE-M1) | `PUT /boards/{boardId}/columns/{columnId}/tasks/{taskId}` — `TaskController.updateById` |
| BE-T4 | Tasks | Delete task (cascades subtasks) | Deletes a task and its subtasks | `DELETE /boards/{boardId}/columns/{columnId}/tasks/{taskId}` — `TaskController.deleteById` |

### Subtasks

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-Sub1 | Subtasks | List subtasks for task | Returns every subtask on the given task, each carrying `id`, `title`, `isCompleted` — no `version` field | `GET /boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` — `SubtaskController.findAllByTaskId` |
| BE-Sub2 | Subtasks | Create subtask | Creates a subtask under the given task from `title` | `POST /boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` — `TaskController.addSubtaskByTaskId` |
| BE-Sub3 | Subtasks | Update subtask title / toggle completion | Requires at least one of `title`/`isCompleted`; unlike Column/Task updates, carries **no `version` field** — see Gap §1.6 | `PUT .../subtasks/{subtaskId}` — `SubtaskController.updateById` |
| BE-Sub4 | Subtasks | Delete subtask | Deletes a single subtask | `DELETE .../subtasks/{subtaskId}` — `SubtaskController.deleteById` |

### Task movement and status

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-M1 | Task movement and status | Move task to another column | Moves a task to `targetColumnId`, guarded by the caller's `version`; carries no target-position/index — see Gap §1.3 | `PATCH /tasks/{taskId}/move` — `TaskMoveController.moveToColumn` |

### Navigation and layout

*No backend routes exist for this feature area, and none are expected to —
navigation chrome and responsive layout are frontend-only concerns with no
corresponding server-side capability.*

### Theming

*No backend routes or fields exist for this feature area — no theme-preference
field appears anywhere in `UserEntity`, `UserResponseDTO`, or any request DTO,
and no endpoint reads or writes one. See Gap §1.5.*

### Auth and account

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-A1 | Auth and account | Sign up | Creates a user from `displayName`/`email`/`password`, then auto-authenticates and issues a session cookie | `POST /signup` — `AuthenticationController.signup` |
| BE-A2 | Auth and account | Sign in | Authenticates `email`/`password` and issues a session cookie; enforces a 2-concurrent-session ceiling and rotates the session id (D-01, see `docs/ARCHITECTURE.md`) | `POST /signin` — `AuthenticationController.signin` |
| BE-A3 | Auth and account | Log out | Clears the session cookie and its server-side session record; wired declaratively rather than through a controller method | `POST /logout` — declarative route (`SecurityConfiguration.securityFilterChain`, no controller class) |

### Activity log

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-Act1 | Activity log | List board activity (paginated) | Returns a paginated `Page<ActivityLogResponseDTO>` of board events (`eventId`, `action`, `detail`, `userId`, `createdAt`) for the given board | `GET /boards/{boardId}/activity` — `ActivityController.findAllByBoardId` |

## Appendix C: Method and Limitations

**PENDING-CALL-2** — this appendix is mid-rewrite as part of the two-call visual
confirmation pass (`260808-ls7`). Task 1 rendered and reconciled 12 of the 20
planned pages, resolving MU-Th1, MU-S4, and MU-M3 (see their rows in Appendix A
and Gap §1.3 above). Task 2 renders the remaining 8 pages, resolves MU-Th2,
MU-Th3, and MU-N2, and replaces the two paragraphs below — which still describe
the pre-render state — with an honest account of what was rendered.

**Coverage achieved.** All 73 pages of the mock-up PDF were read as text (78,808
characters), giving complete textual coverage of every screen's labels, copy, and
numeric state (task/subtask counts, column headers).

**Visual rendering was not possible in this environment — 0 of the planned up-to-20
pages were visually confirmed.** The plan called for a bounded visual read (at most
20 pages, at most 2 tool calls) to catch what text cannot express: theming,
responsive layout, drag/reorder affordances, and checkbox/toggle visual states.
Two independent blockers made this unavailable here, discovered during execution
rather than at planning time:

1. The Read tool refuses any page-range request against the source PDF outright
   with "PDF file exceeds maximum allowed size for text extraction (100MB)" — the
   file is 115 MB, over the tool's fixed cap, regardless of how few pages are
   requested. Splitting the requested pages into small derived PDFs (well under
   the cap, using the same already-approved `pypdf` — see below) worked around
   this first blocker.
2. Even against a small derived PDF, the Read tool's image-rendering path itself
   requires `pdftoppm` (poppler-utils), which is not installed in this
   environment, and no fallback renderer (`pymupdf`/`fitz`, `pdf2image`, `Pillow`)
   is available either. Installing a new system tool mid-execution was
   deliberately not attempted: this plan's own threat model (T-ku4-SC) commits to
   no unattended installs, `pypdf` being pre-installed specifically to avoid that
   exact class of decision, and a system-level poppler install is a strictly
   larger version of the same supply-chain concern, not a smaller one.

**Compensating technique used instead: PDF page `mediabox` (canvas-size)
inspection**, via the same `pypdf` library already sanctioned for text extraction
(no new dependency). Every page's canvas dimensions were read for all 73 pages —
a structural fact independent of rendering. This directly corrected one finding
inherited from planning (MU-N3): the true desktop/tablet/mobile breakpoint
boundaries are at pages 34 and 54, not 22 and 34 as the phase's planning-time
page-range table stated. It could **not** resolve theming: page dimensions are
identical within a device tier regardless of light/dark, and the PDF carries no
outline/bookmark metadata (`reader.outline` is empty, `reader.metadata` is
`None`) that could label a page pair by theme. Every theming claim in this
document (MU-Th1..MU-Th3, Gap §1.5) is therefore built from text-derived
structural evidence (duplicated page-pair content, a design-system page
documenting both palettes together) rather than a visual confirmation of which
specific page is rendered light vs. dark — that specific sub-claim remains
unconfirmed by any method available in this environment. MU-M3 (drag/reorder) is
similarly unconfirmed by any method, since it has no textual signature at all;
it is included as a labeled, lower-confidence, convention-based inference rather
than omitted, so a reader knows the API surface for it was not simply overlooked.

**Reproducibility.** Because the source PDF lives outside this repository, the
extracted text file committed alongside this document
(`.planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/mockup-pages.txt`)
is the durable, re-checkable record of every mock-up claim above — not the PDF
itself, which a future reader may not have access to.
