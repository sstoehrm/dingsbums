# dingsbums whiteboard — design

Implements `spec.edn`, `spec/frontend.md` and `spec/backend.md`: a very
simplified miro-like whiteboard. A babashka backend keeps sessions in one atom;
a hammer (ClojureScript) single page app draws the board on a Canvas 2D and
syncs live with every other browser in the same session.

Figure: `.blend/specs/2026-10-06-whiteboard.edn` (simpleviz).

## Decisions

| Topic | Decision |
|---|---|
| Collaboration | Live multi-user: object ops over a WebSocket (hammer.tubes), server applies and rebroadcasts, last write wins per object |
| Rendering | Canvas 2D (`hammer.canvas/defdraw`) for the board; DOM overlays only for the session bar, tool bar, selection toolbar and text editor |
| Tar export/import | Server-side endpoints |
| Navigation | Infinite canvas with pan and zoom |
| Deep link | Session id in the URL hash (`/#<id>`) |
| Undo/redo | Per-client history of the client's own actions |

## Out of scope

Rotation, z-order controls, presence/cursors, persistence across server
restarts, auth/accounts, touch gestures beyond what pointer events give.

## Layout

| Path | What |
|---|---|
| `bb.edn` | tasks: `server`, `test`, `build`, `dev` |
| `src/dingsbums/ops.cljc` | object ops shared by server and client: validation, upsert, delete with connection cascade |
| `src/dingsbums/sessions.clj` | pure session ops + the `sessions` atom |
| `src/dingsbums/tar.clj` | ustar write/read, export/import of a session |
| `src/dingsbums/server.clj` | httpkit (built into bb): static files, REST, WebSocket, sweeper |
| `deps.edn`, `shadow-cljs.edn`, `package.json` | frontend build: hammer `v0.1.2` (`https://github.com/sstoehrm/hammer.git`, sha `14b64e3`), shadow-cljs 3.5.4, no ClojureScript pin |
| `src/dingsbums/geom.cljs` | pure: camera transforms, hit-testing, resize, rubber band, `fit-text` |
| `src/dingsbums/model.cljs` | pure: object constructors, groups, locks, delete cascade, history |
| `src/dingsbums/events.cljs` | hammer events and effects |
| `src/dingsbums/board.cljs` | the `defdraw` board and pointer handling |
| `src/dingsbums/views.cljs` | landing screen and DOM overlays |
| `src/dingsbums/core.cljs` | `init`: mount, window listeners (keys, paste, hashchange) |
| `public/index.html`, `public/js/` | page and build output, served by bb |
| `test/dingsbums/*_test.clj` / `*_test.cljs` | backend (`bb test`) / frontend (`npm test`) tests |

## Data model

A session is a map of objects keyed by id; each object is one entry.

```clojure
{:id "uuid"
 :kind :text | :frame | :shape | :sticky | :image | :connection
 :x 0 :y 0 :w 200 :h 100            ; world coordinates; absent on :connection
 :shape :circle | :rect | :star | :arrow   ; :shape only
 :fill "#ffd54f"                    ; :shape (optional, nil = unfilled) and :sticky
 :text "..."                        ; :text and :sticky
 :src "data:image/png;base64,..."   ; :image
 :from "id" :to "id"                ; :connection, drawn center to center
 :group "gid"                       ; optional
 :locked? true                      ; optional
 :z 12}                             ; draw order, higher on top
```

| Kind | Border | Fill | Text |
|---|---|---|---|
| `:text` | none | none | yes |
| `:frame` | black | none | no |
| `:shape` | black | optional color | no |
| `:sticky` | black, rectangle | color (default yellow) | yes |
| `:image` | none | the image | no |
| `:connection` | black line, arrowhead at `:to` | — | no |

- A new object gets `:z` = max existing `:z` + 1. Frames are drawn and hit
  below all other kinds regardless of `:z`; connections are drawn above frames
  and below the other objects.
- **Groups** are a shared `:group` id. Selecting any member selects the whole
  group. Grouping needs at least two selected objects (connections excluded)
  and gives them a fresh group id (replacing old ones); ungrouping removes
  `:group` from the selection.
- **Locking** sets `:locked?` on every selected object (so a group locks as a
  whole). Locked objects can be selected, and unlocked from the selection
  toolbar, but cannot be moved, resized, text-edited, recolored or deleted.
- **Deleting** an object also deletes every connection whose `:from` or `:to`
  it was.

## Backend

State: `(defonce sessions (atom {}))` holding
`{sid {:objects {id obj} :clients #{ch} :empty-since ms-or-nil}}`. Session ids
are random UUIDs; no route lists them.

### HTTP

