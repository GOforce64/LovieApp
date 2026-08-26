# In-app redesign — design

Date: 2026-08-26
Status: awaiting review
Authority: `love-button-spec.md`. Where this document and the spec disagree, the
spec wins, and §11 below lists the amendments this design requires.

---

## 1. Why

The app's home screen is a scaffold. It was built to prove the send path worked and
has never been designed. Two things changed since:

1. The receipt ladder shipped (Plan 4), and **the app cannot show any of it**.
   `SendWorker` reports state through `setWidgetState(context, appWidgetId, …)`,
   addressed by a platform widget id. Sending from the app passes
   `INVALID_APPWIDGET_ID`, and that function returns immediately. A tap in the app
   gives a haptic and then silence — no sending, no sent, no delivered, no seen.
2. The four widget states now carry meaning (crimson, pink, gold) that nothing in
   the app explains.

## 2. Scope

**In:** the Home screen; a new states guide; the in-app state ladder and the
plumbing it needs; the visual theme; the splash animation; the mascot on the
message buttons; a fix to the icon generator's stale colours.

**Out:** any further "cute things in general" the partner adds later — those are a
separate pass. Message history (settled: the server purges at 7 days, §5.1). A
theme switcher: this is **one** theme, not two skins.

---

## 3. What the app is for

**Watching it land.** The widgets are the fast way to send; the app is where you see
it arrive. That single decision drives everything below — it is why the ladder needs
plumbing that does not exist, and why the focal area is the largest thing on screen.

---

## 4. The state ladder in the app

### 4.1 How the app learns the state

A new `CurrentSend` DataStore holding one record: `{sendId, msgId, state, at}`.

- `SendWorker` writes it when a send starts, and updates it as the send resolves.
- `PushService` updates it when a receipt arrives whose `send_id` matches.
- The UI collects it as a `Flow` and renders whatever it says.

Chosen over an in-memory `StateFlow` because MIUI kills this process routinely — the
project already treats process death as normal rather than exceptional — so an
in-memory ladder would vanish mid-send, and the cold-open requirement (§4.3) would
need a second persistence mechanism anyway. One mechanism, not two. It also matches
the shape of `PendingSends` and `UnseenSends`, which already exist.

Rejected: reusing Glance's per-widget store with a synthetic widget id. It abuses a
store built for widgets and muddies what `INVALID_APPWIDGET_ID` means.

### 4.2 Widget sends appear too

`SendWorker` writes `CurrentSend` on **every** send, whatever the origin. Tapping a
widget and then opening the app shows that send laddering. This is free — the same
code path — and it is what the partner asked for explicitly.

### 4.3 The focal area keeps the outcome; the widget forgets

One deliberate divergence from §7.1.

| | Widget | App focal area |
|---|---|---|
| After the 20s window | returns to **idle** | **keeps** the final state |
| Why | it is a button on your home screen, and a permanently lit button is noise | remembering is its entire job |

So on a cold open the focal area shows how the last send ended:

```
        ♥♥♥            <- gold, seen
   Wifey saw your
   "Miss you" · 2h ago
```

That record lives **only on this phone**, and it is one record, not a log. It does
not reopen the server retention decision.

### 4.4 Copy

Warm and plain. These are read hundreds of times; the quiet ones wear out slowest.
`partnerName` already lives in `Prefs`, so each phone says the other's name.

| State | Line |
|---|---|
| Sending | `sending…` |
| Sent | `on its way to {partner}` |
| Delivered | `it buzzed her phone` |
| Seen | `{partner} saw it ♡` |
| Failed | `didn't get through :(` |
| Cold open | `{partner} saw your "{message}" · {age} ago` |

"her phone" is written from the sending phone's point of view; the delivered line
does not need the partner's name because the seen line right after it does.

### 4.5 The focal heart is animated

The widget swaps between five finished pictures because `RemoteViews` can do little
else. The app is Compose and is not limited that way, so the focal heart animates per
pixel:

