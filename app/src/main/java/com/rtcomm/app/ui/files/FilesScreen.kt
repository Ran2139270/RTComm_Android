package com.rtcomm.app.ui.files

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.FileMeta
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.EmptyState
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.Reveal
import com.rtcomm.app.ui.common.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var files by remember { mutableStateOf<List<FileMeta>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var uploadName by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var lookupId by remember { mutableStateOf("") }
    val cancelRequested = remember { mutableStateOf(false) }

    // 先显缓存（上次会话的文件列表），再按需上传/查询刷新。
    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { com.rtcomm.app.data.PrefsCache.load(context, "files") }
        if (cached != null) {
            runCatching { com.google.gson.Gson().fromJson(cached, Array<FileMeta>::class.java)?.toList() }
                .getOrNull()?.let { if (files.isEmpty()) files = it }
        }
    }

    fun persistFiles(list: List<FileMeta>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                com.rtcomm.app.data.PrefsCache.save(context, "files", com.google.gson.Gson().toJson(list))
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        cancelRequested.value = false
        scope.launch {
            uploading = true; progress = 0f; err = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // 与聊天页一致：图片先压缩再传，避免直传原图
                    val original = FileTransfer.copyToCache(context, uri)
                    val picked = FileTransfer.compressImage(original)
                    uploadName = picked.name
                    try {
                        FileTransfer.upload(picked, null, cancelled = { cancelRequested.value }) { done, total ->
                            progress = if (total > 0) done.toFloat() / total else 0f
                        }
                    } finally {
                        // upload() 已删除 picked；压缩产生新文件时原始缓存副本也要清理。
                        if (original.file != picked.file) original.file.delete()
                    }
                }
            }
            uploading = false
            r.onSuccess { meta -> files = listOf(meta) + files.filterNot { it.id == meta.id }; persistFiles(files) }
            r.onFailure { if (it !is InterruptedException) err = Api.userMessage(it) }
        }
    }

    // 共享 FAB（在 MainScaffold，切 Tab 时不抖动）发出的「上传文件」信号。
    LaunchedEffect(Unit) {
        AppState.fabUploadFile.collect {
            if (it) {
                AppState.fabUploadFile.value = false
                if (!uploading) picker.launch("*/*")
            }
        }
    }

    // 顶部栏固定（pinned）：和首页一致，滚动时毛玻璃标题栏不会被翻过。
    val titleScroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(titleScroll.nestedScrollConnection),
        topBar = { AppTopBar("文件", scrollBehavior = titleScroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Reveal(err != null) { err?.let { ErrorBanner(it) } }
            Reveal(uploading) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("上传中：" + uploadName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                            IconButton(onClick = { cancelRequested.value = true }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                        }
                        Spacer(Modifier.height(Space.sm))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.xs))
                        Text("" + (progress * 100).toInt() + "%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = lookupId,
                    onValueChange = { lookupId = it },
                    label = { Text("按文件 ID 查询") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                Spacer(Modifier.width(Space.sm))
                Button(onClick = {
                    val id = lookupId.trim(); if (id.isEmpty()) return@Button
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Api.fileMeta(id) } }
                        r.onSuccess { m -> if (m != null) { files = listOf(m) + files.filterNot { it.id == m.id }; lookupId = ""; persistFiles(files) } else err = "文件不存在" }
                        r.onFailure { err = Api.userMessage(it) }
                    }
                }) { Text("查询") }
            }
            Spacer(Modifier.height(Space.sm))
            FadeSwap(files.isEmpty() && !uploading) { empty ->
                if (empty) {
                    EmptyState(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        "暂无文件",
                        "上传文件或按 ID 查询，均可在此下载",
                        action = { Button(onClick = { picker.launch("*/*") }) { Text("上传文件") } },
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files, key = { it.id }) { f ->
                            FileCard(f, modifier = Modifier.animateItem()) { scope.launch { downloadAndOpen(context, f, snackbar) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(f: FileMeta, modifier: Modifier = Modifier, onDownload: () -> Unit) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .motionPress(pressedScale = 0.99f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onDownload),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
        headlineContent = { Text(f.fileName, fontWeight = FontWeight.Medium, maxLines = 2) },
        supportingContent = {
            Text(FileTransfer.humanSize(f.fileSize) + " · " + (f.mimeType ?: "") + " · " + Format.relative(f.createdAt), style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = { IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, stringResource(R.string.action_download)) } },
    )
}

private suspend fun downloadAndOpen(context: android.content.Context, f: FileMeta, snackbar: SnackbarHostState) {
    val r = withContext(Dispatchers.IO) { runCatching { FileTransfer.download(context, f.id, f.fileName) } }
    r.onSuccess { file ->
        val choice = snackbar.showSnackbar("已保存：" + file.name, actionLabel = "打开")
        if (choice == SnackbarResult.ActionPerformed) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, f.mimeType ?: FileTransfer.guessMime(f.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(open) }
        }
    }
    r.onFailure { snackbar.showSnackbar("下载失败：" + Api.userMessage(it)) }
}
