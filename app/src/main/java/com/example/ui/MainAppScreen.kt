package com.example.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.FtpConnection
import com.example.data.model.FtpFileItem
import com.example.data.model.Playlist
import com.example.data.model.PlaylistItem
import com.example.data.repository.AudioPlayerManager
import com.example.data.repository.PlaybackRepeatMode
import com.example.ui.theme.CyberDark
import com.example.ui.theme.CyberGrey
import kotlinx.coroutines.CoroutineScope
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.CyberTeal
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.*
import com.example.ui.theme.CyberCharcoal
import com.example.ui.theme.CyberLight
import com.example.ui.theme.SoftTeal
import com.example.ui.theme.WarningHotPink
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri

sealed class Screen(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Browser : Screen("Explorar", Icons.Default.FolderOpen)
    object Radios : Screen("Radios", Icons.Default.Radio)
    object Multimedia : Screen("Multimedia", Icons.Default.MusicNote)
    object Player : Screen("Reproductor", Icons.Default.PlayCircleFilled)
    object Settings : Screen("Ajustes", Icons.Default.Settings)
}

@Composable
fun PozoBrandLogo(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Color(0xFF1E222D), shape = RoundedCornerShape(8.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Outer cyan circle (vinyl edge glow)
                drawCircle(
                    color = Color(0xFF00ADB5),
                    radius = size.width / 2.3f,
                    style = Stroke(width = 2.dp.toPx())
                )
                // Inner dark vinyl body
                drawCircle(
                    color = Color(0xFF11141A),
                    radius = size.width / 2.6f
                )
                // Vinyl grooves (subtle lines)
                drawCircle(
                    color = Color(0xFF393E46),
                    radius = size.width / 3.8f,
                    style = Stroke(width = 1.dp.toPx())
                )
                // Center pink hub
                drawCircle(
                    color = Color(0xFFE23E57),
                    radius = size.width / 6.5f
                )
            }
            // Tiny Music Note Icon on top
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Column {
            Text(
                text = "POZO",
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF00ADB5),
                letterSpacing = 1.sp,
                lineHeight = 14.sp
            )
            Text(
                text = "MEDIA HUB",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.LightGray,
                letterSpacing = 1.2.sp,
                lineHeight = 8.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: FtpViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf<Screen>(Screen.Multimedia) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    BackHandler {
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val postNotificationLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { _ -> }
        
        LaunchedEffect(Unit) {
            val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                postNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var showResumeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!AudioPlayerManager.hasPromptedResume) {
            val track = AudioPlayerManager.currentTrack.value
            if (track != null) {
                showResumeDialog = true
            }
            AudioPlayerManager.hasPromptedResume = true
        }
    }

    if (showResumeDialog) {
        val track = AudioPlayerManager.currentTrack.value
        if (track != null) {
            AlertDialog(
                onDismissRequest = { showResumeDialog = false },
                title = { Text("¿Continuar última reproducción?", color = Color.White) },
                text = { Text("¿Quieres continuar escuchando \"${track.fileName}\" desde donde te quedaste?", color = Color.LightGray) },
                confirmButton = {
                    Button(
                        onClick = {
                            showResumeDialog = false
                            val savedPosition = context.getSharedPreferences("ftp_hub_playback", android.content.Context.MODE_PRIVATE)
                                .getLong("track_position", 0L)
                            AudioPlayerManager.playTrack(context, track, initialPositionMs = savedPosition)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberTeal)
                    ) {
                        Text("SÍ", color = CyberDark)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResumeDialog = false }) {
                        Text("NO", color = Color.Gray)
                    }
                },
                containerColor = CyberSurface
            )
        }
    }

    // Collect App / Playback states
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
    val isViewingImage by viewModel.isViewingImage.collectAsStateWithLifecycle()
    val viewingImageLocalFile by viewModel.viewingImageLocalFile.collectAsStateWithLifecycle()
    val isImageLoading by viewModel.imageLoadingState.collectAsStateWithLifecycle()

    val currentTrack by AudioPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by AudioPlayerManager.isPlaying.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = CyberDark,
                drawerContentColor = Color.White,
                modifier = Modifier.width(320.dp).fillMaxHeight()
            ) {
                DrawerControlPanelContent(viewModel, scope, drawerState)
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("menu_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menú de Audio y Volumen",
                                tint = CyberTeal
                            )
                        }
                    },
                    title = {
                        PozoBrandLogo()
                    },
                    actions = {
                        if (isConnected) {
                            IconButton(
                                onClick = { viewModel.disconnectFtp() },
                                modifier = Modifier.testTag("disconnect_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Desconectar",
                                    tint = WarningHotPink
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CyberDark,
                        titleContentColor = Color.White
                    )
                )
            },
        bottomBar = {
            NavigationBar(
                containerColor = CyberDark,
                windowInsets = WindowInsets.navigationBars
            ) {
                val screens = listOf(Screen.Radios, Screen.Multimedia, Screen.Player, Screen.Settings)
                screens.forEach { screen ->
                    val selected = currentTab == screen
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = screen },
                        icon = {
                            BadgedBox(badge = {
                                if (screen == Screen.Player && currentTrack != null && isPlaying) {
                                    Badge(containerColor = CyberTeal) {
                                        Text("LIVE", color = CyberDark, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                    tint = if (selected) CyberTeal else Color.Gray
                                )
                            }
                        },
                        label = {
                            Text(
                                text = screen.title,
                                color = if (selected) CyberTeal else Color.Gray,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = CyberGrey
                        ),
                        modifier = Modifier.testTag("tab_${screen.title.lowercase()}")
                    )
                }
            }
        },
        containerColor = CyberDark
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(CyberDark, CyberSurface)
                    )
                )
        ) {
            // Screen switching with slide animations
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "ScreenTransition"
            ) { tab ->
                when (tab) {
                    is Screen.Browser -> BrowserTabSchema(viewModel, onNavigateToPlayer = { currentTab = Screen.Player })
                    is Screen.Radios -> RadiosOnlineDetailsScreen(viewModel)
                    is Screen.Multimedia -> MultimediaTabSchema(viewModel, onNavigateToPlayer = { currentTab = Screen.Player })
                    is Screen.Player -> PlayerTabSchema(viewModel, onNavigateToRecent = {
                        viewModel.setMultimediaSubTab(3) // 3 = RECIENTES
                        currentTab = Screen.Multimedia
                    })
                    is Screen.Settings -> AppSettingsAndEqualizerScreen(viewModel, onNavigateToPlayer = { currentTab = Screen.Player })
                }
            }

            // Floating Mini-Player Bar removed per user request to allow uninterrupted browsing inside the app. Player remains in its section and in the notification bar.

            // High Contrast Immersive Full-Screen Photo Viewer Overlay
            if (isViewingImage) {
                FullScreenPhotoViewer(
                    localFile = viewingImageLocalFile,
                    isLoading = isImageLoading,
                    onDismiss = { viewModel.closeImageViewer() }
                )
            }
        }
    }
}
}

// ---------------------- TAB 1: BROWSER VIEW ----------------------
@Composable
fun BrowserTabSchema(viewModel: FtpViewModel, onNavigateToPlayer: () -> Unit) {
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()

    AnimatedContent(targetState = isConnected, label = "ConnectedTransition") { connected ->
        if (!connected) {
            FtpProfilesPanel(viewModel)
        } else {
            FtpFileExplorerPanel(viewModel, onNavigateToPlayer)
        }
    }
}

@Composable
fun FtpProfilesPanel(viewModel: FtpViewModel) {
    val connections by viewModel.savedConnections.collectAsStateWithLifecycle()
    val isConnecting by viewModel.isConnecting.collectAsStateWithLifecycle()
    val connError by viewModel.connectionError.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var connectionToEdit by remember { mutableStateOf<FtpConnection?>(null) }
    var connectionToDelete by remember { mutableStateOf<FtpConnection?>(null) }

    if (connectionToDelete != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { connectionToDelete = null },
            title = {
                Text(
                    text = "Confirmar eliminación",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "¿Estás seguro de que deseas eliminar el servidor \"${connectionToDelete?.name}\"?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        connectionToDelete?.let { conn ->
                            viewModel.removeConnection(conn)
                            Toast.makeText(context, "Servidor eliminado", Toast.LENGTH_SHORT).show()
                        }
                        connectionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Eliminar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { connectionToDelete = null }
                ) {
                    Text("Cancelar", color = CyberTeal, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CyberDark,
            textContentColor = Color.White,
            titleContentColor = Color.White
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Servidores FTP de Almacenamiento",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberTeal
                )
                Text(
                    text = "Configura y conéctate a tus cuentas FTP remotas para visualizar fotos y transmitir música sin consumir almacenamiento local.",
                    fontSize = 13.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }

            if (connError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = WarningHotPink.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = WarningHotPink)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = connError ?: "", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (connections.isEmpty() && !isConnecting) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = CyberGrey,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No hay ningún servidor configurado",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Haz clic en el botón '+' para añadir tu servidor.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                }
            } else {
                items(connections, key = { it.id }) { conn ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberSurface),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { if (!isConnecting) viewModel.connectFtp(conn) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connection_card_${conn.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(CyberGrey, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = CyberTeal
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = conn.name,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "${conn.host}:${conn.port}",
                                        color = Color.Gray,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Usuario: ${conn.username}",
                                        color = CyberTeal.copy(alpha = 0.8f),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.duplicateConnection(conn) },
                                    modifier = Modifier.testTag("duplicate_connection_${conn.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Duplicar Conexión",
                                        tint = CyberTeal
                                    )
                                }
                                IconButton(
                                    onClick = { connectionToEdit = conn },
                                    modifier = Modifier.testTag("edit_connection_${conn.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Editar Conexión",
                                        tint = CyberTeal
                                    )
                                }
                                IconButton(
                                    onClick = { connectionToDelete = conn },
                                    modifier = Modifier.testTag("delete_connection_${conn.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Borrar Conexión",
                                        tint = WarningHotPink
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        if (isConnecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = CyberTeal)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Estableciendo conexión remota...",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Autenticando sockets FTP",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Add Connection Floating Button
        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = CyberTeal,
            contentColor = CyberDark,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("add_connection_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Añadir FTP")
        }

        if (showAddDialog) {
            AddConnectionDialog(
                onSave = { name, host, port, user, pass, musicPath ->
                    viewModel.createConnection(name, host, port, user, pass, musicPath)
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false }
            )
        }

        if (connectionToEdit != null) {
            AddConnectionDialog(
                connection = connectionToEdit,
                onSave = { name, host, port, user, pass, musicPath ->
                    val updated = connectionToEdit!!.copy(
                        name = name,
                        host = host,
                        port = port,
                        username = user,
                        password = pass,
                        musicFolder = musicPath
                    )
                    viewModel.updateConnection(updated)
                    connectionToEdit = null
                },
                onDismiss = { connectionToEdit = null }
            )
        }
    }
}

@Composable
fun AddConnectionDialog(
    connection: FtpConnection? = null,
    onSave: (String, String, Int, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(connection?.name ?: "") }
    var host by remember { mutableStateOf(connection?.host ?: "") }
    var port by remember { mutableStateOf(connection?.port?.toString() ?: "21") }
    var username by remember { mutableStateOf(connection?.username ?: "anonymous") }
    var password by remember { mutableStateOf(connection?.password ?: "") }
    var musicFolder by remember { mutableStateOf(connection?.musicFolder ?: "/") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Añadir Nuevo Servidor FTP",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberTeal
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del Servidor (ej. Mi NAS)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        focusedLabelColor = CyberTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connection_name")
                )

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Servidor IP / Hostname") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        focusedLabelColor = CyberTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connection_host")
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Puerto FTP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        focusedLabelColor = CyberTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connection_port")
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Nombre de Usuario") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        focusedLabelColor = CyberTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connection_username")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        focusedLabelColor = CyberTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connection_password")
                )

                OutlinedTextField(
                    value = musicFolder,
                    onValueChange = { musicFolder = it },
                    label = { Text("Ruta Carpeta de Música (ej: /Música)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        focusedLabelColor = CyberTeal
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connection_music_folder")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCELAR", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isNotEmpty() && host.isNotEmpty()) {
                                val p = port.toIntOrNull() ?: 21
                                onSave(name, host, p, username, password, musicFolder)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                        modifier = Modifier.testTag("submit_connection_button")
                    ) {
                        Text("GUARDAR Y CONECTAR")
                    }
                }
            }
        }
    }
}

@Composable
fun FtpFileExplorerPanel(viewModel: FtpViewModel, onNavigateToPlayer: () -> Unit) {
    val context = LocalContext.current
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val filesList by viewModel.filesList.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingFiles.collectAsStateWithLifecycle()
    val activeConn by viewModel.activeConnection.collectAsStateWithLifecycle()
    val clipboardPath by viewModel.clipboardPath.collectAsStateWithLifecycle()
    val isMoveAction by viewModel.isMoveAction.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var showCreateDirDialog by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<FtpFileItem?>(null) }
    var itemToPlaylist by remember { mutableStateOf<FtpFileItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Active Connection Header Title
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface.copy(alpha = 0.5f)),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = activeConn?.name ?: "Servidor FTP",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = activeConn?.host ?: "",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    activeConn?.let { conn ->
                        if (conn.musicFolder.isNotBlank()) {
                            IconButton(
                                onClick = { viewModel.navigateToPath(conn.musicFolder) },
                                modifier = Modifier.testTag("direct_music_folder_shortcut")
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = "Carpeta de Música", tint = SoftTeal)
                            }
                        }
                    }
                    IconButton(
                        onClick = { showCreateDirDialog = true },
                        modifier = Modifier.testTag("create_folder_button")
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Nueva Carpeta", tint = CyberTeal)
                    }
                    IconButton(
                        onClick = { viewModel.loadFilesFromCurrentPath() },
                        modifier = Modifier.testTag("refresh_files_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = CyberTeal)
                    }
                }
            }
        }

        // Breadcrumbs indicator
        BreadcrumbsView(currentPath) { idx ->
            viewModel.navigateBreadcrumb(idx)
        }

        // Clipboard Float Panel if elements are loaded
        if (clipboardPath != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberTeal.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isMoveAction) Icons.Default.ContentCut else Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = CyberTeal
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isMoveAction) "Listo para Mover" else "Listo para Copiar",
                                fontWeight = FontWeight.Bold,
                                color = CyberTeal,
                                fontSize = 12.sp
                            )
                            Text(
                                text = clipboardPath?.substringAfterLast("/") ?: "",
                                color = Color.White,
                                fontSize = 11.sp,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = { viewModel.pasteClipboardCurrent() }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Pegar", tint = CyberTeal)
                        }
                        IconButton(onClick = { viewModel.clearClipboard() }) {
                            Icon(Icons.Default.Cancel, contentDescription = "Cancelar", tint = WarningHotPink)
                        }
                    }
                }
            }
        }

        // File list loading
        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyberTeal)
                }
            } else if (filesList.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = CyberGrey, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Esta carpeta está vacía", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filesList) { item ->
                        FtpFileRow(
                            item = item,
                            onFolderClick = { viewModel.navigateIntoDirectory(item.name) },
                            onAudioClick = {
                                val playlistItem = PlaylistItem(
                                    playlistId = 0,
                                    fileName = item.name,
                                    filePath = item.path,
                                    fileSize = item.size
                                )
                                AudioPlayerManager.playTrack(context, playlistItem)
                                onNavigateToPlayer()
                            },
                            onImageClick = { viewModel.openImageViewer(item) },
                            onDeleteClick = { itemToDelete = item },
                            onCopyToClipboard = { viewModel.setClipboardElement(item, isCut = false) },
                            onCutToClipboard = { viewModel.setClipboardElement(item, isCut = true) },
                            onAddToPlaylistClick = { itemToPlaylist = item },
                            onDownloadClick = { viewModel.downloadFileToDevice(context, item) }
                        )
                    }
                }
            }
        }
    }

    // Modal Folder Creation Dialog
    if (showCreateDirDialog) {
        Dialog(onDismissRequest = { showCreateDirDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Nueva Carpeta", fontWeight = FontWeight.Bold, color = CyberTeal, fontSize = 16.sp)
                    OutlinedTextField(
                        value = folderNameInput,
                        onValueChange = { folderNameInput = it },
                        label = { Text("Nombre de la carpeta") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberTeal, focusedLabelColor = CyberTeal
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCreateDirDialog = false }) {
                            Text("CANCELAR", color = Color.Gray)
                        }
                        Button(
                            onClick = {
                                if (folderNameInput.isNotEmpty()) {
                                    viewModel.createFolder(folderNameInput)
                                    folderNameInput = ""
                                    showCreateDirDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark)
                        ) {
                            Text("CREAR")
                        }
                    }
                }
            }
        }
    }

    // Modal Delete Confirmation Dialog
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("¿Eliminar elemento?", color = Color.White) },
            text = { Text("Se eliminará de forma permanente '${itemToDelete?.name}' de tu servidor FTP.", color = Color.LightGray) },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("CANCELAR", color = Color.Gray)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        itemToDelete?.let { viewModel.deleteFileItem(it) }
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink)
                ) {
                    Text("BORRAR")
                }
            },
            containerColor = CyberSurface
        )
    }

    // Modal Add File to Playlist Dialog
    if (itemToPlaylist != null && playlists.isNotEmpty()) {
        Dialog(onDismissRequest = { itemToPlaylist = null }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Selecciona una lista de reproducción", fontWeight = FontWeight.Bold, color = CyberTeal, fontSize = 16.sp)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp)
                    ) {
                        items(playlists) { list ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        itemToPlaylist?.let {
                                            viewModel.addFileToPlaylist(list.id, it)
                                            Toast
                                                .makeText(context, "Añadido a ${list.name}", Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                        itemToPlaylist = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = SoftTeal)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(list.name, color = Color.White, fontSize = 14.sp)
                            }
                            HorizontalDivider(color = CyberGrey)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { itemToPlaylist = null }) {
                            Text("CERRAR", color = Color.Gray)
                        }
                    }
                }
            }
        }
    } else if (itemToPlaylist != null && playlists.isEmpty()) {
        AlertDialog(
            onDismissRequest = { itemToPlaylist = null },
            title = { Text("No hay Playlists creadas", color = Color.White) },
            text = { Text("Por favor dirígete a la sección 'Multimedia' para crear tu primera lista de reproducción antes de añadir pistas.", color = Color.LightGray) },
            confirmButton = {
                Button(onClick = { itemToPlaylist = null }, colors = ButtonDefaults.buttonColors(containerColor = CyberTeal)) {
                    Text("ENTENDIDO", color = CyberDark)
                }
            },
            containerColor = CyberSurface
        )
    }
}

