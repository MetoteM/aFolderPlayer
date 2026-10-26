package dev.alex.folderplayer

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Keep the list usable in short windows; secondary controls scroll in their own column. */
internal class BrowserPane(
    context: Context,
    private val portrait: List<View>,
    private val results: View,
    private val heading: List<View>,
    private val controls: List<View>,
) : LinearLayout(context) {
    private var wide: Boolean? = null
    private var paneWidth = 0
    private val title = heading.first() as TextView
    private val titleSize = title.textSize
    private val titleLines = title.maxLines
    private val status = heading[1] as TextView
    private val statusLines = status.maxLines

    init {
        orientation = VERTICAL
        rebuild(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec)
        val h=MeasureSpec.getSize(heightMeasureSpec)
        if(w>0 && h>0) {
            val landscape=w>h
            if(wide!=landscape || landscape && paneWidth!=w)rebuild(landscape,w)
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }

    private fun rebuild(landscape: Boolean, availableWidth: Int = 0) {
        paneWidth=availableWidth
        wide = landscape
        val first = (results as? ScrollView)?.scrollY ?: 0
        portrait.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        removeAllViews()
        orientation = if (landscape) HORIZONTAL else VERTICAL
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (landscape) 18 * resources.displayMetrics.scaledDensity else titleSize)
        title.maxLines = if (landscape) 1 else titleLines
        status.maxLines = if (landscape) 1 else statusLines
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        if (!landscape) {
            portrait.forEach { addView(it, if (it === results) LayoutParams(-1,0,1f) else LayoutParams(-1,-2)) }
        } else {
            val browser = LinearLayout(context).apply { orientation=VERTICAL }
            val side = LinearLayout(context).apply { orientation=VERTICAL }
            val label = LinearLayout(context).apply { orientation=HORIZONTAL }
            label.addView(heading[0], LayoutParams(0,-2,1f))
            label.addView(heading[1], LayoutParams(-2,-2))
            browser.addView(label)
            heading.drop(2).forEach { browser.addView(it,LayoutParams(-1,-2)) }
            browser.addView(results,LayoutParams(-1,0,1f))
            controls.forEach { side.addView(it,LayoutParams(-1,-2)) }
            val panel = ScrollView(context).apply { addView(side);isFillViewport=false }
            addView(browser,LayoutParams(0,-1,1f))
            val width = (availableWidth*.4f).toInt()
                .coerceAtMost((280*resources.displayMetrics.density).toInt())
            addView(panel,LayoutParams(width,-1))
        }
        if (results is ScrollView && first>0) results.post { results.scrollTo(0,first) }
        requestLayout()
    }

    companion object {
        fun install(root: LinearLayout, results: View, heading: List<View>, controls: List<View>) {
            val children = (0 until root.childCount).map { root.getChildAt(it) }
            // Detach before constructing the pane: its first rebuild adopts the original children.
            root.removeAllViews()
            root.addView(BrowserPane(root.context,children,results,heading,controls),LayoutParams(-1,-1))
        }
    }
}
