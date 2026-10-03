import SwiftUI

/// 内容的种类，用来筛选，也决定每一轮长什么样
enum TurnKind: CaseIterable {
    case word, sentence, image, dialog, transcript, practice

    var title: String {
        switch self {
        case .word: "单词"
        case .sentence: "句子"
        case .image: "图片"
        case .dialog: "对话"
        case .transcript: "传译"
        case .practice: "练习"
        }
    }
}

extension TurnKind {
    /// 和会话里这类卡片相同的底色，以及配套的深色
    var cardColor: Color {
        switch self {
        case .word: .lxSurface
        case .sentence: .lxSentenceCard
        case .image: .lxImageCard
        case .dialog: .lxDialogCard
        case .transcript: .lxTranscriptCard
        case .practice: .lxPracticeCard
        }
    }

    var inkColor: Color {
        switch self {
        case .word: .lxAccent
        case .sentence: .lxSentenceInk
        case .image: .lxImageInk
        case .dialog: .lxDialogInk
        case .transcript: .lxTranscriptInk
        case .practice: .lxPracticeInk
        }
    }
}

extension Turn {
    var kind: TurnKind {
        if practice != nil { return .practice }
        if transcript != nil { return .transcript }
        if dialog != nil { return .dialog }
        if isImage { return .image }
        if word != nil { return .word }
        return .sentence
    }
}

/// 会话里的一轮。每种内容有固定的样子，看一眼就分得清：
/// 单词是浅蓝色的词典卡片；句子的原文和译文一起放在浅绿色卡片里；图片的译文直接覆盖在图上。
/// 操作按钮只在最新一轮和被点选的那一轮出现。
struct TurnView: View {
    let turn: Turn
    @ObservedObject var controller: ConversationController
    @Binding var editingTurn: UUID?
    let onOpenWord: (WordEntry) -> Void
    let onReply: (SentenceResult) -> Void
    let onEditImage: (UUID) -> Void
    let onNeedAI: () -> Void
    /// 长内容是否展开：刚翻译出来的展开，重新打开会话时收起
    let expanded: Bool
    let onToggleExpand: () -> Void
    /// 显示操作按钮（最新一轮，或者被点选的那一轮）
    var showsActions = false
    /// 点一下这一轮：选中它，显示操作按钮
    var onSelect: () -> Void = {}
    /// 删除这一轮（由会话页弹出确认）
    var onDelete: () -> Void = {}

    @State private var editText = ""
    @State private var copied = false
    @State private var showBefore = false
    /// 切到“原图”的那几张图片（默认都显示译文）
    @State private var originals: Set<UUID> = []
    @AppStorage(SettingsKey.showAIUsage) private var showUsage = false

    private var editing: Bool { editingTurn == turn.id }

