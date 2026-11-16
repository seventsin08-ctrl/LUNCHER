package com.luncher.home

import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Shows apps either as a 4-column icon grid or as a list of rows. */
class AppListAdapter(
    private val grid: Boolean,
    private val onClick: (AppInfo, View) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private var items: List<AppInfo> = emptyList()

    fun submit(list: List<AppInfo>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class Holder(view: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val icon = ImageView(ctx).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * IconFactory.CORNER)
                }
            }
        }
        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
        }
        val root = LinearLayout(ctx)
        if (grid) {
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER_HORIZONTAL
            root.setPadding(0, dp(8), 0, dp(10))
            root.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
            label.textSize = 11.5f
            label.gravity = Gravity.CENTER
            root.addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2) }
            )
        } else {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(4), dp(7), dp(4), dp(7))
            root.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            label.textSize = 17f
            root.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
            )
        }
        root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return Holder(root, icon, label)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        holder.icon.setImageBitmap(app.icon)
        holder.label.text = app.label
        holder.itemView.setOnClickListener { onClick(app, holder.icon) }
        holder.itemView.setOnLongClickListener { onLongClick(app, holder.icon); true }
    }
}
