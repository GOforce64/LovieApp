# Task 2 Report: Local Message Catalogue

## Status: DONE

**Commit SHA:** `10854ac`

---

## Files Created

1. **`app/src/main/java/com/lovebutton/app/data/Messages.kt`**
   - Data class `LoveMessage` with id, text, and channelId fields
   - Const `DEV_CHANNEL_ID = "dev_buzz_v1"` (deliberately temporary)
   - Val `MESSAGES` with four hardcoded messages (ids 1-4)
   - Function `messageForId(id: Int)` returning null for unknown ids
   - Complete doc comments explaining design rationale

2. **`app/src/test/java/com/lovebutton/app/MessagesTest.kt`**
   - Five test methods covering:
     - Catalogue size and message ids
     - Non-blank text validation
     - messageForId lookup
     - Null return for unknown ids
     - Dev channel assignment

---

## Test Results

### Step 2: Initial Test Run (Expected Failure)

Command:
```
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.MessagesTest"
```

**Result:** FAILED (as expected)
- Task `:app:compileDebugUnitTestKotlin` failed
- Error: `Unresolved reference 'data'` (expected — implementation not yet created)

### Step 4: Final Test Run (Expected Passing)

Command:
```
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:testDebugUnitTest --tests "com.lovebutton.app.MessagesTest"
```

**Result:** BUILD SUCCESSFUL
- 5 tests passed (0 failures, 0 skipped)
  - `messageForId returns null for an unknown id` ✓
  - `catalogue has the four spec messages with ids 1 to 4` ✓
  - `each message has non-blank text` ✓
  - `every message uses the temporary dev channel for now` ✓
  - `messageForId returns the matching message` ✓
- Duration: ~5 seconds
- 27 actionable tasks (7 executed, 20 up-to-date)

Test XML Report: `/home/killua/Projects/LovieApp/app/build/test-results/testDebugUnitTest/TEST-com.lovebutton.app.MessagesTest.xml`

---

## APK Build Verification

Command:
```
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
```

**Result:** BUILD SUCCESSFUL
- 40 actionable tasks (4 executed, 36 up-to-date)
- APK produced as expected
- No regressions from Task 1

---

## Implementation Notes

### Key Design Decisions (from brief)

1. **DEV_CHANNEL_ID Permanence:** Deliberately named `dev_buzz_v1` and not `msg_1`..`msg_4`. Android freezes notification channel sounds at creation time. Creating the real message channels now would permanently bake the default sound into those channel ids. This temporary channel is deleted and replaced when sounds are finalized in milestone 4.

2. **All Messages Use Dev Channel:** All four messages point to `DEV_CHANNEL_ID`. One test explicitly asserts this as intentional, not an oversight. This is by design per the brief.

3. **Null Return for Unknown IDs:** `messageForId()` returns `null` (not throwing, not a default) for unknown ids. This is intentional: an older app receiving a push with a newer message id (from a newer server build) must detect the unknown id gracefully rather than crash.

4. **Server vs. Client Responsibility:** Messages live entirely in the app. The server sends only the id (`msg_id: 3`). This means:
   - Message text never transits Google's servers
   - Adding a fifth message is an app-only change
   - Server maintains its own allowlist of valid ids

5. **Doc Comments:** All doc comments preserved verbatim from the brief for clarity and future learning.

---

## No Deviations

- All code transcribed verbatim from the brief
- TDD ordering followed exactly (test first, then implementation)
- Both files created in correct locations
- No modifications to existing files (Task 1 artifacts untouched)
- No changes to Gradle configuration or versions
- Commit message matches brief specification

---

## Next Steps for Task 3

The test source set is now ready for Task 3 (API client and MockWebServer tests). No additional configuration needed:
- `app/src/test/java/com/lovebutton/app/` directory structure is in place
- JUnit 4 imports confirmed working
- Gradle test infrastructure operational

---

**Implemented by:** Task 2 agent  
**Date:** 2026-08-22  
**Time:** ~5 minutes
