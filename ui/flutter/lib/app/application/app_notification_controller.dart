import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path/path.dart' as path;
import 'package:window_manager/window_manager.dart';

import '../../api/model/task.dart' as api_task;
import '../../core/capabilities/app_capabilities.dart';
import '../../core/common/task_event.dart';
import '../../core/libgopeed_boot.dart';
import '../../core/utils/file_explorer.dart';
import '../../features/tasks/application/tasks_controller.dart';
import '../../features/tasks/domain/task_record.dart';
import '../../l10n/l10n.dart';
import '../../util/log_util.dart';
import '../../util/util.dart';
import 'app_runtime_controller.dart';

final appNotificationControllerProvider = AsyncNotifierProvider<AppNotificationController, AppNotificationState>(
  AppNotificationController.new,
);

const _openFileAction = 'open_file';
const _openFolderAction = 'open_folder';

// macOS resolves buttons through pre-registered notification categories, one
// per action combination a done task event can offer. Error notifications
// carry no buttons at all.
const _categoryDoneSingleFile = 'taskDoneSingleFile';
const _categoryDoneFolder = 'taskDoneFolder';

// Android channel for download progress and completion notifications.
// Separate from the keep-alive foreground-service channel so the user can
// control download alerts independently in system settings.
const _downloadChannelId = 'gopeed_downloads';

// Per-task notification ids start here so they never collide with the
// keep-alive foreground notification (1001). One id per task: the progress
// notification and the final done/error notification share it, so the final
// one replaces the progress bar instead of stacking a second notification.
const _taskNotificationIdBase = 10000;

/// A notification button resolved for one task event.
///
/// [id] is one of [_openFileAction]/[_openFolderAction]; Windows carries
/// [path] inside the action arguments, macOS and Linux deliver it through the
/// notification payload, so the click callback can act without looking the
/// task up again (it may already be gone by the time the user clicks).
class NotificationActionSpec {
  const NotificationActionSpec({required this.id, required this.path});

  final String id;
  final String path;

  String encode() => jsonEncode({'action': id, 'path': path});

  static NotificationActionSpec? decode(String raw) {
    if (raw.isEmpty) return null;
    try {
      final data = jsonDecode(raw);
      if (data is! Map<String, dynamic>) return null;
      final id = data['action'];
      final targetPath = data['path'];
      if (id is! String || targetPath is! String) return null;
      if (id != _openFileAction && id != _openFolderAction) return null;
      return NotificationActionSpec(id: id, path: targetPath);
    } catch (_) {
      return null;
    }
  }
}

class AppNotificationState {
  const AppNotificationState({this.started = false});

  final bool started;
}

class AppNotificationController extends AsyncNotifier<AppNotificationState> {
  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();
  StreamSubscription<TaskEvent>? _taskEventSubscription;
  ProviderSubscription<AsyncValue<TasksState>>? _taskProgressSubscription;

  /// Stable notification id per task id. Progress and done/error share it.
  final Map<String, int> _taskNotificationIds = {};
  var _nextTaskNotificationId = _taskNotificationIdBase;

  /// Tasks whose done/error event already fired. Their progress notification
  /// was replaced by the final one, so the task-list watcher must not cancel
  /// it when the task leaves the downloading set.
  final Set<String> _finalizedTaskIds = {};

  int _notificationIdForTask(String taskId) {
    return _taskNotificationIds.putIfAbsent(taskId, () => _nextTaskNotificationId++);
  }

  @visibleForTesting
  Stream<TaskEvent> get taskEvents => LibgopeedBoot.instance.taskEvents;

  @override
  Future<AppNotificationState> build() async {
    final runtime = ref.watch(appRuntimeControllerProvider).value;
    // Desktop and Android only: the notification UI (and the Android
    // permission flow) is not wired up for iOS or web.
    if (runtime == null || kIsWeb || (!Util.isDesktop() && !Util.isAndroid())) {
      return const AppNotificationState();
    }
    await _initNotifications(
      appLocalizationsFor(runtime.downloaderConfig.extra.locale),
      requestPermissions: runtime.downloaderConfig.extra.desktopNotification,
    );
    _listenTaskEvents();
    _listenTaskProgress();
    ref.onDispose(() {
      unawaited(_taskEventSubscription?.cancel());
      _taskProgressSubscription?.close();
    });
    return const AppNotificationState(started: true);
  }

