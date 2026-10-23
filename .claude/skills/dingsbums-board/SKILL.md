---
name: dingsbums-board
description: Use when asked to generate, draft or template a dingsbums board — a retro, kanban, dot-voting round, workshop canvas, diagram or any prepared layout — as a file the user uploads with the board's Import button. Writes session.edn in dingsbums' object format and packs it into the .tar Import takes.
---

# dingsbums board

dingsbums is a whiteboard people share by link. Its **Import** button (top
left, beside Export) takes a `.tar` and puts that board on the session for
everyone on it. This skill writes such a file, so a board can be prepared
before a meeting instead of drawn live: columns for a retro, a voting grid
with a pool of dots, a diagram, a workshop agenda.

Import **replaces everything** on the board and clears undo: tell the user
to import into a fresh session (or export first). Objects the server finds
invalid are **dropped without a word**, so always pack with `pack.clj`,
which runs the server's own import on the result.

## The file

A ustar tar with `session.edn` and, for pictures, `images/<id>.<ext>`:

```clojure
{:version 1
 :objects {"title" {:id "title" :kind :text :x 40 :y 100 :w 520 :h 64 :z 1 :text "Sprint 42 retro"}
           "s1" {:id "s1" :kind :sticky :x 40 :y 200 :w 200 :h 200 :z 2 :fill "#fff176" :text "Pairing"}}}
```

`:objects` is a **map** from id to object; the key is the object's `:id`.

## Objects

Every key an object may have; any other key, or a value of the wrong type,
gets the whole object dropped.

| Key | Value |
| --- | --- |
| `:id` | string, 1–64 of `A-Z a-z 0-9 _ -`, unique |
| `:kind` | `:text :sticky :frame :shape :image :connection :dot :counter :scale-x :scale-xy` |
| `:x :y :w :h` | numbers, world pixels; y grows downwards; every kind but `:connection` needs all four to be drawn (the app's own minimum is 20×20) |
| `:z` | number: stacking within a layer, higher on top |
| `:text` | string (`:text`, `:sticky`); `\n` breaks lines |
| `:fill` | `"#rrggbb"` (`:shape`, `:sticky`, `:dot`); nil, or left out, is no fill on a shape and the default colour on the others |
| `:color` | text colour `"#rrggbb"` (`:text`, `:sticky`); leave out for automatic |
| `:shape` | `:rect :circle :star :arrow` (`:shape`) |
| `:side` | `:top :bottom :left :right`: where a measuring box shows its number, outside the box (default bottom) |
| `:from :to` | ids of the two objects a `:connection` joins |
| `:src` | `"images/<id>.png"` (file in the tar) or a `data:` URL (`:image`) |
| `:group` | any string; objects sharing it select and move together |
| `:locked?` | `true`: can't be moved, edited or deleted until unlocked |

The kinds, with the size the app gives them:

| Kind | Looks like | Default size |
| --- | --- | --- |
| `:text` | bare text, no box | 200×50 |
| `:sticky` | flat coloured note, text inside; default fill `#fff176` | 200×200 |
| `:frame` | thin rounded outline; **no title of its own**: put a `:text` above it | 400×300 |
| `:shape` | outlined rect, circle (ellipse in its box), star or block arrow; optional fill | 120×120 |
| `:image` | the picture, stretched to the box | its own |
| `:connection` | straight line with an arrowhead, from `:from`'s center to `:to`'s edge; no `:x :y :w :h` | — |
| `:dot` | filled circle (default `#e57373`), the thing people vote with | 24×24 |
| `:counter` | outlined box showing how many dots have their center in it | 300×200 |
| `:scale-x` | box with a 0–10 axis along the bottom; shows the dots' average x (`Ø 3.7`) | 500×160 |
| `:scale-xy` | box with 0–10 axes, y counting up; shows the average x and y | 400×400 |

Colours the app's menus offer, so a generated board matches what people can
pick: `#fff176` yellow, `#ffb74d` orange, `#e57373` red, `#81c784` green,
`#64b5f6` blue, `#ba68c8` purple, `#ffffff`, `#000000`.

## How it draws

- **Layers**, bottom to top: frames and measuring boxes, then connections,
  then everything else. `:z` orders within a layer; give each object its own.
- **Text fits its box.** The font is monospace and sized to the largest that
  fits (6–200 px), top-left aligned. It wraps at spaces and keeps `\n`;
  it shrinks rather than break a word. A big box with a short text gets a
  huge font, so size boxes for the font you want: about
  `w = 0.6 × size × chars + 2p`, `h = 1.2 × size × lines + 2p`, with padding
  `p` 8 for `:text` and 20 for `:sticky`. A 40 px heading of 20 characters:
  ~500×64. A 200×200 sticky reads well up to ~25 words. Each box sizes its
  own text, so stickies of different lengths show different sizes; put
  line breaks in multi-step instructions.
- **Text colour** is automatic: dark or white to suit a sticky's fill, or
  the sticky or filled shape behind a `:text`, else the theme's (dark on
  light themes, light on dark). Set `:color` only for emphasis.
