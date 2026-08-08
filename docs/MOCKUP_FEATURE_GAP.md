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
environment; that constraint applies to every claim below, not just Boards.

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

## 2. Backend features not reflected in the mock-ups

*(Populated in Task 2 for the remaining feature areas; no Boards-specific
backend-only capability was identified in this pass — `BoardController`'s four
routes each have a corresponding mock-up affordance, see Section 3.)*

## 3. Features present in both

**3.1 — Boards.** *(hand-off map: mock-up screen → endpoint)*

| Mock-up screen / affordance | Backend endpoint |
|---|---|
| Sidebar board list + switcher, "ALL BOARDS (n)" (Page 2, MU-01) | `GET /boards` — `BoardController.findAllByUserId` (BE-01) |
| "Edit Board" modal, rename (Page 9, MU-06) | `PUT /boards/{boardId}` — `BoardController.updateById` (BE-02) |
| "Delete Board" confirmation (Page 10, MU-08), reached via board options menu (Page 24, MU-07) | `DELETE /boards/{boardId}` — `BoardController.deleteById` (BE-03) |
| "+ Add New Column" inside Add/Edit Board modals (Pages 8-9, MU-05/MU-06) | `POST /boards/{boardId}/columns` — `BoardController.addColumnByBoardId` (BE-04) |

---

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

### Auth and account

`PENDING-TASK-2`

### Columns

`PENDING-TASK-2`

### Tasks

`PENDING-TASK-2`

### Subtasks

`PENDING-TASK-2`

### Task movement and status

`PENDING-TASK-2`

### Navigation and layout

`PENDING-TASK-2`

### Theming

`PENDING-TASK-2`

### Activity log

`PENDING-TASK-2`

## Appendix B: Backend Inventory (full)

### Boards

| ID | Feature Area | Action | Description | Source |
|----|---|---|---|---|
| BE-01 | Boards | List boards for current user | Returns every board owned by the authenticated user, as `{ id, name }` — no nested columns/tasks | `GET /boards` — `BoardController.findAllByUserId` |
| BE-02 | Boards | Rename board | Updates a board's `name`. `UpdateBoardRequestDTO` carries only `name` — no `version` field, unlike the Column/Task update DTOs (no optimistic-locking guard on board rename) | `PUT /boards/{boardId}` — `BoardController.updateById` |
| BE-03 | Boards | Delete board (cascades columns/tasks/subtasks) | Deletes a board and all of its columns, tasks, and subtasks transactionally | `DELETE /boards/{boardId}` — `BoardController.deleteById` |
| BE-04 | Boards | Add column to board | Creates a new column under the given board | `POST /boards/{boardId}/columns` — `BoardController.addColumnByBoardId` |

### Auth and account

`PENDING-TASK-2`

### Columns

`PENDING-TASK-2`

### Tasks

`PENDING-TASK-2`

### Subtasks

`PENDING-TASK-2`

### Task movement and status

`PENDING-TASK-2`

### Navigation and layout

`PENDING-TASK-2`

### Theming

`PENDING-TASK-2`

### Activity log

`PENDING-TASK-2`

## Appendix C: Method and Limitations

`PENDING-TASK-2`
