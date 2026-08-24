# Plan 3 design — four sounds, four widgets

- **Status:** approved 2026-08-24, not yet planned
- **Depends on:** Plans 1 and 2, both merged to `main`
- **Spec authority:** `love-button-spec.md` §6.2, §6.3, §7. Where this document and
  the spec disagree, the spec wins.

Milestone 4. Two pieces that ship together because neither is much use alone: the
four real notification sounds, and a home-screen widget per message so the buzz can
be sent without opening anything.

---

## 1. Scope

**In:**

- Four notification channels `msg_1`..`msg_4`, each with its own sound, replacing the
  temporary `dev_buzz_v1`.
- Four home-screen widgets, one per message, each sending its one hardcoded message.
- A reduced visual state ladder: Idle → Sending → Sent | Failed → Idle.

**Out, and deliberately so** — all of it belongs to Plan 4 and all of it depends on
`/v1/receipts`, which the Worker does not have:

- The Delivered and Seen states, including the outer glow.
- `pending_send_id → glanceId` correlation and the buffered-receipt race (spec §7.1).
- The "send read receipts" toggle.

The ladder stops at Sent rather than faking the rest. A widget that claims *delivered*
without a receipt is lying, and this is a product about knowing she got it.

---

## 2. The toolchain finding

**Pin `androidx.glance:glance-appwidget:1.1.1`.** Established by probe, not assumption.

| Version | Result |
|---|---|
| `1.+` → 1.3.0-alpha02 | **Fails.** Demands compileSdk 37 |
| 1.1.1 (latest stable) | **Builds clean** |

1.2.0 never left rc, so 1.1.1 is the newest stable that exists.

The failure mode is the reason this is written down: it surfaces as
`checkDebugAarMetadata` reporting "compileSdk of at least 37", naming neither Glance
nor a version, which is the same shape as the trap recorded in Ruling 8 for Compose
1.12.0. Anyone who bumps this dependency casually will lose an hour.

Glance does **not** drag Compose forward — every transitive request resolves back to
1.11.4 under the pinned BOM. The JDK 21 / Gradle 9.5.0 / AGP 8.13.0 / compileSdk 36
set is untouched by this plan.

---

## 3. Channels and sounds

`Messages.kt` gains a `soundRes` field, and each `LoveMessage.channelId` moves from
the shared `DEV_CHANNEL_ID` to its own `msg_N`.

`LoveButtonApp.onCreate` deletes `dev_buzz_v1` and creates the four real channels,
each `IMPORTANCE_HIGH` with its sound from `res/raw` and vibration on.

Deleting the dev channel is safe precisely because Task 2 of Plan 2 made it
throwaway for this exact moment: its id was chosen so that `msg_1` would still be
free when the real sounds arrived.

**This step is irreversible.** A channel's sound is frozen at creation (spec §6.3).
Creating `msg_1` with a placeholder burns that id permanently — fixing it later means
deleting the channel, which resets her notification settings visibly. Therefore the
four `.ogg` files must exist before any channel code runs. This is a human
prerequisite, not a coding task.

---

## 4. Widgets

One `MessageWidget(msgId)` carries all rendering. Four thin subclasses and four
`GlanceAppWidgetReceiver`s are registered separately in the manifest, with four
`appwidget-provider` XMLs — 2x2, `minWidth`/`minHeight` 110dp, `targetCell` 2x2,
resizing disabled.

Separate *registration* is what spec §7 requires, so that all four appear as distinct
entries in the launcher's picker with their own preview and label. It says nothing
about separate implementations, and four copies of the ladder would be four places to
fix every future change.

Icons are **custom pixel art**, authored as ASCII grids and generated into
VectorDrawables by `scripts/pixel_icons.py`. Eight drawables: heart, smiley speech
bubble, cat paw, and the word CALL, each filled and outline.

Three decisions worth keeping:

