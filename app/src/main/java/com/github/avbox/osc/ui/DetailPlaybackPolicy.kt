package com.github.avbox.osc.ui

internal enum class DetailPlaybackEntry {
    PlayCapsule,
    Episode,
    EpisodeFullscreen,
    Line,
    Cast,
    ExitFullscreen,
    SwitchRollback,
    MusicReturn,
}

internal data class DetailPlaybackPlan(
    val startPlayback: Boolean,
    val enterFullScreen: Boolean,
    val stopPlayback: Boolean,
)

internal object DetailPlaybackPolicy {

    fun plan(entry: DetailPlaybackEntry, resumable: Boolean = false): DetailPlaybackPlan = when (entry) {
        DetailPlaybackEntry.PlayCapsule,
        DetailPlaybackEntry.EpisodeFullscreen -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = true, stopPlayback = false)

        // 单击集数：在详情页内小窗播放，不进入全屏；双击才走 EpisodeFullscreen 直接全屏
        DetailPlaybackEntry.Episode -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.SwitchRollback,
        DetailPlaybackEntry.MusicReturn -> DetailPlaybackPlan(startPlayback = resumable, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.Line,
        DetailPlaybackEntry.Cast -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.ExitFullscreen -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = true)
    }
}
