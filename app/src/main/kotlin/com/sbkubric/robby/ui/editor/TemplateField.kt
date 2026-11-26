package com.sbkubric.robby.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sbkubric.robby.R

/** A template text with chips that insert the trigger's variables at the cursor. */
@Composable
fun TemplateField(label: String, value: String, variables: List<String>, onValueChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The draft is the source of truth: follow it when it changes from outside (e.g. an action above was removed).
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                onValueChange(it.text)
            },
            label = { Text(label) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.editor_variables), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            variables.forEach { variable ->
                val token = "{$variable}"
                SuggestionChip(
                    onClick = {
                        val selection = field.selection
                        val text = field.text.replaceRange(selection.min, selection.max, token)
                        field = TextFieldValue(text, TextRange(selection.min + token.length))
                        onValueChange(text)
                    },
                    label = { Text(token) },
                )
            }
        }
    }
}
