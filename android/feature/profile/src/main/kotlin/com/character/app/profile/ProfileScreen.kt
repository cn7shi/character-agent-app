package com.character.app.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.character.app.domain.ProfileChange
import com.character.app.domain.UserProfile

@Composable
fun ProfileScreen(
    profile: UserProfile?,
    pending: ProfileChange?,
    supported: Boolean,
    busy: Boolean,
    refresh: () -> Unit,
    edit: () -> Unit,
    reset: () -> Unit,
    retry: () -> Unit,
    reload: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("我的资料", style = MaterialTheme.typography.headlineSmall)
        Text("设置你的昵称和个人简介。", style = MaterialTheme.typography.bodyMedium)

        if (!supported) {
            Text("当前连接暂不支持个人资料。")
        } else {
            OutlinedButton(onClick = refresh, enabled = !busy) {
                Text("刷新资料")
            }
            if (profile == null) {
                Text("还未读取资料，请连接服务后刷新。")
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(profile.nickname.ifBlank { "未设置昵称" }, style = MaterialTheme.typography.titleLarge)
                        Text(profile.bio.ifBlank { "还没有填写简介" })
                    }
                }
                Button(onClick = edit, enabled = !busy && pending == null) {
                    Text("编辑资料")
                }
                TextButton(
                    onClick = { confirmReset = true },
                    enabled = !busy && pending == null && (profile.nickname.isNotEmpty() || profile.bio.isNotEmpty()),
                ) {
                    Text("重置资料")
                }
            }
            if (pending != null) {
                PendingProfile(pending, busy, retry, reload)
            }
            Text("重置只清空昵称和简介，角色、聊天和笔记会保留。", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("清空个人资料？") },
            text = { Text("昵称和简介将被清空。角色、聊天和笔记会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        reset()
                    },
                    enabled = !busy,
                ) {
                    Text("确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun PendingProfile(
    pending: ProfileChange,
    busy: Boolean,
    retry: () -> Unit,
    reload: () -> Unit,
) {
    Text(if (pending.reset) "资料重置结果待确认" else "资料保存结果待确认")
    Text("原操作已保存在本机，可以重试。读取最新资料会放弃本机待确认操作；已到达服务的操作可能已经生效。", style = MaterialTheme.typography.bodySmall)
    Button(onClick = retry, enabled = !busy) {
        Text("重试原操作")
    }
    OutlinedButton(onClick = reload, enabled = !busy) {
        Text("读取最新资料")
    }
}

@Composable
fun ProfileEditorScreen(
    value: UserProfile,
    pending: ProfileChange?,
    busy: Boolean,
    change: (UserProfile) -> Unit,
    save: () -> Unit,
    retry: () -> Unit,
    reload: () -> Unit,
    close: () -> Unit,
) {
    val editable = !busy && pending == null
    val nicknameSize = value.nickname.trim().let { it.codePointCount(0, it.length) }
    val bioSize = value.bio.trim().let { it.codePointCount(0, it.length) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("编辑个人资料", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = value.nickname,
            onValueChange = { change(value.copy(nickname = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text("昵称") },
            singleLine = true,
            supportingText = { Text("$nicknameSize / 50 字，不能为空") },
            isError = nicknameSize > 50,
        )
        OutlinedTextField(
            value = value.bio,
            onValueChange = { change(value.copy(bio = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text("个人简介") },
            minLines = 3,
            supportingText = { Text("$bioSize / 500 字") },
            isError = bioSize > 500,
        )
        if (pending != null) {
            PendingProfile(pending, busy, retry, reload)
        } else {
            Button(
                onClick = save,
                enabled = editable && nicknameSize in 1..50 && bioSize <= 500,
            ) {
                Text("保存资料")
            }
        }
        TextButton(onClick = close, enabled = !busy) {
            Text("返回资料")
        }
    }
}
