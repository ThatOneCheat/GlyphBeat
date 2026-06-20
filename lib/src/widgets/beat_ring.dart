import 'package:flutter/material.dart';

class BeatRing extends StatefulWidget {
  final bool isOnset;
  final double confidence;
  final String beatType;

  const BeatRing({
    super.key,
    required this.isOnset,
    required this.confidence,
    required this.beatType,
  });

  @override
  State<BeatRing> createState() => _BeatRingState();
}

class _BeatRingState extends State<BeatRing> with TickerProviderStateMixin {
  late AnimationController _pulseController;
  late AnimationController _glowController;
  late Animation<double> _pulseAnim;
  late Animation<double> _glowAnim;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );
    _glowController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );

    _pulseAnim = CurvedAnimation(
      parent: _pulseController,
      curve: Curves.easeOutExpo,
    );
    _glowAnim = CurvedAnimation(
      parent: _glowController,
      curve: Curves.easeOutCubic,
    );
  }

  @override
  void didUpdateWidget(BeatRing oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isOnset && !oldWidget.isOnset && !_pulseController.isAnimating) {
      _pulseController.forward(from: 0.0);
      _glowController.forward(from: 0.0);
    }
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _glowController.dispose();
    super.dispose();
  }

  Color _beatColor() {
    switch (widget.beatType) {
      case 'KICK':
        return const Color(0xFFFF0033);
      case 'SUB_BASS':
        return const Color(0xFFFF0033);
      case 'SNARE':
        return Colors.white;
      case 'HIHAT':
        return Colors.white70;
      default:
        return Colors.white38;
    }
  }

  String _beatIcon() {
    switch (widget.beatType) {
      case 'KICK':
        return '\u25C9';
      case 'SUB_BASS':
        return '\u25CE';
      case 'SNARE':
        return '\u25C8';
      case 'HIHAT':
        return '\u2727';
      default:
        return '\u25CB';
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([_pulseAnim, _glowAnim]),
      builder: (context, child) {
        final ringColor = _beatColor();
        final pulseVal = _pulseAnim.value;
        final glowVal = _glowAnim.value;

        final scale = 1.0 + (pulseVal * widget.confidence * 0.3);
        final fadeOut = (1.0 - pulseVal);
        final glowFade = (1.0 - glowVal);

        return SizedBox(
          width: 140,
          height: 140,
          child: Stack(
            alignment: Alignment.center,
            children: [
              if (_glowController.isAnimating)
                Transform.scale(
                  scale: 1.0 + (glowVal * 0.8),
                  child: Container(
                    width: 120,
                    height: 120,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: ringColor.withValues(alpha: glowFade * 0.2),
                        width: 1.0,
                      ),
                    ),
                  ),
                ),
              if (_pulseController.isAnimating)
                Transform.scale(
                  scale: scale,
                  child: Container(
                    width: 100,
                    height: 100,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: ringColor.withValues(
                            alpha: fadeOut * widget.confidence * 0.9),
                        width: 4.0 * fadeOut,
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: ringColor.withValues(alpha: fadeOut * 0.5),
                          blurRadius: 24,
                          spreadRadius: 4,
                        ),
                      ],
                    ),
                  ),
                ),
              Container(
                width: 90,
                height: 90,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: ringColor.withValues(alpha: 0.15),
                    width: 2.0,
                  ),
                ),
              ),
              Container(
                width: 78,
                height: 78,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: RadialGradient(
                    colors: [
                      ringColor.withValues(alpha: widget.isOnset ? 0.3 : 0.02),
                      ringColor.withValues(alpha: 0.0),
                    ],
                    stops: const [0.0, 1.0],
                  ),
                ),
              ),
              Text(
                _beatIcon(),
                style: TextStyle(
                  color: widget.isOnset
                      ? ringColor
                      : ringColor.withValues(alpha: 0.4),
                  fontSize: 28,
                  shadows: widget.isOnset
                      ? [
                          Shadow(
                            color: ringColor.withValues(alpha: 0.8),
                            blurRadius: 10,
                          ),
                        ]
                      : null,
                ),
              ),
              Positioned(
                bottom: 22,
                child: Text(
                  widget.beatType.replaceAll('_', ' '),
                  style: TextStyle(
                    fontFamily: 'JetBrains Mono',
                    color: widget.isOnset
                        ? ringColor.withValues(alpha: 0.9)
                        : ringColor.withValues(alpha: 0.3),
                    fontSize: 9,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 2.5,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
