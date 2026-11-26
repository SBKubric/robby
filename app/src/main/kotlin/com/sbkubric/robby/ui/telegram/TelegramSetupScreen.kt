package com.sbkubric.robby.ui.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbkubric.robby.R
import com.sbkubric.robby.ui.BackTopBar
import com.sbkubric.robby.ui.appContainer

@Composable
fun TelegramSetupScreen(onBack: () -> Unit) {
    val container = appContainer()
    val resources = LocalResources.current
    val vm = viewModel { TelegramSetupViewModel(container.telegramApi, container.telegram, resources) }
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { BackTopBar(stringResource(R.string.tg_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Step(stringResource(R.string.tg_step_token), stringResource(R.string.tg_step_token_text)) {
                OutlinedTextField(
                    value = vm.token,
                    onValueChange = vm::onTokenChange,
                    label = { Text(stringResource(R.string.tg_token)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                val bot = vm.bot
                when {
                    bot != null -> Good(stringResource(R.string.tg_bot_found, bot))
                    else -> Button(onClick = vm::checkToken, enabled = !vm.checking && vm.token.isNotEmpty()) {
                        Text(stringResource(if (vm.checking) R.string.tg_checking else R.string.tg_check))
                    }
                }
                vm.tokenError?.let { Problem(it) }
            }

            val link = vm.link
            if (link != null) {
                Step(stringResource(R.string.tg_step_group), stringResource(R.string.tg_step_group_text)) {
                    SelectionContainer { Text(link, style = MaterialTheme.typography.bodyMedium) }
                    FilledTonalButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                        Text(stringResource(R.string.tg_open_link))
                    }
                    val group = vm.group
                    when {
                        group != null -> Good(stringResource(R.string.tg_group_found, group.title))
                        vm.waiting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.tg_waiting), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    vm.groupError?.let {
                        Problem(it)
                        Button(onClick = vm::startSearch) { Text(stringResource(R.string.tg_search_again)) }
                    }
                }
            }

            if (vm.group != null) {
                Step(stringResource(R.string.tg_step_test), null) {
                    if (vm.done) {
                        Good(stringResource(R.string.tg_done))
                        Button(onClick = onBack) { Text(stringResource(R.string.tg_finish)) }
                    } else {
                        Button(onClick = vm::sendTest, enabled = !vm.sending) {
                            Text(stringResource(if (vm.sending) R.string.tg_sending else R.string.tg_send_test))
                        }
                    }
                    vm.testError?.let { Problem(it) }
                }
            }
        }
    }
}

@Composable
private fun Step(title: String, text: String?, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (text != null) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Good(text: String) {
    Text("✓ $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Problem(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