- **The outline variant is derived, never authored** — a cell touching an empty cell
  or the border. The pair cannot drift apart when a shape is edited.
- **Thin shapes are painted as pure fill.** CALL is one pixel thick everywhere, so it
  has no interior; painting its border would render the whole word in the dark rose
  while every other icon read as pink. The generator detects an empty interior and
  drops the border.
- **Every drawable renders into a 22-unit box inside a 24dp viewport regardless of
  grid size**, so an 11x11 icon sits optically identical beside a 13x13 one. The set
  ships at 11x11; the grid is per-icon, so that can change later without touching the
  rest.

Three colours, one `<path>` each, so any layer can be retinted without redrawing:
border `#D1447E`, fill `#FF6FA5`, shine `#FFD9E8`. The heart carries a shine on its
upper-left lobe.

The spec named "waving hand" and "phone" for messages 3 and 4. Both were replaced —
a hand does not survive an 11x11 grid, and a pixel telephone fights the diagonal.
A cat paw and the word CALL read instantly at widget size, which is the only thing
that matters here.

**Tap path:** `ActionCallback` → haptic (`CONFIRM`) → state Sending → enqueue
`SendWorker`. The haptic fires before the network call starts, which is what makes
the tap feel immediate (spec §7).

`SendWorker` gains a `glanceId` input and writes the outcome back to that widget's
state. It already exists and already routes every call through WorkManager, so this
is an added parameter rather than a new mechanism.

**State lives in Glance's own per-widget store**, which survives host-process death.
That matters more here than anywhere: on MIUI the widget host is killed routinely,
and state held in memory would silently reset to Idle mid-send.

**Not enrolled:** renders dimmed and opens the app instead of sending.

---

## 5. State ladder

| State | Icon | Colour | Held for |
|---|---|---|---|
| Idle | outline | pale grey-pink | — |
| Sending | outline, dimmed | pale pink | until the request returns |
| Sent | filled | pink | 4s, then Idle |
| Failed | outline + small ✗ | grey | 3s, then Idle |

Spec §7.1's table holds Sent "until receipt or timeout". With no receipts in this
plan, that reduces to a 4s hold. The transition function is pure and unit-tested; the
rendering is verified by eye.

---

## 6. Testing

Following spec §11 — JVM unit tests for pure logic, hardware for everything else.

**Unit:** the message → channel → sound mapping (extending the existing
`MessagesTest`), and the state-transition function.

**Hardware:** all four widgets appear as separate entries in the picker; each sends
its own message; all four sounds are audibly distinct **and still distinct after a
reinstall** — the second half is what proves the channels were created correctly the
first time rather than coincidentally sounding right.

---

## 7. Assets — done, staged outside the repo

Both prerequisites are complete and staged at `~/Downloads/love-button-assets/`.
Task 1 of the plan places them; nothing is in `res/` yet.

**Sounds** — sourced by Giorgos, then trimmed, peak-matched, faded and encoded:

| File | Message | Duration | Peak |
|---|---|---|---|
| `love.ogg` | I love you | 0.31s | -2.5 dB |
| `thinking.ogg` | Thinking of you | 0.50s | -1.8 dB |
| `miss.ogg` | Miss you | 1.13s | -2.9 dB |
| `call.ogg` | Call me | 1.04s | -2.5 dB |

Every source began with up to half a second of silence, which on this product is pure
latency — the buzz is the whole point, and a beat of nothing before it is felt even if
it is not noticed. All four now start on the first sample.

Normalisation is **peak-based, not LUFS**. EBU R128 needs roughly three seconds to
integrate and these are sub-second clips, so `loudnorm` was operating on meaningless
measurements; peak matching is what applies at this length. `miss.ogg` sits 1 dB below
the rest by ear, because a sustained voice reads louder than a transient at equal peak.

**Icons** — eight VectorDrawables plus the generator, to be committed as
`scripts/pixel_icons.py` so the grids stay editable rather than the XML.
