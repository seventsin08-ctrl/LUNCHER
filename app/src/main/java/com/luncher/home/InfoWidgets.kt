package com.luncher.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Live info for the Dynamic Island and widgets: weather (Open-Meteo, no account needed),
 * today's steps and calories, today's date and how many events you have.
 */
object InfoData {
    class Weather(val temp: Int, val feels: Int, val hi: Int, val lo: Int, val code: Int,
                  val days: List<Triple<String, Int, Int>>, val time: Long)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(fn: () -> Unit) { listeners += fn }
    fun unlisten(fn: () -> Unit) { listeners -= fn }
    private fun changed() = main.post { for (l in listeners.toList()) runCatching { l() } }

    // ---------------------------------------------------------------- weather
    var weather: Weather? = null
        private set
    private var fetching = false

    fun loadWeather(ctx: Context): Weather? {
        if (weather != null) return weather
        val s = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE).getString("info_weather", null) ?: return null
        weather = runCatching { parse(org.json.JSONObject(s)) }.getOrNull()
        return weather
    }

    /** Refresh at most every 20 minutes. Uses your rough location (or the last one we knew). */
    fun refreshWeather(ctx: Context, force: Boolean = false) {
        val w = loadWeather(ctx)
        if (fetching || (!force && w != null && System.currentTimeMillis() - w.time < 20 * 60_000L)) return
        val prefs = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        var lat = prefs.getFloat("info_lat", Float.NaN).toDouble(); var lon = prefs.getFloat("info_lon", Float.NaN).toDouble()
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val lm = ctx.getSystemService(android.location.LocationManager::class.java)
                @Suppress("MissingPermission")
                val loc = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
                if (loc != null) {
                    lat = loc.latitude; lon = loc.longitude
                    prefs.edit().putFloat("info_lat", lat.toFloat()).putFloat("info_lon", lon.toFloat()).apply()
                }
            }
        }
        if (lat.isNaN() || lon.isNaN()) return
        fetching = true
        Thread {
            runCatching {
                val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=3&timezone=auto")
                val c = url.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val j = org.json.JSONObject(body)
                val cur = j.getJSONObject("current"); val d = j.getJSONObject("daily")
                val o = org.json.JSONObject()
                    .put("t", cur.getDouble("temperature_2m")).put("f", cur.getDouble("apparent_temperature"))
                    .put("c", cur.getInt("weather_code"))
                    .put("hi", d.getJSONArray("temperature_2m_max")).put("lo", d.getJSONArray("temperature_2m_min"))
                    .put("dc", d.getJSONArray("weather_code")).put("days", d.getJSONArray("time"))
                    .put("time", System.currentTimeMillis())
                prefs.edit().putString("info_weather", o.toString()).apply()
                weather = parse(o)
                changed()
            }
            fetching = false
        }.start()
    }

    private fun parse(o: org.json.JSONObject): Weather {
        val hi = o.getJSONArray("hi"); val lo = o.getJSONArray("lo"); val dc = o.getJSONArray("dc"); val ds = o.getJSONArray("days")
        val days = (1 until ds.length()).map { i ->
            val name = runCatching {
                val dt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ds.getString(i))!!
                java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(dt).uppercase()
            }.getOrDefault("")
            Triple(name, dc.getInt(i), hi.getDouble(i).roundToInt())
        }
        return Weather(o.getDouble("t").roundToInt(), o.getDouble("f").roundToInt(), hi.getDouble(0).roundToInt(),
            lo.getDouble(0).roundToInt(), o.getInt("c"), days, o.getLong("time"))
    }

    /** WMO weather code → (emoji, label). */
    fun condition(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "CLEAR"
        1, 2 -> "🌤️" to "PARTLY CLOUDY"
        3 -> "☁️" to "CLOUDY"
        45, 48 -> "🌫️" to "FOG"
        in 51..57 -> "🌦️" to "DRIZZLE"
        in 61..67, in 80..82 -> "🌧️" to "RAIN"
        in 71..77, 85, 86 -> "🌨️" to "SNOW"
        in 95..99 -> "⛈️" to "STORM"
        else -> "🌡️" to "WEATHER"
    }

    // ---------------------------------------------------------------- steps
    private var stepListener: SensorEventListener? = null
    var stepsToday = -1
        private set

    /** Count steps with the phone's step sensor (needs "Physical activity" permission). */
    fun startSteps(ctx: Context) {
        if (stepListener != null) return
        if (Build.VERSION.SDK_INT >= 29 &&
            ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val prefs = ctx.applicationContext.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val total = e.values[0].toInt()
                val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                var base = prefs.getInt("steps_base", -1)
                if (prefs.getInt("steps_day", -1) != day || base < 0 || total < base) {
                    // New day (or the phone restarted): remember yesterday, start counting again.
                    val prev = prefs.getInt("steps_today", 0)
                    val hist = prefs.getString("steps_hist", "") ?: ""
                    if (prefs.getInt("steps_day", -1) >= 0) prefs.edit().putString("steps_hist", (hist.split(",").filter { it.isNotBlank() } + prev.toString()).takeLast(6).joinToString(",")).apply()
                    base = total - (if (total < base) 0 else 0)
                    prefs.edit().putInt("steps_base", base).putInt("steps_day", day).apply()
                }
                stepsToday = (total - base).coerceAtLeast(0)
                prefs.edit().putInt("steps_today", stepsToday).apply()
                changed()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)) stepListener = l
        if (stepsToday < 0) stepsToday = prefs.getInt("steps_today", -1)
    }

    /** Last 6 days + today, for the little bar chart. */
    fun stepHistory(ctx: Context): List<Int> {
        val p = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        return (p.getString("steps_hist", "") ?: "").split(",").mapNotNull { it.toIntOrNull() } + maxOf(0, stepsToday)
    }

    fun calories(): Double = maxOf(0, stepsToday) * 0.04

    // ---------------------------------------------------------------- calendar
    fun eventsToday(ctx: Context): Int {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return -1
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val end = start + 24 * 3600_000L
        return runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
            }.build()
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID), null, null, null)?.use { it.count } ?: 0
        }.getOrDefault(-1)
    }
}