    var body: some View {
        if let practice = turn.practice {
            PracticeCard(record: practice, date: turn.createdAt, expanded: expanded, onToggleExpand: onToggleExpand,
                         onAgain: { controller.startPractice(again: practice) })
                .contentShape(.rect)
                .onTapGesture(perform: onSelect)
                #if os(macOS)
                .contextMenu {
                    Button("删除这一轮", systemImage: "trash", role: .destructive) { onDelete() }
                }
                #endif
        } else if let transcript = turn.transcript {
            TranscriptCard(lines: transcript, duration: turn.transcriptDuration, sourceIsChinese: turn.sourceIsChinese,
                           date: turn.createdAt, expanded: expanded, onToggleExpand: onToggleExpand,
                           onContinue: { controller.continueInterpretation(turn.id) })
                .contentShape(.rect)
                .onTapGesture(perform: onSelect)
                #if os(macOS)
                .contextMenu {
                    Button("删除这一轮", systemImage: "trash", role: .destructive) { onDelete() }
                }
                #endif
        } else if let dialog = turn.dialog {
            DialogCard(lines: dialog, date: turn.createdAt)
                .contentShape(.rect)
                .onTapGesture(perform: onSelect)
                #if os(macOS)
                .contextMenu {
                    Button("删除这一轮", systemImage: "trash", role: .destructive) { onDelete() }
                }
                #endif
        } else if turn.isImage || turn.word != nil {
            VStack(alignment: .leading, spacing: 6) {
                // 单词直接显示成词卡，不再重复一个原文气泡
                if turn.isImage { imageSource }
                if let audio = turn.audioFile {
                    HStack(spacing: 6) {
                        Spacer(minLength: 0)
                        Image(systemName: "mic.fill").font(.caption).foregroundStyle(Color.lxAccent)
                        AudioReplayButton(name: audio, duration: turn.audioDuration)
                    }
                }
                tagLine
                result
            }
        } else {
            // 一句话或一段话：原文和译文放在同一张浅绿色卡片里，算一组
            VStack(alignment: .leading, spacing: 8) {
                if editing {
                    editor
                } else {
                    sentenceSource
                }
                tagLine
                Divider().opacity(0.5)
                result
                    .opacity(editing ? 0.4 : 1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.lxSentenceCard, in: .rect(cornerRadius: 18))
            .contextMenu { sourceMenu }
        }
    }

    @ViewBuilder
    private var tagLine: some View {
        if let tags {
            Text(tags)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    /// 卡片里的原文：灰色小字，最多两行，右边是快捷复制；语音输入的带麦克风标记和回放
    private var sentenceSource: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .top, spacing: 6) {
                if turn.audioFile != nil {
                    Image(systemName: "mic.fill")
                        .font(.caption)
                        .foregroundStyle(Color.lxAccent)
                        .padding(.top, 2)
                        .accessibilityLabel("语音输入")
                }
                FoldableText(text: turn.source, font: .system(size: 14), expanded: expanded, foldedLines: 2, onToggle: onToggleExpand)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(.rect)
                    .onTapGesture(perform: onSelect)
                CopyButton(text: turn.source, label: "复制原文")
                    .padding(.top, -10)
                    .padding(.trailing, -10)
            }
            if let audio = turn.audioFile {
                AudioReplayButton(name: audio, duration: turn.audioDuration)
            }
        }
    }

    /// 只在手动指定方向或改过原文时才标出来，平时不显示
    private var tags: String? {
        var parts: [String] = []
        if turn.manualDirection { parts.append(turn.sourceIsChinese ? "中 → 英 · 手动" : "英 → 中 · 手动") }
        if turn.edited { parts.append("已编辑") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    // MARK: 原文

    @ViewBuilder
    private var sourceMenu: some View {
        Button("编辑原文", systemImage: "pencil") {
            editText = turn.source
            editingTurn = turn.id
        }
        Button("复制原文", systemImage: "doc.on.doc") { Clipboard.copy(turn.source) }
        Button("朗读原文", systemImage: "speaker.wave.2") {
            Speaker.shared.toggle(.text(turn.source, isChinese: turn.sourceIsChinese))
        }
        #if os(macOS)
        // iPhone 上往左滑删除；Mac 没有左滑，保留右键删除
        Button("删除这一轮", systemImage: "trash", role: .destructive) { onDelete() }
        #endif
    }

    private var editor: some View {
        VStack(alignment: .trailing, spacing: 8) {
            TextField("原文", text: $editText, axis: .vertical)
                .font(.system(size: 16))
                .lineLimit(2...12)
            HStack(spacing: 8) {
                Button("取消") { editingTurn = nil }
                    .buttonStyle(.glass)
                Button("保存并重新翻译") {
                    controller.editSource(turn.id, to: editText)
                    editingTurn = nil
                }
                .buttonStyle(.glassProminent)
                .disabled(editText.trimmed.isEmpty)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).stroke(Color.lxAccent, lineWidth: 2))
        .onAppear { if editText.isEmpty { editText = turn.source } }
    }

    /// 图片这一轮的“原文”：只显示几张图和附带的要求，图片本身在下面的译文卡片里
    private var imageSource: some View {
        VStack(alignment: .trailing, spacing: 4) {
            if let instruction = turn.instruction {
                Label(instruction, systemImage: "sparkles")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 7)
                    .background(Color.lxSurface, in: UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: 14,
                                                                             bottomTrailingRadius: 4, topTrailingRadius: 14))
            }
            Text("\(turn.images.count) 张图片" + (turn.state == .working ? " · 识别和翻译中" : ""))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 56)
    }

