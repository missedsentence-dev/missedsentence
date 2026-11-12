package com.missedsentence.car.ui

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.missedsentence.car.Controller
import com.missedsentence.car.content.Pack
import com.missedsentence.car.core.DemoScenario
import com.missedsentence.car.core.Level
import com.missedsentence.car.core.Reason
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 주행 중 화면. 큰 재생 버튼, 진행 막대, 멈췄을 때의 이유 아이콘 하나만 둡니다.
 * 시연 모드에서는 아래에 '시연' 표시가 붙은 띠가 하나 더 나와 신호와 동작을 보여 줍니다.
 */
class DriveScreen(private val a: MainActivity) {

    private val ui = a.ui
    private val c: Controller = a.controller

    private lateinit var title: TextView
    private lateinit var button: PlayButtonView
    private lateinit var icon: ReasonIconView
    private lateinit var progress: ProgressBarView
    private var stripStatus: TextView? = null
    private val stripLines = ArrayList<TextView>()
    private var builtKey = ""
    private var lastHead = ""
    private var lastStatusMs = 0L

    private fun key(): String =
        "${c.scenario?.route?.id}|${c.preview}|${c.virtualSpeed}|${c.engineEnabled}|${c.demoMode}|${c.pack?.id}"

    fun build(): View {
        builtKey = key()
        val root = ui.vbox().apply { setPadding(ui.dp(32), ui.dp(4), ui.dp(32), ui.dp(20)) }

        // 묶음 제목은 시연 모드에서만 보여 줍니다. 평소 주행 화면에는 버튼, 진행 막대, 이유 아이콘만 둡니다.
        title = ui.text(c.pack?.title ?: "", 22f, Palette.SUB, maxLines = 1)
        title.visibility = if (c.demoMode) View.VISIBLE else View.GONE
        root.addView(title, ui.matchWrap())

        val size = a.driveButtonSizeDp()
        val side = (size * 0.5f).roundToInt()
        val center = ui.hbox(12).apply { gravity = Gravity.CENTER }
        center.addView(View(a), ui.fixed(side, side))   // 버튼이 가운데 오도록 맞추는 빈 자리
        button = PlayButtonView(a).apply {
            setOnClickListener {
                c.player.toggle()
                update()
            }
        }
        center.addView(button, ui.fixed(size, size))
        icon = ReasonIconView(a)
        center.addView(icon, ui.fixed(side, side))

        val mainColumn = ui.vbox()
        mainColumn.addView(center, ui.weight(1f, vertical = true))
        progress = ProgressBarView(a)
        mainColumn.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(14)))

        if (!c.demoMode) {
            root.addView(mainColumn, ui.weight(1f, vertical = true))
        } else if (a.isWide) {
            // 넓은 화면에서는 시연 띠를 오른쪽에 세워 재생 버튼 자리를 줄이지 않습니다.
            val body = ui.hbox(24, centerVertical = false)
            body.addView(mainColumn, ui.weight(1.5f))
            body.addView(ui.scroll(buildStrip(lines = 5, lineRows = 2)).apply { tag = "drive-strip" }, ui.weight(1f))
            root.addView(body, ui.weight(1f, vertical = true))
        } else {
            root.addView(mainColumn, ui.weight(1f, vertical = true))
            root.addView(buildStrip(lines = 2, lineRows = 1), ui.matchWrap().apply { topMargin = ui.dp(12) })
        }
        return root
    }

    private fun buildStrip(lines: Int, lineRows: Int): View {
        val card = ui.card(padDp = 18, gapDp = 8)
        val head = ui.hbox(12)
        head.addView(ui.tag("시연", Palette.BG, Palette.WARN))
        head.addView(ui.text("신호 → 동작", 18f, Palette.TEXT, bold = true))
        card.addView(head, ui.matchWrap())
        stripStatus = ui.text("", 17f, Palette.SUB, maxLines = if (lineRows > 1) 5 else 2)
        card.addView(stripStatus, ui.matchWrap())

        stripLines.clear()
        repeat(lines) { i ->
            // 맨 위 줄이 가장 최근 것이라 밝게 둡니다.
            val line = ui.text("", 18f, if (i == 0) Palette.TEXT else Palette.SUB, maxLines = lineRows)
            stripLines.add(line)
            card.addView(line, ui.matchWrap())
        }

        val tools = ui.flow(10, 8)
        tools.addView(ui.text("가상 속도", 17f, Palette.FAINT).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ui.dp(60)
        })
        tools.addView(ui.chip("실제 값", c.virtualSpeed == null) { c.setVirtualSpeed(null); a.render() })
        for (v in VIRTUAL_SPEEDS) {
            tools.addView(ui.chip("시속 ${v.roundToInt()}", c.virtualSpeed == v) { c.setVirtualSpeed(v); a.render() })
        }
        if (c.scenario != null) {
            tools.addView(ui.chip("장면 멈추기", false) { c.endScenario("멈춤"); a.render() })
        }
        if (c.preview) {
            tools.addView(ui.chip("미리보기 끝내기", false) { c.setPreview(false); a.render() })
        }
        card.addView(tools, ui.matchWrap())
        return card
    }

    fun update() {
        if (key() != builtKey) {
            a.render()
            return
        }
        val p = c.player
        button.playing = p.wantPlay
        button.held = p.wantPlay && (p.engineHeld || p.focusLost)
        val kind = c.holdIcon()
        icon.icon = kind
        icon.visibility = if (kind != null) View.VISIBLE else View.INVISIBLE
        progress.fraction = stepFraction(p.progress())

        val status = stripStatus ?: return
        // 가장 최근 것이 맨 위에 오게 보여 줍니다.
        val lines = c.recentLog(stripLines.size).asReversed()
        for (i in stripLines.indices) {
            val l = lines.getOrNull(i)
            stripLines[i].setTextIfChanged(if (l == null) "" else "${l.clock}  ${l.signal}  →  ${l.action}")
        }
        // 거리와 속도 숫자는 계속 바뀌므로 1초에 한 번만 고칩니다. 새 동작이 찍혔을 때는 바로 고칩니다.
        val now = SystemClock.elapsedRealtime()
        val head = lines.firstOrNull()?.let { it.clock + it.action } ?: ""
        if (head != lastHead || now - lastStatusMs >= STATUS_EVERY_MS) {
            lastHead = head
            lastStatusMs = now
            status.setTextIfChanged(stripText())
        }
    }

    private fun stripText(): String {
        val parts = ArrayList<String>()
        if (!c.engineEnabled) parts.add("규칙 끔: 휴대폰을 블루투스로 연결해 들을 때와 같음")
        val sc = c.scenario
        val f = c.frame
        if (sc != null && f != null) {
            parts.add("${sc.route.title} ${f.positionM.roundToInt()}m / ${sc.route.lengthM.roundToInt()}m")
            parts.add("시속 ${f.speedKmh.roundToInt()}km")
            val turn = f.nextTurn
            if (turn != null) parts.add("${turnName(turn.type)}까지 ${turn.distanceM.roundToInt()}m")
            when {
                f.speedLimitKmh <= 30 -> parts.add("어린이보호구역 안")
                f.schoolCautionM != null -> parts.add("어린이보호구역까지 ${f.schoolCautionM.roundToInt()}m")
            }
            if (f.waitingAtStop) parts.add("신호 대기")
        } else {
            val v = c.speedKmh?.roundToInt()
            parts.add(if (c.virtualSpeed != null) "가상 속도 시속 ${v ?: 0}km" else "시속 ${v?.toString() ?: "모름"}km")
            parts.add("기어 ${c.gear ?: "모름"}")
        }
        parts.add(
            when {
                !c.turnKnown && !c.turnLeft && !c.turnRight -> "방향지시등 값 없음"
                c.turnLeft && c.turnRight -> "비상등"
                c.turnLeft -> "방향지시등 왼쪽"
                c.turnRight -> "방향지시등 오른쪽"
                else -> "방향지시등 꺼짐"
            }
        )
        parts.add(
            when (c.brake) {
                true -> "브레이크 밟음"
                false -> "브레이크 뗌"
                null -> "브레이크 값 없음"
            }
        )
        if (c.player.fromQueue) parts.add("다시 들을 문장부터 이어 듣는 중")
        return parts.joinToString("   ")
    }

    private fun turnName(type: com.missedsentence.car.core.TurnType): String = when (type) {
        com.missedsentence.car.core.TurnType.LEFT -> "좌회전"
        com.missedsentence.car.core.TurnType.RIGHT -> "우회전"
        com.missedsentence.car.core.TurnType.UTURN -> "유턴"
        com.missedsentence.car.core.TurnType.MERGE -> "합류"
        com.missedsentence.car.core.TurnType.EXIT -> "진출"
        com.missedsentence.car.core.TurnType.FORK -> "분기"
        else -> "다음 안내"
    }

    companion object {
        val VIRTUAL_SPEEDS = listOf(20f, 60f)
        private const val STATUS_EVERY_MS = 1_000L
    }
}

