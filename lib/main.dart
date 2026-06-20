import 'package:flutter/material.dart';
import 'src/screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const GlyphVisualizerApp());
}

class GlyphVisualizerApp extends StatelessWidget {
  const GlyphVisualizerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'GlyphBeat',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF000000),
        primaryColor: const Color(0xFFFFFFFF),
        colorScheme: const ColorScheme.dark(
          primary: Colors.white,
          secondary: Color(0xFF888888),
          surface: Color(0xFF111111),
        ),
        textTheme: ThemeData.dark().textTheme.apply(
              fontFamily: 'Space Grotesk',
            ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}
