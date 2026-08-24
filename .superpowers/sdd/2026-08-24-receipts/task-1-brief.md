## Task 1: `/v1/send` accepts a client-minted `send_id`

The approved deviation from spec §5.2. Server-side only, so it can ship and be tested before the app changes.

**Files:**
- Modify: `server/src/routes/send.ts`
- Test: `server/test/send.test.ts`

**Interfaces:**
- Consumes: `recipientOf(person)`, `requireDevice`, `fail`, `nowSeconds`, `readJson`
- Produces: `POST /v1/send` accepting optional `send_id: string`; exports `isUuid(value: unknown): boolean`

- [ ] **Step 1: Write the failing tests**

Append inside the existing `describe("POST /v1/send", ...)` block in `server/test/send.test.ts`:

```typescript
  it("accepts a client-minted send_id and uses it as the row id", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });
    const id = "11111111-2222-4333-8444-555555555555";

    const res = await send({ msg_id: 1, send_id: id });
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ send_id: id });

    const row = await env.DB.prepare("SELECT id FROM sends WHERE id = ?").bind(id).first();
    expect(row).not.toBeNull();
  });

  it("rejects a malformed send_id", async () => {
    // Not merely tidiness: the id becomes a primary key, and a client that can
    // write arbitrary keys can collide with rows it does not own.
    const res = await send({ msg_id: 1, send_id: "not-a-uuid" });
    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ error: "bad_request" });
  });

  it("rejects a send_id that already exists", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });
    const id = "99999999-8888-4777-8666-555555555555";

    const first = await send({ msg_id: 1, send_id: id });
    expect(first.status).toBe(200);

    // A replayed id would otherwise overwrite or silently merge with the first
    // send, and its receipts would correlate to the wrong tile.
    const second = await send({ msg_id: 2, send_id: id });
    expect(second.status).toBe(409);
    expect(await second.json()).toMatchObject({ error: "duplicate_send_id" });
  });

  it("still mints a send_id when the client omits one", async () => {
    interceptFcm(200, { name: "projects/test-project/messages/1" });
    const res = await send({ msg_id: 1 });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { send_id: string };
    expect(body.send_id).toMatch(/^[0-9a-f-]{36}$/i);
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd server && npx vitest run test/send.test.ts`
Expected: FAIL — the malformed id is accepted and the duplicate returns 200 rather than 409.

- [ ] **Step 3: Implement in `server/src/routes/send.ts`**

Replace the `SendBody` interface and add the validator directly beneath it:

```typescript
interface SendBody {
  msg_id?: unknown;
  send_id?: unknown;
}

/**
 * A canonical v4-shaped UUID.
 *
 * The app mints the send id so that its `send_id -> widget` mapping exists
 * before the request leaves, which makes the receipt-beats-response race in
 * spec §7.1 unreachable rather than merely handled. The id becomes a primary
 * key, so it is validated rather than trusted: a client that can write
 * arbitrary keys can collide with rows it does not own.
 */
export function isUuid(value: unknown): boolean {
  return (
    typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
  );
}
```

Then replace the id-minting and insert block:

```typescript
  if (body.send_id !== undefined && !isUuid(body.send_id)) {
    return fail(c, 400, "bad_request", "send_id must be a UUID.");
  }

  const toPerson = recipientOf(device.person);
  const sendId = (body.send_id as string | undefined) ?? crypto.randomUUID();
  const sentAt = nowSeconds();

  // Record the send before pushing. The row is what a receipt correlates
  // against later, and it must exist even if every push fails.
  //
  // INSERT OR IGNORE rather than a SELECT-then-INSERT: two taps racing on the
  // same id would both pass a prior existence check and the second would
  // clobber the first. `changes` is the only answer that cannot race.
  const inserted = await c.env.DB.prepare(
    `INSERT OR IGNORE INTO sends (id, from_person, to_person, msg_id, sent_at)
     VALUES (?, ?, ?, ?, ?)`,
  )
    .bind(sendId, device.person, toPerson, body.msg_id, sentAt)
    .run();

  if (inserted.meta.changes === 0) {
    return fail(c, 409, "duplicate_send_id", "That send_id has already been used.");
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd server && npx vitest run test/send.test.ts`
Expected: PASS. The whole suite: `npm test` → 59 tests.

- [ ] **Step 5: Commit**

```bash
git add server/src/routes/send.ts server/test/send.test.ts
git commit -m "feat(server): accept a client-minted send_id"
```

---

