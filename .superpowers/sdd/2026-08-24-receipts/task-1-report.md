# Task 1 Report: `/v1/send` accepts a client-minted `send_id`

## Changes Made

### 1. Updated `server/test/send.test.ts`
Added 4 new test cases to the "POST /v1/send" describe block:
- `accepts a client-minted send_id and uses it as the row id`: Verifies that a valid UUID provided by the client is used as the send record's id
- `rejects a malformed send_id`: Ensures that invalid UUIDs return 400 with `bad_request` error
- `rejects a send_id that already exists`: Verifies that duplicate send_ids return 409 with `duplicate_send_id` error
- `still mints a send_id when the client omits one`: Confirms backward compatibility when send_id is not provided

### 2. Updated `server/src/routes/send.ts`
- **Modified SendBody interface**: Added `send_id?: unknown` field
- **Added isUuid validator**: Exported function that validates v4-shaped UUIDs using regex `/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i`
- **Updated send_id handling**: 
  - Added validation that rejects malformed send_ids with 400
  - Changed from immediate insert to `INSERT OR IGNORE` with duplicate detection
  - Falls back to `crypto.randomUUID()` when client omits send_id
  - Returns 409 with `duplicate_send_id` error when a send_id already exists

## Test Results

### Initial Test Run (Before Implementation)
Failed as expected with 3 failures:
- Malformed send_id was accepted instead of returning 400
- Duplicate send_id returned 200 instead of 409
- Client-provided send_id was ignored in favor of minted one

### Final Test Run (After Implementation)
**Single file test**: `cd server && npx vitest run test/send.test.ts`
```
✓ test/send.test.ts (14 tests) 284ms
Test Files  1 passed (1)
Tests  14 passed (14)
```

**Full test suite**: `npm test`
```
✓ test/send.test.ts (14 tests)
✓ test/enroll.test.ts (8 tests)
✓ test/auth.test.ts (8 tests)
✓ test/messages.test.ts (6 tests)
✓ test/google-oauth.test.ts (3 tests)
✓ test/fcm.test.ts (5 tests)
✓ test/crypto.test.ts (9 tests)
✓ test/schema.test.ts (4 tests)
✓ test/health.test.ts (2 tests)

Test Files  9 passed (9)
Tests  59 passed (59)
```

## Key Implementation Details

1. **UUID Validation**: Strict regex-based validation prevents clients from writing arbitrary primary keys that could collide with rows they don't own
2. **INSERT OR IGNORE Pattern**: Using `changes` count to detect duplicates avoids race conditions where two simultaneous requests with the same send_id could both pass a prior existence check
3. **Backward Compatibility**: Endpoint continues to mint send_ids when clients don't provide one, so existing deployed Android clients remain compatible
4. **Recipients Always Derived**: The implementation preserves the invariant that recipients are never read from request bodies (remains `3 - from_person`)

## Commit

- **SHA**: f037f52
- **Message**: `feat(server): accept a client-minted send_id`
- **Files Modified**: 
  - server/src/routes/send.ts
  - server/test/send.test.ts

## Notes

- No surprises encountered during implementation
- All changes follow the provided requirements exactly as specified
- The endpoint maintains its 200-status guarantee even when returning errors for send_id validation (400 and 409 are appropriate error codes)
- Deployment was not run as per task instructions
