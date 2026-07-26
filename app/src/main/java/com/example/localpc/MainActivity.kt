package com.example.localpc

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// Explicit imports for state delegates
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// --- Data Models ---
data class VideoItem(val uri: Uri, val name: String, val folder: String)

// --- Shared State & Information Bridge ---
object PresentationBridge {
    var current: CastPresentation? by mutableStateOf(null)
    
    // Remote Control States synced from TV via JS Bridge
    var currentVideoTitle by mutableStateOf("No Video Playing")
    var isPlaying by mutableStateOf(false)
    var currentTime by mutableStateOf(0.0)
    var totalDuration by mutableStateOf(0.0)
    var volume by mutableStateOf(1.0f)
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

// --- Content Information Extractor ---
fun extractVideoInfo(context: Context, uris: List<Uri>): List<VideoItem> {
    val list = mutableListOf<VideoItem>()
    for (uri in uris) {
        var name = "Unknown Video"
        var folder = "Picked Videos" // Default folder
        
        try {
            // Take persistent permission so the WebView can read the file safely
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Get File Name
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                    
                    // Attempt to get Folder (Bucket) Name from the MediaStore columns of the picker
                    val bucketIdx = cursor.getColumnIndex("bucket_display_name")
                    if (bucketIdx != -1) {
                        val bucket = cursor.getString(bucketIdx)
                        if (bucket != null) folder = bucket
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list.add(VideoItem(uri, name, folder))
    }
    return list
}

fun formatTime(seconds: Double): String {
    val totalSecs = seconds.toInt()
    val hrs = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    return if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs)
    else String.format("%02d:%02d", mins, secs)
}

// --- Compose UI ---
enum class AppScreen { Home, Folders, Videos, Remote }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(AppScreen.Home) }
    
    // Video Library Data
    var allVideos by remember { mutableStateOf(emptyList<VideoItem>()) }
    var folders by remember { mutableStateOf(emptyMap<String, List<VideoItem>>()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }

    val pickVideosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            val newVideos = extractVideoInfo(context, uris)
            // Combine old and new, ensuring no duplicates
            allVideos = (allVideos + newVideos).distinctBy { it.uri }
            // Sort into MX Player style folders
            folders = allVideos.groupBy { it.folder }
            currentScreen = AppScreen.Folders
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(when(currentScreen) {
                        AppScreen.Home -> "Local TV Caster"
                        AppScreen.Folders -> "Library Folders"
                        AppScreen.Videos -> selectedFolder ?: "Videos"
                        AppScreen.Remote -> "TV Remote"
                    }) 
                },
                actions = {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }) {
                        Text("Connect TV")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentScreen) {
                AppScreen.Home -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = { pickVideosLauncher.launch("video/*") },
                            modifier = Modifier.fillMaxWidth().height(60.dp)
                        ) { Text("Pick Videos to Library") }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        OutlinedButton(
                            onClick = { currentScreen = AppScreen.Folders },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            enabled = allVideos.isNotEmpty()
                        ) { Text("Open Video Library") }
                    }
                }
                
                AppScreen.Folders -> {
                    Column {
                        Button(onClick = { currentScreen = AppScreen.Home }, modifier = Modifier.padding(8.dp)) { Text("Back to Home") }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folders.keys.toList().sorted()) { folderName ->
                                ListItem(
                                    headlineContent = { Text(folderName, style = MaterialTheme.typography.titleMedium) },
                                    supportingContent = { Text("${folders[folderName]?.size} videos") },
                                    modifier = Modifier.clickable {
                                        selectedFolder = folderName
                                        currentScreen = AppScreen.Videos
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                AppScreen.Videos -> {
                    Column {
                        Button(onClick = { currentScreen = AppScreen.Folders }, modifier = Modifier.padding(8.dp)) { Text("Back to Folders") }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folders[selectedFolder] ?: emptyList()) { video ->
                                ListItem(
                                    headlineContent = { Text(video.name, maxLines = 2) },
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
                }

                AppScreen.Remote -> {
                    RemoteControlScreen { currentScreen = AppScreen.Videos }
                }
            }
        }
    }
}

@Composable
fun RemoteControlScreen(onBack: () -> Unit) {
    val presentation = PresentationBridge.current
    var isDraggingSlider by remember { mutableStateOf(false) }
    var localSliderValue by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onBack, modifier = Modifier.align(Alignment.Start)) { Text("Back to Library") }
        Spacer(Modifier.height(32.dp))

        Text(PresentationBridge.currentVideoTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
        Spacer(Modifier.height(32.dp))

        // Progress Slider
        val duration = PresentationBridge.totalDuration.toFloat().coerceAtLeast(1f)
        val currentProgress = if (isDraggingSlider) localSliderValue else PresentationBridge.currentTime.toFloat().coerceIn(0f, duration)

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
            Text(formatTime(currentProgress.toDouble()))
            Text(formatTime(duration.toDouble()))
        }

        Spacer(Modifier.height(32.dp))

        // Playback Controls
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { presentation?.seekTo(currentProgress - 10.0) }) { Text("-10s") }
            
            Button(onClick = { 
                if (PresentationBridge.isPlaying) presentation?.pause() else presentation?.resume()
            }, modifier = Modifier.height(56.dp)) {
                Text(if (PresentationBridge.isPlaying) "PAUSE" else "PLAY")
            }

            Button(onClick = { presentation?.seekTo(currentProgress + 10.0) }) { Text("+10s") }
        }

        Spacer(Modifier.height(48.dp))

        // Volume Control
        Text("Volume Control", style = MaterialTheme.typography.labelLarge)
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

// --- Information Transfer JS Bridge ---
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

// --- Strict 16:9 TV Presentation Method ---
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
            settings.allowContentAccess = true
            
            // Register JS Bridge
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

    // Remote Commands triggered from Kotlin -> TV HTML
    fun playVideo(uriString: String) {
        webView.post {
            val escapedUri = JSONObject.quote(uriString)
            webView.evaluateJavascript("playVideo($escapedUri)", null)
        }
    }

    fun pause() { webView.post { webView.evaluateJavascript("pauseVideo()", null) } }
    fun resume() { webView.post { webView.evaluateJavascript("resumeVideo()", null) } }
    fun seekTo(seconds: Double) { webView.post { webView.evaluateJavascript("seekVideo($seconds)", null) } }
    fun setVolume(level: Float) { webView.post { webView.evaluateJavascript("setVolume($level)", null) } }

    override fun onStop() {
        super.onStop()
        webView.destroy()
    }
}
