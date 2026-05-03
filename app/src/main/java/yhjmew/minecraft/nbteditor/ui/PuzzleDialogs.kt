package yhjmew.minecraft.nbteditor.ui

import android.app.AlertDialog
import android.content.Intent
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
        .setTitle("⚠️ Inventory Warning")
        .setMessage("This will generate multiple map items and pack them into shulker boxes.\n\nMake sure your inventory has at least 1 empty slot.")
        .setPositiveButton("I understand, continue") { _, _ -> showPuzzleConfigDialog() }
        .setNegativeButton("Cancel", null)
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
        .setTitle("Choose Puzzle Size")
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
        hint = "Width (cols)"
        inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    val etH = EditText(this).apply {
        hint = "Height (rows)"
        inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    layout.addView(etW); layout.addView(etH)

    AlertDialog.Builder(this)
        .setTitle("Input Dimensions")
        .setView(layout)
        .setPositiveButton("Confirm") { _, _ ->
            try {
                val strW = etW.text.toString(); val strH = etH.text.toString()
                if (strW.isEmpty() || strH.isEmpty()) {
                    toast("Please enter size"); return@setPositiveButton
                }
                puzzleCols = strW.toInt(); puzzleRows = strH.toInt()
                if (puzzleCols * puzzleRows > 100) {
                    toast("⚠️ Large size: ${puzzleCols * puzzleRows} maps will be generated")
                }
                pickPuzzleImage()
            } catch (e: Exception) { toast("Input error: ${e.message}") }
        }.show()
}

fun MainActivity.pickPuzzleImage() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }
    startActivityForResult(intent, MainActivity.REQUEST_PICK_IMAGE_FOR_PUZZLE)
}