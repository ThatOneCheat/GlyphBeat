class GlyphDeviceInfo {
  final int? detectedModel;
  final List<int> supportedModels;
  final String deviceName;

  const GlyphDeviceInfo({
    required this.detectedModel,
    required this.supportedModels,
    required this.deviceName,
  });

  factory GlyphDeviceInfo.fromMap(Map<dynamic, dynamic> map) {
    return GlyphDeviceInfo(
      detectedModel: (map['detectedModel'] as num?)?.toInt(),
      supportedModels: (map['supportedModels'] as List<dynamic>? ?? const [])
          .map((value) => (value as num).toInt())
          .toList(growable: false),
      deviceName: map['deviceName'] as String? ?? 'Unknown device',
    );
  }
}
