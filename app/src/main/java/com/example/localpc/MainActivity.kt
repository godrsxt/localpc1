package com.example.localpc

import android.Manifest
import android.app.Presentation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// --- Data Models ---
data class VideoItem(val id: Long, val name: String, val uri: Uri, val durationMs: Long, val folderName: String)
data class FolderItem(val name: String, val videos: List<VideoItem>)

// --- State Holders for the Bridge ---
object PresentationBridge {
    var current: CastPresentation? by mutableStateOf(null)
    
    // Remote Control States synced from TV
    var currentVideoTitle by mutableStateOf("No Video Playing")
    var isPlaying by mutableStateOf(false)
    var currentTime by mutableStateOf(0.0)
    var totalDuration by mutableStateOf(0.0)
    var volume by mutableStateOf(1.0f) // 0.0 to 1.0
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
        setupSecondaryDisplayScanner()
    }

    private fun setupSecondaryDisplayScanner() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { updateTVDisplay() }
            override fun onDisplayRemoved(displayId: Int) { updateTVDisplay() }
            override fun onDisplayChanged(displayId: Int) { updateTVDisplay() }
        }
        displayManager.registerDisplayListener(displayListener, null)
        updateTVDisplay()
    }

    private fun updateTVDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (displays.isEmpty()) {
            PresentationBridge.current?.dismiss()
            PresentationBridge.current = null
        } else {
            val display = displays[0]
            if (PresentationBridge.current?.display?.displayId != display.displayId) {
                PresentationBridge.current?.dismiss()
                val presentation = CastPresentation(this, display)
                presentation.show()
                PresentationBridge.current = presentation
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PresentationBridge.current?.dismiss()
        PresentationBridge.current = null
    }
}

// --- Video Fetcher ---
suspend fun fetchAllVideos(context: Context): List<FolderItem> = withContext(Dispatchers.IO) {
    val videos = mutableListOf<VideoItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    )
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: "Unknown"
            val duration = cursor.getLong(durCol)
            val folder = cursor.getString(bucketCol) ?: "Internal Storage"
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

            videos.add(VideoItem(id, name, uri, duration, folder))
        }
    }
    
    // Group by folder and sort folders alphabetically
    return@withContext videos
        .groupBy { it.folderName }
        .map { FolderItem(it.key, it.value) }
        .sortedBy { it.name.lowercase() }
}

fun formatMillis(ms: Long): String {
    val hrs = TimeUnit.MILLISECONDS.toHours(ms)
    val mins = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val secs = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs) else String.format("%02d:%02d", mins, secs)
}