@Composable
fun BreadcrumbsView(path: String, onBreadcrumbClick: (Int) -> Unit) {
    val segments = path.split("/").filter { it.isNotEmpty() }
    
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberGrey.copy(alpha = 0.5f))
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            Text(
                text = "Raíz",
                color = if (segments.isEmpty()) CyberTeal else Color.Gray,
                fontWeight = if (segments.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onBreadcrumbClick(-1) }
                    .padding(end = 4.dp)
                    .testTag("breadcrumb_root")
            )
        }

        itemsIndexed(segments) { index, segment ->
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            val isCurrent = index == segments.size - 1
            Text(
                text = segment,
                color = if (isCurrent) CyberTeal else Color.Gray,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { onBreadcrumbClick(index) }
                    .padding(end = 4.dp)
                    .testTag("breadcrumb_segment_$index")
            )
        }
    }
}

@Composable
fun FtpFileRow(
    item: FtpFileItem,
    onFolderClick: () -> Unit,
    onAudioClick: () -> Unit,
    onImageClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCopyToClipboard: () -> Unit,
    onCutToClipboard: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val icon = when {
        item.isDirectory -> Icons.Default.Folder
        item.isAudio -> Icons.Default.MusicNote
        item.isImage -> Icons.Default.Image
        item.isVideo -> Icons.Default.Movie
        else -> Icons.Default.InsertDriveFile
    }

    val iconColor = when {
        item.isDirectory -> CyberTeal
        item.isAudio -> SoftTeal
        item.isImage -> WarningHotPink
        item.isVideo -> Color.Magenta
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    item.isDirectory -> onFolderClick()
                    item.isAudio -> onAudioClick()
                    item.isImage -> onImageClick()
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.isDirectory) "Carpeta" else formatFileSize(item.size),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        Box {
            IconButton(onClick = { isExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.Gray)
            }
            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { isExpanded = false },
                modifier = Modifier.background(CyberSurface)
            ) {
                DropdownMenuItem(
                    text = { Text("Copiar", color = Color.White) },
                    onClick = {
                        isExpanded = false
                        onCopyToClipboard()
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CyberTeal) }
                )
                DropdownMenuItem(
                    text = { Text("Mover (Cortar)", color = Color.White) },
                    onClick = {
                        isExpanded = false
                        onCutToClipboard()
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, tint = CyberTeal) }
                )
                if (!item.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Descargar al móvil", color = Color.White) },
                        onClick = {
                            isExpanded = false
                            onDownloadClick()
                        },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = CyberTeal) }
                    )
                }
                if (item.isAudio) {
                    DropdownMenuItem(
                        text = { Text("Añadir a Playlist...", color = Color.White) },
                        onClick = {
                            isExpanded = false
                            onAddToPlaylistClick()
                        },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = SoftTeal) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Borrar", color = WarningHotPink) },
                    onClick = {
                        isExpanded = false
                        onDeleteClick()
                    },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = WarningHotPink) }
                )
            }
        }
    }
    HorizontalDivider(color = CyberGrey.copy(alpha = 0.3f), thickness = 0.5.dp)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1) {
        String.format("%.2f MB", mb)
    } else {
        String.format("%.1f KB", kb)
    }
}