/**
 * 정차 화면. 기어가 P이고 주행 규제가 풀렸을 때만 보입니다.
 * 출발 제안, 콘텐츠 선택, 민감도, 오늘 멈춘 이유, 다시 들을 문장, 지원 확인 결과, 시연 도구를 둡니다.
 */
class ParkScreen(private val a: MainActivity) {

    private val ui = a.ui
    private val c: Controller = a.controller

    private var nowLine: TextView? = null
    private var sentenceLine: TextView? = null
    private var soundLine: TextView? = null
    private var playButton: TextView? = null
    private var progress: ProgressBarView? = null

    fun build(): View {
        val left = ui.vbox(20)
        val right = ui.vbox(20)
        workCard()?.let { left.addView(it, ui.matchWrap()) }
        left.addView(nowCard(), ui.matchWrap())
        left.addView(packsCard(), ui.matchWrap())
        left.addView(levelCard(), ui.matchWrap())
        right.addView(todayCard(), ui.matchWrap())
        right.addView(relistenCard(), ui.matchWrap())
        right.addView(supportCard(), ui.matchWrap())
        right.addView(demoCard(), ui.matchWrap())

        if (a.isWide) {
            val row = ui.hbox(0, centerVertical = false)
            row.addView(ui.scroll(left, 24).apply { tag = "park-left" }, ui.weight(1f))
            row.addView(ui.scroll(right, 24).apply { tag = "park-right" }, ui.weight(1f))
            return row
        }
        val all = ui.vbox(20)
        all.addView(left, ui.matchWrap())
        all.addView(right, ui.matchWrap())
        return ui.scroll(all, 24).apply { tag = "park-all" }
    }

