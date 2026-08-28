# PearlTrack

Paper 26.1.2 外掛：讓**飛行速度極快的終界珍珠**（水平速度可達 1000+ blocks/tick）不會因為一 tick 內飛出已載入區塊而停擺。

原理很單純：珍珠飛得再快，它要繼續 tick 就只需要**它自己所在的那一個 chunk** 是 entity-ticking。
所以每個 tick，外掛用彈道公式算出珍珠**下一 tick 會落在哪個 chunk**，趕在實體移動之前把那些 chunk 釘住；
飛過去的就放掉。實體一旦重新出現在已載入區塊，就用真實的 Pos/Motion 覆蓋預測值重新校正。

---

## 為什麼這樣可行 — tick 順序

Bukkit 的 scheduler heartbeat 在 `MinecraftServer#tickServer` 中**早於**世界／實體 tick 執行。
所以 `runTaskTimer(..., 1L, 1L)` 的同步任務在第 N tick 讀到的是第 N−1 tick 結束的狀態，
而此刻下的 chunk ticket **會在本 tick 的實體移動之前生效** —— 剛好就是「先載入、再讓它飛進去」。

`PearlTracker` 因此**必須**留在主執行緒的同步任務上。整個外掛唯一跨執行緒的地方是 `FlightLogger`
的寫檔佇列（主執行緒 offer，async 任務 drain）。

## 幾個先算清楚的數字（drag = 0.99）

阻力是等比衰減，所以整段飛行是收斂的幾何級數：

| 初速 v₀ | 總水平射程 `v₀/(1−drag)` | 衰減到 <16 b/t | 那時已飛了 |
|---|---|---|---|
| 1,000 b/t | **≈ 100,000 blocks** | 412 ticks（20.6 秒） | ≈ 98,400 blocks（98.4%） |
| 10,000 b/t | ≈ 1,000,000 blocks | 642 ticks | ≈ 984,000 blocks |

要飛到百萬等級需要 v₀ ≈ 10,000 b/t。座標硬天花板在 ±3.0×10⁷ 附近（`tracking.coordinate-limit`）。

垂直方向終端速度約 −3 b/t，412 ticks 會掉約 1,200 blocks —— **在正常世界裡珍珠會先撞到地形**。
想真的飛完全程，把 `tracking.disable-collision` 打開（`Entity#setNoPhysics`，收斂或結束時自動還原）。

## Chunk 預算

- 一顆珍珠同時只釘 `lookahead-ticks + 1` 個 chunk（預設 6 個，`forceload-radius: 0` 時）。
- `chunks.max-forced-chunks` 是**外掛自己的預算保護，不是伺服器限制**。常被引用的 256 是
  `/forceload add` **單一指令**的區域上限；`World#setChunkForceLoaded` / `addPluginChunkTicket`
  這兩個 API 本身沒有硬上限。
- 預算滿了會**先砍最遠的預測 chunk**（`desired` 集合按「最近的未來優先」排序）。
- **不載整條走廊**：1000 b/t 一 tick 橫跨 60+ 個 chunk，全載會瞬間吃光預算，而且珍珠不需要。
  代價是沿途未載入區域不做碰撞判定。要沿途碰撞就調大 `forceload-radius`。

### `PLUGIN_TICKET` vs `FORCE_LOADED`

|  | `setChunkForceLoaded` | `addPluginChunkTicket`（預設） |
|---|---|---|
| 寫進存檔 | ✅ 會（崩潰後永久殘留） | ❌ 不會 |
| plugin disable 自動釋放 | ❌ | ✅ |
| ticket level | 31（entity ticking） | 31（entity ticking） |

選 `FORCE_LOADED` 時外掛會寫一份 `forced-chunks.journal`，下次啟動若發現這個檔案還在
（＝上次是非正常關閉），會自動把殘留的 chunk 解除。

## 收斂

水平速度掉到 `convergence-threshold`（預設 16 b/t，＝一 tick 不再跨越整個 chunk）時：

1. 記錄**收斂點**座標與 tick，寫進 log；
2. 停止預測式的 lookahead 釘選，改成一塊跟著珍珠移動的 `keep-radius-after-convergence`（預設 5×5）區域；
3. 還原碰撞（如果先前關掉了），讓它自然落地；
4. `keep-ticks-after-convergence` 之後完全放手。

> 注意：16 b/t 時**還有約 1,600 blocks 要飛**（16/(1−drag)）。一塊固定不動的區域撐不住，
> 所以這裡用的是跟著走的視窗；預設 600 ticks 足夠讓它落地。

## 指令

| 指令 | 說明 |
|---|---|
| `/pearltrack status [id]` | 最後真實座標＋那時的 tick、目前（真實或預測）座標、下一 tick 預測座標、水平速度、目前 chunk、已釘 chunk 數、校正次數與 drift、預估收斂 tick 與剩餘射程、CSV 檔名 |
| `/pearltrack list` | 所有追蹤中的珍珠 |
| `/pearltrack next` | 標記「我接下來丟的那一顆」，無視速度門檻 |
| `/pearltrack stop <id\|all>` | 停止追蹤並釋放 chunk |
| `/pearltrack reload` | 重讀 config.yml |

別名 `/pt`，權限 `pearltrack.use`（預設 op）。`id` 可以只打 UUID 前 8 碼。

## 飛行記錄

`plugins/PearlTrack/flights/<時間>_<uuid8>.csv`：

```
tick,event,x,y,z,vx,vy,vz,hSpeed,chunkX,chunkZ,drift,pinnedChunks,travelled,note
```

`event` 為 `LAUNCH` / `REAL` / `PREDICTED` / `LOST` / `CONVERGED` / `END`，
所以可以直接看出哪幾 tick 是真實觀測、哪幾 tick 是模型推算的。

`drift` 是**校正當下預測值與真實座標的距離**，這是校準係數的關鍵欄位：
如果 drift 持續往同一方向累積，就調 `physics.drag` / `physics.gravity`，或把 `physics.order`
從 `VANILLA` 換成 `GRAVITY_FIRST`（兩者水平完全相同，只差垂直終端速度 −3.00 vs −2.97 b/t）。

## 建置

需要 **JDK 25**（Paper 26.1.2 的 class 檔是 Java 25，javac 25 才讀得動）。

```bash
./gradlew build
# 產物：build/libs/PearlTrack-1.0.0.jar
```

若 JDK 25 不在 Gradle 的預設搜尋路徑：

```bash
./gradlew build -Porg.gradle.java.installations.paths=/path/to/jdk-25
```

`PearlPhysics` 完全不碰 Bukkit 型別，所以 `./gradlew test` 不需要伺服器就能跑完彈道模型的驗證。

## 限制

- **僅支援 vanilla Paper，不支援 Folia。** 超高速實體會不斷跨越 region，需要另一套
  `RegionScheduler` 設計。
- 追蹤狀態不跨伺服器重啟。實體上的 PDC tag（`pearltrack:tracked`）會留著，方便事後辨識。
- 一路生成全新地形（約每 tick 一個 chunk）本身是重負載，這是主動 forceload 無法迴避的成本。
- `ProjectileLaunchEvent` 當下若有其他外掛在事件之後才設定速度，速度門檻可能誤判；
  用 `/pearltrack next` 可以繞過門檻。