| Route | Behavior |
|---|---|
| `GET /`, `GET /js/*`, other static files | from `public/`; unknown paths 404 |
| `POST /api/sessions` | new session, `:empty-since` now; `200 {"id": "..."}` |
| `GET /api/sessions/:id` | `204` if it exists, `404` otherwise |
| `GET /api/sessions/:id/export` | `200 application/x-tar`, `Content-Disposition: attachment; filename="dingsbums-<id>.tar"`; 404 if unknown |
| `POST /api/sessions/:id/import` | body is a tar; replaces the session's objects and sends `[:session/snapshot objs]` to all its clients; `204`; `400` on a malformed tar (session untouched); `404` if unknown |

### Tar format

POSIX ustar, written and read by `dingsbums.tar` (no dependencies):

- `session.edn`: `{:version 1 :objects {id obj}}`; each image's `:src` is
  replaced by `"images/<id>.<ext>"`.
- `images/<id>.<ext>`: the decoded image bytes; `<ext>` from the data URL's
  MIME type (`png`, `jpeg`, `gif`, `webp`, `svg`; else `bin`).

Import reads `session.edn` with `clojure.edn` (no eval), re-inlines each image
file as a data URL, and keeps only map entries whose value is a map with
`:id` and `:kind`. A missing `session.edn` or an unreadable archive is a 400.

### WebSocket `/ws?session=<id>`

hammer.tubes protocol: one EDN event vector per text frame, read with
`clojure.edn/read-string`.

- **Open:** unknown session → send `[:session/missing]`, close. Otherwise add
  the channel to `:clients` and set `:empty-since` nil.
- **Receive** `[:session/hello]`: reply `[:session/snapshot objs]` (or
  `[:session/missing]` if the session is gone). The client sends it from the
  tube's `:on-connect`; hammer.tubes flushes queued ops *before* `:on-connect`,
  so the snapshot already contains ops queued while offline.
- **Receive** `[:op/upsert [obj ...]]`, `[:op/patch [{:id :x :y :w :h} ...]]` or
  `[:op/delete [id ...]]`: apply with one `swap!` (delete cascades to
  connections; a patch only touches existing objects, so it never resurrects a
  deleted one; `dingsbums.ops`), forward the same frame to the session's other
  clients. Unreadable frames and events of any other shape are ignored.
  Objects need an `:id` of 1–64 `[A-Za-z0-9_-]` and a known `:kind`, may only
  carry the keys of the data model, each with the right type (numbers for
  geometry and `:z`, strings for text, src, fill, ends, group; a known `:shape`;
  boolean `:locked?`).
- **Frame limit:** http-kit `:max-ws` 20 MB; a larger frame makes http-kit
  close the connection (1009), the client reconnects and resyncs.
- **Close:** remove the channel; when it was the last, set `:empty-since` now.
- **Sweeper:** a loop every 60 s runs `(expire sessions-map now-ms)`, which
  drops sessions with no clients whose `:empty-since` is more than 15 min ago.

## Frontend

### Screens and routing

- No hash: landing screen — "Create new session" button, session id input and
  "Join" button, an error line.
- `#<id>`: `GET /api/sessions/<id>`; 204 opens the board and creates the tube,
  404 returns to landing with "Session not found".
- Create: `POST /api/sessions`, then set the hash to the new id.
- `hashchange` re-runs this; leaving a board destroys its tube.

### Board

A full-window `defdraw` canvas. Camera `{:x :y :zoom}`:
`screen = (world - cam) * zoom`. Zoom range 0.1–4.

| Input | Result |
|---|---|
| wheel | pan |
| ctrl/cmd+wheel, trackpad pinch | zoom around the cursor |
| space+drag, middle-button drag | pan |

DOM overlays:

| Where | Content |
|---|---|
| top left | session id, copy button (`navigator.clipboard`), export (link to the export endpoint), import (file input → POST), "offline" badge while the tube is disconnected, one-line message for errors |
| bottom left | tools: select, text, frame, shape (picker: circle, rectangle, star, arrow), sticky note, connection; undo ↶ and redo ↷ |
| above the selection's bounding box | fill swatches (when every selected object is a shape or sticky), lock/unlock, group/ungroup, delete |
| over the edited object | `<textarea>` while editing text |

### Pointer

Hit-testing is in world coordinates, topmost first (order as drawn, reversed).
Connections are hit within 6 screen px of their line; circles by ellipse,
everything else by bounding box.

| Action | Result |
|---|---|
| tool active, click | object at a default size centered on the point |
| tool active, drag | object spanning the dragged rectangle (min 20×20) |
| | text tool: then enters text editing; every tool: back to select afterwards |
| connection tool: press on A, release on B ≠ A | `:connection` from A to B; release elsewhere cancels |
| click object | select it (or its group) |
| shift+click | toggle it (or its group) in the selection |
| drag on empty canvas | rubber band: selects objects fully inside (expanded to groups) |
| click empty canvas | clear selection; commits an active text edit |
| drag a selected object | move the selection's unlocked objects |
| drag a corner handle | resize; handles shown only when exactly one unlocked, non-connection object is selected; min 20×20 |
| double-click an object with text | edit text |

