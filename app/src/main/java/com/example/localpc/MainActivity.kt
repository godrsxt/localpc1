package com.example.localpc

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object PresentationBridge {
    var current: CastPresentation? by mutableStateOf(null)
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

fun saveVideoToInternal(context: Context, sourceUri: Uri, onSuccess: (String) -> Unit, onError: () -> Unit) {
    try {
        val filename = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(Date()) + ".mp4"
        val destFile = File(context.filesDir, "video_files/$filename")
        destFile.parentFile?.mkdirs()

        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            destFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        onSuccess(filename)
    } catch (e: Exception) {
        e.printStackTrace()
        onError()
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Not connected") }
    var selectedVideoName by remember { mutableStateOf<String?>(null) }
    var showFileList by remember { mutableStateOf(false) }
    var fileList by remember { mutableStateOf(listOf<String>()) }

    val presentation = PresentationBridge.current

    val pickVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            statusText = "Saving video..."
            saveVideoToInternal(
                context = context,
                sourceUri = it,
                onSuccess = { savedName ->
                    selectedVideoName = savedName
                    statusText = "Video saved: $savedName"
                },
                onError = { statusText = "Error saving video" }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Local TV Caster", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("1. Connect to TV / Wireless Display") }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        
        Text("2. Video Player", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickVideoLauncher.launch("video/*") }, modifier = Modifier.weight(1f)) { 
                Text("Add Video") 
            }

            Button(onClick = {
                fileList = File(context.filesDir, "video_files").list()?.toList() ?: emptyList()
                showFileList = true
            }, modifier = Modifier.weight(1f)) { 
                Text("Library") 
            }
        }
        
        Spacer(Modifier.height(16.dp))

        Text(
            text = if (selectedVideoName != null) "Selected: $selectedVideoName" else "No video selected",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (presentation == null) statusText = "Connect first"
                    else if (selectedVideoName == null) statusText = "Select a video first"
                    else {
                        presentation.playVideo(selectedVideoName!!)
                        statusText = "Playing $selectedVideoName"
                    }
                }, 
                enabled = presentation != null && selectedVideoName != null,
                modifier = Modifier.weight(1f)
            ) { Text("Play on TV") }

            Button(
                onClick = {
                    if (presentation == null) statusText = "Connect first"
                    else {
                        presentation.stopVideo()
                        statusText = "Video Stopped"
                    }
                }, 
                enabled = presentation != null,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        Spacer(Modifier.height(24.dp))
        Text("Status: $statusText", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)

        if (showFileList) {
            AlertDialog(
                onDismissRequest = { showFileList = false },
                confirmButton = { TextButton(onClick = { showFileList = false }) { Text("Close") } },
                title = { Text("Video Library") },
                text = {
                    if (fileList.isEmpty()) Text("No videos found. Click 'Add Video' first.")
                    else {
                        LazyColumn {
                            items(fileList) { name ->
                                TextButton(onClick = {
                                    selectedVideoName = name
                                    statusText = "Selected video: $name"
                                    showFileList = false
                                }) { Text(name) }
                            }
                        }
                    }
                }
            )
        }
    }
}

class MainActivity.CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var webView: WebView
    private val videoDir: File = File(context.filesDir, "video_files").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force completely black background on the secondary display
        val rootLayout = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val aspectContainer = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }

        webView = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
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

    // Forces the container to be exactly 16:9 ratio in the center of the screen
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

    fun playVideo(filename: String) {
        val videoFile = File(videoDir, filename)
        if (!videoFile.exists()) return

        val authority = "${context.applicationContext.packageName}.provider"
        val uri = FileProvider.getUriForFile(context, authority, videoFile)

        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        webView.post {
            val escapedUri = JSONObject.quote(uri.toString())
            webView.evaluateJavascript("playVideo($escapedUri)", null)
        }
    }

    fun stopVideo() {
        webView.post {
            webView.evaluateJavascript("stopVideo()", null)
        }
    }

    override fun onStop() {
        super.onStop()
        webView.destroy()
    }
}
