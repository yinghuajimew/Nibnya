package yhjmew.nibnya.map

import android.graphics.Bitmap

/**
 * 地图瓦片来源。
 *
 * ## 为什么要有这一层
 * 旧版 [MapSession.render] 一次性把整个范围画进一张 Bitmap，
 * 于是**图有多大就只能看多大** —— 半径 64 区块时边长 2064px，
 * 再往外滑就什么都没有。
 *
 * 改成瓦片后，「能看多大」和「图有多大」彻底解耦：
 * 视图按当前视野算出需要哪几块，缺的异步补，滑到哪里画到哪里。
 *
 * ## 与 SeedCraft 的对应关系
 * 接口形状取自 SeedCraft 的 `MapView.TileSource`（GPL-3.0），
 * 字段含义保持一致，方便对着两边代码看：
 *
 * ```
 * tileCells()        → cells，一块瓦片多少采样点（128）
 * blocksPerCell()    → 一个采样点覆盖多少方块（4/16/64/256）
 * get()              → 命中缓存直接返回，未命中返回 null
 * request()          → 异步生成，完成后回调 invalidate
 * setWanted()        → 视图当前真正需要的瓦片，用来丢掉滑动中过期的请求
 * generationId()     → 切换世界时 +1，旧结果自动作废
 * ```
 *
 * ## 线程
 * [get] / [blocksPerCell] / [key] / [tileCells] 在 **UI 线程**调用，必须瞬时返回；
 * [request] 投递任务后立即返回，真正干活在后台线程。
 */
interface TileSource {

    /** 一块瓦片的边长（采样点）。128 在手机上约等于一屏。 */
    fun tileCells(): Int

    /**
     * 一个采样点覆盖多少方块。
     *
     * 与 [scale] 有关：视图把「每像素多少方块」量化成 4/16/64/256 几档，
     * 传入的就是这一档。允许返回比 [scale] 更小的值（放大到极限时
     * 存档层用 1 方块/采样点，而种子层仍按 4）。
     */
    fun blocksPerCell(scale: Int): Int

    /** 缓存命中返回位图；未命中返回 null（调用方负责 request）。 */
    fun get(scale: Int, tileX: Int, tileZ: Int): Bitmap?

    /** 投递一次生成。已在排队或已缓存时应立即返回。 */
    fun request(scale: Int, tileX: Int, tileZ: Int)

    /** 视图当前需要的全部瓦片键。不在集合里的生成结果会被丢弃。 */
    fun setWanted(keys: Collection<String>)

    /** 缓存键，必须包含 generation，避免换世界后命中旧图。 */
    fun key(scale: Int, tileX: Int, tileZ: Int): String

    /** 数据是否已就绪（世界打开完成）。false 时视图显示「加载中」。 */
    fun isReady(): Boolean

    /** 切换世界 / 重开时递增，用于作废旧瓦片。 */
    fun generationId(): Int

    /**
     * 一块瓦片产出的通知（后台线程调用）。
     *
     * 视图收到后应 [android.view.View.invalidate]，不要在这里碰 UI 对象。
     */
    fun setInvalidation(callback: (() -> Unit)?)

    /** 是否已经没有任何数据（没区块、没种子）—— 视图据此显示占位提示。 */
    fun hasAnyData(): Boolean = true
}
