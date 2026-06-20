import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/audio_frame_data.dart';
import '../models/glyph_device_info.dart';
import '../services/glyph_visualizer_service.dart';
import '../widgets/glyph_zones.dart';
import '../widgets/spectrum_painter.dart';
import 'calibration_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const _models = [
    'PHONE (1)',
    'PHONE (2)',
    'PHONE (2A)',
    'PHONE (2A)+',
    'PHONE (3A) PRO',
    'PHONE (4A)',
  ];

  static const _prefsKeyModel = 'selected_model';
  static const _prefsKeyDiagnostics = 'show_diagnostics';
  static const _prefsKeySensitivity = 'sensitivity';
  static const _prefsKeyUseInternalAudio = 'use_internal_audio';
  // UI redraw throttle: smooth (~120Hz cap) while on-screen, calm (~30Hz) when backgrounded.
  // Kept in lockstep with the native event rate via setForeground() in didChangeAppLifecycleState.
  static const _uiRefreshFg = Duration(milliseconds: 8);
  static const _uiRefreshBg = Duration(milliseconds: 33);
  Duration _uiRefreshInterval = _uiRefreshFg;

  bool _isRunning = false;
  bool _showDiagnostics = false;
  double _sensitivity = 1.0;
  int _currentModel = 4;
  bool _useInternalAudio = true;
  String _captureSource = 'IDLE';

  final Set<int> _supportedModels = <int>{};

  StreamSubscription<AudioFrameData>? _eventSub;
  SharedPreferences? _prefs;
  final ValueNotifier<AudioFrameData?> _latestFrameNotifier =
      ValueNotifier(null);
  GlyphDeviceInfo? _deviceInfo;
  DateTime? _lastUiFrameAt;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _eventSub?.cancel();
    _latestFrameNotifier.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    // Fast, smooth telemetry while the dashboard is visible; drop to ~30Hz otherwise.
    final foreground = state == AppLifecycleState.resumed;
    _uiRefreshInterval = foreground ? _uiRefreshFg : _uiRefreshBg;
    unawaited(GlyphVisualizerService.setForeground(foreground));
  }

  Future<void> _bootstrap() async {
    final prefsFuture = SharedPreferences.getInstance();
    final deviceInfoFuture = GlyphVisualizerService.getDeviceInfo();
    final runningFuture = GlyphVisualizerService.isRunning();

    final prefs = await prefsFuture;
    final deviceInfo = await deviceInfoFuture;
    final running = await runningFuture;

    final savedModel = (prefs.getInt(_prefsKeyModel) ?? _currentModel)
        .clamp(0, _models.length - 1);

    _supportedModels
      ..clear()
      ..addAll(deviceInfo.supportedModels);

    final resolvedModel = deviceInfo.detectedModel ?? savedModel;
    final resolvedSensitivity = prefs.getDouble(_prefsKeySensitivity) ?? 1.0;
    final showDiagnostics = prefs.getBool(_prefsKeyDiagnostics) ?? false;
    final useInternalAudio = prefs.getBool(_prefsKeyUseInternalAudio) ?? true;

    _prefs = prefs;

    if (!mounted) {
      return;
    }

    setState(() {
      _deviceInfo = deviceInfo;
      _isRunning = running;
      _showDiagnostics = showDiagnostics;
      _currentModel = resolvedModel;
      _sensitivity = resolvedSensitivity;
      _useInternalAudio = useInternalAudio;
    });

    await _persistSettings();

    if (running) {
      _subscribeToEvents();
    }
  }

  Future<void> _persistSettings() async {
    final prefs = _prefs ?? await SharedPreferences.getInstance();
    _prefs = prefs;

    await prefs.setInt(_prefsKeyModel, _currentModel);
    await prefs.setBool(_prefsKeyDiagnostics, _showDiagnostics);
    await prefs.setDouble(_prefsKeySensitivity, _sensitivity);
    await prefs.setBool(_prefsKeyUseInternalAudio, _useInternalAudio);
  }

  void _subscribeToEvents() {
    _eventSub?.cancel();
    _eventSub = GlyphVisualizerService.eventStream.listen((data) {
      final nextSensitivity = data.currentSensitivity;
      final nextCaptureSource = data.captureSource;
      final shouldPersist = (_sensitivity - nextSensitivity).abs() > 0.001;
      final captureChanged = nextCaptureSource != _captureSource;
      final forceImmediateFrame =
          data.isOnset || shouldPersist || captureChanged;
      final now = DateTime.now();

      if (!mounted) {
        return;
      }

      if (!forceImmediateFrame &&
          _lastUiFrameAt != null &&
          now.difference(_lastUiFrameAt!) < _uiRefreshInterval) {
        return;
      }

      _latestFrameNotifier.value = data;

      setState(() {
        _captureSource = nextCaptureSource;
        _sensitivity = nextSensitivity;
      });
      _lastUiFrameAt = now;

      if (shouldPersist) {
        unawaited(_persistSettings());
      }
    });
  }

  Future<void> _start() async {
    final micGranted = await GlyphVisualizerService.requestPermissions();
    if (!micGranted) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'PERMISSIONS REQUIRED',
              style: TextStyle(color: Colors.black),
            ),
            backgroundColor: Colors.white,
          ),
        );
      }
      return;
    }

    // SYSTEM mode captures device audio via the global Visualizer (no screen-record prompt); MIC
    // mode uses the microphone. Chosen via the in-app toggle, no auto-switching between them.
    await GlyphVisualizerService.start(
      sensitivity: _sensitivity,
      model: _currentModel,
      useInternalAudio: _useInternalAudio,
    );
    final running = await GlyphVisualizerService.isRunning();

    if (!mounted) {
      return;
    }

    setState(() {
      _isRunning = running;
      _captureSource = running ? (_useInternalAudio ? 'SYSTEM' : 'MIC') : 'IDLE';
    });
    if (running) {
      _subscribeToEvents();
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'AUDIO CAPTURE FAILED TO START',
            style: TextStyle(color: Colors.black),
          ),
          backgroundColor: Colors.white,
        ),
      );
    }
  }

  Future<void> _stop() async {
    await GlyphVisualizerService.stop();
    _eventSub?.cancel();
    if (!mounted) {
      return;
    }
    setState(() {
      _isRunning = false;
      _captureSource = 'IDLE';
    });
    _latestFrameNotifier.value = null;
  }

  Future<void> _selectModel(int model) async {
    if (!_isModelSelectable(model) || model == _currentModel) {
      return;
    }

    setState(() {
      _currentModel = model;
    });
    await _persistSettings();

    if (_isRunning) {
      await GlyphVisualizerService.setModel(model);
    }
  }

  Future<void> _setSensitivity(double value) async {
    setState(() {
      _sensitivity = value;
    });
    unawaited(_persistSettings());

    if (_isRunning) {
      await GlyphVisualizerService.setSensitivity(value);
    }
  }

  Future<void> _setCaptureMode(bool useInternal) async {
    if (useInternal == _useInternalAudio) {
      return;
    }
    setState(() {
      _useInternalAudio = useInternal;
      if (_isRunning) {
        _captureSource = useInternal ? 'SYSTEM' : 'MIC';
      }
    });
    await _persistSettings();
    if (_isRunning) {
      // Swap the live source in-place (keeps the service/glyph session up).
      await GlyphVisualizerService.setCaptureMode(useInternal);
    }
  }

  Future<void> _toggleDiagnostics() async {
    final nextValue = !_showDiagnostics;
    setState(() {
      _showDiagnostics = nextValue;
    });
    await _persistSettings();
  }

  Future<void> _openCalibration() async {
    final hadDiagnostics = _showDiagnostics;
    if (!hadDiagnostics) {
      setState(() {
        _showDiagnostics = true;
      });
      await _persistSettings();
    }

    if (!mounted) {
      return;
    }

    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => CalibrationScreen(
          eventStream: GlyphVisualizerService.eventStream,
          sensitivity: _sensitivity,
          onSensitivityChanged: (value) {
            unawaited(_setSensitivity(value));
          },
        ),
      ),
    );

    if (!hadDiagnostics) {
      setState(() {
        _showDiagnostics = false;
      });
      await _persistSettings();
    }
  }

  bool _isModelSelectable(int index) {
    if (_deviceInfo == null) {
      return true;
    }
    if (_deviceInfo!.detectedModel == null) {
      return true;
    }
    return _supportedModels.contains(index);
  }

  String get _statusText => _isRunning ? 'RUNNING' : 'STOPPED';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // Removed ambient glow to keep pure OLED black background
          SafeArea(
            child: SingleChildScrollView(
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildHeader(),
                    const SizedBox(height: 24),
                    _buildDeviceBanner(),
                    const SizedBox(height: 24),
                    _buildVisualizerPanel(),
                    const SizedBox(height: 24),
                    _buildStatsRow(),
                    const SizedBox(height: 24),
                    _buildUtilityRow(),
                    if (_showDiagnostics) ...[
                      const SizedBox(height: 20),
                      RepaintBoundary(child: _buildDiagnosticsOverlay()),
                    ],
                    const SizedBox(height: 32),
                    _buildCaptureModeSelector(),
                    const SizedBox(height: 32),
                    _buildModelSelector(),
                    const SizedBox(height: 32),
                    _buildSensitivitySlider(),
                    const SizedBox(height: 48),
                    Row(
                      children: [
                        Expanded(
                          child: _buildButton(
                            'START',
                            _isRunning ? null : _start,
                            _isRunning,
                            isPrimary: true,
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: _buildButton(
                            'STOP',
                            _isRunning ? _stop : null,
                            !_isRunning,
                            isPrimary: false,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeader() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'GLYPH\nBEAT',
          style: TextStyle(
            fontFamily: 'Space Grotesk',
            fontSize: 44,
            fontWeight: FontWeight.w900,
            color: Colors.white,
            height: 0.95,
            letterSpacing: -2,
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            _StatusChip(
              label: _statusText,
              color: _isRunning ? const Color(0xFFFF0033) : Colors.white38,
              isActive: _isRunning,
            ),
            const SizedBox(width: 8),
            _StatusChip(
              label: _captureSource,
              color: (_captureSource == 'INTERNAL' || _captureSource == 'SYSTEM')
                  ? Colors.white
                  : Colors.white38,
              isActive: _captureSource != 'IDLE',
            ),
            const Spacer(),
            const Text(
              'DSP ENGINE',
              style: TextStyle(
                fontFamily: 'JetBrains Mono',
                fontSize: 10,
                color: Colors.white54,
                letterSpacing: 4,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildDeviceBanner() {
    final deviceInfo = _deviceInfo;
    if (deviceInfo == null) return const SizedBox.shrink();

    final detectedModel = deviceInfo.detectedModel;
    final isAuto = detectedModel != null;
    final message = isAuto
        ? 'DETECTED ${_models[detectedModel]} // ${deviceInfo.deviceName.toUpperCase()}'
        : 'AUTO-DETECT UNAVAILABLE // MANUAL MODE';

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
      ),
      child: Row(
        children: [
          Icon(
            isAuto ? Icons.memory : Icons.warning_amber_rounded,
            color: isAuto ? Colors.white : const Color(0xFFFF0033),
            size: 16,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(
                fontFamily: 'JetBrains Mono',
                color: Colors.white70,
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.0,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildVisualizerPanel() {
    return ValueListenableBuilder<AudioFrameData?>(
      valueListenable: _latestFrameNotifier,
      builder: (context, frame, child) {
        return Container(
          height: 220,
          width: double.infinity,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.02),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(
                color: Colors.white.withValues(alpha: 0.05), width: 1.5),
          ),
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              if (frame != null && _isRunning)
                Positioned.fill(
                  bottom: 24,
                  child: LiveSpectrum(magnitudes: frame.magnitude),
                ),
              if (!_isRunning)
                const Center(
                  child: Text(
                    'WAITING FOR SIGNAL',
                    style: TextStyle(
                      fontFamily: 'JetBrains Mono',
                      color: Colors.white24,
                      letterSpacing: 4,
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
              if (frame != null && _isRunning)
                Center(
                  child: GlyphZones(
                    activeDrum: frame.activeDrum,
                    activeDrumAt: frame.activeDrumAt,
                    isRunning: _isRunning,
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildStatsRow() {
    return ValueListenableBuilder<AudioFrameData?>(
      valueListenable: _latestFrameNotifier,
      builder: (context, frame, child) {
        if (frame == null || !_isRunning) {
          return const SizedBox(height: 56);
        }

        return Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.03),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withValues(alpha: 0.05)),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                  child: _buildDataChunk(
                      'BASS', frame.bassEnergy.toStringAsFixed(2))),
              Container(width: 1, height: 30, color: Colors.white12),
              Expanded(
                  child: _buildDataChunk(
                      'MID', frame.midEnergy.toStringAsFixed(2))),
              Container(width: 1, height: 30, color: Colors.white12),
              Expanded(
                  child: _buildDataChunk(
                      'TREB', frame.trebleEnergy.toStringAsFixed(2))),
              Container(width: 1, height: 30, color: Colors.white12),
              Expanded(
                  child: _buildDataChunk(
                      'BEAT', frame.beatType.replaceAll('_', ' '))),
            ],
          ),
        );
      },
    );
  }

  Widget _buildUtilityRow() {
    return Row(
      children: [
        Expanded(
          child: _buildUtilityButton(
            label: _showDiagnostics ? 'HIDE OVERLAY' : 'SHOW OVERLAY',
            icon: Icons.monitor_heart_outlined,
            onTap: _toggleDiagnostics,
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: _buildUtilityButton(
            label: 'CALIBRATION',
            icon: Icons.tune_rounded,
            onTap: _openCalibration,
          ),
        ),
      ],
    );
  }

  Widget _buildDiagnosticsOverlay() {
    return ValueListenableBuilder<AudioFrameData?>(
      valueListenable: _latestFrameNotifier,
      builder: (context, frame, child) {
        return Container(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: const Color(0xFF0A0A0A),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
                color: const Color(0xFFFF0033).withValues(alpha: 0.3)),
            boxShadow: [
              BoxShadow(
                color: const Color(0xFFFF0033).withValues(alpha: 0.05),
                blurRadius: 20,
              )
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(
                children: [
                  Icon(Icons.analytics_outlined,
                      color: Color(0xFFFF0033), size: 16),
                  SizedBox(width: 8),
                  Text(
                    'DIAGNOSTICS & TELEMETRY',
                    style: TextStyle(
                      fontFamily: 'JetBrains Mono',
                      color: Colors.white,
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 2,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              LayoutBuilder(
                builder: (context, constraints) {
                  final columns = constraints.maxWidth >= 720
                      ? 5
                      : constraints.maxWidth >= 460
                          ? 3
                          : 2;
                  return GridView.count(
                    crossAxisCount: columns,
                    crossAxisSpacing: 12,
                    mainAxisSpacing: 12,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    childAspectRatio: 2.5,
                    children: [
                      _diagTile(
                          'CAPTURE', frame?.captureSource ?? _captureSource),
                      _diagTile(
                          'TEMPO',
                          frame == null || frame.bpm <= 0
                              ? '--'
                              : '${frame.bpm.toStringAsFixed(0)} BPM'),
                      _diagTile(
                          'CONF',
                          frame == null
                              ? '--'
                              : frame.confidence.toStringAsFixed(2)),
                      _diagTile(
                          'BAND',
                          frame == null
                              ? '--'
                              : frame.strongestBandIndex.toString()),
                      _diagTile(
                          'PAN',
                          frame == null
                              ? '--'
                              : frame.spatialPan.toStringAsFixed(2)),
                      _diagTile(
                          'RATE',
                          frame == null
                              ? '--'
                              : '${frame.updateRateHz.toStringAsFixed(1)} HZ'),
                    ],
                  );
                },
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _diagTile(String label, String value) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        color: Colors.white.withValues(alpha: 0.05),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            label,
            style: const TextStyle(
              fontFamily: 'JetBrains Mono',
              color: Colors.white54,
              fontSize: 9,
              letterSpacing: 1.5,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: const TextStyle(
              fontFamily: 'JetBrains Mono',
              color: Colors.white,
              fontWeight: FontWeight.w900,
              fontSize: 13,
            ),
            maxLines: 1,
            overflow: TextOverflow.fade,
            softWrap: false,
          ),
        ],
      ),
    );
  }

  Widget _buildDataChunk(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Text(
          value,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontFamily: 'JetBrains Mono',
            color: Colors.white,
            fontSize: 16,
            fontWeight: FontWeight.w900,
          ),
          maxLines: 1,
          overflow: TextOverflow.fade,
          softWrap: false,
        ),
        const SizedBox(height: 4),
        Text(
          label,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontFamily: 'JetBrains Mono',
            color: Colors.white54,
            fontSize: 9,
            letterSpacing: 1.5,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  Widget _buildCaptureModeSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'AUDIO SOURCE',
          style: TextStyle(
            fontFamily: 'JetBrains Mono',
            fontSize: 10,
            color: Colors.white54,
            letterSpacing: 2,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            _buildCaptureModePill('SYSTEM AUDIO', true),
            const SizedBox(width: 12),
            _buildCaptureModePill('MICROPHONE', false),
          ],
        ),
      ],
    );
  }

  Widget _buildCaptureModePill(String label, bool useInternal) {
    final isSelected = _useInternalAudio == useInternal;
    return Expanded(
      child: GestureDetector(
        onTap: () => unawaited(_setCaptureMode(useInternal)),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(vertical: 14),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color:
                isSelected ? Colors.white : Colors.white.withValues(alpha: 0.04),
            borderRadius: BorderRadius.circular(100),
            border: Border.all(
              color:
                  isSelected ? Colors.white : Colors.white.withValues(alpha: 0.1),
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              fontFamily: 'Space Grotesk',
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: isSelected ? Colors.black : Colors.white,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildModelSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'DEVICE MODEL',
          style: TextStyle(
            fontFamily: 'JetBrains Mono',
            fontSize: 10,
            color: Colors.white54,
            letterSpacing: 2,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 12),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          physics: const BouncingScrollPhysics(),
          child: Row(
            children: _models.asMap().entries.map((entry) {
              final isSelected = _currentModel == entry.key;
              final isEnabled = _isModelSelectable(entry.key);

              return GestureDetector(
                onTap:
                    isEnabled ? () => unawaited(_selectModel(entry.key)) : null,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  margin: EdgeInsets.only(
                      right: entry.key < _models.length - 1 ? 12 : 0),
                  padding:
                      const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                  decoration: BoxDecoration(
                    color: isSelected
                        ? Colors.white
                        : Colors.white.withValues(alpha: 0.04),
                    borderRadius: BorderRadius.circular(100),
                    border: Border.all(
                      color: isSelected
                          ? Colors.white
                          : Colors.white.withValues(alpha: 0.1),
                    ),
                  ),
                  child: Text(
                    entry.value,
                    style: TextStyle(
                      fontFamily: 'Space Grotesk',
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: isSelected
                          ? Colors.black
                          : (isEnabled ? Colors.white : Colors.white38),
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
        ),
      ],
    );
  }

  Widget _buildSensitivitySlider() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text(
              'SENSITIVITY //',
              style: TextStyle(
                fontFamily: 'JetBrains Mono',
                fontSize: 10,
                color: Colors.white54,
                letterSpacing: 2,
                fontWeight: FontWeight.bold,
              ),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: const Color(0xFFFF0033).withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                _sensitivity.toStringAsFixed(2),
                style: const TextStyle(
                  fontFamily: 'JetBrains Mono',
                  fontSize: 12,
                  color: Color(0xFFFF0033),
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        SliderTheme(
          data: SliderThemeData(
            activeTrackColor: const Color(0xFFFF0033),
            inactiveTrackColor: Colors.white.withValues(alpha: 0.1),
            thumbColor: Colors.white,
            overlayColor: const Color(0xFFFF0033).withValues(alpha: 0.2),
            trackHeight: 4,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 8),
            overlayShape: const RoundSliderOverlayShape(overlayRadius: 20),
          ),
          child: Slider(
            value: _sensitivity,
            min: 0.1,
            max: 3.0,
            onChanged: (value) => unawaited(_setSensitivity(value)),
          ),
        ),
      ],
    );
  }

  Widget _buildUtilityButton({
    required String label,
    required IconData icon,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.04),
          border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: Colors.white70, size: 16),
            const SizedBox(width: 8),
            Text(
              label,
              style: const TextStyle(
                fontFamily: 'Space Grotesk',
                color: Colors.white,
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 1.2,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildButton(String label, VoidCallback? onTap, bool disabled,
      {required bool isPrimary}) {
    final bgColor = isPrimary
        ? (disabled ? Colors.white.withValues(alpha: 0.05) : Colors.white)
        : (disabled
            ? Colors.white.withValues(alpha: 0.05)
            : const Color(0xFFFF0033));

    final textColor = isPrimary
        ? (disabled ? Colors.white38 : Colors.black)
        : (disabled ? Colors.white38 : Colors.white);

    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOutQuart,
        padding: const EdgeInsets.symmetric(vertical: 20),
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: disabled
                ? Colors.transparent
                : (isPrimary ? Colors.white : const Color(0xFFFF0033)),
          ),
          boxShadow: [
            if (!disabled && isPrimary)
              BoxShadow(
                color: Colors.white.withValues(alpha: 0.3),
                blurRadius: 20,
                offset: const Offset(0, 4),
              ),
            if (!disabled && !isPrimary)
              BoxShadow(
                color: const Color(0xFFFF0033).withValues(alpha: 0.35),
                blurRadius: 20,
                offset: const Offset(0, 4),
              ),
          ],
        ),
        child: Center(
          child: Text(
            label,
            style: TextStyle(
              fontFamily: 'Space Grotesk',
              fontSize: 15,
              fontWeight: FontWeight.w800,
              letterSpacing: 2,
              color: textColor,
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String label;
  final Color color;
  final bool isActive;

  const _StatusChip({
    required this.label,
    required this.color,
    this.isActive = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(100),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              boxShadow: [
                if (isActive)
                  BoxShadow(
                    color: color.withValues(alpha: 0.6),
                    blurRadius: 6,
                  )
              ],
            ),
          ),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              fontFamily: 'JetBrains Mono',
              color: color,
              fontSize: 9,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.2,
            ),
          ),
        ],
      ),
    );
  }
}
