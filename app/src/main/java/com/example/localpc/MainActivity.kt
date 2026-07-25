package com.example.localpc

import android.app.Presentation
import android.content.ContentValues
import android.content.Intent
import android.content.UriPermission
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.view.Display
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

// Bridge so the phone (keyboard/remote) UI can reach the live "PC" showing
// on the external display.
object PresentationBridge {
    var current: CastPresentation? = null
}

class MainActivity : ComponentActivity() {
    private var currentPresentation: CastPresentation? = null
    
    // Logic for picking video files
    private var selectedVideoUri by mutableStateOf<Uri?>(null)
    private var videoContent by remember { mutableStateOf("") }
    private var videoFilename by remember { mutableStateOf("untitled.mp4") }
    private var showFileList by remember { mutableStateOf(false) }
    private var fileList by remember { mutableStateOf(listOf<String>()) }
    
    // Register the video picker
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // We save the URI. In a real app, you might copy this to internal storage.
            // For this demo, we assume the app has access to it or we convert it.
            // NOTE: To reliably play in WebView, we save it to internal storage first.
            saveVideoToInternal(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(onOpenCastSettings = {
                    startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                })
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
            currentPresentation?.dismiss()
            currentPresentation = null
            PresentationBridge.current = null
        } else {
            val display = displays[0]
            if (currentPresentation?.display?.displayId != display.displayId) {
                currentPresentation?.dismiss()
                currentPresentation = CastPresentation(this, display)
                currentPresentation?.show()
                PresentationBridge.current = currentPresentation
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPresentation?.dismiss()
        currentPresentation = null
        PresentationBridge.current = null
    }

    // Helper to copy video to internal storage to ensure WebView security permissions
    private fun saveVideoToExternal(sourceUri: Uri) {
        try {
            val context = LocalContext.current
            val filename = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(Date()) + ".mp4"
            val destFile = File(context.filesDir, "video_files/$filename")
            destFile.parentFile?.mkdirs()
            
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            videoContent = filename
            videoFilename = filename
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            saveVideoToExternal(data.data!!)
        }
    }

    override fun onStart() {
        super.onStart()
        // Check if we already have videos in storage and list them
        val filesDir = File(filesDir, "video_files")
        if (filesDir.exists()) {
            fileList = filesDir.list()?.toList() ?: emptyList()
        }
    }
}

@Composable
fun MainScreen(onOpenCastSettings: () -> Unit) {
    var statusText by remember { mutableStateOf("Not connected") }
    var filename by remember { mutableStateOf("untitled.txt") }
    var content by remember { mutableStateOf("") }
    
    // Video specific state
    var isPlaying by remember { mutableStateOf(false) }
    val presentation = PresentationBridge.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Local PC", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCastSettings) { Text("1. Connect") }
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
                pushToTv(presentation, "setContent(${'"' + JSONObject.quote(content) + '"'})")
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
                    statusText = if (ok) "Saved" else "Save failed"
                }
            }) { Text("Save") }
            Button(onClick = {
                if (presentation == null) statusText = "Connect first"
                else { presentation.listFiles(); statusText = "File list loaded" }
            }) { Text("Open") }
        }

        // ---------------------------------------------------------
        // VIDEO PLAYER SECTION
        // ---------------------------------------------------------
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("2. Video Player", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Request to pick a video from gallery/storage
                (LocalContext.current as MainActivity).pickVideoLauncher.launch("video/*")
            }) { Text("Pick Video") }
            
            Button(onClick = {
                if (presentation == null) statusText = "Connect first"
                else {
                    presentation.playVideo(filename)
                    statusText = "Playing $filename"
                }
            }, enabled = isPlaying) { Text("Play Selected") }
            
            Button(onClick = {
                if (presentation == null) statusText = "Connect first"
                else {
                    presentation.stopVideo()
                    statusText = "Video Stopped"
                }
            }, enabled = isPlaying) { Text("Stop") }
        }
        
        Spacer(Modifier.height(8.dp))
        Text(statusText, style = MaterialTheme.typography.bodySmall)

        // File List Dialog
        if (showFileList) {
            AlertDialog(
                onDismissRequest = { showFileList = false },
                confirmButton = { TextButton(onClick = { showFileList = false }) { Text("Close") } },
                title = { Text("Saved Videos") },
                text = {
                    if (fileList.isEmpty()) Text("No videos saved yet.")
                    else {
                        LazyColumn {
                            items(fileList) { name ->
                                TextButton(onClick = {
                                    filename = name
                                    // Reset text area, but actually load video content logic here if needed
                                    pushToTv(presentation, "setContent('Video: $name')")
                                    statusText = "Loaded: $name"
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

// Helper to push generic JS commands
fun pushToTv(presentation: CastPresentation?, command: String) {
    if (presentation != null) {
        presentation.webView.post {
            presentation.webView.evaluateJavascript(command, null)
        }
    }
}

// -------------------------------------------------------------------------
// TV DISPLAY: Updated to support Video
// -------------------------------------------------------------------------
class CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var webView: WebView
    private val filesDir: File by lazy {
        File(context.filesDir, "notepad_files").apply { mkdirs() }
    }
    private val videoDir: File by lazy {
        File(context.filesDir, "video_files").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val aspectContainer = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }

        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // Auto-play on TV
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

        // Lock box to 16:9
        val displayMetrics = android.util.DisplayMetrics()
        display.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val targetRatio = 16f / 9f

        val params = aspectContainer.layoutParams as FrameLayout.LayoutParams
        if (screenRatio > targetRatio) {
            params.height = screenHeight
            params.width = (screenHeight * targetRatio).toInt()
        } else {
            params.width = screenWidth
            params.height = (screenWidth / targetRatio).toInt()
        }
        aspectContainer.layoutParams = params
    }

    // --- File Handling (Notepad) ---
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

    fun listFiles(): List<String> {
        return filesDir.list()?.toList() ?: emptyList()
    }

    // --- VIDEO PLAYER LOGIC ---
    fun playVideo(filename: String) {
        val videoFile = File(videoDir, filename)
        
        if (!videoFile.exists()) {
            statusUpdate("Video not found")
            return
        }

        // Create a FileProvider URI for the video
        // Note: file_paths.xml must exist for this to work
        val authority = context.packageName + ".provider"
        val uri = FileProvider.getUriForFile(context, authority, videoFile)
        
        // Grant temporary read permission to WebView
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        webView.post {
            // Use a JavaScript command to inject the URI into the video element
            // We encode the URI to be safe for JS
            val escapedUri = JSONObject.quote(uri.toString())
            webView.evaluateJavascript("playVideo($escapedUri)", null)
        }
    }

    fun stopVideo() {
        webView.post {
            webView.evaluateJavascript("stopVideo()", null)
        }
    }

    // Utility to update status in the UI thread
    private fun statusUpdate(msg: String) {
        // Since we don't have a status callback here, we just log or ignore for now
        // In a real app, we might have a callback interface
    }

    override fun onStop() {
        super.onStop()
        webView.destroy()
    }
}
