package com.missedsentence.car.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.missedsentence.car.core.NavSink
import com.missedsentence.car.core.NextTurn
import com.missedsentence.car.core.TurnParsing
import com.missedsentence.car.core.TurnType

/**
 * Pleos NaviHelper SDK 연동.
 * 회전 안내, 주의 지점, 제한속도, 현재 위치를 받아 [NavSink]로 넘깁니다.
 * 자료형의 필드 이름을 문서로 확인하지 못해, 이름이 비슷한 getter를 찾아 읽고 원문을 화면 기록에 남깁니다.
 * 위치는 메모리에서 비교에만 쓰고 저장하지 않습니다. 모든 호출은 메인 스레드에서 합니다.
 */
class NaviBridge(context: Context, private val sink: NavSink) : NaviHelperEventListener {

    data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val poiId: String,
        val poiSubId: String,
        val address: String,
        val source: String
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var bookmarks: List<Place> = emptyList()
        private set
    @Volatile var recents: List<Place> = emptyList()
        private set

    /** 시연 출근길이 내비 정보를 대신 넣는 동안에는 실제 이벤트를 넘기지 않습니다. */
    @Volatile var muted = false

    /** 즐겨찾기나 최근 목적지 목록이 도착하면 알려 줍니다. */
    var onPlacesChanged: (() -> Unit)? = null

    private val rawLog = ArrayDeque<String>()
    private val rawSeen = HashMap<String, String>()

    /** 화면의 '내비 원문 기록'에 보여 줄 최근 원문. 종류별로 값이 바뀔 때만 남깁니다. */
    fun rawLines(): List<String> = synchronized(rawLog) { rawLog.toList() }

    fun connect() = onMain {
        if (helper != null) return@onMain
        var created: NaviHelper? = null
        try {
            created = NaviHelper(appContext)
            created.initialize()
            created.addListener(this)
            helper = created
            connected = true
            lastError = null
            created.getCurrentLocationInfo()
        } catch (t: Throwable) {
            runCatching { created?.release() }
            helper = null
            connected = false
            lastError = describe(t)
        }
    }

    fun release() = onMain {
        val h = helper ?: return@onMain
        runCatching {
            h.removeListener(this)
            h.release()
        }
        helper = null
        connected = false
    }

    /** 회전 안내와 현재 위치를 다시 묻습니다. 2초에 한 번 부릅니다. */
    fun poll() = call {
        it.getTBTInfo()
        it.getCurrentLocationInfo()
    }

    fun requestPlaces() = call {
        it.getBookmarkInfo()
        it.getRecentDestinationInfo()
    }

    /** 즐겨찾기에서 회사로 보이는 곳 */
    fun workPlace(): Place? = bookmarks.firstOrNull { p ->
        val n = p.name.lowercase()
        WORK_WORDS.any { n.contains(it) }
    }

    /** 길안내를 시작합니다. 성공 여부는 내비게이션 앱의 이벤트로 확인됩니다. */
    fun routeTo(place: Place): Boolean {
        val h = helper ?: return false
        return try {
            h.requestRoute(
                RouteInfo(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    poiId = place.poiId,
                    poiName = place.name,
                    poiSubId = place.poiSubId,
                    address = place.address
                )
            )
            true
        } catch (t: Throwable) {
            lastError = describe(t)
            false
        }
    }

    private fun call(action: (NaviHelper) -> Unit) = onMain {
        val h = helper ?: return@onMain
        try {
            action(h)
        } catch (t: Throwable) {
            lastError = describe(t)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: ""}".trim().take(140)

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun remember(kind: String, raw: String) {
        val text = raw.take(400)
        synchronized(rawLog) {
            if (rawSeen[kind] == text) return
            rawSeen[kind] = text
            rawLog.addLast("$kind: $text")
            while (rawLog.size > MAX_RAW) rawLog.removeFirst()
        }
    }

    // ---- 자료형의 필드 이름이 SDK 버전마다 달라도 동작하도록 getter를 이름으로 찾습니다. ----

    private fun toPlace(item: Any, source: String): Place? {
        val lat = readDouble(item, "getLatitude", "getLat") ?: return null
        val lon = readDouble(item, "getLongitude", "getLon", "getLng") ?: return null
        val name = readString(item, "getPoiName", "getName", "getTitle") ?: return null
        return Place(
            name = name,
            latitude = lat,
            longitude = lon,
            poiId = readString(item, "getPoiId") ?: "",
            poiSubId = readString(item, "getPoiSubId") ?: "0",
            address = readString(item, "getAddress", "getRoadAddress") ?: "",
            source = source
        )
    }

    private fun readDouble(target: Any, vararg getters: String): Double? {
        for (g in getters) {
            val v = runCatching { target.javaClass.getMethod(g).invoke(target) }.getOrNull()
            if (v is Number && v.toDouble().isFinite()) return v.toDouble()
        }
        return null
    }

    private fun readString(target: Any, vararg getters: String): String? {
        for (g in getters) {
            val v = runCatching { target.javaClass.getMethod(g).invoke(target) }.getOrNull()
            if (v is CharSequence && v.isNotBlank()) return v.toString()
        }
        return null
    }

    /** 인자 없는 getter를 모두 읽어 이름과 값으로 돌려줍니다. */
    private fun getters(target: Any): List<Pair<String, Any?>> =
        target.javaClass.methods
            .filter { it.parameterTypes.isEmpty() && it.name.startsWith("get") && it.name != "getClass" }
            .sortedBy { it.name }
            .map { m -> m.name.removePrefix("get").lowercase() to runCatching { m.invoke(target) }.getOrNull() }

    /** 남은 거리(m)로 보이는 숫자 값 */
    private fun distanceOf(fields: List<Pair<String, Any?>>): Float? {
        for ((name, value) in fields) {
            if (value !is Number) continue
            if (DISTANCE_WORDS.any { name.contains(it) } && value.toDouble().isFinite() && value.toDouble() >= 0) {
                return value.toFloat()
            }
        }
        return null
    }

    /** 회전 종류나 구역 종류로 보이는 글자 값을 모읍니다. */
    private fun kindText(fields: List<Pair<String, Any?>>): String =
        fields.filter { (name, value) ->
            value != null && value !is Number && value !is Boolean && KIND_WORDS.any { name.contains(it) }
        }.joinToString(" ") { it.second.toString() }

    // ---- NaviHelperEventListener ----

    override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
        remember("회전 안내", tbtInfoList.toString())
        if (muted) return
        val first = tbtInfoList.firstOrNull()
        if (first == null) {
            sink.onNextTurn(null, now())
            return
        }
        val fields = getters(first)
        val distance = distanceOf(fields)
        var type = TurnParsing.typeFrom(kindText(fields))
        if (type == TurnType.OTHER) type = TurnParsing.typeFrom(first.toString())
        if (distance == null) {
            // 거리를 읽지 못하면 미리 미룰 수 없습니다. 길안내 중이라는 사실만 남깁니다.
            sink.onNextTurn(NextTurn(TurnType.OTHER, Float.MAX_VALUE), now())
            return
        }
        sink.onNextTurn(NextTurn(type, distance), now())
    }

