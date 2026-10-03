package com.shebeixinxi.deviceinfo

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.shebeixinxi.deviceinfo.databinding.ActivityMainBinding
import com.shebeixinxi.deviceinfo.databinding.DialogTextBinding
import com.shebeixinxi.deviceinfo.databinding.ItemFeatureCellBinding
import com.shebeixinxi.deviceinfo.databinding.ItemHeroBinding
import com.shebeixinxi.deviceinfo.databinding.ItemRowActionBinding
import com.shebeixinxi.deviceinfo.databinding.ItemRowInlineBinding
import com.shebeixinxi.deviceinfo.databinding.ItemRowStackedBinding
import com.shebeixinxi.deviceinfo.databinding.ItemSectionBinding
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * 设备信息 - 基础版本
 *
 * 以「分组 + 键值对」的形式展示当前设备的主要软硬件信息。
 * 所有取值都做了容错处理：单个条目读取失败只会被跳过，不会影响整体展示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前选中的一级菜单；null 表示「概览」 */
    private var selectedKey: String? = null

    private val liveHandler = Handler(Looper.getMainLooper())
    private var pendingExportJson: String? = null
    private var pendingExportText: String? = null

    private val exportJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingExportJson
        pendingExportJson = null
        if (uri == null || content == null) return@registerForActivityResult

        val saved = runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开文件")
        }.isSuccess
        toast(getString(if (saved) R.string.export_saved else R.string.export_failed))
    }

    private val exportTextLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val content = pendingExportText
        pendingExportText = null
        if (uri == null || content == null) return@registerForActivityResult

        val saved = runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开文件")
        }.isSuccess
        toast(getString(if (saved) R.string.export_text_saved else R.string.export_text_failed))
    }

    private val importSnapshotLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val imported = runCatching {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取文件")
            if (JSONObject(content).optJSONArray("sections") == null) error("不是设备报告")
            content
        }.getOrNull()
        if (imported == null) {
            toast(getString(R.string.snapshot_invalid))
        } else {
            getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE).edit()
                .putString(SNAPSHOT_JSON, imported)
                .putLong(SNAPSHOT_TIME, System.currentTimeMillis())
                .apply()
            toast(getString(R.string.snapshot_imported))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        selectedKey = savedInstanceState?.getString(STATE_SELECTION)

        // NavigationView 在构造时会把 background 换成自己的 drawable，XML 设置无效，这里再覆盖一次
        binding.navigationView.setBackgroundColor(
            ContextCompat.getColor(this, R.color.card_background)
        )

        setupDrawer()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTION, selectedKey)
    }

    override fun onDestroy() {
        liveHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            render()
            toast(getString(R.string.action_refresh))
            true
        }

        R.id.action_more -> {
            showMoreDialog()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------
    // 分类切换：左侧抽屉与顶部 Tab 双向同步
    // ------------------------------------------------------------------

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navigationView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawers()
            selectCategory(MENU_KEYS[item.itemId])
            true
        }

        syncDrawer()
    }

    /** 统一的分类切换入口 */
    private fun selectCategory(key: String?) {
        selectedKey = key
        syncDrawer()
        render()
    }

    private fun syncDrawer() {
        val checkedId = MENU_KEYS.entries.firstOrNull { it.value == selectedKey }?.key ?: R.id.nav_all
        binding.navigationView.setCheckedItem(checkedId)
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 一级菜单的一个分类 */
    private data class Section(
        val key: String,
        val titleRes: Int,
        val iconRes: Int,
        val provider: () -> List<Row>,
    )

    /** 卡片内的一行内容，支持三种形态 */
    private sealed class Row {
        /** 普通「标签 - 值」行 */
        data class Item(val label: String, val value: String) : Row()

        /** 可点击行，右侧带「点击查看」 */
        data class Action(
            val label: String,
            val actionText: String,
            val onClick: () -> Unit,
        ) : Row()

        /** 两列能力网格，第二项表示是否支持 */
        data class Grid(val items: List<Pair<String, Boolean>>) : Row()

        /** 数值占用进度，例如内存和存储。 */
        data class Progress(val label: String, val percent: Int, val value: String) : Row()
    }

    /** 设备体检结果。INFO 是能力展示，不参与健康评分。 */
    private enum class HealthState { GOOD, NOTICE, INFO }

    private data class HealthItem(
        val label: String,
        val state: HealthState,
        val detail: String,
    )

    private fun allSections(): List<Section> = listOf(
        Section("device", R.string.nav_device, R.drawable.ic_nav_device, ::deviceInfo),
        Section("features", R.string.nav_features, R.drawable.ic_nav_features, ::featureInfo),
        Section("system", R.string.nav_system, R.drawable.ic_nav_system, ::systemInfo),
        Section("cpu", R.string.nav_cpu, R.drawable.ic_nav_cpu, ::cpuInfo),
        Section("memory", R.string.nav_memory, R.drawable.ic_nav_memory, ::memoryInfo),
        Section("display", R.string.nav_display, R.drawable.ic_nav_display, ::displayInfo),
        Section("battery", R.string.nav_battery, R.drawable.ic_nav_battery, ::batteryInfo),
        Section("network", R.string.nav_network, R.drawable.ic_nav_network, ::networkInfo),
        Section("media", R.string.nav_media, R.drawable.ic_nav_media, ::mediaInfo),
        Section("motion", R.string.nav_motion, R.drawable.ic_nav_motion, ::motionInfo),
        Section("sensor", R.string.nav_sensor, R.drawable.ic_nav_sensor, ::sensorInfo),
        Section("camera", R.string.nav_camera, R.drawable.ic_nav_camera, ::cameraInfo),
        Section("app", R.string.nav_app, R.drawable.ic_nav_app, ::appInfo),
    )

    private fun render() {
        val container = binding.container
        container.removeAllViews()

        val sections = allSections()
        val current = selectedKey
        val shown = if (current == null) sections else sections.filter { it.key == current }

        supportActionBar?.title = shown.firstOrNull()?.let { getString(it.titleRes) }
            ?: getString(R.string.app_name)

        // 顶部设备概览卡片
        val hero = ItemHeroBinding.inflate(layoutInflater, container, false)
        hero.heroTitle.text = deviceTitle()
        hero.heroSubtitle.text =
            getString(R.string.hero_subtitle, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        container.addView(hero.root)

        if (shown.isEmpty()) {
            container.addView(hintView(getString(R.string.empty_section)))
            return
        }
        shown.forEach { container.addView(buildSectionCard(it)) }
    }

    /** 「厂商 型号」，型号已含厂商时不重复拼接 */
    private fun deviceTitle(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return when {
            model.isBlank() -> maker.ifBlank { getString(R.string.app_name) }
            maker.isBlank() -> model
            model.lowercase(Locale.US).startsWith(maker.lowercase(Locale.US)) -> model
            else -> "$maker $model"
        }
    }

    private fun buildSectionCard(section: Section): View {
        val card = ItemSectionBinding.inflate(layoutInflater, binding.container, false)
        card.sectionTitle.setText(section.titleRes)
        card.sectionIcon.setImageResource(section.iconRes)

        val rows = runCatching { section.provider() }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            card.rowsContainer.addView(hintView(getString(R.string.empty_section)))
            return card.root
        }

        rows.forEachIndexed { index, row ->
            // 网格前后不加分隔线，避免割裂感
            val prev = rows.getOrNull(index - 1)
            if (index > 0 && row !is Row.Grid && prev !is Row.Grid) {
                card.rowsContainer.addView(rowDivider())
            }
            card.rowsContainer.addView(buildRowView(row))
        }
        return card.root
    }

    private fun buildRowView(row: Row): View = when (row) {
        is Row.Item -> buildTextRow(row.label, row.value)

        is Row.Action -> ItemRowActionBinding
            .inflate(layoutInflater, binding.container, false)
            .apply {
                rowLabel.text = row.label
                rowAction.text = row.actionText
                root.setOnClickListener { row.onClick() }
            }.root

        is Row.Grid -> buildFeatureGrid(row.items)

        is Row.Progress -> buildProgressRow(row)
    }

    private fun buildProgressRow(row: Row.Progress): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val summary = TextView(this).apply {
            text = "${row.label}  ${row.percent}%"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
        }
        val detail = TextView(this).apply {
            text = row.value
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = row.percent.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
            )
            progressBackgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.icon_chip_bg)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply { topMargin = dp(6) }
        }
        column.addView(summary)
        column.addView(detail)
        column.addView(progress)
        return column
    }

    /** 短内容左右排布；长内容（指纹、传感器列表等）改为上下排布，避免右侧挤压换行 */
    private fun buildTextRow(label: String, value: String): View {
        val text = value.ifBlank { "—" }
        val row = if (text.length <= INLINE_VALUE_MAX_LENGTH) {
            ItemRowInlineBinding.inflate(layoutInflater, binding.container, false).apply {
                rowLabel.text = label
                rowValue.text = text
            }.root
        } else {
            ItemRowStackedBinding.inflate(layoutInflater, binding.container, false).apply {
                rowLabel.text = label
                rowValue.text = text
            }.root
        }

        // 指纹、传感器列表、IP 列表等长内容直接复制，省掉手动拖选的痛苦。
        if (text.length > INLINE_VALUE_MAX_LENGTH || text.contains('\n')) {
            row.setOnClickListener { copyToClipboard(label, "$label：$text") }
            row.setOnLongClickListener {
                copyToClipboard(label, "$label：$text")
                true
            }
        }
        return row
    }

    /** 两列能力网格：支持显示绿色对勾，不支持显示红色叉号 */
    private fun buildFeatureGrid(items: List<Pair<String, Boolean>>): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        items.chunked(FEATURE_GRID_COLUMNS).forEach { lineItems ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            lineItems.forEach { (label, supported) ->
                val cell = ItemFeatureCellBinding.inflate(layoutInflater, line, false)
                cell.cellLabel.text = label
                cell.cellIcon.setImageResource(
                    if (supported) R.drawable.ic_check else R.drawable.ic_cross
                )
                cell.cellIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (supported) R.color.state_supported else R.color.state_unsupported
                    )
                )
                if (!supported) {
                    cell.cellLabel.setTextColor(
                        ContextCompat.getColor(this, R.color.text_secondary)
                    )
                }
                line.addView(cell.root)
            }

            // 末行不足两列时补占位，保证列对齐
            repeat(FEATURE_GRID_COLUMNS - lineItems.size) {
                line.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            column.addView(line)
        }
        return column
    }

    private fun rowDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
    }

    private fun hintView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // 各分组数据
    // ------------------------------------------------------------------

    private fun deviceInfo(): List<Row> = info(
        "设备名称" to deviceName(),
        "设备备注" to deviceLabel(),
        "产品型号" to Build.MODEL,
        "产品系列" to Build.PRODUCT,
        "制造商" to Build.MANUFACTURER,
        "品牌" to Build.BRAND,
        "设备代号" to Build.DEVICE,
        "主板" to Build.BOARD,
        "硬件版本号" to Build.HARDWARE,
        "内部版本号" to Build.DISPLAY,
        "引导程序" to Build.BOOTLOADER,
        "设备类型" to deviceType(),
        "设备能力定级" to performanceClass(),
        "5G New Radio (NR)" to yesNo(hasFeature(FEATURE_TELEPHONY_NR)),
        "面容认证" to yesNo(hasFeature(FEATURE_FACE)),
        "指纹认证" to yesNo(hasFeature(PackageManager.FEATURE_FINGERPRINT)),
        "是否为模拟器" to yesNo(isEmulator()),
        "是否已 Root" to yesNo(isRooted()),
        "设备指纹" to Build.FINGERPRINT,
    )

    private fun deviceLabel(): String? = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
        .getString(DEVICE_LABEL, null)

    private fun showDeviceLabelDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.device_label_hint)
            setSingleLine(true)
            setText(deviceLabel().orEmpty())
            setSelection(length())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_label)
            .setView(input)
            .setNegativeButton(R.string.ok, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString().trim()
                getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE).edit().apply {
                    if (value.isBlank()) remove(DEVICE_LABEL) else putString(DEVICE_LABEL, value)
                }.apply()
                render()
                toast(getString(R.string.device_label_saved))
            }
            .show()
    }

    /** 设备能力网格：每项对应一个系统特性开关 */
    private fun featureInfo(): List<Row> = listOf(
        Row.Grid(
            listOf(
                "蓝牙" to hasFeature(PackageManager.FEATURE_BLUETOOTH),
                "蓝牙 LE" to hasFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                "WLAN" to hasFeature(PackageManager.FEATURE_WIFI),
                "Wi-Fi Direct" to hasFeature(PackageManager.FEATURE_WIFI_DIRECT),
                "NFC" to hasFeature(PackageManager.FEATURE_NFC),
                "NFC 卡模拟" to hasFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
                "以太网" to hasFeature(PackageManager.FEATURE_ETHERNET),
                "蜂窝通信" to hasFeature(PackageManager.FEATURE_TELEPHONY),
                "eSIM" to hasFeature(FEATURE_TELEPHONY_EUICC),
                "5G NR" to hasFeature(FEATURE_TELEPHONY_NR),
                "卫星定位" to hasFeature(PackageManager.FEATURE_LOCATION_GPS),
                "USB 主机" to hasFeature(PackageManager.FEATURE_USB_HOST),
                "闪光灯" to hasFeature(PackageManager.FEATURE_CAMERA_FLASH),
                "红外发射器" to hasFeature(PackageManager.FEATURE_CONSUMER_IR),
                "人脸识别" to hasFeature(FEATURE_FACE),
                "指纹识别" to hasFeature(PackageManager.FEATURE_FINGERPRINT),
                "触摸屏" to hasFeature(PackageManager.FEATURE_TOUCHSCREEN),
                "加速度计" to hasFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER),
                "陀螺仪" to hasFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE),
                "电子罗盘" to hasFeature(PackageManager.FEATURE_SENSOR_COMPASS),
                "气压计" to hasFeature(PackageManager.FEATURE_SENSOR_BAROMETER),
                "计步器" to hasFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER),
            )
        )
    ) + Row.Action(getString(R.string.usb_details), getString(R.string.action_view)) {
        showUsbDetailsDialog()
    } + Row.Action(getString(R.string.feature_details), getString(R.string.action_view)) {
        showFeatureDetailsDialog()
    } + Row.Action(getString(R.string.gpu_details), getString(R.string.action_view)) {
        showGpuDetailsDialog()
    }

    private fun showUsbDetailsDialog() {
        val usb = getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = usb?.deviceList?.values?.toList().orEmpty()
        val body = if (devices.isEmpty()) {
            getString(R.string.usb_details_unavailable)
        } else {
            devices.joinToString("\n\n") { device ->
                buildString {
                    appendLine(device.deviceName)
                    appendLine("厂商 ID：${device.vendorId} · 产品 ID：${device.productId}")
                    appendLine("设备类别：${device.deviceClass} · 子类：${device.deviceSubclass}")
                    appendLine("协议：${device.deviceProtocol} · 接口数：${device.interfaceCount}")
                    append("本应用已获授权：${yesNo(usb?.hasPermission(device) == true)}")
                }
            }
        }
        showTextDialog(getString(R.string.usb_details), body)
    }

    private fun showFeatureDetailsDialog() {
        val features = runCatching { packageManager.systemAvailableFeatures.toList() }
            .getOrDefault(emptyList())
        val names = features.mapNotNull { feature ->
            feature.name ?: feature.glEsVersion?.let { "OpenGL ES $it" }
        }.distinct().sorted()
        val body = if (names.isEmpty()) {
            getString(R.string.feature_details_unavailable)
        } else {
            "共 ${names.size} 项系统特性：\n\n${names.joinToString("\n") { "· $it" }}"
        }
        showTextDialog(getString(R.string.feature_details), body)
    }

    private fun showGpuDetailsDialog() {
        val body = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, version, 0, version, 1))
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val configCount = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0))
            val context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val maxTexture = IntArray(1)
            val maxViewport = IntArray(2)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0)
            val result = buildString {
                appendLine("厂商：${GLES20.glGetString(GLES20.GL_VENDOR) ?: "未知"}")
                appendLine("渲染器：${GLES20.glGetString(GLES20.GL_RENDERER) ?: "未知"}")
                appendLine("OpenGL ES：${GLES20.glGetString(GLES20.GL_VERSION) ?: "未知"}")
                appendLine("最大纹理尺寸：${maxTexture[0]} px")
                append("最大视口尺寸：${maxViewport[0]} × ${maxViewport[1]} px")
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            result
        }.getOrElse { getString(R.string.gpu_details_unavailable) }
        showTextDialog(getString(R.string.gpu_details), body)
    }

    /** 多媒体能力，逐项弹窗查看明细 */
    private fun mediaInfo(): List<Row> = listOf(
        Row.Action("视频编解码器", getString(R.string.action_view)) {
            showListDialog(R.string.media_video_codec, videoCodecs(), inferred = true)
        },
        Row.Action("音频编解码器", getString(R.string.action_view)) {
            showListDialog(R.string.media_audio_codec, audioCodecs(), inferred = true)
        },
        Row.Action("封装格式", getString(R.string.action_view)) {
            showListDialog(R.string.media_container, containerFormats(), inferred = true)
        },
        Row.Action("DRM 支持", getString(R.string.action_view)) {
            showListDialog(R.string.media_drm, drmSchemes(), inferred = false)
        },
        Row.Action(getString(R.string.audio_details), getString(R.string.action_view)) {
            showAudioDetailsDialog()
        },
    )

    private fun showAudioDetailsDialog() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            showTextDialog(getString(R.string.audio_details), getString(R.string.empty_section))
            return
        }
        val devices = runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        val inputDevices = runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())
        val mode = when (audio.mode) {
            AudioManager.MODE_NORMAL -> "正常"
            AudioManager.MODE_RINGTONE -> "响铃"
            AudioManager.MODE_IN_CALL -> "通话"
            AudioManager.MODE_IN_COMMUNICATION -> "通信"
            else -> "其他"
        }
        val sampleRate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "未知"
        val buffer = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "未知"
        val body = buildString {
            appendLine("音频模式：$mode")
            appendLine("媒体音量：${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            appendLine("正在播放：${yesNo(audio.isMusicActive)}")
            appendLine("输出采样率：$sampleRate Hz")
            appendLine("输出缓冲区：$buffer 帧")
            appendLine()
            appendLine("已识别的输出设备：")
            if (devices.isEmpty()) {
                append("无")
            } else {
                devices.forEach { device ->
                    appendLine("· ${audioDeviceTypeText(device.type)}：${device.productName.ifBlank { "未命名" }}")
                }
            }
            appendLine()
            appendLine("已识别的输入设备：")
            if (inputDevices.isEmpty()) {
                append("无")
            } else {
                inputDevices.forEach { device ->
                    appendLine("· ${audioDeviceTypeText(device.type)}：${device.productName.ifBlank { "未命名" }}")
                }
            }
        }
        showTextDialog(getString(R.string.audio_details), body)
    }

    private fun audioDeviceTypeText(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "其他设备"
    }

    /**
     * 动作感知。
     * 「触控操作手」和「设备握持手」属于用户使用习惯，Android 没有公开读取接口，
     * 第三方应用无法自行判定，这里如实显示未知。
     */
    private fun motionInfo(): List<Row> = info(
        "触控操作手" to getString(R.string.value_unknown),
        "设备握持手" to getString(R.string.value_unknown),
    ) + Row.Item("说明", getString(R.string.motion_unknown_hint)) +
        Row.Action(getString(R.string.vibration_test), getString(R.string.action_view)) {
            showVibrationTestDialog()
        }

    @Suppress("DEPRECATION")
    private fun showVibrationTestDialog() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator == null || !vibrator.hasVibrator()) {
            showTextDialog(getString(R.string.vibration_test), getString(R.string.vibration_unavailable))
            return
        }
        val options = arrayOf("短振动（250 ms）", "长振动（800 ms）", "节奏振动")
        AlertDialog.Builder(this)
            .setTitle(R.string.vibration_test)
            .setItems(options) { _, which ->
                @Suppress("DEPRECATION")
                when (which) {
                    0 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(250L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else vibrator.vibrate(250L)
                    1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(800L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else vibrator.vibrate(800L)
                    else -> {
                        val pattern = longArrayOf(0L, 120L, 100L, 120L, 100L, 300L)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                        } else vibrator.vibrate(pattern, -1)
                    }
                }
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }

    private fun systemInfo(): List<Row> {
        return info(
            "Android 版本" to Build.VERSION.RELEASE,
            "API 级别" to Build.VERSION.SDK_INT.toString(),
            "版本代号" to Build.VERSION.CODENAME,
            "安全补丁级别" to Build.VERSION.SECURITY_PATCH,
            "增量版本" to Build.VERSION.INCREMENTAL,
            "编译编号" to Build.ID,
            "显示版本" to Build.DISPLAY,
            "编译类型" to Build.TYPE,
            "编译标签" to Build.TAGS,
            "编译主机" to Build.HOST,
            "编译用户" to Build.USER,
            "编译时间" to formatTime(Build.TIME),
            "内核版本" to System.getProperty("os.version"),
            "内核详情" to readTextFile("/proc/version"),
            "系统已运行" to formatDuration(SystemClock.elapsedRealtime()),
            "系统语言" to Locale.getDefault().toString(),
            "时区" to TimeZone.getDefault().id,
            "64 位运行时" to yesNo(Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()),
        ) + listOf(
            Row.Action(getString(R.string.time_details), getString(R.string.action_view)) {
                showTimeDetailsDialog()
            },
            Row.Action(getString(R.string.system_state_details), getString(R.string.action_view)) {
                showSystemStateDetailsDialog()
            },
            Row.Action(getString(R.string.thermal_details), getString(R.string.action_view)) {
                showThermalDetailsDialog()
            },
        )
    }

    private fun showTimeDetailsDialog() {
        val autoTime = readGlobalSetting("auto_time")
        val autoTimeZone = readGlobalSetting("auto_time_zone")
        val zone = TimeZone.getDefault()
        val offsetMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes >= 0) "+" else "-"
        val absoluteMinutes = kotlin.math.abs(offsetMinutes)
        val offset = String.format(Locale.US, "GMT%s%02d:%02d", sign, absoluteMinutes / 60, absoluteMinutes % 60)
        val body = listOf(
            "当前时间：${formatTime(System.currentTimeMillis())}",
            "Unix 时间戳：${System.currentTimeMillis()}",
            "时区 ID：${zone.id}",
            "时区偏移：$offset",
            "自动设置时间：${settingState(autoTime)}",
            "自动设置时区：${settingState(autoTimeZone)}",
            "系统已运行：${formatDuration(SystemClock.elapsedRealtime())}",
            "设备启动时间：${formatTime(System.currentTimeMillis() - SystemClock.elapsedRealtime())}",
        ).joinToString("\n")
        showTextDialog(getString(R.string.time_details), body)
    }

    private fun readGlobalSetting(name: String): Int =
        runCatching { Settings.Global.getInt(contentResolver, name, -1) }.getOrDefault(-1)

    private fun settingState(value: Int): String = when (value) {
        1 -> "已开启"
        0 -> "已关闭"
        else -> "系统未提供"
    }

    private fun showSystemStateDetailsDialog() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val nightModeText = when (nightMode) {
            Configuration.UI_MODE_NIGHT_YES -> "深色模式"
            Configuration.UI_MODE_NIGHT_NO -> "浅色模式"
            else -> "跟随系统/未知"
        }
        val animationNames = listOf(
            "窗口动画" to "window_animation_scale",
            "过渡动画" to "transition_animation_scale",
            "动画时长" to "animator_duration_scale",
        )
        val animationLines = animationNames.map { (label, key) ->
            "$label：${readGlobalFloat(key)} 倍"
        }
        val body = listOf(
            "飞行模式：${settingState(readGlobalSetting("airplane_mode_on"))}",
            "省电模式：${if (power?.isPowerSaveMode == true) "已开启" else "已关闭/不可用"}",
            "主题模式：$nightModeText",
            "开发者选项：${settingState(readGlobalSetting("development_settings_enabled"))}",
            "充电时保持亮屏：${if (readGlobalSetting("stay_on_while_plugged_in") != 0) "已开启" else "已关闭"}",
            *animationLines.toTypedArray(),
        ).joinToString("\n")
        showTextDialog(getString(R.string.system_state_details), body)
    }

    private fun readGlobalFloat(name: String): String =
        runCatching { Settings.Global.getFloat(contentResolver, name, 1f) }
            .getOrDefault(1f)
            .let { String.format(Locale.US, "%.1f", it) }

    private fun showSoundStateDetailsDialog() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val ringerMode = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "普通"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            AudioManager.RINGER_MODE_SILENT -> "静音"
            else -> "未知"
        }
        val interruptionFilter = when (notifications?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "允许所有通知"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先通知"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "完全静音"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
            else -> "未知/系统未提供"
        }
        fun volumeLine(label: String, stream: Int): String {
            val current = runCatching { audio?.getStreamVolume(stream) ?: 0 }.getOrDefault(0)
            val max = runCatching { audio?.getStreamMaxVolume(stream) ?: 0 }.getOrDefault(0)
            return "${label}音量：$current / $max"
        }
        val body = listOf(
            "响铃模式：$ringerMode",
            "勿扰模式：$interruptionFilter",
            "勿扰策略访问：${if (notifications?.isNotificationPolicyAccessGranted == true) "已允许" else "未允许/不可用"}",
            volumeLine("媒体", AudioManager.STREAM_MUSIC),
            volumeLine("铃声", AudioManager.STREAM_RING),
            volumeLine("通知", AudioManager.STREAM_NOTIFICATION),
            volumeLine("闹钟", AudioManager.STREAM_ALARM),
        ).joinToString("\n")
        showTextDialog(getString(R.string.sound_state_details), body)
    }

    private fun showThermalDetailsDialog() {
        val body = runCatching {
            val zones = File("/sys/class/thermal").listFiles().orEmpty()
                .filter { it.name.startsWith("thermal_zone") }
                .sortedBy { it.name }
            val lines = zones.mapNotNull { zone ->
                val raw = readTextFile(File(zone, "temp").path)?.toDoubleOrNull() ?: return@mapNotNull null
                val celsius = if (raw > 200 || raw < -200) raw / 1000.0 else raw
                val type = readTextFile(File(zone, "type").path) ?: zone.name
                "$type：${String.format(Locale.US, "%.1f ℃", celsius)}"
            }
            lines.joinToString("\n").ifBlank { getString(R.string.thermal_unavailable) }
        }.getOrElse { getString(R.string.thermal_unavailable) }
        showTextDialog(getString(R.string.thermal_details), body)
    }

    private fun cpuInfo(): List<Row> {
        return info(
            "支持的 ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            "32 位 ABI" to Build.SUPPORTED_32_BIT_ABIS.joinToString(", ").ifBlank { "无" },
            "64 位 ABI" to Build.SUPPORTED_64_BIT_ABIS.joinToString(", ").ifBlank { "无" },
            "逻辑处理器/线程数" to Runtime.getRuntime().availableProcessors().toString(),
            "CPU 硬件" to readCpuInfo("Hardware"),
            "CPU 型号" to (readCpuInfo("model name") ?: readCpuInfo("Processor")),
            "CPU 最高频率" to readCpuFreq("cpuinfo_max_freq"),
            "CPU 最低频率" to readCpuFreq("cpuinfo_min_freq"),
        ) + listOf(
            Row.Action(getString(R.string.cpu_usage_realtime), getString(R.string.action_view)) {
                showCpuUsageDialog()
            },
            Row.Action(getString(R.string.shizuku_status), getString(R.string.action_view)) {
                showShizukuStatusDialog()
            },
            Row.Action(getString(R.string.cpu_core_details), getString(R.string.action_view)) {
                showCpuCoreDetailsDialog()
            },
        )
    }

    private fun showShizukuStatusDialog() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val message = when {
            granted -> getString(R.string.shizuku_granted)
            running -> getString(R.string.shizuku_requesting)
            else -> getString(R.string.shizuku_not_running)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.shizuku_status)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
        if (running && !granted) {
            builder.setNeutralButton(R.string.shizuku_request) { _, _ ->
                runCatching { Shizuku.requestPermission(1001) }
                    .onFailure { toast(getString(R.string.shizuku_not_running)) }
            }
        }
        builder.show()
    }

    private fun showCpuUsageDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val summary = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 16f
        }
        val totalProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
            )
            progressBackgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.icon_chip_bg)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply { topMargin = dp(8) }
        }
        val coreHint = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        }
        val coresContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            addView(coresContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430),
            )
        }
        root.addView(summary)
        root.addView(totalProgress)
        root.addView(coreHint)
        root.addView(scroll)

        val rowViews = linkedMapOf<String, Pair<TextView, ProgressBar>>()
        var previous: Map<String, CpuTimes>? = null
        lateinit var dialog: AlertDialog

        fun frequencyUsage(snapshot: CpuFrequency): Double? {
            if (snapshot.current <= 0L || snapshot.max <= snapshot.min) return null
            return ((snapshot.current - snapshot.min).toDouble() /
                (snapshot.max - snapshot.min) * 100.0).coerceIn(0.0, 100.0)
        }

        fun usage(current: CpuTimes, before: CpuTimes?): Double? {
            if (before == null) return null
            val totalDelta = current.total - before.total
            val idleDelta = current.idle - before.idle
            return if (totalDelta > 0) {
                ((totalDelta - idleDelta).toDouble() / totalDelta * 100.0).coerceIn(0.0, 100.0)
            } else null
        }

        fun ensureRows(labels: List<String>) {
            if (rowViews.keys.toList() == labels) return
            rowViews.clear()
            coresContainer.removeAllViews()
            labels.forEach { label ->
                val labelView = TextView(this).apply {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 13f
                }
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progressTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.state_supported)
                    )
                    progressBackgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.icon_chip_bg)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(7),
                    ).apply { topMargin = dp(4) }
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(7), 0, dp(7))
                }
                row.addView(labelView)
                row.addView(progress)
                coresContainer.addView(row)
                rowViews[label] = labelView to progress
            }
        }

        val update = object : Runnable {
            override fun run() {
                val current = readAllCpuTimes()
                val total = current["cpu"]
                val cores = current.filterKeys { it != "cpu" }
                    .toSortedMap(compareBy { it.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE })
                if (cores.isEmpty()) {
                    val frequencies = readCpuFrequencySnapshots()
                    if (frequencies.isNotEmpty()) {
                        val labels = frequencies.keys.toList()
                        ensureRows(labels)
                        val values = frequencies.mapValues { frequencyUsage(it.value) }
                        val available = values.values.filterNotNull()
                        val average = available.takeIf { it.isNotEmpty() }?.average()
                        summary.text = "总处理器占用（频率估算）：${average?.let { String.format(Locale.US, "%.1f%%", it) } ?: "暂不可读取"}"
                        totalProgress.progress = (average ?: 0.0).toInt().coerceIn(0, 100)
                        coreHint.text = "逻辑处理器/线程：${frequencies.size} · 数据来源：频率估算 · 每秒刷新"
                        frequencies.forEach { (label, snapshot) ->
                            val value = values[label]
                            val views = rowViews[label] ?: return@forEach
                            views.first.text = "$label：${value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "频率不可读取"}"
                            views.second.progress = (value ?: 0.0).toInt().coerceIn(0, 100)
                        }
                        previous = null
                        if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
                        return
                    }
                    val fallback = readTopCpuUsage()
                    rowViews.clear()
                    coresContainer.removeAllViews()
                    coresContainer.addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.cpu_core_usage_unavailable)
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                        textSize = 13f
                        setPadding(0, dp(8), 0, dp(8))
                    })
                    summary.text = "总处理器占用：${fallback?.let { String.format(Locale.US, "%.1f%%", it) } ?: "暂不可读取"}"
                    totalProgress.progress = (fallback ?: 0.0).toInt().coerceIn(0, 100)
                    coreHint.text = "逻辑处理器/线程：${Runtime.getRuntime().availableProcessors()} · 数据来源：系统 top · 每秒刷新"
                    previous = null
                    if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
                    return
                }
                val totalUsage = total?.let { usage(it, previous?.get("cpu")) }
                val labels = cores.keys.toList()
                ensureRows(labels)
                summary.text = "总处理器占用：${totalUsage?.let { String.format(Locale.US, "%.1f%%", it) } ?: "采样中…"}"
                totalProgress.progress = (totalUsage ?: 0.0).toInt().coerceIn(0, 100)
                coreHint.text = "逻辑处理器/线程：${cores.size} · 数据来源：/proc/stat 真实采样 · 每秒刷新"
                cores.forEach { (label, stat) ->
                    val value = usage(stat, previous?.get(label))
                    val views = rowViews[label] ?: return@forEach
                    views.first.text = "$label：${value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "采样中…"}"
                    views.second.progress = (value ?: 0.0).toInt().coerceIn(0, 100)
                }
                previous = current
                if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.cpu_usage_realtime)
            .setView(root)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard(getString(R.string.cpu_usage_realtime), summary.text.toString())
            }
            .create()
        dialog.setOnDismissListener { liveHandler.removeCallbacks(update) }
        dialog.show()
        liveHandler.post(update)
    }

    private fun showCpuCoreDetailsDialog() {
        val cores = File("/sys/devices/system/cpu").listFiles().orEmpty()
            .filter { it.name.matches(Regex("cpu\\d+")) }
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
        val body = cores.map { core ->
            val path = File(core, "cpufreq")
            val current = readCpuFreqFile(File(path, "scaling_cur_freq"))
            val max = readCpuFreqFile(File(path, "cpuinfo_max_freq"))
            val min = readCpuFreqFile(File(path, "cpuinfo_min_freq"))
            "${core.name}: 当前 ${current ?: "未知"} · 最高 ${max ?: "未知"} · 最低 ${min ?: "未知"}"
        }.joinToString("\n").ifBlank { getString(R.string.cpu_core_details_unavailable) }
        showTextDialog(getString(R.string.cpu_core_details), body)
    }

    private fun memoryInfo(): List<Row> {
        val result = mutableListOf<Row>()

        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            result += info(
                "运行内存总量" to formatBytes(mi.totalMem),
                "运行内存可用" to formatBytes(mi.availMem),
                "低内存阈值" to formatBytes(mi.threshold),
                "当前处于低内存" to yesNo(mi.lowMemory),
                "低内存机型" to yesNo(am.isLowRamDevice),
                "单应用内存上限" to "${am.memoryClass} MB",
            )
            val used = (mi.totalMem - mi.availMem).coerceAtLeast(0L)
            val percent = if (mi.totalMem > 0) (used * 100 / mi.totalMem).toInt() else 0
            result += Row.Progress("运行内存占用", percent, "已用 ${formatBytes(used)} / ${formatBytes(mi.totalMem)}")
        }

        val internalTotal = totalBytes(Environment.getDataDirectory())
        val internalAvailable = availableBytes(Environment.getDataDirectory())
        result += info("内部存储总量" to formatBytes(internalTotal))
        result += info("内部存储可用" to formatBytes(internalAvailable))
        if (internalTotal > 0) {
            val used = (internalTotal - internalAvailable).coerceAtLeast(0L)
            val percent = (used * 100 / internalTotal).toInt()
            result += Row.Progress("内部存储占用", percent, "已用 ${formatBytes(used)} / ${formatBytes(internalTotal)}")
        }

        val external = Environment.getExternalStorageDirectory()
        if (external != null && external.exists()) {
            result += info(
                "外部存储总量" to formatBytes(totalBytes(external)),
                "外部存储可用" to formatBytes(availableBytes(external)),
            )
        }
        result += Row.Action(getString(R.string.memory_realtime), getString(R.string.action_view)) {
            showMemoryRealtimeDialog()
        }
        result += Row.Action(getString(R.string.storage_volume_details), getString(R.string.action_view)) {
            showStorageVolumeDetailsDialog()
        }
        return result
    }

    private fun showMemoryRealtimeDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val summary = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 16f
        }
        val detail = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.brand_primary)
            )
            progressBackgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.icon_chip_bg)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8),
            )
        }
        val updated = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(summary)
        root.addView(detail)
        root.addView(progress)
        root.addView(updated)

        lateinit var dialog: AlertDialog
        val update = object : Runnable {
            override fun run() {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val mi = ActivityManager.MemoryInfo()
                if (am == null) {
                    summary.text = "暂时无法读取内存"
                } else {
                    am.getMemoryInfo(mi)
                    val used = (mi.totalMem - mi.availMem).coerceAtLeast(0L)
                    val percent = if (mi.totalMem > 0) used * 100.0 / mi.totalMem else 0.0
                    summary.text = String.format(Locale.US, "运行内存占用：%.1f%%", percent)
                    detail.text = "已用 ${formatBytes(used)} · 可用 ${formatBytes(mi.availMem)} · 总量 ${formatBytes(mi.totalMem)}"
                    progress.progress = percent.toInt().coerceIn(0, 100)
                    updated.text = "每秒刷新 · ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}"
                }
                if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.memory_realtime)
            .setView(root)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.setOnDismissListener { liveHandler.removeCallbacks(update) }
        dialog.show()
        liveHandler.post(update)
    }

    private fun showStorageVolumeDetailsDialog() {
        val storageManager = getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val volumes = runCatching { storageManager?.storageVolumes.orEmpty() }.getOrDefault(emptyList())
        val lines = volumes.mapIndexed { index, volume ->
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null
            val capacity = if (directory?.exists() == true) {
                "总量 ${formatBytes(totalBytes(directory))} · 可用 ${formatBytes(availableBytes(directory))}"
            } else "容量不可读取"
            val description = runCatching { volume.getDescription(this) }.getOrNull().orEmpty()
            buildString {
                appendLine("存储卷 ${index + 1}${if (description.isNotBlank()) "：$description" else ""}")
                appendLine("状态：${volume.state}")
                appendLine("主存储：${yesNo(volume.isPrimary)} · 可移除：${yesNo(volume.isRemovable)}")
                appendLine("模拟存储：${yesNo(volume.isEmulated)} · UUID：${volume.uuid ?: "无"}")
                appendLine("路径：${directory?.path ?: "系统未提供"}")
                append(capacity)
            }
        }
        showTextDialog(
            getString(R.string.storage_volume_details),
            lines.joinToString("\n\n").ifBlank { getString(R.string.storage_volume_unavailable) },
        )
    }

    private fun displayInfo(): List<Row> {
        val dm = resources.displayMetrics
        var width = dm.widthPixels
        var height = dm.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val real = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(real)
            width = real.widthPixels
            height = real.heightPixels
        }

        val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.refreshRate
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }

        val xInch = width / dm.xdpi
        val yInch = height / dm.ydpi
        val inches = sqrt(xInch * xInch + yInch * yInch)

        return info(
            "分辨率" to "$width × $height px",
            "屏幕密度" to "${dm.densityDpi} dpi（${dm.density}×）",
            "物理尺寸" to String.format(Locale.US, "%.2f 英寸", inches),
            "刷新率" to refreshRate?.let { String.format(Locale.US, "%.1f Hz", it) },
            "字体缩放" to String.format(Locale.US, "%.2f", resources.configuration.fontScale),
            "屏幕方向" to if (resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) "横屏" else "竖屏",
        ) + Row.Action(getString(R.string.touch_test), getString(R.string.action_view)) {
            showTouchTestDialog()
        } + Row.Action(getString(R.string.screen_color_test), getString(R.string.action_view)) {
            showScreenColorTestDialog()
        } + Row.Action(getString(R.string.display_details), getString(R.string.action_view)) {
            showDisplayDetailsDialog()
        }
    }

    private fun showDisplayDetailsDialog() {
        val brightness = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val brightnessMode = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
        val timeout = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrNull()
        val refreshRate = display?.refreshRate
        val body = buildString {
            appendLine("亮度：${brightness?.let { "${it * 100 / 255}%（原始值 $it）" } ?: "未知"}")
            appendLine("自动亮度：${when (brightnessMode) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "开启"
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "关闭"
                else -> "未知"
            }}")
            appendLine("自动熄屏：${timeout?.let { formatDuration(it.toLong()) } ?: "未知"}")
            append("当前刷新率：${refreshRate?.let { String.format(Locale.US, "%.1f Hz", it) } ?: "未知"}")
        }
        showTextDialog(getString(R.string.display_details), body)
    }

    private fun showTouchTestDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val hint = TextView(this).apply {
            text = getString(R.string.touch_test_hint, 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(8))
        }
        val touchView = TouchTraceView(this) { count ->
            hint.text = getString(R.string.touch_test_hint, count)
        }
        touchView.setBackgroundColor(ContextCompat.getColor(this, R.color.page_background))
        container.addView(hint)
        container.addView(
            touchView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.touch_test)
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private class TouchTraceView(
        context: Context,
        private val onStrokeCountChanged: (Int) -> Unit,
    ) : View(context) {
        private val paths = mutableListOf<MutableList<PointF>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 105, 220)
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    paths += mutableListOf(PointF(event.x, event.y))
                    onStrokeCountChanged(paths.size)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    paths.lastOrNull()?.add(PointF(event.x, event.y))
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paths.forEach { path ->
                for (index in 1 until path.size) {
                    val previous = path[index - 1]
                    val current = path[index]
                    canvas.drawLine(previous.x, previous.y, current.x, current.y, paint)
                }
                path.lastOrNull()?.let { canvas.drawCircle(it.x, it.y, 8f, paint) }
            }
        }
    }

    private fun showScreenColorTestDialog() {
        val colorView = ScreenColorTestView(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.screen_color_test)
            .setView(colorView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private class ScreenColorTestView(context: Context) : View(context) {
        private val colors = intArrayOf(
            Color.BLACK,
            Color.WHITE,
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.GRAY,
        )
        private val names = arrayOf("黑", "白", "红", "绿", "蓝", "灰")
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private var colorIndex = 0

        init {
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                colorIndex = (colorIndex + 1) % colors.size
                invalidate()
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(colors[colorIndex])
            val isLight = colorIndex == 1 || colorIndex == 3
            labelPaint.color = if (isLight) Color.BLACK else Color.WHITE
            val label = "点击切换：${names[colorIndex]}色"
            canvas.drawText(label, 32f, height - 40f, labelPaint)
        }
    }

    private fun batteryInfo(): List<Row> {
        val result = mutableListOf<Row>()

        val intent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else null

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)

            result += info(
                "电量" to percent?.let { "$it%" },
                "充电状态" to batteryStatusText(status),
                "充电方式" to batteryPluggedText(plugged),
                "电池健康" to batteryHealthText(health),
                "电池温度" to if (temperature != Int.MIN_VALUE) {
                    String.format(Locale.US, "%.1f ℃", temperature / 10.0)
                } else null,
                "电池电压" to if (voltage != Int.MIN_VALUE) {
                    String.format(Locale.US, "%.3f V", voltage / 1000.0)
                } else null,
                "电池技术" to intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            )
        }

        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null) {
            val current = runCatching {
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            }.getOrDefault(Int.MIN_VALUE)
            result += info(
                "实时电流" to if (current != Int.MIN_VALUE && current != 0) {
                    String.format(Locale.US, "%.1f mA", current / 1000.0)
                } else null,
            )
        }

        result += Row.Action(getString(R.string.battery_details), getString(R.string.action_view)) {
            showBatteryDetailsDialog()
        }
        result += Row.Action(getString(R.string.battery_power_curve), getString(R.string.action_view)) {
            showBatteryPowerDialog()
        }
        return result
    }

    private data class BatterySample(
        val voltage: Double?,
        val currentMa: Double?,
        val powerW: Double?,
    )

    private fun readBatterySample(): BatterySample {
        val intent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > 0 }
            ?.div(1000.0)
        val current = (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.let { manager ->
                runCatching { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong() }
                    .getOrNull()
                    ?.takeIf { it != Long.MIN_VALUE && it != 0L }
                    ?.div(1000.0)
            }
        val power = if (voltage != null && current != null) voltage * kotlin.math.abs(current) / 1000.0 else null
        return BatterySample(voltage, current, power)
    }

    private fun showBatteryPowerDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val values = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
        }
        val chart = PowerCurveView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(210),
            ).apply { topMargin = dp(12) }
        }
        val hint = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(values)
        root.addView(chart)
        root.addView(hint)

        lateinit var dialog: AlertDialog
        val samples = ArrayDeque<Double>()
        val update = object : Runnable {
            override fun run() {
                val sample = readBatterySample()
                val voltageText = sample.voltage?.let { String.format(Locale.US, "%.3f V", it) } ?: "未知"
                val currentText = sample.currentMa?.let { String.format(Locale.US, "%.1f mA", it) } ?: "未知"
                val powerText = sample.powerW?.let { String.format(Locale.US, "%.3f W", it) } ?: "暂不可计算"
                values.text = "实时电压：$voltageText\n实时电流：$currentText\n当前功率：$powerText"
                sample.powerW?.let {
                    if (samples.size >= 60) samples.removeFirst()
                    samples.addLast(it)
                }
                chart.setSamples(samples.toList())
                hint.text = "每秒刷新 · 曲线保留最近 ${samples.size} 个有效功率采样点"
                if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.battery_power_curve)
            .setView(root)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.setOnDismissListener { liveHandler.removeCallbacks(update) }
        dialog.show()
        liveHandler.post(update)
    }

    private fun showBatteryDetailsDialog() {
        val battery = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val intent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (battery == null) {
            showTextDialog(getString(R.string.battery_details), getString(R.string.empty_section))
            return
        }

        fun property(id: Int): Long? = runCatching { battery.getLongProperty(id) }
            .getOrNull()?.takeIf { it != Long.MIN_VALUE }
        fun currentText(value: Long?): String = value?.let {
            String.format(Locale.US, "%.1f mA", it / 1000.0)
        } ?: "未知"

        val charge = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energy = property(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val capacity = property(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else null
        val body = buildString {
            appendLine("实时电流：${currentText(property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))}")
            appendLine("平均电流：${currentText(property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))}")
            appendLine("系统电量：${capacity?.let { "$it%" } ?: "未知"}")
            appendLine("电荷计数：${charge?.let { String.format(Locale.US, "%.1f mAh", it / 1000.0) } ?: "未知"}")
            appendLine("能量计数：${energy?.let { String.format(Locale.US, "%.3f Wh", it / 1_000_000_000.0) } ?: "未知"}")
            append("循环次数：${cycleCount?.toString() ?: "系统未提供"}")
        }
        showTextDialog(getString(R.string.battery_details), body)
    }

    private fun networkInfo(): List<Row> {
        val result = mutableListOf<Row>()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            result += info(
                "网络类型" to caps?.let { networkTypeText(it) },
                "已连接" to yesNo(network != null && caps != null),
                "可访问互联网" to caps?.let { yesNo(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) },
                "按流量计费" to caps?.let { yesNo(!it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) },
            )
        }

        result += info("本机 IP 地址" to localIpv4Addresses().joinToString(", ").ifBlank { "无" })
        result += info(
            "支持 Wi-Fi" to yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)),
            "支持蓝牙" to yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)),
            "支持 GPS" to yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)),
            "支持 NFC" to yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)),
        )
        val totalRx = TrafficStats.getTotalRxBytes().takeIf { it >= 0L }
        val totalTx = TrafficStats.getTotalTxBytes().takeIf { it >= 0L }
        result += info(
            "累计接收流量" to totalRx?.let(::formatBytes),
            "累计发送流量" to totalTx?.let(::formatBytes),
            "累计总流量" to if (totalRx != null && totalTx != null) formatBytes(totalRx + totalTx) else null,
        )
        result += Row.Action(getString(R.string.network_traffic_details), getString(R.string.action_view)) {
            showNetworkTrafficDialog()
        }
        result += Row.Action(getString(R.string.network_details), getString(R.string.action_view)) {
            showNetworkDetailsDialog()
        }
        return result
    }

    private fun showNetworkTrafficDialog() {
        val view = DialogTextBinding.inflate(layoutInflater)
        lateinit var dialog: AlertDialog
        var previous: Pair<Long, Long>? = null
        var previousAt = 0L
        val startRx = TrafficStats.getTotalRxBytes().takeIf { it >= 0L }
        val startTx = TrafficStats.getTotalTxBytes().takeIf { it >= 0L }

        val update = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val speed = if (rx >= 0L && tx >= 0L && previous != null && previousAt > 0L) {
                    val seconds = (now - previousAt).coerceAtLeast(1L) / 1000.0
                    val rxPerSecond = ((rx - previous!!.first).coerceAtLeast(0L) / seconds).toLong()
                    val txPerSecond = ((tx - previous!!.second).coerceAtLeast(0L) / seconds).toLong()
                    "下载 ${formatBytesPerSecond(rxPerSecond)} · 上传 ${formatBytesPerSecond(txPerSecond)} · 合计 ${formatBytesPerSecond(rxPerSecond + txPerSecond)}"
                } else "等待采样…"
                if (rx >= 0L && tx >= 0L) previous = rx to tx
                previousAt = now

                val sessionRx = if (rx >= 0L && startRx != null) (rx - startRx).coerceAtLeast(0L) else null
                val sessionTx = if (tx >= 0L && startTx != null) (tx - startTx).coerceAtLeast(0L) else null
                view.dialogText.text = buildString {
                    appendLine("当前网速：$speed")
                    appendLine()
                    appendLine("累计接收：${rx.takeIf { it >= 0L }?.let(::formatBytes) ?: "未知"}")
                    appendLine("累计发送：${tx.takeIf { it >= 0L }?.let(::formatBytes) ?: "未知"}")
                    appendLine("累计总量：${if (rx >= 0L && tx >= 0L) formatBytes(rx + tx) else "未知"}")
                    appendLine()
                    appendLine("本次监测新增接收：${sessionRx?.let(::formatBytes) ?: "未知"}")
                    append("本次监测新增发送：${sessionTx?.let(::formatBytes) ?: "未知"}")
                }
                if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.network_traffic_details)
            .setView(view.root)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard(getString(R.string.network_traffic_details), view.dialogText.text.toString())
            }
            .create()
        dialog.setOnDismissListener { liveHandler.removeCallbacks(update) }
        dialog.show()
        liveHandler.post(update)
    }

    private fun showNetworkDetailsDialog() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val properties = network?.let { cm.getLinkProperties(it) }
        val body = if (properties == null) {
            getString(R.string.network_details_unavailable)
        } else {
            buildString {
                appendLine("接口：${properties.interfaceName ?: "未知"}")
                appendLine("链路地址：${properties.linkAddresses.joinToString().ifBlank { "无" }}")
                appendLine("DNS：${properties.dnsServers.joinToString().ifBlank { "无" }}")
                appendLine("路由：${properties.routes.joinToString().ifBlank { "无" }}")
                appendLine("搜索域：${properties.domains ?: "无"}")
                append("MTU：${properties.mtu}")
            }
        }
        showTextDialog(getString(R.string.network_details), body)
    }

    private fun sensorInfo(): List<Row> {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        return info(
            "传感器数量" to sensors.size.toString(),
            "传感器列表" to sensors.joinToString("\n") { "· ${it.name}" },
        ) + Row.Action(getString(R.string.sensor_test), getString(R.string.action_view)) {
            showSensorTestDialog()
        } + Row.Action("传感器详细参数", getString(R.string.action_view)) {
            val body = sensors.joinToString("\n\n") { sensor ->
                val range = String.format(Locale.US, "%.3f", sensor.maximumRange)
                val resolution = String.format(Locale.US, "%.3f", sensor.resolution)
                val power = String.format(Locale.US, "%.2f", sensor.power)
                buildString {
                    appendLine(sensor.name)
                    appendLine("类型：${sensor.stringType ?: sensor.type}")
                    appendLine("厂商：${sensor.vendor.ifBlank { "未知" }}")
                    appendLine("版本：${sensor.version}")
                    appendLine("最大量程：$range")
                    appendLine("分辨率：$resolution")
                    append("功耗：$power mA")
                }
            }.ifBlank { getString(R.string.empty_section) }
            showTextDialog(getString(R.string.sensor_details), body)
        }
    }

    private fun showSensorTestDialog() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (sensorManager == null || (accelerometer == null && gyroscope == null)) {
            showTextDialog(getString(R.string.sensor_test), getString(R.string.sensor_test_unavailable))
            return
        }

        val view = DialogTextBinding.inflate(layoutInflater)
        var accelerationValues: FloatArray? = null
        var gyroscopeValues: FloatArray? = null
        fun vectorText(values: FloatArray?): String = values?.let {
            String.format(Locale.US, "x %.2f · y %.2f · z %.2f", it[0], it[1], it[2])
        } ?: "等待数据…"
        fun renderValues() {
            view.dialogText.text = buildString {
                appendLine("加速度计：${vectorText(accelerationValues)} m/s²")
                appendLine("陀螺仪：${vectorText(gyroscopeValues)} rad/s")
                appendLine()
                append("提示：静止时加速度合计通常接近 9.8 m/s²；旋转手机观察陀螺仪数值变化。")
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> accelerationValues = event.values.clone()
                    Sensor.TYPE_GYROSCOPE -> gyroscopeValues = event.values.clone()
                }
                renderValues()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sensor_test)
            .setView(view.root)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard(getString(R.string.sensor_test), view.dialogText.text.toString())
            }
            .create()
        dialog.setOnDismissListener { sensorManager.unregisterListener(listener) }
        dialog.show()
        renderValues()
        accelerometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun cameraInfo(): List<Row> {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val ids = runCatching { cm.cameraIdList }.getOrNull() ?: return emptyList()
        val details = ids.mapNotNull { id ->
            runCatching {
                val c = cm.getCameraCharacteristics(id)
                val facing = c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                val name = when (facing) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    else -> "外接"
                }
                "· $name（ID $id）"
            }.getOrNull()
        }
        val capabilities = ids.mapIndexedNotNull { index, id ->
            runCatching {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    else -> "外接"
                }
                val map = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxJpeg = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val size = maxJpeg?.let { "${it.width} × ${it.height}" } ?: "未知"
                val flash = c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
                val level = when (c.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                    else -> "未知"
                }
                "摄像头 ${index + 1}（$facing，ID $id）\n最大照片尺寸：$size\n闪光灯：${flash?.let { yesNo(it) } ?: "未知"}\nCamera2 等级：$level"
            }.getOrNull()
        }
        return info(
            "摄像头数量" to ids.size.toString(),
            "摄像头明细" to details.joinToString("\n"),
        ) + capabilities.mapIndexed { index, value ->
            Row.Item("摄像头 ${index + 1} 能力", value)
        }
    }

    private fun appInfo(): List<Row> {
        val pi = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return emptyList()

        return info(
            "包名" to packageName,
            "版本名称" to pi.versionName,
            "版本号" to versionCodeOf(pi),
            "目标 SDK" to pi.applicationInfo?.targetSdkVersion?.toString(),
            "最小 SDK" to pi.applicationInfo?.minSdkVersion?.toString(),
            "首次安装时间" to formatTime(pi.firstInstallTime),
            "最近更新时间" to formatTime(pi.lastUpdateTime),
        ) + listOf(
            Row.Action(getString(R.string.app_security), getString(R.string.action_view)) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
                }
                val signedInfo = runCatching {
                    packageManager.getPackageInfo(packageName, flags)
                }.getOrNull()
                showAppSecurityDialog(signedInfo)
            },
            Row.Action(getString(R.string.app_runtime_details), getString(R.string.action_view)) {
                showAppRuntimeDetailsDialog()
            },
        )
    }

    private fun showAppRuntimeDetailsDialog() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        val importance = when (state.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "前台"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "可见但非前台"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "服务"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "缓存"
            else -> "其他（${state.importance}）"
        }
        val appInfo = applicationInfo
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (activityManager?.isBackgroundRestricted == true) "已限制" else "未限制"
        } else "系统版本不支持读取"
        val body = listOf(
            "当前进程状态：$importance",
            "后台活动限制：$backgroundRestricted",
            "设备低内存模式：${if (activityManager?.isLowRamDevice == true) "是" else "否/未知"}",
            "普通内存上限：${activityManager?.memoryClass ?: 0} MB",
            "大内存上限：${activityManager?.largeMemoryClass ?: 0} MB",
            "允许大堆：${if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP != 0) "是" else "否"}",
        ).joinToString("\n")
        showTextDialog(getString(R.string.app_runtime_details), body)
    }

    @Suppress("DEPRECATION")
    private fun showAppSecurityDialog(info: android.content.pm.PackageInfo?) {
        if (info == null) {
            showTextDialog(getString(R.string.app_security), getString(R.string.empty_section))
            return
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        val fingerprints = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString(":") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
        }
        val appInfo = info.applicationInfo
        val installer = runCatching { packageManager.getInstallerPackageName(packageName) }
            .getOrNull()?.ifBlank { "未知" } ?: "未知"
        val body = buildString {
            appendLine("可调试：${yesNo(appInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 } == true)}")
            appendLine("安装来源：$installer")
            appendLine("数据目录：${appInfo?.dataDir ?: "未知"}")
            appendLine("Native 库目录：${appInfo?.nativeLibraryDir ?: "未知"}")
            appendLine()
            appendLine("签名 SHA-256：")
            if (fingerprints.isEmpty()) append("未知") else fingerprints.forEach { appendLine(it) }
        }
        showTextDialog(getString(R.string.app_security), body)
    }

    // ------------------------------------------------------------------
    // 设备能力 / 多媒体能力
    // ------------------------------------------------------------------

    private fun hasFeature(name: String): Boolean =
        runCatching { packageManager.hasSystemFeature(name) }.getOrDefault(false)

    /** 系统设置里的设备名称，取不到时回退到型号 */
    private fun deviceName(): String {
        val fromSettings = runCatching {
            Settings.Global.getString(contentResolver, "device_name")
        }.getOrNull()
        return fromSettings?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    private fun deviceType(): String {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        when (uiMode?.currentModeType) {
            Configuration.UI_MODE_TYPE_TELEVISION -> return "电视"
            Configuration.UI_MODE_TYPE_CAR -> return "车载"
            Configuration.UI_MODE_TYPE_WATCH -> return "手表"
            Configuration.UI_MODE_TYPE_DESK -> return "桌面设备"
        }
        return if (resources.configuration.smallestScreenWidthDp >= 600) "平板" else "手机"
    }

    /** 基于 Android 12 起提供的媒体性能等级 */
    private fun performanceClass(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return getString(R.string.value_unknown)
        return when (Build.VERSION.MEDIA_PERFORMANCE_CLASS) {
            33, 34, 35, 36 -> "旗舰"
            32 -> "高"
            31 -> "中"
            30 -> "入门"
            else -> getString(R.string.value_unknown)
        }
    }

    private fun videoCodecs(): List<String> = codecsFor(video = true)

    private fun audioCodecs(): List<String> = codecsFor(video = false)

    /** 取系统内置解码器支持的 MIME 类型 */
    private fun codecsFor(video: Boolean): List<String> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { info -> info.supportedTypes.toList() }
            .filter { it.startsWith(if (video) "video/" else "audio/") }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    /**
     * 封装格式。Android 没有公开的「容器格式列表」接口，
     * 这里根据系统解码器支持的 MIME 类型推断，结果仅供参考。
     */
    private fun containerFormats(): List<String> {
        val types = (videoCodecs() + audioCodecs()).toSet()
        fun has(vararg mime: String) = mime.any { types.contains(it) }

        return listOf(
            "MP4" to has("video/avc", "video/hevc", "audio/mp4a-latm"),
            "Matroska (MKV)" to has("video/x-matroska"),
            "WebM" to has("video/webm", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "audio/webm"),
            "MPEG-TS" to has("video/mp2t"),
            "3GPP" to has("video/3gpp", "audio/3gpp"),
            "MP3" to has("audio/mpeg"),
            "AAC (ADTS)" to has("audio/aac"),
            "FLAC" to has("audio/flac"),
            "OGG / Vorbis" to has("audio/vorbis", "audio/ogg"),
            "Opus" to has("audio/opus"),
            "WAV / PCM" to has("audio/raw"),
            "AMR" to has("audio/amr-wb", "audio/amr"),
            "MIDI" to has("audio/midi"),
        ).map { (name, supported) -> (if (supported) "✓ " else "✗ ") + name }
    }

    /** DRM 方案，Widevine 额外读取安全等级 */
    @Suppress("DEPRECATION")
    private fun drmSchemes(): List<String> {
        val schemes = listOf(
            "Widevine" to "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",
            "PlayReady" to "9a04f079-9840-4286-ab92-e65be0885f95",
            "ClearKey" to "e2719d58-a985-b3c9-781a-b030af78d30e",
            "Marlin" to "5e629af5-38da-4063-8977-97ffbd9902d4",
        )

        return schemes.map { (name, raw) ->
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return@map "✗ $name"
            val supported = runCatching {
                MediaDrm.isCryptoSchemeSupported(uuid)
            }.getOrDefault(false)
            if (!supported) return@map "✗ $name"

            val detail = runCatching {
                val drm = MediaDrm(uuid)
                try {
                    val level = drm.getPropertyString("securityLevel")
                    val vendor = drm.getPropertyString("vendor")
                    val version = drm.getPropertyString("version")
                    "（安全等级 $level · $vendor $version）"
                } finally {
                    // release() 在 API 33 起被 close() 取代，但为兼容低版本仍需调用
                    runCatching { drm.release() }
                }
            }.getOrDefault("")
            "✓ $name$detail"
        }
    }

    // ------------------------------------------------------------------
    // 弹窗与剪贴板
    // ------------------------------------------------------------------

    private fun showListDialog(titleRes: Int, lines: List<String>, inferred: Boolean) {
        val body = buildString {
            if (lines.isEmpty()) {
                append(getString(R.string.empty_section))
            } else {
                lines.forEach { appendLine(it) }
            }
            if (inferred && lines.isNotEmpty()) {
                appendLine()
                append(getString(R.string.about_inferred))
            }
        }
        showTextDialog(getString(titleRes), body)
    }

    private fun showTextDialog(title: String, body: String) {
        val view = DialogTextBinding.inflate(layoutInflater)
        view.dialogText.text = body
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view.root)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ -> copyToClipboard(title, body) }
            .show()
    }

    private fun showMoreDialog() {
        val actions = arrayOf(
            getString(R.string.action_refresh),
            getString(R.string.search_info),
            getString(R.string.health_check),
            getString(R.string.live_monitor),
            getString(R.string.share_report),
            getString(R.string.share_json_report),
            getString(R.string.export_json),
            getString(R.string.export_text),
            getString(R.string.save_snapshot),
            getString(R.string.compare_snapshot),
            getString(R.string.import_snapshot),
            getString(R.string.system_tools),
            getString(R.string.privacy_permissions),
            getString(R.string.share_redacted_report),
            getString(R.string.device_label),
            getString(R.string.time_details),
            getString(R.string.system_state_details),
            getString(R.string.sound_state_details),
            getString(R.string.app_runtime_details),
            getString(R.string.storage_volume_details),
            getString(R.string.copy_all),
            getString(R.string.about_title),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_more)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        render()
                        toast(getString(R.string.action_refresh))
                    }

                    1 -> showSearchDialog()
                    2 -> showHealthDialog()
                    3 -> showLiveMonitorDialog()
                    4 -> shareReport()
                    5 -> shareJsonReport()
                    6 -> exportJsonFile()
                    7 -> exportTextFile()
                    8 -> saveSnapshot()
                    9 -> compareSnapshot()
                    10 -> importSnapshotFile()
                    11 -> showSystemToolsDialog()
                    12 -> showPermissionDialog()
                    13 -> shareRedactedReport()
                    14 -> showDeviceLabelDialog()
                    15 -> showTimeDetailsDialog()
                    16 -> showSystemStateDetailsDialog()
                    17 -> showSoundStateDetailsDialog()
                    18 -> showAppRuntimeDetailsDialog()
                    19 -> showStorageVolumeDetailsDialog()
                    20 -> copyToClipboard(getString(R.string.app_name), buildPlainTextReport())
                    21 -> showAboutDialog()
                }
            }
            .show()
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_info)
            .setView(input)
            .setNegativeButton(R.string.ok, null)
            .setPositiveButton(R.string.search) { _, _ -> searchInfo(input.text.toString()) }
            .show()
    }

    private fun searchInfo(query: String) {
        val needle = query.trim()
        if (needle.isBlank()) return
        val matches = mutableListOf<String>()
        allSections().forEach { section ->
            val sectionMatches = mutableListOf<String>()
            runCatching { section.provider() }.getOrDefault(emptyList()).forEach { row ->
                when (row) {
                    is Row.Item -> if (matchesQuery(needle, row.label, row.value)) {
                        sectionMatches += "${row.label}：${row.value}"
                    }
                    is Row.Action -> if (matchesQuery(needle, row.label, row.actionText)) {
                        sectionMatches += "${row.label}：${row.actionText}"
                    }
                    is Row.Grid -> row.items.forEach { (label, supported) ->
                        val value = if (supported) "支持" else "不支持"
                        if (matchesQuery(needle, label, value)) sectionMatches += "$label：$value"
                    }
                    is Row.Progress -> if (matchesQuery(needle, row.label, row.value)) {
                        sectionMatches += "${row.label}：${row.value}（${row.percent}%）"
                    }
                }
            }
            if (sectionMatches.isNotEmpty()) {
                matches += "== ${getString(section.titleRes)} =="
                matches += sectionMatches.map { "· $it" }
                matches += ""
            }
        }

        val body = if (matches.isEmpty()) {
            getString(R.string.search_no_result, needle)
        } else {
            "共找到 ${matches.count { it.startsWith("· ") }} 项匹配：\n\n${matches.joinToString("\n")}"
        }
        showTextDialog(getString(R.string.search_result_title, needle), body)
    }

    private fun matchesQuery(query: String, vararg values: String): Boolean =
        values.any { it.contains(query, ignoreCase = true) }

    /** 展示应用清单中的权限及当前授权状态，方便用户核对隐私边界。 */
    private fun showPermissionDialog() {
        val permissions = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty()
        }.getOrDefault(emptyArray())
        val body = buildString {
            appendLine("本应用只读取设备信息，不会在此页面主动申请新权限。")
            appendLine()
            if (permissions.isEmpty()) {
                append(getString(R.string.no_permissions_declared))
            } else {
                appendLine("声明权限：${permissions.size} 项")
                appendLine()
                permissions.forEach { permission ->
                    val granted = ContextCompat.checkSelfPermission(this@MainActivity, permission) ==
                        PackageManager.PERMISSION_GRANTED
                    val shortName = permission.substringAfterLast('.')
                    appendLine("${if (granted) "✓" else "!"} $shortName")
                    appendLine("  $permission")
                }
            }
        }
        showTextDialog(getString(R.string.privacy_permissions), body)
    }

    /** 常用排障入口：看完数据后直接进入对应的系统设置页面。 */
    private fun showSystemToolsDialog() {
        val actions = arrayOf(
            getString(R.string.open_network_settings),
            getString(R.string.open_display_settings),
            getString(R.string.open_battery_settings),
            getString(R.string.open_app_settings),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.system_tools)
            .setItems(actions) { _, which ->
                val intent = when (which) {
                    0 -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    1 -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    2 -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                }
                runCatching { startActivity(intent) }
                    .onFailure { toast(getString(R.string.settings_unavailable)) }
            }
            .show()
    }

    /** 用少量高价值指标给出一个“现在是否值得关注”的快速判断。 */
    private fun showHealthDialog() {
        val items = runHealthCheck()
        val scored = items.filter { it.state == HealthState.GOOD || it.state == HealthState.NOTICE }
        val notices = scored.count { it.state == HealthState.NOTICE }
        val score = if (scored.isEmpty()) 0 else {
            (scored.count { it.state == HealthState.GOOD } * 100 / scored.size)
        }

        val body = buildString {
            append("综合结果：")
            append(if (notices == 0) "状态良好" else "有 $notices 项提醒")
            append("（$score 分）")
            appendLine()
            appendLine("提醒不等同于故障，未连接网络等情况可能只是当前使用环境。")
            appendLine()
            items.forEach { item ->
                val icon = when (item.state) {
                    HealthState.GOOD -> "✓"
                    HealthState.NOTICE -> "!"
                    HealthState.INFO -> "·"
                }
                appendLine("$icon ${item.label}：${item.detail}")
            }
        }
        showTextDialog(getString(R.string.health_check), body)
    }

    private fun runHealthCheck(): List<HealthItem> {
        val result = mutableListOf<HealthItem>()

        val battery = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (battery == null) {
            result += HealthItem("电池", HealthState.NOTICE, "暂时无法读取")
        } else {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val tempCelsius = if (temperature != Int.MIN_VALUE) temperature / 10.0 else null
            val healthText = batteryHealthText(health)
            val notice = percent in 0..19 || (tempCelsius != null && tempCelsius > 45.0) ||
                (health >= 0 && health != BatteryManager.BATTERY_HEALTH_UNKNOWN &&
                    health != BatteryManager.BATTERY_HEALTH_GOOD)
            val detail = buildString {
                if (percent >= 0) append("电量 $percent%") else append("电量未知")
                tempCelsius?.let { append(" · 温度 ${String.format(Locale.US, "%.1f ℃", it)}") }
                healthText?.let { append(" · 健康 $it") }
            }
            result += HealthItem("电池", if (notice) HealthState.NOTICE else HealthState.GOOD, detail)
        }

        val dataDir = Environment.getDataDirectory()
        val storageTotal = totalBytes(dataDir)
        val storageAvailable = availableBytes(dataDir)
        if (storageTotal > 0) {
            val freePercent = storageAvailable * 100 / storageTotal
            result += HealthItem(
                "内部存储",
                if (freePercent < 10) HealthState.NOTICE else HealthState.GOOD,
                "可用 ${formatBytes(storageAvailable)} / ${formatBytes(storageTotal)}（剩余 $freePercent%）",
            )
        } else {
            result += HealthItem("内部存储", HealthState.NOTICE, "暂时无法读取")
        }

        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            result += HealthItem("运行内存", HealthState.NOTICE, "暂时无法读取")
        } else {
            val memory = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memory)
            val freePercent = if (memory.totalMem > 0) memory.availMem * 100 / memory.totalMem else 0
            result += HealthItem(
                "运行内存",
                if (memory.lowMemory || freePercent < 10) HealthState.NOTICE else HealthState.GOOD,
                "可用 ${formatBytes(memory.availMem)} / ${formatBytes(memory.totalMem)}（剩余 $freePercent%）",
            )
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        result += HealthItem(
            "网络",
            if (internet) HealthState.GOOD else HealthState.NOTICE,
            if (internet) "已连接 · ${networkTypeText(caps!!)}" else "当前未检测到可用网络",
        )

        val sensorCount = (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getSensorList(Sensor.TYPE_ALL)?.size
        result += HealthItem(
            "传感器",
            HealthState.INFO,
            sensorCount?.let { "检测到 $it 个" } ?: "暂时无法读取",
        )

        val cameraCount = runCatching {
            (getSystemService(Context.CAMERA_SERVICE) as? CameraManager)?.cameraIdList?.size
        }.getOrNull()
        result += HealthItem(
            "摄像头",
            HealthState.INFO,
            cameraCount?.let { "检测到 $it 个" } ?: "暂时无法读取",
        )

        return result
    }

    /** 通过系统分享面板发送完整报告，不申请存储权限。 */
    private fun shareReport() {
        shareTextReport(buildPlainTextReport(), getString(R.string.share_subject), getString(R.string.share_report))
    }

    private fun shareRedactedReport() {
        val redacted = buildPlainTextReport()
            .replace(Regex("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}"), "[已隐藏 IP]")
            .replace(Regex("(?m)^设备指纹：.*$"), "设备指纹：[已隐藏]")
        shareTextReport(redacted, getString(R.string.share_redacted_subject), getString(R.string.share_redacted_report))
    }

    private fun shareTextReport(body: String, subject: String, chooserTitle: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, chooserTitle))
        }.onFailure {
            toast(getString(R.string.share_unavailable))
        }
    }

    /** 每秒刷新一次，适合观察发热、卡顿、耗电和网络吞吐。 */
    private fun showLiveMonitorDialog() {
        val view = DialogTextBinding.inflate(layoutInflater)
        lateinit var dialog: AlertDialog
        var previousCpu: CpuTimes? = null
        var previousNetwork: Pair<Long, Long>? = null
        var previousAt = 0L

        val update = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val cpu = readCpuTimes()
                val cpuUsage = if (cpu != null && previousCpu != null) {
                    val totalDelta = cpu.total - previousCpu!!.total
                    val idleDelta = cpu.idle - previousCpu!!.idle
                    if (totalDelta > 0) {
                        ((totalDelta - idleDelta).toDouble() / totalDelta * 100.0).coerceIn(0.0, 100.0)
                    } else null
                } else null
                previousCpu = cpu

                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val networkSpeed = if (rx >= 0 && tx >= 0 && previousNetwork != null && previousAt > 0) {
                    val seconds = (now - previousAt).coerceAtLeast(1L) / 1000.0
                    val rxPerSecond = ((rx - previousNetwork!!.first).coerceAtLeast(0L) / seconds).toLong()
                    val txPerSecond = ((tx - previousNetwork!!.second).coerceAtLeast(0L) / seconds).toLong()
                    "下载 ${formatBytesPerSecond(rxPerSecond)} · 上传 ${formatBytesPerSecond(txPerSecond)}"
                } else "等待采样…"
                if (rx >= 0 && tx >= 0) previousNetwork = rx to tx
                previousAt = now

                val battery = runCatching {
                    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull()
                val level = battery?.let {
                    val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (raw >= 0 && scale > 0) raw * 100 / scale else null
                }
                val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE }
                    ?.let { String.format(Locale.US, "%.1f ℃", it / 10.0) }
                val memory = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }

                view.dialogText.text = buildString {
                    appendLine("采样时间：${formatTime(System.currentTimeMillis())}")
                    appendLine()
                    appendLine("CPU 使用率：${cpuUsage?.let { String.format(Locale.US, "%.1f%%", it) } ?: "采样中…"}")
                    appendLine("运行内存可用：${memory?.let { formatBytes(it.availMem) } ?: "未知"}")
                    appendLine("电池：${level?.let { "$it%" } ?: "未知"}${temperature?.let { " · $it" } ?: ""}")
                    appendLine("网络速度：$networkSpeed")
                    appendLine()
                    append("数据每秒刷新；退出页面后会自动停止采样。")
                }
                if (dialog.isShowing) liveHandler.postDelayed(this, 1000L)
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.live_monitor)
            .setView(view.root)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ -> copyToClipboard(getString(R.string.live_monitor), view.dialogText.text.toString()) }
            .create()
        dialog.setOnDismissListener { liveHandler.removeCallbacks(update) }
        dialog.show()
        liveHandler.post(update)
    }

    private fun shareJsonReport() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_json_subject))
            putExtra(Intent.EXTRA_TEXT, buildJsonReport())
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_json_report)))
        }.onFailure {
            toast(getString(R.string.share_unavailable))
        }
    }

    /** 通过系统文件选择器保存 JSON，不需要申请存储权限。 */
    private fun exportJsonFile() {
        pendingExportJson = buildJsonReport()
        val filename = "device-info-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
        exportJsonLauncher.launch(filename)
    }

    private fun exportTextFile() {
        pendingExportText = buildPlainTextReport()
        val filename = "device-info-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        exportTextLauncher.launch(filename)
    }

    private fun importSnapshotFile() {
        importSnapshotLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun buildJsonReport(): String {
        val root = JSONObject()
            .put("app", getString(R.string.app_name))
            .put("packageName", packageName)
            .put("generatedAt", formatTime(System.currentTimeMillis()))
        val sections = JSONArray()
        allSections().forEach { section ->
            val sectionJson = JSONObject()
                .put("key", section.key)
                .put("title", getString(section.titleRes))
            val items = JSONArray()
            runCatching { section.provider() }.getOrDefault(emptyList()).forEach { row ->
                when (row) {
                    is Row.Item -> items.put(JSONObject().put("label", row.label).put("value", row.value))
                    is Row.Action -> items.put(JSONObject().put("label", row.label).put("value", row.actionText))
                    is Row.Grid -> row.items.forEach { (label, supported) ->
                        items.put(JSONObject().put("label", label).put("supported", supported))
                    }
                    is Row.Progress -> items.put(
                        JSONObject().put("label", row.label).put("value", row.value).put("percent", row.percent)
                    )
                }
            }
            sectionJson.put("items", items)
            sections.put(sectionJson)
        }
        return root.put("sections", sections).toString(2)
    }

    /** 保存一份本地快照，便于维修前后或系统升级前后对比。 */
    private fun saveSnapshot() {
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
            .edit()
            .putString(SNAPSHOT_JSON, buildJsonReport())
            .putLong(SNAPSHOT_TIME, System.currentTimeMillis())
            .apply()
        toast(getString(R.string.snapshot_saved))
    }

    private fun compareSnapshot() {
        val preferences = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
        val previousJson = preferences.getString(SNAPSHOT_JSON, null)
        if (previousJson.isNullOrBlank()) {
            showTextDialog(
                getString(R.string.compare_snapshot),
                getString(R.string.snapshot_empty),
            )
            return
        }

        val previous = flattenJsonReport(previousJson)
        val current = flattenJsonReport(buildJsonReport())
        val changed = (previous.keys + current.keys).toSortedSet()
            .filter { previous[it] != current[it] }
        val savedAt = preferences.getLong(SNAPSHOT_TIME, 0L)
        val body = buildString {
            appendLine("上次快照：${if (savedAt > 0) formatTime(savedAt) else "未知时间"}")
            appendLine("当前采样：${formatTime(System.currentTimeMillis())}")
            appendLine()
            if (changed.isEmpty()) {
                append(getString(R.string.snapshot_no_change))
            } else {
                appendLine("共发现 ${changed.size} 项变化：")
                appendLine()
                changed.forEach { key ->
                    appendLine("【$key】")
                    appendLine("之前：${previous[key] ?: "无"}")
                    appendLine("现在：${current[key] ?: "无"}")
                    appendLine()
                }
            }
        }
        showTextDialog(getString(R.string.compare_snapshot), body)
    }

    private fun flattenJsonReport(json: String): Map<String, String> = runCatching {
        val result = linkedMapOf<String, String>()
        val sections = JSONObject(json).optJSONArray("sections") ?: return@runCatching result
        for (sectionIndex in 0 until sections.length()) {
            val section = sections.optJSONObject(sectionIndex) ?: continue
            val title = section.optString("title", section.optString("key"))
            val items = section.optJSONArray("items") ?: continue
            for (itemIndex in 0 until items.length()) {
                val item = items.optJSONObject(itemIndex) ?: continue
                val label = item.optString("label")
                val value = if (item.has("supported")) {
                    if (item.optBoolean("supported")) "支持" else "不支持"
                } else {
                    item.optString("value")
                }
                result["$title · $label"] = value
            }
        }
        result
    }.getOrDefault(emptyMap())

    private fun showAboutDialog() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        val message = getString(
            R.string.about_message,
            versionName,
            packageName,
            totalItemCount(),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** 统计实际展示的信息条目数，网格按单元格计数 */
    private fun totalItemCount(): Int = allSections().sumOf { section ->
        runCatching {
            section.provider().sumOf { row ->
                if (row is Row.Grid) row.items.size else 1
            }
        }.getOrDefault(0)
    }

    /** 导出为纯文本，便于粘贴分享 */
    private fun buildPlainTextReport(): String = buildString {
        appendLine("【${getString(R.string.app_name)}】")
        appendLine()
        allSections().forEach { section ->
            appendLine("== ${getString(section.titleRes)} ==")
            runCatching { section.provider() }.getOrDefault(emptyList()).forEach { row ->
                when (row) {
                    is Row.Item -> appendLine("${row.label}：${row.value}")
                    is Row.Action -> appendLine("${row.label}：${row.actionText}")
                    is Row.Grid -> row.items.forEach { (name, supported) ->
                        appendLine("$name：${if (supported) "支持" else "不支持"}")
                    }
                    is Row.Progress -> appendLine("${row.label}：${row.value}（${row.percent}%）")
                }
            }
            appendLine()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(getString(R.string.copied))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private fun info(vararg pairs: Pair<String, String?>): List<Row> =
        pairs.mapNotNull { (label, value) ->
            value?.takeIf { it.isNotBlank() }?.let { Row.Item(label, it) }
        }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    @Suppress("DEPRECATION")
    private fun versionCodeOf(pi: android.content.pm.PackageInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toString()
        } else {
            pi.versionCode.toString()
        }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun formatDuration(millis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return buildString {
            if (days > 0) append("${days}天 ")
            append(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds))
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes <= 0) "未知" else Formatter.formatFileSize(this, bytes)

    private fun formatBytesPerSecond(bytes: Long): String =
        if (bytes <= 0) "0 B/s" else "${Formatter.formatFileSize(this, bytes)}/s"

    private data class CpuTimes(val total: Long, val idle: Long)

    private data class CpuFrequency(val current: Long, val min: Long, val max: Long)

    private fun readCpuFrequencySnapshots(): Map<String, CpuFrequency> =
        File("/sys/devices/system/cpu").listFiles().orEmpty()
            .filter { it.name.matches(Regex("cpu\\d+")) }
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { core ->
                val cpufreq = File(core, "cpufreq")
                val current = readCpuFreqValue(File(cpufreq, "scaling_cur_freq"))
                    ?: readCpuFreqValue(File(cpufreq, "cpuinfo_cur_freq"))
                val min = readCpuFreqValue(File(cpufreq, "cpuinfo_min_freq")) ?: 0L
                val max = readCpuFreqValue(File(cpufreq, "cpuinfo_max_freq")) ?: 0L
                if (current != null && max > min) core.name to CpuFrequency(current, min, max) else null
            }
            .toMap()

    private fun readCpuFreqValue(file: File): Long? = runCatching {
        file.readText().trim().toLong().takeIf { it > 0L }
    }.getOrNull()

    private fun readCpuTimes(): CpuTimes? = runCatching {
        val line = readProcStatLines().firstOrNull { it.startsWith("cpu ") }
            ?: return@runCatching null
        val values = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        CpuTimes(values.sum(), values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L })
    }.getOrNull()

    private fun readAllCpuTimes(): Map<String, CpuTimes> = runCatching {
        readProcStatLines()
            .asSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val label = parts.firstOrNull()?.takeIf { it == "cpu" || (it.startsWith("cpu") && it.drop(3).toIntOrNull() != null) }
                    ?: return@mapNotNull null
                val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (values.isEmpty()) null else {
                    label to CpuTimes(
                        total = values.sum(),
                        idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L },
                    )
                }
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun readProcStatLines(): List<String> {
        val normal = runCatching { File("/proc/stat").readLines() }
            .getOrDefault(emptyList())
        if (normal.isNotEmpty()) return normal

        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/stat"))
            val completed = process.waitFor(1500L, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroy()
                emptyList()
            } else if (process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readLines() }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun readTopCpuUsage(): Double? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("top", "-b", "-n", "1"))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val cpuLine = output.lineSequence().firstOrNull { it.contains("%cpu", ignoreCase = true) }
            ?: return@runCatching null
        val capacityMatch = Regex("([0-9]+(?:\\.[0-9]+)?)%cpu", RegexOption.IGNORE_CASE).find(cpuLine)
            ?: return@runCatching null
        val idleMatch = Regex("([0-9]+(?:\\.[0-9]+)?)%idle", RegexOption.IGNORE_CASE).find(cpuLine)
            ?: return@runCatching null
        val capacity = capacityMatch.groupValues[1].toDouble()
        val idle = idleMatch.groupValues[1].toDouble()
        if (capacity > 0.0) ((capacity - idle) / capacity * 100.0).coerceIn(0.0, 100.0) else null
    }.getOrNull()

    private fun totalBytes(dir: File): Long =
        runCatching { StatFs(dir.path).totalBytes }.getOrDefault(0L)

    private fun availableBytes(dir: File): Long =
        runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    private fun readTextFile(path: String): String? =
        runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun readCpuInfo(key: String): String? =
        runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.substringBefore(':').trim().equals(key, ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun readCpuFreq(node: String): String? =
        runCatching {
            val khz = File("/sys/devices/system/cpu/cpu0/cpufreq/$node").readText().trim().toLong()
            String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        }.getOrNull()

    private fun readCpuFreqFile(file: File): String? = runCatching {
        val khz = file.readText().trim().toLong()
        String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
    }.getOrNull()

    private fun localIpv4Addresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> nic.inetAddresses.toList().map { nic.name to it } }
                .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }
                .map { (name, addr) -> "$name: ${addr.hostAddress}" }
        }.getOrDefault(emptyList())

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val hardware = Build.HARDWARE.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk_gphone") ||
            product.contains("emulator")
    }

    private fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val paths = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
        )
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    private fun batteryStatusText(status: Int): String? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "未知"
        else -> null
    }

    private fun batteryPluggedText(plugged: Int): String? = when (plugged) {
        0 -> "未连接电源"
        BatteryManager.BATTERY_PLUGGED_AC -> "交流充电器"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
        else -> null
    }

    private fun batteryHealthText(health: Int): String? = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
        BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "未知"
        else -> null
    }

    private fun networkTypeText(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙共享"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "其他"
    }

    private class PowerCurveView(context: Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(63, 81, 181)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 225, 235)
            strokeWidth = 1f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 28f
        }
        private var samples: List<Double> = emptyList()

        fun setSamples(values: List<Double>) {
            samples = values
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = 58f
            val top = 20f
            val right = width - 16f
            val bottom = height - 34f
            val chartWidth = (right - left).coerceAtLeast(1f)
            val chartHeight = (bottom - top).coerceAtLeast(1f)
            val maxValue = samples.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

            for (i in 0..4) {
                val y = top + chartHeight * i / 4f
                canvas.drawLine(left, y, right, y, gridPaint)
                val value = maxValue * (4 - i) / 4.0
                canvas.drawText(String.format(Locale.US, "%.1fW", value), 0f, y + 9f, textPaint)
            }
            canvas.drawText("最近 ${samples.size} 秒", left, height - 5f, textPaint)
            if (samples.size < 2) return

            val path = android.graphics.Path()
            samples.forEachIndexed { index, value ->
                val x = left + chartWidth * index / (samples.size - 1).toFloat()
                val y = bottom - (value / maxValue * chartHeight).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }
    }

    companion object {
        private const val STATE_SELECTION = "state_selection"
        private const val SNAPSHOT_PREFS = "device_snapshot"
        private const val SNAPSHOT_JSON = "snapshot_json"
        private const val SNAPSHOT_TIME = "snapshot_time"
        private const val DEVICE_LABEL = "device_label"

        /** 值不超过该长度时用「标签-值」左右排布，超过则上下排布 */
        private const val INLINE_VALUE_MAX_LENGTH = 26

        /** 能力网格列数 */
        private const val FEATURE_GRID_COLUMNS = 2

        // 以下特性常量引入较晚，为避免老系统上的兼容问题直接使用字符串字面量
        private const val FEATURE_TELEPHONY_EUICC = "android.hardware.telephony.euicc"
        private const val FEATURE_TELEPHONY_NR = "android.hardware.telephony.nr"
        private const val FEATURE_FACE = "android.hardware.biometrics.face"

        /** 抽屉菜单项 → 分类 key，null 表示「概览」 */
        private val MENU_KEYS: Map<Int, String?> = mapOf(
            R.id.nav_all to null,
            R.id.nav_device to "device",
            R.id.nav_features to "features",
            R.id.nav_system to "system",
            R.id.nav_cpu to "cpu",
            R.id.nav_memory to "memory",
            R.id.nav_display to "display",
            R.id.nav_battery to "battery",
            R.id.nav_network to "network",
            R.id.nav_media to "media",
            R.id.nav_motion to "motion",
            R.id.nav_sensor to "sensor",
            R.id.nav_camera to "camera",
            R.id.nav_app to "app",
        )
    }
}
