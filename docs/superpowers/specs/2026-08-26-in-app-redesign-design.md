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
| Failed | `didn't get through — it'll retry` |
| Cold open | `{partner} saw your "{message}" · {age} ago` |

"her phone" is written from the sending phone's point of view; the delivered line
does not need the partner's name because the seen line right after it does.

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
| Idle | `ic_heart_outline` | nothing sent yet |
| Sending | `ic_heart_half` | on its way |
| Sent | `ic_heart_filled` (crimson) | the server has it |
| Delivered | `ic_heart_delivered` (pink) | it buzzed her phone |
| Seen | `ic_heart_seen` (pink + gold) | she looked at it |
| Failed | `ic_heart_outline`, grey tint | didn't get through |

Reached from Home, alongside Delivery setup.

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

**Mapping (inferred — overrule if wrong):** one face per message. The face appears on
that message's button, and the focal area shows the face of whatever was last sent,
beside the pixel heart running the ladder. Four assets serve both places.

The ladder itself stays the pixel heart's job. The panda reacts; it does not encode
state. Keeping state in one visual language is what keeps the guide honest.

### 7.1 Asset manifest

What the partner needs to supply:

| File | Content |
|---|---|
| `panda_love.webp` | face for "I love you" |
| `panda_thinking.webp` | face for "Thinking of you" |
| `panda_miss.webp` | face for "Miss you" |
| `panda_call.webp` | face for "Call me when you can" |

- **512 × 512**, transparent background, WebP (or PNG — the build converts).
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
output is byte-identical to the committed drawables. If it is not, the generator has
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

## 12. Open question for review

**§7's mapping.** Four panda faces, one per message — versus one per ladder state.
The message mapping is chosen because the partner raised the faces while discussing
the message buttons, and because state already has a visual language (the pixel
heart) that must not be duplicated. Worth confirming before art is commissioned,
since it is the one decision here that costs money to redo.
