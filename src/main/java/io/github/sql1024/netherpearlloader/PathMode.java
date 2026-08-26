package io.github.sql1024.netherpearlloader;

import java.util.Locale;

/**
 * 鋪路策略。
 *
 * <p>在 Paper 26.1.2 實測(地獄 y=200,1750 格/tick)得到的關鍵事實:珍珠一個 tick 內
 * 直接位移 1732.5 格、穿過 108 個未生成的區塊,沒有碰撞、Motion 一格不少地保留。也就是說
 * <b>沿途的區塊根本不需要載入</b>,珍珠只需要「它這一 tick 停下來的那個區塊」是 31 級
 * (entity ticking),下一 tick 才有辦法繼續飛。
 *
 * <p>於是有兩種鋪法,成本差了兩個數量級:
 * <ul>
 *   <li>{@link #CORRIDOR}:沿行進路線整條鋪。低速時能讓碰撞判定正常運作(珍珠會真的撞到
 *       沿途地形),但成本與速度成正比——1750 格/tick 要 327 區塊/tick,不可能。</li>
 *   <li>{@link #LANDING}:只鋪未來每個 tick 的<b>落點</b>。成本 = predict.ticks × (2r+1)²,
 *       <b>與速度完全無關</b>。代價是沿途不做碰撞判定(珍珠直接穿過去)——但那本來就是
 *       未載入區塊的既有行為,不是這個模式造成的。</li>
 * </ul>
 */
enum PathMode {

    /** 走廊超過 max-chunks-per-pearl 時自動切成 LANDING,否則用 CORRIDOR。 */
    AUTO,
    /** 沿路線整條鋪。 */
    CORRIDOR,
    /** 只鋪未來每個 tick 的落點。 */
    LANDING;

    static PathMode from(final String raw) {
        if (raw == null) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return AUTO;
        }
    }
}
