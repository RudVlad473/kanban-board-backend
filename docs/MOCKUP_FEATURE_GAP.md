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

20 of the 73 pages were also visually rendered (not just text-extracted) to
confirm theming, checkbox states, the drag/reorder affordance, and mobile
navigation — see [Appendix C](#appendix-c-method-and-limitations) for the method,
the full page list, and what — if anything — remains unconfirmed.

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
recurs as a visually confirmed light pass (Pages 2-11) and dark pass (Pages 12-21)
of the identical screens (MU-Th2), with the same light/dark duplication repeating
at the tablet and mobile breakpoints (MU-Th3, confirmed on Pages 34/44 and
55/65). This is a rendered, real light/dark theme toggle — including a visible
switch control inside the mobile board-switcher panel (Page 63) — not merely a
structural inference. No entity, DTO, or endpoint anywhere in the backend
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
| MU-N2 | Navigation and layout | Sidebar vs. mobile board switcher | Visually confirmed. At mobile width (Pages 55/65) the persistent sidebar is gone entirely, replaced by a compact header showing the active board's name with a dropdown chevron (`Platform Launch ⌄`); the task columns fill the full width and scroll horizontally. Tapping that header trigger opens the mobile board switcher (Page 63): a rounded dropdown/popover panel anchored below the header — not a full-screen overlay and not an off-canvas side drawer — listing `ALL BOARDS ( 3 )`, each board name (active one highlighted), `+ Create New Board`, and the light/dark theme toggle, all bundled into the same panel. Tablet width (Page 34) keeps the full desktop-style persistent sidebar with its own `Hide Sidebar` control, unchanged from desktop — so the breakpoint boundary where the sidebar is replaced by the dropdown switcher falls between tablet (768px) and mobile (375px), not at the desktop/tablet boundary | Page 34, Page 55, Page 63, Page 65 |
| MU-N3 | Navigation and layout | Three responsive breakpoints | Structurally confirmed via each page's PDF `mediabox` (canvas) size rather than visual rendering (see Appendix C): 1440×1024 for pages 2-33 (desktop), 768×1024 for pages 34-53 (tablet), and 375-wide pages (heights 667 or 970) for pages 54-73 (mobile). **This corrects the phase's planning-time page-range table**, which had labeled 22-33 as "Tablet" and 34-53 as "further desktop states" — the actual width break falls at page 34, not page 22 | Pages 2, 34, 54 (representative) |

### Theming

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| MU-Th1 | Theming | Design-system color palette (light + dark) | Visually confirmed. Page 1 documents both a light palette (e.g. `F4F7FD`, `FFFFFF` backgrounds) and a dark palette (e.g. `000112`, `20212C` backgrounds) side by side, plus separately labeled "Light Version" / "Dark Version" catalogs of every interactive-element state: the Light Version catalog (Button Primary/Secondary/Destructive Idle+Hover, Subtask Checkbox Idle/Hovered/Completed, Text Field Idle/Active/Error, Dropdown Idle/Active) sits on a white card; the Dark Version catalog, with the same set of states, sits on its own dark charcoal panel directly below it | Page 1 |
| MU-Th2 | Theming | Duplicated desktop flow (light/dark pass) | Visually confirmed as a genuine light/dark theme pass. Pages 2-11 render in the light palette cataloged on the design-system page (white/off-white `F4F7FD`/`FFFFFF` backgrounds, dark navy text); pages 12-21 render the identical screens in the dark palette (`000112`/`20212C` backgrounds, white text) — confirmed by directly comparing page 2 against page 12, page 3 against page 13, and page 5 against page 15, each pair showing the same screen with only the color scheme swapped. The two ranges do **not** extract text-identical content, contrary to this row's earlier claim: page 2's sidebar badge reads `ALL BOARDS ( 3 )` and page 12's reads `( 8 )` — a same-length-but-different-text discrepancy in the mock sample data that is incidental to theming (both list the same three board names); the rendered background/text/accent colors are what actually distinguishes the passes | Pages 2-21 |
| MU-Th3 | Theming | Duplicated flow at other breakpoints | Visually confirmed as a genuine theme pass at every breakpoint sampled. Tablet: page 34 renders light, page 44 renders the identical empty-board screen dark — and unlike the desktop pair, both tablet pages' sidebar badges read the same `( 3 )`, consistent with the tablet duplication being a pure theme swap with no incidental sample-data drift. Mobile: page 55 renders the populated board light, page 65 renders the same screen dark. The same light/dark duplication pattern established for desktop (MU-Th2) holds at every breakpoint examined | Pages 54-73 |

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

**Coverage achieved.** All 73 pages of the mock-up PDF were read as text (78,808
characters), giving complete textual coverage of every screen's labels, copy, and
numeric state (task/subtask counts, column headers).

**Pages rendered visually:** 20 of 73

Visual rendering — as opposed to text extraction — became possible once
`pdftoppm` (poppler) 26.02.0 was installed on the developer's machine out of
band, between this quick task and the prior one (`260808-ku4`) that first
attempted it. This plan (`260808-ls7`) installed nothing itself: both `pypdf`
6.15.0 and `pdftoppm` 26.02.0 were verified present at planning time and asserted
as preconditions, consistent with this project's no-unattended-installs stance
(`T-ku4-SC`, `T-ls7-SC`). The 100 MB cap the Read tool enforces on the 115 MB
source still required splitting the requested pages into small derived PDFs via
`pypdf` before they could be rendered — `split-mockup-pages.py`
(`.planning/quick/260808-ls7-redo-the-visual-pdf-confirmation-pass-fo/`) does
this, printing the `derived → original` page mapping so every citation below can
be traced back to the page it actually came from. Each of the 20 rendered pages
was independently reconciled against its committed text block in
`mockup-pages.txt` before any citation was written from it — the check that
makes a new page citation trustworthy rather than merely confident.

The 20 pages, and what each closed:

| Page | What it shows | Closed / contributed to |
|---|---|---|
| 1 | Design system: light + dark palettes, Light/Dark Version catalogs of every interactive-element state | MU-Th1; subtask checkbox states for MU-S4 |
| 2 | Desktop light pass, empty board + sidebar | MU-Th2 theme anchor; MU-N2 desktop sidebar baseline |
| 3 | Desktop light pass, populated Todo/Doing/Done board | MU-M3 (no drag affordance drawn) |
| 5 | Desktop light pass, View Task modal, Subtasks (2 of 3) | MU-S4 checkbox states in situ; MU-M3 alternate route |
| 6 | Add New Task modal | Modal coverage |
| 7 | Edit Task modal | Modal coverage |
| 8 | Add New Board modal | Modal coverage |
| 9 | Edit Board modal, column remove controls | Modal coverage |
| 10 | Delete Board confirmation | Modal coverage |
| 11 | Delete Task confirmation | Modal coverage |
| 24 | Board options dropdown (Edit Board / Delete Board) | MU-M3 alternate route |
| 25 | Task options dropdown (Edit Task / Delete Task) | MU-M3 alternate route |
| 12 | Desktop dark pass, empty board — counterpart of page 2 | MU-Th2 (confirms dark theme; explains the `( 3 )`/`( 8 )` text discrepancy as incidental, not the theme signal) |
| 13 | Desktop dark pass, populated board — counterpart of page 3 | MU-Th2; MU-M3 corroboration (still no drag affordance, dark pass) |
| 15 | Desktop dark pass, View Task modal — counterpart of page 5 | MU-Th2; MU-S4 corroboration in dark mode |
| 34 | Tablet light pass, empty board + sidebar | MU-Th3 tablet anchor; MU-N2 (tablet keeps the full desktop-style sidebar) |
| 44 | Tablet dark pass, empty board + sidebar — counterpart of page 34 | MU-Th3 (tablet duplication is a theme pass, not a data variant) |
| 55 | Mobile light pass, populated board | MU-N2 (sidebar gone, replaced by a header board-name/chevron trigger); MU-M3 corroboration at mobile width |
| 63 | Mobile board switcher, open | MU-N2 (decisive: a dropdown/popover panel anchored to the header trigger, not an off-canvas drawer or full-screen overlay) |
| 65 | Mobile dark pass, populated board — counterpart of page 55 | MU-Th3 mobile confirmation |

**What remains open.** Nothing named in the plan's four target questions
(MU-Th1/Th2/Th3, MU-M3, MU-N2, MU-S4) is unresolved after this pass — each now
carries a page-cited observation rather than an inference. The other 53 pages of
the 73-page set were not independently rendered. Per the completed text
extraction, they fall into two categories: (a) further repeats of screen types
already examined here in the remaining theme-pass or breakpoint duplicates (e.g.
pages 14, 16-21 are the dark-pass counterparts of already-rendered light-pass
modals; pages 47-53 are tablet 2-up composites; pages 64/66-73 are the dark-pass
counterparts of the mobile screens already rendered), and (b) a handful of
desktop-pass-A' screens (22, 23, 26, 27) not selected because pages 24-25 already
answered what that group was chosen to answer (the options-menu route for
MU-M3). No further open question is known among the unrendered pages, but — as
with any unexamined row in this document's provenance model — they were not
independently visually verified, and a reader should treat that as "not yet
looked at" rather than "confirmed absent of surprises."

**Corroborating technique: PDF page `mediabox` (canvas-size) inspection**, via
the same `pypdf` library already used for text extraction and page-splitting (no
new dependency). Every page's canvas dimensions were read for all 73 pages — a
structural fact independent of rendering, and the same technique the prior quick
task (`260808-ku4`) relied on before visual rendering was available. It remains
valid and is kept here as corroborating evidence rather than as a substitute for
rendering: it originally corrected one finding (MU-N3) — the true
desktop/tablet/mobile breakpoint boundaries are at pages 34 and 54, not 22 and 34
as the phase's planning-time page-range table stated — and the rendered pages in
this pass (34 at the tablet boundary, 55 at the mobile boundary) confirm that
`mediabox`-derived boundary directly.

**Reproducibility.** Because the source PDF lives outside this repository, the
extracted text file committed alongside this document
(`.planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/mockup-pages.txt`)
is the durable, re-checkable record of every text-derived mock-up claim above —
not the PDF itself, which a future reader may not have access to. The same is
true of the visual pass: the two derived PDFs used to render the 20 pages above
(~30 MB total) were written to a session scratchpad outside this repository and
were never committed, so `split-mockup-pages.py` plus the page list embedded in
the table above is the reproducible record of the visual pass, the same way
`mockup-pages.txt` is for the textual one — a future reader with access to the
original source PDF can regenerate the identical derived files and re-render the
identical pages.
