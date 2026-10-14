package com.example

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.ui.screens.MainChatScreen
import com.example.ui.theme.OrkiTheme
import com.example.ui.viewmodel.OrkiViewModel

class MainActivity : ComponentActivity() {

  companion object {
    private const val STARTUP_TAG = "OrkiStartup"
  }

  private val processStartedAtMs = Process.getStartElapsedRealtime()
  private val viewModel: OrkiViewModel by viewModels()
  private var firstDrawRecorded = false

  override fun onCreate(savedInstanceState: Bundle?) {
    val onCreateStartedAtMs = SystemClock.elapsedRealtime()

    // Disable ONNX Runtime telemetry early before native engine loads
    try {
      android.system.Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
    } catch (_: Throwable) {}

    // Safeguard FinalizerDaemon against unregistered receiver exceptions from SDK finalizers
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      if (thread.name == "FinalizerDaemon" && throwable is IllegalArgumentException && throwable.message?.contains("Receiver not registered") == true) {
        Log.w(STARTUP_TAG, "Suppressed third-party finalizer exception: ${throwable.message}")
        return@setDefaultUncaughtExceptionHandler
      }
      defaultHandler?.uncaughtException(thread, throwable)
    }

    // Must run before super.onCreate(). There is intentionally no keep-on-screen condition:
    // the branded system launch screen leaves as soon as the first app frame is ready.
    installSplashScreen()
    super.onCreate(savedInstanceState)

    observeFirstDraw()
    enableEdgeToEdge()
    setContent {
      OrkiTheme {
        MainChatScreen(
          viewModel = viewModel,
          modifier = Modifier.fillMaxSize()
        )
      }
    }

    Log.i(
      STARTUP_TAG,
      "activity_onCreate_complete_ms=${SystemClock.elapsedRealtime() - onCreateStartedAtMs} " +
        "process_to_onCreate_complete_ms=${SystemClock.elapsedRealtime() - processStartedAtMs}"
    )
  }

  /**
   * Records the real first drawn frame (the same milestone used by `adb shell am start -W`).
   * Nonessential service startup begins only after this callback, so it cannot delay that frame.
   */
  private fun observeFirstDraw() {
    val decorView = window.decorView
    val listener = object : ViewTreeObserver.OnDrawListener {
      override fun onDraw() {
        if (firstDrawRecorded) return
        firstDrawRecorded = true

        val firstFrameMs = SystemClock.elapsedRealtime() - processStartedAtMs
        Log.i(STARTUP_TAG, "first_frame_drawn_ms=$firstFrameMs")

        // Remove the listener and start optional work after the frame has been submitted.
        decorView.post {
          if (decorView.viewTreeObserver.isAlive) {
            decorView.viewTreeObserver.removeOnDrawListener(this)
          }
          viewModel.onFirstFrameDrawn()
          reportFullyDrawn()
        }
      }
    }
    decorView.viewTreeObserver.addOnDrawListener(listener)
  }
}
