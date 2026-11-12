package com.missedsentence.car.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.missedsentence.car.Controller
import com.missedsentence.car.MissedApp
import com.missedsentence.car.vehicle.NaviBridge
import kotlin.math.roundToInt

/**
 * 화면 하나로 이루어진 앱.
 * 주행 중에는 큰 재생 버튼, 진행 막대, 이유 아이콘만 보여 주고,
 * 기어가 P이고 주행 규제가 풀리면 콘텐츠 선택, 오늘 멈춘 이유, 다시 들을 문장을 보여 줍니다.
 */
class MainActivity : Activity() {

    lateinit var ui: Ui
        private set
    lateinit var controller: Controller
        private set

    /** 정차 화면에서 지원 확인 결과와 내비 원문 기록을 펼쳤는지 */
    var showSupport = false
    var showRawNav = false

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var statusMode: TextView
    private lateinit var statusLevel: TextView
    private lateinit var statusDemo: TextView
    private lateinit var statusDrive: TextView

    private var shownPark: Boolean? = null
    private var shownRouteMode: Boolean? = null
    private var wasBlocked = false
    private var shownStructure = -1
    private var driveScreen: DriveScreen? = null
    private var parkScreen: ParkScreen? = null
    private var lastRender = 0L
    private var lastPlacesRequest = 0L

    private val tickListener: () -> Unit = { onTick() }

    val isWide: Boolean
        get() = resources.configuration.screenWidthDp >= 900

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        controller = MissedApp.controller(this)
        buildShell()
        setContentView(root)
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        controller.addTickListener(tickListener)
        render()
    }

    override fun onStop() {
        controller.removeTickListener(tickListener)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private fun buildShell() {
        root = ui.vbox().apply { setBackgroundColor(Palette.BG) }
        val header = ui.hbox(14).apply { setPadding(ui.dp(32), ui.dp(18), ui.dp(32), ui.dp(10)) }
        header.addView(ui.text("놓친문장", 28f, Palette.TEXT, bold = true))
        statusDemo = ui.tag("시연", Palette.BG, Palette.WARN)
        statusLevel = ui.tag("")
        statusMode = ui.tag("")
        statusDrive = ui.tag("")
        header.addView(statusDemo)
        header.addView(statusLevel)
        header.addView(statusMode)
        header.addView(statusDrive)
        content = FrameLayout(this)
        root.addView(header, ui.matchWrap())
        root.addView(content, ui.weight(1f, vertical = true))
    }

    private fun onTick() {
        val c = controller
        // 차량이 화면을 가린 동안에는 보이지 않는 화면을 고쳐 그리지 않습니다. 풀리면 한 번에 다시 그립니다.
        if (c.screenBlocked) {
            wasBlocked = true
            return
        }
        if (wasBlocked) {
            wasBlocked = false
            shownPark = null
        }
        val park = c.showPark
        statusDemo.visibility = if (c.demoMode) View.VISIBLE else View.GONE
        // 주행 중에는 지금 단계만 보여 줍니다. 모드와 기어 표시는 정차 화면과 시연 모드에서만 나옵니다.
        val detail = if (park || c.demoMode) View.VISIBLE else View.GONE
        statusMode.visibility = detail
        statusDrive.visibility = detail
        statusLevel.setTextIfChanged("민감도 ${c.level.label}")
        statusMode.setTextIfChanged(if (c.routeMode) "경로 모드" else "기본 모드")
        if (shownRouteMode != c.routeMode) {
            shownRouteMode = c.routeMode
            statusMode.setTextColor(if (c.routeMode) Palette.OK else Palette.SUB)
        }
        statusDrive.setTextIfChanged(
            when {
                park -> "정차  기어 ${c.gear ?: "모름"}"
                c.scenario != null -> "시연 주행"
                c.preview -> "주행 화면 미리보기"
                else -> "주행 중  기어 ${c.gear ?: "모름"}"
            }
        )

        val now = SystemClock.elapsedRealtime()
        val stale = park && (showSupport || showRawNav) && now - lastRender > 3_000
        if (shownPark != park || (park && shownStructure != c.structure) || stale) {
            render()
        } else if (park) {
            parkScreen?.update()
        } else {
            driveScreen?.update()
        }
    }

    fun render() {
        if (!::content.isInitialized) return
        val c = controller
        val positions = HashMap<String, Int>()
        collectScroll(content, positions)
        content.removeAllViews()
        val park = c.showPark
        shownPark = park
        shownStructure = c.structure
        lastRender = SystemClock.elapsedRealtime()
        val view: View = if (park) {
            driveScreen = null
            maybeRequestPlaces()
            ParkScreen(this).also { parkScreen = it }.build()
        } else {
            parkScreen = null
            DriveScreen(this).also { driveScreen = it }.build()
        }
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (positions.isNotEmpty()) content.post { applyScroll(content, positions) }
        if (park) parkScreen?.update() else driveScreen?.update()
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 즐겨찾기와 최근 목적지는 1분에 한 번만 다시 묻습니다. */
    private fun maybeRequestPlaces(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastPlacesRequest > 60_000) {
            lastPlacesRequest = now
            controller.navi.requestPlaces()
        }
    }

    /** '회사로 안내 시작'. 즐겨찾기에 회사가 있으면 바로, 없으면 목적지를 묻습니다. */
    fun startWorkRoute() {
        val navi = controller.navi
        if (!navi.connected) {
            toast("내비게이션과 연결되지 않았어요. ${navi.lastError ?: ""}".trim())
            return
        }
        val place = controller.routeToWork()
        if (place != null) {
            toast("${place.name}까지 길안내를 요청했어요")
            return
        }
        maybeRequestPlaces(force = true)
        val places = (navi.bookmarks + navi.recents + NaviBridge.DEMO_PLACES).distinctBy { it.name to it.address }
        val labels = places.map { "${it.name}\n${it.source}   ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("즐겨찾기에 회사가 없어요. 어디로 안내할까요?")
            .setItems(labels) { _, which ->
                val target = places[which]
                val ok = navi.routeTo(target)
                toast(if (ok) "${target.name}까지 길안내를 요청했어요" else "길안내를 시작하지 못했어요. ${navi.lastError ?: ""}".trim())
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    fun driveButtonSizeDp(): Int {
        val h = resources.configuration.screenHeightDp
        return (h * 0.42f).roundToInt().coerceIn(200, 380)
    }

    /** 차량 속도와 알림 권한을 요청합니다. 지원 확인 결과 카드에서도 다시 부를 수 있습니다. */
    fun requestNeededPermissions() {
        val wanted = ArrayList<String>()
        wanted += "android.car.permission.CAR_SPEED"
        wanted += "android.car.permission.READ_CAR_STEERING"
        if (Build.VERSION.SDK_INT >= 33) wanted += "android.permission.POST_NOTIFICATIONS"
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun collectScroll(view: View, out: MutableMap<String, Int>) {
        if (view is ScrollView) {
            val key = view.tag as? String
            if (key != null) out[key] = view.scrollY
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectScroll(view.getChildAt(i), out)
        }
    }

    private fun applyScroll(view: View, positions: Map<String, Int>) {
        if (view is ScrollView) {
            val key = view.tag as? String
            val y = if (key != null) positions[key] else null
            if (y != null) view.scrollTo(0, y)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyScroll(view.getChildAt(i), positions)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 7
    }
}
