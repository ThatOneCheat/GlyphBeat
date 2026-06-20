import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// On-screen mirror of the rear glyph drum zones. Lights one zone at a time:
/// Kick (1) -> Ring, Snare (2) -> Slash, Hat (3) -> Dot.
/// Matches the one-zone-at-a-time glyph behaviour, hold durations and linear decay.
class GlyphZones extends StatefulWidget {
  final int activeDrum;
  final int activeDrumAt;
  final bool isRunning;

  const GlyphZones({
    super.key,
    required this.activeDrum,
    required this.activeDrumAt,
    required this.isRunning,
  });

  @override
  State<GlyphZones> createState() => _GlyphZonesState();
}

class _GlyphZonesState extends State<GlyphZones>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  int _activeZone = 0;
  double _brightness = 0.0;
  DateTime? _struckAt;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_tick);
    if (widget.activeDrum != 0) {
      final now = DateTime.now().millisecondsSinceEpoch;
      final age = now - widget.activeDrumAt;
      const decayMs = 100;
      if (age < decayMs) {
        _activeZone = widget.activeDrum;
        _struckAt = DateTime.fromMillisecondsSinceEpoch(widget.activeDrumAt);
        _brightness = 1.0 - age / decayMs;
        _ticker.start();
      }
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  void _tick(Duration elapsed) {
    if (!widget.isRunning || _activeZone == 0 || _struckAt == null) {
      if (_brightness != 0.0) {
        setState(() {
          _brightness = 0.0;
        });
      }
      if (_ticker.isActive) {
        _ticker.stop();
      }
      return;
    }

    final age = DateTime.now().difference(_struckAt!).inMilliseconds;
    // Decay linearly over 100ms
    double newBrightness = 1.0 - age / 100.0;

    if (newBrightness <= 0.0) {
      setState(() {
        _activeZone = 0;
        _brightness = 0.0;
      });
      if (_ticker.isActive) {
        _ticker.stop();
      }
    } else {
      if (newBrightness != _brightness) {
        setState(() {
          _brightness = newBrightness;
        });
      }
    }
  }

  @override
  void didUpdateWidget(covariant GlyphZones oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.isRunning) {
      if (_activeZone != 0 || _brightness != 0.0 || _struckAt != null) {
        setState(() {
          _activeZone = 0;
          _brightness = 0.0;
          _struckAt = null;
        });
      }
      if (_ticker.isActive) {
        _ticker.stop();
      }
      return;
    }
    if (widget.activeDrum != 0 &&
        widget.activeDrumAt != oldWidget.activeDrumAt) {
      setState(() {
        _activeZone = widget.activeDrum;
        _struckAt = DateTime.fromMillisecondsSinceEpoch(widget.activeDrumAt);
        _brightness = 1.0;
      });
      if (!_ticker.isActive) {
        _ticker.start();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        _ZoneVisual(
          label: 'KICK',
          brightness: _activeZone == 1 ? _brightness : 0.0,
          shape: _ZoneShape.ring,
        ),
        const SizedBox(width: 30),
        _ZoneVisual(
          label: 'SNARE',
          brightness: _activeZone == 2 ? _brightness : 0.0,
          shape: _ZoneShape.slash,
        ),
        const SizedBox(width: 30),
        _ZoneVisual(
          label: 'HAT',
          brightness: _activeZone == 3 ? _brightness : 0.0,
          shape: _ZoneShape.dot,
        ),
      ],
    );
  }
}

enum _ZoneShape { ring, slash, dot }

class _ZoneVisual extends StatelessWidget {
  final String label;
  final double brightness;
  final _ZoneShape shape;

  const _ZoneVisual({
    required this.label,
    required this.brightness,
    required this.shape,
  });

  @override
  Widget build(BuildContext context) {
    final lit = Color.lerp(const Color(0xFF272727), Colors.white, brightness)!;
    final glow = <BoxShadow>[
      if (brightness > 0)
        BoxShadow(
          color: Colors.white.withValues(alpha: 0.55 * brightness),
          blurRadius: 18 * brightness,
          spreadRadius: 1.5 * brightness,
        ),
    ];

    Widget shapeWidget;
    switch (shape) {
      case _ZoneShape.ring:
        shapeWidget = Container(
          width: 52,
          height: 52,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(color: lit, width: 5),
            boxShadow: glow,
          ),
        );
        break;
      case _ZoneShape.slash:
        shapeWidget = Transform.rotate(
          angle: 0.5,
          child: Container(
            width: 11,
            height: 52,
            decoration: BoxDecoration(
              color: lit,
              borderRadius: BorderRadius.circular(6),
              boxShadow: glow,
            ),
          ),
        );
        break;
      case _ZoneShape.dot:
        shapeWidget = Container(
          width: 24,
          height: 24,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: lit,
            boxShadow: glow,
          ),
        );
        break;
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: 58,
          height: 58,
          child: Center(child: shapeWidget),
        ),
        const SizedBox(height: 10),
        Text(
          label,
          style: TextStyle(
            fontFamily: 'JetBrains Mono',
            fontSize: 9,
            letterSpacing: 1.5,
            fontWeight: FontWeight.bold,
            color: Colors.white.withValues(alpha: 0.3 + 0.5 * brightness),
          ),
        ),
      ],
    );
  }
}