    override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
        remember("주의 지점", cautionPointList.toString())
        if (muted) return
        var nearest: Float? = null
        var found = false
        for (point in cautionPointList) {
            val fields = getters(point)
            val text = kindText(fields).ifBlank { point.toString() }
            if (!TurnParsing.isSchoolZone(text) && !TurnParsing.isSchoolZone(point.toString())) continue
            found = true
            val d = distanceOf(fields)
            if (d != null && (nearest == null || d < nearest)) nearest = d
        }
        if (found) sink.onSchoolCaution(nearest, now())
    }

    override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
        remember("제한속도", speedLimit.toString())
        if (muted) return
        sink.onSpeedLimit(speedLimit.takeIf { it in 10..200 }, now())
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        if (muted) return
        // 위치는 어린이보호구역 좌표와 비교하는 데만 쓰고, 기록에도 남기지 않습니다.
        val lat = readDouble(currentLocationInfo, "getLatitude", "getLat")
        val lon = readDouble(currentLocationInfo, "getLongitude", "getLon", "getLng")
        sink.onLocation(lat, lon, now())
    }

    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
        val places = bookmarkInfoList.mapNotNull { toPlace(it, "즐겨찾기") }
        if (places != bookmarks) {
            bookmarks = places
            onPlacesChanged?.invoke()
        }
    }

    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
        val places = recentDestinationInfoList.mapNotNull { toPlace(it, "최근 목적지") }
        if (places != recents) {
            recents = places
            onPlacesChanged?.invoke()
        }
    }

    override fun onError(errorCode: NaviErrorCode) {
        lastError = "NaviErrorCode: $errorCode"
    }

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
        remember("길안내 시작", routeStartInfo.toString())
    }

    @Deprecated("Deprecated by NaviHelperEventListener")
    override fun onRouteEnded() {
        if (!muted) sink.onRouteEnded(now())
    }

    override fun onRoutingCompleted() {}

    override fun onReRouteEnded() {}

    override fun onRouteOptionChanged(routeOption: RouteOption) {}

    override fun onRouteCancelled() {
        if (!muted) sink.onRouteEnded(now())
    }

    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {}

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        if (!muted) sink.onRouteEnded(now())
    }

    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {}

    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {}

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {}

    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {}

    override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {}

    override fun onDrivingInfo(drivingInfo: DrivingInfo) {}

    override fun onBookMarkChanged() {}

    override fun onDestinationChanged() {}

    override fun onNavigationStatus(status: NavigationStatus) {
        remember("내비 상태", status.toString())
    }

    override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) {}

    override fun onTripInfo(tripInfo: TripInfo) {}

    companion object {
        private const val MAX_RAW = 30
        private val WORK_WORDS = listOf("회사", "직장", "사무실", "work", "office")
        private val DISTANCE_WORDS = listOf("distance", "dist", "remain", "meter")
        private val KIND_WORDS = listOf(
            "turn", "type", "direction", "guid", "maneuver", "point", "icon", "code", "name", "desc", "kind", "category"
        )

        /** 공식 NaviHelper 샘플 앱에 들어 있는 목적지. 즐겨찾기에 회사가 없을 때 시연용으로 씁니다. */
        val DEMO_PLACES = listOf(
            Place(
                name = "소프트웨어드림센터",
                latitude = 37.41275833,
                longitude = 127.09481944,
                poiId = "36khp2hdj2cfcm5nqhe3",
                poiSubId = "0",
                address = "경기도 성남시 수정구 창업로40번길 6",
                source = "시연용"
            ),
            Place(
                name = "42dot",
                latitude = 37.48544722,
                longitude = 127.03636666,
                poiId = "36khp37hh8gqcm5nqhe5",
                poiSubId = "0",
                address = "서울시 강남구 남부순환로 2621",
                source = "시연용"
            )
        )
    }
}
