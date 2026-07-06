package yhjmew.minecraft.nbteditor.ui

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import yhjmew.minecraft.nbteditor.MainActivity
import yhjmew.minecraft.nbteditor.R

/**
 * 拼图配置对话框（MainActivity 扩展函数）
 */
fun MainActivity.showPuzzleWarningDialog() {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_warning_inventory))
        .setMessage(getString(R.string.msg_warning_puzzle))
        .setPositiveButton(getString(R.string.btn_i_understand_continue)) { _, _ -> showPuzzleConfigDialog() }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

fun MainActivity.showPuzzleConfigDialog() {
    val options = arrayOf(
        getString(R.string.puzzle_opt_default),   // 1x1
        getString(R.string.puzzle_opt_2x2),        // 2x2
        getString(R.string.puzzle_opt_3x3),        // 3x3
        getString(R.string.puzzle_opt_custom)      // custom
    )
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_choose_puzzle_size))
        .setItems(options) { _, w ->
            when (w) {
                0 -> { puzzleRows = 1; puzzleCols = 1; pickPuzzleImage() }
                1 -> { puzzleRows = 2; puzzleCols = 2; pickPuzzleImage() }
                2 -> { puzzleRows = 3; puzzleCols = 3; pickPuzzleImage() }
                3 -> showPuzzleCustomSizeDialog()
            }
        }.show()
}

private fun MainActivity.showPuzzleCustomSizeDialog() {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(30, 20, 30, 0)
    }
    val etW = EditText(this).apply {
        hint = getString(R.string.hint_width_col)
        inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    val etH = EditText(this).apply {
        hint = getString(R.string.hint_height_row)
        inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    layout.addView(etW); layout.addView(etH)

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_input_dimensions))
        .setView(layout)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            try {
                val strW = etW.text.toString(); val strH = etH.text.toString()
                if (strW.isEmpty() || strH.isEmpty()) {
                    toast(getString(R.string.toast_please_enter_size)); return@setPositiveButton
                }
                puzzleCols = strW.toInt(); puzzleRows = strH.toInt()
                if (puzzleCols * puzzleRows > 100) {
                    toast(getString((R.string.toast_huge_size_warning), puzzleCols * puzzleRows))
                }
                pickPuzzleImage()
            } catch (e: Exception) { toast(getString(R.string.toast_input_error, e.message)) }
        }.show()
}

fun MainActivity.pickPuzzleImage() {
    puzzleImageLauncher.launch(arrayOf("image/*"))
}