    // MARK: 结果

    @ViewBuilder
    private var result: some View {
        switch turn.state {
        case .working where !turn.isImage:
            working("翻译中…")
        case .failed:
            HStack(spacing: 12) {
                Label(turn.errorMessage ?? "翻译失败", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("重试") { controller.retry(turn.id) }
                    .buttonStyle(.borderless)
                    .font(.footnote.weight(.semibold))
                    .frame(minHeight: 44)
            }
        default:
            if turn.isImage {
                imageResults
            } else if let entry = turn.word {
                WordCard(entry: entry) { onOpenWord(entry) }
                    .contextMenu { sourceMenu }
            } else if let sentence = turn.sentence {
                sentenceResult(sentence)
            }
        }
    }

    private func working(_ text: String) -> some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text(text).font(.footnote).foregroundStyle(.secondary)
        }
        .frame(minHeight: 28)
    }

    /// 译文直接覆盖在图上原文的位置；右上角点“原图 / 译文”切换。点图片进入大图
    private var imageResults: some View {
        let all = turn.images.map(\.translation).filter { !$0.isEmpty }.joined(separator: "\n")
        let multiple = turn.images.count > 1
        return VStack(alignment: .leading, spacing: 6) {
            if multiple {
                // 多张图并排、按各自的宽高比排得紧凑一些，左右滑动
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: 8) {
                        ForEach(Array(turn.images.enumerated()), id: \.element.id) { index, item in
                            imagePage(item, index: index, height: 260)
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.viewAligned)
                .scrollIndicators(.hidden)
            } else if let item = turn.images.first {
                imagePage(item, index: 0, height: nil)
            }
            if showsActions, turn.state == .done, !all.isEmpty {
                actionBar {
                    SpeakButton(speech: .text(all, isChinese: !(turn.images.first?.recognized.isMostlyChinese ?? false)))
                        .frame(width: 44, height: 44)
                    if multiple {
                        iconButton(copied ? "checkmark" : "doc.on.doc", "复制全部译文") {
                            Clipboard.copy(all)
                            copied = true
                        }
                    }
                }
            }
        }
        .padding(8)
        .background(Color.lxImageCard, in: .rect(cornerRadius: 18))
        .onTapGesture(perform: onSelect)
        #if os(macOS)
        .contextMenu {
            Button("删除这一轮", systemImage: "trash", role: .destructive) { onDelete() }
        }
        #endif
    }

    /// 一张图：译文盖在图上，右上角切换原图和译文，下面是页码和这张图译文的快捷复制。
    /// height 为 nil 时铺满宽度（只有一张图）；多张图时按固定高度、各自的宽高比排
    private func imagePage(_ item: TurnImage, index: Int, height: CGFloat?) -> some View {
        let image = controller.store.image(named: item.fileName)
        let ratio = image.map { $0.size.width / max($0.size.height, 1) } ?? 0.75
        let width: CGFloat? = height.map { min(max($0 * ratio, 120), 300) }
        let hasBlocks = item.done && !(item.blocks ?? []).isEmpty
        return VStack(alignment: .leading, spacing: 2) {
            if let image {
                TranslatedImageView(image: image, blocks: item.blocks ?? [],
                                    showTranslation: !originals.contains(item.id) && item.done)
                    .clipShape(.rect(cornerRadius: 12))
                    .contentShape(.rect)
                    .onTapGesture { onEditImage(item.id) }
                    .overlay(alignment: .topTrailing) {
                        if hasBlocks { originalToggle(item.id).padding(6) }
                    }
                    .overlay {
                        if !item.done {
                            ProgressView("识别和翻译中…")
                                .padding(12)
                                .background(.regularMaterial, in: .rect(cornerRadius: 12))
                        }
                    }
                    .accessibilityLabel("图 \(index + 1)，点按看大图")
                    .frame(width: width, height: height, alignment: .top)
                    .frame(maxWidth: height == nil ? .infinity : nil, maxHeight: height == nil ? 460 : nil)
            }
            HStack(spacing: 4) {
                if turn.images.count > 1 {
                    Text("\(index + 1)/\(turn.images.count)").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                }
                let problem = item.done && (item.failed == true || (item.blocks ?? []).isEmpty)
                if problem {
                    Text(item.failed == true ? "识别或翻译失败" : (item.blocks == nil ? item.translation : "没有识别到文字"))
                        .font(.footnote)
                        .foregroundStyle(item.failed == true ? Color.lxAI : .secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                if item.done {
                    // 出问题时显示文字按钮，平时只是一个小图标
                    Button {
                        controller.reprocessImage(turn.id, item.id)
                    } label: {
                        Group {
                            if problem && (width ?? 999) >= 150 {
                                Label("重新识别", systemImage: "arrow.clockwise").font(.caption.weight(.semibold))
                            } else {
                                Image(systemName: "arrow.clockwise").font(.system(size: 13, weight: .medium))
                            }
                        }
                        .foregroundStyle(problem ? Color.lxAccent : Color.secondary)
                        .frame(minWidth: 36, minHeight: 36)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("重新识别图 \(index + 1)")
                }
                if hasBlocks, !item.translation.isEmpty {
                    CopyButton(text: item.translation, label: "复制图 \(index + 1) 的译文",
                               title: (width ?? 999) >= 210 ? "复制译文" : nil)
                }
            }
            .frame(width: width)
            .frame(minHeight: 30)
        }
    }

    /// 图片右上角的切换：原图 / 译文，点哪个显示哪个
    private func originalToggle(_ id: UUID) -> some View {
        let showingOriginal = originals.contains(id)
        return HStack(spacing: 0) {
            ForEach([false, true], id: \.self) { original in
                let on = showingOriginal == original
                Text(original ? "原图" : "译文")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(on ? Color.black : Color.white)
                    .padding(.horizontal, 10)
                    .frame(height: 26)
                    .background(on ? Color.white : Color.clear, in: .capsule)
                    .contentShape(.capsule)
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.15)) {
                            if original { originals.insert(id) } else { originals.remove(id) }
                        }
                    }
                    .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(2)
        .background(.black.opacity(0.55), in: .capsule)
    }

    private func sentenceResult(_ sentence: SentenceResult) -> some View {
        // 单词和短语查不到词典时也走机器翻译，但不提供 AI 优化：AI 只针对一句话
        let isSentence = !ConversationController.isWordLike(sentence.source)
        let waitingForAI = turn.isOptimizing && !sentence.showsAI
        // 用了 AI 只在句尾标一个小小的“AI”，不再单独一行标签
        let badge = sentence.showsAI ? Text("\(Image(systemName: "sparkles"))AI").font(.caption2.weight(.bold)).foregroundStyle(Color.lxAI) : nil
        return VStack(alignment: .leading, spacing: 8) {
            if waitingForAI {
                // 开着 AI 优化时不先显示机器翻译，等优化好了直接显示结果
                working("AI 优化中…")
            } else {
                HStack(alignment: .top, spacing: 0) {
                    FoldableText(text: sentence.displayed, font: .system(size: 18, weight: .medium), lineSpacing: 4,
                                 expanded: expanded, badge: badge, onToggle: onToggleExpand)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(.rect)
                        .onTapGesture(perform: onSelect)
                    // 译文的快捷复制
                    CopyButton(text: sentence.displayed, label: "复制译文")
                        .padding(.top, -10)
                        .padding(.trailing, -10)
                }
            }
            if let error = turn.aiError {
                Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(Color.lxAI)
            }
            if showsActions {
                if sentence.showsAI {
                    if sentence.aiTranslation != sentence.translation {
                        // 优化前的译文默认折叠
                        Button {
                            withAnimation(.snappy) { showBefore.toggle() }
                        } label: {
                            Label(showBefore ? "收起优化前" : "查看优化前的译文", systemImage: showBefore ? "chevron.up" : "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .frame(minHeight: 32)
                                .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        if showBefore {
                            Text(sentence.translation)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .padding(10)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color.lxSurface, in: .rect(cornerRadius: 12))
                        }
                    }
                    if showUsage {
                        Text([sentence.aiModel, sentence.aiUsage?.summary].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.lxAI)
                    }
                }
                actionBar {
                    SpeakButton(speech: .text(sentence.displayed, isChinese: !sentence.sourceIsChinese))
                        .frame(width: 44, height: 44)
                    iconButton("arrow.up.arrow.down", "对调：把译文反向再翻译一次") { controller.swap(turn) }
                    iconButton("arrowshape.turn.up.left", "AI 写回复", tint: .lxAI) {
                        AISettings.shared.isConfigured ? onReply(sentence) : onNeedAI()
                    }
                    if isSentence || sentence.showsAI {
                        Divider().frame(height: 20).padding(.horizontal, 4)
                        aiChip(on: sentence.showsAI)
                        if sentence.showsAI, !turn.isOptimizing {
                            iconButton("arrow.clockwise", "重新用 AI 优化", tint: .lxAI) {
                                AISettings.shared.isConfigured ? controller.reoptimize(turn.id) : onNeedAI()
                            }
                        }
                    }
                }
                if !sentence.showsAI {
                    Text("译文来自" + sentence.engine).font(.caption2).foregroundStyle(.tertiary)
                }
            }
            if controller.offlineDownloadable, sentence.engine == OnlineTranslator.name, showsActions {
                Button("下载系统离线翻译模型（更快、不限量、无需联网）") { controller.downloadOfflineModel() }
                    .buttonStyle(.borderless)
                    .font(.footnote)
                    .frame(minHeight: 44)
            }
            if !sentence.suggestions.isEmpty {
                Text("你是不是要找：" + sentence.suggestions.prefix(3).map(\.word).joined(separator: "、"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func actionBar<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 0) { content() }
            .padding(.leading, -12)
            .transition(.opacity)
    }

    /// “AI 优化”开关：点一下用 AI 优化，再点一下回到机器翻译；之前优化过的结果会保留，打开时不用重新请求
    private func aiChip(on: Bool) -> some View {
        Button {
            let sentence = turn.sentence
            AISettings.shared.isConfigured || sentence?.aiTranslation != nil ? controller.toggleAI(turn.id) : onNeedAI()
        } label: {
            Label("AI", systemImage: on ? "sparkles" : "sparkle")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(on ? Color.lxAI : Color.secondary)
                .padding(.horizontal, 10)
                .frame(height: 28)
                .background(on ? Color.lxAISoft : Color.secondary.opacity(0.12), in: .capsule)
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(turn.isOptimizing)
        .accessibilityLabel("AI 优化")
        .accessibilityValue(on ? "开" : "关")
    }

    private func iconButton(_ systemName: String, _ label: String, tint: Color = .secondary, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16))
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// 会话里的词典卡片：词头、发音、前几条释义，点按看完整词条
struct WordCard: View {
    let entry: WordEntry
    let onOpen: () -> Void

    @ObservedObject private var history = HistoryStore.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Text(entry.word)
                    .font(entry.isChinese ? .system(size: 26, weight: .semibold) : .system(size: 28, weight: .medium, design: .serif))
                    .textSelection(.enabled)
                Spacer()
                // 复制单词和释义
                CopyButton(text: copyText, label: "复制单词和释义")
                let starred = history.isStarred(entry.word)
                Button { history.toggleStar(entry.word) } label: {
                    Image(systemName: starred ? "star.fill" : "star")
                        .foregroundStyle(starred ? .orange : .secondary)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(starred ? "从生词本移除" : "加入生词本")
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { pronunciations }
                VStack(alignment: .leading, spacing: 8) { pronunciations }
            }
            VStack(alignment: .leading, spacing: 6) {
                if entry.senses.isEmpty {
                    ForEach(entry.definitions.prefix(3)) { d in
                        Text(d.text + (d.note.map { "  " + $0 } ?? "")).font(.callout).lineLimit(3)
                    }
                } else {
                    ForEach(entry.senses.prefix(4)) { sense in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            if let pos = sense.pos {
                                Text(pos).font(.system(.footnote, design: .serif).weight(.semibold).italic()).foregroundStyle(Color.lxAccent)
                            }
                            Text(sense.meaning).font(.callout)
                        }
                    }
                }
            }
            Button(action: onOpen) {
                Label("完整词条：例句、搭配、辨析", systemImage: "book")
                    .font(.footnote.weight(.semibold))
                    .frame(minHeight: 44)
            }
            .buttonStyle(.borderless)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.lxSurface, in: .rect(cornerRadius: 20))
    }

    /// 复制的内容：单词加前几条释义
    private var copyText: String {
        let meanings = entry.senses.isEmpty
            ? entry.definitions.prefix(3).map(\.text)
            : entry.senses.prefix(4).map { [$0.pos, $0.meaning].compactMap { $0 }.joined(separator: " ") }
        return ([entry.word] + meanings).joined(separator: "\n")
    }

    @ViewBuilder
    private var pronunciations: some View {
        if entry.isChinese {
            PronunciationPill(label: "中", text: entry.pinyin ?? "朗读", speech: .chinese(entry.word))
        } else if entry.phonetics.isEmpty {
            PronunciationPill(label: "美", text: "朗读", speech: .english(entry.word, accent: 2))
        } else {
            ForEach(entry.phonetics) { p in
                PronunciationPill(label: p.label, text: "/\(p.ipa)/", speech: .english(entry.word, accent: p.accent))
            }
        }
    }
}

/// 超过几行时折叠；只有真的放不下时才出现“展开全文 / 收起”
struct FoldableText: View {
    let text: String
    let font: Font
    var lineSpacing: CGFloat = 0
    let expanded: Bool
    var alignment: HorizontalAlignment = .leading
    var foldedLines = 4
    /// 接在文字末尾的小标记，例如“AI”
    var badge: Text?
    let onToggle: () -> Void

    @State private var fullHeight: CGFloat = 0
    @State private var foldedHeight: CGFloat = 0

    private var content: Text {
        if let badge { return Text("\(Text(text))  \(badge)") }
        return Text(text)
    }

    var body: some View {
        VStack(alignment: alignment, spacing: 2) {
            styled(content)
                .lineLimit(expanded ? nil : foldedLines)
                .textSelection(.enabled)
                .background(alignment: .topLeading) {
                    // 量一下完整高度和折叠后的高度，判断是不是真的被截断
                    ZStack(alignment: .topLeading) {
                        measured(styled(content)) { fullHeight = $0 }
                        measured(styled(content).lineLimit(foldedLines)) { foldedHeight = $0 }
                    }
                    .hidden()
                    .accessibilityHidden(true)
                }
            if fullHeight > foldedHeight + 1 {
                Button(action: onToggle) {
                    Label(expanded ? "收起" : "展开全文", systemImage: expanded ? "chevron.up" : "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .frame(minHeight: 32)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.lxAccent)
            }
        }
    }

    private func styled(_ text: Text) -> some View {
        text.font(font).lineSpacing(lineSpacing)
    }

    private func measured(_ view: some View, _ update: @escaping (CGFloat) -> Void) -> some View {
        view.fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { update($0) }
    }
}

/// 快捷复制：小图标，点一下复制，变成对勾一会儿
struct CopyButton: View {
    let text: String
    let label: String
    /// 有文字时显示成“图标 + 文字”
    var title: String?

    @State private var copied = false

    var body: some View {
        Button {
            Clipboard.copy(text)
            withAnimation(.snappy) { copied = true }
            Task {
                try? await Task.sleep(for: .seconds(1.5))
                withAnimation(.snappy) { copied = false }
            }
        } label: {
            Group {
                if let title {
                    Label(copied ? "已复制" : title, systemImage: copied ? "checkmark" : "doc.on.doc")
                        .font(.caption.weight(.semibold))
                } else {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 13, weight: .medium))
                }
            }
            .foregroundStyle(copied ? Color.green : Color.secondary)
            .frame(minWidth: 36, minHeight: 36)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(copied ? "已复制" : label)
    }
}
