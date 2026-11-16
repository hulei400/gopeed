import 'dart:io';

import 'package:flutter/services.dart';

/// Controls [GopeedApiService], the native Android foreground service that
/// keeps the Go backend and its API server alive after the app is swiped
/// away from Recents. See `GopeedApiService.kt`.
class AndroidApiKeepAlive {
  const AndroidApiKeepAlive._();

  static const _channel = MethodChannel('gopeed.com/api_keep_alive');

  static Future<void> start({
    required String storageDir,
    required String title,
    required String text,
  }) async {
    if (!Platform.isAndroid) return;
    await _channel.invokeMethod<void>('start', {
      'storageDir': storageDir,
      'title': title,
      'text': text,
    });
  }

  static Future<void> stop() async {
    if (!Platform.isAndroid) return;
    await _channel.invokeMethod<void>('stop');
  }
}
