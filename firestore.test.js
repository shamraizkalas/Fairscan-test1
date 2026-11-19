const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_proscan_123";
const BOB_UID = "bob_proscan_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied read/write on profiles, folders, and documents", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("folders").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("documents").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_1").set({
      userId: ALICE_UID,
      title: "Hack",
      favorite: false,
      locked: false,
      pageCount: 1,
      lastExportFormat: "PDF",
      clientCreatedAtMs: Date.now() - 2000,
      clientUpdatedAtMs: Date.now() - 2000,
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("Owner user profile: valid create and partial update succeed; cross-user get/list rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice User",
      email: "alice@example.com",
      lastSyncedAt: past,
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      lastSyncedAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Cross-user read & listing all users denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").get());
});

test("Owner folders & documents: Kotlin-mirrored create, list, partial update, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const nowMs = Date.now() - 10000;

  // Create folder
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").doc("fld_work").set({
      userId: ALICE_UID,
      name: "Office & Bills",
      colorHex: 4278225275,
      locked: false,
      clientCreatedAtMs: nowMs,
      createdAt: created,
      updatedAt: created,
    })
  );

  // List folders (unfiltered under user scope)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").get()
  );

  // Create document (matches Kotlin write map with omitted null optional fields and NO "id" field)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_101").set({
      userId: ALICE_UID,
      title: "Electricity Bill",
      folderId: "fld_work",
      tags: ["utility", "paid"],
      favorite: true,
      locked: false,
      pageCount: 2,
      lastExportFormat: "PDF",
      clientCreatedAtMs: nowMs,
      clientUpdatedAtMs: nowMs,
      ocrText: "LESCO Electricity Invoice Total Rs 4500",
      aiSummary: "Monthly electricity bill for Rs 4500",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update preserving createdAt & clientCreatedAtMs
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_101").update({
      title: "Electricity Bill (Paid)",
      favorite: false,
      clientUpdatedAtMs: Date.now() - 1000,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // List documents
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").get()
  );

  // Delete document
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_101").delete()
  );
});

test("Red Team security guards: reject Ghost fields, @DocumentId id field, immutable mutations, cross-user writes, and future timestamps", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const nowMs = Date.now() - 10000;

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_safe").set({
      userId: ALICE_UID,
      title: "Contract PDF",
      favorite: false,
      locked: false,
      pageCount: 3,
      lastExportFormat: "PDF",
      clientCreatedAtMs: nowMs,
      clientUpdatedAtMs: nowMs,
      createdAt: created,
      updatedAt: created,
    })
  );

  // 1. Reject extra @DocumentId 'id' or ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_bad_id").set({
      id: "doc_bad_id",
      userId: ALICE_UID,
      title: "Bad Id",
      favorite: false,
      locked: false,
      pageCount: 1,
      lastExportFormat: "PDF",
      clientCreatedAtMs: nowMs,
      clientUpdatedAtMs: nowMs,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_safe").update({
      isAdminOverride: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // 2. Reject immutable createdAt tamper & future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_safe").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_future").set({
      userId: ALICE_UID,
      title: "Future",
      favorite: false,
      locked: false,
      pageCount: 1,
      lastExportFormat: "PDF",
      clientCreatedAtMs: nowMs,
      clientUpdatedAtMs: nowMs,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: created,
    })
  );

  // 3. Reject oversized thumbnail string (> 250000) or too many tags (> 20)
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_oversize").set({
      userId: ALICE_UID,
      title: "Huge Thumb",
      favorite: false,
      locked: false,
      pageCount: 1,
      lastExportFormat: "PDF",
      clientCreatedAtMs: nowMs,
      clientUpdatedAtMs: nowMs,
      coverThumbnailBase64: "A".repeat(250001),
      createdAt: created,
      updatedAt: created,
    })
  );

  // 4. Reject Bob reading or modifying Alice's documents
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("documents").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_safe").update({
      title: "Tampered by Bob",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});
