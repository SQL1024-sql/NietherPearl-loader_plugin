# NetherPearlLoader

只在 **地獄(NETHER)** 對飛行中的終界珍珠強制載入區塊的 Paper 插件。

- 目標伺服器:**Paper 26.1.2**
- 建置:**Java 25 + Maven**
- API 座標:`io.papermc.paper:paper-api:26.1.2.build.74-stable`
  (查自 `repo.papermc.io` 的 `maven-metadata.xml`;26.1.2 這條線最後一個 stable build 是
  `build.74`,jar 內 `apiVersioning.json` = `{"version":"26.1.2.build.74-stable","currentApiVersion":"26.1.2"}`。
  paper-api 本身以 Java 25(class major 69)編譯,所以 `source/target 25` 是必要條件。)

## 為什麼「只在地獄」

主世界的珍珠炮蓄力靠「32 級弱載入」讓珍珠停在不被 tick 的區塊上累積動量。
只要對主世界的珍珠 forceload,它就會繼續飛,蓄力機制直接失效。

因此 `nether-only`(預設 `true`)開著時,插件 **連讀都不會去讀** 非地獄世界的珍珠:
`world.getEnvironment() != NETHER` 就整個 world 跳過,主世界與終界不會被碰到一個區塊。
啟動時的殘留清理也套用同一條件。

## 運作方式

1. `BukkitRunnable` 每 `interval-ticks` 掃一次所有 world,非 NETHER 直接 skip。
2. 每顆 `EnderPearl` 以所在區塊為中心、`radius` 為半徑,`setChunkForceLoaded(x, z, true)`
   載入 (2r+1)² 個區塊。
3. `Map<UUID, Set<chunkKey>>` 追蹤每顆珍珠佔用的區塊,每 tick 做差集:
   補上新需要的、解除上次多餘的(不做「全解除再全載入」,免得區塊在同一 tick 內被卸載又載入)。
4. 珍珠命中/傳送/失效 → 這次掃描看不到它 → 它佔用的區塊整批解除。
5. `onDisable()` 把插件加過的 forceload 全部清乾淨。

另外兩個保護機制:

- **引用計數**:兩顆珍珠共用同一個區塊時,計數歸零才真的解除,不會 A 珍珠飛走就把 B 珍珠腳下抽掉。
- **外來 forceload 保護**:接手時就已經是 forceloaded 的區塊(玩家 `/forceload` 或其他插件設的)
  只記帳、不接管,也不會被我們解除。

## 速度上限與實測數據

反應式掃描先天追不上珍珠。**以下數字全部來自實機**(Paper 26.1.2 build 74,4 核心,
地獄 y=200,`Motion` 用 Bukkit API 設定):

### 珍珠在高速下的真實行為

```
T1  x=     0.5  dx=      0.0  vx=1750.000  chunk=0,0    forced=true
T2  x=  1733.0  dx=   1732.5  vx=1732.500  chunk=108,0   ← 一 tick 穿過 108 個未生成區塊
T3~T6         dx=0(凍結,vx 一格不差地保留)
T7  x=  3448.2  dx=   1715.2  vx=1715.175  chunk=215,0
```

1. **沒有速度上限、沒有 Motion clamp**:珍珠一個 tick 真的位移 1732.5 格,穿過上百個
   未載入區塊,沒有碰撞、動量完整保留。
2. **沿途區塊不需要載入**:珍珠只需要「停下來的那一格」是 31 級 entity ticking。
3. **drag 只在被 tick 時作用一次**(1750 → 1732.5 → 1715.175,每次 ×0.99),
   凍結多久都不消耗動量 → **卡頓只影響飛多久,不影響飛到哪**。
4. 對照組:5 格/tick 的珍珠飛出 forceload 區、進到 32 級 lazy 區塊後立刻凍住 20 tick,
   `vx=4.803` 完全不變——主世界的珍珠炮蓄力機制在實機重現。

### 真正的瓶頸是「區塊生成」,不是 ticket

同一發 1750 格/tick 的珍珠,差別只在落點區塊有沒有預先生成過:

| 落點區塊狀態 | 該 tick 耗時 | 每次跳躍間隔 |
|---|---|---|
| 已生成(從硬碟讀) | 177–350 ms | ~10 tick |
| 全新地形(現場生成) | **5.5–8.9 秒** | ~30–35 tick |

裝不裝這個插件都一樣(控制組實測:無插件 10,139 格/50 秒 TPS 4.0;有插件 8,491 格/41 秒
TPS 4.7)。**所以長程珍珠炮的第一要務是先把走廊 pre-generate**(Chunky 之類),
不是調插件參數。

### 兩種鋪路策略

- `corridor`:沿路線整條鋪,低速時沿途碰撞判定才正常。成本與速度成正比
  (1750 格/tick 要 327 區塊/tick,不可行)。
