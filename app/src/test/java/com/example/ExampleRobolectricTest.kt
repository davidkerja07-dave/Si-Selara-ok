package com.example

import com.example.data.AulaConstants
import com.example.data.BookingEntity
import com.example.data.WorkspaceConstants
import com.example.ui.AuthHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleRobolectricTest {

    @Test
    fun verifyAulaOptions() {
        assertEquals(3, AulaConstants.AULA_OPTIONS.size)
        assertTrue(AulaConstants.AULA_OPTIONS.contains("Aula PLHUT"))
        assertTrue(AulaConstants.AULA_OPTIONS.contains("Aula Kemenag"))
        assertTrue(AulaConstants.AULA_OPTIONS.contains("Aula Kecil"))
    }

    @Test
    fun verifyBookingEntityCreationAndSerialization() {
        val booking = BookingEntity(
            id = "booking_1",
            userId = "user_alice",
            memberUids = listOf("user_alice"),
            namaPeminjam = "Drs. H. Mulyadi",
            tanggalPeminjaman = "2026-11-04",
            tanggalEpochMs = 1793760000000L,
            durasiPinjam = "4 Jam",
            noWhatsapp = "081234567890",
            pilihanAula = AulaConstants.AULA_PLHUT,
            catatan = "Rapat Manasik Haji"
        )
        assertEquals("Drs. H. Mulyadi", booking.namaPeminjam)
        assertEquals(AulaConstants.AULA_PLHUT, booking.pilihanAula)
        assertEquals("4 Jam", booking.durasiPinjam)

        val createMap = booking.toCreateMap("user_alice", listOf("user_bob"))
        assertFalse(createMap.containsKey("id"))
        assertEquals("user_alice", createMap["userId"])
        assertEquals(WorkspaceConstants.WORKSPACE_ID, createMap["workspaceId"])
        assertTrue((createMap["memberUids"] as List<*>).containsAll(listOf("user_alice", "user_bob")))
        assertTrue(createMap.containsKey("createdAt"))
        assertTrue(createMap.containsKey("updatedAt"))

        val updateMap = booking.toEditUpdateMap()
        assertFalse(updateMap.containsKey("id"))
        assertFalse(updateMap.containsKey("userId"))
        assertFalse(updateMap.containsKey("createdAt"))
        assertTrue(updateMap.containsKey("updatedAt"))
    }

    @Test
    fun verifyHallConflictDetection() {
        val targetDate = 1793760000000L
        val existingBookings = listOf(
            BookingEntity(
                id = "booking_1",
                namaPeminjam = "H. Ahmad Fauzi",
                tanggalPeminjaman = "2026-11-04",
                tanggalEpochMs = targetDate,
                durasiPinjam = "4 Jam",
                noWhatsapp = "081234567890",
                pilihanAula = AulaConstants.AULA_PLHUT,
                catatan = "Manasik"
            )
        )

        val bookedMap = existingBookings
            .filter { it.tanggalEpochMs == targetDate }
            .associateBy { it.pilihanAula }

        assertTrue(bookedMap.containsKey(AulaConstants.AULA_PLHUT))
        assertTrue(!bookedMap.containsKey(AulaConstants.AULA_KEMENAG))
        assertTrue(!bookedMap.containsKey(AulaConstants.AULA_KECIL))
    }

    @Test
    fun verifyLoginPasswordValidation() {
        assertTrue(AuthHelper.verifyPassword("kemenag36#"))
        assertNotNull(AuthHelper.getVerifiedAccessHash("kemenag36#"))
        assertFalse(AuthHelper.verifyPassword("wrongpassword"))
        assertNull(AuthHelper.getVerifiedAccessHash("wrongpassword"))
        assertFalse(AuthHelper.verifyPassword(""))
        assertFalse(AuthHelper.verifyPassword("kemenag36"))
    }
}
