package yhjmew.nibnya.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import yhjmew.nibnya.R

/**
 * 按当前配色方案给界面刷色（2026-11-02）。
 *
 * **必须在 `setContentView` 之后、界面稳定之前调用一次**，
 * 否则用户改完颜色回到主页，看到的还是布局里那份默认色。
 *
 * 处理三类东西：
 * 1. 纯色块 View（`ColorSlots.viewIds`）—— `setBackgroundColor`
 * 2. 按钮（`ColorSlots.buttonIds`）—— 用 `ColorDrawable` 覆盖 backgroundTint
 *    因为 `backgroundTint` 是布局属性，运行时改要换 tint list
 * 3. 圆形徽章 —— 保留 oval 形状，只换 solid 色
 *
 * 文字颜色：深底配白字、浅底配深字，避免用户选了浅黄之后白字看不见。
 * 这是 [ColorScheme.onColor] 的职责。
 */
object ColorApplier {

    fun apply(activity: Activity, prefs: SharedPreferences) {
        val ctx: Context = activity
        val night = ColorScheme.isNight(ctx)
        val slots = ColorSlots.build(ctx)
        val byKey = slots.associateBy { it.key }

        // ── 1) 按钮类 ──
        ColorSlots.buttonIds.forEach { (key, viewId) ->
            val slot = byKey[key] ?: return@forEach
            val color = ColorScheme.value(prefs, slot, night)
            val v = activity.findViewById<View?>(viewId) ?: return@forEach

            if (v is Button) {
                // backgroundTint 在布局里设的，运行时通过 ColorDrawable 直接覆盖背景
                v.background = ColorDrawable(color)
                v.backgroundTintList = null
                v.setTextColor(ColorScheme.onColor(color))
            } else {
                v.setBackgroundColor(color)
                // 顶栏/侧边栏里的文字要跟着反色
                if (v is android.view.ViewGroup) {
                    applyTextColorRecursive(v, ColorScheme.onColor(color))
                }
            }
        }

        // ── 2) 纯色块 View ──
        ColorSlots.viewIds.forEach { (key, viewId) ->
            val slot = byKey[key] ?: return@forEach
            val color = ColorScheme.value(prefs, slot, night)
            activity.findViewById<View?>(viewId)?.setBackgroundColor(color)
        }

        // ── 3) 圆形徽章：保留 oval，只换 solid ──
        run {
            val settingsBadge = activity.findViewById<View?>(R.id.badge_settings_circle)
            val aboutBadge = activity.findViewById<View?>(R.id.badge_about_circle)
            // 这个布局在对话框里，不属于 Activity，由 EditorDialogHelper 单独处理
        }
    }

    /** 只刷 Activity 自身布局里的东西；对话框另走 [applyToDialogView] */
    fun applyToDialogView(
        root: View,
        prefs: SharedPreferences,
        night: Boolean,
        settingsBadgeRes: Int,
        aboutBadgeRes: Int,
    ) {
        val ctx = root.context
        val slots = ColorSlots.build(ctx).associateBy { it.key }

        fun color(key: String): Int? =
            slots[key]?.let { ColorScheme.value(prefs, it, night) }

        // 设置圆底
        color("c_badge_settings")?.let { c ->
            root.findViewById<View?>(settingsBadgeRes)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
        }
        // 关于圆底
        color("c_badge_about")?.let { c ->
            root.findViewById<View?>(aboutBadgeRes)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
        }
        // 顶部渐变头
        val start = color("c_header_green")
        val end = color("c_header_green_dark")
        if (start != null && end != null) {
            root.findViewById<View?>(R.id.dialog_color_header)?.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(start, end),
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(ctx, 8f), dp(ctx, 8f), dp(ctx, 8f), dp(ctx, 8f),
                    0f, 0f, 0f, 0f,
                )
            }
        }
    }

    private fun applyTextColorRecursive(v: android.view.ViewGroup, color: Int) {
        for (i in 0 until v.childCount) {
            when (val child = v.getChildAt(i)) {
                is TextView -> child.setTextColor(color)
                is android.view.ViewGroup -> applyTextColorRecursive(child, color)
            }
        }
    }

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
}