| State | Motion |
|---|---|
| Sending | crimson rises row by row from the point upward, looping until the request returns — a slow network reads as "still going", not "stuck" |
| Sent | the fill completes, then one short squash-and-settle |
| Delivered | crimson turns to pink from the centre outward, a ring of pixels at a time |
| Seen | the outline lights gold pixel by pixel around the shape, then a few pixels flash and fade |
| Failed | two quick shakes, then the colour drains to grey |
| Idle | the resting outline breathes, barely |

*Seen* is the only state with a flourish, because it is the only state that is the
point of the app.

All six honour `prefers-reduced-motion` / the system animation scale by falling back
to the plain state change.

---

## 5. The states guide

A new screen. Six rows: the picture, the state name, and what it means.

**The guide shows the widget's own drawables — the same generated art, unmodified.**
This is the whole point of it. A guide that draws its own approximation teaches you
about a picture that does not exist. The state→drawable mapping is extracted out of
`MessageWidget` into one shared function used by both, so the guide is structurally
incapable of showing a colour the widget does not draw.

| State | Picture | Line |
|---|---|---|
| Idle | `ic_heart_outline` | click the button! |
| Sending | `ic_heart_half` | on its way to {partner} 0o0 |
| Sent | `ic_heart_filled` (crimson) | traveling in the interwebs (• ε •) |
| Delivered | `ic_heart_delivered` (pink) | it buzzed {partner}'s phone :3 |
| Seen | `ic_heart_seen` (pink + gold) | {partner} looked at it (>^o^)> |
| Failed | `ic_heart_outline`, grey tint | didn't get through （◞‸◟）|

Reached from Home, alongside Delivery setup.

**The guide and the focal area speak with one voice.** They originally did not: the
guide was playful (kaomoji, exclamation marks) because you read it a handful of times
ever, and the focal lines were warm and plain because you read those hundreds of
times and the loud ones wear out. Seen on hardware, the two vocabularies for the same
six states read as a guide to some *other* screen, so the focal area now shows the
guide's own line. There is one set of words per state, and the guide explains this
screen in the words this screen uses.

Every line here that carries `{partner}` is substituted the same way §4.4's are, so
the guide names the other person too.

---

## 6. The theme — "Sticker Book"

One aesthetic. Lovie and animal in the same world, not a switch between them.

Every element is a sticker: a hard ink keyline, flat colour, and a **shallow** offset
shadow — pressed down flat rather than popping off the page.

| Token | Value | Role |
|---|---|---|
| `ground` | `#FFF0F5` | screen background |
| `ink` | `#2E2430` | keyline and text |
| `surface` | `#FFFFFF` | cards, unaccented buttons |
| `mint` | `#7FD6C2` | message 2 sticker |
| `blossom` | `#FFB8CE` | message 3 sticker |
| `butter` | `#FFCF7A` | message 4 sticker |

Shadow depth: **1.5–2px** on buttons, 2–3px on the focal card. Deliberately shallow —
the first pass was 3–5px and read as too poppy.

Each message gets its own sticker colour, so the four become distinguishable by hue
before the text is read.

**Type:** Fredoka for headings and the warm lines, Quicksand for body. Both bundled
as assets rather than loaded through downloadable-fonts, so rendering never depends
on Play Services being reachable.

### 6.1 The state colours are shared tokens

`sent #D81B60`, `delivered #FF6FA5`, `seen #FFC64B` are defined **once** and used by
the pixel art, the focal area, and the guide. However cute the app's chrome becomes,
those three colours mean exactly one thing everywhere. This is the constraint that
lets the app be redesigned freely without the guide starting to lie.

---

## 7. The mascot

A panda, with a hat and bamboo, in **four cute faces** — supplied by the partner, not
generated here.

**Settled:** one face per message, sitting beside that message's button and
**always visible** — not a reaction, not a state. The pandas are static artwork; the
partner cannot source animation, and none is needed here.

The panda does **not** appear in the focal area. The focal area is the pixel heart,
animated (§4.5). Keeping state in exactly one visual language is what keeps the guide
honest: if a panda also expressed state, the guide would have to teach two vocabularies
and would immediately be incomplete.

