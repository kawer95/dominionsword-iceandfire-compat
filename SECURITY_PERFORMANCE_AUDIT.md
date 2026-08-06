# Dominion Sword: Ice And Fire Compat 安全与性能审计

> 审计日期：2026-08-06  
> 审计对象：`kawer95/dominionsword-iceandfire-compat`  
> 基线提交：`9a1fefb`（`main`）  
> 审计范围：仓库内 Java 源码、Mixin、资源与 Gradle 构建链；不包含未入库的 Dominion Sword、Ice And Fire CE、Uranus 二进制实现  
> 结论等级：**高风险，建议先修复 P0/P1 项再扩大服务器部署规模**

## 1. 执行摘要

本次审计未在仓库源码及三次提交历史中发现硬编码令牌、私钥、密码、主动联网、命令执行或 Java 原生反序列化代码。资源 JSON 均可解析，Gradle Wrapper JAR 的 SHA-256 也能匹配 Gradle 官方已知文件，因此目前没有证据表明仓库被植入恶意代码。

主要风险集中在四个方面：

1. **TPS/拒绝服务风险**：飞行控制器在龙距离目标超过 8 格时，每 20 tick 重新执行一次最多 1024 节点、每节点 26 邻居的 3D A*；龙群分离逻辑又按每条龙扫描和复制全量状态，整体会随龙数量呈平方级增长。
2. **权限撤销失效**：`endControl` 遇到持久任务会无条件返回。即使调用原因是权限已撤销、龙已死亡、玩家显式释放或控制者无效，也不会真正终止控制。
3. **构建供应链风险**：声明的 Gradle 8.3 处于两个已公开漏洞的影响范围，且项目没有分发包校验值、依赖验证元数据或严格仓库内容过滤。
4. **飞行可用性问题**：降落点存在确定的 Y 坐标偏移错误；路径跟随没有推进 `pathIndex`；路径规划将“网格 Y”与“方块 Y”直接比较。这些问题会导致无法降落、折返、无效重算或越过世界高度边界。

建议的修复顺序：

- **P0**：S-01、P-01、R-01。
- **P1**：S-02、P-02、R-02、S-03、S-04。
- **P2**：P-03、P-04、R-03、R-04、S-05、T-01。

## 2. 方法与限制

本次工作包括：

- 对 21 个主 Java 文件和 3 个测试文件逐文件静态审阅；
- 检查权限入口、离线任务、实体生命周期、Mixin 注入点、持久状态和客户端同步；
- 检查每 tick 热路径、碰撞查询、实体扫描、路径规划、缓存和对象分配；
- 解析全部 JSON 资源，并对 Git 历史执行常见凭据特征扫描；
- 计算 `gradle-wrapper.jar` SHA-256，并与 Gradle 官方校验表对照；
- 对固定构建工具版本核对上游安全公告；
- 使用本地 Forge 1.20.1 映射 JAR 的字节码确认 `Level#getHeight` 返回表面上方第一个可用 Y。

限制如下：

- `dominionsword_jar`、`iaf_jar`、`uranus_jar` 均由本地路径提供且未入库，无法对这些二进制依赖做完整 SBOM/CVE 和调用契约审计。
- 因缺少上述三个精确依赖，无法执行可信的全量编译、JUnit、GameTest 或专用服务端集成测试。仅把可由仓库源码直接证明的问题标记为“已确认”。
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
| R-01 | 已确认 | 高 | 可用性 | 降落点 Y 多加 1，正常实心地面会被当作空气拒绝 |
| R-02 | 已确认 | 高 | 正确性/性能 | 路径索引从不推进，前视算法反复从旧起点扫描并可能引导折返 |
| R-03 | 已确认 | 中 | 正确性 | 网格 Y 边界单位错误，远坐标键回绕，启发式存在整数溢出 |
| R-04 | 已确认 | 中 | 兼容性 | 依赖范围远宽于实际验证范围，required Mixin 失败会阻止启动 |
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
2. 修复重规划条件和路径索引推进；加入全局规划预算。
3. 引入强制终止路径，确保权限撤销、死亡、移除和显式释放不可被持久任务短路。
4. 离线敌我判断改为默认拒绝攻击。

### 第二阶段：恢复可扩展性和纵深防御

1. 注册表按维度和空间桶重构，`prune` 每服务器 tick 一次。
2. 所有公开适配器/技能写入口本地重验权限、维度、范围和坐标有限性。
3. 为占用缓存设置 TTL/容量/维度并在任务或世界变化时失效。
4. 减少走廊 AABB 查询和重复 NBT 写入，加入可观测计数器。

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
- 所有写入口对错误控制者、空调用者、跨维度、超距和非有限坐标均失败且状态零变化。
- 依赖和 Wrapper 发生未审阅变化时 CI 失败；Gradle 版本不再落入已知公告的受影响范围。
- 每个 `mods.toml` 宣称支持的组合都通过客户端和专用服务端启动测试。

## 7. 正向观察

- 默认控制模式为 `OWNER_ONLY`，并已有集中式 `DragonControlPolicy`，便于修复时统一收口。
- AOE 半径/高度已有 1–64 的上限，降落搜索也设置了候选和读取预算。
- 运行时与持久状态职责有文档，清理字段集中在 `clearControlState`。
- Mixin 目标、开发/生产方法名和设计意图有注释，资源 JSON 有效。
- 仓库历史未发现凭据，Wrapper JAR 命中官方已知哈希；当前问题属于版本一致性与加固不足，而非已证实的恶意篡改。

---

本报告是基于基线提交的代码审计快照，不等同于渗透测试，也不能替代对三个未入库依赖及 Dominion Sword 网络包处理器的独立审计。
