package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Cloud Firestore repository providing real-time synchronized data access across devices.
 */
class BookingRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Pengguna harus masuk dengan akun Google terlebih dahulu.")
    }

    /**
     * Registers or verifies this Google-authenticated device in the shared Kemenag workspace
     * after password verification so bookings sync in real time across devices.
     */
    suspend fun ensureWorkspaceMembership(
        accessHash: String,
        displayName: String?
    ): Result<Unit> {
        val uidSnapshot = auth.currentUser?.uid ?: "unauthenticated"
        var currentPath = "workspaces/${WorkspaceConstants.WORKSPACE_ID}/members/$uidSnapshot"
        var currentOp = OperationType.WRITE

        return try {
            val uid = requireUserId()
            val memberRef = db.collection("workspaces")
                .document(WorkspaceConstants.WORKSPACE_ID)
                .collection("members")
                .document(uid)
            currentPath = memberRef.path

            val memberSnapshot = memberRef.get().await()
            val trimmedName = displayName?.trim()?.take(100)?.takeIf { it.isNotEmpty() }

            if (!memberSnapshot.exists()) {
                currentOp = OperationType.CREATE
                val memberPayload = buildMap<String, Any> {
                    put("userId", uid)
                    put("accessHash", accessHash)
                    if (trimmedName != null) put("displayName", trimmedName)
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                memberRef.set(memberPayload).await()
            } else if (trimmedName != null) {
                currentOp = OperationType.UPDATE
                memberRef.update(
                    mapOf(
                        "displayName" to trimmedName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val workspaceRef = db.collection("workspaces").document(WorkspaceConstants.WORKSPACE_ID)
            currentPath = workspaceRef.path
            try {
                currentOp = OperationType.UPDATE
                workspaceRef.update(
                    mapOf(
                        "memberUids" to FieldValue.arrayUnion(uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                    currentOp = OperationType.CREATE
                    workspaceRef.set(
                        mapOf(
                            "name" to WorkspaceConstants.WORKSPACE_NAME,
                            "ownerId" to uid,
                            "memberUids" to listOf(uid),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } else {
                    throw e
                }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, currentOp, currentPath)
            Result.failure(e)
        }
    }

    /**
     * Observes the live list of authorized workspace member UIDs.
     */
    fun observeWorkspaceMembers(userId: String): Flow<List<String>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val docRef = db.collection("workspaces").document(WorkspaceConstants.WORKSPACE_ID)
        val path = docRef.path
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    val raw = snapshot.get("memberUids") as? List<*>
                    val members = raw?.filterIsInstance<String>().orEmpty()
                    if (userId in members) members else (members + userId).distinct()
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    emit(listOf(userId))
                }
        )
    }

    /**
     * Real-time stream of all bookings visible to [userId], ordered chronologically.
     * Client query aligns 1:1 with `allow list: if isSignedIn() && request.auth.uid in resource.data.memberUids`.
     */
    fun observeBookings(userId: String): Flow<List<BookingEntity>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "bookings"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        BookingEntity::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(
                        compareBy<BookingEntity> { it.tanggalEpochMs }
                            .thenByDescending { it.createdAt?.seconds ?: 0L }
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Checks live Firestore bookings for a date + hall conflict before saving.
     */
    suspend fun isAulaBooked(
        userId: String,
        aula: String,
        tanggalEpochMs: Long,
        excludeId: String?
    ): Boolean {
        val path = "bookings"
        return try {
            require(userId.isNotBlank()) { "User ID must not be blank" }
            val snapshot = db.collection(path)
                .whereArrayContains("memberUids", userId)
                .get()
                .await()
            val bookings = snapshot.toObjects(
                BookingEntity::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            bookings.any {
                it.pilihanAula == aula &&
                    it.tanggalEpochMs == tanggalEpochMs &&
                    (excludeId == null || it.id != excludeId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            false
        }
    }

    suspend fun insertBooking(
        booking: BookingEntity,
        authorizedMembers: List<String>
    ): Result<Unit> {
        var docPath = "bookings"
        return try {
            val uid = requireUserId()
            validateBookingFields(booking)
            val docRef = db.collection("bookings").document()
            docPath = docRef.path
            val payload = booking.toCreateMap(uid, authorizedMembers)
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateBooking(booking: BookingEntity): Result<Unit> {
        var docPath = "bookings/${booking.id}"
        return try {
            requireUserId()
            require(booking.id.isNotBlank()) { "ID peminjaman tidak boleh kosong untuk pembaruan." }
            validateBookingFields(booking)
            val docRef = db.collection("bookings").document(booking.id)
            docPath = docRef.path
            docRef.update(booking.toEditUpdateMap()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun syncBookingMembers(
        bookingId: String,
        creatorUid: String,
        memberUids: List<String>
    ): Result<Unit> {
        val docPath = "bookings/$bookingId"
        return try {
            requireUserId()
            require(bookingId.isNotBlank()) { "Booking ID must not be blank" }
            val merged = (memberUids + creatorUid).distinct().take(100)
            db.collection("bookings").document(bookingId)
                .update(
                    mapOf(
                        "memberUids" to merged,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteBooking(booking: BookingEntity): Result<Unit> {
        val docPath = "bookings/${booking.id}"
        return try {
            requireUserId()
            require(booking.id.isNotBlank()) { "ID peminjaman tidak boleh kosong untuk penghapusan." }
            db.collection("bookings").document(booking.id).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    private fun validateBookingFields(booking: BookingEntity) {
        val nama = booking.namaPeminjam.trim()
        require(nama.length in 3..100) { "Nama peminjam harus antara 3 hingga 100 karakter." }
        require(booking.tanggalPeminjaman.trim().length in 10..20) { "Format tanggal tidak valid." }
        require(booking.tanggalEpochMs > 0L) { "Tanggal peminjaman harus dipilih." }
        require(booking.durasiPinjam.trim().length in 3..30) { "Durasi peminjaman tidak valid." }
        require(booking.noWhatsapp.trim().length in 9..20) { "Nomor WhatsApp harus antara 9 hingga 20 karakter." }
        require(booking.pilihanAula in AulaConstants.AULA_OPTIONS) { "Pilihan aula tidak valid." }
        require(booking.catatan.trim().length <= 500) { "Catatan maksimal 500 karakter." }
    }
}
