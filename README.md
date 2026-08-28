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

## 為什麼「弱加載」與「高速載入」不衝突

> 註:`world.getEntity(uuid)` 在「已載入但非 entity-ticking」的 chunk 裡**抓得到**珍珠。
> 這在 26.1.2 的 `EntityLookup` 得到證實 —— `get(UUID)` 過的濾網是 `isAccessible()`
> 而非 `isTicking()`:
> ```java
> private static Entity maskNonAccessible(final Entity entity) {
>     final Visibility visibility = EntityLookup.getEntityStatus(entity);
>     return visibility.isAccessible() ? entity : null;
> }
> ```
> `Visibility.fromFullChunkStatus`:FULL(level 33)→ `TRACKED`(accessible、不 ticking),
> ENTITY_TICKING(level 31)→ `TICKING`。1.17 把實體改成獨立 section 儲存時引入的兩軸狀態,
> 到今天仍然成立,所以 `getTicksLived()` 的判準是可靠的。

外掛用的 ticket level 是 **31(entity ticking)**,跟 `/forceload` 同一級,沒有任何弱化。
珍珠在整趟飛行中都是完全載入、正常 tick 的實體 —— 外掛不是在追一顆未載入的珍珠,
而是**讓它從頭到尾都不會變成未載入**。

看似的循環依賴(要載入 chunk 就得先知道珍珠在哪,但珍珠沒載入就讀不到)之所以不成立,
是因為珍珠的位置與速度**不是從實體讀來的**,而是外掛記憶體裡的一個 `Step` record,
每 tick 用純算術往前推。`PearlPhysics` 完全不引用 Bukkit,實體存不存在與它無關。
因果鏈是「模型 → chunk」,實體從未參與決定要載入哪裡。

因此在正常運作下,`world.getEntity(uuid)` **每一 tick 都抓得到** —— 珍珠永遠待在提前釘好的
chunk 裡。CSV 應該幾乎全是 `REAL`。

> **物理模型不是常態運作模式,是故障恢復模式。**
> `PREDICTED` 那幾行代表那幾 tick chunk 沒及時載入好、珍珠真的凍住了,
> 模型是為了「凍住期間我們還知道要去哪裡撈它回來」而存在的。

`chunks.recovery-chunks` 就是這個恢復機制:失去實體後,模型走過但未經實體確認的前幾個 chunk
會一直釘著。珍珠只可能凍在其中之一,放掉就再也回不來了。

## ⚠️ 先決條件:`legacy-ender-pearl-behavior`

**1.21.2 起,終界珍珠會自己抓著一個 entity-ticking 的 chunk ticket,而且跟著它移動。**
這是「1.17 可以、現在不行」的原因。

Paper 26.1.2 原始碼(`Moonrise-optimisation-patches.patch`,`EntityLookup`):

```java
// 任何 ThrownEnderpearl 加入世界時
if (entity instanceof ThrownEnderpearl enderpearl) {
    this.addEnderPearl(CoordinateUtils.getChunkKey(enderpearl.chunkPosition()));
}
// 跨 chunk 時票跟著搬
if (entity instanceof ThrownEnderpearl && (oldSectionX != newSectionX || oldSectionZ != newSectionZ)) {
    this.removeEnderPearl(oldChunk);
    this.addEnderPearl(newChunk);
}

private void addEnderPearl(final long coordinate) {
    if (!this.keepEnderPearlsTicking) return;
    ...addTicketAtLevel(ENDER_PEARL_TICKER, coordinate, ChunkHolderManager.ENTITY_TICKING_TICKET_LEVEL, null);
}
```

而 `keepEnderPearlsTicking` 來自(`PaperHooks`):

```java
public boolean addTicketForEnderPearls(final ServerLevel world) {
    return !world.paperConfig().misc.legacyEnderPearlBehavior;
}
```

另外 vanilla 端還有一條:`ServerPlayer.placeEnderPearlTicket()` 會下
`TicketType.ENDER_PEARL`(半徑 2、40 tick timeout),同樣被這個設定關掉。

### 這代表什麼

| `legacy-ender-pearl-behavior` | 珍珠自帶 ticket | 弱加載蓄能 | 本外掛 |
|---|---|---|---|
| `false`(**預設**) | ✅ 跟著珍珠走 | ❌ **不可能** —— 珍珠的 chunk 永遠 entity-ticking,它會立刻 tick 並飛走 | 只剩前置載入的價值(vanilla 的票是**事後**才補上,珍珠得等 chunk 生成) |
| `true` | ❌ | ✅ 1.17 行為 | **必要** —— 沒有它珍珠飛出去就停住 |

**靠弱加載蓄能的珍珠炮,必須在 `paper-world-defaults.yml` 設:**

```yaml
misc:
  legacy-ender-pearl-behavior: true
```

外掛啟動時會反射讀這個設定並在 console 說明目前是哪一邊,`/pearltrack status` 也有一行
`pearl chunk tickets`。讀不到就顯示 unknown,不會因此失敗。

## 珍珠炮:蓄能期間絕對不能碰

靠弱加載蓄能的珍珠炮,原理是把珍珠留在一個**已載入但非 entity-ticking** 的 chunk 裡:
珍珠不 tick,所以不移動、也不會被阻力衰減,但爆炸的擊退仍然照常施加在它身上 ——
動量只進不出,一路累積。最後把該 chunk 升到 entity-ticking,珍珠帶著全部動量射出。