    /** 0.2초마다 바뀌는 글자만 고칩니다. */
    fun update() {
        val p = c.player
        val pack = c.pack
        playButton?.setTextIfChanged(if (p.wantPlay) "멈춤" else "재생")
        progress?.fraction = stepFraction(p.progress())
        if (pack != null) {
            val n = pack.sentences.size
            val at = (p.index + 1).coerceAtMost(n)
            val state = when {
                !p.wantPlay -> "멈춤"
                p.focusLost -> "다른 앱의 안내 음성이 끝나기를 기다리는 중"
                p.engineHeld -> "잠시 쉬는 중: ${c.holdLabel() ?: ""}"
                p.inRange -> "앞뒤 문장과 함께 다시 듣는 중"
                p.fromQueue -> "다시 들을 문장부터 이어 듣는 중"
                else -> "재생 중"
            }
            nowLine?.setTextIfChanged("${n}문장 가운데 ${at}번째   $state")
            val text = pack.sentences.getOrNull(p.index)?.text ?: ""
            sentenceLine?.setTextIfChanged(text)
            sentenceLine?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            soundLine?.setTextIfChanged("소리: ${p.backend.label}" + if (pack.voice.isNotBlank()) "   ${pack.voice}" else "")
        }
    }

    // ------------------------------------------------------------------ 출발 전

    private fun workCard(): View? {
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val weekdayMorning = day in Calendar.MONDAY..Calendar.FRIDAY && hour in 5..10
        if (!weekdayMorning && !c.demoMode) return null
        val card = ui.card()
        card.addView(ui.section("출발 전", "길안내를 켜면 회전을 미리 알고 문장이 끝나는 자리에서 쉴 수 있어요."))
        card.addView(ui.button("회사로 안내 시작", Ui.Kind.PRIMARY, 22f) { a.startWorkRoute() }, ui.matchWrap())
        if (!weekdayMorning) {
            card.addView(ui.text("평일 아침에만 보이는 제안이에요. 시연 모드라 지금도 보여요.", 16f, Palette.FAINT))
        }
        return card
    }

    // ------------------------------------------------------------------ 지금 듣는 것

