package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.BookingRepository
import com.example.ui.AuthHelper
import com.example.ui.BookingViewModel
import com.example.ui.UiState
import com.example.ui.attemptAutoSignIn
import com.example.ui.components.AllBookingsScreen
import com.example.ui.components.BookingFormScreen
import com.example.ui.components.LoginScreen
import com.example.ui.components.WeeklySummaryScreen
import com.example.ui.signOutGoogle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryGreen
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var isPasswordUnlocked by rememberSaveable { mutableStateOf(false) }
    var loginPassword by rememberSaveable { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isJoiningWorkspace by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = { loginError = null },
            onUnauthenticated = {},
            scope = scope
        )
    }

    // Reset password unlock state if Google user signs out
    LaunchedEffect(currentUser?.uid) {
        if (currentUser == null) {
            isPasswordUnlocked = false
        }
    }

    val user = currentUser
    val isAuthorized = user != null && isPasswordUnlocked

    Crossfade(targetState = isAuthorized, label = "auth_crossfade") { authorized ->
        if (!authorized || user == null) {
            val connectedLabel = user?.let { firebaseUser ->
                firebaseUser.email?.takeIf { it.isNotBlank() }
                    ?: firebaseUser.displayName?.takeIf { it.isNotBlank() }
                    ?: "Akun Google (${firebaseUser.uid.take(6)}...)"
            }
            LoginScreen(
                googleUserEmail = connectedLabel,
                isSigningInOrSyncing = isJoiningWorkspace,
                password = loginPassword,
                loginError = loginError,
                onPasswordChanged = { newPwd ->
                    loginPassword = newPwd
                    if (loginError != null) loginError = null
                },
                onGoogleAuthSuccess = {
                    loginError = null
                },
                onGoogleAuthError = { msg ->
                    loginError = msg
                },
                onSwitchGoogleAccount = {
                    isPasswordUnlocked = false
                    loginError = null
                    signOutGoogle(credentialManager, scope)
                },
                onLoginClick = {
                    val activeUser = auth.currentUser
                    when {
                        activeUser == null -> {
                            loginError = "Silakan tekan 'Masuk dengan Google' terlebih dahulu untuk sinkronisasi online."
                        }
                        loginPassword.isEmpty() -> {
                            loginError = "Password tidak boleh kosong."
                        }
                        else -> {
                            val accessHash = AuthHelper.getVerifiedAccessHash(loginPassword)
                            if (accessHash == null) {
                                loginError = "Password salah. Silakan periksa kembali."
                            } else {
                                isJoiningWorkspace = true
                                loginError = null
                                scope.launch {
                                    val repo = BookingRepository(context.applicationContext)
                                    val joinResult = repo.ensureWorkspaceMembership(
                                        accessHash = accessHash,
                                        displayName = activeUser.displayName ?: activeUser.email
                                    )
                                    isJoiningWorkspace = false
                                    if (joinResult.isSuccess) {
                                        loginPassword = ""
                                        isPasswordUnlocked = true
                                    } else {
                                        val msg = joinResult.exceptionOrNull()?.localizedMessage
                                        loginError = "Gagal menghubungkan ke server online: ${msg ?: "Periksa koneksi internet"}"
                                    }
                                }
                            }
                        }
                    }
                }
            )
        } else {
            key(user.uid) {
                MainFeatureScreen(
                    currentUserId = user.uid,
                    onLockOrLogout = {
                        isPasswordUnlocked = false
                        loginPassword = ""
                        loginError = null
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainFeatureScreen(
    currentUserId: String,
    onLockOrLogout: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BookingViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                BookingViewModel(BookingRepository(db), currentUserId)
            }
        }
    )

    val snackbarHostState = remember { SnackbarHostState() }

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val weekRange by viewModel.currentWeekRange.collectAsStateWithLifecycle()
    val weeklyStats by viewModel.weeklyStats.collectAsStateWithLifecycle()
    val weeklyBookings by viewModel.weeklyBookings.collectAsStateWithLifecycle()
    val allBookings by viewModel.allBookings.collectAsStateWithLifecycle()
    val bookingsUiState by viewModel.bookingsUiState.collectAsStateWithLifecycle()
    val selectedAulaFilter by viewModel.selectedAulaFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val bookedAulasOnSelectedDate by viewModel.bookedAulasOnSelectedDate.collectAsStateWithLifecycle()

    BackHandler(enabled = currentTab != 1) {
        if (currentTab == 0 && formState.editingId != null) {
            viewModel.resetForm()
        }
        viewModel.selectTab(1)
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Si-Sera",
                                fontWeight = FontWeight.Black,
                                fontSize = 23.sp,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (bookingsUiState is UiState.Error)
                                            Icons.Default.CloudOff else Icons.Default.CloudDone,
                                        contentDescription = "Status Sinkronisasi",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (bookingsUiState is UiState.Error) "Offline" else "Real-Time",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Aplikasi Sewa dan Rekap Aula — Kemenag Kota Mojokerto",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f),
                            lineHeight = 15.sp
                        )
                    }
                },
                actions = {
                    if (currentTab == 1) {
                        IconButton(
                            onClick = { viewModel.shareWeeklyReport(context) },
                            modifier = Modifier.testTag("topbar_share_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Bagikan Rekap Mingguan"
                            )
                        }
                    }
                    IconButton(
                        onClick = onLockOrLogout,
                        modifier = Modifier.testTag("topbar_logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(id = R.string.logout_desc)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("bottom_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Rekap Mingguan"
                        )
                    },
                    label = { Text("Rekap Mingguan", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_rekap")
                )

                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    icon = {
                        Icon(
                            imageVector = if (formState.editingId != null) Icons.Default.EditNote else Icons.Default.AddCircle,
                            contentDescription = "Form Peminjaman"
                        )
                    },
                    label = {
                        Text(
                            if (formState.editingId != null) "Edit Data" else "Pinjam Aula",
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_form")
                )

                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Semua Arsip"
                        )
                    },
                    label = { Text("Semua Arsip", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_tab_history")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 700.dp)
            ) {
                if (bookingsUiState is UiState.Error) {
                    val errMsg = (bookingsUiState as UiState.Error).message
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errMsg,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (bookingsUiState is UiState.Loading && allBookings.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = PrimaryGreen)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Menyinkronkan jadwal aula secara real-time...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    when (currentTab) {
                        0 -> BookingFormScreen(
                            formState = formState,
                            bookedAulas = bookedAulasOnSelectedDate,
                            onNamaChanged = viewModel::onNamaChanged,
                            onTanggalSelected = viewModel::onTanggalSelected,
                            onDurasiNilaiChanged = viewModel::onDurasiNilaiChanged,
                            onDurasiSatuanChanged = viewModel::onDurasiSatuanChanged,
                            onWhatsappChanged = viewModel::onWhatsappChanged,
                            onAulaSelected = viewModel::onAulaSelected,
                            onCatatanChanged = viewModel::onCatatanChanged,
                            onSaveClick = viewModel::saveBooking,
                            onCancelEditClick = {
                                viewModel.resetForm()
                                viewModel.selectTab(1)
                            }
                        )
                        1 -> WeeklySummaryScreen(
                            weekRange = weekRange,
                            weeklyStats = weeklyStats,
                            bookings = weeklyBookings,
                            selectedAulaFilter = selectedAulaFilter,
                            onPreviousWeek = viewModel::previousWeek,
                            onNextWeek = viewModel::nextWeek,
                            onCurrentWeekReset = viewModel::resetToCurrentWeek,
                            onAulaFilterSelected = viewModel::onAulaFilterChanged,
                            onEditBooking = viewModel::startEditBooking,
                            onDeleteBooking = viewModel::deleteBooking,
                            onWhatsAppClick = { rawNumber, nama, aula, tanggal ->
                                viewModel.openWhatsApp(context, rawNumber, nama, aula, tanggal)
                            },
                            onShareReport = { viewModel.shareWeeklyReport(context) },
                            onAddNewBooking = {
                                viewModel.resetForm()
                                viewModel.selectTab(0)
                            }
                        )
                        2 -> AllBookingsScreen(
                            bookings = allBookings,
                            searchQuery = searchQuery,
                            onSearchQueryChanged = viewModel::onSearchQueryChanged,
                            onEditBooking = viewModel::startEditBooking,
                            onDeleteBooking = viewModel::deleteBooking,
                            onWhatsAppClick = { rawNumber, nama, aula, tanggal ->
                                viewModel.openWhatsApp(context, rawNumber, nama, aula, tanggal)
                            }
                        )
                    }
                }
            }
        }
    }
}
