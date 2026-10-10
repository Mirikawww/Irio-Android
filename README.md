# Irio Android

BYOK AI 聊天应用的 Android 项目。Windows 项目独立维护于 [Irio-Windows](https://github.com/Mirikawww/Irio-Windows)。

## 开发

需要 JDK 21 和 Android SDK。配置本机 `local.properties` 后运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

模型目录：`python scripts/generate-catalog.py`；仅刷新思考能力：追加 `--reasoning-only`。

本仓库从原 Irio 仓库拆分，保留原有提交历史与 Android 版本标签。
