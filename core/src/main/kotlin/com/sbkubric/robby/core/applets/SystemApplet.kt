package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.ActionSpec
import com.sbkubric.robby.core.applet.Applet
import com.sbkubric.robby.core.applet.ConditionSpec
import com.sbkubric.robby.core.applet.Filter
import com.sbkubric.robby.core.applet.ParamKind
import com.sbkubric.robby.core.applet.ParamSpec
import com.sbkubric.robby.core.applet.ParamValues
import com.sbkubric.robby.core.applet.TriggerSpec

/**
 * «Система»: the server phone itself — a schedule, its own start, time conditions and a notification on it.
 * The alarm, boot receiver and notification live in `:app`; when a schedule fires next is [SystemSchedule].
 */
object SystemApplet {
    const val ID = "system"

    const val SCHEDULE = "system.schedule"
    const val BOOT = "system.boot"
    const val DAYS = "system.days"
    const val WINDOW = "system.window"
    const val NOTIFY = "system.notify"

    /** Param keys. */
    const val P_DAYS = "days"
    const val P_TIME = "time"
    const val P_FROM = "from"
    const val P_TO = "to"
    const val P_TITLE = "title"
    const val P_TEXT = "text"

    /**
     * Values of a schedule event: the slot it fires for, the weekday (ISO number) and the time `HH:MM` as scheduled.
     * They select the scenarios whose schedule it is; templates do not offer them.
     */
    const val SLOT_DAY = "system.slot.day"
    const val SLOT_TIME = "system.slot.time"

    private val everyDay = ParamValues.weekdays(ParamValues.EVERY_DAY)
    private val workdays = ParamValues.weekdays(ParamValues.WORKDAYS)

    val applet = Applet(
        id = ID,
        name = "Система",
        triggers = listOf(
            TriggerSpec(
                id = SCHEDULE,
                label = "По расписанию",
                params = listOf(
                    ParamSpec(P_DAYS, "Дни", ParamKind.WEEKDAYS, Filter.OnDays(SLOT_DAY), default = everyDay),
                    ParamSpec(P_TIME, "Время (ЧЧ:ММ)", ParamKind.TIME, Filter.AtTime(SLOT_TIME), default = "09:00"),
                ),
            ),
            TriggerSpec(id = BOOT, label = "Телефон-сервер включился"),
        ),
        conditions = listOf(
            ConditionSpec(
                id = DAYS,
                label = "Дни недели",
                params = listOf(ParamSpec(P_DAYS, "Дни", ParamKind.WEEKDAYS, default = workdays)),
            ) { params, state ->
                ParamValues.weekdays(params[P_DAYS].orEmpty())?.contains(state.time.dayOfWeek) ?: false
            },
            ConditionSpec(
                id = WINDOW,
                label = "Окно времени",
                params = listOf(
                    ParamSpec(P_FROM, "С (ЧЧ:ММ)", ParamKind.TIME, default = "09:00"),
                    ParamSpec(P_TO, "До (ЧЧ:ММ)", ParamKind.TIME, default = "18:00"),
                ),
            ) { params, state ->
                val from = ParamValues.time(params[P_FROM].orEmpty())
                val to = ParamValues.time(params[P_TO].orEmpty())
                val now = state.time.toLocalTime()
                when {
                    from == null || to == null -> false
                    from == to -> true
                    from < to -> now >= from && now < to
                    else -> now >= from || now < to // crosses midnight, e.g. 22:00–08:00
                }
            },
        ),
        actions = listOf(
            ActionSpec(
                id = NOTIFY,
                label = "Уведомление на телефоне-сервере",
                params = listOf(
                    ParamSpec(P_TITLE, "Заголовок", ParamKind.TEMPLATE),
                    ParamSpec(P_TEXT, "Текст", ParamKind.TEMPLATE),
                ),
                messageParam = P_TEXT,
            ),
        ),
    )
}
