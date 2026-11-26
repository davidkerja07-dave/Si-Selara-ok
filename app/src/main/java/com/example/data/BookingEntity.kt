package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Cloud Firestore model representing a hall booking record synchronized in real time.
 */
@Keep
@IgnoreExtraProperties
data class BookingEntity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val workspaceId: String = WorkspaceConstants.WORKSPACE_ID,
    val memberUids: List<String> = emptyList(),
    val namaPeminjam: String = "",
    val tanggalPeminjaman: String = "", // ISO date string: YYYY-MM-DD
    val tanggalEpochMs: Long = 0L,      // Local midnight timestamp for week/date filtering
    val durasiPinjam: String = "",      // Display duration, e.g., "4 Jam" or "1 Hari"
    val noWhatsapp: String = "",        // WhatsApp contact number, e.g., "081234567890"
    val pilihanAula: String = AulaConstants.AULA_PLHUT, // Strictly "Aula PLHUT", "Aula Kemenag", or "Aula Kecil"
    val catatan: String = "",           // Additional notes or purpose of event
    @com.google.firebase.firestore.ServerTimestamp val createdAt: Timestamp? = null,
    @com.google.firebase.firestore.ServerTimestamp val updatedAt: Timestamp? = null
) {
    /**
     * Produces a Firestore creation payload without any `@DocumentId` `"id"` key
     * and with server-side timestamps.
     */
    fun toCreateMap(creatorUid: String, authorizedMembers: List<String>): Map<String, Any> {
        val mergedMembers = (authorizedMembers + creatorUid).distinct().take(100)
        val trimmedNotes = catatan.trim().take(500).takeIf { it.isNotEmpty() }
        val rawMap = mapOf(
            "userId" to creatorUid,
            "workspaceId" to WorkspaceConstants.WORKSPACE_ID,
            "memberUids" to mergedMembers,
            "namaPeminjam" to namaPeminjam.trim().take(100),
            "tanggalPeminjaman" to tanggalPeminjaman.trim(),
            "tanggalEpochMs" to tanggalEpochMs,
            "durasiPinjam" to durasiPinjam.trim().take(30),
            "noWhatsapp" to noWhatsapp.trim().take(20),
            "pilihanAula" to pilihanAula,
            "catatan" to trimmedNotes,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return rawMap.filterValues { it != null } as Map<String, Any>
    }

    /**
     * Produces a partial update payload modifying only editable booking details
     * while preserving immutable fields (`userId`, `workspaceId`, `createdAt`).
     */
    fun toEditUpdateMap(): Map<String, Any> {
        return mapOf(
            "namaPeminjam" to namaPeminjam.trim().take(100),
            "tanggalPeminjaman" to tanggalPeminjaman.trim(),
            "tanggalEpochMs" to tanggalEpochMs,
            "durasiPinjam" to durasiPinjam.trim().take(30),
            "noWhatsapp" to noWhatsapp.trim().take(20),
            "pilihanAula" to pilihanAula,
            "catatan" to catatan.trim().take(500),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

object AulaConstants {
    const val AULA_PLHUT = "Aula PLHUT"
    const val AULA_KEMENAG = "Aula Kemenag"
    const val AULA_KECIL = "Aula Kecil"

    val AULA_OPTIONS = listOf(AULA_PLHUT, AULA_KEMENAG, AULA_KECIL)
}

object WorkspaceConstants {
    const val WORKSPACE_ID = "kemenag_kota_mojokerto"
    const val WORKSPACE_NAME = "Kemenag Kota Mojokerto"
}
