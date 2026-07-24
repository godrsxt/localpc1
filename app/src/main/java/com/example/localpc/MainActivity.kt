package com.example.localpc

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

// Bridge so the phone (keyboard/remote) UI can reach the live "PC" showing
// on the external display. Pure view-hierarchy content (WebView) only --
// no VideoView/SurfaceView/MediaProjection anywhere, since those are what
// showed gray on your hardware.
object PresentationBridge {
    var current: CastPresentation? = null
}

class MainActivity : ComponentActivity() {
    private var currentPresentation: CastPresentation? = null

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
}

@Composable
fun MainScreen(onOpenCastSettings: () -> Unit) {
    var filename by remember { mutableStateOf("untitled.txt") }
    var content by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Not connected") }
    var showFileList by remember { mutableStateOf(false) }
    var fileList by remember { mutableStateOf(listOf<String>()) }

    fun pushToTv() {
        PresentationBridge.current?.updateContent(content)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Local PC -- Notepad Remote", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCastSettings) { Text("1. Connect") }
            Button(onClick = {
                content = ""
                filename = "untitled.txt"
                pushToTv()
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
                pushToTv() // live-mirrors every keystroke onto the TV window
            },
            label = { Text("Notepad content (mirrors to TV live)") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val presentation = PresentationBridge.current
                if (presentation == null) {
                    statusText = "Connect first (step 1)"
                } else {
                    val ok = presentation.saveFile(filename, content)
                    statusText = if (ok) "Saved $filename" else "Save failed"
                }
            }) { Text("Save") }

            Button(onClick = {
                val presentation = PresentationBridge.current
                if (presentation == null) {
                    statusText = "Connect first (step 1)"
                } else {
                    fileList = presentation.listFiles()
                    showFileList = true
                }
            }) { Text("Open") }
        }

        Spacer(Modifier.height(8.dp))
        Text(statusText, style = MaterialTheme.typography.bodySmall)

        if (showFileList) {
            AlertDialog(
                onDismissRequest = { showFileList = false },
                confirmButton = {
                    TextButton(onClick = { showFileList = false }) { Text("Close") }
                },
                title = { Text("Saved files") },
                text = {
                    if (fileList.isEmpty()) {
                        Text("No files saved yet.")
                    } else {
                        LazyColumn {
                            items(fileList) { name ->
                                TextButton(onClick = {
                                    val loaded = PresentationBridge.current?.loadFile(name)
                                    if (loaded != null) {
                                        content = loaded
                                        filename = name
                                        pushToTv()
                                        statusText = "Opened $name"
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

// -------------------------------------------------------------------------
// TV DISPLAY: a "Notepad" window rendered in a WebView (plain view-hierarchy
// content -- this is what actually composites correctly on your hardware,
// unlike VideoView/MediaProjection). The phone drives everything; this
// display has no input of its own.
// -------------------------------------------------------------------------
class CastPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var webView: WebView
    private val filesDir: File by lazy {
        File(context.filesDir, "notepad_files").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val aspectContainer = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/index.html")
        }

        aspectContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootLayout.addView(
            aspectContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        setContentView(rootLayout)

        // Lock the box to 16:9.
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

    /** Pushes the phone's current text live into the TV Notepad window. */
    fun updateContent(text: String) {
        webView.post {
            val jsString = JSONObject.quote(text) // safely escapes quotes/newlines
            webView.evaluateJavascript("setContent($jsString)", null)
        }
    }

    /** Real file, written to the app's private storage on the phone. */
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

    override fun onStop() {
        super.onStop()
        webView.destroy()
    }
}
