package com.example

import com.example.data.AulaConstants
import com.example.data.BookingEntity
import org.junit.Assert.assertEquals
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
    fun verifyBookingEntityCreation() {
        val booking = BookingEntity(
            id = 1L,
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
    }

    @Test
    fun verifyHallConflictDetection() {
        val targetDate = 1793760000000L
        val existingBookings = listOf(
            BookingEntity(
                id = 1L,
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

        // Aula PLHUT is booked
        assertTrue(bookedMap.containsKey(AulaConstants.AULA_PLHUT))
        // Aula Kemenag and Aula Kecil are still available
        assertTrue(!bookedMap.containsKey(AulaConstants.AULA_KEMENAG))
        assertTrue(!bookedMap.containsKey(AulaConstants.AULA_KECIL))
    }
}
