const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const WORKSPACE_ID = "kemenag_kota_mojokerto";
const VALID_ACCESS_HASH =
  "aec543af69d44bf05572f5c7ab4c4a4de66df1e9543f47c57183a8727ac55841";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

async function registerVerifiedMember(db, uid, displayName = "Staff Kemenag") {
  const ts = pastTimestamp(10000);
  await db
    .collection("workspaces")
    .doc(WORKSPACE_ID)
    .collection("members")
    .doc(uid)
    .set({
      userId: uid,
      accessHash: VALID_ACCESS_HASH,
      displayName,
      createdAt: ts,
      updatedAt: ts,
    });
}

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

test("Unauthenticated user: denied all reads and writes", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = pastTimestamp();

  await assertFails(unauthDb.collection("bookings").get());
  await assertFails(unauthDb.collection("bookings").doc("b1").get());
  await assertFails(
    unauthDb.collection("bookings").doc("b1").set({
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "Unauthorized",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula PLHUT",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(unauthDb.collection("workspaces").doc(WORKSPACE_ID).get());
});

test("Workspace membership: requires matching accessHash and prevents spoofing", async () => {
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastTimestamp();

  // Wrong accessHash fails
  await assertFails(
    charlieDb
      .collection("workspaces")
      .doc(WORKSPACE_ID)
      .collection("members")
      .doc(CHARLIE_UID)
      .set({
        userId: CHARLIE_UID,
        accessHash: "wrong_hash",
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Creating another user's member document fails
  await assertFails(
    charlieDb
      .collection("workspaces")
      .doc(WORKSPACE_ID)
      .collection("members")
      .doc(ALICE_UID)
      .set({
        userId: ALICE_UID,
        accessHash: VALID_ACCESS_HASH,
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Unverified user cannot initialize workspace
  await assertFails(
    aliceDb.collection("workspaces").doc(WORKSPACE_ID).set({
      name: "Kemenag Kota Mojokerto",
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Verified user can register member doc and initialize workspace
  await assertSucceeds(registerVerifiedMember(aliceDb, ALICE_UID, "Alice"));
  await assertSucceeds(
    aliceDb.collection("workspaces").doc(WORKSPACE_ID).set({
      name: "Kemenag Kota Mojokerto",
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Listing workspaces collection is forbidden
  await assertFails(aliceDb.collection("workspaces").get());
});

test("Verified staff devices: can join workspace and share bookings in real time", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(10000);

  await assertSucceeds(registerVerifiedMember(aliceDb, ALICE_UID, "Alice"));
  await assertSucceeds(
    aliceDb.collection("workspaces").doc(WORKSPACE_ID).set({
      name: "Kemenag Kota Mojokerto",
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob without verified member doc cannot join workspace
  await assertFails(
    bobDb.collection("workspaces").doc(WORKSPACE_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: pastTimestamp(),
    })
  );

  // Bob registers verified member doc with password hash, then joins
  await assertSucceeds(registerVerifiedMember(bobDb, BOB_UID, "Bob"));
  await assertSucceeds(
    bobDb.collection("workspaces").doc(WORKSPACE_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: pastTimestamp(),
    })
  );

  // Bob cannot drop Alice (owner) while updating memberUids
  await assertFails(
    bobDb.collection("workspaces").doc(WORKSPACE_ID).update({
      memberUids: [BOB_UID],
      updatedAt: pastTimestamp(),
    })
  );
});

test("Bookings CRUD: valid Kotlin create, partial update, sync, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastTimestamp(10000);

  await assertSucceeds(registerVerifiedMember(aliceDb, ALICE_UID, "Alice"));

  // Mirrors BookingEntity.toCreateMap()
  await assertSucceeds(
    aliceDb.collection("bookings").doc("booking_1").set({
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "Drs. H. Mulyadi",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula PLHUT",
      catatan: "Manasik Haji",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Mirrors BookingEntity.toEditUpdateMap() (partial update preserving createdAt, userId, workspaceId)
  await assertSucceeds(
    aliceDb.collection("bookings").doc("booking_1").update({
      namaPeminjam: "Drs. H. Mulyadi (Revisi)",
      tanggalPeminjaman: "2026-11-26",
      tanggalEpochMs: 1795651200000,
      durasiPinjam: "1 Hari",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula Kemenag",
      catatan: "Diperbarui online",
      updatedAt: pastTimestamp(),
    })
  );

  // Mirrors BookingRepository.syncBookingMembers()
  await assertSucceeds(
    aliceDb.collection("bookings").doc("booking_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: pastTimestamp(),
    })
  );

  // Now Bob (as synchronized member) can read and edit booking_1 in real time
  await assertSucceeds(bobDb.collection("bookings").doc("booking_1").get());
  await assertSucceeds(
    bobDb.collection("bookings").doc("booking_1").update({
      namaPeminjam: "Drs. H. Mulyadi (Confirmed)",
      tanggalPeminjaman: "2026-11-26",
      tanggalEpochMs: 1795651200000,
      durasiPinjam: "1 Hari",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula Kecil",
      catatan: "",
      updatedAt: pastTimestamp(),
    })
  );

  // Reject immutable field mutation (createdAt / userId)
  await assertFails(
    aliceDb.collection("bookings").doc("booking_1").update({
      createdAt: pastTimestamp(100),
      updatedAt: pastTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("bookings").doc("booking_1").update({
      userId: BOB_UID,
      updatedAt: pastTimestamp(),
    })
  );

  // Delete succeeds for authorized member
  await assertSucceeds(bobDb.collection("bookings").doc("booking_1").delete());
});

test("Bookings validation bounds: rejects @DocumentId, ghost fields, bad enums, future timestamps", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastTimestamp(10000);
  await assertSucceeds(registerVerifiedMember(aliceDb, ALICE_UID, "Alice"));

  // Rejects @DocumentId `id` field in payload
  await assertFails(
    aliceDb.collection("bookings").doc("booking_id_field").set({
      id: "booking_id_field",
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "Ahmad",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula PLHUT",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Rejects ghost field (`isVerified`)
  await assertFails(
    aliceDb.collection("bookings").doc("booking_ghost").set({
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "Ahmad",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula PLHUT",
      isVerified: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Rejects invalid hall enum
  await assertFails(
    aliceDb.collection("bookings").doc("booking_bad_hall").set({
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "Ahmad",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula Lainnya",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Rejects tampered future timestamp
  const futureTs = new Date(Date.now() + 60000);
  await assertFails(
    aliceDb.collection("bookings").doc("booking_future_ts").set({
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "Ahmad",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula PLHUT",
      createdAt: futureTs,
      updatedAt: futureTs,
    })
  );
});

test("Cross-user isolation & query alignment: non-member denied; filtered query succeeds; unfiltered denied", async () => {
  const ts = pastTimestamp(10000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("bookings").doc("alice_booking").set({
      userId: ALICE_UID,
      workspaceId: WORKSPACE_ID,
      memberUids: [ALICE_UID],
      namaPeminjam: "H. Ahmad Fauzi",
      tanggalPeminjaman: "2026-11-25",
      tanggalEpochMs: 1795564800000,
      durasiPinjam: "4 Jam",
      noWhatsapp: "081234567890",
      pilihanAula: "Aula PLHUT",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  // Cross-user PII read/write/delete denied
  await assertFails(charlieDb.collection("bookings").doc("alice_booking").get());
  await assertFails(
    charlieDb
      .collection("bookings")
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );
  await assertFails(
    charlieDb.collection("bookings").doc("alice_booking").update({
      namaPeminjam: "Hijacked",
      updatedAt: pastTimestamp(),
    })
  );
  await assertFails(charlieDb.collection("bookings").doc("alice_booking").delete());

  // Exact Kotlin query succeeds for member
  await assertSucceeds(
    aliceDb
      .collection("bookings")
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );

  // Unfiltered query rejected
  await assertFails(aliceDb.collection("bookings").get());
});
