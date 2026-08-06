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