  Future<void> _initNotifications(AppLocalizations locale, {required bool requestPermissions}) async {
    // The runtime is watched, so enabling notifications in settings also
    // requests authorization. macOS remembers previously granted/denied access.
    final openFileAction = DarwinNotificationAction.plain(_openFileAction, locale.openFile);
    final openFolderAction = DarwinNotificationAction.plain(_openFolderAction, locale.openFolder);
    final darwin = DarwinInitializationSettings(
      requestAlertPermission: requestPermissions,
      requestBadgePermission: false,
      requestSoundPermission: requestPermissions,
      notificationCategories: [
        DarwinNotificationCategory(_categoryDoneSingleFile, actions: [openFileAction, openFolderAction]),
        DarwinNotificationCategory(_categoryDoneFolder, actions: [openFolderAction]),
      ],
    );
    final linux = LinuxInitializationSettings(
      defaultActionName: locale.open,
      defaultIcon: AssetsLinuxIcon('assets/icon/icon.png'),
    );

    String? windowsIconPath;
    try {
      if (Util.isWindows()) {
        // Windows requires a file path for IconUri, so use the bundled PNG
        // beside the executable rather than copying it to another directory.
        final file = File(
          path.join(
            path.dirname(Platform.resolvedExecutable),
            'data',
            'flutter_assets',
            'assets',
            'icon',
            'icon_512.png',
          ),
        );
        if (await file.exists()) {
          windowsIconPath = file.absolute.path;
        } else {
          logger.w('Windows notification icon not found: ${file.path}');
        }
      }
    } catch (error, stackTrace) {
      logger.w('prepare Windows notification icon failed', error, stackTrace);
    }

    final windows = WindowsInitializationSettings(
      appName: 'Gopeed',
      appUserModelId: 'com.gopeed.gopeed',
      guid: '3c1bf3f4-3d91-4eaa-a33f-8705e71cf1ce',
      iconPath: windowsIconPath,
    );

    const android = AndroidInitializationSettings('@mipmap/ic_launcher');

    await _plugin.initialize(
      settings: InitializationSettings(macOS: darwin, linux: linux, windows: windows, android: android),
      onDidReceiveNotificationResponse: _onNotificationResponse,
    );

    if (Util.isAndroid() && requestPermissions) {
      // Android 13+ needs a runtime grant on top of the manifest
      // POST_NOTIFICATIONS declaration. A denial just means no download
      // alerts until the user enables them in system settings.
      await _plugin
          .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
          ?.requestNotificationsPermission();
    }
  }

  void _listenTaskEvents() {
    _taskEventSubscription?.cancel();
    _taskEventSubscription = taskEvents.listen((event) async {
      final runtime = ref.read(appRuntimeControllerProvider).value;
      if (runtime?.downloaderConfig.extra.desktopNotification == false) return;
      final locale = appLocalizationsFor(runtime?.downloaderConfig.extra.locale ?? '');
      switch (event.type) {
        case TaskEventType.done:
          _finalizedTaskIds.add(event.taskId);
          await _showNotification(
            locale: locale,
            title: locale.notificationTaskDone,
            body: event.name,
            taskId: event.taskId,
            withActions: true,
          );
        case TaskEventType.error:
          _finalizedTaskIds.add(event.taskId);
          // A failed download has no usable output to open, so no buttons.
          await _showNotification(
            locale: locale,
            title: locale.notificationTaskError,
            body: event.name,
            taskId: event.taskId,
            withActions: false,
          );
      }
    });
  }

  /// Watches the (1s) task list for downloads in progress and keeps one
  /// progress notification per top-level downloading task. Runs on Android
  /// only: desktop shows progress in the app window instead.
  void _listenTaskProgress() {
    if (!Util.isAndroid()) return;
    // build() can re-run when the runtime changes; drop the old listener
    // first so a state change does not sync progress twice.
    _taskProgressSubscription?.close();
    _taskProgressSubscription = ref.listen(tasksControllerProvider, (previous, next) {
      final state = next.value;
      if (state == null) return;
      unawaited(_syncProgressNotifications(state));
    });
  }