這對本外掛是致命的:**釘任何一格 chunk 就是把它升到 entity-ticking**。
蓄能中的珍珠 `Motion` 已經是幾千 b/t,如果只用「速度夠快」當判準,外掛會在蓄能期間
把炮的 chunk 釘住,珍珠帶著半吊子的動量提前發射,炮就廢了。

判斷邏輯抽在 `FlightGate.decide()`(純函式,有單元測試),真值表:

| 看得到 | 有 tick | 有位移 | 夠快 | 結果 |
|---|---|---|---|---|
| ✅ | ❌ | — | — | HOLD(grace 內保留 ticket,之後釋放) |
| ✅ | ✅ | ❌ | — | **HOLD 並立刻釋放** ← 被別的機制固定在原地 |
| ✅ | ✅ | ✅ | ✅ | FLYING |
| ✅ | ✅ | ✅ | ❌ | HOLD 並釋放 |
| ❌ | — | — | ✅ | FLYING(預測模式) |
| ❌ | — | — | ❌ | HOLD 並釋放 |

`ticking` 來自 `Entity#getTicksLived()` 相鄰兩次觀測有沒有變化 —— 這是唯一可靠、
不需要 chunk API 就能回答「這格 chunk 是不是 entity-ticking」的方法。

「有位移」是第二道保險:**Motion 再大,只要 Pos 沒動就一律不釘**。少釘的代價只是
珍珠晚一個 tick 出發(凍住的珍珠動量完全保留,彈道不變);釘錯的代價是炮直接報廢。
兩邊不對稱,所以所有模稜兩可的情況一律倒向「不要碰」。

只要珍珠處在 held 狀態,外掛會**主動釋放它的所有 ticket**,並把最後一次 held 的
chunk(± `chunks.hold-exclusion-radius`)從任何釘選集合裡剔除。

發射的那一刻通常是「珍珠突然從視野消失」(它在該 tick 移動出已載入範圍)。外掛從最後
觀測到的 `(pos, motion)` 接手預測 —— 凍住的珍珠動量完全保留,所以這一 tick 的停頓
不影響彈道,只是整趟晚一個 tick。

### 用在珍珠炮上的流程

```
/pearltrack adopt          # 珍珠已經在炮裡蓄能 → 直接認養,不會驚動它
/pearltrack status         # 看 momentum 累積速率、以及「現在發射能飛多遠」
(發射)                     # 外掛在它離開視野的下一 tick 自動接手
```

`/pearltrack next` 也可以,差別是它在珍珠被丟出的那一刻就開始追(蓄能全程都看得到)。
`auto-track-all-pearls` 的自動偵測**不會**認養蓄能中的珍珠(它會同時檢查 ticking),
這是刻意的 —— 自動邏輯不該碰你的炮。

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

## 模擬測試

`src/test/.../sim/SimulatedServer.java` 用 `java.lang.reflect.Proxy` 代理 Bukkit 的
`World` / `Server` / `Entity` / `Plugin` 介面,讓**真正的** `PearlTracker`、
`ChunkTicketManager`、`FlightGate` 對著一個假世界跑。假世界重現的是整個設計依賴的那條規則:

> 實體只有在它所在的 chunk 被釘住**且**已載入時才會 tick。

這不是 Paper 伺服器 —— 地形生成、碰撞、ticket level 傳播都是模型而非真實。但它會在真實伺服器
會凍住珍珠的地方凍住珍珠,所以 tracker 的每一條路徑都被實際執行過。

`loadDelayTicks` 模擬地形生成延遲,`alwaysLoaded` / `alwaysTicking` 分別模擬「弱加載」與
「正常加載」的 chunk —— 珍珠炮的蓄能就是前者。

跑 `./gradlew test`。基準測試 `withoutTheTrackerTheSecondHopIsTheLastOne` 驗證前提:
沒有外掛時 1000 b/t 的珍珠飛一 tick 就永久凍住。

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

## 指令方塊 / 外掛設定的 Motion

如果你是用指令方塊或其他外掛在 `ProjectileLaunchEvent` **之後**才把 `Motion` 灌進珍珠,
那麼事件當下量到的速度還是普通的 ~1.5 b/t,會被 `only-if-speed-above` 濾掉。

`tracking.late-speed-check-ticks`(預設 3)處理這件事:沒過門檻的珍珠會被放進觀察名單,
接下來幾個 tick 再量一次速度,超標就接手。因為 scheduler 跑在實體移動之前,
珍珠在下一 tick 開頭還停在原地、Motion 已經是新的,還來得及釘落點 chunk。

判斷 Motion 是不是外部寫進去的:比對 `Rotation` 與 `Motion`。Rotation 是實體移動時
從 Motion 推算的,若 `Rotation` 的 pitch 跟 `atan2(-vy, hypot(vx,vz))` 對不上,
就代表這個 Motion 還沒被實體套用過。

## 限制

- **僅支援 vanilla Paper，不支援 Folia。** 超高速實體會不斷跨越 region，需要另一套
  `RegionScheduler` 設計。
- 追蹤狀態不跨伺服器重啟。實體上的 PDC tag（`pearltrack:tracked`）會留著，方便事後辨識。
- 一路生成全新地形（約每 tick 一個 chunk）本身是重負載，這是主動 forceload 無法迴避的成本。
- `ProjectileLaunchEvent` 當下若有其他外掛在事件之後才設定速度，速度門檻可能誤判；
  用 `/pearltrack next` 可以繞過門檻。
