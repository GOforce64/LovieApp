import {
  defineWorkersConfig,
  readD1Migrations,
} from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig(async () => {
  // Read the .sql files in migrations/ so tests can apply them to a fresh
  // in-memory D1 instead of depending on a database someone set up by hand.
  const migrations = await readD1Migrations("./migrations");

  return {
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          singleWorker: true,
          wrangler: { configPath: "./wrangler.toml" },
          miniflare: {
            bindings: {
              TEST_MIGRATIONS: migrations,

              // Test doubles for the three real secrets.
              ENROLL_CODE_1: "test-code-one",
              ENROLL_CODE_2: "test-code-two",
              FCM_SERVICE_ACCOUNT: "{}",

              // These override the [vars] block in wrangler.toml so the suite
              // does not depend on whatever real values are deployed. The FCM
              // tests intercept a URL built from FIREBASE_PROJECT_ID, so this
              // value MUST stay "test-project" and match those interceptors.
              FIREBASE_PROJECT_ID: "test-project",
              PERSON_1_NAME: "Giorgos",
              PERSON_2_NAME: "Her",
            },
          },
        },
      },
    },
  };
});
