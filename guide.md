下面给你一套「能落地」的安卓实现思路：当系统刚把截图保存进相册时，立刻给这张图片**重命名**，在原文件名后面追加当前前台应用的包名，例如把  
`Screenshot_20240701_081605.jpg` → `Screenshot_20240701_081605_com.duolingo.jpg`。同时我也给出完整的权限与版本适配点、关键代码框架，以及可能踩的坑。

---

# 整体设计（兼容 Android 8–14/15）

**核心做法（无需“监听按键”）**  
1) **用 `ContentObserver` 监听 MediaStore**：注册到 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`，只要系统库里新增了图片，就收到回调；再通过筛选确认这是一张“截图”而非普通照片。这个方式是社区常见做法之一（相比 `FileObserver` 更稳），并且是官方推荐的数据变化监听机制。([Android Developers](https://developer.android.com/reference/android/database/ContentObserver?utm_source=chatgpt.com), [Grokking Android](https://www.grokkingandroid.com/use-contentobserver-to-listen-to-changes/?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/35859816/how-to-listen-new-photos-in-android?utm_source=chatgpt.com))

2) **判断是否为“截图”**：  
   - 优先看 `MediaStore.MediaColumns.RELATIVE_PATH` 是否包含 `Pictures/Screenshots/`（Android 10+ 提供），或  
   - 查看 `Images.ImageColumns.BUCKET_DISPLAY_NAME` 是否是 `Screenshots`（多机型兼容；只读列）。([Android Developers](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns?utm_source=chatgpt.com), [Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.provider.mediastore.mediacolumns.bucketdisplayname?view=net-android-35.0&utm_source=chatgpt.com))

3) **拿到当前前台应用包名**：使用 **`UsageStatsManager`** 读取最近的 `MOVE_TO_FOREGROUND` 事件，需引导用户在「设置 → 应用使用情况访问权限」里给你的 App 授权 `PACKAGE_USAGE_STATS`。官方文档明确这是系统级权限，需要用户手动开启；在 Android 14 上偶尔会有滞后/不稳定，实际项目里要做好兜底。([Android Developers](https://developer.android.com/reference/android/app/usage/UsageStatsManager?utm_source=chatgpt.com), [Android Git Repositories](https://android.googlesource.com/platform/developers/build/%2B/master/prebuilts/gradle/AppUsageStatistics/README.md?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/77410929/getting-foreground-app-not-working-on-android-14?utm_source=chatgpt.com))

4) **重命名图片文件**：基于 **`MediaStore`** 的 `ContentResolver.update()` 更新该条目的 `DISPLAY_NAME`。  
   - 访问其它 App 创建的媒体文件时（这正是“系统截图”），Android 10+ 会触发 **`RecoverableSecurityException`**，你需要通过 **`MediaStore.createWriteRequest()`** 发起**一次性**写入授权弹窗，然后再执行 `update()` 完成重命名。([commonsware.com](https://commonsware.com/R/pages/chap-mediastore-003.html?utm_source=chatgpt.com), [Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.provider.mediastore.createwriterequest?view=net-android-35.0&utm_source=chatgpt.com), [Android Developers](https://developer.android.com/reference/android/app/RecoverableSecurityException?utm_source=chatgpt.com))

> 说明：Android 14 新增的“截图检测 API”**只在你的 Activity 可见时**回调，用来提示“有人在你的应用里截图”，**并不能**用来全局监听系统截图或获知文件路径，因此不适合本需求。([Android Developers](https://developer.android.com/about/versions/14/features/screenshot-detection))

---

# 权限与清单（Manifest）配置

```xml
<!-- 读取别的应用创建的图片（Android 13+ 为细粒度媒体权限） -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<!-- 兼容旧系统：Android 12 及以下 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>

<!-- 用于前台应用使用情况（需要用户在设置里手动开启许可） -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions"/>

<!-- 后台常驻监听（建议使用前台服务+通知） -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

