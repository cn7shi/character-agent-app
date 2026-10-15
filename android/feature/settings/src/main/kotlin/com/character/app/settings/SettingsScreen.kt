package com.character.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    currentUrl: String,
    connected: Boolean,
    dark: Boolean,
    busy: Boolean,
    polling: Boolean,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onTheme: (Boolean) -> Unit,
    onMode: (Boolean) -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    // Credential deliberately isn't rememberSaveable or echoed back from persistent storage.
    var credential by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text("外观", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("深色主题")
            Switch(checked = dark, onCheckedChange = onTheme)
        }

        HorizontalDivider()

        Text("连接开发服务", style = MaterialTheme.typography.titleLarge)
        Text("这里填写本项目后端的访问凭证，AI 服务密钥只放在服务端。当前版本用于本机开发联调。")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("使用兼容连接")
            Switch(checked = polling, onCheckedChange = onMode, enabled = !busy)
        }
        Text(
            text = "默认实时接收回复。网络环境不支持时自动改用定时查询，也可在此手动开启。切换保留当前连接的聊天记录。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SuggestionChip(
                onClick = { url = "http://10.0.2.2:8000/" },
                label = { Text("📱 模拟器 (10.0.2.2)") },
            )
            SuggestionChip(
                onClick = { url = "http://127.0.0.1:8000/" },
                label = { Text("💻 USB/本机 (127.0.0.1)") },
            )
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务地址") },
            singleLine = true,
        )

        OutlinedTextField(
            value = credential,
            onValueChange = { credential = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("开发访问凭证") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SuggestionChip(
                onClick = { credential = "VXyaOWUsTIYIJftfvo0qYe26FUizGyPXtGYarIs4-nQ" },
                label = { Text("🔑 填入用户A凭证") },
            )
            SuggestionChip(
                onClick = { credential = "teo7AjAVh6vrRPIJHebjjjNZuZCf3vi1QTaUgN5kTM8" },
                label = { Text("用户B") },
            )
        }

        Button(
            onClick = {
                onConnect(url, credential)
                credential = ""
            },
            enabled = !busy && credential.isNotBlank(),
        ) {
            Text(if (connected) "切换连接" else "保存并连接")
        }

        if (connected) {
            OutlinedButton(onClick = onDisconnect, enabled = !busy) {
                Text("断开并移除本机凭证")
            }
        }

        Text(
            text = "USB 调试连接可使用 127.0.0.1:8000，模拟器可使用 10.0.2.2:8000。具体启动方法见项目运行说明。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
