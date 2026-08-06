# 双审计合并修复计划（附属 1.1.1 / 主模组 1.28.1）

> 本文件合并了本地 Codex 审计与 PR #1（misaka18866，SECURITY_PERFORMANCE_AUDIT.md）的结论，作为当前唯一的审计与修复清单；替代旧 `OPTIMIZATION.md`。

## 总结

- 合并两轮审计，去重后共 18 项：P0 4 项、P1 6 项、P2 8 项。
- 交叉验证确认两条关键发现：降落点 Y 偏移（`Level#getHeight` 返回地表上方首个可用 Y，再加 1 导致正常地面全部被拒）与 wrapper jar 版本漂移（哈希为 8.1/8.1.1，声明 8.3）。
- 只修 bug/性能/加固，不加功能、不改网络协议；版本推补丁号：主模组 `1.28.1`（本地，不提交），附属 `1.1.1`（提交推送）。

## P0（先阻断）

- 降落 Y 偏移：`findLandingSpot` 的龙脚位置改为 `surfaceY`（即 `Level#getHeight` 返回值），地面方块取 `surfaceY - 1`；按龙 AABB 实际 minY 对齐；补平地/屋顶/液体/火/洞穴/上下界回归。
- 强制终止失效（S-01）：拆分 `gracefulOffline` 与 `forceEndControl`；死亡、移除、权限撤销、显式释放必须强制清理攻击目标、乘员标记、任务/阶段与运行时状态；持久任务只在“控制者暂离线且任务仍有效”时保留。
- 重规划与路径推进（P-01/R-02）：`pathIndex` 到达 waypoint 后递增，前视只遍历 `[pathIndex, end)` 并从当前段投影；重算条件改为“目标变化 / 剩余路径失效 / 到剩余路径段偏航 >8 / 连续停滞”，不再用“到最终目标距离 >8”每 20 tick 重算；新增全局规划预算（每 tick 有限展开、按 UUID 抖动错峰）与远距离任务上限。
- 紧急悬停计数：移除控制器末尾的 `emergencyTicks` 递减，找到航路时清零，恢复 100 tick 触发逻辑。

## P1

- 离线敌我 fail-closed（S-04）：`commander` 无法解析时暂停吐息并进入安全悬停，不清空已保存的控制者 UUID；可离线解析阵营时再攻击。
- 写入口本地授权（S-03/S-05）：`release/dismount/move/attack/performAction` 与技能 `activate` 在入口独立重验 `player != null`、`DragonControlPolicy.allows`、`PlayerControl.controller`、同 `ServerLevel`、坐标 finite/世界边界/最大任务距离、目标存活与敌对；离线任务走独立内部方法，不再以 `player == null` 作为授权旁路；技能先完成 `redirectVehicleMove` 成功再提交任务/冷却，失败回滚。
- 注册表 O(N²)（P-02）：`prune` 移到服务器 END tick 统一执行；位置索引按 `ServerLevel` + 空间桶维护，只查 12 格邻域；实体卸载/移除即 `remove`；消除每龙每 tick 的 `HashMap` 快照分配。
- 悬停振荡：`hoverAttack` 的期望悬停点存入运行时状态，水平距离过小时沿用上一方向，稳定成环。
- 走廊开销（P-04）：走廊结果做 2–5 tick 短缓存，先廉价粗筛再完整 AABB；状态写入仅在值变化时执行，运行时阶段放内存、必要事件才持久化。
- 主模组多选 AOE 一致性：`activateDetailed` 用合并后的最小半径/半高重建 `SkillArea`，与客户端预览一致；`SkillArea` 半径/半高做 finite + 1–64 钳制。

## P2

- 占用缓存（P-03）：TTL + 容量上限 + 维度键；目标大幅变化或规划成功时清理。
- 路径坐标加固（R-03）：改用 `Cell(x,y,z)` record 作键，差值转 double/long，Y 边界用单元中心 + 完整 AABB 校验，AABB 覆盖的全部区块都检查，入口先限世界边界与任务距离。
- TAKEOFF 覆盖：仅 TAKEOFF 达到上升高度后转 CRUISE，恢复上升走廊语义。
- 兼容范围（R-04）：`mods.toml`/`gradle.properties` 收紧为 Minecraft `[1.20.1,1.20.2)`、Forge 47.x、Ice And Fire `[1.2.7,1.2.8)`；README 统一 Dominion Sword 最低 `1.28.0`；保留 `required:true` 并记录各版本启动矩阵。
- 构建加固（S-02/B-01）：Gradle 升到已修复 CVE-2026-22865 的 8.x（首选 8.14.x，若 ForgeGradle 6.0.54 不兼容则取可通过全量构建的最低修复版本）；连续运行 wrapper 任务使 JAR/脚本/属性一致并写入官方 `distributionSha256Sum`；从仓库 `gradle.properties` 移除全局 `check.certs=false`（本地需要时经 GRADLE_OPTS 传入）；生成 `verification-metadata.xml` 与仓库内容过滤；三个本地 jar 要求版本 + SHA-256 清单，构建前校验。
- 测试（T-01）：适配器契约测试（错误控制者/空调用者/跨维度/越界/非有限坐标 → 拒绝且状态零变化）、降落 Y 偏移回归、路径推进与重算条件、紧急悬停计数、多选 AOE 合并规格；手动客户端/专用服务端启动矩阵与 1/10/25/50 龙性能记录。

## 测试计划

- 单元：上述契约与纯逻辑项全部纳入 JUnit；主模组 `1.28.1` 全量测试、庞科特附属编译回归。
- 构建：Gradle 升级后干净全量 `build` + `:test` 通过；wrapper 哈希与 `distributionSha256Sum` 校验；本地 jar 哈希清单通过。
- 实机：平原降落 GameTest、无解空间 100 tick 进入紧急悬停、悬停成环、权限撤销/易主/释放/死亡后下一 tick 清除控制、离线敌我暂停攻击、1/10/25/50 龙 MSPT。

## 假设与默认值

- 主模组仓库因含用户未提交工作，`1.28.1` 只落本地；附属 `1.1.1` 照常提交推送。
- Gradle 具体版本以实现时全量构建通过为准；`required:true` 保留（fail-fast）。
- 离线敌对判定 fail-closed（暂停攻击）作为确定行为。
- 不新增技能、机动或 UI；不配置 CI（dominionsword jar 不公开），但构建加固项全部落地。
