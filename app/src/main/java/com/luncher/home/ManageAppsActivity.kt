package com.luncher.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.roundToInt

/** User-selected uninstall requests always go through Android's own confirmation. */
class ManageAppsActivity : Activity() {
    private data class App(val packageName: String, val label: String, val info: ResolveInfo, val system: Boolean)
    private var apps: List<App> = emptyList()
    private val selected = linkedSetOf<String>()
    private val queue = ArrayList<String>()
    private var activePackage: String? = null
    private var completed = 0
    private lateinit var adapter: AppsAdapter
    private lateinit var count: TextView
    private lateinit var uninstall: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        queue.addAll(savedInstanceState?.getStringArrayList("queue").orEmpty())
        activePackage = savedInstanceState?.getString("active")
        completed = savedInstanceState?.getInt("completed") ?: 0
        query = savedInstanceState?.getString("query").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), bars.top + dp(8), dp(16), bars.bottom + dp(12))
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = label("‹", 32f, 0xFF70B7FF.toInt()).apply {
            contentDescription = "Back to launcher settings"
            setPadding(0, 0, dp(18), 0)
            setOnClickListener { finish() }
        }
        header.addView(back)
        header.addView(label("Manage apps", 27f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(label("Tap apps to select them. Android asks for confirmation for each uninstall.", 13f, 0xFFAEAEB2.toInt()).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        root.addView(label("Uninstall normally removes local app data. Shared files and cloud backups may remain.", 13f, 0xFFAEAEB2.toInt()).apply {
            setPadding(0, 0, 0, dp(8))
        })
        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine()
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8E8E93.toInt())
            background = rounded(0xFF242426.toInt(), 12f)
            setPadding(dp(12), 0, dp(12), 0)
            setText(query)
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ManageAppsActivity)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
        }
        adapter = AppsAdapter()
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        count = label("0 selected", 14f, 0xFFAEAEB2.toInt()).apply {
            setPadding(0, dp(4), 0, dp(7))
        }
        root.addView(count)
        uninstall = label("Uninstall selected", 17f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(0xFFB52B28.toInt(), 14f)
            contentDescription = "Uninstall selected apps"
            setOnClickListener { confirmUninstall() }
        }
        root.addView(uninstall, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)))
        setContentView(root)
        search.addTextChangedListener { query = it?.toString().orEmpty(); adapter.notifyDataSetChanged() }
        refreshApps()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refreshApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected", ArrayList(selected))
        outState.putStringArrayList("queue", ArrayList(queue))
        outState.putString("active", activePackage)
        outState.putInt("completed", completed)
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "NotifyDataSetChanged")
    private fun refreshApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .mapNotNull { ri ->
                runCatching {
                    val pkg = ri.activityInfo.packageName
                    val flags = ri.activityInfo.applicationInfo.flags
                    App(pkg, ri.loadLabel(packageManager).toString(), ri,
                        flags and ApplicationInfo.FLAG_SYSTEM != 0)
                }.getOrNull()
            }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        // Retain selections if a package temporarily disappears while its Android dialog is open.
        if (activePackage == null) selected.retainAll(apps.map { it.packageName }.toSet())
        adapter.notifyDataSetChanged()
        updateSelection()
    }

    private fun filtered(): List<App> {
        val value = query.trim()
        return if (value.isEmpty()) apps else apps.filter {
            it.label.contains(value, ignoreCase = true) || it.packageName.contains(value, ignoreCase = true)
        }
    }

    private fun updateSelection() {
        val busy = activePackage != null
        count.text = "${selected.size} selected"
        uninstall.isEnabled = selected.isNotEmpty() && !busy
        uninstall.alpha = if (uninstall.isEnabled) 1f else 0.4f
    }

    private fun confirmUninstall() {
        if (selected.isEmpty() || activePackage != null) return
        val chosen = apps.filter { it.packageName in selected }
        if (chosen.isEmpty()) return
        val names = chosen.joinToString("\n") { "• ${it.label}" }
        AlertDialog.Builder(this)
            .setTitle("Uninstall ${chosen.size} app${if (chosen.size == 1) "" else "s"}?")
            .setMessage("$names\n\nAndroid will ask you to confirm each app. Leave any 'keep app data' option unchecked to remove local app data. Shared files and cloud backups may remain. Some system apps cannot be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                queue.clear()
                queue.addAll(chosen.map { it.packageName })
                completed = 0
                requestNextUninstall()
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun requestNextUninstall() {
        if (queue.isEmpty()) {
            activePackage = null
            refreshApps()
            Toast.makeText(this, "Finished $completed uninstall request${if (completed == 1) "" else "s"}", Toast.LENGTH_LONG).show()
            return
        }
        val pkg = queue.removeAt(0)
        activePackage = pkg
        updateSelection()
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            startActivityForResult(intent, REQ_UNINSTALL)
        } catch (_: Exception) {
            activePackage = null
            queue.clear()
            refreshApps()
            Toast.makeText(this, "Android couldn't open the uninstall screen. No more apps will be removed.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android uninstall result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UNINSTALL) return
        val pkg = activePackage
        activePackage = null
        if (resultCode == RESULT_OK && pkg != null) {
            selected.remove(pkg)
            completed++
            requestNextUninstall()
        } else {
            queue.clear()
            refreshApps()
            Toast.makeText(this, "Stopped. The remaining apps are still selected.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openStorage(app: App) {
        try {
            startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null)))
            Toast.makeText(this, "Open Storage & cache, then Clear storage if you want to reset this app.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Android couldn't open this app's settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val icon: ImageView, val name: TextView,
                           val details: TextView, val check: CheckBox, val storage: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@ManageAppsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(4), dp(8))
                minimumHeight = dp(68)
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(this@ManageAppsActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            val text = LinearLayout(this@ManageAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
            }
            val name = label("", 16f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val details = label("", 12f, 0xFF8E8E93.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            text.addView(name); text.addView(details)
            row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val storage = label("Info", 14f, 0xFF70B7FF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
            }
            row.addView(storage)
            val check = CheckBox(this@ManageAppsActivity).apply { isClickable = false; isFocusable = false }
            row.addView(check)
            return Holder(row, icon, name, details, check, storage)
        }

        override fun getItemCount() = filtered().size

        @Suppress("NotifyDataSetChanged")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = filtered()[position]
            holder.icon.setImageDrawable(runCatching { app.info.loadIcon(packageManager) }.getOrNull())
            holder.name.text = app.label
            holder.details.text = if (app.system) "System app · ${app.packageName}" else app.packageName
            holder.check.isChecked = app.packageName in selected
            holder.row.contentDescription = "${app.label}, ${app.packageName}, ${if (holder.check.isChecked) "selected" else "not selected"}"
            holder.row.setOnClickListener {
                if (activePackage != null) return@setOnClickListener
                if (!selected.add(app.packageName)) selected.remove(app.packageName)
                notifyItemChanged(holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position)
                updateSelection()
            }
            holder.storage.contentDescription = "App info for ${app.label}"
            holder.storage.setOnClickListener { openStorage(app) }
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object { private const val REQ_UNINSTALL = 7301 }
}
