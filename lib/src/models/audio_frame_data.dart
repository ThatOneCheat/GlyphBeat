class AudioFrameData {
  final List<double> magnitude;
  final double bassEnergy;
  final double midEnergy;
  final double trebleEnergy;
  final bool isOnset;
  final double confidence;
  final String beatType;
  final double mlConfidence;
  final int strongestBandIndex;
  final double spatialPan;
  final String captureSource;
  final double updateRateHz;
  final double spectralFlux;
  final double adaptiveThreshold;
  final double noiseFloor;
  final double fluxVariance;
  final double currentSensitivity;
  final double bpm;
  final int activeDrum;
  final int activeDrumAt;

  AudioFrameData({
    required this.magnitude,
    required this.bassEnergy,
    required this.midEnergy,
    required this.trebleEnergy,
    required this.isOnset,
    required this.confidence,
    required this.beatType,
    required this.mlConfidence,
    required this.strongestBandIndex,
    required this.spatialPan,
    required this.captureSource,
    required this.updateRateHz,
    required this.spectralFlux,
    required this.adaptiveThreshold,
    required this.noiseFloor,
    required this.fluxVariance,
    required this.currentSensitivity,
    required this.bpm,
    required this.activeDrum,
    required this.activeDrumAt,
  });

  factory AudioFrameData.fromMap(Map<dynamic, dynamic> map) {
    return AudioFrameData(
      magnitude: (map['magnitude'] as List)
          .map((value) => (value as num).toDouble())
          .toList(growable: false),
      bassEnergy: (map['bassEnergy'] as num).toDouble(),
      midEnergy: (map['midEnergy'] as num).toDouble(),
      trebleEnergy: (map['trebleEnergy'] as num).toDouble(),
      isOnset: map['isOnset'] as bool,
      confidence: (map['confidence'] as num).toDouble(),
      beatType: map['beatType'] as String,
      mlConfidence: (map['mlConfidence'] as num?)?.toDouble() ?? 0,
      strongestBandIndex: (map['strongestBandIndex'] as num?)?.toInt() ?? -1,
      spatialPan: (map['spatialPan'] as num?)?.toDouble() ?? 0,
      captureSource: map['captureSource'] as String? ?? 'IDLE',
      updateRateHz: (map['updateRateHz'] as num?)?.toDouble() ?? 0,
      spectralFlux: (map['spectralFlux'] as num?)?.toDouble() ?? 0,
      adaptiveThreshold: (map['adaptiveThreshold'] as num?)?.toDouble() ?? 0,
      noiseFloor: (map['noiseFloor'] as num?)?.toDouble() ?? 0,
      fluxVariance: (map['fluxVariance'] as num?)?.toDouble() ?? 0,
      currentSensitivity:
          (map['currentSensitivity'] as num?)?.toDouble() ?? 1.0,
      bpm: (map['bpm'] as num?)?.toDouble() ?? 0,
      activeDrum: (map['activeDrum'] as num?)?.toInt() ?? 0,
      activeDrumAt: (map['activeDrumAt'] as num?)?.toInt() ?? 0,
    );
  }
}
