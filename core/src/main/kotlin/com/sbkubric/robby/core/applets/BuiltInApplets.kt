package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.applet.ActionSpec
import com.sbkubric.robby.core.applet.Applet
import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applet.Filter
import com.sbkubric.robby.core.applet.ParamKind
import com.sbkubric.robby.core.applet.ParamSpec
import com.sbkubric.robby.core.applet.TriggerSpec

/** Incoming SMS. The Android receiver lives in `:app`. */
object SmsApplet {
    const val ID = "sms"
    const val INCOMING = "sms.in"
    const val SENDER = "sms.sender"
    const val TEXT = "sms.text"

    val applet = Applet(
        id = ID,
        name = "SMS",
        triggers = listOf(
            TriggerSpec(
                id = INCOMING,
                label = "Входящее SMS",
                params = listOf(
                    ParamSpec("sender", "Отправитель (пусто — любой)", filter = Filter.Equals(SENDER)),
                    ParamSpec("text", "Текст содержит (пусто — любой)", filter = Filter.Contains(TEXT)),
                ),
                variables = listOf(SENDER, TEXT),
            ),
        ),
    )
}

/** Telegram connector: a bot that writes to one group. The HTTP part lives in `:app`. */
object TelegramApplet {
    const val ID = "telegram"
    const val SEND = "telegram.send"
    const val TEXT = "text"

    val applet = Applet(
        id = ID,
        name = "Telegram",
        actions = listOf(
            ActionSpec(SEND, "Отправить сообщение в группу", listOf(ParamSpec(TEXT, "Текст", ParamKind.TEMPLATE))),
        ),
        isConnector = true,
    )
}

object BuiltInApplets {
    val catalog = AppletCatalog(listOf(SystemApplet.applet, BatteryApplet.applet, SmsApplet.applet, TelegramApplet.applet))
}