- `landing`:只鋪未來每個 tick 的**落點**,成本 = `ticks × (2r+1)²`,**與速度無關**。
- `auto`(預設):走廊塞不進 `max-chunks-per-pearl` 時自動切成 `landing`。

### forceload 必須非同步(這是實測抓到的 bug)

反編譯 Paper 伺服器 jar 確認:

```
CraftWorld.setChunkForceLoaded → ServerLevel.setChunkForced →
    invokevirtual getChunk:(II)Lnet/minecraft/world/level/chunk/LevelChunk;
```

`setChunkForceLoaded(x, z, true)` **內部會在主執行緒同步 getChunk**,對沒生成過的遠方區塊
下 forceload 等於在主執行緒跑地形生成。插件因此改成:先 `getChunkAtAsync` 把區塊叫起來
(生成跑在 chunk worker),回到主執行緒後才掛 forceload。

## 讓「弱加載蓄力」和「地獄飛行加載」並存

蓄力中的珍珠要 32 級(載入但不 tick,珍珠才凍得住),飛行中的珍珠要 31 級
(entity ticking,珍珠才會動)——需求完全相反。而且兩者從 API 看一模一樣:都有巨大
Motion、都靜止不動(飛行中的珍珠在等區塊載入時同樣不動)。

如果炮膛在**主世界**,`nether-only: true` 就已經完全隔離了,不必再做任何事。
如果炮膛也在**地獄**,用 `stasis-protection` 的兩層保護:

| 手段 | 機制 | 優點 / 缺點 |
|---|---|---|
| `skip-idle-pearls` | 珍珠「動過一次」才接管 | 零設定;插件 reload 會忘記,卡在半路的珍珠要再動一格才會被接回 |
| `protected-chunks` | 列出的區塊永遠不 forceload | 確定性、不受 reload 影響;要手動填炮膛座標 |

兩者實測驗證(Paper 26.1.2):

```
# skip-idle-pearls:珍珠靜止時插件零介入,動了才接管
[NetherPearlLoader] pearl 70dddf83 動了,開始接管 (5.5, 200.0, 0.5)

# protected-chunks 設為區塊 1..3 之後
T5  x=20.0  chunk=1,0  forced=false   ← 保護區內,拒絕 forceload
T19 x=82.4  chunk=5,0  forced=true    ← 保護區外,正常接管
```

⚠ **這兩層保護只能保證「插件不會去 tick 你的蓄力珍珠」,不能阻止原版自己 tick 它。**
自 1.21.2 起,珍珠移動進新區塊時會替自己建立 31 級 ticket。實測中保護區內的珍珠仍然
飛過去了,就是這個原因——它自己的 ticket 把區塊升到了 31 級。蓄力之所以成立,是因為
凍住的珍珠**沒被 tick 就無法刷新自己的 ticket**,2 秒後 ticket 過期,它才會一直凍著。
所以插件能做到的是「不要成為那個弄醒它的人」,其餘仍由你的紅石結構負責。

## config.yml

```yaml
radius: 1            # 半徑,1 = 3x3
interval-ticks: 1    # 掃描間隔
nether-only: true    # 只在地獄生效
debug: false         # 主控台輸出追蹤訊息

predict:
  enabled: true            # 讀 Motion 向量預先鋪路
  mode: auto               # auto / corridor / landing,見上面「兩種鋪路策略」
  ticks: 1                 # 往前預測幾 tick(建議 >= 你伺服器的區塊載入延遲)
  max-chunks-per-pearl: 256 # 每顆珍珠的區塊數上限(安全閥)

recover-on-enable: true    # 啟動時清掉上一輪當機殘留的 forceload
journal-interval-ticks: 100 # 殘留紀錄檔的最小寫入間隔
```

`recover-on-enable` 處理的是「伺服器被 kill / 當機,`onDisable()` 沒跑到」的情況:
插件會把目前佔用的區塊節流寫進 `plugins/NetherPearlLoader/forceloaded.txt`(非同步),
下次啟動時照著清掉,存檔不會被永久釘住。

## 指令

| 指令 | 說明 |
| --- | --- |
| `/npl status` | 顯示目前設定、追蹤中的珍珠數與佔用區塊數 |
| `/npl reload` | 重讀 config(會先解除目前所有 forceload 再套用新設定) |

權限:`netherpearlloader.admin`(預設 op)。

## 建置

需要 **JDK 25**(paper-api 26.x 以 Java 25 編譯,JDK 21 無法讀取)。

```bash
mvn clean package
# 產出 target/NetherPearlLoader-1.0.0.jar
```

把 jar 丟進 `plugins/` 即可;`config.yml` 首次啟動會自動產生。