  Future<void> _syncProgressNotifications(TasksState state) async {
    final runtime = ref.read(appRuntimeControllerProvider).value;
    if (runtime?.downloaderConfig.extra.desktopNotification == false) return;
    final locale = appLocalizationsFor(runtime?.downloaderConfig.extra.locale ?? '');
    final downloading = <String, TaskRecord>{};
    for (final task in state.tasks) {
      if (task.status == TaskStatus.downloading && !task.waiting) {
        downloading[task.id] = task;
      }
    }
    for (final task in downloading.values) {
      await _showProgressNotification(locale: locale, task: task);
    }
    // Cancel progress for tasks that stopped without a done/error event
    // (paused or deleted). Done/error are left alone: their event replaces
    // the progress notification with the final one under the same id.
    for (final id in _taskNotificationIds.keys.toList()) {
      if (downloading.containsKey(id)) continue;
      if (_finalizedTaskIds.contains(id)) continue;
      final record = state.findTask(id);
      if (record != null && (record.status == TaskStatus.completed || record.status == TaskStatus.failed)) {
        continue;
      }
      await _plugin.cancel(id: _taskNotificationIds[id]!);
      _taskNotificationIds.remove(id);
    }
  }

  Future<void> _showProgressNotification({required AppLocalizations locale, required TaskRecord task}) async {
    final percent = task.progress == null ? null : (task.progress! * 100).round().clamp(0, 100);
    final details = NotificationDetails(
      android: AndroidNotificationDetails(
        _downloadChannelId,
        locale.downloadNotificationChannel,
        channelDescription: locale.downloadNotificationChannelDescription,
        importance: Importance.defaultImportance,
        priority: Priority.low,
        showProgress: true,
        maxProgress: 100,
        progress: percent ?? 0,
        indeterminate: percent == null,
        ongoing: true,
        autoCancel: false,
        onlyAlertOnce: true,
        showWhen: true,
      ),
    );
    await _plugin.show(
      id: _notificationIdForTask(task.id),
      title: task.name,
      body: _progressBody(task),
      notificationDetails: details,
    );
  }

  String _progressBody(TaskRecord task) {
    final parts = <String>[];
    if (task.total != null && task.total!.isNotEmpty) {
      parts.add('${task.downloaded} / ${task.total}');
    } else {
      parts.add(task.downloaded);
    }
    if (task.speed != null && task.speed!.isNotEmpty) {
      parts.add(task.speed!);
    }
    return parts.join(' · ');
  }

  Future<void> _showNotification({
    required AppLocalizations locale,
    required String title,
    required String body,
    required String taskId,
    required bool withActions,
  }) async {
    final target = withActions ? await resolveTaskTarget(taskId) : null;
    // The payload carries the path and whether it is a single file: on
    // Android a plain tap on the notification opens the file directly, so
    // the click handler needs both. Desktop readers ignore the extra field.
    final payload = target == null
        ? ''
        : jsonEncode({'path': target.path, 'canOpenFile': target.canOpenFile});
    final details = NotificationDetails(
      macOS: DarwinNotificationDetails(
        categoryIdentifier: switch (target) {
          null => null,
          _ when target.canOpenFile => _categoryDoneSingleFile,
          _ => _categoryDoneFolder,
        },
      ),
      linux: LinuxNotificationDetails(
        actions: [
          if (target != null && target.canOpenFile)
            LinuxNotificationAction(key: _openFileAction, label: locale.openFile),
          if (target != null) LinuxNotificationAction(key: _openFolderAction, label: locale.openFolder),
        ],
      ),
      windows: WindowsNotificationDetails(
        actions: [
          if (target != null && target.canOpenFile)
            WindowsAction(content: locale.openFile, arguments: NotificationActionSpec(id: _openFileAction, path: target.path).encode()),
          if (target != null)
            WindowsAction(content: locale.openFolder, arguments: NotificationActionSpec(id: _openFolderAction, path: target.path).encode()),
        ],
      ),
      android: AndroidNotificationDetails(
        _downloadChannelId,
        locale.downloadNotificationChannel,
        channelDescription: locale.downloadNotificationChannelDescription,
        importance: Importance.high,
        priority: Priority.high,
        autoCancel: true,
        ongoing: false,
        onlyAlertOnce: true,
        // Android has no folder-reveal (FileExplorer.reveal is desktop-only),
        // so only offer "open file", and only for single-file tasks.
        actions: [
          if (target != null && target.canOpenFile)
            AndroidNotificationAction(_openFileAction, locale.openFile),
        ],
      ),
    );
    // Same id as the progress notification for this task, so the final
    // notification replaces the progress bar instead of stacking.
    await _plugin.show(
      id: _notificationIdForTask(taskId),
      title: title,
      body: body,
      notificationDetails: details,
      payload: payload,
    );
  }

