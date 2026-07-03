# MemorySpeedBuild

面向 **Paper 26.2** 的小型、独立世界记忆速建（speedbuild）插件。项目只依赖 Paper API；不需要 WorldEdit、数据库、NMS 或任何额外插件。

- **构建工具：** Maven
- **服务器：** Paper 26.2
- **Java：** 25
- **玩家上限：** 每个竞技场 8 人
- **核心实现：** `src/main/java/io/github/doywvtkebrxvh/speedbuild/MemorySpeedBuildPlugin.java`（单一 Java 文件）

> 26.2 的 Paper API 在当前阶段使用 `26.2.build.*-alpha` 坐标。若 Paper 发布新的 26.2 build，只需要更新 `pom.xml` 的 `paper.version` 后重新构建。

## 构建

本地需安装 JDK 25 和 Maven：

```bash
mvn clean package
```

JAR 输出为：

```text
target/memory-speedbuild-1.0.0.jar
```

将该 JAR 放进 Paper 服务端的 `plugins/` 目录后重启。GitHub Actions 工作流在 `.github/workflows/build.yml`，会使用 Temurin Java 25 自动构建并上传 JAR artifact。

## 权限

| 权限 | 默认 | 用途 |
|---|---:|---|
| `speedbuild.admin` | OP | 配置模板/竞技场、管理建筑库、注册地图、编辑地图、强制开始/停止 |

普通玩家只需要使用 `/sb join <地图>` 和 `/sb quit`。

## 首次配置

### 1. 设置生存主世界返回点

在生存主世界内执行：

```text
/sb set survival
```

玩家退出、比赛结束、管理员强制停止时都会返回这个精确坐标。

### 2. 创建模板世界并保存建筑

```text
/sb create template basic
```

插件会创建 `sb_template_basic` 虚空世界，并在 `(0,64,0)` 生成 3×3 平台。模板世界独立于主世界。

在模板世界中搭建建筑。**选区 X、Z 必须都恰好是 7 格**，应把 7×7 的地面上方第一层作为建筑起点。随后框选整块 7×7×高度区域：

```text
/sb addbuild <x1> <y1> <z1> <x2> <y2> <z2> house 1
/sb save template basic
/sb leave template
```

建筑库存的是方块的 `BlockData`，因此楼梯朝向、半砖上下、栅栏连接、含水状态等方块状态可以正确复原和比较。

### 3. 创建竞技场

```text
/sb create map arena1
```

在虚空竞技场中手工建造玩家岛、中心岛和死亡观战点。站在每个配置位置后执行：

```text
/sb add setspawn 1
/sb addisland <x1> <z1> <x2> <z2> 1

/sb add setspawn 2
/sb addisland <x1> <z1> <x2> <z2> 2

/sb add centre
/sb add deathspawn
/sb save arena1
/sb register arena1
```

`/sb add setspawn <编号>` 会同时记录玩家出生位置和玩家脚下方块。脚下方块是该玩家 7×7 建筑区的中心基准；实际建筑从它的**上一格**开始生成。`/sb add centre` 同样记录中心岛的基准点。

编号范围为 1–8。每个可用出生点都必须同时配置：出生点、建筑基准点、岛屿范围。注册时还必须有中心岛和死亡观战点。

## 管理员命令

```text
/sb set survival

/sb create template <地图名称>
/sb save template <地图名称>
/sb leave template

/sb create map <地图名称>
/sb edit <地图名称>
/sb save <地图名称>
/sb register <地图名称>
/sb unregister <地图名称>

/sb addbuild <x1> <y1> <z1> <x2> <y2> <z2> <name> <1|2|3>
/sb delbuild <name>
/sb listbuild

/sb add setspawn <1-8>
/sb addisland <x1> <z1> <x2> <z2> <1-8>
/sb del setspawn <1-8>
/sb add centre
/sb add deathspawn

/sbforce start
/sbforce stop
```

地图名与建筑名均限制为 1–32 个小写字母、数字、`_`、`-`，例如 `arena_1`、`small-house`。

## 玩家命令

```text
/sb join <地图名称>
/sb quit
```

- 加入时随机占用未使用的出生点，切换为冒险模式并清空物品栏。
- 4 人时自动开始 30 秒倒计时；8 人时拒绝新玩家。
- 游戏中、地图恢复中均拒绝加入。
- `/sb quit` 会返回生存主世界、清空物品栏并切换为生存模式。游戏中退出按离场淘汰处理。

## 游戏规则实现

- 每回合随机抽取一个建筑，所有玩家看到同一主题。
- **记忆阶段：** 15 秒。玩家可在生存模式飞行；放置、破坏被取消，且每 tick 校验并自动还原展示建筑。最后 5 秒只显示数字 title，并播放音符盒声音。
- **复原阶段：** 清空 7×7 建筑区，按建筑快照精确发放材料。第 1–5 回合 60 秒，以后每 5 回合减少 5 秒，最低 15 秒。
- **完成：** 每 tick 比较方块种类和 `BlockData`。完美匹配立即成功、切到冒险模式，并在聊天栏广播耗时。
- **岛屿边界：** 游戏中超出本人的 X/Z 岛屿边界会被传回自己的建筑基准中心。
- **评判：** 时间到后在中心岛展示标准建筑，生成无攻击能力的“审视者”远古守卫者，显示 100 分制整数分数，随机处理同分最低者。为保证无 NMS 依赖，远古守卫者的“激光”用连续 `END_ROD` 粒子光束表现，并精准指向被淘汰者建筑区中心。
- **淘汰：** 淘汰者的整个岛屿清空、所有人听到爆炸音效；淘汰者转旁观者并传送到死亡观战点。
- **离线：** 游戏中离线立即淘汰并清空岛屿；重进后会被立即转入旁观者。
- **结束：** 仅剩一名玩家时宣布冠军与前三名，所有相关玩家返回生存主世界；各玩家和中心岛的 7×7 建筑区清空回初始状态。

## 存储文件

插件的所有持久化配置保存在：

```text
plugins/MemorySpeedBuild/state.yml
```

其中包括生存返回点、模板世界名、竞技场配置、出生点/岛屿范围，以及建筑快照。独立世界目录默认在服务端根目录中，名称为：

```text
sb_template_<模板名>
sb_arena_<竞技场名>
```

## 设计边界

这是小型独立插件，建筑快照覆盖**方块与方块状态**。不要把容器内物品、实体、告示牌文本、命令方块内容等 NBT/实体数据作为标准建筑的一部分；它们不属于本插件的比较和材料发放范围。`/sb addbuild` 会拒绝无法作为同种可放置物品发放的特殊方块。