- 申请 `PACKAGE_USAGE_STATS` 后，还需跳转到 `Settings.ACTION_USAGE_ACCESS_SETTINGS` 引导用户打开“使用情况访问”授权；这是官方要求。([Android Developers](https://developer.android.com/reference/kotlin/android/app/usage/UsageStatsManager?utm_source=chatgpt.com))  
- Android 13 起媒体权限被拆分为 `READ_MEDIA_IMAGES/VIDEO/AUDIO`，不再用宽泛的 `READ_EXTERNAL_STORAGE`（仅用于旧系统）。([Android Developers](https://developer.android.com/about/versions/13/behavior-changes-13?utm_source=chatgpt.com))

---

# 关键流程与代码骨架（Kotlin）

### 1) 注册 `ContentObserver` 监听新增图片

```kotlin
class ScreenshotWatcherService : LifecycleService() {

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // 建议做节流与去抖：onChange 可能多次触发
            GlobalScope.launch(Dispatchers.IO) {
                handleMediaStoreChange(uri)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // 递归子项
            observer
        )
        // 启动为前台服务，保证稳定运行
        startForegroundWithNotification()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }
}
```

> 注意：`ContentObserver.onChange()` 可能针对一次写入多次回调，需做**去重/节流**；业界也有类似反馈。([Stack Overflow](https://stackoverflow.com/questions/22012274/contentobserver-onchange-method-gets-called-many-times?utm_source=chatgpt.com))

### 2) 判断是否“刚刚新增的截图”

```kotlin
private fun queryRecentScreenshot(context: Context, hintUri: Uri?): Pair<Uri, String>? {
    val now = System.currentTimeMillis() / 1000 // MediaStore 的 DATE_ADDED 是秒
    val selection = buildString {
        append("${MediaStore.Images.Media.DATE_ADDED} >= ? AND ")
        // 兼容不同厂商目录：优先 RELATIVE_PATH，其次 BUCKET_DISPLAY_NAME
        append("(")
        append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ")
        append(" OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?")
        append(")")
    }
    val args = arrayOf((now - 10).toString(), "%/Screenshots/%", "Screenshots")
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
    )?.use { c ->
        if (c.moveToFirst()) {
            val id = c.getLong(0)
            val name = c.getString(1)
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
            )
            return uri to name
        }
    }
    return null
}
```

- `RELATIVE_PATH`/`BUCKET_DISPLAY_NAME` 字段说明参见官方/参考文档。([Android Developers](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns?utm_source=chatgpt.com), [Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.provider.mediastore.mediacolumns.bucketdisplayname?view=net-android-35.0&utm_source=chatgpt.com))

### 3) 获取当前前台应用包名（`UsageStatsManager`）

```kotlin
@SuppressLint("WrongConstant")
private fun getTopPackageName(ctx: Context): String? {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val end = System.currentTimeMillis()
    val begin = end - 60_000 // 最近 1 分钟
    val events = usm.queryEvents(begin, end)
    val e = UsageEvents.Event()
    var top: String? = null
    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            top = e.packageName
        }
    }
    return top
}
```

> 首次使用需引导用户在系统设置打开“应用使用情况访问”权限；Android 官方文档与示例项目都说明了这一点。Android 14 上有开发者反馈偶发不返回最新前台进程，要做好兜底（例如取**最近一次** `MOVE_TO_FOREGROUND` 事件并增加时间窗口）。([Android Developers](https://developer.android.com/reference/kotlin/android/app/usage/UsageStatsManager?utm_source=chatgpt.com), [Android Git Repositories](https://android.googlesource.com/platform/developers/build/%2B/master/prebuilts/gradle/AppUsageStatistics/README.md?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/77410929/getting-foreground-app-not-working-on-android-14?utm_source=chatgpt.com))

### 4) 执行“重命名”——更新 `DISPLAY_NAME`

```kotlin
private suspend fun renameWithPackageSuffix(
    context: Context,
    uri: Uri,
    originalName: String,
    pkg: String
): Boolean {
    // 已经改过名就跳过
    if (originalName.contains("_$pkg")) return true

    val dot = originalName.lastIndexOf('.')
    val (base, ext) = if (dot > 0) originalName.substring(0, dot) to originalName.substring(dot)
                      else originalName to ""

    val newName = "${base}_${pkg}${ext}"
    val cv = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
    }
    return try {
        context.contentResolver.update(uri, cv, null, null) > 0
    } catch (e: RecoverableSecurityException) {
        // 需要用户一次性授权后再执行
        val pi = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
        // 这里建议发通知→点击后启动 Activity 调用 startIntentSenderForResult()
        // 授权成功后再调一次 update()
        false
    }
}
```

- “编辑第三方媒体文件”在 Android 10+ 会抛 **`RecoverableSecurityException`**，必须通过 **`MediaStore.createWriteRequest()`** 获取**用户同意**后才能修改（包含重命名）。这套流程是官方与社区广泛采用的做法。([Android Developers](https://developer.android.com/reference/android/app/RecoverableSecurityException?utm_source=chatgpt.com), [commonsware.com](https://commonsware.com/R/pages/chap-mediastore-003.html?utm_source=chatgpt.com), [Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.provider.mediastore.createwriterequest?view=net-android-35.0&utm_source=chatgpt.com))

---

# 运行与用户体验建议

- **服务形态**：建议用**前台服务**（带常驻通知）或 `WorkManager` 周期性拉取+粘合 `ContentObserver`，避免被系统杀进程。  
- **授权时机**：首次运行时请求 `READ_MEDIA_IMAGES`（或旧系统的存储权限），并引导开启“使用情况访问”。当第一次遇到重命名时，再触发系统的**写入授权对话框**（CreateWriteRequest），之后就能顺利更新。([Android Developers](https://developer.android.com/about/versions/13/behavior-changes-13?utm_source=chatgpt.com), [commonsware.com](https://commonsware.com/R/pages/chap-mediastore-003.html?utm_source=chatgpt.com))
- **多机型目录差异**：三星等机型可能把截图放在 `DCIM/Screenshots`，所以用 `RELATIVE_PATH LIKE %Screenshots%` 或 `BUCKET_DISPLAY_NAME='Screenshots'` 组合判断最稳。([Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.provider.mediastore.mediacolumns.bucketdisplayname?view=net-android-35.0&utm_source=chatgpt.com))
- **稳定性**：`ContentObserver.onChange()` 可能多次触发；对“最近 5–10 秒新增、且未处理过的项”做去重即可。([Stack Overflow](https://stackoverflow.com/questions/22012274/contentobserver-onchange-method-gets-called-many-times?utm_source=chatgpt.com))
- **为什么不用 Android 14 的“截图检测 API”？** 因为它**仅在你的 Activity 可见时**回调，并且只是告诉“有人在你的应用里截图”，并不提供文件路径或系统相册事件，不能满足“全局重命名系统截图”的诉求。([Android Developers](https://developer.android.com/about/versions/14/features/screenshot-detection))

---

# 版本/权限速查表

- **读取相册截图**：  
  - Android 13+ → `READ_MEDIA_IMAGES`。  
  - Android 12- → `READ_EXTERNAL_STORAGE`（兼容旧版）。([Android Developers](https://developer.android.com/about/versions/13/behavior-changes-13?utm_source=chatgpt.com))
- **修改相册文件名（第三方创建）**：  
  - 走 `ContentResolver.update(DISPLAY_NAME)`，如遇 `RecoverableSecurityException` → `MediaStore.createWriteRequest()` 请求**精细写入授权**。([Android Developers](https://developer.android.com/reference/android/app/RecoverableSecurityException?utm_source=chatgpt.com), [commonsware.com](https://commonsware.com/R/pages/chap-mediastore-003.html?utm_source=chatgpt.com))
- **拿前台应用包名**：  
  - `UsageStatsManager` + `PACKAGE_USAGE_STATS`（用户在设置中手动开启）。注意 Android 14 可能偶发延迟/不准确。([Android Developers](https://developer.android.com/reference/kotlin/android/app/usage/UsageStatsManager?utm_source=chatgpt.com), [Android Git Repositories](https://android.googlesource.com/platform/developers/build/%2B/master/prebuilts/gradle/AppUsageStatistics/README.md?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/77410929/getting-foreground-app-not-working-on-android-14?utm_source=chatgpt.com))

---

# 可能的边界/策略

- **同名冲突**：如果目标文件名已存在（例如短时间多次截同一画面），可在包名前再追加一个去重计数或时间毫秒后缀。  
- **Play 上架合规**：这个功能需要“读取媒体”和“使用情况访问”权限；如在商店上架，务必在应用内明确用途与可关闭开关，避免被判定为“过度收集/干预他应用”。（属于产品合规建议）

---

# 给你的 App 起名 & 包名

- **中文名**：`包名截图助手`（简洁清楚）  
- **英文名**：`PkgShot Renamer`  
- **应用包名建议**（二选一）：  
  - `com.lisan.pkgshot`（通用、好记）  
  - `io.lisan.screenshottagger`（技术感强）

---

## 参考资料
- Android 14 “截图检测 API”：作用范围仅限**当前 Activity 可见时**回调，适合自家 App 场景提醒，不适合全局重命名系统截图。([Android Developers](https://developer.android.com/about/versions/14/features/screenshot-detection))  
- `ContentObserver` 用于监听媒体库变化（更推荐于 `FileObserver`），以及其多次触发的特性与注意点。([Grokking Android](https://www.grokkingandroid.com/use-contentobserver-to-listen-to-changes/?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/35859816/how-to-listen-new-photos-in-android?utm_source=chatgpt.com))  
- `READ_MEDIA_IMAGES` 等 Android 13+ 细粒度媒体权限；行为变化与申请方式。([Android Developers](https://developer.android.com/about/versions/13/behavior-changes-13?utm_source=chatgpt.com))  
- `RELATIVE_PATH` / `BUCKET_DISPLAY_NAME` 字段及其用途（识别“Screenshots”相册）。([Android Developers](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns?utm_source=chatgpt.com), [Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/api/android.provider.mediastore.mediacolumns.bucketdisplayname?view=net-android-35.0&utm_source=chatgpt.com))  
- 通过 `ContentResolver.update()` 修改 `DISPLAY_NAME`，以及 Android 10+ 需要的 `RecoverableSecurityException` / `MediaStore.createWriteRequest()` 写入授权流程。([commonsware.com](https://commonsware.com/R/pages/chap-mediastore-003.html?utm_source=chatgpt.com), [Android Developers](https://developer.android.com/reference/android/app/RecoverableSecurityException?utm_source=chatgpt.com))  
- `UsageStatsManager` 获取前台包名所需权限与设置页引导；实测在 Android 14 存在不稳定反馈。([Android Developers](https://developer.android.com/reference/kotlin/android/app/usage/UsageStatsManager?utm_source=chatgpt.com), [Android Git Repositories](https://android.googlesource.com/platform/developers/build/%2B/master/prebuilts/gradle/AppUsageStatistics/README.md?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/77410929/getting-foreground-app-not-working-on-android-14?utm_source=chatgpt.com))

---

如果你愿意，我可以把上述代码骨架整理成一个**最小可运行的示例工程**（Kotlin，前台服务+通知），包含权限引导页、去抖与冲突处理、以及一次性写入授权的用户交互。