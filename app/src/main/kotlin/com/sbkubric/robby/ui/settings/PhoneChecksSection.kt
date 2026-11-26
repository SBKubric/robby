package com.sbkubric.robby.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sbkubric.robby.R
import com.sbkubric.robby.checks.ServerPhoneChecks
import com.sbkubric.robby.ui.BadgeTone
import com.sbkubric.robby.ui.StatusBadge
import com.sbkubric.robby.ui.appContainer

/**
 * The checks as they are now: read again whenever the app comes back to the screen (from a system screen or the
 * permission dialog) and whenever the scenarios change.
 */
@Composable
fun rememberPhoneChecks(): List<ServerPhoneChecks.Result> {
    val container = appContainer()
    val scenarios by remember { container.scenarios.observeAll() }.collectAsState(initial = emptyList())
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    return remember(scenarios, resumed) { container.phoneChecks.evaluate(scenarios) }
}

/** The list under «Проверки телефона-сервера»: each check, and for each problem the button that fixes it. */
@Composable
fun PhoneChecksList(checks: List<ServerPhoneChecks.Result>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val phoneChecks = appContainer().phoneChecks
    // The result needs no handling: coming back from the dialog re-reads every check.
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { result ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(result.check.title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusBadge(
                            stringResource(if (result.ok) R.string.check_ok else R.string.check_problem),
                            if (result.ok) BadgeTone.GOOD else BadgeTone.BAD,
                        )
                    }
                    Text(
                        if (result.ok) stringResource(result.check.ok) else problemText(context, result),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    val fix = result.fix
                    if (!result.ok && fix != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                when (fix) {
                                    is ServerPhoneChecks.Fix.Request -> request.launch(fix.permissions.toTypedArray())
                                    is ServerPhoneChecks.Fix.Open -> context.openOr(fix.intent, phoneChecks.appDetails())
                                }
                            }) { Text(stringResource(R.string.check_fix)) }
                            if (fix is ServerPhoneChecks.Fix.Request) {
                                TextButton(onClick = { context.openOr(phoneChecks.appDetails(), phoneChecks.appDetails()) }) {
                                    Text(stringResource(R.string.permission_open_settings))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun problemText(context: Context, result: ServerPhoneChecks.Result): String {
    if (result.check != ServerPhoneChecks.Check.PERMISSIONS) return stringResource(result.check.problem)
    val pm = context.packageManager
    val labels = result.missingPermissions.map { permission ->
        runCatching { pm.getPermissionInfo(permission, 0).loadLabel(pm).toString() }.getOrDefault(permission.substringAfterLast('.'))
    }
    return stringResource(result.check.problem, labels.joinToString(", "))
}

/** Opens a system screen; some firmware lacks a screen, then the app's own page is the way there. */
private fun Context.openOr(intent: Intent, fallback: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(fallback)
    }
}
