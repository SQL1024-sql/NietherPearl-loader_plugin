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

## 速度上限(重要)

反應式掃描先天追不上珍珠:珍珠速度可能高達每 tick 數百個區塊,而區塊載入本身就比珍珠慢。
預期行為是**斷續前進**:珍珠衝進未載入區塊 → 卡住 → 下一 tick 掃到 → 補載入 → 繼續飛。

本插件的優化是讀珍珠的 Motion 向量(`Entity#getVelocity()`),沿著它下一 tick 的**行進路線**
(每 8 格取樣一次,保證不跳過區塊)預先鋪路,而不是只鋪它現在站的那一格:

- 速度在 `predict.max-chunks-per-pearl` 涵蓋得了的範圍內 → 幾乎不再卡頓。
- 速度極高(每 tick 數百區塊)→ 一 tick 要載入上萬個區塊,任何伺服器都撐不住,
  因此有上限;超過的部分仍會退化成「卡住 → 補載入 → 續飛」。這是物理極限,不是實作偷懶。

彈道模型只保留水平阻力 0.99(重力只影響 y,不影響 chunk X/Z),不模擬碰撞與水中阻力;
猜錯頂多多鋪/少鋪幾個區塊,下一 tick 的差集會自動修正。

## config.yml

```yaml
radius: 1            # 半徑,1 = 3x3
interval-ticks: 1    # 掃描間隔
nether-only: true    # 只在地獄生效
debug: false         # 主控台輸出追蹤訊息

predict:
  enabled: true            # 讀 Motion 向量預先鋪路
  ticks: 1                 # 往前預測幾 tick
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
