package com.lumen.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumen.player.LumenApp
import com.lumen.player.data.settings.KeySlot
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.launch

private data class KeyStatus(val text: String, val ok: Boolean?)

/** "gsk_abcd…wxyz"처럼 앞 4자와 뒤 4자만 보여 준다 */
private fun mask(key: String) = if (key.length <= 10) "•".repeat(key.length) else key.take(4) + "…" + key.takeLast(4)

/**
 * API 키 입력 칸. 저장 버튼이나 키보드 완료로 저장하고, 저장한 값을 다시 읽어 맞는지 본 뒤
 * 공급자에게 확인 요청을 보낸다. 저장된 키는 가려서 보여 준다.
 */
@Composable
fun ApiKeyField(provider: SttProvider, modifier: Modifier = Modifier, onSaved: () -> Unit = {}) =
    ApiKeyField(KeySlot.Stt(provider), modifier, onSaved)

@Composable
fun ApiKeyField(provider: KeySlot, modifier: Modifier = Modifier, onSaved: () -> Unit = {}) {
    val container = LumenApp.container
    val repo = container.settings
    val ai = repo.settings.collectAsState().value.ai
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val saved = repo.apiKey(ai, provider)
    var input by remember(provider) { mutableStateOf("") }
    var status by remember(provider) { mutableStateOf<KeyStatus?>(null) }

    fun verify(key: String) {
        status = KeyStatus("${provider.label}에 키를 확인하는 중", null)
        scope.launch {
            container.apiKeyChecker.check(provider, key, repo.current().ai)
                .onSuccess { status = KeyStatus(it, true) }
                .onFailure { status = KeyStatus(it.message ?: "확인하지 못했습니다", false) }
        }
    }

    fun save() {
        val key = input.trim()
        if (key.isEmpty()) return
        focus.clearFocus()
        scope.launch {
            if (repo.setApiKey(provider, key)) {
                input = ""
                onSaved()
                verify(key)
            } else {
                status = KeyStatus("이 기기에 키를 저장하지 못했습니다. 다시 시도해 주세요", false)
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (saved.isNotBlank()) "저장된 ${provider.label} 키: ${mask(saved)}" else "${provider.label} 키가 저장되어 있지 않습니다",
            style = MaterialTheme.typography.bodySmall,
            color = if (saved.isNotBlank()) Lumen.Primary else Lumen.TextMuted,
        )
        OutlinedTextField(
            input, { input = it },
            singleLine = true,
            label = { Text(if (saved.isNotBlank()) "새 키 (바꿀 때만 입력)" else "${provider.label} API 키 붙여 넣기") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = input.isNotBlank(), onClick = { save() }) { Text("키 저장") }
            TextButton(enabled = saved.isNotBlank() && input.isBlank(), onClick = { verify(saved) }) { Text("키 확인") }
        }
        status?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.bodySmall,
                color = when (it.ok) {
                    true -> Lumen.Primary
                    false -> Lumen.Error
                    null -> Lumen.TextMuted
                },
            )
        }
    }
}