// ---------------------- TAB 2: MULTIMEDIA PANEL (LOCAL & FTP LIBRARIES, FAVS, RECENT, PLAYLISTS) ----------------------
@Composable
fun MultimediaTabSchema(viewModel: FtpViewModel, onNavigateToPlayer: () -> Unit) {
    val activeSubTab by viewModel.multimediaSubTab.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = activeSubTab,
            containerColor = CyberDark,
            contentColor = CyberTeal,
            edgePadding = 16.dp
        ) {
            Tab(
                selected = activeSubTab == 0,
                onClick = { viewModel.setMultimediaSubTab(0) },
                text = { Text("LOCAL", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
            Tab(
                selected = activeSubTab == 1,
                onClick = { viewModel.setMultimediaSubTab(1) },
                text = { Text("FTP", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
            Tab(
                selected = activeSubTab == 2,
                onClick = { viewModel.setMultimediaSubTab(2) },
                text = { Text("FAVORITOS", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
            Tab(
                selected = activeSubTab == 3,
                onClick = { viewModel.setMultimediaSubTab(3) },
                text = { Text("RECIENTES", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
            Tab(
                selected = activeSubTab == 4,
                onClick = { viewModel.setMultimediaSubTab(4) },
                text = { Text("MIS PLAYLISTS", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
            Tab(
                selected = activeSubTab == 5,
                onClick = { viewModel.setMultimediaSubTab(5) },
                text = { Text("GRABACIONES", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (activeSubTab) {
                0 -> LocalLibraryDetailsScreen(viewModel, showOnlyFtp = false)
                1 -> LocalLibraryDetailsScreen(viewModel, showOnlyFtp = true)
                2 -> FavoritesDetailsScreen(viewModel)
                3 -> RecentTracksDetailsScreen(viewModel)
                4 -> PlaylistsDetailsScreen(viewModel)
                5 -> RecordingsLibraryDetailsScreen(viewModel, onNavigateToPlayer)
            }
        }
    }
}

@Composable
fun RecordingsLibraryDetailsScreen(viewModel: FtpViewModel, onNavigateToPlayer: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("app_buffer_configs", android.content.Context.MODE_PRIVATE) }
    var recordingPath by remember { mutableStateOf(prefs.getString("recording_destination_dir", "") ?: "") }
    
    val targetDir = if (recordingPath.isNotEmpty()) {
        java.io.File(recordingPath)
    } else {
        java.io.File(context.filesDir, "grabaciones")
    }
    
    var recordedFilesList by remember {
        mutableStateOf(
            if (targetDir.exists() && targetDir.isDirectory) {
                targetDir.listFiles { _, name -> name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() } ?: emptyList()
            } else emptyList()
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    
    val filteredFiles = recordedFilesList.filter {
        it.name.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = CyberTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Directorio de Grabación Activo:",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = targetDir.absolutePath,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar grabaciones...", color = Color.Gray, fontSize = 11.sp) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CyberSurface,
                unfocusedContainerColor = CyberSurface,
                focusedIndicatorColor = CyberTeal,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = CyberCharcoal,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No se encontraron grabaciones coincidentes" else "No hay grabaciones de emisoras de radio",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredFiles) { file ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberSurface),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${String.format("%.2f", file.length() / (1024f * 1024f))} MB",
                                        color = CyberTeal,
                                        fontSize = 10.sp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Guardado localmente",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val playItem = com.example.data.model.PlaylistItem(
                                            id = 0,
                                            playlistId = -3,
                                            fileName = file.name,
                                            filePath = file.absolutePath,
                                            fileSize = file.length(),
                                            durationText = "Podcast Rec"
                                        )
                                        AudioPlayerManager.playTrack(context, playItem)
                                        onNavigateToPlayer()
                                        android.widget.Toast.makeText(context, "Reproduciendo grabación: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Reproducir",
                                        tint = CyberTeal,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(6.dp))
                                
                                IconButton(
                                    onClick = {
                                        try {
                                            if (file.delete()) {
                                                recordedFilesList = if (targetDir.exists() && targetDir.isDirectory) {
                                                    targetDir.listFiles { _, name -> name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() } ?: emptyList()
                                                } else {
                                                    emptyList()
                                                }
                                                android.widget.Toast.makeText(context, "Archivo eliminado", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(context, "Error al borrar archivo", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Eliminar",
                                        tint = WarningHotPink,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppSettingsAndEqualizerScreen(viewModel: FtpViewModel, onNavigateToPlayer: () -> Unit) {
    var activeSubTab by remember { mutableStateOf(1) } // Default to showing Settings tab (1), offering EQ as sibling tab (0)

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = activeSubTab,
            containerColor = CyberDark,
            contentColor = CyberTeal
        ) {
            Tab(
                selected = activeSubTab == 0,
                onClick = { activeSubTab = 0 },
                text = { Text("ECUALIZADOR 🎛️", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
            Tab(
                selected = activeSubTab == 1,
                onClick = { activeSubTab = 1 },
                text = { Text("AJUSTES ⚙️", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (activeSubTab) {
                0 -> EqualizerDetailsScreen()
                1 -> AppSettingsDetailsScreen(viewModel, onNavigateToPlayer = onNavigateToPlayer)
            }
        }
    }
}

@Composable
fun RecentTracksDetailsScreen(viewModel: FtpViewModel) {
    val context = LocalContext.current
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Últimas 10 Pistas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
                    Text("Historial reciente de pistas reproducidas localmente o por FTP.", fontSize = 12.sp, color = Color.Gray)
                }
                if (recentTracks.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearRecentTracks() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Limpiar todo",
                            tint = WarningHotPink,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        if (recentTracks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = CyberGrey,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Historial vacío", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Las pistas que reproduzcas aparecerán aquí.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        } else {
            items(recentTracks) { recent ->
                val isLocal = recent.playlistId == -1
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val playlistItem = PlaylistItem(
                            id = 0,
                            playlistId = recent.playlistId,
                            fileName = recent.fileName,
                            filePath = recent.filePath,
                            fileSize = recent.fileSize,
                            durationText = recent.durationText
                        )
                        AudioPlayerManager.playTrack(context, playlistItem)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isLocal) Icons.Default.Phonelink else Icons.Default.Cloud,
                                contentDescription = null,
                                tint = CyberTeal,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = recent.fileName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isLocal) "Móvil Local • Historial" else "Servidor Remoto • Historial",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Reproducir de nuevo",
                            tint = CyberTeal,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

fun getPathFromDocumentTreeUri(rawUri: Uri): String? {
    try {
        val decodedUri = Uri.decode(rawUri.toString())
        if (decodedUri.contains("/tree/")) {
            val partAfterTree = decodedUri.substringAfter("/tree/")
            val split = partAfterTree.split(":")
            if (split.size > 1) {
                val storageId = split[0]
                val relativePath = split[1]
                return if (storageId == "primary") {
                    "/storage/emulated/0/$relativePath"
                } else {
                    "/storage/$storageId/$relativePath"
                }
            } else {
                if (partAfterTree.startsWith("primary")) {
                    return "/storage/emulated/0"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun getParentFolderName(path: String): String {
    return try {
        if (path.startsWith("content://")) {
            "Descargas / Sistema"
        } else if (path.startsWith("ftp://")) {
            val cleanPath = path.substring(6)
            val parts = cleanPath.split("/").filter { it.isNotBlank() }
            if (parts.size >= 2) {
                "[FTP] ${parts[parts.size - 2]}"
            } else {
                "[FTP] Raíz"
            }
        } else {
            val file = java.io.File(path)
            val parentName = file.parentFile?.name
            if (parentName.isNullOrBlank() || parentName == "/") "Descargas" else parentName
        }
    } catch (e: Exception) {
        "Otros"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLibraryDetailsScreen(viewModel: FtpViewModel, showOnlyFtp: Boolean = false) {
    val context = LocalContext.current
    val scannedTracksAll by viewModel.scannedLocalTracks.collectAsStateWithLifecycle()
    val scannedTracks = remember(scannedTracksAll, showOnlyFtp) {
        if (showOnlyFtp) {
            scannedTracksAll.filter { it.filePath.startsWith("ftp://") }
        } else {
            scannedTracksAll.filter { !it.filePath.startsWith("ftp://") }
        }
    }
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanFolders by viewModel.scanFolders.collectAsStateWithLifecycle()
    val savedConnections by viewModel.savedConnections.collectAsStateWithLifecycle()

    val requiredPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.scanAllFolders(context)
        } else {
            Toast.makeText(context, "Se requiere permiso de lectura para escanear música.", Toast.LENGTH_LONG).show()
        }
    }

    val activeSubTab = "library"
    var selectedFilter by remember { mutableStateOf("Carpeta") } // "Carpeta", "Todas", "Artista", "Género", "Año"
    val filters = listOf("Carpeta", "Todas", "Artista", "Género", "Año")
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Grabaciones & Buffer Settings Storage
    val prefs = remember { context.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE) }
    var bufferLimitMins by remember { mutableStateOf(prefs.getInt("radio_buffer_limit_minutes", 120)) }
    var recordingPath by remember { mutableStateOf(prefs.getString("recording_destination_dir", "") ?: "") }

    val recFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolvedPath = getPathFromDocumentTreeUri(uri)
            if (resolvedPath != null) {
                recordingPath = resolvedPath
                prefs.edit().putString("recording_destination_dir", resolvedPath).apply()
                Toast.makeText(context, "Destino de grabaciones: $resolvedPath", Toast.LENGTH_LONG).show()
            } else {
                val uriStr = uri.toString()
                recordingPath = uriStr
                prefs.edit().putString("recording_destination_dir", uriStr).apply()
                Toast.makeText(context, "URI guardado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filteredTracks = scannedTracks.filter {
        it.title.contains(searchQuery, ignoreCase = true) ||
        it.artist.contains(searchQuery, ignoreCase = true) ||
        it.album.contains(searchQuery, ignoreCase = true) ||
        it.filePath.contains(searchQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header for Library
        item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (showOnlyFtp) "Biblioteca FTP" else "Biblioteca Local", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
                        Text("${scannedTracks.size} pistas disponibles", fontSize = 12.sp, color = Color.Gray)
                    }

                    if (isScanning) {
                        CircularProgressIndicator(color = CyberTeal, modifier = Modifier.size(24.dp))
                    } else {
                        Button(
                            onClick = {
                                val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    requiredPermission
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                
                                if (isGranted) {
                                    viewModel.scanAllFolders(context)
                                } else {
                                    permissionLauncher.launch(requiredPermission)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.YoutubeSearchedFor, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberDark)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Escanear", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Search input field
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar por título, artista o álbum...", color = Color.Gray, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyberTeal) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = Color.Gray)
                            }
                        }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        unfocusedBorderColor = CyberCharcoal,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            // Filters selector
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = {
                                selectedFilter = filter
                                expandedGroup = null
                            },
                            label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CyberCharcoal,
                                labelColor = Color.LightGray,
                                selectedContainerColor = CyberTeal,
                                selectedLabelColor = CyberDark
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = Color.Transparent,
                                selectedBorderColor = Color.Transparent,
                                enabled = true,
                                selected = selectedFilter == filter
                            )
                        )
                    }
                }
            }

            if (filteredTracks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.MusicVideo, contentDescription = null, tint = CyberGrey, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(if (searchQuery.isNotEmpty()) "Ningún resultado" else "No hay canciones escaneadas", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(if (searchQuery.isNotEmpty()) "Prueba con otra búsqueda." else if (showOnlyFtp) "Ve al apartado de Ajustes para vincular y sincronizar pistas desde tu conexión FTP." else "Ve a la pestaña de Ajustes para escanear directorios de tu almacenamiento local.", color = Color.Gray, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                when (selectedFilter) {
                    "Carpeta" -> {
                        val grouped = filteredTracks.groupBy { track ->
                            getParentFolderName(track.filePath)
                        }
                        grouped.forEach { (folderName, tracksInFolder) ->
                            val isFolderExpanded = expandedGroup == folderName
                            val firstTrack = tracksInFolder.firstOrNull()

                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = CyberCharcoal),
                                    shape = RoundedCornerShape(10.dp),
                                    onClick = {
                                        expandedGroup = if (isFolderExpanded) null else folderName
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            // Displays the album artwork of the first track as compiling folder artwork automatically!
                                            TrackCoverImage(
                                                filePath = firstTrack?.filePath,
                                                modifier = Modifier.size(48.dp),
                                                backgroundColor = CyberGrey,
                                                placeholderIcon = Icons.Default.Folder,
                                                iconSize = 24.dp
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = folderName,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${tracksInFolder.size} pistas",
                                                    color = CyberTeal,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        Icon(
                                            imageVector = if (isFolderExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = Color.Gray
                                        )
                                    }
                                }
                            }

                            if (isFolderExpanded) {
                                items(tracksInFolder) { track ->
                                    Row(modifier = Modifier.padding(start = 12.dp)) {
                                        LocalTrackCard(track = track, context = context, viewModel = viewModel, allTracksInView = tracksInFolder)
                                    }
                                }
                            }
                        }
                    }
                    "Todas" -> {
                        items(filteredTracks) { track ->
                            LocalTrackCard(track = track, context = context, viewModel = viewModel, allTracksInView = filteredTracks)
                        }
                    }
                    "Artista" -> {
                        val grouped = filteredTracks.groupBy { it.artist }
                        grouped.forEach { (artist, tracksForArtist) ->
                            item {
                                GroupHeader(title = artist, count = tracksForArtist.size, isExpanded = expandedGroup == artist) {
                                    expandedGroup = if (expandedGroup == artist) null else artist
                                }
                            }
                            if (expandedGroup == artist) {
                                items(tracksForArtist) { track ->
                                    Row(modifier = Modifier.padding(start = 12.dp)) {
                                        LocalTrackCard(track = track, context = context, viewModel = viewModel, allTracksInView = tracksForArtist)
                                    }
                                }
                            }
                        }
                    }
                    "Género" -> {
                        val grouped = filteredTracks.groupBy { it.genre }
                        grouped.forEach { (genre, tracksForGenre) ->
                            item {
                                GroupHeader(title = genre, count = tracksForGenre.size, isExpanded = expandedGroup == genre) {
                                    expandedGroup = if (expandedGroup == genre) null else genre
                                }
                            }
                            if (expandedGroup == genre) {
                                items(tracksForGenre) { track ->
                                    Row(modifier = Modifier.padding(start = 12.dp)) {
                                        LocalTrackCard(track = track, context = context, viewModel = viewModel, allTracksInView = tracksForGenre)
                                    }
                                }
                            }
                        }
                    }
                    "Año" -> {
                        val grouped = filteredTracks.groupBy { it.year }
                        grouped.forEach { (year, tracksForYear) ->
                            item {
                                GroupHeader(title = year, count = tracksForYear.size, isExpanded = expandedGroup == year) {
                                    expandedGroup = if (expandedGroup == year) null else year
                                }
                            }
                            if (expandedGroup == year) {
                                items(tracksForYear) { track ->
                                    Row(modifier = Modifier.padding(start = 12.dp)) {
                                        LocalTrackCard(track = track, context = context, viewModel = viewModel, allTracksInView = tracksForYear)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun getCoverCacheInfo(context: android.content.Context): Pair<Long, Int> {
    return try {
        val cacheDir = context.cacheDir
        val files = cacheDir.listFiles { _, name ->
            name.startsWith("ftp_cover_") || name == "current_playing_media.mp3"
        } ?: emptyArray()
        val totalSize = files.sumOf { it.length() }
        Pair(totalSize, files.size)
    } catch (e: Exception) {
        Pair(0L, 0)
    }
}

fun clearCoverCache(context: android.content.Context): Boolean {
    return try {
        val cacheDir = context.cacheDir
        val files = cacheDir.listFiles { _, name ->
            name.startsWith("ftp_cover_") || name == "current_playing_media.mp3"
        } ?: emptyArray()
        var success = true
        for (f in files) {
            if (!f.delete()) {
                success = false
            }
        }
        success
    } catch (e: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsDetailsScreen(viewModel: FtpViewModel, onNavigateToPlayer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanFolders by viewModel.scanFolders.collectAsStateWithLifecycle()
    val savedConnections by viewModel.savedConnections.collectAsStateWithLifecycle()
    
    // Pickers and States
    val prefs = remember { context.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE) }
    var bufferLimitMins by remember { mutableStateOf(prefs.getInt("radio_buffer_limit_minutes", 120)) }
    var recordingPath by remember { mutableStateOf(prefs.getString("recording_destination_dir", "") ?: "") }

    val recFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolvedPath = getPathFromDocumentTreeUri(uri)
            if (resolvedPath != null) {
                recordingPath = resolvedPath
                prefs.edit().putString("recording_destination_dir", resolvedPath).apply()
                Toast.makeText(context, "Destino de grabaciones: $resolvedPath", Toast.LENGTH_LONG).show()
            } else {
                val uriStr = uri.toString()
                recordingPath = uriStr
                prefs.edit().putString("recording_destination_dir", uriStr).apply()
                Toast.makeText(context, "URI guardado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // States for adding radio dialog from options
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var logoUriInput by remember { mutableStateOf<String?>(null) }
    
    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            logoUriInput = it.toString()
        }
    }

    var activeSettingsTab by remember { mutableStateOf(0) } // 0 = FTP Servidores, 1 = Radios & Carpetas, 2 = Memoria Caché, 3 = Permisos, 4 = Manual, 5 = Cambios, 6 = Info

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = activeSettingsTab,
            containerColor = CyberDark,
            contentColor = CyberTeal,
            edgePadding = 8.dp
        ) {
            Tab(
                selected = activeSettingsTab == 0,
                onClick = { activeSettingsTab = 0 },
                text = { Text("FTP CONECTAR 🔌", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
            Tab(
                selected = activeSettingsTab == 1,
                onClick = { activeSettingsTab = 1 },
                text = { Text("RADIOS & RUTAS 📻", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
            Tab(
                selected = activeSettingsTab == 2,
                onClick = { activeSettingsTab = 2 },
                text = { Text("MEMORIA CACHÉ 🧹", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
            Tab(
                selected = activeSettingsTab == 3,
                onClick = { activeSettingsTab = 3 },
                text = { Text("PERMISOS ☑️", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
            Tab(
                selected = activeSettingsTab == 4,
                onClick = { activeSettingsTab = 4 },
                text = { Text("MANUAL 📖", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
            Tab(
                selected = activeSettingsTab == 5,
                onClick = { activeSettingsTab = 5 },
                text = { Text("NOVEDADES v1.1 📋", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
            Tab(
                selected = activeSettingsTab == 6,
                onClick = { activeSettingsTab = 6 },
                text = { Text("INFO Y VERSIÓN ℹ️", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (activeSettingsTab) {
                0 -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        BrowserTabSchema(viewModel, onNavigateToPlayer)
                    }
                }
                1 -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Section: Gestor de Carpetas
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Gestor de Directorios de Escaneo", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Configura múltiples rutas locales de tu celular o carpetas remotas en tus servidores FTP configurados para buscar canciones de forma unificada en tu biblioteca.", fontSize = 10.sp, color = Color.Gray)
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Text("Rutas configuradas (${scanFolders.size}):", color = CyberTeal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    if (scanFolders.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(CyberCharcoal, RoundedCornerShape(8.dp))
                                                .padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("No hay carpetas configuradas. Agrega una abajo👇", fontSize = 11.sp, color = Color.Gray)
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            scanFolders.forEach { folder ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(CyberCharcoal, RoundedCornerShape(8.dp))
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                        Icon(
                                                            imageVector = if (folder.isFtp) Icons.Default.CloudQueue else Icons.Default.Folder,
                                                            contentDescription = null,
                                                            tint = if (folder.isFtp) CyberTeal else SoftTeal,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Column {
                                                            Text(
                                                                text = if (folder.isFtp) "[FTP] ${folder.connectionName ?: "Servidor"}" else "Carpeta Local",
                                                                color = Color.White,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Text(
                                                                text = folder.path,
                                                                color = Color.Gray,
                                                                fontSize = 10.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.removeScanFolder(folder)
                                                            Toast.makeText(context, "Ruta de escaneo eliminada", Toast.LENGTH_SHORT).show()
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Quitar",
                                                            tint = WarningHotPink,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Divider(color = CyberCharcoal)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    
                                    var addModeIsFtp by remember { mutableStateOf(false) }
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = { addModeIsFtp = false },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (!addModeIsFtp) CyberTeal else CyberCharcoal,
                                                contentColor = if (!addModeIsFtp) CyberDark else Color.White
                                            ),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Añadir Local", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        
                                        Button(
                                            onClick = { addModeIsFtp = true },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (addModeIsFtp) CyberTeal else CyberCharcoal,
                                                contentColor = if (addModeIsFtp) CyberDark else Color.White
                                            ),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Añadir FTP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    if (!addModeIsFtp) {
                                        var tempPath by remember { mutableStateOf("") }
                                        
                                        val localPickerLauncher = rememberLauncherForActivityResult(
                                            contract = ActivityResultContracts.OpenDocumentTree()
                                        ) { uri ->
                                            uri?.let {
                                                val resolvedPath = getPathFromDocumentTreeUri(it)
                                                if (resolvedPath != null) {
                                                    tempPath = resolvedPath
                                                }
                                            }
                                        }

                                        OutlinedTextField(
                                            value = tempPath,
                                            onValueChange = { tempPath = it },
                                            label = { Text("Ruta absoluta o seleccionada", fontSize = 11.sp, color = CyberTeal) },
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = { localPickerLauncher.launch(null) }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.FolderOpen,
                                                        contentDescription = "Examinar almacenamiento",
                                                        tint = CyberTeal
                                                    )
                                                }
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = CyberTeal,
                                                focusedLabelColor = CyberTeal,
                                                unfocusedBorderColor = CyberCharcoal,
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            ),
                                            modifier = Modifier.fillMaxWidth().testTag("local_scan_path_input")
                                        )
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { localPickerLauncher.launch(null) },
                                                colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal, contentColor = CyberTeal),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Examinar Celular...", fontSize = 11.sp)
                                            }
                                            Button(
                                                onClick = {
                                                    if (tempPath.isNotBlank()) {
                                                        viewModel.addLocalScanFolder(tempPath.trim())
                                                        tempPath = ""
                                                        Toast.makeText(context, "Carpeta local sintonizada y agregada", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Escribe una ruta o selecciona una carpeta.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Agregar Carpeta", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    } else {
                                        if (savedConnections.isEmpty()) {
                                            Text("No tienes ningún servidor FTP guardado en la app. Ve a la sección de Conectar FTP para configurar uno primero.", fontSize = 11.sp, color = WarningHotPink, modifier = Modifier.padding(vertical = 4.dp))
                                        } else {
                                            var selectedConnectionIndex by remember { mutableStateOf(0) }
                                            val conn = savedConnections.getOrNull(selectedConnectionIndex)
                                            
                                            var ftpPathInput by remember { mutableStateOf("/") }
                                            var dropdownExpanded by remember { mutableStateOf(false) }
                                            
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = conn?.name ?: "Selecciona Servidor",
                                                    onValueChange = {},
                                                    readOnly = true,
                                                    label = { Text("Asociar con Servidor FTP", fontSize = 11.sp, color = CyberTeal) },
                                                    trailingIcon = {
                                                        IconButton(onClick = { dropdownExpanded = true }) {
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Elegir", tint = CyberTeal)
                                                        }
                                                    },
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = CyberTeal,
                                                        unfocusedBorderColor = CyberCharcoal,
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                DropdownMenu(
                                                    expanded = dropdownExpanded,
                                                    onDismissRequest = { dropdownExpanded = false },
                                                    modifier = Modifier.fillMaxWidth().background(CyberCharcoal)
                                                ) {
                                                    savedConnections.forEachIndexed { idx, connection ->
                                                        DropdownMenuItem(
                                                            text = { Text(connection.name, color = Color.White, fontSize = 12.sp) },
                                                            onClick = {
                                                                selectedConnectionIndex = idx
                                                                dropdownExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.height(8.dp))
                                            
                                            OutlinedTextField(
                                                value = ftpPathInput,
                                                onValueChange = { ftpPathInput = it },
                                                label = { Text("Ruta Remota (ej. /Music)", fontSize = 11.sp, color = CyberTeal) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = CyberTeal,
                                                    focusedLabelColor = CyberTeal,
                                                    unfocusedBorderColor = CyberCharcoal,
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            
                                            Spacer(modifier = Modifier.height(8.dp))
                                            
                                            Button(
                                                onClick = {
                                                    if (conn != null) {
                                                        viewModel.addFtpScanFolder(conn, ftpPathInput.trim())
                                                        Toast.makeText(context, "Directorio FTP agregado a escaneo.", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Guardar Carpeta FTP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section 2: Agregar Emisoras de Radio Online
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Radio, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sintonizar / Agregar Emisora Online", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text("Guarda una nueva emisión directa para que aparezca automáticamente en tu lista sintonizadora de internet.", fontSize = 10.sp, color = Color.Gray)

                                    OutlinedTextField(
                                        value = nameInput,
                                        onValueChange = { nameInput = it },
                                        label = { Text("Nombre de la Emisora") },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyberTeal,
                                            focusedLabelColor = CyberTeal,
                                            unfocusedBorderColor = CyberCharcoal,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    OutlinedTextField(
                                        value = urlInput,
                                        onValueChange = { urlInput = it },
                                        label = { Text("URL de la Emisión (http://...)") },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyberTeal,
                                            focusedLabelColor = CyberTeal,
                                            unfocusedBorderColor = CyberCharcoal,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { logoPickerLauncher.launch("image/*") },
                                            colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal, contentColor = CyberTeal),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(if (logoUriInput.isNullOrBlank()) "Elegir Logo (Teléfono)" else "Cambiar Logo", fontSize = 11.sp)
                                        }
                                        if (!logoUriInput.isNullOrBlank()) {
                                            AsyncImage(
                                                model = logoUriInput,
                                                contentDescription = "Logo",
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .border(1.dp, CyberTeal, RoundedCornerShape(4.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            IconButton(onClick = { logoUriInput = null }) {
                                                Icon(Icons.Default.Close, contentDescription = "Quitar", tint = WarningHotPink, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (nameInput.isNotBlank() && urlInput.isNotBlank()) {
                                                viewModel.addOnlineRadio(nameInput.trim(), urlInput.trim(), logoUriInput)
                                                nameInput = ""
                                                urlInput = ""
                                                logoUriInput = null
                                                Toast.makeText(context, "Radio Online agregada con éxito", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Por favor, completa los campos de nombre y URL", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Guardar Nueva Radio", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Section: Configuración de Timeshift y Estabilidad de Radio
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = null,
                                            tint = WarningHotPink,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Opciones Avanzadas de Radios",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Ajusta cómo la aplicación interactúa con las emisoras online para mejorar la estabilidad y evitar cortes del búfer.",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Auto Timeshift SharedPref option
                                    var autoTimeshift by remember { 
                                        mutableStateOf(prefs.getBoolean("auto_timeshift_enabled", false)) 
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val newValue = !autoTimeshift
                                                autoTimeshift = newValue
                                                prefs.edit().putBoolean("auto_timeshift_enabled", newValue).apply()
                                                Toast.makeText(context, if (newValue) "Grabación automática de Timeshift activada" else "Grabación automática desactivada (Escucha directa estable)", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Timeshift Automático (Búfer en Vivo)",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Inicia el archivo de almacenamiento tan pronto sintonizas. Desactiva para una conexión directa más fluida.",
                                                color = Color.Gray,
                                                fontSize = 9.sp
                                            )
                                        }
                                        Switch(
                                            checked = autoTimeshift,
                                            onCheckedChange = { newValue ->
                                                autoTimeshift = newValue
                                                prefs.edit().putBoolean("auto_timeshift_enabled", newValue).apply()
                                                Toast.makeText(context, if (newValue) "Grabación automática de Timeshift activada" else "Grabación automática desactivada (Escucha directa estable)", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = CyberTeal,
                                                checkedTrackColor = CyberTeal.copy(alpha = 0.5f),
                                                uncheckedThumbColor = Color.Gray,
                                                uncheckedTrackColor = CyberCharcoal
                                            )
                                        )
                                    }

                                    Divider(color = CyberCharcoal, modifier = Modifier.padding(vertical = 8.dp))

                                    // Timeshift on Pause option
                                    var timeshiftOnPause by remember { 
                                        mutableStateOf(prefs.getBoolean("timeshift_on_pause_enabled", true)) 
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val newValue = !timeshiftOnPause
                                                timeshiftOnPause = newValue
                                                prefs.edit().putBoolean("timeshift_on_pause_enabled", newValue).apply()
                                                Toast.makeText(context, if (newValue) "Se grabará Timeshift al pulsar pausa" else "Búfer desactivado al pausar", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Timeshift Automático al Pausar",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Si estás escuchando en vivo directo, inicia la grabación del búfer al pausar para poder reanudar desde ese instante.",
                                                color = Color.Gray,
                                                fontSize = 9.sp
                                            )
                                        }
                                        Switch(
                                            checked = timeshiftOnPause,
                                            onCheckedChange = { newValue ->
                                                timeshiftOnPause = newValue
                                                prefs.edit().putBoolean("timeshift_on_pause_enabled", newValue).apply()
                                                Toast.makeText(context, if (newValue) "Se grabará Timeshift al pulsar pausa" else "Búfer desactivado al pausar", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = CyberTeal,
                                                checkedTrackColor = CyberTeal.copy(alpha = 0.5f),
                                                uncheckedThumbColor = Color.Gray,
                                                uncheckedTrackColor = CyberCharcoal
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    var cacheData by remember { mutableStateOf(getCoverCacheInfo(context)) }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            tint = WarningHotPink,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Gestión de Caché de Carátulas",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Para acelerar la visualización y ahorrar tus datos móviles, el extractor de carátulas copia y almacena las imágenes internamente. Estas permanecerán disponibles de forma permanente mientras usas la aplicación, permitiendo ver las carátulas incluso sin conexión.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Espacio Ocupado:",
                                                color = Color.Gray,
                                                fontSize = 11.sp
                                            )
                                            val formattedSize = remember(cacheData.first) {
                                                val bytes = cacheData.first
                                                if (bytes >= 1024 * 1024) {
                                                    String.format("%.2f MB", bytes.toFloat() / (1024 * 1024))
                                                } else {
                                                    String.format("%.1f KB", bytes.toFloat() / 1024)
                                                }
                                            }
                                            Text(
                                                text = formattedSize,
                                                color = CyberTeal,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "Imágenes en Caché:",
                                                color = Color.Gray,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "${cacheData.second}",
                                                color = Color.White,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Button(
                                        onClick = {
                                            val liberatedBytes = cacheData.first
                                            val freedCount = cacheData.second
                                            val success = clearCoverCache(context)
                                            if (success) {
                                                cacheData = getCoverCacheInfo(context)
                                                val liberatedMb = liberatedBytes.toFloat() / (1024 * 1024)
                                                val detailMsg = if (liberatedBytes >= 1024 * 1024) {
                                                    String.format("Se liberaron %.2f MB y %d carátulas.", liberatedMb, freedCount)
                                                } else {
                                                    "Caché liberada con éxito."
                                                }
                                                Toast.makeText(context, detailMsg, Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "No se pudo vaciar la caché por completo.", Toast.LENGTH_SHORT).show()
                                                cacheData = getCoverCacheInfo(context)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = WarningHotPink,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = cacheData.second > 0
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Borrar Caché de Carátulas",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    var showCacheExplorerDialog by remember { mutableStateOf(false) }
                                    var cachedFilesList by remember(showCacheExplorerDialog) {
                                        mutableStateOf(
                                            try {
                                                context.cacheDir.listFiles()?.toList() ?: emptyList()
                                            } catch (e: Exception) {
                                                emptyList()
                                            }
                                        )
                                    }

                                    Button(
                                        onClick = { showCacheExplorerDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CyberCharcoal,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = CyberTeal,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Ver Archivos en Caché 📂",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    if (showCacheExplorerDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showCacheExplorerDialog = false },
                                            title = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = CyberTeal)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Caché Interna: ${cachedFilesList.size} archivos", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                }
                                            },
                                            text = {
                                                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                                                    Text(
                                                        text = "Listado de archivos temporales de carátulas y fragmentos almacenados en la memoria intermedia de la aplicación:",
                                                        fontSize = 11.sp,
                                                        color = Color.Gray,
                                                        modifier = Modifier.padding(bottom = 12.dp)
                                                    )
                                                    
                                                    if (cachedFilesList.isEmpty()) {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text("La carpeta de caché está vacía.", color = Color.LightGray, fontSize = 12.sp)
                                                        }
                                                    } else {
                                                        LazyColumn(
                                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            items(cachedFilesList.size) { index ->
                                                                val file = cachedFilesList[index]
                                                                Row(
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .background(CyberCharcoal, RoundedCornerShape(8.dp))
                                                                        .padding(8.dp),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                                ) {
                                                                    Column(modifier = Modifier.weight(1f)) {
                                                                        Text(
                                                                            text = file.name,
                                                                            color = Color.White,
                                                                            fontSize = 11.sp,
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow.Ellipsis
                                                                        )
                                                                        Text(
                                                                            text = formatBytes(file.length()),
                                                                            color = CyberTeal,
                                                                            fontSize = 9.sp
                                                                        )
                                                                    }
                                                                    IconButton(
                                                                        onClick = {
                                                                            try {
                                                                                if (file.delete()) {
                                                                                    cachedFilesList = context.cacheDir.listFiles()?.toList() ?: emptyList()
                                                                                    cacheData = getCoverCacheInfo(context)
                                                                                    Toast.makeText(context, "Archivo eliminado", Toast.LENGTH_SHORT).show()
                                                                                } else {
                                                                                    Toast.makeText(context, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
                                                                                }
                                                                            } catch (e: Exception) {
                                                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                                            }
                                                                        },
                                                                        modifier = Modifier.size(28.dp)
                                                                    ) {
                                                                        Icon(Icons.Default.Delete, contentDescription = "Eliminar archivo de caché", tint = WarningHotPink, modifier = Modifier.size(16.dp))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            confirmButton = {
                                                TextButton(onClick = { showCacheExplorerDialog = false }) {
                                                    Text("Cerrar", color = CyberTeal)
                                                }
                                            },
                                            containerColor = CyberSurface,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    // Update check state dynamically of permissions
                    var hasIgnoreBattery by remember {
                        mutableStateOf(
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                                pm.isIgnoringBatteryOptimizations(context.packageName)
                            } else true
                        )
                    }
                    var hasNotifications by remember {
                        mutableStateOf(
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            } else true
                        )
                    }
                    var hasStorageAudio by remember {
                        mutableStateOf(
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            } else {
                                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
                        )
                    }

                    val notificationLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasNotifications = isGranted
                        if (isGranted) {
                            Toast.makeText(context, "¡Notificaciones de control permitidas!", Toast.LENGTH_SHORT).show()
                        }
                    }

                    val mediaLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasStorageAudio = isGranted
                        if (isGranted) {
                            Toast.makeText(context, "¡Permiso de lectura local concedido!", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // A handler to refresh states when resuming the app (coming back from phone setting menus)
                    DisposableEffect(Unit) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                                    hasIgnoreBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    hasNotifications = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                }
                                hasStorageAudio = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                } else {
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                }
                            }
                        }
                        val lifecycle = (context as? androidx.activity.ComponentActivity)?.lifecycle
                        lifecycle?.addObserver(observer)
                        onDispose {
                            lifecycle?.removeObserver(observer)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = CyberTeal)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Configuración de Permisos 🔒", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Para que la radio en directo sin cortes, los servidores de música y las reproducciones sigan funcionando en segundo plano, tu sistema Android necesita desactivar la optimización de pila.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                }
                            }
                        }

                        // Card 1: Batería
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = Icons.Default.Power,
                                                contentDescription = null,
                                                tint = if (hasIgnoreBattery) CyberTeal else WarningHotPink,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("Batería sin Restricciones ⚡", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = if (hasIgnoreBattery) "Optimización Desactivada (Excelente)" else "Optimización Activa (Android puede apagar la radio)",
                                                    color = if (hasIgnoreBattery) CyberTeal else WarningHotPink,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (hasIgnoreBattery) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (hasIgnoreBattery) CyberTeal else WarningHotPink
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "El sistema de ahorro de Android interrumpe aleatoriamente los procesos de música inalámbrica de fondo. Activar el modo 'Sin Restricciones' evita los cortes y asegura una reproducción de fondo perfecta de la radio.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = android.net.Uri.parse("package:${context.packageName}")
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                    context.startActivity(intent)
                                                } catch (ex: Exception) {
                                                    Toast.makeText(context, "Por favor desactiva la optimización manualmente en Ajustes del teléfono -> Batería", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (hasIgnoreBattery) CyberCharcoal else WarningHotPink,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = if (hasIgnoreBattery) "CONFIGURADO CORRECTAMENTE ✅" else "CONFIGURAR BATERÍA SIN LÍMITES",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Card 2: Notificaciones
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = Icons.Default.Notifications,
                                                contentDescription = null,
                                                tint = if (hasNotifications) CyberTeal else WarningHotPink,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("Notificaciones de Control 🔔", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = if (hasNotifications) "Habilitado" else "Habilitar para ver barra de control",
                                                    color = if (hasNotifications) CyberTeal else WarningHotPink,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (hasNotifications) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (hasNotifications) CyberTeal else WarningHotPink
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Permiso indispensable para poder pausar, avanzar o cambiar de canción cómodamente desde la pantalla de bloqueo o desde el menú superior deslizable de tu Android.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    
                                    Button(
                                        onClick = {
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                Toast.makeText(context, "Las notificaciones ya están autorizadas automáticamente en tu versión de Android.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (hasNotifications) CyberCharcoal else CyberTeal,
                                            contentColor = if (hasNotifications) Color.White else CyberDark
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = if (hasNotifications) "NOTIFICACIONES CONCEDIDAS ✅" else "PERMITIR NOTIFICACIONES",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Card 3: Lectura Almacenamiento
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = if (hasStorageAudio) CyberTeal else WarningHotPink,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("Permiso de Archivos Musicales 🎵", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = if (hasStorageAudio) "Habilitado" else "Pendiente para reproducir ficheros",
                                                    color = if (hasStorageAudio) CyberTeal else WarningHotPink,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (hasStorageAudio) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (hasStorageAudio) CyberTeal else WarningHotPink
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Posibilita la lectura de archivos locales para las carpetas sincronizadas, la creación física de podcasts descargados y la gestión óptima de tus grabaciones guardadas.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    
                                    Button(
                                        onClick = {
                                            val permStr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                                android.Manifest.permission.READ_MEDIA_AUDIO
                                            } else {
                                                android.Manifest.permission.READ_EXTERNAL_STORAGE
                                            }
                                            mediaLauncher.launch(permStr)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (hasStorageAudio) CyberCharcoal else CyberTeal,
                                            contentColor = if (hasStorageAudio) Color.White else CyberDark
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = if (hasStorageAudio) "ACCESO A ARCHIVOS AUTORIZADO ✅" else "CONCEDER ACCESO A FICHEROS AUDIO",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                4 -> {
                    // TAB 5: MANUAL DE USO INTERACTIVO
                    val categories = listOf(
                        Triple("🔌 Servidores FTP y Red Local", "Aprende a conectar y sincronizar tu propia biblioteca de música en la nube", 
                            "1. Ve a la pestaña 'FTP CONECTAR' e introduce la dirección IP o dominio de tu servidor, junto con el usuario, contraseña y puerto (por defecto es 21).\n\n" +
                            "2. Una vez guardado el perfil, podrás explorar directorios remotos directamente al vuelo.\n\n" +
                            "3. Añade pistas a tu cola de reproducción clicando en ellas. Se transmitirán por streaming seguro en tiempo real sin necesidad de ocupar memoria interna (descarga física)."
                        ),
                        Triple("📻 Estaciones de Radio Online", "Sintoniza flujos digitales en directo y añade tus diales favoritos",
                            "1. En 'RADIOS & RUTAS' dispones de un gestor de emisoras donde puedes añadir flujos de radio personales URL (soporta codecs MP3, AAC, y listas M3U8/HLS).\n\n" +
                            "2. Puedes personalizar el visual de tu dial asignándole un título descriptivo y cargando un logotipo o miniatura de carátula desde tu galería de fotos local.\n\n" +
                            "3. Disfruta de la reproducción ininterrumpida estés donde estés de tus diales de noticias o música preferidos."
                        ),
                        Triple("⏰ Grabadora de Emisiones (Live Recording)", "Graba fragmentos de tus radio-shows o canciones preferidas",
                            "1. Mientras sintonizas una emisora, dispones de una sección para activar la grabación en tiempo real.\n\n" +
                            "2. Los archivos resultantes se codifican e insertan en la carpeta de grabaciones de tu celular.\n\n" +
                            "3. Puedes configurar libremente la ruta física de destino de las grabaciones desde la pestaña 'GRABACIONES' para guardarlas en una memoria externa SD o directorio específico."
                        ),
                        Triple("📍 Podmarks (Marcadores de Audio Interactivos)", "Marca y salta a los mejores momentos de tus grabaciones o streamings",
                            "1. Si escuchas un fragmento espectacular de audio, pincha en 'CREAR PODMARK' en la interfaz del reproductor.\n\n" +
                            "2. Introducirá una marca física indexada con la fecha actual y la posición de segundo exacta.\n\n" +
                            "3. En la barra de reproducción (slider) aparecerán unos pines físicos interactivos (puntos decorados con pines) que representan tus marcas.\n\n" +
                            "4. Clicando sobre el pin o en tu panel de 'MIS PODMARKS GUARDADOS' del reproductor, el cabezal de audio saltará instantáneamente al momento perfecto guardado."
                        ),
                        Triple("🧹 Optimización de Caché y Memoria", "Mantén el rendimiento óptimo y borra datos residuales",
                            "1. La radio por internet y la música FTP descargan flujos constantes en un búfer temporal de memoria para evitar interrupciones.\n\n" +
                            "2. En la pestaña 'MEMORIA CACHÉ' puedes vigilar el espacio físico total consumido por estos archivos temporales.\n\n" +
                            "3. El borrado de caché es seguro y libera tu almacenamiento. No eliminará tus listas de canciones, grabaciones físicas ni tus podmarks configurados."
                        ),
                        Triple("⚡ Prevención de Cortes (Segundo Plano)", "Impedir que tu móvil Android apague de improvisto el reproductor de fondo",
                            "1. Por defecto, tu sistema operativo Android ahorra energía cerrando procesos inactivos cuando la pantalla se apaga o estás usando otras apps.\n\n" +
                            "2. Dirígete a la pestaña de 'PERMISOS' para verificar el estado de optimización.\n\n" +
                            "3. Al conceder el permiso 'Batería sin límites' (ignorar optimización de batería), garantizas que el flujo de streaming o radio continúe permanente en segundo plano."
                        )
                    )

                    var expandedIndex by remember { mutableStateOf<Int?>(null) }
                    var searchManualQuery by remember { mutableStateOf("") }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Manual de Instrucciones del Usuario 📖",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Text(
                            text = "Aprende a dominar las amplias herramientas de gestión y reproducción de Pozo Media Hub.",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )

                        // Search Bar to filter manual categories
                        TextField(
                            value = searchManualQuery,
                            onValueChange = { searchManualQuery = it },
                            placeholder = { Text("Buscar en el manual...", color = Color.Gray, fontSize = 12.sp) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = CyberSurface,
                                unfocusedContainerColor = CyberSurface,
                                focusedIndicatorColor = CyberTeal,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )

                        val filteredCategories = categories.filter {
                            it.first.contains(searchManualQuery, ignoreCase = true) ||
                            it.second.contains(searchManualQuery, ignoreCase = true) ||
                            it.third.contains(searchManualQuery, ignoreCase = true)
                        }

                        if (filteredCategories.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No se encontraron secciones para la búsqueda.", color = Color.Gray, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(filteredCategories) { index, item ->
                                    val isExpanded = expandedIndex == index
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isExpanded) CyberSurface else CyberCharcoal
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            expandedIndex = if (isExpanded) null else index
                                        }
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = item.first,
                                                    color = if (isExpanded) CyberTeal else Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                    contentDescription = null,
                                                    tint = if (isExpanded) CyberTeal else Color.LightGray
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = item.second,
                                                color = Color.LightGray,
                                                fontSize = 11.sp,
                                                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            
                                            AnimatedVisibility(
                                                visible = isExpanded,
                                                enter = expandVertically() + fadeIn(),
                                                exit = shrinkVertically() + fadeOut()
                                            ) {
                                                Column {
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    HorizontalDivider(color = CyberGrey.copy(alpha = 0.5f), thickness = 1.dp)
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(
                                                        text = item.third,
                                                        color = Color(0xFFE2E7EC),
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 16.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                5 -> {
                    // TAB 5: LISTADO DE NOVEDADES Y CAMBIOS DE LA VERSIÓN v1.1
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Historial de Cambios - Versión 1.1 📋", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "¡Bienvenido a la actualización 1.1 de Pozo Media Hub! A continuación se detallan las mejoras e innovaciones introducidas en esta versión.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Star, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Novedades Principales", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    HorizontalDivider(color = CyberCharcoal)

                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row {
                                            Text("💾", fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Grabaciones integradas en Multimedia", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Hemos movido todas las capacidades de podcasts y grabaciones locales físicas, así como los Podmarks (marcadores de audio interactivos), de la pestaña ajustes a una nueva pestaña exclusiva en MULTIMEDIA, haciendo la navegación instantánea y directa.", color = Color.Gray, fontSize = 10.sp)
                                            }
                                        }

                                        Row {
                                            Text("📦", fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Actualizador de Dropbox Rediseñado", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Desconectamos permanentemente la distribución de código abierto de GitHub para enfocarnos exclusivamente en Dropbox. Ahora la aplicación v1.1 se actualiza de manera más estable desde tus links compartidos.", color = Color.Gray, fontSize = 10.sp)
                                            }
                                        }

                                        Row {
                                            Text("🔧", fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Apartado de Cambios (Changelog)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Integrado este panel interactivo de novedades directamente en tus Ajustes del sistema para que nunca te pierdas de las últimas funciones de la aplicación.", color = Color.Gray, fontSize = 10.sp)
                                            }
                                        }
                                        
                                        Row {
                                            Text("🚀", fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Optimización de Timeshift Buffer", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Mejoramos la latencia y la fluidez al pausar / reanudar y navegar las emisoras de radio online en tiempo real.", color = Color.Gray, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                6 -> {
                    // TAB 6: INFO Y VERSIÓN CON ACTUALIZADOR DROPBOX EXCLUSIVO (GITHUB ELIMINADO v1.1)
                    val currentVersionName = "1.1"
                    
                    val updaterPrefs = remember { context.getSharedPreferences("app_updater_configs", android.content.Context.MODE_PRIVATE) }
                    
                    var dropboxVersionUrl by remember { mutableStateOf(updaterPrefs.getString("dropbox_version_url", "") ?: "") }
                    var dropboxApkUrl by remember { mutableStateOf(updaterPrefs.getString("dropbox_apk_url", "") ?: "") }
                    
                    var updateStatusMsg by remember { mutableStateOf("Listo para buscar actualizaciones en Dropbox.") }
                    var updateNotes by remember { mutableStateOf("") }
                    var updateDownloadUrl by remember { mutableStateOf<String?>(null) }
                    var updateIsNewer by remember { mutableStateOf(false) }
                    var updateIsLoading by remember { mutableStateOf(false) }
                    
                    val updateScope = rememberCoroutineScope()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    PozoBrandLogo()
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "ESTADO DE VERSIÓN",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CyberTeal,
                                        letterSpacing = 1.5.sp
                                    )
                                    Text(
                                        text = "Versión actual: v$currentVersionName",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Canal de lanzamiento: Producción (Oficial)",
                                        fontSize = 10.sp,
                                        color = Color.LightGray
                                    )
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Sync,
                                                contentDescription = null,
                                                tint = CyberTeal,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Actualizar Aplicación (Dropbox) 📦",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Text(
                                        text = "Sube un archivo de texto simple a Dropbox que contenga el número de versión (ej: 1.1) y un archivo APK con la aplicación. Pega sus enlaces compartidos abajo para habilitar la búsqueda e instalación automatizada.",
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = dropboxVersionUrl,
                                        onValueChange = { 
                                            dropboxVersionUrl = it 
                                            updaterPrefs.edit().putString("dropbox_version_url", it).apply()
                                        },
                                        label = { Text("Enlace Dropbox a version.txt", fontSize = 10.sp, color = Color.Gray) },
                                        placeholder = { Text("https://www.dropbox.com/.../version.txt?dl=0", fontSize = 10.sp, color = Color.DarkGray) },
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyberTeal,
                                            unfocusedBorderColor = CyberCharcoal
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = dropboxApkUrl,
                                        onValueChange = { 
                                            dropboxApkUrl = it 
                                            updaterPrefs.edit().putString("dropbox_apk_url", it).apply()
                                        },
                                        label = { Text("Enlace Dropbox a la APK", fontSize = 10.sp, color = Color.Gray) },
                                        placeholder = { Text("https://www.dropbox.com/.../app.apk?dl=0", fontSize = 10.sp, color = Color.DarkGray) },
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyberTeal,
                                            unfocusedBorderColor = CyberCharcoal
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Estado del Chequeo
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(CyberCharcoal, RoundedCornerShape(8.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (updateIsLoading) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        color = CyberTeal,
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                }
                                                Text(
                                                    text = updateStatusMsg,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (updateIsNewer) WarningHotPink else if (updateStatusMsg.startsWith("✅")) CyberTeal else Color.White
                                                )
                                            }
                                            if (updateNotes.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "Información del Servidor:",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CyberTeal
                                                )
                                                Text(
                                                    text = updateNotes,
                                                    fontSize = 10.sp,
                                                    color = Color.LightGray,
                                                    maxLines = 5,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            updateIsLoading = true
                                            updateStatusMsg = "Conectando..."
                                            updateNotes = ""
                                            updateDownloadUrl = null
                                            updateIsNewer = false
                                            
                                            updateScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    if (dropboxVersionUrl.isBlank()) {
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            updateIsLoading = false
                                                            updateStatusMsg = "⚠️ Por favor, introduce primero un enlace válido de Dropbox para tu version.txt."
                                                        }
                                                        return@launch
                                                    }
                                                    
                                                    var formattedUrl = dropboxVersionUrl.trim()
                                                    if (formattedUrl.contains("dropbox.com")) {
                                                        formattedUrl = formattedUrl
                                                            .replace("www.dropbox.com", "dl.dropboxusercontent.com")
                                                            .replace("dl=0", "dl=1")
                                                        if (!formattedUrl.contains("dl=1") && !formattedUrl.contains("raw=1")) {
                                                            formattedUrl += (if (formattedUrl.contains("?")) "&" else "?") + "dl=1"
                                                        }
                                                    }
                                                    
                                                    val url = java.net.URL(formattedUrl)
                                                    val conn = url.openConnection() as java.net.HttpURLConnection
                                                    conn.requestMethod = "GET"
                                                    conn.connectTimeout = 8000
                                                    conn.readTimeout = 8000
                                                    
                                                    val responseCode = conn.responseCode
                                                    if (responseCode == 200 || responseCode == 302) {
                                                        val realConn = if (responseCode == 302) {
                                                            val redirectUrl = conn.getHeaderField("Location")
                                                            val rUrl = java.net.URL(redirectUrl)
                                                            (rUrl.openConnection() as java.net.HttpURLConnection).apply {
                                                                requestMethod = "GET"
                                                                connectTimeout = 8000
                                                                readTimeout = 8000
                                                            }
                                                        } else conn
                                                        
                                                        val rawText = realConn.inputStream.bufferedReader().use { it.readText() }.trim()
                                                        
                                                        if (rawText.contains("<html", ignoreCase = true) || rawText.startsWith("<!")) {
                                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                updateIsLoading = false
                                                                updateStatusMsg = "⚠️ El enlace no apunta al texto directo sino a la web de Dropbox.\n\nAsegúrate de copiar el 'Enlace Compartido' y que empiece por https://www.dropbox.com/."
                                                            }
                                                        } else {
                                                            val cleanTag = rawText.lowercase().removePrefix("v").trim()
                                                            val currentVer = currentVersionName.lowercase().removePrefix("v").trim()
                                                            
                                                            if (cleanTag.length > 20 || cleanTag.isEmpty()) {
                                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                    updateIsLoading = false
                                                                    updateStatusMsg = "⚠️ Contenido inválido leído en Dropbox: '$rawText'. El fichero 'version.txt' de Dropbox debe contener SOLAMENTE el número de versión (ej: 1.1) en texto plano."
                                                                }
                                                            } else {
                                                                val isDifferent = cleanTag != currentVer
                                                                
                                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                    updateIsLoading = false
                                                                    
                                                                    var apkDownloadUrl = dropboxApkUrl.trim()
                                                                    if (apkDownloadUrl.isBlank()) {
                                                                        apkDownloadUrl = dropboxVersionUrl.trim() // fallback
                                                                    } else if (apkDownloadUrl.contains("dropbox.com")) {
                                                                        apkDownloadUrl = apkDownloadUrl
                                                                            .replace("www.dropbox.com", "dl.dropboxusercontent.com")
                                                                            .replace("dl=0", "dl=1")
                                                                        if (!apkDownloadUrl.contains("dl=1") && !apkDownloadUrl.contains("raw=1")) {
                                                                            apkDownloadUrl += (if (apkDownloadUrl.contains("?")) "&" else "?") + "dl=1"
                                                                        }
                                                                    }
                                                                    
                                                                    updateDownloadUrl = apkDownloadUrl
                                                                    if (isDifferent) {
                                                                        updateIsNewer = true
                                                                        updateStatusMsg = "✨ ¡NUEVA ACTUALIZACIÓN DETECTADA EN DROPBOX: v$cleanTag! ✨"
                                                                        updateNotes = "Descarga el archivo APK directamente de tu cuenta de Dropbox para su instalación."
                                                                    } else {
                                                                        updateIsNewer = false
                                                                        updateStatusMsg = "✅ Tu aplicación está en la última versión oficial (v$currentVersionName)."
                                                                        updateNotes = "El archivo version.txt leído de Dropbox indica que la versión remota idéntica es v$cleanTag."
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            updateIsLoading = false
                                                            updateStatusMsg = "⚠️ No se pudo leer el archivo de versión (Código $responseCode). Comprueba que el enlace compartido de Dropbox sea público."
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        updateIsLoading = false
                                                        updateStatusMsg = "❌ Error de conexión: ${e.localizedMessage ?: "Consulte su conexión"}"
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CyberTeal,
                                            contentColor = CyberDark
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = !updateIsLoading
                                    ) {
                                        Text(
                                            text = if (updateIsLoading) "BUSCANDO..." else "BUSCAR ÚLTIMA VERSIÓN 🔄",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (updateDownloadUrl != null) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = {
                                                try {
                                                    val intent = android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(updateDownloadUrl)
                                                    )
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (updateIsNewer) WarningHotPink else CyberCharcoal,
                                                contentColor = Color.White
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = if (updateIsNewer) "DESCARGAR APK NUEVA AHORA 📩" else "ABRIR ENLACE DE CONEXIÓN 🌐",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Aviso de Sincronización y Actualizaciones 🔔",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "La opción de Dropbox es perfecta para una distribución privada rápida sin configurar repositorios complejos. Al crear un enlace compartido en Dropbox, asegúrate de que el enlace sea de acceso público para que la app pueda descargar directamente el APK.",
                                        fontSize = 10.5.sp,
                                        color = Color.Gray,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GroupHeader(title: String, count: Int, isExpanded: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CyberCharcoal),
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = CyberTeal
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (title == "Desconocido" || title.isBlank()) "Sin clasificar" else title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$count canciones",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray
                )
            }
        }
    }
}

@Composable
fun LocalTrackCard(
    track: com.example.data.model.LocalMusicTrack,
    context: android.content.Context,
    viewModel: FtpViewModel,
    allTracksInView: List<com.example.data.model.LocalMusicTrack>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = {
            val playlistPlaylists = allTracksInView.map {
                PlaylistItem(
                    id = 0,
                    playlistId = -1,
                    fileName = it.title,
                    filePath = it.filePath,
                    fileSize = it.size
                )
            }
            val currentPlaylistItem = PlaylistItem(
                id = 0,
                playlistId = -1,
                fileName = track.title,
                filePath = track.filePath,
                fileSize = track.size
            )
            AudioPlayerManager.playTrack(context, currentPlaylistItem, playlistPlaylists)
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TrackCoverImage(
                    filePath = track.filePath,
                    modifier = Modifier.size(44.dp),
                    backgroundColor = CyberGrey,
                    placeholderIcon = Icons.Default.Speaker,
                    iconSize = 24.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} • ${track.album}",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Año: ${if (track.year == "Desconocido") "N/A" else track.year} • ${track.durationText}",
                        color = CyberTeal.copy(alpha = 0.8f),
                        fontSize = 10.sp
                    )
                }
            }

            val favorites by viewModel.favoriteTracks.collectAsStateWithLifecycle()
            val isFav = favorites.any { it.filePath == track.filePath }
            IconButton(
                onClick = {
                    viewModel.toggleFavorite(
                        filePath = track.filePath,
                        fileName = track.title,
                        isLocal = true,
                        artist = track.artist
                    )
                }
            ) {
                Icon(
                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (isFav) WarningHotPink else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun FavoritesDetailsScreen(viewModel: FtpViewModel) {
    val context = LocalContext.current
    val favorites by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Mis Canciones Favoritas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
            Text("Acceso ultra rápido a tus pistas musicales preferidas locales o remotas.", fontSize = 12.sp, color = Color.Gray)
        }

        if (favorites.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = null, tint = CyberGrey, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No tienes favoritos guardados", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Toca el icono de corazón en la reproducción para guardar música.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        } else {
            items(favorites) { fav ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val playlistItem = PlaylistItem(
                            id = 0,
                            playlistId = -1,
                            fileName = fav.fileName,
                            filePath = fav.filePath,
                            fileSize = 0
                        )
                        AudioPlayerManager.playTrack(context, playlistItem)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (fav.isLocal) Icons.Default.Phonelink else Icons.Default.Cloud,
                                contentDescription = null,
                                tint = CyberTeal,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(fav.fileName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(if (fav.isLocal) "Móvil Local" else "Servidor Remoto", color = Color.Gray, fontSize = 11.sp)
                            }
                        }

                        IconButton(onClick = { viewModel.toggleFavorite(fav.filePath, fav.fileName, fav.isLocal) }) {
                            Icon(Icons.Default.Favorite, contentDescription = "Borrar de Favoritos", tint = WarningHotPink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EqualizerDetailsScreen() {
    val isEqEnabled by AudioPlayerManager.isEqEnabled.collectAsStateWithLifecycle()
    val bands by AudioPlayerManager.equalizerBands.collectAsStateWithLifecycle()
    val boostLevel by AudioPlayerManager.boostLevel.collectAsStateWithLifecycle()
    val systemVolume by AudioPlayerManager.systemVolume.collectAsStateWithLifecycle()
    val maxSystemVolume by AudioPlayerManager.maxSystemVolume.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Ecualizador & Aumento del Volumen", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
            Text("Mejora la ganancia acústica de tu dispositivo y personaliza el sonido de las pistas multimedia.", fontSize = 12.sp, color = Color.Gray)
        }

        // System Volume Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = CyberTeal)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Volumen del Sistema", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text("$systemVolume / $maxSystemVolume", color = CyberTeal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = systemVolume.toFloat(),
                        onValueChange = { AudioPlayerManager.setSystemVolume(it.roundToInt()) },
                        valueRange = 0f..maxSystemVolume.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = CyberTeal,
                            activeTrackColor = CyberTeal,
                            inactiveTrackColor = CyberCharcoal
                        )
                    )
                }
            }
        }

        // Volume Booster (Loudness Enhancer)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = WarningHotPink)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Amplificador de Potencia (Boost MX)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text(
                            text = "+${(boostLevel / 2000f * 100).roundToInt()}%",
                            color = WarningHotPink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Incrementa el decibelio del volumen nativo hasta el doble permitido por hardware.",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Slider(
                        value = boostLevel / 2000f,
                        onValueChange = { AudioPlayerManager.setAudioBoost(it) },
                        colors = SliderDefaults.colors(
                            thumbColor = WarningHotPink,
                            activeTrackColor = WarningHotPink,
                            inactiveTrackColor = CyberCharcoal
                        )
                    )

                    // Alert limit note if boost level is raised high
                    if (boostLevel > 800f) {
                        Text(
                            text = "⚠ ¡Atención! Los aumentos extremos de ganancia pueden distorsionar los altavoces de tu móvil.",
                            color = WarningHotPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Personalized Graphic Equalizer Config Screen
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Personalizar Bandas EQ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Switch(
                            checked = isEqEnabled,
                            onCheckedChange = { AudioPlayerManager.setEqualizerEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberDark,
                                checkedTrackColor = CyberTeal,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = CyberCharcoal
                            ),
                            modifier = Modifier.testTag("eq_switch")
                        )
                    }

                    if (!isEqEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Activa el interruptor para ajustar las frecuencias acústicas",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    } else if (bands.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Cargando ecualizador nativo...",
                                color = CyberTeal,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Graphic Equalizer quick preset selections
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { AudioPlayerManager.setSubwooferPreset() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGrey),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Mega Bass", fontSize = 10.sp, color = CyberTeal)
                            }
                            Button(
                                onClick = { AudioPlayerManager.setVocalPreset() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGrey),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Vocal", fontSize = 10.sp, color = CyberTeal)
                            }
                            Button(
                                onClick = { AudioPlayerManager.setFlatPreset() },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberGrey),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Plana", fontSize = 10.sp, color = CyberTeal)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Vertically Stacked Slider controls
                        bands.forEach { band ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val freqDisplay = if (band.centerFrequencyHz >= 1000) {
                                        "${(band.centerFrequencyHz / 1000f).roundToInt()} kHz"
                                    } else {
                                        "${band.centerFrequencyHz} Hz"
                                    }
                                    Text(freqDisplay, color = Color.LightGray, fontSize = 12.sp)
                                    val dbValue = (band.currentLevelMb / 100f).roundToInt()
                                    Text("${if (dbValue > 0) "+" else ""}$dbValue dB", color = CyberTeal, fontSize = 12.sp)
                                }
                                Slider(
                                    value = band.currentLevelMb.toFloat(),
                                    onValueChange = { AudioPlayerManager.setBandGain(band.bandId, it.roundToInt()) },
                                    valueRange = band.minLevelMb.toFloat()..band.maxLevelMb.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = CyberTeal,
                                        activeTrackColor = CyberTeal,
                                        inactiveTrackColor = CyberCharcoal
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsDetailsScreen(viewModel: FtpViewModel) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistNameInput by remember { mutableStateOf("") }
    
    var activeExpandedPlaylist by remember { mutableStateOf<Playlist?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Listas de Reproducción", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Crear", tint = CyberTeal)
                    }
                }
            }

            if (playlists.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FeaturedPlayList, contentDescription = null, tint = CyberGrey, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No tienes listas guardadas", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Crea una lista para comenzar a agrupar tus pistas FTP.", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            } else {
                items(playlists) { playlist ->
                    val isExpanded = activeExpandedPlaylist?.id == playlist.id
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // Header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeExpandedPlaylist = if (isExpanded) null else playlist
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = if (isExpanded) CyberTeal else Color.Gray,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(playlist.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.deletePlaylist(playlist) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Borrar", tint = WarningHotPink)
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color.Gray
                                    )
                                }
                            }

                            // Dynamic Track items in expand panel
                            if (isExpanded) {
                                PlaylistTracksLoader(viewModel, playlist)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            Dialog(onDismissRequest = { showCreateDialog = false }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Nueva Playlist", fontWeight = FontWeight.Bold, color = CyberTeal)
                        OutlinedTextField(
                            value = playlistNameInput,
                            onValueChange = { playlistNameInput = it },
                            label = { Text("Nombre de la lista") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberTeal, focusedLabelColor = CyberTeal
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showCreateDialog = false }) {
                                Text("CANCELAR", color = Color.Gray)
                            }
                            Button(
                                onClick = {
                                    if (playlistNameInput.isNotEmpty()) {
                                        viewModel.createPlaylist(playlistNameInput)
                                        playlistNameInput = ""
                                        showCreateDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark)
                            ) {
                                Text("CREAR")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistTracksLoader(viewModel: FtpViewModel, playlist: Playlist) {
    val context = LocalContext.current
    val itemsFlow = remember(playlist.id) { viewModel.getItemsForPlaylist(playlist.id) }
    val items by itemsFlow.collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberDark.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp)
    ) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No hay canciones agregadas. Ve a 'Explorar' y mantén presionado un archivo de música.",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
        } else {
            items.forEachIndexed { idx, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            AudioPlayerManager.playTrack(context, item, items)
                        }
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SoftTeal, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.fileName,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { viewModel.removePlaylistItem(item) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Quitar", tint = WarningHotPink, modifier = Modifier.size(16.dp))
                    }
                }
                if (idx < items.size - 1) {
                    HorizontalDivider(color = CyberCharcoal)
                }
            }
        }
    }
}

// ---------------------- TAB 3: IMMERSIVE ACTIVE PLAYER VIEW ----------------------
@Composable
fun SoundWaveVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 14
    val heights = listOf(16.dp, 32.dp, 12.dp, 28.dp, 22.dp, 40.dp, 14.dp, 30.dp, 24.dp, 10.dp, 36.dp, 18.dp, 28.dp, 16.dp)
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "Visualizer")
        
        for (i in 0 until barCount) {
            val duration = 400 + (i * 70) % 350
            val delay = (i * 50) % 200
            
            val animatedFraction by if (isPlaying) {
                infiniteTransition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = duration, delayMillis = delay, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "BarHeight_$i"
                )
            } else {
                remember { mutableStateOf(0.15f) }
            }
            
            val maxHeight = heights[i % heights.size]
            val currentHeight = maxHeight * animatedFraction
            
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(currentHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                CyberTeal.copy(alpha = 0.4f),
                                CyberTeal
                            )
                        )
                    )
            )
        }
    }
}

data class Podmark(
    val trackName: String,
    val filePath: String,
    val positionMs: Long,
    val timestampText: String,
    val createdAt: String
)

fun getPodmarks(context: android.content.Context): List<Podmark> {
    val prefs = context.getSharedPreferences("player_podmarks", android.content.Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("podmarks_list", "[]") ?: "[]"
    val list = mutableListOf<Podmark>()
    try {
        val array = org.json.JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                Podmark(
                    trackName = obj.optString("trackName", ""),
                    filePath = obj.optString("filePath", ""),
                    positionMs = obj.optLong("positionMs", 0L),
                    timestampText = obj.optString("timestampText", "00:00"),
                    createdAt = obj.optString("createdAt", "")
                )
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

fun addPodmark(context: android.content.Context, trackName: String, filePath: String, positionMs: Long, timestampText: String): Boolean {
    val list = getPodmarks(context).toMutableList()
    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
    val currentDate = sdf.format(java.util.Date())
    list.add(Podmark(trackName, filePath, positionMs, timestampText, currentDate))
    
    val array = org.json.JSONArray()
    for (pm in list) {
        val obj = org.json.JSONObject()
        obj.put("trackName", pm.trackName)
        obj.put("filePath", pm.filePath)
        obj.put("positionMs", pm.positionMs)
        obj.put("timestampText", pm.timestampText)
        obj.put("createdAt", pm.createdAt)
        array.put(obj)
    }
    val prefs = context.getSharedPreferences("player_podmarks", android.content.Context.MODE_PRIVATE)
    return prefs.edit().putString("podmarks_list", array.toString()).commit()
}

fun deletePodmark(context: android.content.Context, index: Int): Boolean {
    val list = getPodmarks(context).toMutableList()
    if (index in list.indices) {
        list.removeAt(index)
        val array = org.json.JSONArray()
        for (pm in list) {
            val obj = org.json.JSONObject()
            obj.put("trackName", pm.trackName)
            obj.put("filePath", pm.filePath)
            obj.put("positionMs", pm.positionMs)
            obj.put("timestampText", pm.timestampText)
            obj.put("createdAt", pm.createdAt)
            array.put(obj)
        }
        val prefs = context.getSharedPreferences("player_podmarks", android.content.Context.MODE_PRIVATE)
        return prefs.edit().putString("podmarks_list", array.toString()).commit()
    }
    return false
}

fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1) {
        String.format("%.2f MB", mb)
    } else {
        String.format("%.1f KB", kb)
    }
}

@Composable
fun SliderPodmarksOverlay(
    podmarks: List<Podmark>,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0 || podmarks.isEmpty()) return
    
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(16.dp)) {
        val totalWidth = maxWidth
        val startPadding = 10.dp
        val usableWidth = totalWidth - (startPadding * 2)
        
        podmarks.forEach { pm ->
            val fraction = pm.positionMs.toFloat() / durationMs.toFloat()
            if (fraction in 0f..1f) {
                val xOffset = startPadding + (usableWidth * fraction)
                
                Box(
                    modifier = Modifier
                        .offset(x = xOffset - 9.dp, y = 0.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            onSeekTo(pm.positionMs)
                        }
                ) {
                    Text(
                        text = "📍",
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerTabSchema(viewModel: FtpViewModel, onNavigateToRecent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTrack by AudioPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by AudioPlayerManager.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by AudioPlayerManager.isBuffering.collectAsStateWithLifecycle()
    val currentPosition by AudioPlayerManager.currentPosition.collectAsStateWithLifecycle()
    val duration by AudioPlayerManager.duration.collectAsStateWithLifecycle()
    val isRecordingProtected by AudioPlayerManager.isRecordingProtectedMode.collectAsStateWithLifecycle()

    var refreshPodmarksTrigger by remember { mutableStateOf(0) }
    val currentTrackPodmarks = remember(refreshPodmarksTrigger, currentTrack?.filePath) {
        val path = currentTrack?.filePath ?: ""
        getPodmarks(context).filter { it.filePath == path }
    }

    // Smooth record/disc rotation state
    val infiniteTransition = rememberInfiniteTransition(label = "DiscRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    val onlineRadios by viewModel.onlineRadios.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (currentTrack == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = CyberGrey,
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No hay audio reproduciéndose",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    "Conéctate a tu servidor FTP y selecciona un archivo multimedia de música o abre una playlist para iniciar la transmisión.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        } else {
            val track = currentTrack!!

            Spacer(modifier = Modifier.height(8.dp))
            SoundWaveVisualizer(
                isPlaying = isPlaying,
                modifier = Modifier
                    .height(32.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))

            val isRadio = track.playlistId == -2
            if (!isRadio) {
                Spacer(modifier = Modifier.height(16.dp))
                RotatingCdPlayer(
                    filePath = track.filePath,
                    isPlaying = isPlaying,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                val matchedRadio = onlineRadios.find { it.url == track.filePath }
                val logoUri = matchedRadio?.logoUri
                Card(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .align(Alignment.CenterHorizontally),
                    colors = CardDefaults.cardColors(containerColor = CyberCharcoal),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberTeal.copy(alpha = 0.5f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!logoUri.isNullOrBlank()) {
                            AsyncImage(
                                model = logoUri,
                                contentDescription = "Logo Emisora",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = "Radio",
                                tint = CyberTeal,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Metadata Row Info with Favorite hearts button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.fileName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (track.filePath.startsWith("/")) "Dispositivo Local" else "Servidor Remoto",
                        color = CyberTeal,
                        fontSize = 12.sp,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                }

                // Heart Favorites Button
                val favorites by viewModel.favoriteTracks.collectAsStateWithLifecycle()
                val isFav = favorites.any { it.filePath == track.filePath }
                IconButton(
                    onClick = {
                        viewModel.toggleFavorite(
                            filePath = track.filePath,
                            fileName = track.fileName,
                            isLocal = track.filePath.startsWith("/"),
                            artist = "Desconocido"
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorito",
                        tint = if (isFav) WarningHotPink else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Buffering progress loader details
            if (isBuffering) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = CyberTeal, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Descargando archivo para reproducir...", color = Color.Gray, fontSize = 11.sp)
                }
            }

            // Timelines and Slider Controls
            if (isRadio) {
                val radioElapsed by AudioPlayerManager.radioElapsedTimeSec.collectAsStateWithLifecycle()
                val radioPlayPos by AudioPlayerManager.radioPlayPositionSec.collectAsStateWithLifecycle()
                val isRadioLive by AudioPlayerManager.isRadioLiveMode.collectAsStateWithLifecycle()
                val totalRecBytes by AudioPlayerManager.totalRecordedBytes.collectAsStateWithLifecycle()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header Status card (Live / Timeshift)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isRadioLive) Color.Red.copy(alpha = 0.15f) else CyberTeal.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isRadioLive) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color.Red)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "EMISIÓN EN DIRECTO",
                                    color = Color.Red,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = CyberTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "REPRODUCCIÓN EN TIMESHIFT (RETRASADO)",
                                    color = CyberTeal,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    // Timeshift Seek Slider
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SliderPodmarksOverlay(
                            podmarks = currentTrackPodmarks,
                            durationMs = maxOf(1, radioElapsed).toLong() * 1000L,
                            onSeekTo = { posMs ->
                                AudioPlayerManager.seekRadioTimeShift((posMs / 1000L).toInt())
                            }
                        )
                        Slider(
                            value = radioPlayPos.toFloat(),
                            onValueChange = { AudioPlayerManager.seekRadioTimeShift(it.toInt()) },
                            valueRange = 0f..maxOf(1f, radioElapsed.toFloat()),
                            colors = SliderDefaults.colors(
                                thumbColor = if (isRadioLive) Color.Red else CyberTeal,
                                activeTrackColor = if (isRadioLive) Color.Red else CyberTeal,
                                inactiveTrackColor = CyberCharcoal
                            )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatSec(radioPlayPos), color = Color.Gray, fontSize = 12.sp)
                            Text("Total buffer: " + formatSec(radioElapsed), color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    // MAIN TACTILE RADIO CONTROLS: REWIND, STOP, PLAY/PAUSE, FORWARD
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // REWIND 30s
                        IconButton(
                            onClick = { AudioPlayerManager.skipSeconds(-30) }
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.RotateLeft, contentDescription = "Retroceder 30 Segundos", tint = Color.LightGray, modifier = Modifier.size(24.dp))
                                Text("-30s", fontSize = 9.sp, color = CyberTeal)
                            }
                        }

                        // STOP BUTTON
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(if (isRecordingProtected) CyberGrey else CyberCharcoal, CircleShape)
                                .clickable {
                                    if (isRecordingProtected) {
                                        Toast.makeText(context, "🚫 Botón STOP bloqueado por el Modo Seguro. Desactívalo pulsando largo 3s el de abajo.", Toast.LENGTH_LONG).show()
                                    } else {
                                        AudioPlayerManager.stopRadioStream()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Parar Emisora (Cierra Buffer)",
                                tint = WarningHotPink,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // CENTRAL DYNAMIC PLAY / PAUSE TOGGLE BUTTON
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(CyberTeal, CircleShape)
                                .clickable { AudioPlayerManager.togglePlayPause(context) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = CyberDark,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        // FAST FORWARD 30s
                        IconButton(
                            onClick = { AudioPlayerManager.skipSeconds(30) }
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.RotateRight, contentDescription = "Adelantar 30 Segundos", tint = Color.LightGray, modifier = Modifier.size(24.dp))
                                Text("+30s", fontSize = 9.sp, color = CyberTeal)
                            }
                        }
                    }

                    // VOLVER AL DIRECTO / GRABAR COMO PODCAST ACTION ACTIONS ROW & RECIENTES & PODMARKS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isRadioLive) {
                            Button(
                                onClick = { AudioPlayerManager.goLiveRadio() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("DIRECTO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                            }
                        }

                        Button(
                            onClick = {
                                val success = AudioPlayerManager.saveRadioRecording(context)
                                if (success) {
                                    Toast.makeText(context, "Transmisión guardada como Podcast", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al guardar buffer", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTeal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = CyberDark, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("GUARDAR PODCAST", color = CyberDark, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        }

                        Button(
                            onClick = onNavigateToRecent,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.History, contentDescription = "Historial Recientes", tint = CyberTeal, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RECIENTES", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            val currentPosMs = radioPlayPos.toLong() * 1000L
                            val timestampTxt = formatSec(radioPlayPos)
                            val added = addPodmark(context, track.fileName, track.filePath, currentPosMs, timestampTxt)
                            if (added) {
                                refreshPodmarksTrigger++
                                Toast.makeText(context, "📍 Podmark guardado a las $timestampTxt", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Crear Podmark", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CREAR PODMARK (MEJOR MOMENTO) 📍", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Grabado en buffer: ${String.format("%.2f", totalRecBytes.toFloat() / 1024f / 1024f)} MB",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                        if (!isRadioLive) {
                            Text(
                                text = "Timeshift Activo ⏸️",
                                color = CyberTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // MODO SEGURO (SAFE SECTOR LOCK) CARD CONTROL
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isRecordingProtected) CyberTeal.copy(alpha = 0.15f) else CyberSurface
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(isRecordingProtected) {
                                if (isRecordingProtected) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitFirstDown()
                                            var releasedOrCancelled = false
                                            val startTime = System.currentTimeMillis()
                                            val job = scope.launch {
                                                kotlinx.coroutines.delay(3000)
                                                if (!releasedOrCancelled) {
                                                    AudioPlayerManager.setRecordingProtectedMode(false)
                                                    try {
                                                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                            val up = waitForUpOrCancellation()
                                            releasedOrCancelled = true
                                            job.cancel()
                                            if (up != null) {
                                                val elapsed = System.currentTimeMillis() - startTime
                                                if (elapsed < 3000) {
                                                    scope.launch(Dispatchers.Main) {
                                                        Toast.makeText(context, "⚠️ Mantén pulsado durante 3 segundos para desactivar el Modo Seguro.", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    scope.launch(Dispatchers.Main) {
                                                        Toast.makeText(context, "🔓 Modo Seguro DESACTIVADO", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    detectTapGestures(
                                        onTap = {
                                            AudioPlayerManager.setRecordingProtectedMode(true)
                                            Toast.makeText(context, "🔒 Modo Seguro ACTIVO", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            },
                        border = if (isRecordingProtected) {
                            androidx.compose.foundation.BorderStroke(2.dp, CyberTeal)
                        } else {
                            androidx.compose.foundation.BorderStroke(1.dp, CyberCharcoal)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (isRecordingProtected) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = if (isRecordingProtected) CyberTeal else Color.Gray,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (isRecordingProtected) "Modo Seguro: ACTIVO 🔒" else "Modo Seguro: DESACTIVADO 🔓",
                                        color = if (isRecordingProtected) CyberTeal else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = if (isRecordingProtected) "Bloqueo de cambios de emisora activo para proteger la grabación." else "Previene interrupciones accidentales si tocas otra canción o stream.",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Switch(
                                checked = isRecordingProtected,
                                onCheckedChange = { check ->
                                    if (check) {
                                        AudioPlayerManager.setRecordingProtectedMode(true)
                                        Toast.makeText(context, "🔒 Modo Seguro activado", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "⚠️ Mantén pulsado la tarjeta de Modo Seguro durante 3 segundos para desactivar.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberDark,
                                    checkedTrackColor = CyberTeal,
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = CyberCharcoal
                                )
                            )
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val formattedPosition = formatMs(currentPosition)
                    val formattedDuration = formatMs(duration)

                    SliderPodmarksOverlay(
                        podmarks = currentTrackPodmarks,
                        durationMs = duration,
                        onSeekTo = { posMs ->
                            AudioPlayerManager.seekTo(posMs)
                        }
                    )
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { AudioPlayerManager.seekTo(it.toLong()) },
                        valueRange = 0f..if (duration > 0) duration.toFloat() else 1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberTeal,
                            activeTrackColor = CyberTeal,
                            inactiveTrackColor = CyberCharcoal
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formattedPosition, color = Color.Gray, fontSize = 12.sp)
                        Text(formattedDuration, color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Standard Media control triggers with -10s / +30s buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle logic
                    val isShuffleEnabled by AudioPlayerManager.isShuffleEnabled.collectAsStateWithLifecycle()
                    IconButton(onClick = { AudioPlayerManager.toggleShuffle() }) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Aleatorio",
                            tint = if (isShuffleEnabled) CyberTeal else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Rewind 30 Seconds
                    IconButton(
                        onClick = { AudioPlayerManager.skipSeconds(-30) }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.RotateLeft, contentDescription = "Retroceder 30 Segundos", tint = Color.LightGray, modifier = Modifier.size(22.dp))
                            Text("-30s", fontSize = 8.sp, color = CyberTeal)
                        }
                    }

                    IconButton(
                        onClick = { AudioPlayerManager.playPrevious(context) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior", tint = Color.White, modifier = Modifier.size(28.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(CyberTeal, CircleShape)
                            .clickable { AudioPlayerManager.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = CyberDark,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { AudioPlayerManager.playNext(context) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Siguiente", tint = Color.White, modifier = Modifier.size(28.dp))
                    }

                    // Fast Forward 30 Seconds
                    IconButton(
                        onClick = { AudioPlayerManager.skipSeconds(30) }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Adelantar 30 Segundos", tint = Color.LightGray, modifier = Modifier.size(22.dp))
                            Text("+30s", fontSize = 8.sp, color = CyberTeal)
                        }
                    }

                    // Repeat Mode Button
                    val repeatMode by AudioPlayerManager.repeatMode.collectAsStateWithLifecycle()
                    val repeatIcon = when (repeatMode) {
                        PlaybackRepeatMode.ONE -> Icons.Default.RepeatOne
                        else -> Icons.Default.Repeat
                    }
                    val repeatTint = when (repeatMode) {
                        PlaybackRepeatMode.NONE -> Color.Gray
                        else -> CyberTeal
                    }
                    IconButton(onClick = { AudioPlayerManager.toggleRepeatMode() }) {
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = "Repetir",
                            tint = repeatTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val timestampTxt = formatMs(currentPosition)
                            val added = addPodmark(context, track.fileName, track.filePath, currentPosition, timestampTxt)
                            if (added) {
                                refreshPodmarksTrigger++
                                Toast.makeText(context, "📍 Podmark guardado a las $timestampTxt", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Crear Podmark", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CREAR PODMARK 📍", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }

                    Button(
                        onClick = onNavigateToRecent,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.History, contentDescription = "Recientes", tint = CyberTeal, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("RECIENTES ⌛", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}

fun formatSec(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}


// ---------------------- 4. FULL-SCREEN PROTO VIEWER OVERLAY ----------------------
@Composable
fun FullScreenPhotoViewer(
    localFile: File?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = CyberTeal)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Cargando foto con el servidor FTP...", color = Color.Gray, fontSize = 11.sp)
            }
        } else if (localFile != null && localFile.exists()) {
            AsyncImage(
                model = localFile,
                contentDescription = "Visualización de FTP",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        } else {
            Text("Imposible visualizar la vista de este archivo.", color = WarningHotPink)
        }

        // Close Floating Button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
        }
    }
}

@Composable
fun RadiosOnlineDetailsScreen(viewModel: FtpViewModel) {
    val context = LocalContext.current
    val onlineRadios by viewModel.onlineRadios.collectAsStateWithLifecycle()
    val currentTrack by AudioPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by AudioPlayerManager.isPlaying.collectAsStateWithLifecycle()

    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var logoUriInput by remember { mutableStateOf<String?>(null) }

    var radioToDelete by remember { mutableStateOf<OnlineRadio?>(null) }
    var radioToEdit by remember { mutableStateOf<OnlineRadio?>(null) }
    var editNameInput by remember { mutableStateOf("") }
    var editUrlInput by remember { mutableStateOf("") }
    var editLogoUriInput by remember { mutableStateOf<String?>(null) }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            logoUriInput = it.toString()
        }
    }

    val editLogoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            editLogoUriInput = it.toString()
        }
    }

    if (radioToEdit != null) {
        AlertDialog(
            onDismissRequest = { radioToEdit = null },
            title = {
                Text(
                    text = "Editar Emisora 📻",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editNameInput,
                        onValueChange = { editNameInput = it },
                        label = { Text("Nombre de la Radio") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberTeal,
                            focusedLabelColor = CyberTeal,
                            unfocusedBorderColor = CyberCharcoal,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editUrlInput,
                        onValueChange = { editUrlInput = it },
                        label = { Text("URL de la Emisión") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberTeal,
                            focusedLabelColor = CyberTeal,
                            unfocusedBorderColor = CyberCharcoal,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Logo de la Emisora:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { editLogoPickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal, contentColor = CyberTeal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (editLogoUriInput.isNullOrBlank()) "Elegir Logo (Teléfono)" else "Cambiar Logo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (!editLogoUriInput.isNullOrBlank()) {
                            AsyncImage(
                                model = editLogoUriInput,
                                contentDescription = "Edit Logo Preview",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, CyberTeal, RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { editLogoUriInput = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Quitar logo", tint = WarningHotPink, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        radioToEdit?.let { old ->
                            if (editNameInput.isNotBlank() && editUrlInput.isNotBlank()) {
                                viewModel.editOnlineRadio(old, editNameInput.trim(), editUrlInput.trim(), editLogoUriInput)
                                Toast.makeText(context, "Emisora sintonizada y actualizada", Toast.LENGTH_SHORT).show()
                            }
                        }
                        radioToEdit = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { radioToEdit = null }
                ) {
                    Text("Cancelar", color = CyberTeal, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CyberDark,
            textContentColor = Color.White,
            titleContentColor = Color.White
        )
    }

    if (radioToDelete != null) {
        AlertDialog(
            onDismissRequest = { radioToDelete = null },
            title = {
                Text(
                    text = "Confirmar eliminación",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "¿Estás seguro de que deseas eliminar la emisora llamada \"${radioToDelete?.name}\"?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        radioToDelete?.let { radio ->
                            viewModel.removeOnlineRadio(radio)
                            Toast.makeText(context, "Emisora eliminada", Toast.LENGTH_SHORT).show()
                        }
                        radioToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Eliminar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { radioToDelete = null }
                ) {
                    Text("Cancelar", color = CyberTeal, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CyberDark,
            textContentColor = Color.White,
            titleContentColor = Color.White
        )
    }

    var showAddRadioDialog by remember { mutableStateOf(false) }

    if (showAddRadioDialog) {
        AlertDialog(
            onDismissRequest = { showAddRadioDialog = false },
            title = {
                Text(
                    text = "Añadir Nueva Emisora 📻",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Nombre de la Radio (ej: Rock FM)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberTeal,
                            focusedLabelColor = CyberTeal,
                            unfocusedBorderColor = CyberCharcoal,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("radio_name_input")
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL de la Emisión (http://...)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberTeal,
                            focusedLabelColor = CyberTeal,
                            unfocusedBorderColor = CyberCharcoal,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("radio_url_input")
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Logo de la Emisora (Opcional):", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { logoPickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal, contentColor = CyberTeal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (logoUriInput.isNullOrBlank()) "Elegir Logo (Teléfono)" else "Cambiar Logo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (!logoUriInput.isNullOrBlank()) {
                            AsyncImage(
                                model = logoUriInput,
                                contentDescription = "Logo Elegido Preview",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, CyberTeal, RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { logoUriInput = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Quitar logo", tint = WarningHotPink, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nameInput.isNotBlank() && urlInput.isNotBlank()) {
                            viewModel.addOnlineRadio(nameInput.trim(), urlInput.trim(), logoUriInput)
                            nameInput = ""
                            urlInput = ""
                            logoUriInput = null
                            showAddRadioDialog = false
                            Toast.makeText(context, "Emisora sintonizada correctamente", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Por favor, rellena todos los campos", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberTeal, contentColor = CyberDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nameInput = ""
                        urlInput = ""
                        logoUriInput = null
                        showAddRadioDialog = false
                    }
                ) {
                    Text("Cancelar", color = CyberTeal, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CyberDark,
            textContentColor = Color.White,
            titleContentColor = Color.White
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mis Radios Online", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyberTeal)
                    Text("Reproduce, guarda y sintoniza emisoras de internet.", fontSize = 11.sp, color = Color.Gray)
                }
                IconButton(
                    onClick = { showAddRadioDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(CyberCharcoal, RoundedCornerShape(10.dp))
                        .border(androidx.compose.foundation.BorderStroke(1.dp, CyberTeal.copy(alpha = 0.5f)), RoundedCornerShape(10.dp))
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Añadir Emisora", tint = CyberTeal)
                }
            }
        }

        // Stations List
        if (onlineRadios.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Radio, contentDescription = null, tint = CyberGrey, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No hay emisoras guardadas", color = Color.Gray, fontSize = 12.sp)
                }
            }
        } else {
            items(onlineRadios) { radio ->
                val isCurrentRadio = currentTrack?.filePath == radio.url
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentRadio) CyberCharcoal else CyberSurface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val playlistItem = PlaylistItem(
                                id = 0,
                                playlistId = -2, // web streams indicator
                                fileName = radio.name,
                                filePath = radio.url,
                                fileSize = 0L
                            )
                            AudioPlayerManager.playTrack(context, playlistItem)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            // Drag handle
                            var dragAccumulatorY by remember { mutableStateOf(0f) }
                            var hasMoved by remember { mutableStateOf(false) }

                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Mantener presionado y arrastrar para reordenar",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(24.dp)
                                    .pointerInput(radio.url) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragAccumulatorY = 0f
                                                hasMoved = false
                                            },
                                            onDragEnd = {
                                                dragAccumulatorY = 0f
                                                hasMoved = false
                                            },
                                            onDragCancel = {
                                                dragAccumulatorY = 0f
                                                hasMoved = false
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragAccumulatorY += dragAmount.y
                                                val threshold = 90f // pixels
                                                if (!hasMoved) {
                                                    if (dragAccumulatorY < -threshold) {
                                                        viewModel.moveRadioUp(radio)
                                                        hasMoved = true
                                                    } else if (dragAccumulatorY > threshold) {
                                                        viewModel.moveRadioDown(radio)
                                                        hasMoved = true
                                                    }
                                                }
                                            }
                                        )
                                      }
                            )

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCurrentRadio) CyberTeal else CyberCharcoal),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!radio.logoUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = radio.logoUri,
                                        contentDescription = "Logo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isCurrentRadio && isPlaying) Icons.Default.VolumeUp else Icons.Default.Radio,
                                        contentDescription = null,
                                        tint = if (isCurrentRadio) CyberDark else Color.LightGray
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = radio.name,
                                    color = if (isCurrentRadio) CyberTeal else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = radio.url,
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    radioToEdit = radio
                                    editNameInput = radio.name
                                    editUrlInput = radio.url
                                    editLogoUriInput = radio.logoUri
                                },
                                modifier = Modifier.testTag("edit_radio_${radio.name}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Editar",
                                    tint = CyberTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    radioToDelete = radio
                                },
                                modifier = Modifier.testTag("delete_radio_${radio.name}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Eliminar",
                                    tint = WarningHotPink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DrawerControlPanelContent(
    viewModel: FtpViewModel,
    scope: CoroutineScope,
    drawerState: DrawerState
) {
    val context = LocalContext.current
    val currentTrack by AudioPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by AudioPlayerManager.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by AudioPlayerManager.isBuffering.collectAsStateWithLifecycle()
    val currentPosition by AudioPlayerManager.currentPosition.collectAsStateWithLifecycle()
    val duration by AudioPlayerManager.duration.collectAsStateWithLifecycle()
    val boostLevel by AudioPlayerManager.boostLevel.collectAsStateWithLifecycle()

    val prefs = remember { context.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE) }
    var bufferLimitMins by remember { mutableStateOf(prefs.getInt("radio_buffer_limit_minutes", 120)) }
    var recordingPath by remember { mutableStateOf(prefs.getString("recording_destination_dir", "") ?: "") }

    var isSettingsExpanded by remember { mutableStateOf(false) }
    var isPodcastsExpanded by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    val onlineRadios by viewModel.onlineRadios.collectAsStateWithLifecycle()

    val targetDir = if (recordingPath.isNotBlank()) {
        java.io.File(recordingPath)
    } else {
        java.io.File(context.filesDir, "grabaciones")
    }

    var recordedFilesList by remember(recordingPath, isPodcastsExpanded) {
        mutableStateOf(
            if (targetDir.exists() && targetDir.isDirectory) {
                targetDir.listFiles { _, name -> name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() } ?: emptyList()
            } else {
                emptyList()
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolvedPath = getPathFromDocumentTreeUri(uri)
            if (resolvedPath != null) {
                recordingPath = resolvedPath
                prefs.edit().putString("recording_destination_dir", resolvedPath).apply()
                val newTarget = java.io.File(resolvedPath)
                recordedFilesList = if (newTarget.exists() && newTarget.isDirectory) {
                    newTarget.listFiles { _, name -> name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() } ?: emptyList()
                } else {
                    emptyList()
                }
                android.widget.Toast.makeText(context, "Directorio: $resolvedPath", android.widget.Toast.LENGTH_LONG).show()
            } else {
                val uriStr = uri.toString()
                recordingPath = uriStr
                prefs.edit().putString("recording_destination_dir", uriStr).apply()
                android.widget.Toast.makeText(context, "URI guardado", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Drawer Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AudioFile, contentDescription = null, tint = CyberTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SISTEMA DE AUDIO",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp
                )
            }
            IconButton(onClick = { scope.launch { drawerState.close() } }) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar menú panel", tint = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Reproductor y Potenciador de Graves integrado en la barra deslizante.",
            color = Color.Gray,
            fontSize = 10.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Mini Media Player Panel (Sliding controller)
        Text("REPRODUCCIÓN ACTUAL", color = CyberTeal, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentTrack != null) {
                    val isRadio = currentTrack!!.playlistId == -2
                    if (isRadio) {
                        val matchedRadio = onlineRadios.find { it.url == currentTrack!!.filePath }
                        val logoUri = matchedRadio?.logoUri
                        if (!logoUri.isNullOrBlank()) {
                            AsyncImage(
                                model = logoUri,
                                contentDescription = "Logo Emisora",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyberCharcoal),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            TrackCoverImage(
                                filePath = currentTrack!!.filePath,
                                modifier = Modifier.size(56.dp),
                                backgroundColor = CyberGrey,
                                placeholderIcon = Icons.Default.Radio,
                                iconSize = 36.dp
                            )
                        }
                    } else {
                        TrackCoverImage(
                            filePath = currentTrack!!.filePath,
                            modifier = Modifier.size(56.dp),
                            backgroundColor = CyberGrey,
                            placeholderIcon = Icons.Default.MusicNote,
                            iconSize = 36.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = currentTrack!!.fileName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (currentTrack!!.playlistId == -2) "Radio En Linea Streaming" else "Pista Local/FTP",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isBuffering) {
                        CircularProgressIndicator(color = CyberTeal, modifier = Modifier.size(20.dp))
                    } else if (currentTrack!!.playlistId != -2) {
                        // Position Seek representation (Not on streams)
                        val positionMinutes = (currentPosition / 1000) / 60
                        val positionSeconds = (currentPosition / 1000) % 60
                        val durationMinutes = (duration / 1000) / 60
                        val durationSeconds = (duration / 1000) % 60

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                String.format("%02d:%02d", positionMinutes, positionSeconds),
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                            Text(
                                String.format("%02d:%02d", durationMinutes, durationSeconds),
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }

                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() else 0f,
                            onValueChange = { AudioPlayerManager.seekTo(it.toLong()) },
                            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                            colors = SliderDefaults.colors(
                                thumbColor = CyberTeal,
                                activeTrackColor = CyberTeal,
                                inactiveTrackColor = CyberCharcoal
                            )
                        )
                    } else {
                        // Web streaming status indicators
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlaying) Color.Green else Color.Red)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isPlaying) "SINTONIZANDO EN VIVO" else "SINTONÍA EN PAUSA",
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Player buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { AudioPlayerManager.playPrevious(context) }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior", tint = Color.White)
                        }
                        IconButton(
                            onClick = { AudioPlayerManager.togglePlayPause() },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(CyberTeal)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Reproducir / Pausar",
                                tint = CyberDark
                            )
                        }
                        IconButton(onClick = { AudioPlayerManager.playNext(context) }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente", tint = Color.White)
                        }
                    }

                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = CyberGrey, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No hay reproducción activa", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sound Booster Section
        Text("DOCK GAIN (BOOST MX)", color = WarningHotPink, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = WarningHotPink)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Amplificador Pro", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text(
                        text = "+${(boostLevel / 2000f * 100).roundToInt()}%",
                        color = WarningHotPink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = boostLevel / 2000f,
                    onValueChange = { AudioPlayerManager.setAudioBoost(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = WarningHotPink,
                        activeTrackColor = WarningHotPink,
                        inactiveTrackColor = CyberCharcoal
                    )
                )

                if (boostLevel > 800f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠ Aumentos altos pueden distorsionar o sobrecalentar los altavoces de tu terminal.",
                        color = WarningHotPink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Card 3: AJUSTES Y OPTIMIZACIÓN GLOBLALES
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isSettingsExpanded = !isSettingsExpanded }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = CyberTeal)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "AJUSTES DEL BUFFER Y PORTABLES",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Icon(
                        imageVector = if (isSettingsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isSettingsExpanded) "Contraer" else "Expandir",
                        tint = Color.LightGray
                    )
                }

                if (isSettingsExpanded) {
                    HorizontalDivider(color = CyberCharcoal, modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        "Límite del Buffer de Radio",
                        color = CyberTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        "Define el tamaño máximo en minutos para el archivo temporal de Timeshift para prevenir saturación de la memoria física.",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val limits = listOf(30, 60, 120, 240)
                        limits.forEach { limit ->
                            val isSelected = bufferLimitMins == limit
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSelected) CyberTeal else CyberCharcoal,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        bufferLimitMins = limit
                                        prefs.edit().putInt("radio_buffer_limit_minutes", limit).apply()
                                        android.widget.Toast.makeText(context, "Buffer de Radio establecido en $limit minutos", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${limit}m",
                                    color = if (isSelected) CyberDark else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Ruta de Podcasts y Grabaciones",
                        color = CyberTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        "Escribe o selecciona gráficamente el destino absoluto para salvar de forma portable los fragmentos grabados.",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = if (recordingPath.isEmpty()) "Carpeta de Grabaciones (Predeterminado)" else recordingPath,
                            onValueChange = {
                                recordingPath = it
                                prefs.edit().putString("recording_destination_dir", it).apply()
                            },
                            readOnly = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberTeal,
                                unfocusedBorderColor = CyberCharcoal,
                                focusedContainerColor = CyberDark,
                                unfocusedContainerColor = CyberDark
                            )
                        )

                        IconButton(
                            onClick = { launcher.launch(null) },
                            modifier = Modifier
                                .size(48.dp)
                                .background(CyberCharcoal, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Elegir Carpeta", tint = CyberTeal)
                        }
                    }

                    if (recordingPath.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                recordingPath = ""
                                prefs.edit().putString("recording_destination_dir", "").apply()
                                android.widget.Toast.makeText(context, "Ruta restaurada por defecto", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Restaurar Carpeta Interna", color = WarningHotPink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 4: MIS PODCASTS / GRABACIONES DIRECTOS INDEPENDIENTES
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isPodcastsExpanded = !isPodcastsExpanded }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, tint = WarningHotPink)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "MIS PODCASTS GRABADOS",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Icon(
                        imageVector = if (isPodcastsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isPodcastsExpanded) "Contraer" else "Expandir",
                        tint = Color.LightGray
                    )
                }

                if (isPodcastsExpanded) {
                    HorizontalDivider(color = CyberCharcoal, modifier = Modifier.padding(vertical = 8.dp))

                    if (recordedFilesList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, tint = CyberCharcoal, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("No hay podcasts grabados en este directorio", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            recordedFilesList.forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CyberDark, RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${String.format("%.2f", file.length() / (1024f * 1024f))} MB",
                                            color = CyberTeal,
                                            fontSize = 10.sp
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                val playItem = com.example.data.model.PlaylistItem(
                                                    id = 0,
                                                    playlistId = -3,
                                                    fileName = file.name,
                                                    filePath = file.absolutePath,
                                                    fileSize = file.length(),
                                                    durationText = "Podcast Rec"
                                                )
                                                AudioPlayerManager.playTrack(context, playItem)
                                                scope.launch { drawerState.close() }
                                                android.widget.Toast.makeText(context, "Reproduciendo grabaciones: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = CyberTeal, modifier = Modifier.size(20.dp))
                                        }

                                        IconButton(
                                            onClick = {
                                                try {
                                                    if (file.delete()) {
                                                        val newTarget = java.io.File(targetDir.absolutePath)
                                                        recordedFilesList = if (newTarget.exists() && newTarget.isDirectory) {
                                                            newTarget.listFiles { _, name -> name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() } ?: emptyList()
                                                        } else {
                                                            emptyList()
                                                        }
                                                        android.widget.Toast.makeText(context, "Grabación eliminada del disco", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        android.widget.Toast.makeText(context, "Error al eliminar archivo", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = WarningHotPink, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Footer button inside drawer
        Button(
            onClick = { scope.launch { drawerState.close() } },
            colors = ButtonDefaults.buttonColors(containerColor = CyberCharcoal, contentColor = Color.White),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cerrar Panel Lateral", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Exit / Close Application Button
        Button(
            onClick = {
                showExitConfirmDialog = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink.copy(alpha = 0.2f), contentColor = WarningHotPink),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, WarningHotPink),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Cerrar Aplicación", tint = WarningHotPink, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cerrar Aplicación", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        if (showExitConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showExitConfirmDialog = false },
                title = { Text("⚠️ Cerrar Aplicación", color = WarningHotPink, fontWeight = FontWeight.Bold) },
                text = { Text("¿Estás seguro de que quieres salir por completo y cerrar la aplicación?", color = Color.LightGray) },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitConfirmDialog = false
                            (context as? android.app.Activity)?.finishAndRemoveTask()
                            java.lang.System.exit(0)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WarningHotPink)
                    ) {
                        Text("Cerrar", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirmDialog = false }) {
                        Text("Cancelar", color = Color.Gray)
                    }
                },
                containerColor = CyberSurface
            )
        }
    }
}

// --- EXTRACTOR DE METADATOS MP3 Y CARÁTULAS DE AUDIO EN TIEMPO REAL ---

fun getMp3Cover(context: android.content.Context, path: String): android.graphics.Bitmap? {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        if (path.startsWith("content://")) {
            val uri = android.net.Uri.parse(path)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            }
        } else if (path.startsWith("ftp://")) {
            val hash = path.hashCode()
            val coverCacheFile = java.io.File(context.cacheDir, "ftp_cover_$hash.mp3")
            if (coverCacheFile.exists() && coverCacheFile.length() > 0) {
                retriever.setDataSource(coverCacheFile.absolutePath)
            } else {
                val parts = path.substring(6).split("/", limit = 2)
                val ftpConnectionId = parts.getOrNull(0)?.toIntOrNull()
                val remoteSubPath = "/" + (parts.getOrNull(1) ?: "")
                if (ftpConnectionId != null) {
                    kotlinx.coroutines.runBlocking {
                        try {
                            val db = com.example.data.database.AppDatabase.getDatabase(context)
                            val targetConn = db.ftpConnectionDao().getAllConnectionsOnce().firstOrNull { it.id == ftpConnectionId }
                            if (targetConn != null) {
                                com.example.data.repository.FtpClientManager.connect(targetConn)
                                com.example.data.repository.FtpClientManager.downloadPartialFileToCache(
                                    remotePath = remoteSubPath,
                                    localFile = coverCacheFile,
                                    maxBytes = 750 * 1024L
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("RotatingCdPlayer", "Error loading FTP cover: ${e.message}")
                        }
                    }
                }
                if (coverCacheFile.exists() && coverCacheFile.length() > 0) {
                    retriever.setDataSource(coverCacheFile.absolutePath)
                } else {
                    val cachedFile = java.io.File(context.cacheDir, "current_playing_media.mp3")
                    if (cachedFile.exists() && cachedFile.length() > 0) {
                        retriever.setDataSource(cachedFile.absolutePath)
                    } else {
                        return null
                    }
                }
            }
        } else {
            val f = java.io.File(path)
            if (f.exists() && f.canRead()) {
                retriever.setDataSource(path)
            } else {
                return null
            }
        }
        val art = retriever.embeddedPicture
        if (art != null) {
            android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {}
    }
}

@Composable
fun TrackCoverImage(
    filePath: String?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = CyberGrey,
    placeholderIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.MusicNote,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var coverBitmap by remember(filePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(filePath) {
        if (filePath != null && (filePath.startsWith("/") || filePath.startsWith("content://") || filePath.startsWith("ftp://"))) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (filePath.startsWith("ftp://")) {
                    kotlinx.coroutines.delay(2000)
                }
                coverBitmap = getMp3Cover(context, filePath)
            }
        } else {
            coverBitmap = null
        }
    }
    
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (coverBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = coverBitmap!!.asImageBitmap(),
                contentDescription = "Carátula del Álbum",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = CyberTeal,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
fun RotatingCdPlayer(
    filePath: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var coverBitmap by remember(filePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(filePath) {
        if (filePath != null && (filePath.startsWith("/") || filePath.startsWith("content://") || filePath.startsWith("ftp://"))) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (filePath.startsWith("ftp://")) {
                    kotlinx.coroutines.delay(1000)
                }
                coverBitmap = getMp3Cover(context, filePath)
            }
        } else {
            coverBitmap = null
        }
    }

    var rotationAngle by remember { mutableStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var lastTime = System.nanoTime()
            while (isPlaying) {
                try {
                    androidx.compose.runtime.withFrameNanos { frameTimeNanos ->
                        val elapsedSec = (frameTimeNanos - lastTime) / 1_000_000_000f
                        val delta = if (elapsedSec in 0f..0.1f) elapsedSec else 0.016f
                        lastTime = frameTimeNanos
                        rotationAngle = (rotationAngle + delta * 35f) % 360f
                    }
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(16)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .size(220.dp)
            .shadow(12.dp, CircleShape)
            .background(CyberSurface, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // 1. ROTATING INNER DISC (Artwork / holographic label background)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(rotationZ = rotationAngle)
                .clip(CircleShape)
        ) {
            if (coverBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = coverBitmap!!.asImageBitmap(),
                    contentDescription = "Carátula del CD",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Technical CD fallback
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(CyberCharcoal, CyberSurface, CyberDark)
                            )
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.85f)
                            .align(Alignment.Center)
                            .border(1.5.dp, CyberTeal.copy(alpha = 0.4f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.7f)
                            .align(Alignment.Center)
                            .border(1.1.dp, CyberTeal.copy(alpha = 0.2f), CircleShape)
                    )
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = CyberTeal.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(54.dp)
                            .align(Alignment.Center)
                    )
                }
            }

            // Rainbow sweep decoration showing metallic CD reflection values
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent,
                                CyberTeal.copy(alpha = 0.12f),
                                Color.Transparent,
                                WarningHotPink.copy(alpha = 0.12f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // CD grooves details
            Box(
                modifier = Modifier
                    .fillMaxSize(0.92f)
                    .align(Alignment.Center)
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(0.82f)
                    .align(Alignment.Center)
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(0.72f)
                    .align(Alignment.Center)
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            )
        }

        // 2. STATIC GLARE/GLOSS LAYER (Stays fixed as the CD spins underneath)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.02f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    )
                )
        )

        // 3. CENTER HUB (CD Clamping/Plastic Center Ring)
        Box(
            modifier = Modifier
                .size(62.dp)
                .align(Alignment.Center)
                .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .border(0.8.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            )
        }

        // Spindle clear hole
        Box(
            modifier = Modifier
                .size(24.dp)
                .align(Alignment.Center)
                .background(CyberDark, CircleShape)
                .border(2.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
        )
    }
}

