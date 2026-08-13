package com.panzhikun.metaldogshower

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val setupViewModel by viewModels<SetupViewModel>()
    private val controlViewModel by viewModels<PhoneControlViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playLaunchAnimation = savedInstanceState == null
        val marketingCapture = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("marketing_capture", false)
        enableEdgeToEdge()
        consumeIncomingIntent(intent)
        setContent {
            MetalDogTheme {
                AnimatedAppLaunch(playAnimation = playLaunchAnimation) {
                    MetalDogScreen(setupViewModel, controlViewModel, marketingCapture)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingIntent(intent)
    }

    private fun consumeIncomingIntent(intent: Intent?) {
        val value = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        value?.let(setupViewModel::acceptIncomingQr)
    }
}

private val Navy = Color(0xFF111638)
private val NavySoft = Color(0xFF272D55)
private val Orange = Color(0xFFE96B3A)
private val OrangeDark = Color(0xFFB9441F)
private val Ivory = Color(0xFFF7F1E3)

@Composable
private fun MetalDogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Color(0xFFFFB59B),
                onPrimary = Color(0xFF5C1604),
                primaryContainer = Color(0xFF7B2D14),
                onPrimaryContainer = Color(0xFFFFDBCF),
                secondary = Color(0xFFC4C8FF),
                onSecondary = Color(0xFF252A58),
                secondaryContainer = NavySoft,
                onSecondaryContainer = Color(0xFFE1E2FF),
                background = Color(0xFF101018),
                surface = Color(0xFF191921),
                surfaceVariant = Color(0xFF292934),
                onSurface = Color(0xFFF2F0FA),
                onSurfaceVariant = Color(0xFFC9C5D2),
                error = Color(0xFFFFB4AB),
            )
        } else {
            lightColorScheme(
                primary = OrangeDark,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFDBCF),
                onPrimaryContainer = Color(0xFF3B0A00),
                secondary = NavySoft,
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE1E2FF),
                onSecondaryContainer = Color(0xFF12163F),
                background = Color(0xFFF8F6F1),
                surface = Color(0xFFFFFBFF),
                surfaceVariant = Color(0xFFE9E5EE),
                onSurface = Color(0xFF1C1B20),
                onSurfaceVariant = Color(0xFF4A4750),
                error = Color(0xFFBA1A1A),
            )
        },
        content = content,
    )
}