Default sizes: text 200×50, frame 400×300, shape 120×120, sticky 200×200.
Default fill: shape none, sticky `#fff176`. Swatches: none, `#fff176`,
`#ffb74d`, `#e57373`, `#81c784`, `#64b5f6`, `#ba68c8`, `#ffffff`, `#000000`
("none" only for shapes).

### Text

- `(fit-text measure text w h)` → `{:size px :lines [str ...]}`: the largest
  font size (6–200 px, binary search) at which the text, word-wrapped to
  `w - 2*padding`, fits `h - 2*padding` at a line height of 1.2×size. Words
  longer than a line break by character. `measure` is `(fn [s size] width)`:
  canvas `measureText` in the browser, a fake in tests.
- Editing: double-click or F2 on a single selected unlocked text/sticky opens
  the textarea at the object's screen rect with the fitted font size (scaled by
  zoom). Clicking outside commits (one upsert), Esc aborts. Committing an empty
  `:text` object deletes it.

### Keys

Ignored while the textarea has focus.

| Key | Action |
|---|---|
| F2 | edit text |
| Delete, Backspace | delete the unlocked selected objects |
| Esc | clear the selection, select tool |
| ctrl/cmd+G | group |
| ctrl/cmd+shift+G | ungroup |
| ctrl/cmd+Z | undo |
| ctrl/cmd+shift+Z, ctrl+Y | redo |

### Paste

Window `paste` event, ignored while editing text:

- an image file → `:image` with its data URL, natural size scaled down to fit
  600×600, centered in the view; a data URL over 15 MB is refused with
  "Image too large (max 15 MB)" in the session bar;
- otherwise plain text → `:text` object with that text, 300×100, centered.

### Sync

- Every local mutation goes through one function,
  `(commit db before-after)`, which updates `[:objects]`, records a history
  entry, and returns `:hammer.tubes/send` effects: one `[:op/upsert [...]]` for
  the changed objects and/or one `[:op/delete [...]]` for the removed ids.
- Incoming `[:op/upsert ...]`, `[:op/delete ...]` and `[:session/snapshot ...]`
  only update `[:objects]` (and drop removed ids from the selection);
  a snapshot also clears history.
- During a drag or resize the objects update locally every pointer move,
  applied to their *current* state (a remote fill/lock/delete during the drag is
  kept); `:op/patch` frames with geometry only go out at most every 33 ms and
  once more on pointer-up.
- Upserts go out one object per frame, so a selection of images never exceeds
  the frame limit.
- HTTP answers carry the session id they were asked for and are ignored once
  the user is in another session.
- `[:session/missing]` destroys the tube and returns to landing with
  "Session expired or not found".
- Tube `:on-connect` sets online and sends `[:session/hello]`;
  `:on-disconnect` shows the offline badge. Queued ops go out on reconnect
  (hammer.tubes), then the hello, then the server's snapshot arrives.
- Only `:op/upsert`, `:op/delete`, `:session/snapshot` and `:session/missing`
  from the server are dispatched (tube `:on-receive`); anything else is
  dropped.

### Undo/redo

- A history entry is `{:before {id obj-or-nil} :after {id obj-or-nil}}` for the
  ids an action changed (`nil` = absent).
- Undo applies `:before`, redo applies `:after`: present entries as one
  upsert, nil entries as one delete — ordinary ops, so other clients see normal
  edits.
- One entry per user action. A drag or resize gesture is one entry (state at
  pointer-down vs pointer-up); a text edit is one entry on commit.
- Per client, own actions only, capped at 100. A new action clears redo; a
  snapshot clears both stacks.
- Undo writes the `:before` state back under last-write-wins: it overwrites a
  later edit by another user of the same object. Accepted.

## Errors

| Case | Handling |
|---|---|
| join unknown id | landing, "Session not found" |
| session expired while connected/away | `[:session/missing]` → landing, "Session expired or not found" |
| WS disconnect | offline badge, ops queued, snapshot on reconnect |
| import 400 | message in the session bar, board unchanged |
| unsupported paste | ignored |
| malformed frame or op on the server | ignored, channel stays open |
| hammer-reported errors | console (default reporter) |

## Testing

| Suite | Runner | Covers |
|---|---|---|
| backend | `bb test` | session ops (create, upsert, delete with cascade, snapshot, client join/leave), `expire` with an injected clock, tar write → read round trip, export/import with images, HTTP routes via the handler fn |
| frontend | `npm test` (shadow `:node-test`, jsdom) | `geom` (camera, hit-test, resize, rubber band, `fit-text` with a fake measure), `model` (groups, locks, cascade, history: entries, undo/redo, cap, redo cleared, drag coalesced), events through `hammer.testing` with a fake WebSocket asserting the ops sent |
| build | `npx shadow-cljs release app` | `:advanced` build, no warnings |
| manual | `bb server`, two browser tabs | live sync, every tool, export → import |

Canvas drawing is verified by eye in the browser.
