# Dominion Sword: Ice And Fire Compat 安全与性能审计

> 初次审计：2026-08-06；复审日期：2026-08-10
> 审计对象：`kawer95/dominionsword-iceandfire-compat`
> 复审基线：`f963c4a`（`main`）
> 审计范围：仓库内 Java 源码、Mixin、资源与 Gradle 构建链；不包含未入库的 Dominion Sword、Ice And Fire CE、Uranus 二进制实现
> 结论等级：**高风险，建议先修复 P0/P1 项再扩大服务器部署规模**

## 1. 执行摘要

两次审计均未在仓库源码及当前四次提交历史中发现硬编码令牌、私钥、密码、主动联网、命令执行或 Java 原生反序列化代码。资源 JSON 均可解析，Gradle Wrapper JAR 的 SHA-256 也能匹配 Gradle 官方已知文件，因此目前没有证据表明仓库被植入恶意代码。

复审确认：`9a1fefb..f963c4a` 没有源码变化，只新增了 `AUDIT_AND_FIX_PLAN.md`；因此原有安全和性能问题均仍存在。主要风险集中在五个方面：

1. **TPS/拒绝服务风险**：飞行控制器在龙距离目标超过 8 格时，每 20 tick 重新执行一次最多 1024 节点、每节点 26 邻居的 3D A*；龙群分离逻辑又按每条龙扫描和复制全量状态，整体会随龙数量呈平方级增长。
2. **权限撤销失效**：`endControl` 遇到持久任务会无条件返回。即使调用原因是权限已撤销、龙已死亡、玩家显式释放或控制者无效，也不会真正终止控制。
3. **构建供应链风险**：声明的 Gradle 8.3 处于两个已公开漏洞的影响范围，且项目没有分发包校验值、依赖验证元数据或严格仓库内容过滤。
4. **飞行可用性问题**：降落点存在确定的 Y 坐标偏移错误；路径跟随没有推进 `pathIndex`；路径规划将“网格 Y”与“方块 Y”直接比较。这些问题会导致无法降落、折返、无效重算或越过世界高度边界。
5. **状态机与预算失真**：控制器尾部使用 tick 开始时的旧 `phase` 覆盖 `dispatch` 内的新状态，导致起飞首 tick 结束、自动降落阶段被反复覆盖；紧急悬停计数由三处同时加减；所谓节点/读取硬预算没有覆盖 AABB 碰撞体积和路径平滑采样。

建议的修复顺序：

- 复审去重后共 **21 项**：P0 5 项、P1 7 项、P2 9 项；其中 18 项已由仓库源码直接确认，3 项因依赖上游调用链或第三方实体行为而标记为“可能存在”。
- **P0**：S-01、P-01、R-01、R-02、R-05。
- **P1**：S-02、S-03、S-04、P-02、P-05、R-06、R-07。
- **P2**：S-05、P-03、P-04、R-03、R-04、R-08、R-09、B-01、T-01。

## 2. 方法与限制

本次工作包括：

- 对当前 20 个主 Java 文件和 3 个测试文件再次逐文件静态审阅；
- 检查权限入口、离线任务、实体生命周期、Mixin 注入点、持久状态和客户端同步；
- 检查每 tick 热路径、碰撞查询、实体扫描、路径规划、缓存和对象分配；
- 解析全部 JSON 资源，并对当前四次提交的完整 Git 历史执行仅报告命中文件名的常见凭据特征扫描；
- 计算 `gradle-wrapper.jar` SHA-256，并与 Gradle 官方校验表对照；
- 通过 Gradle 官方 GitHub Security Advisory API 再次核对固定构建工具版本，并从 `services.gradle.org` 获取 Wrapper/发行包官方 SHA-256；
- 使用本地 Forge 1.20.1 映射 JAR 的字节码确认 `Level#getHeight` 返回表面上方第一个可用 Y。
- 将 `AUDIT_AND_FIX_PLAN.md` 的合并结论逐项回查到源码；其中“只删除 `emergencyTicks` 递减”的建议会让当前双重递增从约 100 tick 变为约 50 tick，需按本报告 R-06 改为单一计数所有者。

限制如下：

- `dominionsword_jar`、`iaf_jar`、`uranus_jar` 均由本地路径提供且未入库，无法对这些二进制依赖做完整 SBOM/CVE 和调用契约审计。
- 因缺少上述三个精确依赖，无法执行可信的全量编译、JUnit、GameTest 或专用服务端集成测试。仅把可由仓库源码直接证明的问题标记为“已确认”。
- `./gradlew --version` 在下载仓库声明的 Gradle 8.3 发行包时超过 124 秒超时；下载的忽略目录已清理，未把不完整产物纳入提交。
- S-03、S-05 的网络可利用性依赖 Dominion Sword 主模组是否在进入适配器前完成了不可绕过的服务端校验，因此标记为“可能存在”；但兼容模组本身缺少纵深校验是确定事实。

