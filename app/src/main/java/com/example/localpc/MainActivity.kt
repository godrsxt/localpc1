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
    var filename by remember { mutableStateOf("untitled.txt") }
    var content by remember { mutableStateOf("") }
    var showFileList by remember { mutableStateOf(false) }
    var fileList by remember { mutableStateOf(listOf<String>()) }
    var isVideoMode by remember { mutableStateOf(false) }

    val presentation = PresentationBridge.current

    val pickVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            statusText = "Saving video..."
            saveVideoToInternal(
                context = context,
                sourceUri = it,
                onSuccess = { savedName ->
                    filename = savedName
                    statusText = "Video saved: $savedName"
                    content = ""
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
        Text("Local PC", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
            }) { Text("1. Connect") }
            Button(onClick = {
                content = ""
                filename = "untitled.txt"
                pushToTv(presentation, "setContent(\"New file\")")
                statusText = "New file"
            }) { Text("New") }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("Filename") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = content,
            onValueChange = {
                content = it
                pushToTv(presentation, "setContent(${JSONObject.quote(it)})")
            },
            label = { Text("Notepad content") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (presentation == null) {
                    statusText = "Connect first"
                } else {
                    val ok = presentation.saveFile(filename, content)
                    statusText = if (ok) "Saved text" else "Save failed"
                }
            }) { Text("Save Text") }

            Button(onClick = {
                isVideoMode = false
                fileList = File(context.filesDir, "notepad_files").list()?.toList() ?: emptyList()
                showFileList = true
            }) { Text("Open Text") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("2. Video Player", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickVideoLauncher.launch("video/*")
            }) { Text("Pick") }

            Button(onClick = {
                isVideoMode = true
                fileList = File(context.filesDir, "video_files").list()?.toList() ?: emptyList()
                showFileList = true
            }) { Text("Library") }

            Button(onClick = {
                if (presentation == null) statusText = "Connect first"
                else {
                    presentation.playVideo(filename)
                    statusText = "Playing $filename"
                }
            }, enabled = presentation != null) { Text("Play") }

            Button(onClick = {
                if (presentation == null) statusText = "Connect first"
                else {
                    presentation.stopVideo()
                    statusText = "Video Stopped"
                }
            }, enabled = presentation != null) { Text("Stop") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Status: $statusText", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

        if (showFileList) {
            AlertDialog(
                onDismissRequest = { showFileList = false },
                confirmButton = { TextButton(onClick = { showFileList = false }) { Text("Close") } },
                title = { Text(if (isVideoMode) "Saved Videos" else "Saved Text Files") },
                text = {
                    if (fileList.isEmpty()) Text("No files found.")
                    else {
                        LazyColumn {
                            items(fileList) { name ->
                                TextButton(onClick = {
                                    filename = name
                                    if (isVideoMode) {
                                        pushToTv(presentation, "setContent('Video: $name')")
                                        statusText = "Selected video: $name"
                                    } else {
                                        val loaded = presentation?.loadFile(name) ?: ""
                                        content = loaded
                                        pushToTv(presentation, "setContent(${JSONObject.quote(loaded)})")
                                        statusText = "Loaded text: $name"
                                    }
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

fun pushToTv(presentation: CastPresentation?, command: String) {
    presentation?.webView?.post {
        presentation.webView.evaluateJavascript(command, null)
    }
}

class CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var webView: WebView
    private val filesDir: File = File(context.filesDir, "notepad_files").apply { mkdirs() }
    private val videoDir: File = File(context.filesDir, "video_files").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val aspectContainer = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }

        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true     // MANDATORY for local video
            settings.allowContentAccess = true  // MANDATORY for content:// URIs
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

    fun saveFile(filename: String, content: String): Boolean {
        return try {
            File(filesDir, filename).writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun loadFile(filename: String): String? {
        val file = File(filesDir, filename)
        return if (file.exists()) file.readText() else null
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
