package com.missedsentence.car.vehicle

import android.content.Context
import android.os.Handler
import com.missedsentence.car.core.NavSink

/** 검사에서 Virtual Console 대신 차량 값을 바꾸는 자리 */
object FakeCar {
    var speed: Float? = 0f
    var gear: String? = "P"
    var steer: Float? = 0f
    var ux: Boolean? = false
    var screenAllowed: Boolean? = true
    val values = HashMap<String, String>()
    var lastNavi: NaviBridge? = null

    fun reset() {
        speed = 0f
        gear = "P"
        steer = 0f
        ux = false
        screenAllowed = true
        values.clear()
        values["TurnSignal.getCurrentTurnSignalState"] = "OFF"
        values["Brake.getCurrentBrakePedalPressed"] = "false"
    }

    fun turn(state: String) {
        values["TurnSignal.getCurrentTurnSignalState"] = state
    }

    fun brake(on: Boolean) {
        values["Brake.getCurrentBrakePedalPressed"] = on.toString()
    }
}

class CarSignals(context: Context) {
    val available = true
    val lastError: String? = null
    fun connect() {}
    fun speedKmh(): Float? = FakeCar.speed
    fun gear(): String? = FakeCar.gear
    fun steeringDeg(): Float? = FakeCar.steer
    fun uxRestricted(): Boolean? = FakeCar.ux
    fun screenAllowedWhileDriving(packageName: String, className: String): Boolean? = FakeCar.screenAllowed
}

class VehicleProbe(context: Context, handler: Handler) {
    data class Stats(val connected: Boolean, val probes: Int, val responding: Int, val unsupported: Int, val initError: String?)
    data class DiagRow(val key: String, val value: String?, val error: String?)

    fun start() {}
    fun snapshot(): Map<String, String> = HashMap(FakeCar.values)
    fun stats() = Stats(true, FakeCar.values.size, FakeCar.values.size, 0, null)
    fun diagnostics(): List<DiagRow> = FakeCar.values.toSortedMap().map { DiagRow(it.key, it.value, null) }
}

class NaviBridge(context: Context, val sink: NavSink) {
    data class Place(
        val name: String, val latitude: Double, val longitude: Double,
        val poiId: String, val poiSubId: String, val address: String, val source: String
    )

    var connected = true
    var lastError: String? = null
    var muted = false
    var onPlacesChanged: (() -> Unit)? = null
    var bookmarks: List<Place> = emptyList()
    var recents: List<Place> = emptyList()
    var polls = 0

    init {
        FakeCar.lastNavi = this
    }

    fun connect() {}
    fun poll() {
        polls++
    }
    fun requestPlaces() {}
    fun workPlace(): Place? = bookmarks.firstOrNull { it.name.contains("회사") }
    fun routeTo(place: Place): Boolean = true
    fun rawLines(): List<String> = emptyList()

    companion object {
        val DEMO_PLACES = emptyList<Place>()
    }
}
