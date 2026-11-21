# XiYingChain

连锁挖矿插件，适用于 **Paper / Purpur 1.20+**，Java 与基岩（Geyser/Floodgate）玩家均可使用。

## 功能

- **BFS 连锁挖矿**：挖一块带动周围同类方块（上限可配置，默认 64）
- **工具类型匹配**：镐 / 斧 / 铲 / 锄各自对应一组可连锁方块
- **蹲下触发**（默认开启，防误挖）：按住 Shift 挖才连锁
- **耐久概率消耗**：连锁 N 块按 `1/(未损+1)` 概率扣耐久，与基岩原版一致
- **时运 / 精准采集**：通过 `breakNaturally(tool)` 交给 Bukkit 原生计算掉落
- **创造模式**：不限耐久，连锁上限取配置值
- **掉落物吸附**：连锁掉的物品自动吸到玩家脚边
- **玩家数据持久化**：每位玩家的开关存 `players.yml`，重启保留

## 命令与权限

| 命令 | 作用 | 权限 |
|---|---|---|
| `/chain` 或 `/ch` | 打开设置（总开关 / 蹲下 / 单方块开关） | `chain.admin`（默认 OP） |
| `/chain on` / `off` | 开启 / 关闭连锁 | 同上 |
| `/chain sneak` | 切换「仅蹲下触发」 | 同上 |
| `/chain block <方块ID>` | 单独禁用 / 启用某方块 | 同上 |
| `/chain reload` | 重载配置 | 同上 |

> 普通玩家无需命令，按住 Shift 挖矿即可连锁；**只有管理员能调设置**。
> `chain.use` 权限默认所有人为 true（保留给未来扩展）。

## 配置（`config.yml`）

```yaml
enabled: true          # 连锁总开关
maxBlocks: 64          # 单次连锁上限
sneakOnly: true        # 是否必须蹲下才触发（默认 true）
pickaxe: [...]         # 镐可连锁的方块
axe: [...]             # 斧可连锁的方块
shovel: [...]          # 铲可连锁的方块
hoe: [...]             # 锄可连锁的方块
```

方块 ID 支持 `minecraft:stone`、`STONE`、`stone` 三种写法（大小写不敏感）。

默认列表：镐 26 种（石头/天然石材/全部矿石含深层）、斧 30 种（全部原木含 stripped/菌柄）、铲 5 种（泥土/沙子/雪块/砂砾/红砂）、锄 15 种（全部叶子 + 农作物）。

`/chain reload` 改完配置即时生效，无需重启。

## 编译

 jar 已放在 `jars/XiYingChain-1.0.0.jar`，可直接用。要从源码重建：

- 目标字节码 **Java 17**（`--release 17`），否则 Purcur/Paper 的 ASM 会报 `Unsupported class file major version 71`
- 依赖：`paper-api.jar` + adventure / examination / guava / gson / slf4j-api / bungeecord-chat（见本地编译目录约定）

```bash
javac --release 17 -encoding UTF-8 -cp "<classpath>" -d out src/main/java/com/xiying/chain/ChainPlugin.java
cp src/main/resources/{plugin.yml,config.yml} out/
cd out && jar cf ../XiYingChain-1.0.0.jar .
```

## 源码结构

单类实现：`src/main/java/com/xiying/chain/ChainPlugin.java`
- `onBreak` 事件监听（蹲下 / 工具 / 方块匹配判定）
- `doChain` BFS 洪泛（6 邻域 + long 位压缩坐标去重）
- `chainMining` Set 标记衍生方块，避免递归触发
- `applyDurability` 延迟扣耐久
- `players.yml` 玩家设置持久化（60 秒异步自动保存）
