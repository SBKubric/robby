package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.Applet
import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applet.ConditionSpec
import com.sbkubric.robby.core.applet.Filter
import com.sbkubric.robby.core.applet.ParamSpec
import com.sbkubric.robby.core.applet.TriggerSpec
import com.sbkubric.robby.core.applet.Variable

/**
 * «Звонок»: incoming and missed calls of the server phone, and whether a call is going on.
 * The events come from the phone's call state broadcasts through [CallWatch]; the receiver lives in `:app`.
 */
object CallApplet {
    const val ID = "call"

    const val INCOMING = "call.incoming"
    const val MISSED = "call.missed"

    /** A call is ringing or going on. */
    const val ACTIVE = "call.active"

    /** Variables: the caller's number as the phone reports it, and the contact's name; empty if unknown. */
    const val NUMBER = "call.number"
    const val CONTACT = "call.contact"

    /** Param key. */
    const val P_CALLER = "caller"

    private val variables = listOf(Variable(NUMBER, "+7 900 123-45-67"), Variable(CONTACT, "Иван Петров"))

    private val caller = ParamSpec(P_CALLER, "Номер или контакт (пусто — любой)", filter = Filter.Equals(NUMBER, or = CONTACT))

    val applet = Applet(
        id = ID,
        name = "Звонок",
        triggers = listOf(
            TriggerSpec(INCOMING, "Входящий звонок", listOf(caller), variables),
            TriggerSpec(MISSED, "Пропущенный звонок", listOf(caller), variables),
        ),
        conditions = listOf(
            // Not idle: a waiting call makes the state RINGING although another call goes on.
            ConditionSpec(ACTIVE, "Идёт звонок") { _, state -> state.call != null && state.call != CallState.IDLE },
        ),
    )
}
