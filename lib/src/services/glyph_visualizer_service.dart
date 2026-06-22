import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import '../models/audio_frame_data.dart';
import '../models/glyph_device_info.dart';

class GlyphVisualizerService {
  static const _channel = MethodChannel('com.glyphvisualizer/control');
  static const _eventChannel = EventChannel('com.glyphvisualizer/events');

  static Stream<AudioFrameData>? _eventStream;
  static Stream<AudioFrameData> get eventStream {
    _eventStream ??= _eventChannel
        .receiveBroadcastStream()
        .map((event) => AudioFrameData.fromMap(event as Map));
    return _eventStream!;
  }

  static Future<bool> requestPermissions() async {
    try {
      final audioStatus = await Permission.microphone.request();
      if (audioStatus != PermissionStatus.granted) return false;

      final notifStatus = await Permission.notification.request();
      return notifStatus == PermissionStatus.granted;
    } on MissingPluginException {
      return false;
    }
  }

  static Future<void> start({
    required double sensitivity,
    required int model,
    required bool useInternalAudio,
    required bool useMlClassifier,
  }) async {
    try {
      await _channel.invokeMethod('start', {
        'sensitivity': sensitivity,
        'model': model,
        'useInternalAudio': useInternalAudio,
        'useMlClassifier': useMlClassifier,
      });
    } on MissingPluginException {
      return;
    }
  }

  /// Switch the capture source live while running (true = system audio via the
  /// Visualizer, false = microphone).
  static Future<void> setCaptureMode(bool useInternalAudio) async {
    try {
      await _channel
          .invokeMethod('setCaptureMode', {'useInternalAudio': useInternalAudio});
    } on MissingPluginException {
      return;
    }
  }

  /// Tell native whether the dashboard is on-screen. Foreground posts telemetry
  /// fast (~120Hz cap, data-limited); background drops to ~30Hz.
  static Future<void> setForeground(bool foreground) async {
    try {
      await _channel.invokeMethod('setForeground', {'foreground': foreground});
    } on MissingPluginException {
      return;
    }
  }

  static Future<void> stop() async {
    try {
      await _channel.invokeMethod('stop');
    } on MissingPluginException {
      return;
    }
  }

  static Future<void> setSensitivity(double sensitivity) async {
    try {
      await _channel
          .invokeMethod('setSensitivity', {'sensitivity': sensitivity});
    } on MissingPluginException {
      return;
    }
  }

  static Future<void> setModel(int model) async {
    try {
      await _channel.invokeMethod('setModel', {'model': model});
    } on MissingPluginException {
      return;
    }
  }

  /// Toggle the AI drum classifier live while running (true = learned MLP types each
  /// onset, false = legacy band-energy heuristic).
  static Future<void> setMlClassifier(bool useMlClassifier) async {
    try {
      await _channel
          .invokeMethod('setMlClassifier', {'useMlClassifier': useMlClassifier});
    } on MissingPluginException {
      return;
    }
  }

  static Future<bool> isRunning() async {
    try {
      return await _channel.invokeMethod('isRunning') ?? false;
    } on MissingPluginException {
      return false;
    }
  }

  static Future<GlyphDeviceInfo> getDeviceInfo() async {
    try {
      final result =
          await _channel.invokeMapMethod<dynamic, dynamic>('getDeviceInfo');
      return GlyphDeviceInfo.fromMap(result ?? const <dynamic, dynamic>{});
    } on MissingPluginException {
      return const GlyphDeviceInfo(
        detectedModel: null,
        supportedModels: <int>[0, 1, 2, 3, 4, 5],
        deviceName: 'Unsupported host',
      );
    }
  }
}
