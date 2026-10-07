package com.rtcomm.app.ui.chat

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

/** 录音上限：超过自动发送，避免一个文件大到传不动。 */
private const val MAX_VOICE_SECONDS = 60

/**
 * 语音录制按钮（点按开始 / 再次点按发送，录制中可以取消）。
 *
 * 用点按切换而不是长按：长按在低端机上容易因为触摸抖动提前结束，也不好取消。
 */
@Composable
fun VoiceRecorderButton(
    enabled: Boolean,
    onRecorded: (File) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outFile by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    val recording = recorder != null

    fun start() {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val target = File(dir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            outFile = target
            recorder = r
            seconds = 0
        } catch (e: Exception) {
            runCatching { r.release() }
            target.delete()
            onError("录音启动失败：${e.message ?: "未知错误"}")
        }
    }

    /** @param send true=发送，false=取消删除 */
    fun finish(send: Boolean) {
        val r = recorder ?: return
        val target = outFile
        val secs = seconds
        recorder = null
        outFile = null
        // 录得太短时 stop() 会抛异常，属于预期情况，吞掉即可。
        runCatching { r.stop() }
        runCatching { r.release() }
        if (target == null) return
        if (!send || secs < 1) {
            target.delete()
            return
        }
        onRecorded(target)
    }

    // 计时 + 超长自动发送
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recorder != null) {
            delay(1000)
            seconds += 1
            if (seconds >= MAX_VOICE_SECONDS) {
                finish(send = true)
                break
            }
        }
    }

    // 离开页面时释放，避免麦克风被一直占用。
    DisposableEffect(Unit) {
        onDispose {
            recorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            recorder = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) start() else onError("没有麦克风权限，无法录音")
    }

    fun onMicClick() {
        if (recording) {
            finish(send = true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (recording) {
        // 录制中：显示计时与取消/发送，替代输入行的视觉焦点。
        val pulse = rememberInfiniteTransition(label = "rec")
        val pulseAlpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "rec-alpha",
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha), CircleShape),
                )
                Text(
                    "录音中 %02d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { finish(send = false) }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = { finish(send = true) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text(stringResource(R.string.action_send))
                }
            }
        }
    } else {
        IconButton(
            onClick = ::onMicClick,
            enabled = enabled,
            modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "录音")
        }
    }
}
