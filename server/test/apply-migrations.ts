import { applyD1Migrations, env } from "cloudflare:test";

// Runs once per test file, before any test. Applying the same migrations
// twice is a no-op, so this is safe to repeat.
await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
