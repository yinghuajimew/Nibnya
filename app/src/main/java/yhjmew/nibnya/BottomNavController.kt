package yhjmew.nibnya

import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * 底部三格导航（仿 Android 三键导航）：主页 / 地图 / 其他。
 *
 * 2026-11-02 加入。当前「地图」「其他」两页**只是占位**，
 * 目的是先把「切换」这条链路跑通、验证观感，再决定里面放什么。
 *
 * 设计要点：
 * - 三页都在同一个 [android.widget.FrameLayout] 里叠着，靠 `isVisible` 切换，
 *   不重建 Fragment、不销毁 View —— 切回去时滚动位置、编辑状态全都还在。
 * - 选中态用 [app_accent] 高亮 + 加粗，未选中用次级文字色，三格等宽。
 * - 图标暂缺（用户要求先纯文本验证），将来要加图标时，
 *   在 `bar_bottom_nav.xml` 里给每格塞一个 ImageView 即可，本类不用改。
 */
internal class BottomNavController(private val activity: MainActivity) {

    enum class Page(val tabId: Int, val textId: Int) {
        HOME(R.id.nav_tab_home, R.id.nav_text_home),
        MAP(R.id.nav_tab_map, R.id.nav_text_map),
        MORE(R.id.nav_tab_more, R.id.nav_text_more),
    }

    private val pages = mapOf(
        Page.HOME to R.id.page_home,
        Page.MAP to R.id.page_map,
        Page.MORE to R.id.page_more,
    )

    var current: Page = Page.HOME
        private set

    /** 上次选中的非主页页签。从主页点「返回」时优先回到它。 */
    private var lastNonHome: Page = Page.MAP

    init {
        Page.entries.forEach { page ->
            activity.findViewById<android.view.View>(page.tabId)?.setOnClickListener {
                select(page)
            }
        }
    }

    fun select(page: Page) {
        current = page
        if (page != Page.HOME) lastNonHome = page

        pages.forEach { (p, viewId) ->
            activity.findViewById<android.view.View>(viewId)?.isVisible = (p == page)
        }

        // 选中态：高亮 + 加粗；未选中：次级灰
        // 跟随配色方案：用户改过 c_nav_selected 就用他的，否则用默认值
        val accent = activity.prefs?.let { p ->
            val ctx = activity.applicationContext
            val slot = yhjmew.nibnya.util.ColorSlots.build(ctx)
                .first { it.key == "c_nav_selected" }
            yhjmew.nibnya.util.ColorScheme.value(p, slot, yhjmew.nibnya.util.ColorScheme.isNight(ctx))
        } ?: ContextCompat.getColor(activity, R.color.nav_selected)
        val dim = ContextCompat.getColor(activity, R.color.app_text_secondary)
        Page.entries.forEach { p ->
            val tv = activity.findViewById<TextView>(p.textId) ?: return@forEach
            val on = p == page
            tv.setTextColor(if (on) accent else dim)
            tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL)
        }
    }

    /**
     * 拦截返回键。
     *
     * @return true 表示事件已被消费（从别的页回到了主页），false 交给系统。
     */
    fun handleBack(): Boolean {
        if (current == Page.HOME) return false
        select(Page.HOME)
        return true
    }

    fun saveState(outState: android.os.Bundle) {
        outState.putString(KEY_PAGE, current.name)
        outState.putString(KEY_LAST_NON_HOME, lastNonHome.name)
    }

    fun restoreState(state: android.os.Bundle?) {
        if (state == null) return
        val name = state.getString(KEY_PAGE) ?: return
        val page = runCatching { Page.valueOf(name) }.getOrNull() ?: return
        val last = state.getString(KEY_LAST_NON_HOME)
            ?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?.takeIf { it != Page.HOME }
        if (last != null) lastNonHome = last
        select(page)
    }

    private companion object {
        const val KEY_PAGE = "bottomNavPage"
        const val KEY_LAST_NON_HOME = "bottomNavLastNonHome"
    }
}