- **The first view** shows world (0, 0) at the screen's top-left at 100%,
  under the session bar and the list of who is on the board. Start content
  around x 40, y 120. Boards larger than the screen are fine (people pan
  and zoom out): put the title and the instructions in the first
  ~1200×700, the sections after it in reading order, below or right.
- **Measuring boxes** count **dots only**, by their center, live. The
  number (a count, or `Ø 3.7`, or `Ø 3.7 · 6.2`) sits ~32 px outside the
  box on its `:side`, centred; leave that space free. A counter has nothing
  inside, so anything may go in it. A scale's axes run along its bottom
  edge (x, 0 left to 10 right) and, for `:scale-xy`, its left edge (y, 0
  bottom to 10 top), with tick labels just inside; its average shows as a
  line or crosshair across the box. Put axis labels outside: under the
  bottom edge's ends and left of the left edge's ends, with the number on
  another side (`:side :right`, say) when it would collide with them.
- **Locked** objects can't be dragged, but other objects still move over
  them: lock the boxes, frames and titles of a template, never the dots or
  stickies people should move. A locked box still counts dots dropped in.

## Layout recipes

- **Section:** a `:frame`, and a `:text` title 64 px tall just above its
  top-left corner. Lock the title and frame (`:locked? true`) when they are
  scaffolding people shouldn't knock around.
- **Columns** (retro, kanban): frames side by side, 40–60 px apart, each
  titled; stickies on a 220 px grid inside (200 px notes, 20 px gaps).
- **Dot voting:** a `:counter` per option (title it, or put the option's
  sticky in it — stickies don't count), a row of `:dot`s outside the boxes,
  a few per person. For votes you can tell apart, give each person a colour
  and place their dots together: by position, not `:group`, which would
  make them move as one.
- **Rating:** a `:scale-x` (one question) or `:scale-xy` (two, e.g. effort
  × impact) with labelled ends: small `:text`s beside the axes.
- **Diagram:** shapes or stickies on a grid, `:connection`s between them.
  A connection whose `:from` or `:to` is missing is kept but not drawn.

Ids only need to be unique; readable ones (`"col-good"`, `"dot-3"`) make
the file easy to adjust. For grids, compute positions with a short script
rather than by hand.

## Steps

1. Ask what the board is for if it isn't clear (who, how many people,
   what they will do on it); that decides sections, sizes and how many
   dots. Told not to ask: choose, and say what you chose when handing over.
2. Write `session.edn` into a new folder, pictures into `images/` beside it.
3. Pack it, from the dingsbums repo root:

   ```sh
   bb -cp src .claude/skills/dingsbums-board/pack.clj <folder> [out.tar]
   ```

   It writes the tar (default `board.tar`), then reads it back through the
   server's import and lists every object it would drop, every connection
   to a missing object and every missing picture; it exits 1 if there is
   any. Fix and pack again until it is clean.
4. Hand over the tar: open a session, Import, pick the file. It replaces
   the board, so a fresh session is the safe place.
