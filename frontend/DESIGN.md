# The Ledger — design system

The visualiser demonstrates a claim about evidence: that a log can prove its own integrity. So
it is designed like the two documents that deal in evidence — a **research paper** and a
**notary's ledger** — not like a SaaS dashboard. Every rule below exists to make the argument
easier to follow when it is projected in a viva.

Tokens live in [`src/index.css`](src/index.css); primitives in [`src/ui/`](src/ui/).

---

## 1. Principles

1. **One saturated colour, and it is ours.** Every baseline is graphite. CAAC is written in
   fountain-pen ultramarine (`--signal`). If something is blue, it is our contribution or the
   proof path that supports it. Nothing else may use `--signal`.
2. **Colour carries meaning, never decoration.** Green only means *verified*, vermilion only
   means *tampered*, ochre only means *evidence supplied by the proof* (siblings).
3. **Three voices, never mixed within a role** (§2).
4. **Whitespace is structure.** Sections are separated by space and a single rule, not by boxes.
   Boxes (`Figure`) are reserved for exhibits: things you point at.
5. **Motion explains or confirms; it never decorates** (§5). If an animation does not teach or
   acknowledge something, delete it.
6. **Paper by default.** Light theme for projectors; the night ledger is a re-inking of the same
   hierarchy, not an inversion.

## 2. Typography

| Voice | Face | Used for | Why |
|---|---|---|---|
| The paper | **Newsreader** (variable, optical size) | Masthead, section titles, ledes (italic), entry messages, citations | A text serif with real optical sizing: light and tight at display size, sturdy at lede size. It signals "this is research" and carries the narrative. |
| The instrument | **IBM Plex Sans** | Controls, labels, body, captions | Engineered, neutral, and designed alongside its mono, so UI and data sit on one rhythm. |
| The evidence | **IBM Plex Mono** | Hashes, counts, indices, kickers | Tabular figures and slashed zero: columns of numbers align, `0` is never `O`. Kickers in spaced caps act as the ledger's column headings. |

All three are bundled through `@fontsource`, so the demo works offline.

**Scale** — fluid, ratio ≈ 1.25 (major third), interpolated from 360 px to 1440 px:

| Token | Range | Role |
|---|---|---|
| `text-display` | 32 → 56 px | Masthead only, Newsreader light, tracking −2 % |
| `text-title` | 24 → 34 px | Section titles, Newsreader regular |
| `text-lede` | 17 → 20 px | Italic ledes, entry messages, key figures |
| `text-body` | 15 → 16 px | Body |
| `text-small` | 13 → 14 px | Controls, captions |
| `text-micro` | 11 → 12 px | Kickers, trace rows, axis labels |

Line length: ledes cap at 62ch, captions at 78ch.

## 3. Colour

| Token | Paper | Night | Meaning |
|---|---|---|---|
| `--paper` / `--paper-raised` / `--paper-sunk` | warm off-whites | warm near-blacks | surface, exhibit, hover well |
| `--ink` / `--ink-soft` / `--ink-faint` | near-black → taupe | cream → taupe | text hierarchy |
| `--rule` / `--rule-strong` | | | hairlines, field borders |
| `--signal` | #2b3bc9 | #8f9bff | **CAAC and the proof path only** |
| `--verified` | #2f7a3e | #7cc58a | a proof checked out |
| `--tampered` | #c8410b | #f08a5d | a mismatch, an error |
| `--sibling` | #b7791f | #e0b25a | hashes handed over by the proof |
| `--s-fixed`, `--s-time`, `--s-entropy`, `--s-paper`, `--s-pipeline` | desaturated | desaturated | baselines, graphite family |

Alternating chunks use the same hue at two opacities, so adjacent chunks separate without new
colours. CAAC's opacities are higher than the baselines', so it stays the most prominent row.

## 4. Space

Small steps are fixed (alignment needs exact pixels); large steps breathe with the viewport.

