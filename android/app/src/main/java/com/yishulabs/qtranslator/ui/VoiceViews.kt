package com.yishulabs.qtranslator.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import com.yishulabs.qtranslator.conversation.ConversationController

// 语音输入的界面部件，对应苹果版 VoiceViews.swift：麦克风按钮、正在听的面板、回放原声按钮。

/** 麦克风按钮：输入框没有内容时代替发送按钮 */
@Composable
fun MicButton(onClick: () -> Unit) {
    RoundIconButton(Icons.Rounded.Mic, "语音输入", onClick)
}

/** 正在听：实时显示识别出的文字、识别语言（点一下中英切换）和声波，右边结束，左上角取消。没在听时不显示 */
@Composable
fun VoiceListeningPanel(controller: ConversationController) {}

/** 一轮里的“回放原声”：语音输入时录下的声音 */
@Composable
fun AudioReplayButton(fileName: String, duration: Double?) {}
