package com.yishulabs.qtranslator.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 普通设置：朗读口音、自动朗读、外观、朗读速度和音色、各功能的选项。键名和苹果版的 SettingsKey 对应 */
object Prefs {
    lateinit var store: SharedPreferences
        private set

    fun init(context: Context) {
        store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        accent = store.getInt("accent", 2)
        autoSpeak = store.getBoolean("autoSpeak", false)
        showAIUsage = store.getBoolean("showAIUsage", false)
        voiceAutoSend = store.getBoolean("voice.autoSend", false)
        appearance = store.getInt("ui.appearance", 0)
        playbackSpeed = store.getFloat("speech.speed", 1f)
        voiceEnglish = store.getString("speech.voice.en", "") ?: ""
        voiceChinese = store.getString("speech.voice.zh", "") ?: ""
        aiVoice = store.getString("speech.aiVoice", "") ?: ""
        interpreterSpeak = store.getBoolean("interpreter.speak", false)
        dialogSpeak = store.getBoolean("dialog.speak", true)
    }

    /** 语音输入说完后直接翻译（默认先放进输入框，可以改） */
    var voiceAutoSend by mutableStateOf(false)
        private set

    /** 外观：0 跟随系统，1 浅色，2 深色 */
    var appearance by mutableIntStateOf(0)
        private set

    /** 全局朗读速度（倍数，默认 1）：系统音色、AI 音色和有道真人发音都按这个速度 */
    var playbackSpeed by mutableStateOf(1f)
        private set

    /** 选的系统音色（Voice 的名字，空表示默认） */
    var voiceEnglish by mutableStateOf("")
        private set
    var voiceChinese by mutableStateOf("")
        private set

    /** 选的 AI 音色（OpenAI 的 voice 名字），空表示不用 AI 音色 */
    var aiVoice by mutableStateOf("")
        private set

    /** 同声传译默认用耳机朗读译文；面对面对话朗读译文 */
    var interpreterSpeak by mutableStateOf(false)
        private set
    var dialogSpeak by mutableStateOf(true)
        private set

    fun updateVoiceAutoSend(value: Boolean) { voiceAutoSend = value; store.edit().putBoolean("voice.autoSend", value).apply() }
    fun updateAppearance(value: Int) { appearance = value; store.edit().putInt("ui.appearance", value).apply() }
    fun updatePlaybackSpeed(value: Float) { playbackSpeed = value; store.edit().putFloat("speech.speed", value).apply() }
    fun updateVoiceEnglish(value: String) { voiceEnglish = value; store.edit().putString("speech.voice.en", value).apply() }
    fun updateVoiceChinese(value: String) { voiceChinese = value; store.edit().putString("speech.voice.zh", value).apply() }
    fun updateAIVoice(value: String) { aiVoice = value; store.edit().putString("speech.aiVoice", value).apply() }
    fun updateInterpreterSpeak(value: Boolean) { interpreterSpeak = value; store.edit().putBoolean("interpreter.speak", value).apply() }
    fun updateDialogSpeak(value: Boolean) { dialogSpeak = value; store.edit().putBoolean("dialog.speak", value).apply() }

    /** 默认英文口音：1 英音，2 美音 */
    var accent by mutableIntStateOf(2)
        private set

    var autoSpeak by mutableStateOf(false)
        private set

    /** 在译文下面显示这次 AI 用了多少 token（默认不显示，用量报表里都有） */
    var showAIUsage by mutableStateOf(false)
        private set

    fun updateShowAIUsage(value: Boolean) {
        showAIUsage = value
        store.edit().putBoolean("showAIUsage", value).apply()
    }

    fun updateAccent(value: Int) {
        accent = value
        store.edit().putInt("accent", value).apply()
    }

    fun updateAutoSpeak(value: Boolean) {
        autoSpeak = value
        store.edit().putBoolean("autoSpeak", value).apply()
    }
}

/** API Key 只保存在本机：用 Android 密钥库里的密钥加密后再存 */
object SecretStore {
    private const val ALIAS = "qtranslator.ai"
    private lateinit var store: SharedPreferences

    fun init(context: Context) {
        store = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun get(account: String): String? {
        val saved = store.getString(account, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
    }

    fun set(account: String, value: String) {
        if (value.isEmpty()) {
            store.edit().remove(account).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray())
        store.edit().putString(account, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
}
