# Dominion Sword: Ice And Fire Compat

Ice And Fire CE（1.2.7）的龙通过 `DominionVehicleAdapter` 接入 Dominion Sword，作为可自驱载具受令。

仓库：https://github.com/kawer95/dominionsword-iceandfire-compat

要求：Forge 1.20.1、Dominion Sword 1.27.0+、Ice And Fire CE 1.2.7（Forge）、Uranus 2.x。

## 构建

下载 Ice And Fire CE 1.2.7 Forge 与 Uranus 2.x 的 jar 放入 `libs/`（该目录已被 git 忽略），然后：

```powershell
.\gradlew.bat build `
  -Pdominionsword_jar="..\DominionSword-1.20.1\build\libs\dominionsword-1.27.0.jar" `
  -Piaf_jar="libs\IceAndFireCE-1.2.7-1.20.1-forge.jar" `
  -Puranus_jar="libs\uranus-2.2.6-bugfix.2-1.20.1-forge.jar"
```

也可以通过 `-Piaf_jar` / `-Puranus_jar` 直接指向磁盘上任意位置的 jar。

## 兼容范围

- 已对照 Ice And Fire CE `[1.2.7, 1.3)` 的 Forge 发布版 jar 验证 Mixin 目标方法签名。
- 需要 Dominion Sword `[1.27.0,)`（`canSelfDrive` / `actionEnabled` 车辆 API）。

## 构建与测试（未配置 CI）

dominionsword 主模组 jar 不公开，仓库不配置 CI；发布前按以下步骤做一次干净全量构建：

1. 在全新或清空本机 Gradle 缓存的目录中检出代码。
2. 准备 `dominionsword-1.27.0.jar`、`IceAndFireCE-1.2.7-1.20.1-forge.jar`、`uranus-2.2.6-bugfix.2-1.20.1-forge.jar`。
3. 执行 `.\gradlew.bat clean build -Pdominionsword_jar=... -Piaf_jar=... -Puranus_jar=...`。
4. `:test` 任务（JUnit 5）应全部通过，`reobfJar` 产出 `build/libs/dominionsword_iceandfire_compat-1.0.1.jar`。