| Token | Range | Use |
|---|---|---|
| Tailwind 4-px steps | fixed | inside components |
| `gutter` | 16 → 40 px | page margins, column gaps |
| `block` | 20 → 32 px | between a header and its content, inside exhibits |
| `section` | 40 → 88 px | between sections, and above and below the trees |

Layout: one centred column, max 88 rem. Rows that compare strategies use a two-column grid
(label rail 10–15 rem | data), so every strip starts at the same x and is directly comparable.

## 5. Motion

Two curves: `--ease-arrive` (fast out, long settle) for things appearing; `--ease-shift` (symmetric)
for state changes. Durations are named by intent: `--dur-feedback` 140 ms, `--dur-state` 280 ms,
`--dur-reveal` 560 ms, `--dur-step` 140 ms. All of it collapses under `prefers-reduced-motion`.

| Interaction | What it teaches |
|---|---|
| **Proof climb** — the path draws leaf → root at one level per 140 ms; each trace row appears with its level; stage 2 starts only when stage 1 reaches its root | The cost of a proof is its height: you watch O(log n) instead of being told it. |
| **Notary stamp** — the verdict presses on, slightly rotated, after the climb finishes | A verdict is the conclusion of the computation, not a label shown before it. |
| **Hash avalanche** — a hash that changes scrambles and resolves left to right | One changed byte changes every character of SHA-256. Change the fixed chunk size and watch only the roots that depend on it re-resolve. |
| **Chunk glide** — chunks keep their identity and glide when boundaries move | Which boundaries moved and which held still: locality, made visible. |
| **Ink rule** — one rule slides under the active section tab | You are moving through one document, not between apps. |
| **Cost bars grow** — rebuild-cost bars grow from zero in row order | Magnitudes are compared as they land: CAAC's sliver against the paper's full bar. |
| **Changed chunks mark in** — after a change, the chunks whose root changed fill in vermilion | Where the change landed, per strategy: one block for CAAC, a whole tail for count-based cutting. |
| **Legend focus** — hovering a strategy in the Results legend dims the others in every chart | Follow one strategy through every metric at once. |
| **Procedure** — the anchor demo's steps darken their top rule when done; later steps stay faded until reachable | The demo has an order (anchor → attack → verify), and the page enforces it. |
| **Press** — buttons that compute sink 1 px | Acknowledgement, nothing more. |
| **Copy** — click any hash to copy it; "copied" rises briefly | Hashes are evidence you can take with you. |
| **Sweep placeholder** — slow sweep where data will land | Space is held; nothing jumps when results arrive. |

## 6. Primitives (`src/ui/`)

| Component | Role |
|---|---|
| `Section` | `§ n` kicker, serif title, italic lede, then content. Each view opens like a section of the paper. |
| `Figure` | The exhibit box, with a numbered caption beneath ("Fig. 2 …"). |
| `Field`, `Stat` | Labelled control; labelled figure (number is the hero). |
| `Hash` | Truncated mono hash: tooltip with the full value, click to copy, avalanche on change. |
| `Seal` | Verified / tampered verdict, stamped. Re-mount via `key` to stamp again. |
| `Tabs` | Numbered section tabs with the sliding ink rule; the URL hash tracks the tab. |
| `ThemeToggle` | Paper / night, remembered per browser; `?theme=dark` forces it (screenshots, slides). |

View-level patterns (in `src/components/`): the **label rail + strip** grid for comparing
strategies row by row (§1, §3), **headline figures** (big serif number, kicker unit, one-line
explanation) at the top of §3 and §4, and **numbered procedures** for multi-step demos (§3.1).

## 7. Writing

- Section titles are plain statements ("Proving one entry"), not marketing.
- Ledes state the idea in one or two sentences; captions say what the figure shows and how to
  read it.
- Numbers keep their units ("12 hashes", "384 B"). The paper is always credited where its method
  appears.
