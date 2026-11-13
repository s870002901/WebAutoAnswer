# WebAutoAnswer

LSPosed 模块：把 `assets/inject.js` 注入目标 App（云端学习）的 WebView，用于跑自动答题脚本。

## 目录结构

```
WebAutoAnswer/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── .github/workflows/build.yml
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── xposed_init        # 入口类全名，无扩展名
        │   └── inject.js          # 注入脚本
        ├── java/com/example/webautoanswer/
        │   └── MainHook.java
        └── res/values/arrays.xml  # xposed_scope，给 LSPosed 自动勾选作用域用
```

## 打包前必改：目标包名

`com.tbc.android.defaults` 是猜测值，**必须换成实际包名**，共两处：

1. `app/src/main/java/com/example/webautoanswer/MainHook.java` → `TARGET_PKG`
2. `app/src/main/res/values/arrays.xml` → `xposed_scope` 里的 `<item>`

用 MT 管理器打开云端学习 APK，查看 `AndroidManifest.xml` 的 `package` 属性。包名不一致时模块不会命中任何进程（日志里也不会有命中记录）。

## 构建

本地构建：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI 构建：推到 GitHub 的 main/master 分支，或在 Actions 页面手动 `Run workflow`，
产物在 Artifacts 里（`WebAutoAnswer-debug-apk`）。用 debug 包是为了免签名流程，LSPosed 对 debug 签名没有限制。

## 安装与启用

1. 安装 APK（模块本身没有 Activity，桌面不会有图标，属正常）。
2. LSPosed 里启用模块，作用域勾选云端学习。
3. 强制停止云端学习后重新打开。
4. 日志确认（`adb logcat -s XposedBridge` 或 LSPosed 日志）：
   - `[WebAutoAnswer] 命中目标: ...`
   - `[WebAutoAnswer] Hook WebView#setWebViewClient 成功`
   - `[WebAutoAnswer] inject.js 加载完成，长度=...`
   - `[WebAutoAnswer] 系统 WebView 注入完成: https://...`

注入成功的肉眼标志：页面右下角出现绿色 `WAA boot-1 | <url>` 角标（`inject.js` 里 `SHOW_BADGE` 控制）。

## inject.js 换脚本

当前 `inject.js` 是**链路自检占位版**（防重复注入 + SPA 路由监听 + 角标），确认链路通之后，
把你的完整答题脚本整段替换进去即可。`assets` 不参与 dex 编译，只改 JS 不需要改 Java 代码，
但仍需重新执行一次 `assembleDebug` 打包安装。

## 与原始草稿的差异（已修正）

| 位置 | 原始问题 | 已处理 |
| --- | --- | --- |
| `settings.gradle` | 缺 `pluginManagement` 块、`include ':app'` 重复 | 补 `pluginManagement`，仓库只 include 一次 |
| `settings.gradle` | 缺 Xposed API 仓库，`de.robv.android.xposed:api:82` 无法解析 | 加 `maven { url 'https://api.xposed.info/' }` |
| `app/build.gradle` | 用 AGP 8.x 已废弃的 `packagingOptions`；`dependencies` 末尾被截断 | 改 `packaging {}`，依赖补全 |
| `AndroidManifest.xml` | 根标签重复、`android:supports` 残行 | 重写为合法 XML，并加 `xposed_scope` |
| `MainHook.java` | `import` 段残缺、`package` 后无包名 | 完整重写 |
| `MainHook.java` | 只 Hook `WebViewClient` 基类 `onPageFinished`，App 子类重写后**根本不会触发** | 改为拦截 `setWebViewClient`，沿实例类的继承链逐层 Hook |
| `MainHook.java` | `getCodeSource().getLocation().getPath()` 未 URL 解码，含空格/中文路径必失败 | 加 `URLDecoder`，并补「模块 Context 读 assets」兜底 |
| `build.yml` | `gradle wrapper` 依赖 runner 预装 gradle（实际没有），`uses` 行重复 | 改 `gradle/actions/setup-gradle` 提供 gradle |

## 已知限制

- **TBS 私有 dex**：若目标 App 的 X5/TBS 内核是从私有目录动态加载的（类不在 App 自己的 classLoader 里），
  `findClass("com.tencent.smtt.sdk.WebView", lpparam.classLoader)` 找不到，此路径的 Hook 会失败（日志里会打印）。
  此时若注入不生效，需要改成从 TBS 的 DexClassLoader 取类（或退到 X5 的 `QbSdk` 初始化时机再 Hook）。
- **不注入 iframe / 子 frame**：`evaluateJavascript` 只作用于主 frame。
- 若页面在 `onPageFinished` 之后仍继续异步加载（SPA 常见），依赖 `inject.js` 自己的路由监听补触发，
  不要在 Java 侧加延时重复注入。