// --- Compose UI ---
enum class AppScreen { Folders, Videos, Remote }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var currentScreen by remember { mutableStateOf(AppScreen.Folders) }
    var folders by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf<FolderItem?>(null) }
    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasPermission = permissions.values.all { it }
        if (hasPermission) {
            coroutineScope.launch { folders = fetchAllVideos(context) }
        }
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(arrayOf(perm))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when(currentScreen) {
                    AppScreen.Folders -> "Video Library"
                    AppScreen.Videos -> selectedFolder?.name ?: "Videos"
                    AppScreen.Remote -> "TV Remote"
                }) },
                navigationIcon = {
                    if (currentScreen != AppScreen.Folders) {
                        IconButton(onClick = { 
                            currentScreen = if (currentScreen == AppScreen.Remote) AppScreen.Videos else AppScreen.Folders 
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }) {
                        Text("Cast")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!hasPermission) {
                Text("Storage permission required to scan videos.", modifier = Modifier.align(Alignment.Center))
                return@Scaffold
            }

            when (currentScreen) {
                AppScreen.Folders -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(folders) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                supportingContent = { Text("${folder.videos.size} videos") },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable {
                                    selectedFolder = folder
                                    currentScreen = AppScreen.Videos
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
                AppScreen.Videos -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(selectedFolder?.videos ?: emptyList()) { video ->
                            ListItem(
                                headlineContent = { Text(video.name, maxLines = 2) },
                                supportingContent = { Text(formatMillis(video.durationMs)) },
                                leadingContent = { Icon(Icons.Default.OndemandVideo, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    if (PresentationBridge.current != null) {
                                        PresentationBridge.currentVideoTitle = video.name
                                        PresentationBridge.current?.playVideo(video.uri.toString())
                                        currentScreen = AppScreen.Remote
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
                AppScreen.Remote -> {
                    RemoteControlScreen()
                }
            }
        }
    }
}

@Composable
fun RemoteControlScreen() {
    val presentation = PresentationBridge.current
    var isDraggingSlider by remember { mutableStateOf(false) }
    var localSliderValue by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(PresentationBridge.currentVideoTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
        Spacer(Modifier.height(32.dp))

        // Progress Slider
        val currentProgress = if (isDraggingSlider) localSliderValue else PresentationBridge.currentTime.toFloat()
        val duration = PresentationBridge.totalDuration.toFloat().takeIf { it > 0 } ?: 1f

        Slider(
            value = currentProgress,
            onValueChange = { 
                isDraggingSlider = true
                localSliderValue = it 
            },
            onValueChangeFinished = {
                isDraggingSlider = false
                presentation?.seekTo(localSliderValue.toDouble())
            },
            valueRange = 0f..duration
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMillis((currentProgress * 1000).toLong()))
            Text(formatMillis((duration * 1000).toLong()))
        }

        Spacer(Modifier.height(32.dp))

        // Playback Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = { presentation?.seekTo(currentProgress - 10.0) }) {
                Text("-10s")
            }
            
            FloatingActionButton(onClick = { 
                if (PresentationBridge.isPlaying) presentation?.pause() else presentation?.resume()
            }) {
                Icon(
                    if (PresentationBridge.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause"
                )
            }

            FilledTonalButton(onClick = { presentation?.seekTo(currentProgress + 10.0) }) {
                Text("+10s")
            }
        }

        Spacer(Modifier.height(48.dp))

        // Volume Control
        Text("Volume", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = PresentationBridge.volume,
            onValueChange = { 
                PresentationBridge.volume = it
                presentation?.setVolume(it)
            },
            valueRange = 0f..1f
        )
    }
}

// --- The JS Bridge ---
class WebAppInterface {
    @JavascriptInterface
    fun updateState(isPlaying: Boolean, currentTime: Double, duration: Double) {
        PresentationBridge.isPlaying = isPlaying
        PresentationBridge.currentTime = currentTime
        PresentationBridge.totalDuration = duration
    }
    
    @JavascriptInterface
    fun onEnded() {
        PresentationBridge.isPlaying = false
        PresentationBridge.currentTime = 0.0
    }
}

// --- TV Presentation Class ---
// STRICT CONSTRAINT: Maintained exact FrameLayout, aspect ratio, and WebView method.
class CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val aspectContainer = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }

        webView = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true // CRITICAL: Allows loading content:// URIs directly
            
            // Bridge registration
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/index.html")
        }

        aspectContainer.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        rootLayout.addView(
            aspectContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        setContentView(rootLayout)
        setupAspectRatio(aspectContainer)
    }

    private fun setupAspectRatio(container: FrameLayout) {
        val displayMetrics = android.util.DisplayMetrics()
        display.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val targetRatio = 16f / 9f

        val params = container.layoutParams as FrameLayout.LayoutParams
        if (screenRatio > targetRatio) {
            params.height = screenHeight
            params.width = (screenHeight * targetRatio).toInt()
        } else {
            params.width = screenWidth
            params.height = (screenWidth / targetRatio).toInt()
        }
        container.layoutParams = params
    }

    // Remote Commands
    fun playVideo(uriString: String) {
        webView.post {
            val escapedUri = JSONObject.quote(uriString)
            webView.evaluateJavascript("playVideo($escapedUri)", null)
        }
    }

    fun pause() {
        webView.post { webView.evaluateJavascript("pauseVideo()", null) }
    }

    fun resume() {
        webView.post { webView.evaluateJavascript("resumeVideo()", null) }
    }

    fun seekTo(seconds: Double) {
        webView.post { webView.evaluateJavascript("seekVideo($seconds)", null) }
    }

    fun setVolume(level: Float) {
        webView.post { webView.evaluateJavascript("setVolume($level)", null) }
    }

    override fun onStop() {
        super.onStop()
        webView.destroy()
    }
}
