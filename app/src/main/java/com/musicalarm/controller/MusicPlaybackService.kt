package com.musicalarm.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.Locale
import java.util.concurrent.Executors

/** Media3-backed playback with failover for broken/unavailable SAF audio tracks. */
class MusicPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_ALARM = "com.musicalarm.folderalarm.service.ALARM"
        const val ACTION_MANUAL = "com.musicalarm.folderalarm.service.MANUAL"
        private const val CHANNEL = "music_folder_alarm_playback"
        private const val NOTIFICATION_ID = 101
        private const val MAX_TRACK_ERRORS = 25
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestSequence = 0
    private var activeAlarm: MusicAlarm? = null
    private var usedFallback = false
    private var fallbackPlayer: MediaPlayer? = null
    private var alarmQueue = mutableListOf<MusicTrack>()
    private var failedTrackCount = 0
    private var confirmedAlarmPlayback = false
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var warnedBattery = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_LOW) speakLowBattery()
        }
    }

    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "volume") {
            val value = AlarmStore.volume(this)
            player.volume = value
            fallbackPlayer?.setVolume(value, value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(mediaAudioAttributes(alarm = false), true)
            volume = AlarmStore.volume(this@MusicPlaybackService)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        if (activeAlarm != null && !confirmedAlarmPlayback && !usedFallback) {
                            confirmedAlarmPlayback = true
                            AlarmStore.addEvent(
                                this@MusicPlaybackService,
                                activeAlarm!!.name,
                                "Music is playing successfully (${alarmQueue.size} tracks)"
                            )
                        }
                        checkBattery()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem != null) {
                        // The user may select manual playback while the emergency tone is sounding.
                        stopFallbackTone()
                        mediaItem.localConfiguration?.uri?.toString()?.let { uri ->
                            val name = mediaItem.mediaMetadata.title?.toString() ?: "Unknown track"
                            AlarmStore.rememberTrack(this@MusicPlaybackService, MusicTrack(uri, name))
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val alarm = activeAlarm ?: return
                    if (usedFallback) return
                    val generation = requestSequence
                    // Recover on the player thread after its error callback returns.
                    mainHandler.post {
                        if (generation == requestSequence && activeAlarm?.id == alarm.id && !usedFallback) {
                            skipFailedTrack(alarm, error.errorCodeName)
                        }
                    }
                }
            })
        }
        session = MediaSession.Builder(this, player).build()
        getSharedPreferences("music_folder_alarm_v1", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferenceListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW))
        tts = TextToSpeech(this) { status ->
            voiceReady = status == TextToSpeech.SUCCESS
            if (voiceReady) tts?.language = Locale.getDefault()
        }
    }

    private fun mediaAudioAttributes(alarm: Boolean) = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(if (alarm) C.USAGE_ALARM else C.USAGE_MEDIA)
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mediaServiceResult = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_MANUAL) {
            requestSequence++
            activeAlarm = null
            alarmQueue.clear()
            usedFallback = false
            stopFallbackTone()
            player.setAudioAttributes(mediaAudioAttributes(alarm = false), true)
            return mediaServiceResult
        }
        if (intent?.action == ACTION_ALARM) {
            startForeground(NOTIFICATION_ID, notification("Preparing scheduled music"))
            val id = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID)
            val alarm = AlarmStore.alarms(this).find { it.id == id }
            if (alarm == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            playAlarm(alarm)
            return START_STICKY
        }
        return mediaServiceResult
    }

    private fun playAlarm(alarm: MusicAlarm) {
        requestSequence++
        val generation = requestSequence
        activeAlarm = alarm
        usedFallback = false
        confirmedAlarmPlayback = false
        failedTrackCount = 0
        warnedBattery = false
        alarmQueue.clear()
        stopFallbackTone()

        val folder = alarm.folderUri
        if (folder.isNullOrBlank()) {
            playFallback(alarm, "No music folder selected")
            return
        }
        executor.execute {
            val result = runCatching { MusicFolderReader.tracks(this, folder) }
            mainHandler.post {
                if (generation != requestSequence) return@post
                val tracks = result.getOrElse { error ->
                    AlarmStore.addEvent(this, alarm.name, "Cannot read music folder: ${error.javaClass.simpleName}")
                    emptyList()
                }
                if (tracks.isEmpty()) {
                    playFallback(alarm, "Music folder empty or unavailable")
                    return@post
                }
                val first = if (alarm.shuffleEnabled) {
                    AlarmStore.chooseStartTrack(this, alarm.id, tracks)
                } else 0
                val ordered = if (alarm.shuffleEnabled) {
                    listOf(tracks[first]) + tracks.filterIndexed { index, _ -> index != first }.shuffled()
                } else tracks
                alarmQueue = ordered.toMutableList()
                player.setAudioAttributes(mediaAudioAttributes(alarm = true), true)
                // Shuffle is implemented in our own ordered playlist so the chosen first
                // song really is the first one ExoPlayer attempts.
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
                loadAlarmQueue(startIndex = 0)
                AlarmStore.addEvent(this, alarm.name, "Alarm triggered; loaded ${tracks.size} tracks")
            }
        }
    }

    private fun item(track: MusicTrack) = MediaItem.Builder()
        .setUri(track.uri)
        .setMediaId(track.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
        .build()

    private fun loadAlarmQueue(startIndex: Int) {
        if (alarmQueue.isEmpty()) return
        player.setMediaItems(alarmQueue.map(::item), startIndex.coerceIn(0, alarmQueue.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    private fun skipFailedTrack(alarm: MusicAlarm, code: String) {
        val failedIndex = player.currentMediaItemIndex.takeIf { it in alarmQueue.indices } ?: 0
        val failed = alarmQueue.getOrNull(failedIndex)
        if (failed != null) alarmQueue.removeAt(failedIndex)
        failedTrackCount++
        if (failedTrackCount == 1) {
            AlarmStore.addEvent(
                this, alarm.name,
                "Track '${failed?.title ?: "unknown"}' failed ($code); trying other songs"
            )
        }
        if (alarmQueue.isEmpty() || failedTrackCount >= MAX_TRACK_ERRORS) {
            playFallback(alarm, "Music playback failed ($code); ${failedTrackCount} attempted track(s)")
            return
        }
        loadAlarmQueue(startIndex = failedIndex.coerceAtMost(alarmQueue.lastIndex))
    }

    /** A bundled WAV avoids ROM-dependent settings:// alarm ringtone URIs. */
    private fun playFallback(alarm: MusicAlarm, reason: String) {
        usedFallback = true
        player.stop()
        player.clearMediaItems()
        stopFallbackTone()
        try {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val nativePlayer = MediaPlayer.create(
                this, R.raw.music_alarm_fallback, attributes, 0
            ) ?: error("Cannot open bundled fallback audio")
            val volume = AlarmStore.volume(this)
            nativePlayer.isLooping = true
            nativePlayer.setVolume(volume, volume)
            nativePlayer.start()
            fallbackPlayer = nativePlayer
            startForeground(NOTIFICATION_ID, notification("Backup alarm tone playing"))
            AlarmStore.addEvent(this, alarm.name, "Bundled alarm tone playing; $reason")
            checkBattery()
        } catch (error: Exception) {
            AlarmStore.addEvent(
                this, alarm.name,
                "Bundled alarm tone failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}"
            )
        }
    }

    private fun stopFallbackTone() {
        val old = fallbackPlayer
        fallbackPlayer = null
        if (old != null) {
            runCatching { if (old.isPlaying) old.stop() }
            runCatching { old.release() }
        }
    }

    private fun checkBattery() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = status.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0 && 100 * level / scale <= 15) speakLowBattery()
    }

    private fun speakLowBattery() {
        if (!warnedBattery && (player.isPlaying || fallbackPlayer?.isPlaying == true) &&
            AlarmStore.voiceLowBattery(this) && voiceReady) {
            warnedBattery = true
            tts?.speak("Battery low. Your music alarm is playing.", TextToSpeech.QUEUE_FLUSH, null, "battery_low")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Music alarm playback", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Music Folder Alarm")
        .setContentText(message)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying && fallbackPlayer?.isPlaying != true) super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        getSharedPreferences("music_folder_alarm_v1", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        tts?.stop()
        tts?.shutdown()
        requestSequence++
        executor.shutdownNow()
        stopFallbackTone()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }
}
