import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

class SpectrumPainter extends CustomPainter {
  final List<double> magnitudes;

  // ── Pre-allocated, reusable Paint objects ──────────────────────────
  static final Paint _gridPaint = Paint()
    ..color = Colors.white.withValues(alpha: 0.03)
    ..strokeWidth = 0.5;
  static final Paint _glowPaint = Paint()
    ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 6);
  static final Paint _barPaint = Paint();
  static final Paint _tipPaint = Paint();
  static final Paint _refPaint = Paint();
  static final Paint _basePaint = Paint()..strokeWidth = 0.5;

  // ── Cached per-bar color table ─────────────────────────────────────
  static List<Color>? _cachedBarColors;
  static int _cachedBarCount = -1;

  static List<ui.Shader>? _cachedBarShaders;
  static List<ui.Shader>? _cachedRefShaders;
  static double _cachedCenterY = -1;

  static void _updateShaderCaches(
      int numBars, double centerY, List<Color> colors) {
    if (_cachedBarShaders != null &&
        _cachedBarShaders!.length == numBars &&
        _cachedCenterY == centerY) {
      return;
    }
    _cachedBarShaders = List<ui.Shader>.generate(numBars, (i) {
      return ui.Gradient.linear(
        Offset(0, centerY),
        const Offset(0, 0),
        [
          colors[i].withValues(alpha: 0.4),
          colors[i].withValues(alpha: 0.9),
          colors[i],
        ],
        [0.0, 0.6, 1.0],
      );
    });
    _cachedRefShaders = List<ui.Shader>.generate(numBars, (i) {
      return ui.Gradient.linear(
        Offset(0, centerY + 2),
        Offset(0, centerY + 2 + (centerY * 0.25)),
        [
          colors[i].withValues(alpha: 0.15),
          colors[i].withValues(alpha: 0.0),
        ],
      );
    });
    _cachedCenterY = centerY;
  }

  static List<Color> _getBarColors(int numBars) {
    if (_cachedBarCount == numBars && _cachedBarColors != null) {
      return _cachedBarColors!;
    }
    final colors = List<Color>.generate(numBars, (i) {
      final freqRatio = i / numBars;
      // Nothing OS aesthetic: Deep Red trailing into pure bright white/steely grey
      if (freqRatio < 0.25) {
        return Color.lerp(
          const Color(0xFFFF0033),
          const Color(0xFFFF0033),
          freqRatio / 0.25,
        )!;
      } else if (freqRatio < 0.8) {
        return Color.lerp(
          const Color(0xFFFF0033),
          const Color(0xFFFFFFFF),
          (freqRatio - 0.25) / 0.55,
        )!;
      } else {
        return Color.lerp(
          const Color(0xFFFFFFFF),
          const Color(0xFFAAAAAA),
          (freqRatio - 0.8) / 0.20,
        )!;
      }
    });
    _cachedBarColors = colors;
    _cachedBarCount = numBars;
    return colors;
  }

  SpectrumPainter({
    required this.magnitudes,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (magnitudes.isEmpty) return;

    final numBars = magnitudes.length;
    const spacing = 1.5;
    final barWidth = (size.width - (spacing * (numBars - 1))) / numBars;
    final centerY = size.height * 0.65;
    final barColors = _getBarColors(numBars);
    _updateShaderCaches(numBars, centerY, barColors);

    // Draw faint horizontal grid lines for depth
    for (int g = 1; g <= 4; g++) {
      final gy = centerY - (centerY * g / 5);
      canvas.drawLine(Offset(0, gy), Offset(size.width, gy), _gridPaint);
    }

    for (int i = 0; i < numBars; i++) {
      final rawMag = magnitudes[i];
      final logMag =
          rawMag > 0 ? (math.log(1 + rawMag * 80) / math.log(81)) : 0.0;
      final heightPct = logMag.clamp(0.0, 1.0);

      final barHeight = heightPct * (centerY * 0.95);
      final x = i * (barWidth + spacing);
      final barColor = barColors[i];

      // Draw soft glow behind bar
      if (barHeight > 2) {
        _glowPaint.color =
            barColor.withValues(alpha: (heightPct * 0.6).clamp(0.0, 0.5));
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(x - 1, centerY - barHeight, barWidth + 2, barHeight),
            const Radius.circular(2),
          ),
          _glowPaint,
        );
      }

      // Draw main bar with gradient
      final barRect =
          Rect.fromLTWH(x, centerY - barHeight, barWidth, barHeight);
      _barPaint.shader = _cachedBarShaders![i];
      canvas.drawRRect(
        RRect.fromRectAndRadius(barRect, const Radius.circular(1.5)),
        _barPaint,
      );

      // Draw bright tip cap on top of tall bars
      if (barHeight > 4) {
        _tipPaint.color = Colors.white.withValues(alpha: heightPct * 0.9);
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(x, centerY - barHeight, barWidth, 2),
            const Radius.circular(1),
          ),
          _tipPaint,
        );
      }

      // Draw reflection (mirrored, faded)
      final refHeight = barHeight * 0.25;
      _refPaint.shader = _cachedRefShaders![i];
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(x, centerY + 2, barWidth, refHeight),
          const Radius.circular(1),
        ),
        _refPaint,
      );
    }

    // Draw baseline separator
    _basePaint.shader = ui.Gradient.linear(
      Offset(0, centerY),
      Offset(size.width, centerY),
      [
        Colors.white.withValues(alpha: 0.0),
        Colors.white.withValues(alpha: 0.12),
        Colors.white.withValues(alpha: 0.12),
        Colors.white.withValues(alpha: 0.0),
      ],
      [0.0, 0.2, 0.8, 1.0],
    );
    canvas.drawLine(
        Offset(0, centerY), Offset(size.width, centerY), _basePaint);
  }

  @override
  bool shouldRepaint(covariant SpectrumPainter oldDelegate) {
    return !listEquals(magnitudes, oldDelegate.magnitudes);
  }
}

class LiveSpectrum extends StatelessWidget {
  final List<double> magnitudes;

  const LiveSpectrum({
    super.key,
    required this.magnitudes,
  });

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: const Size(double.infinity, 140),
      painter: SpectrumPainter(
        magnitudes: magnitudes,
      ),
    );
  }
}
