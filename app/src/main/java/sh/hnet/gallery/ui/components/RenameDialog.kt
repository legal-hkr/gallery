package sh.hnet.gallery.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import sh.hnet.gallery.R
import sh.hnet.gallery.model.FileNames

/**
 * Rename a file (SPEC §10.3): the family's name dialog (as News' `NameDialog`) with the name
 * selected and the extension as a fixed suffix beside it, which can't be changed or deleted. The
 * name is checked on Rename (and the keyboard's action): empty, characters a storage refuses, a name
 * already in the file's folder on the phone ([taken]).
 *
 * @param onRename The whole new name, extension included
 */
@Composable
fun RenameDialog(name: String, taken: Collection<String>, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val (base, extension) = remember(name) { FileNames.split(name) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(base, TextRange(0, base.length))) }
    var problem by remember { mutableStateOf<FileNames.Problem?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        val found = FileNames.problem(field.text, extension, taken)
        problem = found
        if (found == null) {
            val newName = field.text.trim() + extension
            if (newName == name) onDismiss() else onRename(newName)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_rename)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    problem = null
                },
                label = { Text(stringResource(R.string.label_file_name)) },
                suffix = if (extension.isNotEmpty()) ({ Text(extension) }) else null,
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { found ->
                    {
                        Text(
                            when (found) {
                                FileNames.Problem.Empty -> stringResource(R.string.error_name_empty)
                                is FileNames.Problem.Characters -> stringResource(R.string.error_name_characters, found.characters)
                                FileNames.Problem.Exists -> stringResource(R.string.error_file_exists)
                            },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        dismissButton = { DialogDismissButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { DialogConfirmButton(onClick = submit, enabled = field.text.isNotBlank()) { Text(stringResource(R.string.button_rename)) } },
    )
}