/** Island pills and big views for the info above. */
object InfoViews {
    private fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()

    /** Emoji drawn into a small bitmap (used as the island icon). */
    fun emoji(ctx: Context, e: String, bg: Int = 0): Bitmap {
        val s = dp(ctx, 44f)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (bg != 0) c.drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s * 0.62f; textAlign = Paint.Align.CENTER }
        c.drawText(e, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p)
        return b
    }

    private fun label(ctx: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Expanded island weather: condition, feels-like, big temperature and a gauge from low to high. */
    fun weatherGauge(act: Activity, w: InfoData.Weather?): View {
        val f = FrameLayout(act)
        if (w == null) {
            f.addView(label(act, "Weather needs your rough location — allow it in Settings › Permissions & Setup", 14f, 0xB3FFFFFF.toInt()),
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return f
        }
        val (_, cond) = InfoData.condition(w.code)
        f.addView(GaugeView(act, w), FrameLayout.LayoutParams(MATCH_PARENT, dp(act, 132f)))
        f.addView(label(act, cond, 12f, Color.WHITE, true), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        f.addView(TextView(act).apply {
            text = android.text.SpannableStringBuilder("FEELS LIKE  ").apply {
                val s = length; append("${w.feels}")
                setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), s, length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
            }
            textSize = 12f; setTextColor(0x99FFFFFF.toInt())
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        f.addView(label(act, "L ${w.lo}", 13f, 0xFF64D2FF.toInt(), true), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        f.addView(label(act, "H ${w.hi}", 13f, 0xFFFF9F68.toInt(), true), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        return f
    }

    /** Arc of fine ticks, blue (low) to orange (high); the current temperature is the bright tick. */
    private class GaugeView(ctx: Context, val w: InfoData.Weather) : View(ctx) {
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height * 0.98f
            val r = minOf(width * 0.42f, height * 0.78f)
            val n = 46
            val span = (w.hi - w.lo).coerceAtLeast(1)
            val pos = ((w.temp - w.lo).toFloat() / span).coerceIn(0f, 1f)
            for (i in 0..n) {
                val t = i / n.toFloat()
                val a = Math.toRadians((180.0 + 180.0 * t))
                val col = blend(0xFF3A7BD5.toInt(), 0xFFFF8A4C.toInt(), t)
                val near = kotlin.math.abs(t - pos) < 0.5f / n
                tick.color = if (near) Color.WHITE else col
                tick.alpha = if (near) 255 else 200
                tick.strokeWidth = (if (near) 3f else 2f) * d
                val r1 = r * (if (near) 0.62f else 0.72f); val r2 = r
                c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), tick)
            }
            big.textSize = 30f * d
            c.drawText("${w.temp}°", cx, cy - r * 0.62f - 6f * d, big)
        }
        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }

    /** Steps: big green number, calories and the last 7 days as bars. */
    fun stepsCard(act: Activity, compact: Boolean = false): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val steps = InfoData.stepsToday
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(act, if (steps >= 0) String.format(Locale.getDefault(), "%,d", steps) else "—", if (compact) 22f else 28f, 0xFF30D158.toInt(), true))
        texts.addView(label(act, if (steps >= 0) "STEPS · ${String.format(Locale.getDefault(), "%.1f", InfoData.calories())} kcal" else "Allow Physical activity", 11f, 0x99FFFFFF.toInt(), true))
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(BarsChart(act, InfoData.stepHistory(act)), LinearLayout.LayoutParams(dp(act, 110f), dp(act, 44f)))
        col.addView(top)
        return col
    }

    private class BarsChart(ctx: Context, val values: List<Int>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            // Always 7 slim bars (missing days as stubs), so one value never becomes a big blob.
            val v = (List(7) { 0 } + values).takeLast(7)
            val max = (v.maxOrNull() ?: 1).coerceAtLeast(1)
            val bw = minOf(width / (7 * 1.6f), resources.displayMetrics.density * 8f)
            val step = (width - bw) / 6f
            for ((i, x) in v.withIndex()) {
                val h = (height * (x.toFloat() / max)).coerceAtLeast(height * 0.08f)
                val left = width - bw - (6 - i) * step
                p.color = if (i == v.lastIndex) 0xFF30D158.toInt() else 0x6630D158
                c.drawRoundRect(RectF(left, height - h, left + bw, height.toFloat()), bw / 2, bw / 2, p)
            }
        }
    }

    /** Today's weather card (big) with the next two days, like the widget in the picture. */
    fun todayWeather(act: Activity): View {
        val w = InfoData.weather
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 14f), dp(act, 12f), dp(act, 14f), dp(act, 12f)) }
        col.addView(label(act, "TODAY", 11f, 0x99FFFFFF.toInt(), true))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val main = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 12f), dp(act, 10f), dp(act, 10f), dp(act, 10f))
            background = GradientDrawable().apply { cornerRadius = dp(act, 16f).toFloat(); setColor(0x26FFFFFF) }
        }
        val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        if (w == null) t.addView(label(act, "—", 22f, Color.WHITE, true)) else {
            t.addView(label(act, "${w.temp}°", 24f, Color.WHITE, true))
            t.addView(label(act, InfoData.condition(w.code).second, 10f, 0xB3FFFFFF.toInt(), true))
            t.addView(TextView(act).apply {
                text = android.text.SpannableStringBuilder("L: ${w.lo}  H: ${w.hi}").apply {
                    setSpan(android.text.style.ForegroundColorSpan(0xFF64D2FF.toInt()), 0, "L: ${w.lo}".length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(0xFFFF9F68.toInt()), "L: ${w.lo}  ".length, length, 0)
                }
                textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            })
        }
        main.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        main.addView(label(act, w?.let { InfoData.condition(it.code).first } ?: "🌡️", 30f, Color.WHITE))
        row.addView(main, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        for ((name, code, hi) in w?.days?.take(2).orEmpty()) {
            val d = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(act, 8f), dp(act, 8f), dp(act, 8f), dp(act, 8f))
                background = GradientDrawable().apply { cornerRadius = dp(act, 16f).toFloat(); setColor(0x1AFFFFFF) }
            }
            d.addView(label(act, name, 10f, 0x99FFFFFF.toInt(), true))
            d.addView(label(act, InfoData.condition(code).first, 18f, Color.WHITE))
            d.addView(label(act, "$hi°", 11f, Color.WHITE, true))
            row.addView(d, LinearLayout.LayoutParams(dp(act, 52f), MATCH_PARENT).apply { marginStart = dp(act, 6f) })
        }
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(act, 6f) })
        return col
    }

    /** Month calendar with today circled red and today's event count. */
    fun monthCard(act: Activity, events: Int): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val month = java.text.SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time)
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label(act, month, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (events >= 0) head.addView(label(act, if (events == 1) "1 event today" else "$events events today", 12f, 0xFFFF453A.toInt(), true))
        col.addView(head)
        val c2 = cal.clone() as Calendar; c2.set(Calendar.DAY_OF_MONTH, 1)
        val lead = (c2.get(Calendar.DAY_OF_WEEK) - c2.firstDayOfWeek + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val names = listOf("S", "M", "T", "W", "T", "F", "S")
        val grid = android.widget.GridLayout(act).apply { columnCount = 7 }
        for (i in 0 until 7) grid.addView(label(act, names[(i + c2.firstDayOfWeek - 1) % 7], 10f, 0x80FFFFFF.toInt(), true).apply { gravity = Gravity.CENTER },
            android.widget.GridLayout.LayoutParams().apply { width = 0; columnSpec = android.widget.GridLayout.spec(i % 7, 1f) })
        for (i in 0 until lead + days) {
            val d = i - lead + 1
            val tv = label(act, if (d >= 1) "$d" else "", 11f, Color.WHITE, d == today).apply {
                gravity = Gravity.CENTER
                if (d == today) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }
            }
            grid.addView(tv, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(act, 20f); columnSpec = android.widget.GridLayout.spec((i % 7), 1f)
            })
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(act, 6f) })
        return col
    }
}