### 7.1 Asset manifest

What the partner needs to supply:

| File | Content |
|---|---|
| `panda_love.webp` | face for "I love you" |
| `panda_thinking.webp` | face for "Thinking of you" |
| `panda_miss.webp` | face for "Miss you" |
| `panda_call.webp` | face for "Call me when you can" |

- **512 × 512**, transparent background, WebP (or PNG — the build converts).
- **Static images only.** No animation, no sprite sheets, no Lottie.
- Panda centred with roughly 8% padding, so it can be cropped to a circle later
  without losing the hat.
- Same hat and bamboo across all four; **only the face changes**. That consistency
  is what makes it one character rather than four pandas.
- Dropped into `app/src/main/res/drawable/`.

**The screens are built against placeholders first**, so implementation is never
blocked waiting on art. Swapping the real files in is a drop-in with no code change.

---

## 8. Splash animation

Android 12+ `SplashScreen` API with an animated vector — the heart drawing itself and
filling. Reuses the ladder's own idea rather than inventing a second motion language.

Capped at ~1000ms, and cold-start only. Uses the platform API rather than a fake
splash Activity, which is both faster and the only approach that does not flash the
system splash first.

---

## 9. Fixing the icon generator

`scripts/pixel_icons.py` still carries the pre-swap colours:

```python
FILL = "#FF6FA5"   # generator calls this "sent"
DEEP = "#C2185B"   # generator calls this "delivered" — the old, too-dark crimson
```

The shipped drawables are now `sent #D81B60` and `delivered #FF6FA5`. **Re-running
the generator today would silently revert the colour swap and restore a crimson that
was rejected on hardware.** It is a loaded gun pointed at a decision that has already
been made.

Fix: update the constants and their comments to match, then re-run it and confirm the
output is byte-identical to the committed drawables.

**The generator also gains a Kotlin emitter.** Animating individual pixels (§4.5)
means the app needs the grids as *data*, not as finished VectorDrawables. So
`pixel_icons.py` writes a Kotlin source file of the same grids alongside the XML it
already produces. One source of truth: the animated heart in the app and the static
heart on the home screen are generated from the same ASCII art in the same run, and
cannot drift. Hand-copying the grids into Kotlin would guarantee they eventually do. If it is not, the generator has
drifted in some other way too, and that difference is the real finding.

Not a defect, and not to be "fixed": `ic_call_seen.xml` is drawn entirely in gold.
The phone glyph is one pixel thick and has no border layer to gild, so the glyph
itself turns gold — without it, *seen* would be indistinguishable from *delivered*
for that icon. The generator documents this. Leave it alone.

---

## 10. Testing

| Piece | How |
|---|---|
| State → copy line | Pure function, unit tested, all six states |
| State → drawable mapping | Pure function, unit tested; shared by widget and guide |
| `CurrentSend` | Robolectric, like `PendingSends` |
| Generator fix | Re-run, diff output against committed drawables |
| The ladder end to end | Hardware, two phones — same as Plan 4 |
| Kotlin grid emitter | Generated grids compared against the committed drawables' cells |

The ladder cannot be unit tested for the same reason the widget's could not: it spans
a worker, a push handler and a UI. Hardware is the gate, and that is stated here so
nobody later mistakes green tests for a working ladder.

---

## 11. Spec amendments required

- **§6.1** — three screens becomes four; Home gains the focal ladder; the guide is
  added. The "send read receipts toggle" is already gone.
- **§7.1** — note that the app's focal area keeps the final state where the widget
  returns to idle, and why.

No server change. No API change. No migration.

---

## 12. Resolved

**The panda mapping is settled** (§7): four static faces, one per message, always
visible beside their button, never expressing state. Confirmed by the partner before
any art is commissioned, which was the point of raising it.

**Division of labour, plainly:** the partner supplies four static panda images; every
piece of motion in this design is generated from the pixel grids and built here.
