package com.glyphvisualizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nothing.ketchum.Common
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class MainActivity : FlutterFragmentActivity() {
    private val TAG = "GlyphVisualizer"
    private val METHOD_CHANNEL = "com.glyphvisualizer/control"
    private val EVENT_CHANNEL = "com.glyphvisualizer/events"

    companion object {
        @JvmField
        var globalEventSink: EventChannel.EventSink? = null
    }

    private var sensitivity = 1.0
    private var model = 4
    private var useInternalAudio = true
    private var useMlClassifier = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(object : MethodCallHandler {
            override fun onMethodCall(call: io.flutter.plugin.common.MethodCall, result: Result) {
                handleMethodCall(call, result)
            }
        })

        val eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                globalEventSink = events
            }

            override fun onCancel(arguments: Any?) {
                globalEventSink = null
            }
        })
    }

    private fun handleMethodCall(call: io.flutter.plugin.common.MethodCall, result: Result) {
        when (call.method) {
            "start" -> {
                sensitivity = call.argument<Double>("sensitivity") ?: 1.0
                model = call.argument<Int>("model") ?: 4
                useInternalAudio = call.argument<Boolean>("useInternalAudio") ?: true
                useMlClassifier = call.argument<Boolean>("useMlClassifier") ?: true
                startVisualizer()
                result.success(true)
            }
            "setCaptureMode" -> {
                useInternalAudio = call.argument<Boolean>("useInternalAudio") ?: true
                if (VisualizerService.isServiceRunning) {
                    val intent = Intent(this, VisualizerService::class.java)
                    intent.action = "SET_CAPTURE_MODE"
                    intent.putExtra("useInternalAudio", useInternalAudio)
                    startService(intent)
                }
                result.success(true)
            }
            "setForeground" -> {
                val foreground = call.argument<Boolean>("foreground") ?: true
                if (VisualizerService.isServiceRunning) {
                    val intent = Intent(this, VisualizerService::class.java)
                    intent.action = "SET_FOREGROUND"
                    intent.putExtra("foreground", foreground)
                    startService(intent)
                }
                result.success(true)
            }
            "stop" -> {
                stopVisualizer()
                result.success(true)
            }
            "setSensitivity" -> {
                sensitivity = call.argument<Double>("sensitivity") ?: 1.0
                updateServiceSettings()
                result.success(true)
            }
            "setModel" -> {
                model = call.argument<Int>("model") ?: 4
                updateServiceSettings()
                result.success(true)
            }
            "setMlClassifier" -> {
                useMlClassifier = call.argument<Boolean>("useMlClassifier") ?: true
                updateServiceSettings()
                result.success(true)
            }
            "isRunning" -> {
                result.success(VisualizerService.isServiceRunning)
            }
            "getDeviceInfo" -> {
                result.success(getDeviceInfo())
            }
            else -> result.notImplemented()
        }
    }

    private fun startVisualizer() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        val intent = Intent(this, VisualizerService::class.java)
        intent.putExtra("sensitivity", sensitivity)
        intent.putExtra("model", model)
        // SYSTEM audio is captured via the Visualizer (no MediaProjection / screen-record needed);
        // MIC mode uses the microphone. The user picks via the in-app toggle.
        intent.putExtra("useInternalAudio", useInternalAudio)
        intent.putExtra("useMlClassifier", useMlClassifier)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Log.d(TAG, "Visualizer started")
    }

    private fun stopVisualizer() {
        val intent = Intent(this, VisualizerService::class.java)
        intent.action = "STOP"
        startService(intent)

        Log.d(TAG, "Visualizer stopped")
    }

    private fun updateServiceSettings() {
        if (VisualizerService.isServiceRunning) {
            val intent = Intent(this, VisualizerService::class.java)
            intent.putExtra("sensitivity", sensitivity)
            intent.putExtra("model", model)
            intent.putExtra("useMlClassifier", useMlClassifier)
            startService(intent)
        }
    }

    private fun checkPermissions(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var notifGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return audioGranted && notifGranted
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 100)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun getDeviceInfo(): Map<String, Any?> {
        val detectedModel = detectDeviceModel()
        val supportedModels = if (detectedModel != null) {
            listOf(detectedModel)
        } else {
            listOf(0, 1, 2, 3, 4, 5)
        }

        return mapOf(
            "detectedModel" to detectedModel,
            "supportedModels" to supportedModels,
            "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        )
    }

    private fun detectDeviceModel(): Int? {
        return when {
            Common.is20111() -> 0
            Common.is22111() -> 1
            Common.is23111() -> 2
            Common.is23113() -> 3
            Common.is24111() -> 4
            Common.is25111() -> 5
            else -> null
        }
    }
}