## 3. 风险总览

| ID | 状态 | 等级 | 类别 | 摘要 |
|---|---|---:|---|---|
| S-01 | 已确认 | 高 | 授权 | 持久任务可阻止权限撤销、显式释放和无效实体清理 |
| S-02 | 已确认 | 高（构建环境） | 供应链 | Gradle 8.3 受已公开高危/中危漏洞影响且没有依赖验证 |
| S-03 | 可能存在 | 中 | 授权 | 多个写操作入口依赖上游校验，技能在授权结果前先修改状态 |
| S-04 | 已确认 | 中 | 游戏完整性 | 控制者离线时敌我判断默认放行，持久攻击可伤害不应攻击的目标 |
| S-05 | 可能存在 | 中 | 输入校验 | 坐标、范围、维度和攻击目标缺少本地边界校验 |
| P-01 | 已确认 | 高 | 性能 | 远距离飞行每秒重跑有硬上限但仍昂贵的 3D A* |
| P-02 | 已确认 | 高 | 性能 | 注册表清理和多龙分离为 O(N²)，并产生大量临时 Map/Vec3 |
| P-03 | 已确认 | 中 | 性能/正确性 | 占用缓存不设上限、不失效，长期增长且保留过期碰撞结果 |
| P-04 | 已确认 | 中 | 性能 | 每条受控龙每 tick 最多 36 次完整 AABB 走廊碰撞检查并频繁写 NBT |
| P-05 | 已确认 | 中 | 性能/拒绝服务 | A* 与降落的“硬预算”没有覆盖 AABB 体积、平滑采样和全部区块读取 |
| R-01 | 已确认 | 高 | 可用性 | 降落点 Y 多加 1，正常实心地面会被当作空气拒绝 |
| R-02 | 已确认 | 高 | 正确性/性能 | 路径索引从不推进，前视算法反复从旧起点扫描并可能引导折返 |
| R-03 | 已确认 | 中 | 正确性 | 网格 Y 边界单位错误，远坐标键回绕，启发式存在整数溢出 |
| R-04 | 已确认 | 中 | 兼容性 | 依赖范围远宽于实际验证范围，required Mixin 失败会阻止启动 |
| R-05 | 已确认 | 高 | 状态机/性能 | tick 尾部用旧 phase 覆盖起飞和自动降落过程中刚写入的新 phase |
| R-06 | 已确认 | 中 | 状态机 | 紧急计数一轮内两次递增、一次递减，修复计划若只删递减会把阈值减半 |
| R-07 | 已确认 | 中 | 游戏完整性/性能 | 正常解除控制会删除持久化的 900 tick 掠袭冷却，可通过重新选择绕过 |
| R-08 | 可能存在 | 中 | 降落安全 | 流体和火焰只检查候选中心列，没有覆盖大型龙的完整落地足迹 |
| R-09 | 已确认 | 中 | 飞控稳定性 | 龙越过目标水平中心时悬停方向瞬间翻转，可能在目标两侧持续振荡 |
| B-01 | 已确认 | 中 | 供应链 | Wrapper JAR 与声明版本不一致，分发包无 SHA-256 固定 |
| T-01 | 已确认 | 中 | 质量保障 | 测试未覆盖真实降落、路径、权限撤销、Mixin 和龙群性能 |

## 4. 详细发现

### S-01：持久任务使强制终止失效

**状态：已确认｜等级：高｜优先级：P0**

证据：

