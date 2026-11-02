package yhjmew.nibnya.util

import android.content.Context
import androidx.core.content.ContextCompat
import yhjmew.nibnya.R

/**
 * 色槽注册表 —— 「哪些地方的颜色允许用户改」的**唯一清单**。
 *
 * 每一项都把 `R.color.<key>` 解析成 ARGB，存进 [ColorScheme.Slot.defaultResId]。
 * 之所以这里存解析后的 int 而不是资源 id，是因为：
 *  - 默认值是「当前主题下该用哪个色」，日间/夜间由系统按 night 变体自动选
 *  - 一旦解析出来就是确定值，后面比较、写盘、做色块预览都方便
 *
 * ⚠️ 新增可配色项时，只要在 [build] 里加一行，设置页会自动出现，
 *    布局里记得把硬编码色替换成 `@color/<key>` 并给色块 View 加 id（见 [viewIds]）。
 */
object ColorSlots {

    /** 色槽 key -> 布局里那个纯色 View 的 id（按钮直接设背景色，不需要 View） */
    val viewIds: Map<String, Int> = mapOf(
        "c_icon_tree_fs" to R.id.view_bg_fullscreen_tree,
        "c_icon_view_fs" to R.id.view_bg_fullscreen_view,
        "c_icon_search_fs" to R.id.view_bg_fullscreen_search,
        "c_icon_tree" to R.id.view_bg_tree_mode,
        "c_icon_view" to R.id.view_bg_view_options,
        "c_icon_search" to R.id.view_bg_search_nbt,
    )

    /** 按钮类色槽 -> 直接设背景的那个控件 id */
    val buttonIds: Map<String, Int> = mapOf(
        "c_btn_player" to R.id.btn_online_players,
        "c_btn_map" to R.id.btn_map_nbt,
        "c_btn_village" to R.id.btn_village_nbt,
        "c_btn_any_key" to R.id.btn_open_any_key,
        "c_btn_global" to R.id.btn_global_nbt,
        "c_btn_copy" to R.id.btn_copy,
        "c_btn_save" to R.id.btn_save,
        "c_btn_edit_player" to R.id.btn_edit_player,
        "c_btn_backup" to R.id.btn_backup_history,
        "c_btn_parse" to R.id.btn_parse,
        "c_btn_fullscreen" to R.id.btn_go_fullscreen,
        "c_btn_clear_cache" to R.id.btn_clear_cache,
        "c_btn_switch_path" to R.id.btn_switch_path,
        "c_bar_top" to R.id.layout_fullscreen_bar,
        "c_bar_sidebar" to R.id.sidebar_header,
    )

    /** 全部色槽，按设置页里的分组顺序排列 */
    fun build(ctx: Context): List<ColorScheme.Slot> = listOf(
        // ── 主页 · 功能按钮 ──
        slot(ctx, "c_btn_player",      R.string.color_group_home,    R.string.color_name_btn_player),
        slot(ctx, "c_btn_map",         R.string.color_group_home,    R.string.color_name_btn_map),
        slot(ctx, "c_btn_village",     R.string.color_group_home,    R.string.color_name_btn_village),
        slot(ctx, "c_btn_any_key",     R.string.color_group_home,    R.string.color_name_btn_any_key),
        slot(ctx, "c_btn_global",      R.string.color_group_home,    R.string.color_name_btn_global),

        // ── 主页 · 工具栏 ──
        slot(ctx, "c_btn_copy",        R.string.color_group_toolbar, R.string.color_name_btn_copy),
        slot(ctx, "c_btn_save",        R.string.color_group_toolbar, R.string.color_name_btn_save),
        slot(ctx, "c_btn_edit_player", R.string.color_group_toolbar, R.string.color_name_btn_edit_player),
        slot(ctx, "c_btn_backup",      R.string.color_group_toolbar, R.string.color_name_btn_backup),
        slot(ctx, "c_btn_parse",       R.string.color_group_toolbar, R.string.color_name_btn_parse),
        slot(ctx, "c_btn_fullscreen",  R.string.color_group_toolbar, R.string.color_name_btn_fullscreen),

        // ── 侧边栏 ──
        slot(ctx, "c_bar_sidebar",     R.string.color_group_sidebar, R.string.color_name_bar_sidebar),
        slot(ctx, "c_btn_clear_cache", R.string.color_group_sidebar, R.string.color_name_btn_clear_cache),
        slot(ctx, "c_btn_switch_path", R.string.color_group_sidebar, R.string.color_name_btn_switch_path),

        // ── 小图标按钮（主页）──
        slot(ctx, "c_icon_tree",       R.string.color_group_icons,   R.string.color_name_icon_tree),
        slot(ctx, "c_icon_view",       R.string.color_group_icons,   R.string.color_name_icon_view),
        slot(ctx, "c_icon_search",     R.string.color_group_icons,   R.string.color_name_icon_search),

        // ── 全屏模式 ──
        slot(ctx, "c_bar_top",         R.string.color_group_fullscreen, R.string.color_name_bar_top),
        slot(ctx, "c_icon_tree_fs",    R.string.color_group_fullscreen, R.string.color_name_icon_tree_fs),
        slot(ctx, "c_icon_view_fs",    R.string.color_group_fullscreen, R.string.color_name_icon_view_fs),
        slot(ctx, "c_icon_search_fs",  R.string.color_group_fullscreen, R.string.color_name_icon_search_fs),

        // ── 设置 / 关于弹窗 ──
        slot(ctx, "c_header_green",      R.string.color_group_dialog, R.string.color_name_header_green),
        slot(ctx, "c_header_green_dark", R.string.color_group_dialog, R.string.color_name_header_green_dark),
        slot(ctx, "c_badge_settings",    R.string.color_group_dialog, R.string.color_name_badge_settings),
        slot(ctx, "c_badge_about",       R.string.color_group_dialog, R.string.color_name_badge_about),
    )

    private fun slot(ctx: Context, key: String, groupRes: Int, labelRes: Int): ColorScheme.Slot {
        val resId = ctx.resources.getIdentifier(key, "color", ctx.packageName)
        val default = if (resId != 0) ContextCompat.getColor(ctx, resId) else 0xFF888888.toInt()
        return ColorScheme.Slot(key, default, groupRes, labelRes)
    }
}