@Composable
private fun MetalDogScreen(
    setupViewModel: SetupViewModel,
    controlViewModel: PhoneControlViewModel,
    marketingCapture: Boolean = false,
) {
    val setup by setupViewModel.state.collectAsStateWithLifecycle()
    val control by controlViewModel.state.collectAsStateWithLifecycle()
    val visibleControl = if (marketingCapture) {
        remember { marketingPhoneControlState() }
    } else {
        control
    }
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val darkTheme = isSystemInDarkTheme()
    val homeListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val onboardingStore = remember(context.applicationContext) {
        OnboardingStore(context.applicationContext)
    }
    var showOnboarding by rememberSaveable { mutableStateOf(onboardingStore.shouldShow()) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(setupViewModel::acceptIncomingQr)
    }
    val launchScanner = {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                .setPrompt("扫描所选浴室墙上的二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(true),
        )
    }

    LaunchedEffect(setup.loggedIn) {
        if (!setup.loggedIn) showSettings = false
    }
    LaunchedEffect(setup.loggedIn, setup.qrValue) {
        if (setup.loggedIn && setup.qrValue.isNotBlank()) showSettings = true
    }
    LaunchedEffect(
        setup.loggedIn,
        setup.pollingEnabled,
        setup.pollingIntervalSeconds,
        lifecycleOwner,
    ) {
        if (!setup.loggedIn || !setup.pollingEnabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controlViewModel.pollConfiguredRooms()
            while (isActive) {
                delay(setup.pollingIntervalSeconds.coerceAtLeast(60) * 1_000L)
                controlViewModel.pollConfiguredRooms()
            }
        }
    }

    val settingsVisible = showSettings
    val activeListState = if (settingsVisible) settingsListState else homeListState
    val headerBehindStatusBar by remember(activeListState) {
        derivedStateOf {
            activeListState.firstVisibleItemIndex == 0 &&
                activeListState.firstVisibleItemScrollOffset < 110
        }
    }
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(activity.window, view).apply {
            // The home header is navy, while settings uses the normal surface. Keep the
            // status-bar icons legible for the currently visible page and scroll position.
            isAppearanceLightStatusBars = if (settingsVisible) !darkTheme else !headerBehindStatusBar && !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    if (showOnboarding) {
        OnboardingFlow(
            onFinish = {
                onboardingStore.markCompleted()
                showOnboarding = false
            },
        )
        return
    }

    BackHandler(settingsVisible) { showSettings = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AnimatedContent(
            targetState = settingsVisible,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState) {
                    fadeIn(tween(230)) togetherWith fadeOut(tween(170))
                } else {
                    fadeIn(tween(230)) togetherWith fadeOut(tween(170))
                }
            },
            label = "home_settings_transition",
        ) { showingSettings ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .imePadding(),
                state = if (showingSettings) settingsListState else homeListState,
                contentPadding = PaddingValues(bottom = 118.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            if (showingSettings) {
            item(key = "settings_header") { SettingsHeader() }
            item(key = "settings_network") { PageSection { NetworkStatus(setup.hasInternet) } }
            item(key = "settings_rooms") {
                PageSection {
                    RoomConfigurationCard(
                        state = setup,
                        onSelectSlot = setupViewModel::selectSetupSlot,
                        onQrChanged = setupViewModel::setQrValue,
                        onScan = launchScanner,
                        onPaste = { readClipboardText(context)?.let(setupViewModel::acceptIncomingQr) },
                        onResolve = setupViewModel::resolveDevice,
                        onRemove = setupViewModel::removeRoom,
                    )
                }
            }
            item(key = "settings_watch") {
                PageSection { WatchSyncCard(setup) { setupViewModel.provisionWatch() } }
            }
            item(key = "settings_polling") {
                PageSection {
                    PollingSettingsCard(
                        state = setup,
                        onEnabledChanged = setupViewModel::setPollingEnabled,
                        onIntervalChanged = setupViewModel::setPollingIntervalSeconds,
                        onBackgroundEnabledChanged = setupViewModel::setBackgroundPollingEnabled,
                        onBackgroundModeChanged = setupViewModel::setBackgroundPollingMode,
                        onBackgroundIntervalChanged = setupViewModel::setBackgroundPollingIntervalMinutes,
                        onDailyStartChanged = setupViewModel::setBackgroundDailyStartMinute,
                        onDailyEndChanged = setupViewModel::setBackgroundDailyEndMinute,
                        onOnceStartChanged = setupViewModel::setBackgroundOnceStartAtMillis,
                        onOnceEndChanged = setupViewModel::setBackgroundOnceEndAtMillis,
                        onRefreshNow = if (marketingCapture) ({}) else controlViewModel::pollConfiguredRooms,
                    )
                }
            }
            item(key = "settings_help") {
                PageSection { HelpCard(onShowGuide = { showOnboarding = true }) }
            }
            item(key = "settings_security") { PageSection { AuthLimitationCard() } }
            item(key = "settings_logout") {
                PageSection {
                    TextButton(
                        onClick = setupViewModel::logout,
                        enabled = !setup.isBusy && !visibleControl.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出登录") }
                }
            }
        } else {
            item(key = "home_header") {
                BrandHeader()
            }
            item(key = "home_network") { PageSection { NetworkStatus(setup.hasInternet) } }
            if (setup.loggedIn) {
                item(key = "home_control") {
                    PageSection {
                        PhoneControlCard(
                            state = visibleControl,
                            onSelectRoom = if (marketingCapture) ({}) else controlViewModel::selectRoom,
                            onRefresh = if (marketingCapture) ({}) else controlViewModel::refreshSelected,
                            onControl = if (marketingCapture) ({ _ -> }) else controlViewModel::controlSelected,
                        )
                    }
                }
            } else {
                item(key = "login_intro") { PageSection { IntroCard() } }
                item(key = "login_rooms") {
                    PageSection {
                        RoomConfigurationCard(
                            state = setup,
                            onSelectSlot = setupViewModel::selectSetupSlot,
                            onQrChanged = setupViewModel::setQrValue,
                            onScan = launchScanner,
                            onPaste = { readClipboardText(context)?.let(setupViewModel::acceptIncomingQr) },
                            onResolve = setupViewModel::resolveDevice,
                            onRemove = setupViewModel::removeRoom,
                        )
                    }
                }
                item(key = "login_form") {
                    PageSection {
                        LoginCard(
                            state = setup,
                            onPhoneChanged = setupViewModel::setPhone,
                            onOtpChanged = setupViewModel::setOtp,
                            onRequestOtp = setupViewModel::requestOtp,
                            onLogin = setupViewModel::login,
                        )
                    }
                }
                item(key = "login_security") { PageSection { AuthLimitationCard() } }
            }
        }

        setup.busyLabel?.let { label ->
            item(key = "setup_busy") { PageSection { BusyCard(label) } }
        }
        setup.statusMessage?.let { message ->
            item(key = "setup_status") { PageSection { MessageCard(message, false) } }
        }
        setup.errorMessage?.let { message ->
            item(key = "setup_error") { PageSection { MessageCard(message, true) } }
        }
        if (setup.loggedIn) {
            visibleControl.busyLabel?.let { label ->
                item(key = "control_busy") { PageSection { BusyCard(label) } }
            }
            visibleControl.statusMessage?.let { message ->
                item(key = "control_status") { PageSection { MessageCard(message, false) } }
            }
            visibleControl.errorMessage?.let { message ->
                item(key = "control_error") { PageSection { MessageCard(message, true) } }
            }
        }
        }
        }

        AppDock(
            settingsSelected = settingsVisible,
            onHome = { showSettings = false },
            onSettings = { showSettings = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun BrandHeader() {
    Surface(color = Navy, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NavySoft, shape = CircleShape) {
                    Image(
                        painter = painterResource(R.drawable.metaldog_brand_mark),
                        contentDescription = null,
                        modifier = Modifier.size(70.dp).padding(7.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "金属狗淋浴",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ivory,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "珍惜每一滴水",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC8CBE8),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                "设置",
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun AppDock(
    settingsSelected: Boolean,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val dockShape = RoundedCornerShape(36.dp)
    val glassBrush = Brush.linearGradient(
        colors = if (dark) {
            listOf(
                Color(0xE62A2B38),
                Color(0xD921222D),
                Color(0xE5303040),
            )
        } else {
            listOf(
                Color(0xF4FFFFFF),
                Color(0xDDEFF1F8),
                Color(0xEEFFFFFF),
            )
        },
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .height(70.dp)
            .shadow(12.dp, dockShape, clip = false)
            .clip(dockShape)
            .background(glassBrush, dockShape)
            .border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f),
                shape = dockShape,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(6.dp),
        ) {
            val gap = 6.dp
            val itemWidth = (maxWidth - gap) / 2
            val indicatorOffset by animateDpAsState(
                targetValue = if (settingsSelected) itemWidth + gap else 0.dp,
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                label = "dock_glass_indicator",
            )
            val selectedBrush = Brush.linearGradient(
                colors = if (dark) {
                    listOf(Color(0xA044465A), Color(0x66363A4E), Color(0x994A4C61))
                } else {
                    listOf(Color(0xD9FFFFFF), Color(0xB7E3E6F0), Color(0xE8FFFFFF))
                },
            )

            Canvas(Modifier.fillMaxSize()) {
                val highlight = if (dark) Color.White.copy(alpha = 0.13f) else
                    Color.White.copy(alpha = 0.72f)
                drawLine(
                    color = highlight,
                    start = androidx.compose.ui.geometry.Offset(size.width * 0.09f, 1.4.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(size.width * 0.72f, 1.4.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = if (dark) Color(0xFF7F87B9).copy(alpha = 0.08f) else
                        Color.White.copy(alpha = 0.22f),
                    radius = size.height * 0.72f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, -size.height * 0.20f),
                )
                drawCircle(
                    color = if (dark) Color(0xFF101228).copy(alpha = 0.18f) else
                        Color(0xFFBBC4DB).copy(alpha = 0.12f),
                    radius = size.height * 0.62f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.07f, size.height * 1.18f),
                )
            }

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .size(width = itemWidth, height = 58.dp)
                    .background(selectedBrush, RoundedCornerShape(29.dp))
                    .border(
                        width = 0.75.dp,
                        color = Color.White.copy(alpha = if (dark) 0.20f else 0.88f),
                        shape = RoundedCornerShape(29.dp),
                    ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                DockItem(
                    label = "主页",
                    selected = !settingsSelected,
                    icon = DockIcon.Home,
                    onClick = onHome,
                    modifier = Modifier.weight(1f),
                )
                DockItem(
                    label = "设置",
                    selected = settingsSelected,
                    icon = DockIcon.Settings,
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class DockIcon { Home, Settings }

@Composable
private fun DockItem(
    label: String,
    selected: Boolean,
    icon: DockIcon,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(29.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(Modifier.size(23.dp)) {
                val stroke = Stroke(width = 2.25.dp.toPx(), cap = StrokeCap.Round)
                when (icon) {
                    DockIcon.Home -> {
                        val roof = Path().apply {
                            moveTo(size.width * 0.15f, size.height * 0.47f)
                            lineTo(size.width * 0.50f, size.height * 0.18f)
                            lineTo(size.width * 0.85f, size.height * 0.47f)
                        }
                        drawPath(roof, color, style = stroke)
                        val house = Path().apply {
                            moveTo(size.width * 0.24f, size.height * 0.43f)
                            lineTo(size.width * 0.24f, size.height * 0.83f)
                            lineTo(size.width * 0.76f, size.height * 0.83f)
                            lineTo(size.width * 0.76f, size.height * 0.43f)
                        }
                        drawPath(house, color, style = stroke)
                    }
                    DockIcon.Settings -> {
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val holeRadius = size.minDimension * 0.12f
                        val rootRadius = size.minDimension * 0.29f
                        val tipRadius = size.minDimension * 0.43f
                        val gearStroke = 2.2.dp.toPx()
                        repeat(8) { tooth ->
                            val angle = -PI / 2.0 + tooth * PI / 4.0
                            drawLine(
                                color = color,
                                start = androidx.compose.ui.geometry.Offset(
                                    center.x + cos(angle).toFloat() * rootRadius,
                                    center.y + sin(angle).toFloat() * rootRadius,
                                ),
                                end = androidx.compose.ui.geometry.Offset(
                                    center.x + cos(angle).toFloat() * tipRadius,
                                    center.y + sin(angle).toFloat() * tipRadius,
                                ),
                                strokeWidth = gearStroke,
                                cap = StrokeCap.Round,
                            )
                        }
                        drawCircle(
                            color = color,
                            radius = rootRadius,
                            center = center,
                            style = Stroke(width = gearStroke),
                        )
                        drawCircle(
                            color = color,
                            radius = holeRadius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = color,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PageSection(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .padding(horizontal = 18.dp),
            content = content,
        )
    }
}

@Composable
private fun IntroCard() {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("开始使用", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(
                "录入两间浴室，再登录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PhoneControlCard(
    state: PhoneControlUiState,
    onSelectRoom: (Int) -> Unit,
    onRefresh: () -> Unit,
    onControl: (Boolean) -> Unit,
) {
    var pendingDesired by remember { mutableStateOf<Boolean?>(null) }
    val selected = state.rooms.firstOrNull { it.selected }
    LaunchedEffect(state.selectedSlot) { pendingDesired = null }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("手机控制", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择浴室",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.rooms.forEach { room ->
                    RoomChoice(
                        room = room,
                        enabled = !state.isBusy,
                        onClick = { onSelectRoom(room.slot) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (selected == null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        "请选择浴室1或浴室2",
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                SelectedRoomStatus(selected)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.isBusy && state.hasInternet,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("手动刷新${selected.name}") }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { pendingDesired = false },
                        enabled = !state.isBusy && selected.stateKnown && selected.isOpen,
                        modifier = Modifier.weight(1f),
                    ) { Text("关闭") }
                    Button(
                        onClick = { pendingDesired = true },
                        enabled = !state.isBusy && selected.stateKnown && !selected.isOpen,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        modifier = Modifier.weight(1f),
                    ) { Text("开启") }
                }
            }
        }
    }

    val desired = pendingDesired
    if (desired != null && selected != null) {
        AlertDialog(
            onDismissRequest = { pendingDesired = null },
            title = { Text("确认${if (desired) "开启" else "关闭"}${selected.name}") },
            text = {
                Text(
                    "只控制${selected.name}。结果不明时请先刷新。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDesired = null
                        onControl(desired)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (desired) Orange else NavySoft),
                ) { Text("确认${if (desired) "开启" else "关闭"}") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDesired = null }) { Text("取消") }
            },
        )
    }
}

private fun marketingPhoneControlState(): PhoneControlUiState {
    val now = System.currentTimeMillis()
    return PhoneControlUiState(
        loggedIn = true,
        hasInternet = true,
        rooms = listOf(
            PhoneRoomUi(
                slot = 1,
                name = "浴室1",
                configured = true,
                selected = true,
                stateKnown = true,
                uncertain = false,
                isOpen = false,
                remainingSeconds = 0,
                confirmedAtEpochMillis = now,
            ),
            PhoneRoomUi(
                slot = 2,
                name = "浴室2",
                configured = true,
                selected = false,
                stateKnown = true,
                uncertain = false,
                isOpen = true,
                remainingSeconds = 8 * 60 + 20,
                confirmedAtEpochMillis = now,
            ),
        ),
        selectedSlot = 1,
        statusMessage = "演示状态：浴室1已关闭，浴室2已开启",
    )
}

@Composable
private fun RoomChoice(room: PhoneRoomUi, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (room.selected) MaterialTheme.colorScheme.secondaryContainer else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(room.name, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !room.configured -> "未配置"
                    room.uncertain -> "结果待确认"
                    !room.stateKnown -> "点击核对"
                    room.isOpen -> "已开启"
                    else -> "已关闭"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (room.isOpen && room.stateKnown) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectedRoomStatus(room: PhoneRoomUi) {
    Surface(
        color = if (room.stateKnown && room.isOpen) MaterialTheme.colorScheme.primaryContainer else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            when {
                room.uncertain -> Text("结果不明 · 只能先刷新", color = MaterialTheme.colorScheme.error)
                !room.stateKnown -> Text("尚未取得当前状态", color = MaterialTheme.colorScheme.onSurfaceVariant)
                room.isOpen -> {
                    Text("已开启", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    CountdownText(room)
                }
                else -> Text("已关闭", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CountdownText(room: PhoneRoomUi) {
    var now by remember(room.confirmedAtEpochMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(room.confirmedAtEpochMillis, room.remainingSeconds) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = ((now - room.confirmedAtEpochMillis).coerceAtLeast(0L) / 1_000L).toInt()
    val remaining = (room.remainingSeconds - elapsed).coerceAtLeast(0)
    Text(
        "%02d:%02d".format(remaining / 60, remaining % 60),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun RoomConfigurationCard(
    state: SetupUiState,
    onSelectSlot: (Int) -> Unit,
    onQrChanged: (String) -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onResolve: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("浴室配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择编号，扫描对应二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.rooms.forEach { room ->
                    FilterChip(
                        selected = state.selectedSetupSlot == room.slot,
                        onClick = { onSelectSlot(room.slot) },
                        enabled = !state.isBusy,
                        label = { Text("${room.label} · ${if (room.configured) "已配置" else "未配置"}") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.qrValue,
                onValueChange = onQrChanged,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
                label = { Text("${"浴室${state.selectedSetupSlot}"}二维码链接") },
                placeholder = { Text("http(s)://…/设备别名") },
                minLines = 2,
                maxLines = 3,
            )
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScan, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                    Text("相机扫描")
                }
                OutlinedButton(onClick = onPaste, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                    Text("粘贴链接")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onResolve,
                enabled = !state.isBusy && state.hasInternet && state.qrValue.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("识别并保存为浴室${state.selectedSetupSlot}") }
            val selectedRoom = state.rooms.first { it.slot == state.selectedSetupSlot }
            if (selectedRoom.configured && state.rooms.count(RoomSetupUi::configured) > 1) {
                TextButton(
                    onClick = { onRemove(selectedRoom.slot) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("移除${selectedRoom.label}") }
            }
            state.candidateDeviceName?.let { name ->
                Text(
                    "官方识别名称：$name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LoginCard(
    state: SetupUiState,
    onPhoneChanged: (String) -> Unit,
    onOtpChanged: (String) -> Unit,
    onRequestOtp: () -> Unit,
    onLogin: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("官方短信登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.phone,
                onValueChange = onPhoneChanged,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy && state.rooms.any(RoomSetupUi::configured),
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.otp,
                    onValueChange = onOtpChanged,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isBusy && state.rooms.any(RoomSetupUi::configured),
                    label = { Text("6 位验证码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedButton(
                    onClick = onRequestOtp,
                    enabled = !state.isBusy && state.hasInternet && state.phone.length == 11 &&
                        state.otpCooldownSeconds == 0 && state.rooms.any(RoomSetupUi::configured),
                ) {
                    Text(if (state.otpCooldownSeconds > 0) "${state.otpCooldownSeconds}s" else "发送验证码")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "验证码不会保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onLogin,
                enabled = !state.isBusy && state.hasInternet && state.phone.length == 11 &&
                    state.otp.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("登录并安全保存") }
        }
    }
}

@Composable
private fun WatchSyncCard(state: SetupUiState, onProvision: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                if (state.watchBound) "Wear OS 手表已同步" else "同步到 Wear OS 手表",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "同步浴室和登录状态到手表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onProvision,
                enabled = !state.isBusy && state.rooms.any(RoomSetupUi::configured),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.watchBound) "重新同步两间浴室" else "安全同步给手表") }
        }
    }
}

@Composable
private fun NetworkStatus(hasInternet: Boolean) {
    val foreground = if (hasInternet) Color(0xFF2E7D4F) else MaterialTheme.colorScheme.error
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(13.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(foreground, CircleShape))
            Spacer(Modifier.size(9.dp))
            Text(
                if (hasInternet) "手机网络可用" else "手机当前未发现可用网络",
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun BusyCard(label: String) {
    Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(label)
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else
            MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(13.dp),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun HelpCard(onShowGuide: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text("使用帮助", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "查看扫码、登录、手表同步和定时刷新步骤。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onShowGuide, modifier = Modifier.fillMaxWidth()) {
                Text("重新查看新手指引")
            }
        }
    }
}

@Composable
private fun AuthLimitationCard() {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text("认证与安全说明", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(
                "登录信息由系统加密保存。失效后需重新验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))
            HorizontalDivider()
            Spacer(Modifier.height(9.dp))
            Text(
                "定时刷新只查询状态，不会自动开关。结果不明时不要重复点击。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (!clipboard.hasPrimaryClip()) return null
    val description = clipboard.primaryClipDescription ?: return null
    if (
        !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
        !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    ) return null
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}