- [`DragonAutopilot.java:31`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonAutopilot.java#L31) 在权限不再允许、实体被移除、死亡等情况下调用 `endControl`。
- [`DragonAutopilot.java:55`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonAutopilot.java#L55) 只要 `hasPersistentVehicleTask` 为真就直接 `return`，没有区分“玩家暂时离线”和“必须强制撤销”。
- [`IceAndFireDragonVehicleAdapter.java:84`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonVehicleAdapter.java#L84) 的显式 `release` 也调用同一入口，因此持久任务可能令玩家无法释放。
- [`DominionSwordIceAndFireCompatMod.java:77`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/DominionSwordIceAndFireCompatMod.java#L77) 在实体载入时把离线控制者视为无效，但随后仍可能被上述持久任务分支短路。

影响：权限模式变更、龙易主、控制列表撤销、实体死亡或显式释放后，旧任务仍可继续控制龙。这破坏了 `DragonControlPolicy` 作为统一授权点的设计，也可能持续占用运行时状态和每 tick 预算。

建议：

1. 拆分 `gracefulOffline` 与 `forceEndControl`；死亡、移除、权限撤销、显式释放必须走强制清理。
2. 仅在“控制者暂时离线且持久任务仍有效”时保留任务。
3. 离线校验直接使用已有的 `DragonControlPolicy.allows(UUID, dragon)`，并同时核对持久化的控制者 UUID。
4. 强制终止时清理攻击目标、乘员标记、任务/阶段和 `DragonFlightRegistry` 状态。
5. 添加测试：权限从允许切为拒绝、龙易主、玩家释放、实体死亡、玩家离线后重载区块。

### S-02：Gradle 8.3 落入已公开漏洞范围，且无完整性约束

**状态：已确认｜等级：高（构建环境）｜优先级：P1**

证据：

- [`gradle-wrapper.properties:3`](gradle/wrapper/gradle-wrapper.properties#L3) 固定下载 Gradle 8.3。
- [Gradle 官方 GHSA-mqwm-5m85-gmcv / CVE-2026-22865](https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv) 将 `< 8.14.4` 列为受影响版本，严重度 High。仓库响应异常时，Gradle 可能继续从后续仓库解析同名恶意构件；官方建议升级或启用严格内容过滤/依赖验证。
- [Gradle 官方 GHSA-mrff-q8qj-xvg8 / CVE-2023-42445](https://github.com/gradle/gradle/security/advisories/GHSA-mrff-q8qj-xvg8) 将 `< 8.4` 列为受影响版本；解析特定 XML 时可能发生 XXE，本地文本可能被带外泄露。
- 项目没有 `gradle/verification-metadata.xml`，也没有仓库内容过滤或依赖锁定。

影响：风险作用于开发机和 CI，而不是已发布模组的 Minecraft 运行时。利用通常需要仓库故障、攻击者控制后续仓库/构件，或构建解析恶意 XML；但构建进程可读取源码、本地凭据和签名材料，因此应按高价值供应链入口处理。

建议：

1. 在 ForgeGradle 6 的 Gradle 8.x 支持范围内升级到 **8.14.4 或更高的 8.x 安全版本**，并完成构建回归。
2. 生成并审阅 `gradle/verification-metadata.xml`，严格验证插件、Maven 依赖及元数据。
3. 对仓库使用 `exclusiveContent`/content filter，避免 Forge、Gradle Plugin Portal、Maven Central 之间的命名空间串仓。
4. 对三个本地 JAR 要求明确版本与 SHA-256；构建前校验，不接受只由任意路径决定的未验证二进制。
5. 升级时审阅 Wrapper JAR 与 distribution 校验值，不把自动生成结果直接视为可信基线。

### S-03：写操作入口缺少本地纵深授权

**状态：可能存在｜等级：中｜优先级：P1**

证据：

- [`IceAndFireDragonVehicleAdapter.java:84`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonVehicleAdapter.java#L84) 的 `release` 不检查调用玩家。
- [`IceAndFireDragonVehicleAdapter.java:109`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonVehicleAdapter.java#L109) 的 `dismount` 不检查权限或当前控制者。
- [`IceAndFireDragonVehicleAdapter.java:188`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonVehicleAdapter.java#L188) 的 `performAction` 不调用 `validateAction`，可直接起飞、降落或切换自动控制。
- [`IceAndFireDragonSkillProvider.java:62`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonSkillProvider.java#L62) 在 `redirectVehicleMove` 返回授权/接受结果之前已经修改任务、任务模式和冷却；失败也不回滚。

影响：如果 Dominion Sword 的任何当前或未来调用路径漏掉前置校验，其他模组、错误的包处理器或恶意客户端可借此改变非本人龙的状态。由于主模组二进制未纳入审计，不能断言现有网络路径已经可利用。

建议：所有公开写入口都独立验证 `player != null`、`DragonControlPolicy.allows`、`PlayerControl.controller`、实体属于该玩家控制集合和动作当前可用性。离线任务使用单独的内部方法和不可伪造的持久任务上下文，不要用 `player == null` 作为授权旁路。技能应先验证并成功重定向，再提交任务/冷却；或在失败时完整回滚。

### S-04：离线控制者使敌我校验默认放行

**状态：已确认｜等级：中｜优先级：P1**

证据：[`DragonFlightController.java:300`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L300) 在无法取得在线 `ServerPlayer` 时直接返回 `true`；悬停攻击每 5 tick 可调用一次该判断和龙息。

影响：持久攻击任务在控制者离线后，可能继续对已变为友军、属于同阵营或不应攻击的目标喷火。它与 S-01 的持久任务短路组合时风险更高。

建议：敌我关系无法确定时应 **fail closed**：暂停攻击而不是默认敌对。持久任务应保存控制者 UUID，并通过可离线解析的阵营/所有权服务判断；无法解析时清除攻击目标或进入安全悬停。

### S-05：坐标、范围、维度和目标缺少本地边界校验

**状态：可能存在｜等级：中｜优先级：P2**

证据：

- [`IceAndFireDragonVehicleAdapter.java:124`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonVehicleAdapter.java#L124) 的移动仅检查 `target != null`，未拒绝 NaN、Infinity、世界边界外或超距坐标。
- [`IceAndFireDragonVehicleAdapter.java:135`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonVehicleAdapter.java#L135) 的攻击仅要求目标存活，未本地检查同维度、范围和敌对性。
- [`IceAndFireDragonSkillProvider.java:55`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonSkillProvider.java#L55) 接受任意非空目标位置，技能展示的 96 格范围没有在本类重新验证。

影响：若上游包校验可绕过，异常 double 会污染 NBT、路径键、速度和旋转；极远坐标会放大 A*、平滑与缓存成本；跨维度/超距目标会留下无法完成的任务。

建议：在适配器边界统一验证 `Double.isFinite`、世界边界、构建高度、最大任务距离、相同 `ServerLevel`、目标存活/敌对，以及玩家到命令对象的允许距离。验证失败不得修改任何持久状态。

### P-01：远距离飞行每 20 tick 重跑 3D A*

**状态：已确认｜等级：高｜优先级：P0**

证据：

- [`DragonFlightController.java:101`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L101) 的重算条件在间隔到期后检查的是“龙到最终目标距离 > 8 格”，并不是注释/变量名暗示的“偏离当前路径 > 8 格”。远距离飞行时该条件持续成立。
- [`DragonPathPlanner.java:25`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L25) 每次最多展开 1024 节点；[`DragonPathPlanner.java:70`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L70) 每节点检查 26 个邻居，之后还会逐格做路径平滑碰撞检查。

影响：每条远距离飞行龙每秒最多触发一次大量优先队列、HashMap/HashSet 和碰撞工作。玩家可以通过合法的远距离移动命令持续制造主线程压力；多龙时与 P-02 叠加可显著拉低 TPS。

建议：

1. 重算条件改为：目标变化、剩余路径失效、连续停滞、当前点到“剩余路径段”的距离超阈值。
2. 使用 `pathIndex` 只验证剩余路径，不以最终目标距离代替偏航距离。
3. 建立服务端全局规划预算/队列，每 tick 只允许有限展开量；按 UUID 加抖动，避免所有龙同 tick 重算。
4. 给任务距离、单次扩展和单 tick 碰撞读取设置联合预算，并记录超预算指标。
5. 建立 1、10、25、50 条龙的 JFR/spark 基准，验收 P95 tick 时间。

### P-02：龙群注册表处理为 O(N²) 且高分配

**状态：已确认｜等级：高｜优先级：P1**

证据：

- [`DragonFlightController.java:49`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L49) 每条龙每 tick 都调用一次全表 `prune`。
- [`DragonFlightRegistry.java:51`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightRegistry.java#L51) 的 `prune` 遍历所有状态。
- [`DragonFlightRegistry.java:56`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightRegistry.java#L56) 为每条龙创建一个新 `HashMap` 并复制其他所有龙的位置；[`DragonFlightController.java:246`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L246) 再遍历该快照。

影响：N 条受控龙每 tick 产生 O(N²) 遍历与临时对象。状态没有维度键，坐标相近但位于不同维度的龙也会产生错误分离力。

建议：在服务器 END tick 统一清理一次；按 `ServerLevel` 和空间桶维护位置索引；只查询 12 格邻域；直接访问只读迭代器或复用缓冲区，避免每龙构造全量 Map；实体卸载/移除时立即 `remove`。

### P-03：占用缓存无限增长且不会失效

**状态：已确认｜等级：中｜优先级：P2**

证据：[`DragonFlightRegistry.java:34`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightRegistry.java#L34) 为每条龙维护无上限 `HashMap<Long, Boolean>`；[`DragonPathPlanner.java:132`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L132) 只写入/读取，从未在目标变化、方块变化、维度变化或成功规划后清除。

影响：长时间受控且执行不同远程任务的龙会持续累积网格单元；建筑变化后仍使用旧的可通行/不可通行结果，可能撞墙或错误进入紧急悬停。

建议：使用有大小上限和 tick TTL 的缓存；缓存键包含维度；目标大幅变化时清理；监听相关方块更新或只短期缓存；记录命中率与容量，容量达到阈值时淘汰最旧项。

### P-04：每 tick 碰撞采样和持久 NBT 操作偏重

**状态：已确认｜等级：中｜优先级：P2**

证据：

- [`DragonGuidance.java:13`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonGuidance.java#L13) 组合 3 个 yaw × 3 个 pitch 候选，每个候选在 4 个距离执行完整龙 AABB `noCollision`，即每龙每 tick 最多 36 次。
- [`DragonFlightController.java:78`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L78) 几乎每 tick 都把阶段写为字符串 `CRUISE`，即使值没有变化；`DragonRideState` 的读取也反复进入持久 CompoundTag。
- [`DragonFlightController.java:307`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L307) 盘旋攻击每 10 tick 重建友军列表并执行一次范围实体查询。

影响：单龙可接受的固定成本在龙群中线性放大，并与 P-01/P-02 叠加；临时对象增加 GC 抖动。

建议：对走廊结果做 2–5 tick 短缓存，只在方向/位置/方块版本变化时重采样；先做廉价射线/体素粗筛再做完整 AABB；状态 setter 在值不变时不写；运行时阶段放内存，必要事件才持久化；友军使用 UUID Set 快照并错峰刷新。

### P-05：现有“硬预算”没有覆盖真正昂贵的碰撞工作

**状态：已确认｜等级：中｜优先级：P1**

证据：

- [`DragonPathPlanner.java:59`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L59) 的 `MAX_EXPANSIONS` 只限制从优先队列取出的节点；每节点仍可对 26 个邻居调用 `isOpen`，而 [`DragonPathPlanner.java:141`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L141) 的完整龙 AABB `noCollision` 成本不计入预算。
- [`DragonPathPlanner.java:106`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L106) 在规划完成后同步执行平滑；`segmentClear` 按路径长度逐格采样，且会从最远点反复回退尝试，没有独立采样上限或跨 tick 预算。
- [`DragonLandingPlanner.java:87`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonLandingPlanner.java#L87) 的完整 AABB `noCollision` 同样不增加 `MAX_READS`，因此“512 读取”并不代表总方块/碰撞读取上限；龙体型越大，单次调用扫描体积越大。

影响：合法的远距离命令、无解空间或大型龙可在单 tick 内触发远高于常量名称暗示的工作量。1024 节点和 64 候选限制了循环次数，却没有形成可靠的主线程时间上限。

建议：把预算定义为可计量的“节点展开 + AABB 体素/区块访问 + 平滑样本”联合配额；搜索和平滑都做成可跨 tick 续算的任务；达到单 tick 配额立即让出主线程。对不同龙尺寸记录实际碰撞查询数和耗时，而不是只记录候选数。

### R-01：降落点 Y 偏移导致正常地面被拒绝

**状态：已确认｜等级：高｜优先级：P0**

证据：[`DragonLandingPlanner.java:48`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonLandingPlanner.java#L48) 通过 `Level#getHeight` 获得高度。Minecraft 1.20.1 的该方法返回最高阻挡方块上方第一个可用 Y；[`DragonLandingPlanner.java:51`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonLandingPlanner.java#L51) 又加了 `1.0D`，随后 [`DragonLandingPlanner.java:52`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonLandingPlanner.java#L52) 取 `spot.below()`，最终检查的仍是第一个可用空气方块。第 55 行看到空气便拒绝候选。

影响：普通地表候选基本全部失败，自动降落和空中下车无法完成；到达目标后的龙会持续悬停，并每 40 tick 再做一轮最多 64 候选/512 读取的搜索。

建议：把龙脚位置设为 `surfaceY`，地面位置为 `surfaceY - 1`；同时按龙 AABB 的实际 minY 对齐位置。新增 Forge GameTest：平地、台阶、屋顶、液体、火、洞穴、世界上下界以及不同体型龙。

### R-02：路径索引不推进，前视可能把龙拉回旧起点

**状态：已确认｜等级：高｜优先级：P1**

证据：[`DragonFlightRegistry.java:25`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightRegistry.java#L25) 声明了 `pathIndex`，但主代码没有任何读写；[`DragonGuidance.java:52`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonGuidance.java#L52) 每 tick 都从完整路径第一个点开始累计，而 `previous` 却设为龙的当前位置。

影响：龙离旧起点越来越远后，“当前位置到旧起点”的反向段会被当作路径第一段；当这段超过前视距离时，返回点位于龙与旧起点之间，导致折返或振荡。P-01 的周期重算会暂时刷新起点，掩盖而不是解决问题。

建议：到达/越过 waypoint 时递增 `pathIndex`；前视只遍历 `[pathIndex, end)`；将当前位置投影到当前或后续路径段并从投影点累计；重算后重置索引；添加 L 形路径、长直线、绕障和越过 waypoint 的确定性测试。

### R-03：路径坐标边界、键编码和启发式不安全

**状态：已确认｜等级：中｜优先级：P2**

证据：

- [`DragonPathPlanner.java:74`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L74) 把网格索引 `ny` 直接与方块高度 `getMinBuildHeight/getMaxBuildHeight` 比较；正确比较对象应是 `(ny + 0.5) * cellY` 和龙完整 AABB。
- [`DragonPathPlanner.java:163`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L163) 每轴只保留 21 bit。以最小 4 格网格计算，约 419 万方块后即回绕，而 Minecraft 世界边界远大于该值。
- [`DragonPathPlanner.java:185`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L185) 在 `int` 中先计算平方，差值超过约 46340 个网格即可溢出，再转为 `sqrt`。
- [`DragonPathPlanner.java:139`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L139) 仅检查两个正向角点所在区块，不能覆盖大型 AABB 的全部区块。

影响：路径可规划到构建高度外；远坐标发生节点碰撞、NaN 启发式或错误闭集；区块边界附近可能读取未检查区块或把不可用空间当作可用。

建议：使用 `record Cell(int x, int y, int z)` 作为 Map 键；差值先转 `double/long`；用单元中心与完整移动 AABB校验 Y；检查 AABB 覆盖的所有区块；在入口先限制世界边界和任务距离。

### R-04：声明的兼容范围宽于实际验证范围

**状态：已确认｜等级：中｜优先级：P2**

证据：

- [`gradle.properties:4`](gradle.properties#L4) 声明 Minecraft `[1.20.1,1.21)`，但源码与官方映射固定为 1.20.1；Forge 范围为无上界 `[47,)`。
- [`mods.toml:34`](src/main/resources/META-INF/mods.toml#L34) 允许 Ice And Fire `[1.2.7,1.3)`，而 [`EntityDragonBaseMixin.java:26`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/mixin/EntityDragonBaseMixin.java#L26) 明确只对 1.2.7 发布 JAR 验证过签名。
- [`dominionsword_iceandfire_compat.mixins.json:2`](src/main/resources/dominionsword_iceandfire_compat.mixins.json#L2) 设置 `required: true`；关键方法名或字段变化会使游戏启动失败。
- README 同时出现 Dominion Sword 1.28.0+、1.27.0 构建示例和 `[1.27.0,)` 兼容描述，契约不一致。

影响：加载器会接受未经验证的组合，而 required Mixin 在目标变化时可能直接阻止客户端或服务端启动。

建议：将 Minecraft 收紧为 `[1.20.1,1.20.2)`、Forge 收紧到 47.x；Ice And Fire 仅声明真实测试通过的版本；为每个支持版本运行客户端和专用服务端启动矩阵；统一 README 与 `mods.toml` 的 Dominion Sword 最低版本。

### R-05：tick 尾部以旧 phase 覆盖状态机刚完成的转换

**状态：已确认｜等级：高｜优先级：P0**

证据：

- [`DragonFlightController.java:54`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L54) 在 `dispatch` 之前把 `phase` 读入局部变量。
- `dispatch` 内部可能改变状态：[`DragonFlightController.java:134`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L134) 在达到起飞高度时写 `CRUISE`；[`DragonFlightController.java:120`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L120) 通过 `beginLanding` 写 `LANDING`。
- [`DragonFlightController.java:78`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L78) 随后仍依据 tick 开头的旧 `phase` 判断，并在旧值不是 `LANDING` 时无条件写 `CRUISE`。

影响：`TAKEOFF` 在首个控制 tick 后就被写成 `CRUISE`，10 格起飞阶段实际上无法保持；从 `CRUISE` 到自动降落的转换也会在同 tick 被覆盖。龙到达目标时可能每 tick 重新搜索降落点、重复执行未纳入完整预算的碰撞工作，并且无法按设计完成落地清理。

建议：不要在 tick 尾部无条件修正 phase。让每个状态处理器返回“速度 + 下一状态”，由一个位置原子提交；或在 `dispatch` 后重新读取当前 phase，并只允许显式的合法迁移。补充测试：TAKEOFF 在高度不足时连续保持、达到阈值后仅转换一次、TRANSIT 到 LANDING 不被覆盖、落地后只清理一次。

### R-06：紧急悬停计数有三个写入点，当前修复计划会把等待时间减半

**状态：已确认｜等级：中｜优先级：P1**

证据：

- [`DragonPathPlanner.java:86`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonPathPlanner.java#L86) 在规划失败时递增一次。
- [`DragonFlightController.java:111`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L111) 发现空路径后再次递增，并立即检查 100 tick 阈值。
- [`DragonFlightController.java:82`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L82) 在同一 tick 末尾又递减一次。空路径会令每 tick 都重规划，所以当前净变化约为 `+1/tick`，只是偶然接近设计值。
- `AUDIT_AND_FIX_PLAN.md` 当前建议只移除末尾递减；若保留前两个递增点，净变化会变成 `+2/tick`，100 tick 阈值约 50 tick 即触发。

影响：计数器不是清晰的“连续无安全航路 tick 数”，修复其中任一写入点都可能改变行为；成功规划后也没有直接清零，只依赖尾部逐 tick 衰减，旧失败会污染下一次任务。

建议：规定唯一所有者。规划器只返回结果，不修改 `emergencyTicks`；控制器每个“本 tick 无可用路径”递增一次，获得可用路径或任务变化时清零，饱和到上限。用 99/100/101 tick 和“失败后成功再失败”测试锁定语义，并同步修正 `AUDIT_AND_FIX_PLAN.md`。

### R-07：正常解除控制会清除掠袭冷却

**状态：已确认｜等级：中｜优先级：P1**

证据：[`IceAndFireDragonSkillProvider.java:75`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/IceAndFireDragonSkillProvider.java#L75) 把 900 tick 冷却截止时间写入 `STRAFE_READY`；正常 `release` 最终调用 `clearControlState(..., false)`，而 [`DragonRideState.java:331`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonRideState.java#L331) 会删除同一字段。

影响：在持久任务已经结束、解除控制可以正常完成的情况下，玩家可通过释放并重新选择龙来提前清除掠袭技能冷却。高频 AOE 掠袭既破坏玩法约束，也会增加范围查询和飞控负载。

建议：把技能冷却与临时控制会话分离。正常 release、重新选择和区块重载都保留冷却；只有管理员修复、世界数据迁移或明确的死亡重置策略可以清除。添加“释放—重新选择—冷却仍剩余”的测试。

### R-08：降落危险方块检查只覆盖候选中心列

**状态：可能存在｜等级：中｜优先级：P2**

证据：[`DragonLandingPlanner.java:86`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonLandingPlanner.java#L86) 用完整龙 AABB 做实体碰撞，但第 90–95 行检查流体和火焰时始终使用 `ground.getX()/getZ()`，只扫描中心的一列方块。火焰和流体通常不会被实体碰撞检查完整排除。

影响：修复 R-01 后，大型龙的碰撞箱边缘仍可能覆盖岩浆、水或火焰；同时只验证中心地面，无法保证足够的落脚支撑。具体伤害和落地表现依赖 Ice And Fire 实现，因此标为可能存在。

建议：遍历移动后 AABB 覆盖的完整 X/Z 足迹和必要 Y 范围，检查流体、火焰与危险方块标签；明确最小支撑面积。把“中心安全但翼/身体覆盖岩浆或火”的场景加入 GameTest。

### R-09：悬停点方向会在越过目标中心时瞬间翻转

**状态：已确认｜等级：中｜优先级：P2**

证据：[`DragonFlightController.java:158`](src/main/java/com/arxyt/dominionsword/iceandfirecompat/control/DragonFlightController.java#L158) 每 tick 使用“目标到龙的当前水平向量”计算悬停点；当龙因惯性越过目标中心时，该向量符号翻转，第 163 行的期望悬停点会瞬间跳到目标另一侧约 20 格以外。运行时状态没有保存上一稳定悬停方向。

影响：PD 阻尼尚未把速度降为零时，期望点可能反复换边，造成振荡、频繁转向和额外走廊/碰撞工作；目标水平位置与龙几乎重合时还会退化为直接悬停在目标正上方。

建议：任务开始时保存稳定的水平锚定方向，只在目标显著移动、路径受阻或低速稳定后逐渐旋转；水平向量过小时沿用上一方向，不要根据符号瞬时翻面。添加“高速越过目标中心”和“目标与龙同 X/Z”的确定性测试。

### B-01：Wrapper 版本不一致且分发包未固定哈希

**状态：已确认｜等级：中｜优先级：P2**

证据：

- 仓库 `gradle-wrapper.jar` SHA-256 为 `ed2c26eba7cfb93cc2b7785d05e534f07b5b48b5e7fc941921cd098628abca58`。该值在 [Gradle 官方校验表](https://gradle.org/release-checksums/) 对应 8.1/8.1.1 Wrapper，而不是 `gradle-wrapper.properties` 声明的 8.3；8.3 官方 Wrapper 哈希为 `0336f591bc0ec9aa0c9988929b93ecc916b3c1d52aed202c7381db144aa0ef15`。
- [`gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties) 没有 `distributionSha256Sum`。Gradle 的[安全最佳实践](https://docs.gradle.org/current/userguide/best_practices_security.html)要求固定分发包 SHA-256 并校验 Wrapper。
- [`gradle.properties:1`](gradle.properties#L1) 永久设置 `-Dnet.minecraftforge.gradle.check.certs=false`。ForgeGradle 6 源码表明它关闭的是对 Forge/Mojang Maven 的启动前 HTTPS HEAD 证书探测，而不是关闭 Java 对实际下载的 TLS 校验；因此不是直接 TLS 绕过，但会掩盖异常信任环境并弱化早期告警。

影响：当前 JAR 哈希是 Gradle 官方已知值，不能据此认定被篡改；但版本漂移会使审阅者误判来源。分发 ZIP 无独立哈希时，完整性只依赖传输与缓存。关闭预检则降低环境异常的可见性。

建议：升级时按官方方式连续运行 Wrapper 任务直至 JAR/脚本/属性一致；写入官方 `distributionSha256Sum`；在 CI 加 Wrapper validation；删除全局 `check.certs=false`，仅在受控故障排查中临时传参使用。

### T-01：测试与 CI 未覆盖高风险运行路径

**状态：已确认｜等级：中｜优先级：P2**

现有测试只覆盖纯数学、NBT 字段清理和降落搜索环生成。它们没有调用真实 `findLandingSpot`，没有构造 Level/龙 AABB，没有测试 A*、路径推进、授权撤销、离线任务、Mixin 应用、客户端同步或多龙性能。README 也明确说明未配置 CI。

建议：

1. 使用 Forge GameTest 覆盖降落、碰撞、路径和实体生命周期。
2. 增加适配器契约测试，确保每个写入口在错误玩家、空玩家、跨维度和越界输入下拒绝且不修改状态。
3. 增加专用服务端启动测试验证 required Mixin；至少覆盖每个宣称支持的依赖版本。
4. 增加 1/10/25/50 龙的固定场景性能回归，保存 tick 时间、规划展开数、碰撞查询数和缓存容量。
5. 解决私有主模组依赖的可重复构建：发布最小 API artifact 到受控 Maven/GitHub Packages，或在 CI 安全注入带 SHA-256 的二进制。

## 5. 推荐修复路线

### 第一阶段：阻断高影响问题

1. 修复降落 Y 偏移，并补真实 Level GameTest。
2. 修复 tick 尾部旧 phase 覆盖，确保起飞、巡航、降落迁移由单一状态机提交。
3. 修复重规划条件和路径索引推进；加入覆盖碰撞与平滑工作的全局规划预算。
4. 引入强制终止路径，确保权限撤销、死亡、移除和显式释放不可被持久任务短路。
5. 离线敌我判断改为默认拒绝攻击。

### 第二阶段：恢复可扩展性和纵深防御

1. 注册表按维度和空间桶重构，`prune` 每服务器 tick 一次。
2. 所有公开适配器/技能写入口本地重验权限、维度、范围和坐标有限性。
3. 为占用缓存设置 TTL/容量/维度并在任务或世界变化时失效。
4. 统一紧急悬停计数所有者；成功规划和任务变化时清零。
5. 将掠袭冷却从控制会话清理中分离，解除/重选不能重置冷却。
6. 减少走廊 AABB 查询和重复 NBT 写入，加入可观测计数器。

### 第三阶段：加固构建与发布

1. 升级到修复 CVE-2026-22865 的 Gradle 8.x 版本，并统一 Wrapper 文件。
2. 添加 distribution SHA-256、dependency verification、仓库内容过滤和本地 JAR 哈希清单。
3. 收紧 `mods.toml` 兼容范围并建立客户端/专用服务端版本矩阵。
4. 配置 CI：Wrapper 校验、编译、JUnit、GameTest、服务端启动、依赖审计和秘密扫描。

## 6. 验收标准

- 50 条龙执行远距离任务时，不再每条龙每秒重算 A*；规划工作有全局硬预算且 P95 tick 时间满足项目目标。
- 任意权限撤销、龙易主、死亡、移除或玩家释放后，下一个 server tick 内清除控制与攻击状态，即使存在持久任务。
- 平地/屋顶/液体/火/洞穴/世界边界 GameTest 对降落行为全部给出预期结果。
- 路径跟随单调推进剩余 waypoint，不回到已通过的旧起点。
- TAKEOFF 在达到设定高度前持续有效；TRANSIT→LANDING 不被 tick 尾部覆盖，降落搜索不会每 tick 重启。
- 连续无路 99 tick 不进入、100 tick 进入紧急悬停；成功获得路径后计数立即归零。
- 释放并重新选择龙后，900 tick 掠袭冷却仍保持原截止时间。
- 悬停龙越过目标中心时，期望悬停点连续变化且不会在两侧反复翻转。
- 所有写入口对错误控制者、空调用者、跨维度、超距和非有限坐标均失败且状态零变化。
- 依赖和 Wrapper 发生未审阅变化时 CI 失败；Gradle 版本不再落入已知公告的受影响范围。
- 每个 `mods.toml` 宣称支持的组合都通过客户端和专用服务端启动测试。

## 7. 正向观察

- 默认控制模式为 `OWNER_ONLY`，并已有集中式 `DragonControlPolicy`，便于修复时统一收口。
- 固定 AOE 规格已有 1–64 的数值钳制，降落搜索也设置了候选和显式读取计数；仍需按 S-05/P-05 补 finite 校验和真实碰撞工作预算。
- 运行时与持久状态职责有文档，清理字段集中在 `clearControlState`。
- Mixin 目标、开发/生产方法名和设计意图有注释，资源 JSON 有效。
- 仓库历史未发现凭据，Wrapper JAR 命中官方已知哈希；当前问题属于版本一致性与加固不足，而非已证实的恶意篡改。

---

本报告是基于 `f963c4a` 的第二次代码审计快照，不等同于渗透测试，也不能替代对三个未入库依赖及 Dominion Sword 网络包处理器的独立审计。
