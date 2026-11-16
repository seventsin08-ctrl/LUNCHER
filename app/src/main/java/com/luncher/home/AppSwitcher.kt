package com.luncher.home

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * iOS-style App Switcher: a horizontal carousel of cards for recently used apps.
 * Tap a card to jump back into that app; flick it up to remove it from the list.
 */
class AppSwitcher(
    private val ctx: Context,
    private val root: FrameLayout,
    private val onOpen: (AppInfo, View) -> Unit,
    private val onRemove: (AppInfo) -> Unit,
    private val onClosed: () -> Unit
) {
    class Entry(val app: AppInfo, val lastUsed: Long)

    private var layer: FrameLayout? = null
    val isShowing: Boolean get() = layer != null

    private val ease = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun show(entries: List<Entry>, animScale: Float, topInset: Int, bottomInset: Int) {
        if (layer != null) return
        val items = entries.toMutableList()

        val l = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isClickable = true
            alpha = 0f
            setOnClickListener { hide(animScale) }
        }

        val w = root.width
        val h = root.height
        val cardW = (w * 0.66f).toInt()
        val cardH = ((h - topInset - bottomInset) * 0.62f).toInt()

        val title = TextView(ctx).apply {
            text = if (items.isEmpty()) "No Recent Apps" else "Recent Apps"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        l.addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = topInset + dp(18)
        })

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding((w - cardW) / 2, 0, (w - cardW) / 2, 0)
        }
        val adapter = CardAdapter(items, cardW, cardH)
        rv.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(rv)

        // Cards shrink and fade slightly as they move away from the center.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = styleCards(recyclerView)
        })

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos !in items.indices) return
                val removed = items.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                onRemove(removed.app)
                if (items.isEmpty()) {
                    title.text = "No Recent Apps"
                    root.postDelayed({ hide(animScale) }, 250)
                }
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.3f
        }).attachToRecyclerView(rv)

        l.addView(rv, FrameLayout.LayoutParams(MATCH_PARENT, cardH + dp(60), Gravity.CENTER_VERTICAL))

        val hint = TextView(ctx).apply {
            text = "Tap to open  ·  Swipe a card up to close it"
            setTextColor(0x80FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        l.addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = bottomInset + dp(28)
        })

        root.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer = l

        // Open on the previous app (index 0) and animate the carousel in from below.
        rv.translationY = dp(120).toFloat()
        rv.scaleX = 0.9f
        rv.scaleY = 0.9f
        val d = (380 * animScale).toLong()
        l.animate().alpha(1f).setDuration((220 * animScale).toLong()).setInterpolator(ease).start()
        rv.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(d).setInterpolator(ease).start()
        rv.post { styleCards(rv) }
    }

    fun hide(animScale: Float) {
        val l = layer ?: return
        layer = null
        onClosed()
        if (animScale == 0f) {
            root.removeView(l)
            return
        }
        l.animate().alpha(0f).setDuration((200 * animScale).toLong()).setInterpolator(ease)
            .withEndAction { root.removeView(l) }.start()
    }

    private fun styleCards(rv: RecyclerView) {
        val center = rv.width / 2f
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val childCenter = (child.left + child.right) / 2f
            val dist = ((childCenter - center) / rv.width).coerceIn(-1f, 1f)
            val s = 1f - abs(dist) * 0.14f
            child.scaleX = s
            child.scaleY = s
            child.alpha = 1f - abs(dist) * 0.35f
        }
    }

    private inner class CardAdapter(
        private val items: List<Entry>,
        private val cardW: Int,
        private val cardH: Int
    ) : RecyclerView.Adapter<CardAdapter.Holder>() {

        inner class Holder(
            view: View,
            val icon: ImageView,
            val name: TextView,
            val card: FrameLayout,
            val big: ImageView,
            val bg: ImageView,
            val time: TextView
        ) : RecyclerView.ViewHolder(view)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = RecyclerView.LayoutParams(cardW, WRAP_CONTENT)
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, 0, dp(8))
            }
            val icon = ImageView(ctx)
            header.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            val name = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            }
            header.addView(name, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            col.addView(header)

            val card = FrameLayout(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF3A3A3E.toInt(), 0xFF1C1C1F.toInt())
                ).apply {
                    cornerRadius = dp(28).toFloat()
                    setStroke(maxOf(1, dp(1)), 0x40FFFFFF)
                }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat())
                    }
                }
                clipToOutline = true
                elevation = dp(10).toFloat()
            }
            val bg = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.55f
                if (Build.VERSION.SDK_INT >= 31) {
                    setRenderEffect(RenderEffect.createBlurEffect(dp(40).toFloat(), dp(40).toFloat(), Shader.TileMode.CLAMP))
                }
            }
            card.addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val big = ImageView(ctx)
            card.addView(big, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
            val time = TextView(ctx).apply {
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
            }
            card.addView(time, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = dp(22)
            })
            col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, cardH))
            return Holder(col, icon, name, card, big, bg, time)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            holder.icon.setImageBitmap(e.app.icon)
            holder.name.text = e.app.label
            holder.big.setImageBitmap(e.app.icon)
            holder.bg.setImageBitmap(e.app.icon)
            holder.time.text = if (e.lastUsed > 0) {
                DateUtils.getRelativeTimeSpanString(
                    e.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                ).toString()
            } else ""
            holder.card.setOnClickListener { onOpen(e.app, holder.card) }
        }
    }
}
