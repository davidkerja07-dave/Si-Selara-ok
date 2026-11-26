package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AulaConstants
import com.example.data.BookingEntity
import com.example.data.BookingRepository
import com.example.data.WorkspaceConstants
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class FormState(
    val editingId: String? = null,
    val namaPeminjam: String = "",
    val namaError: String? = null,
    val tanggalEpochMs: Long = System.currentTimeMillis(),
    val tanggalFormatted: String = "",
    val tanggalDisplay: String = "",
    val tanggalError: String? = null,
    val durasiNilai: String = "4",
    val durasiSatuan: String = "Jam",
    val durasiError: String? = null,
    val noWhatsapp: String = "",
    val whatsappError: String? = null,
    val pilihanAula: String = AulaConstants.AULA_PLHUT,
    val aulaError: String? = null,
    val catatan: String = "",
    val isSaving: Boolean = false
)

data class WeeklyStats(
    val totalSemua: Int = 0,
    val totalPlhut: Int = 0,
    val totalKemenag: Int = 0,
    val totalKecil: Int = 0
)

data class WeekRange(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val label: String,
    val offset: Int
)

class BookingViewModel(
    private val repository: BookingRepository,
    private val currentUserId: String
) : ViewModel() {

    val bookingsUiState: StateFlow<UiState<List<BookingEntity>>> = repository.observeBookings(currentUserId)
        .map<List<BookingEntity>, UiState<List<BookingEntity>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing bookings", rootError)
            emit(UiState.Error(rootError.localizedMessage ?: "Gagal menyinkronkan data jadwal aula"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val allBookings: StateFlow<List<BookingEntity>> = bookingsUiState
        .map { state -> (state as? UiState.Success)?.data.orEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    private val workspaceMembers: StateFlow<List<String>> = repository.observeWorkspaceMembers(currentUserId)
        .catch { error ->
            if (error is CancellationException) throw error
            emit(listOf(currentUserId))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = listOf(currentUserId)
        )

    // Form State
    private val _formState = MutableStateFlow(FormState())
    val formState: StateFlow<FormState> = _formState.asStateFlow()

    // Real-time map of booked halls for the currently chosen form date (excluding current editing booking)
    val bookedAulasOnSelectedDate: StateFlow<Map<String, BookingEntity>> = combine(
        allBookings,
        _formState
    ) { bookings, form ->
        bookings
            .filter { it.tanggalEpochMs == form.tanggalEpochMs && (form.editingId == null || it.id != form.editingId) }
            .associateBy { it.pilihanAula }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyMap()
    )

    private val _weekOffset = MutableStateFlow(0)
    val weekOffset: StateFlow<Int> = _weekOffset.asStateFlow()

    // Filter by Aula on weekly screen ("Semua", "Aula PLHUT", "Aula Kemenag", "Aula Kecil")
    private val _selectedAulaFilter = MutableStateFlow("Semua")
    val selectedAulaFilter: StateFlow<String> = _selectedAulaFilter.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Active bottom navigation tab (0 = Form Peminjaman, 1 = Rekap Mingguan, 2 = Semua Arsip)
    private val _currentTab = MutableStateFlow(1)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    // UI Events (Snackbar messages)
    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val indonesianDateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
    private val shortRangeFormat = SimpleDateFormat("d MMM yyyy", Locale("id", "ID"))

    init {
        val today = System.currentTimeMillis()
        val cleanMidnight = normalizeMidnight(today)
        _formState.update {
            it.copy(
                tanggalEpochMs = cleanMidnight,
                tanggalFormatted = isoDateFormat.format(Date(cleanMidnight)),
                tanggalDisplay = indonesianDateFormat.format(Date(cleanMidnight))
            )
        }

        // Automatically propagate new authorized workspace devices to existing shared bookings
        viewModelScope.launch {
            combine(workspaceMembers, allBookings) { members, bookings ->
                members to bookings
            }.collect { (members, bookings) ->
                if (members.size > 1) {
                    bookings.filter { booking ->
                        booking.id.isNotBlank() && !booking.memberUids.containsAll(members)
                    }.forEach { booking ->
                        val ownerUid = booking.userId.ifBlank { currentUserId }
                        repository.syncBookingMembers(booking.id, ownerUid, members)
                    }
                }
            }
        }
    }

    private fun normalizeMidnight(epochMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun selectTab(tab: Int) {
        _currentTab.value = tab
    }

    // --- FORM ACTIONS ---

    fun onNamaChanged(nama: String) {
        val bounded = nama.take(100)
        _formState.update {
            it.copy(
                namaPeminjam = bounded,
                namaError = if (bounded.trim().length < 3) "Nama peminjam minimal 3 karakter" else null
            )
        }
    }

    fun onTanggalSelected(epochMs: Long) {
        val cleanMidnight = normalizeMidnight(epochMs)

        val currentForm = _formState.value
        val bookedOnDate = allBookings.value
            .filter { it.tanggalEpochMs == cleanMidnight && (currentForm.editingId == null || it.id != currentForm.editingId) }
            .associateBy { it.pilihanAula }

        var currentAula = currentForm.pilihanAula
        var aulaError: String? = null

        if (bookedOnDate.containsKey(currentAula)) {
            val availableAula = AulaConstants.AULA_OPTIONS.firstOrNull { !bookedOnDate.containsKey(it) }
            if (availableAula != null) {
                currentAula = availableAula
                viewModelScope.launch {
                    _uiEvent.emit("Aula sebelumnya sudah terisi pada tanggal ini. Dialihkan ke $availableAula yang tersedia.")
                }
            } else {
                aulaError = "Semua gedung aula sudah penuh terisi pada tanggal ini. Silakan pilih tanggal lain."
                viewModelScope.launch {
                    _uiEvent.emit("Perhatian: Seluruh gedung aula sudah penuh pada tanggal yang dipilih!")
                }
            }
        }

        _formState.update {
            it.copy(
                tanggalEpochMs = cleanMidnight,
                tanggalFormatted = isoDateFormat.format(Date(cleanMidnight)),
                tanggalDisplay = indonesianDateFormat.format(Date(cleanMidnight)),
                pilihanAula = currentAula,
                aulaError = aulaError,
                tanggalError = null
            )
        }
    }

    fun onDurasiNilaiChanged(nilai: String) {
        val filtered = nilai.filter { it.isDigit() }.take(4)
        _formState.update {
            it.copy(
                durasiNilai = filtered,
                durasiError = if (filtered.isBlank() || filtered.toIntOrNull() == 0) "Durasi pinjam harus diisi angka valid" else null
            )
        }
    }

    fun onDurasiSatuanChanged(satuan: String) {
        _formState.update { it.copy(durasiSatuan = satuan) }
    }

    fun onWhatsappChanged(wa: String) {
        val filtered = wa.filter { it.isDigit() || it == '+' }.take(20)
        val digitsCount = filtered.count { it.isDigit() }
        _formState.update {
            it.copy(
                noWhatsapp = filtered,
                whatsappError = if (digitsCount < 9 || filtered.length < 9) "Nomor WhatsApp minimal 9 digit angka" else null
            )
        }
    }

    fun onAulaSelected(aula: String) {
        if (!AulaConstants.AULA_OPTIONS.contains(aula)) return
        val currentForm = _formState.value

        val bookedEntity = allBookings.value.firstOrNull {
            it.tanggalEpochMs == currentForm.tanggalEpochMs &&
                it.pilihanAula == aula &&
                (currentForm.editingId == null || it.id != currentForm.editingId)
        }

        if (bookedEntity != null) {
            _formState.update {
                it.copy(aulaError = "Gedung $aula sudah disewa oleh ${bookedEntity.namaPeminjam} pada tanggal ini dan tidak dapat dipilih.")
            }
            viewModelScope.launch {
                _uiEvent.emit("Tidak dapat dipilih: $aula sudah disewa oleh ${bookedEntity.namaPeminjam}.")
            }
            return
        }

        _formState.update {
            it.copy(
                pilihanAula = aula,
                aulaError = null
            )
        }
    }

    fun onCatatanChanged(catatan: String) {
        _formState.update { it.copy(catatan = catatan.take(500)) }
    }

    fun startEditBooking(booking: BookingEntity) {
        val parts = booking.durasiPinjam.split(" ")
        val nilai = parts.firstOrNull()?.filter { it.isDigit() } ?: "1"
        val satuan = if (parts.size > 1) parts[1] else "Jam"

        _formState.value = FormState(
            editingId = booking.id,
            namaPeminjam = booking.namaPeminjam,
            tanggalEpochMs = booking.tanggalEpochMs,
            tanggalFormatted = booking.tanggalPeminjaman,
            tanggalDisplay = indonesianDateFormat.format(Date(booking.tanggalEpochMs)),
            durasiNilai = nilai,
            durasiSatuan = satuan,
            noWhatsapp = booking.noWhatsapp,
            pilihanAula = booking.pilihanAula,
            catatan = booking.catatan,
            aulaError = null
        )
        _currentTab.value = 0
    }

    fun resetForm() {
        val cleanMidnight = normalizeMidnight(System.currentTimeMillis())

        val bookedToday = allBookings.value
            .filter { it.tanggalEpochMs == cleanMidnight }
            .associateBy { it.pilihanAula }
        val defaultAula = AulaConstants.AULA_OPTIONS.firstOrNull { !bookedToday.containsKey(it) } ?: AulaConstants.AULA_PLHUT

        _formState.value = FormState(
            editingId = null,
            namaPeminjam = "",
            tanggalEpochMs = cleanMidnight,
            tanggalFormatted = isoDateFormat.format(Date(cleanMidnight)),
            tanggalDisplay = indonesianDateFormat.format(Date(cleanMidnight)),
            durasiNilai = "4",
            durasiSatuan = "Jam",
            noWhatsapp = "",
            pilihanAula = defaultAula,
            catatan = "",
            aulaError = if (bookedToday.size >= AulaConstants.AULA_OPTIONS.size)
                "Semua gedung aula sudah penuh terisi pada hari ini. Silakan pilih tanggal lain." else null
        )
    }

    fun saveBooking() {
        val state = _formState.value

        var hasError = false
        var updatedNamaError: String? = null
        var updatedDurasiError: String? = null
        var updatedWhatsappError: String? = null

        val trimmedNama = state.namaPeminjam.trim()
        if (trimmedNama.length !in 3..100) {
            updatedNamaError = "Nama peminjam harus 3 hingga 100 karakter"
            hasError = true
        }

        val durasiNum = state.durasiNilai.toIntOrNull()
        if (durasiNum == null || durasiNum <= 0) {
            updatedDurasiError = "Durasi pinjam harus berupa angka positif"
            hasError = true
        }

        val trimmedWa = state.noWhatsapp.trim()
        val waDigits = trimmedWa.filter { it.isDigit() }
        if (waDigits.length < 9 || trimmedWa.length !in 9..20) {
            updatedWhatsappError = "Nomor WhatsApp harus 9 hingga 20 karakter"
            hasError = true
        }

        if (hasError) {
            _formState.update {
                it.copy(
                    namaError = updatedNamaError,
                    durasiError = updatedDurasiError,
                    whatsappError = updatedWhatsappError
                )
            }
            viewModelScope.launch {
                _uiEvent.emit("Harap perbaiki data yang belum lengkap.")
            }
            return
        }

        viewModelScope.launch {
            _formState.update { it.copy(isSaving = true) }
            try {
                // Live Firestore conflict check across all synchronized devices
                val isAlreadyBooked = repository.isAulaBooked(
                    userId = currentUserId,
                    aula = state.pilihanAula,
                    tanggalEpochMs = state.tanggalEpochMs,
                    excludeId = state.editingId
                )
                if (isAlreadyBooked) {
                    val bookedBy = allBookings.value.firstOrNull {
                        it.tanggalEpochMs == state.tanggalEpochMs &&
                            it.pilihanAula == state.pilihanAula &&
                            (state.editingId == null || it.id != state.editingId)
                    }?.namaPeminjam ?: "pihak lain"

                    _formState.update {
                        it.copy(aulaError = "Gedung ${state.pilihanAula} sudah disewa oleh $bookedBy pada tanggal ini!")
                    }
                    _uiEvent.emit("Gagal: Gedung ${state.pilihanAula} sudah disewa oleh $bookedBy. Silakan pilih gedung aula lain.")
                    return@launch
                }

                val durasiString = "${state.durasiNilai} ${state.durasiSatuan}"
                val result = if (state.editingId != null) {
                    val updatedBooking = BookingEntity(
                        id = state.editingId,
                        userId = currentUserId,
                        workspaceId = WorkspaceConstants.WORKSPACE_ID,
                        namaPeminjam = trimmedNama,
                        tanggalPeminjaman = state.tanggalFormatted,
                        tanggalEpochMs = state.tanggalEpochMs,
                        durasiPinjam = durasiString,
                        noWhatsapp = trimmedWa,
                        pilihanAula = state.pilihanAula,
                        catatan = state.catatan.trim().take(500)
                    )
                    repository.updateBooking(updatedBooking)
                } else {
                    val newBooking = BookingEntity(
                        userId = currentUserId,
                        workspaceId = WorkspaceConstants.WORKSPACE_ID,
                        memberUids = workspaceMembers.value,
                        namaPeminjam = trimmedNama,
                        tanggalPeminjaman = state.tanggalFormatted,
                        tanggalEpochMs = state.tanggalEpochMs,
                        durasiPinjam = durasiString,
                        noWhatsapp = trimmedWa,
                        pilihanAula = state.pilihanAula,
                        catatan = state.catatan.trim().take(500)
                    )
                    repository.insertBooking(newBooking, workspaceMembers.value)
                }

                if (result.isSuccess) {
                    val msg = if (state.editingId != null)
                        "Peminjaman aula berhasil diperbarui & disinkronkan online!"
                    else
                        "Peminjaman aula berhasil disimpan & disinkronkan online!"
                    _uiEvent.emit(msg)
                    resetForm()
                    _currentTab.value = 1
                } else {
                    val err = result.exceptionOrNull()
                    _uiEvent.emit("Gagal menyimpan ke server: ${err?.localizedMessage ?: "Terjadi kesalahan jaringan"}")
                }
            } finally {
                _formState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun deleteBooking(booking: BookingEntity) {
        viewModelScope.launch {
            val result = repository.deleteBooking(booking)
            if (result.isSuccess) {
                _uiEvent.emit("Data peminjaman telah dihapus secara online.")
            } else {
                val err = result.exceptionOrNull()
                _uiEvent.emit("Gagal menghapus: ${err?.localizedMessage ?: "Terjadi kesalahan jaringan"}")
            }
        }
    }

    // --- REKAP MINGGUAN & STATISTIK ---

    fun nextWeek() {
        _weekOffset.update { it + 1 }
    }

    fun previousWeek() {
        _weekOffset.update { it - 1 }
    }

    fun resetToCurrentWeek() {
        _weekOffset.value = 0
    }

    fun onAulaFilterChanged(filter: String) {
        _selectedAulaFilter.value = filter
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun getWeekRange(offset: Int): WeekRange {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            add(Calendar.WEEK_OF_YEAR, offset)
        }
        val startMs = cal.timeInMillis

        val calEnd = Calendar.getInstance().apply {
            timeInMillis = startMs
            add(Calendar.DAY_OF_MONTH, 6)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endMs = calEnd.timeInMillis

        val startLabel = shortRangeFormat.format(Date(startMs))
        val endLabel = shortRangeFormat.format(Date(endMs))

        val label = when (offset) {
            0 -> "Minggu Ini ($startLabel - $endLabel)"
            -1 -> "Minggu Lalu ($startLabel - $endLabel)"
            1 -> "Minggu Depan ($startLabel - $endLabel)"
            else -> "$startLabel - $endLabel"
        }

        return WeekRange(startMs, endMs, label, offset)
    }

    val currentWeekRange: StateFlow<WeekRange> = _weekOffset
        .map { offset -> getWeekRange(offset) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = getWeekRange(0)
        )

    val weeklyBookings: StateFlow<List<BookingEntity>> = combine(
        allBookings,
        currentWeekRange,
        _selectedAulaFilter
    ) { bookings, weekRange, filter ->
        bookings.filter { item ->
            val inWeek = item.tanggalEpochMs in weekRange.startEpochMs..weekRange.endEpochMs
            val matchesAula = if (filter == "Semua") true else item.pilihanAula == filter
            inWeek && matchesAula
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )

    val weeklyStats: StateFlow<WeeklyStats> = combine(
        allBookings,
        currentWeekRange
    ) { bookings, weekRange ->
        val weekItems = bookings.filter { it.tanggalEpochMs in weekRange.startEpochMs..weekRange.endEpochMs }
        WeeklyStats(
            totalSemua = weekItems.size,
            totalPlhut = weekItems.count { it.pilihanAula == AulaConstants.AULA_PLHUT },
            totalKemenag = weekItems.count { it.pilihanAula == AulaConstants.AULA_KEMENAG },
            totalKecil = weekItems.count { it.pilihanAula == AulaConstants.AULA_KECIL }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = WeeklyStats()
    )

    // --- ACTIONS: WHATSAPP & SHARE ---

    fun openWhatsApp(context: Context, rawNumber: String, namaPeminjam: String, aula: String, tanggal: String) {
        var cleanNumber = rawNumber.filter { it.isDigit() }
        if (cleanNumber.startsWith("0")) {
            cleanNumber = "62" + cleanNumber.substring(1)
        }

        val message = "Halo Bapak/Ibu $namaPeminjam, konfirmasi peminjaman/sewa $aula Kemenag Kota Mojokerto untuk tanggal $tanggal melalui sistem Si-Sera. Ada hal atau fasilitas yang perlu dikoordinasikan?"
        val encodedMessage = Uri.encode(message)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=$encodedMessage")

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            viewModelScope.launch {
                _uiEvent.emit("Tidak dapat membuka WhatsApp: Pastikan aplikasi WhatsApp terpasang.")
            }
        }
    }

    fun shareWeeklyReport(context: Context) {
        val weekRange = currentWeekRange.value
        val stats = weeklyStats.value
        val bookings = weeklyBookings.value

        val sb = StringBuilder()
        sb.append("📋 *SI-SERA - REKAPITULASI SEWA & PEMINJAMAN AULA*\n")
        sb.append("🏛 *Kemenag Kota Mojokerto*\n")
        sb.append("🗓 Periode: ${weekRange.label}\n\n")
        sb.append("📊 *RINGKASAN STATISTIK:*\n")
        sb.append("• Total Peminjaman: ${stats.totalSemua} kegiatan\n")
        sb.append("• Aula PLHUT: ${stats.totalPlhut} kegiatan\n")
        sb.append("• Aula Kemenag: ${stats.totalKemenag} kegiatan\n")
        sb.append("• Aula Kecil: ${stats.totalKecil} kegiatan\n\n")
        sb.append("📝 *DAFTAR JADWAL KEGIATAN:*\n")

        if (bookings.isEmpty()) {
            sb.append("Belum ada agenda peminjaman untuk periode ini.\n")
        } else {
            bookings.forEachIndexed { index, item ->
                sb.append("${index + 1}. *${item.pilihanAula}*\n")
                sb.append("   • Peminjam: ${item.namaPeminjam}\n")
                sb.append("   • Tanggal: ${item.tanggalPeminjaman}\n")
                sb.append("   • Durasi: ${item.durasiPinjam}\n")
                sb.append("   • No. WA: ${item.noWhatsapp}\n")
                if (item.catatan.isNotBlank()) {
                    sb.append("   • Catatan: ${item.catatan}\n")
                }
                sb.append("\n")
            }
        }
        sb.append("Dicatat melalui Si-Sera (Aplikasi Sewa dan Rekap Aula) Kemenag Kota Mojokerto.")

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Bagikan Rekap Mingguan").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(shareIntent)
    }

    private companion object {
        const val TAG = "BookingViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
