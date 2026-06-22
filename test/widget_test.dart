import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glyph_visualizer/main.dart';
import 'package:glyph_visualizer/src/widgets/glyph_zones.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('App loads smoke test', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const GlyphVisualizerApp());
    await tester.pumpAndSettle();
    expect(find.text('GLYPH\nBEAT'), findsOneWidget);
  });

  testWidgets('GlyphZones mirrors hardware mapping correctly', (WidgetTester tester) async {
    // 1. Kick (activeDrum = 1) -> Slash (Transform.rotate)
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: GlyphZones(
            activeDrum: 1,
            activeDrumAt: 0,
            isRunning: true,
          ),
        ),
      ),
    );
    await tester.pump();
    
    final kickFinder = find.ancestor(
      of: find.text('KICK'),
      matching: find.byType(Column),
    ).first;
    expect(find.descendant(of: kickFinder, matching: find.byType(Transform)), findsOneWidget);

    // 2. Snare (activeDrum = 2) -> Dot (Container with circular shape and solid color, i.e., no border)
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: GlyphZones(
            activeDrum: 2,
            activeDrumAt: 0,
            isRunning: true,
          ),
        ),
      ),
    );
    await tester.pump();

    final snareFinder = find.ancestor(
      of: find.text('SNARE'),
      matching: find.byType(Column),
    ).first;
    expect(find.descendant(of: snareFinder, matching: find.byType(Transform)), findsNothing);
    final snareContainer = tester.widget<Container>(
      find.descendant(of: snareFinder, matching: find.byType(Container)).first
    );
    final snareDeco = snareContainer.decoration as BoxDecoration;
    expect(snareDeco.shape, BoxShape.circle);
    expect(snareDeco.border, isNull);

    // 3. Hat (activeDrum = 3) -> Ring (Container with circular shape and a border)
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: GlyphZones(
            activeDrum: 3,
            activeDrumAt: 0,
            isRunning: true,
          ),
        ),
      ),
    );
    await tester.pump();

    final hatFinder = find.ancestor(
      of: find.text('HAT'),
      matching: find.byType(Column),
    ).first;
    expect(find.descendant(of: hatFinder, matching: find.byType(Transform)), findsNothing);
    final hatContainer = tester.widget<Container>(
      find.descendant(of: hatFinder, matching: find.byType(Container)).first
    );
    final hatDeco = hatContainer.decoration as BoxDecoration;
    expect(hatDeco.shape, BoxShape.circle);
    expect(hatDeco.border, isNotNull);
  });
}
