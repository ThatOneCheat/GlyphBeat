import 'dart:async';

import 'package:flutter/material.dart';

import '../models/audio_frame_data.dart';

class CalibrationScreen extends StatefulWidget {
  final Stream<AudioFrameData> eventStream;
  final double sensitivity;
  final ValueChanged<double> onSensitivityChanged;

  const CalibrationScreen({
    super.key,
    required this.eventStream,
    required this.sensitivity,
    required this.onSensitivityChanged,
  });

  @override
  State<CalibrationScreen> createState() => _CalibrationScreenState();
}

class _CalibrationScreenState extends State<CalibrationScreen> {
  late double _sensitivity;
  AudioFrameData? _frame;
  StreamSubscription<AudioFrameData>? _sub;
  DateTime? _lastUpdate;
  static const _throttle = Duration(milliseconds: 66); // ~15fps

  @override
  void initState() {
    super.initState();
    _sensitivity = widget.sensitivity;
    _sub = widget.eventStream.listen((data) {
      final now = DateTime.now();
      if (_lastUpdate != null && now.difference(_lastUpdate!) < _throttle) {
        return;
      }
      _lastUpdate = now;
      if (mounted) {
        setState(() {
          _frame = data;
        });
      }
    });
  }

  @override
  void didUpdateWidget(CalibrationScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.sensitivity != oldWidget.sensitivity) {
      setState(() {
        _sensitivity = widget.sensitivity;
      });
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final frame = _frame;
    final thresholdDenominator =
        (frame?.adaptiveThreshold ?? 0) <= 0 ? 1.0 : frame!.adaptiveThreshold;
    final fluxRatio = frame == null
        ? 0.0
        : (frame.spectralFlux / thresholdDenominator).clamp(0.0, 1.0);
    final noiseRatio = frame == null
        ? 0.0
        : (frame.noiseFloor / thresholdDenominator).clamp(0.0, 1.0);

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: const Text(
          'CALIBRATION',
          style: TextStyle(
            fontFamily: 'Space Grotesk',
            fontWeight: FontWeight.w900,
            letterSpacing: 1.5,
          ),
        ),
        iconTheme: const IconThemeData(color: Colors.white),
        elevation: 0,
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1.0),
          child: Container(
            color: Colors.white.withValues(alpha: 0.05),
            height: 1.0,
          ),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          _MetricCard(
            title: 'THRESHOLD PREVIEW',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _LabeledMeter(
                  label: 'SPECTRAL FLUX',
                  valueText: frame == null
                      ? '--'
                      : frame.spectralFlux.toStringAsFixed(4),
                  value: fluxRatio,
                  color: Colors.white,
                ),
                const SizedBox(height: 16),
                _LabeledMeter(
                  label: 'NOISE FLOOR',
                  valueText: frame == null
                      ? '--'
                      : frame.noiseFloor.toStringAsFixed(4),
                  value: noiseRatio,
                  color: Colors.white38,
                ),
                const SizedBox(height: 16),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.05),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    frame == null
                        ? 'ADAPTIVE: --'
                        : 'ADAPTIVE: ${frame.adaptiveThreshold.toStringAsFixed(4)}',
                    style: const TextStyle(
                      fontFamily: 'JetBrains Mono',
                      color: Colors.white70,
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1.2,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          _MetricCard(
            title: 'SENSITIVITY OVERRIDE',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'NOISE GATE',
                      style: TextStyle(
                        fontFamily: 'JetBrains Mono',
                        color: Colors.white54,
                        fontSize: 11,
                        letterSpacing: 1.2,
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: const Color(0xFFFF0033).withValues(alpha: 0.2),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        _sensitivity.toStringAsFixed(2),
                        style: const TextStyle(
                          fontFamily: 'JetBrains Mono',
                          color: Color(0xFFFF0033),
                          fontWeight: FontWeight.w900,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                SliderTheme(
                  data: SliderThemeData(
                    activeTrackColor: const Color(0xFFFF0033),
                    inactiveTrackColor: Colors.white.withValues(alpha: 0.1),
                    thumbColor: Colors.white,
                    overlayColor:
                        const Color(0xFFFF0033).withValues(alpha: 0.15),
                    trackHeight: 4,
                    thumbShape:
                        const RoundSliderThumbShape(enabledThumbRadius: 8),
                  ),
                  child: Slider(
                    value: _sensitivity,
                    min: 0.1,
                    max: 3.0,
                    onChanged: (value) {
                      setState(() {
                        _sensitivity = value;
                      });
                      widget.onSensitivityChanged(value);
                    },
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          _MetricCard(
            title: 'LIVE DIAGNOSTICS',
            child: Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                _BadgeMetric(
                  label: 'BEAT',
                  value: frame?.beatType ?? '--',
                ),
                _BadgeMetric(
                  label: 'CAPTURE',
                  value: frame?.captureSource ?? '--',
                ),
                _BadgeMetric(
                  label: 'BAND',
                  value: frame == null
                      ? '--'
                      : frame.strongestBandIndex.toString(),
                ),
                _BadgeMetric(
                  label: 'RATE',
                  value: frame == null
                      ? '--'
                      : '${frame.updateRateHz.toStringAsFixed(1)} HZ',
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          _MetricCard(
            title: 'RAW METERS',
            child: Column(
              children: [
                _LabeledMeter(
                  label: 'CONFIDENCE',
                  valueText: frame == null
                      ? '--'
                      : frame.confidence.toStringAsFixed(2),
                  value: frame?.confidence ?? 0,
                  color: const Color(0xFFFF0033),
                ),
                const SizedBox(height: 16),
                _LabeledMeter(
                  label: 'PAN (L/R)',
                  valueText: frame == null
                      ? '--'
                      : frame.spatialPan.toStringAsFixed(2),
                  value: frame == null
                      ? 0.5
                      : ((frame.spatialPan + 1) / 2).clamp(0.0, 1.0),
                  color: Colors.white70,
                ),
                const SizedBox(height: 16),
                _LabeledMeter(
                  label: 'FLUX VAR',
                  valueText: frame == null
                      ? '--'
                      : frame.fluxVariance.toStringAsFixed(4),
                  value: frame == null
                      ? 0
                      : (frame.fluxVariance /
                              (frame.adaptiveThreshold + 0.0001))
                          .clamp(0.0, 1.0),
                  color: Colors.white38,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MetricCard extends StatelessWidget {
  final String title;
  final Widget child;

  const _MetricCard({
    required this.title,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.03),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 4,
                height: 12,
                color: const Color(0xFFFF0033),
              ),
              const SizedBox(width: 8),
              Text(
                title,
                style: const TextStyle(
                  fontFamily: 'Space Grotesk',
                  color: Colors.white,
                  fontWeight: FontWeight.w800,
                  fontSize: 13,
                  letterSpacing: 2,
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          child,
        ],
      ),
    );
  }
}

class _LabeledMeter extends StatelessWidget {
  final String label;
  final String valueText;
  final double value;
  final Color color;

  const _LabeledMeter({
    required this.label,
    required this.valueText,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              label,
              style: const TextStyle(
                fontFamily: 'JetBrains Mono',
                color: Colors.white54,
                fontSize: 10,
                fontWeight: FontWeight.bold,
                letterSpacing: 1.2,
              ),
            ),
            Text(
              valueText,
              style: const TextStyle(
                fontFamily: 'JetBrains Mono',
                color: Colors.white,
                fontWeight: FontWeight.w900,
                fontSize: 11,
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Container(
          height: 6,
          width: double.infinity,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.05),
            borderRadius: BorderRadius.circular(3),
            border: Border.all(color: Colors.white.withValues(alpha: 0.1)),
          ),
          child: FractionallySizedBox(
            alignment: Alignment.centerLeft,
            widthFactor: value.clamp(0.0, 1.0),
            child: Container(
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(3),
                boxShadow: [
                  BoxShadow(
                    color: color.withValues(alpha: 0.4),
                    blurRadius: 8,
                  )
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _BadgeMetric extends StatelessWidget {
  final String label;
  final String value;

  const _BadgeMetric({
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: const TextStyle(
              fontFamily: 'JetBrains Mono',
              color: Colors.white54,
              fontSize: 9,
              letterSpacing: 1.2,
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
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }
}