    private fun nowCard(): View {
        val card = ui.card()
        val pack = c.pack
        card.addView(ui.section("지금 듣는 것"))
        if (pack == null) {
            card.addView(ui.text("들을 수 있는 콘텐츠가 없어요.", 20f, Palette.SUB))
            return card
        }
        card.addView(ui.text(pack.title, 26f, Palette.TEXT, bold = true))
        nowLine = ui.text("", 19f, Palette.SUB)
        card.addView(nowLine, ui.matchWrap())
        progress = ProgressBarView(a)
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)))
        sentenceLine = ui.text("", 20f, Palette.TEXT)
        card.addView(sentenceLine, ui.matchWrap())

        val row = ui.flow(12, 12)
        playButton = ui.button("재생", Ui.Kind.PRIMARY, 22f) {
            c.player.toggle()
            update()
        }
        row.addView(playButton)
        row.addView(ui.button("한 문장 다시") { c.player.previous(); update() })
        row.addView(ui.button("다음 문장") { c.player.next(); update() })
        card.addView(row, ui.matchWrap())
        soundLine = ui.text("", 16f, Palette.FAINT)
        card.addView(soundLine, ui.matchWrap())
        return card
    }

    // ------------------------------------------------------------------ 콘텐츠

    private fun packsCard(): View {
        val card = ui.card()
        card.addView(ui.section("콘텐츠", "문장 단위로 나눈 묶음이에요. 어학은 다시 들을 문장부터 이어 듣고, 이야기는 정차한 뒤 앞뒤 문장과 함께 다시 들어요."))
        for (p in c.packs) {
            // 문장이 없는 묶음(직접 녹음을 넣지 않은 자리)은 목록에 보이지 않게 합니다.
            if (p.isEmpty) continue
            val selected = p.id == c.pack?.id
            val box = ui.vbox(4).apply {
                setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
                minimumHeight = ui.dp(72)
                val base = if (selected) ui.round(Palette.ACCENT_SOFT, 16, Palette.ACCENT, 2) else ui.round(Palette.SURFACE_2, 16, Palette.LINE)
                background = if (p.isEmpty) base else ui.pressable(base)
            }
            val head = ui.hbox(10)
            head.addView(ui.text(p.title, 21f, if (p.isEmpty) Palette.FAINT else Palette.TEXT, bold = true))
            head.addView(ui.tag(p.kind.label))
            if (selected) head.addView(ui.tag("듣는 중", Palette.OK, Palette.OK_SOFT))
            box.addView(head)
            val sub = when {
                p.isEmpty -> "아직 비어 있어요. ${p.description}"
                else -> "${p.sentences.size}문장   ${p.description}"
            }
            box.addView(ui.text(sub, 17f, Palette.SUB))
            if (!p.isEmpty) {
                box.isClickable = true
                box.setOnClickListener {
                    c.selectPack(p.id)
                    a.render()
                }
            }
            card.addView(box, ui.matchWrap())
        }
        return card
    }

    // ------------------------------------------------------------------ 민감도

    private fun levelCard(): View {
        val card = ui.card()
        card.addView(ui.section("민감도", "우회전과 어린이보호구역은 어느 단계에서나 똑같이 쉬어요."))
        val row = ui.flow(12, 12)
        for (l in Level.values()) {
            row.addView(ui.chip(l.label, c.level == l) {
                c.setLevel(l)
                a.render()
            })
        }
        card.addView(row, ui.matchWrap())
        val text = when (c.level) {
            Level.NORMAL -> "보통: 좌회전, 유턴, 합류, 진출은 10초 전에 미리 미루고, 차로 변경은 30분에 2번까지 멈춰요."
            Level.LOW -> "낮음: 좌회전, 유턴, 합류, 진출은 8초 전에 미루고, 차로 변경에서는 멈추지 않아요."
        }
        card.addView(ui.text(text, 18f, Palette.SUB))
        return card
    }

    // ------------------------------------------------------------------ 오늘 멈춘 이유

    private fun todayCard(): View {
        val t = c.today
        val card = ui.card()
        card.addView(ui.section("오늘 멈춘 이유", "위치는 저장하지 않고, 멈춘 이유의 종류와 횟수만 차 안에 남겨요."))
        val row = ui.hbox(12, centerVertical = false)
        row.addView(stat("회전", "${t.turns}번"), ui.weight(1f))
        row.addView(stat("보행자 순간", "${t.pedestrian}번"), ui.weight(1f))
        row.addView(stat("조용했던 시간", duration(t.quietMs)), ui.weight(1f))
        card.addView(row, ui.matchWrap())
        if (t.total == 0 && t.focusPauses == 0 && t.budgetSkips == 0) {
            card.addView(ui.text("아직 멈춘 순간이 없어요.", 18f, Palette.SUB))
            return card
        }
        val lines = ArrayList<String>()
        lines.add("우회전 ${t.count(Reason.RIGHT_TURN)}번, 어린이보호구역 ${t.count(Reason.SCHOOL_ZONE)}번, 차로 변경 ${t.count(Reason.LANE_CHANGE)}번, 급제동 ${t.count(Reason.HARD_BRAKE)}번")
        lines.add("문장이 끝나는 자리에서 미룬 것 ${t.boundary}번, 문장 중간에 끊긴 것 ${t.cuts}번")
        lines.add("신호 대기 중에 다시 들은 것 ${t.waiting}번, 안내 음성 때문에 멈춘 것 ${t.focusPauses}번")
        lines.add("예산을 넘어 멈추지 않고 지나간 차로 변경 ${t.budgetSkips}번")
        val total = t.listenedMs + t.quietMs
        if (total > 0) {
            val share = (t.quietMs * 1000 / total) / 10.0
            lines.add("들은 시간 ${duration(t.listenedMs)}, 조용했던 비율 ${share}%")
        }
        for (l in lines) card.addView(ui.text(l, 18f, Palette.SUB))
        return card
    }

    private fun stat(label: String, value: String): View = ui.vbox(2).apply {
        setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        background = ui.round(Palette.SURFACE_2, 16)
        addView(ui.text(value, 30f, Palette.TEXT, bold = true))
        addView(ui.text(label, 17f, Palette.SUB))
    }

    private fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초"
    }

    // ------------------------------------------------------------------ 다시 들을 문장

    private fun relistenCard(): View {
        val card = ui.card()
        val pack = c.pack
        card.addView(ui.section("다시 들을 문장", "멈추지 않고 지나간 순간에 나오던 문장이에요. 중간에 끊긴 문장은 처음부터 다시 나오므로 여기에 넣지 않아요."))
        if (pack == null) return card
        val items = c.relistenItems(pack.id)
        if (items.isEmpty()) {
            card.addView(ui.text("지금은 없어요.", 18f, Palette.SUB))
            return card
        }
        if (pack.kind == Pack.Kind.LANGUAGE) {
            card.addView(ui.text("다음 출발 때 이 문장부터 자동으로 이어 들어요.", 18f, Palette.OK))
        }
        for (item in items) {
            val box = ui.vbox(8).apply {
                setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
                background = ui.round(Palette.SURFACE_2, 16)
            }
            val head = ui.hbox(10)
            head.addView(ui.text("${item.index + 1}번째 문장", 20f, Palette.TEXT, bold = true))
            head.addView(ui.tag(item.reason.label))
            box.addView(head)
            val text = pack.sentences.getOrNull(item.index)?.text ?: ""
            if (text.isNotBlank()) box.addView(ui.text(text, 18f, Palette.SUB))
            val row = ui.flow(10, 10)
            if (pack.kind == Pack.Kind.STORY) {
                row.addView(ui.button("앞뒤 문장과 함께 듣기", Ui.Kind.PRIMARY) {
                    c.listenAround(item.index)
                    update()
                })
            }
            row.addView(ui.button("지우기", Ui.Kind.GHOST) {
                c.removeRelisten(pack.id, item.index)
                a.render()
            })
            box.addView(row)
            card.addView(box, ui.matchWrap())
        }
        if (pack.kind == Pack.Kind.LANGUAGE) {
            card.addView(ui.button("지금 이어 듣기") {
                c.queueRelisten()
                if (!c.player.wantPlay) c.player.play()
                update()
            }, ui.matchWrap())
        }
        return card
    }

    // ------------------------------------------------------------------ 지원 확인 결과

    private fun supportCard(): View {
        val card = ui.card()
        card.addView(ui.section("지원 확인 결과", "시작할 때 이 차에서 쓸 수 있는 신호를 확인하고, 없는 신호의 규칙은 꺼요."))
        card.addView(ui.button(if (a.showSupport) "접기" else "펼쳐 보기", Ui.Kind.GHOST) {
            a.showSupport = !a.showSupport
            a.render()
        }, ui.wrap())
        if (!a.showSupport) return card
        for (row in c.supportRows()) {
            val line = ui.hbox(12)
            val (label, color, bg) = when (row.ok) {
                true -> Triple("사용", Palette.OK, Palette.OK_SOFT)
                false -> Triple("없음", Palette.DANGER, Palette.DANGER_SOFT)
                null -> Triple("확인 중", Palette.WARN, Palette.WARN_SOFT)
            }
            line.addView(ui.tag(label, color, bg))
            val texts = ui.vbox(2)
            texts.addView(ui.text(row.name, 19f, Palette.TEXT, bold = true))
            texts.addView(ui.text(row.detail, 16f, Palette.SUB, maxLines = 2))
            line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(line, ui.matchWrap())
            if (row.name == "속도" && row.ok == false) {
                card.addView(ui.button("차량 속도 권한 다시 요청") { a.requestNeededPermissions() }, ui.wrap())
            }
        }
        card.addView(ui.divider())
        for (note in c.ruleNotes()) card.addView(ui.text(note, 17f, Palette.SUB))
        return card
    }

    // ------------------------------------------------------------------ 시연 도구

    private fun demoCard(): View {
        val card = ui.card()
        card.addView(ui.section("시연 도구", "길안내 없이도 장면을 보여 줄 수 있게, 정해 둔 출근길이 속도와 내비 정보를 대신 넣어요. 방향지시등, 브레이크, 조향, 기어는 Virtual Console 값이 그대로 함께 쓰여요."))
        val mode = ui.flow(12, 12)
        mode.addView(ui.chip("시연 모드 켬", c.demoMode) { c.setDemoMode(true); a.render() })
        mode.addView(ui.chip("끔", !c.demoMode) { c.setDemoMode(false); a.render() })
        card.addView(mode, ui.matchWrap())
        if (!c.demoMode) return card

        card.addView(ui.text("장면", 19f, Palette.TEXT, bold = true))
        val scenes = ui.flow(12, 12)
        for (route in DemoScenario.all()) {
            scenes.addView(ui.button(route.title) {
                c.startScene(route)
                a.render()
            })
        }
        card.addView(scenes, ui.matchWrap())
        card.addView(ui.text("장면을 시작하면 재생이 켜지고 주행 화면으로 바뀌어요. 끝나면 이 화면으로 돌아와요.", 16f, Palette.FAINT))

        card.addView(ui.text("비교", 19f, Palette.TEXT, bold = true))
        val compare = ui.flow(12, 12)
        compare.addView(ui.chip("놓친문장 규칙 켬", c.engineEnabled) { c.setEngineEnabled(true); a.render() })
        compare.addView(ui.chip("규칙 끔", !c.engineEnabled) { c.setEngineEnabled(false); a.render() })
        card.addView(compare, ui.matchWrap())
        card.addView(ui.text("규칙을 끄면 휴대폰을 블루투스로 연결해 들을 때처럼 어떤 순간에도 계속 재생해요.", 16f, Palette.FAINT))

        card.addView(ui.text("가상 속도", 19f, Palette.TEXT, bold = true))
        val speeds = ui.flow(12, 12)
        speeds.addView(ui.chip("실제 값", c.virtualSpeed == null) { c.setVirtualSpeed(null); a.render() })
        for (v in DriveScreen.VIRTUAL_SPEEDS) {
            speeds.addView(ui.chip("시속 ${v.roundToInt()}", c.virtualSpeed == v) { c.setVirtualSpeed(v); a.render() })
        }
        card.addView(speeds, ui.matchWrap())
        card.addView(ui.text("기본 모드 장면용이에요. 시속 20에서 방향지시등을 켜면 회전으로, 시속 60에서 켜면 차로 변경으로 판단해요. 주행 화면의 시연 띠에서도 바꿀 수 있어요.", 16f, Palette.FAINT))

        val tools = ui.flow(12, 12)
        tools.addView(ui.button("주행 화면 미리보기") { c.setPreview(true); a.render() })
        tools.addView(ui.button("처음 문장부터 다시 찍기", Ui.Kind.GHOST) {
            c.resetForRetake()
            a.render()
            a.toast("처음 문장으로 돌아가고 오늘 기록을 지웠어요")
        })
        card.addView(tools, ui.matchWrap())

        card.addView(ui.divider())
        card.addView(ui.button(if (a.showRawNav) "내비 원문 기록 접기" else "내비 원문 기록 보기", Ui.Kind.GHOST) {
            a.showRawNav = !a.showRawNav
            a.render()
        }, ui.wrap())
        if (a.showRawNav) {
            val raw = c.navi.rawLines()
            if (raw.isEmpty()) {
                card.addView(ui.text("아직 받은 값이 없어요. 내비에서 길안내를 시작하면 회전 안내, 주의 지점, 제한속도의 원문이 여기에 남아요. 위치는 남기지 않아요.", 17f, Palette.SUB))
            } else {
                for (line in raw.takeLast(12)) card.addView(ui.text(line, 15f, Palette.SUB, maxLines = 6))
            }
        }
        return card
    }
}