  /// Reads the file path out of a notification payload (`{'path': ...}`).
  String? _payloadPath(String? payload) {
    if (payload == null || payload.isEmpty) return null;
    try {
      final data = jsonDecode(payload);
      final targetPath = data is Map<String, dynamic> ? data['path'] : null;
      return targetPath is String ? targetPath : null;
    } catch (_) {
      return null;
    }
  }

  /// Reads the `canOpenFile` flag out of a notification payload. Defaults
  /// to true for payloads written before the flag existed.
  bool _payloadCanOpenFile(String? payload) {
    if (payload == null || payload.isEmpty) return true;
    try {
      final data = jsonDecode(payload);
      final canOpen = data is Map<String, dynamic> ? data['canOpenFile'] : null;
      return canOpen is bool ? canOpen : true;
    } catch (_) {
      return true;
    }
  }

  /// Resolves the download target a done task event points at, following the
  /// same rule as the task details page: only single-file tasks offer "open
  /// file", folder tasks (e.g. multi-file torrents) can only reveal the
  /// folder. Returns null when the task is already gone.
  @visibleForTesting
  Future<({String path, bool canOpenFile})?> resolveTaskTarget(String taskId) async {
    final apiTask = await findTask(taskId);
    if (apiTask == null) return null;
    final record = TaskRecord.fromApi(apiTask);
    return (path: record.storagePath, canOpenFile: !record.isFolder);
  }

  @visibleForTesting
  Future<void> handleNotificationResponse(NotificationResponse response) => _onNotificationResponse(response);

  Future<void> _onNotificationResponse(NotificationResponse response) async {
    // Windows actions carry the full spec in their arguments.
    var spec = NotificationActionSpec.decode(response.actionId ?? '');
    if (spec == null) {
      // macOS/Linux actions are stable keys; the path arrives in the payload.
      final id = response.actionId ?? '';
      if (id == _openFileAction || id == _openFolderAction) {
        try {
          final data = jsonDecode(response.payload ?? '');
          final targetPath = data is Map<String, dynamic> ? data['path'] : null;
          if (targetPath is String) {
            spec = NotificationActionSpec(id: id, path: targetPath);
          }
        } catch (_) {
          // Malformed payload: fall through to the plain-click behavior.
        }
      }
    }
    if (spec == null) {
      // Plain click on the notification body.
      if (Util.isAndroid()) {
        // Android: open the file directly (an APK goes to the package
        // installer). The tap itself already brings the app forward, and
        // there is no folder to reveal, so folder tasks do nothing here.
        final targetPath = _payloadPath(response.payload);
        if (targetPath != null && _payloadCanOpenFile(response.payload)) {
          if (!await openTaskFile(targetPath)) {
            logger.w('failed to open file from notification: $targetPath');
          }
        }
        return;
      }
      await bringWindowToFront();
      return;
    }
    // Use the task card's file operations, including reveal's parent fallback.
    switch (spec.id) {
      case _openFileAction:
        if (!await openTaskFile(spec.path)) {
          logger.w('failed to open file from notification: ${spec.path}');
        }
      case _openFolderAction:
        if (!await revealTaskFolder(spec.path)) {
          logger.w('failed to reveal folder from notification: ${spec.path}');
        }
    }
  }

  @visibleForTesting
  Future<api_task.Task?> findTask(String taskId) async {
    try {
      final tasks = await ref.read(gopeedServiceProvider).getTasks(api_task.Status.values);
      for (final task in tasks) {
        if (task.id == taskId) return task;
      }
    } catch (error, stackTrace) {
      logger.w('look up task for notification failed', error, stackTrace);
    }
    return null;
  }

  @visibleForTesting
  Future<bool> openTaskFile(String filePath) => FileExplorer.open(filePath);

  @visibleForTesting
  Future<bool> revealTaskFolder(String filePath) => FileExplorer.reveal(filePath);

  @visibleForTesting
  Future<void> bringWindowToFront() async {
    await windowManager.show();
    await windowManager.focus();
  }
}
