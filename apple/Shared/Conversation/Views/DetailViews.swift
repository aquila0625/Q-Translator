import SwiftUI

/// 完整词条弹窗：顶部有下拉指示条，往下拉就关掉。点词组、同根词时在弹窗里继续往下查，不发到会话里。
struct WordSheet: View {
    let word: String
    var entry: WordEntry?
    /// 词典里查不到的词组，用它翻译
    let translate: (String) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            WordPage(word: word, entry: entry, translate: translate) { path.append($0) }
                .navigationDestination(for: String.self) { next in
                    WordPage(word: next, translate: translate) { path.append($0) }
                }
                #if os(macOS)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
                #endif
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 640)
        #endif
        .presentationDragIndicator(.visible)
        .tint(.lxAccent)
    }
}

/// 一页词条。词典里没有时显示整句翻译。
struct WordPage: View {
    let word: String
    var entry: WordEntry?
    let translate: (String) async -> String?
    let onLookup: (String) -> Void

    @State private var loaded: WordEntry?
    @State private var translation: String?
    @State private var finished = false

    var body: some View {
        ScrollView {
            if let current = entry ?? loaded {
                WordView(entry: current, onLookup: onLookup, wide: false)
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 20)
            } else if let translation {
                VStack(alignment: .leading, spacing: 14) {
                    Text(word).font(.system(size: 28, weight: .medium, design: .serif)).textSelection(.enabled)
                    Text(translation).font(.system(size: 20, weight: .medium)).textSelection(.enabled)
                    HStack(spacing: 8) {
                        SpeakPill(speech: .text(word, isChinese: word.isMostlyChinese))
                        GlassPillButton(title: "复制译文", systemName: "doc.on.doc") { Clipboard.copy(translation) }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else if finished {
                ContentUnavailableView("查不到“\(word)”", systemImage: "exclamationmark.magnifyingglass",
                                       description: Text("请检查网络后重试"))
                    .padding(.top, 60)
            } else {
                ProgressView().padding(.top, 80)
            }
        }
        .background(Color.lxBackground)
        .navigationTitle(entry == nil ? word : "")
        .inlineNavigationTitle()
        .task {
            guard entry == nil, loaded == nil, translation == nil else { return }
            loaded = try? await Youdao.lookup(word).entry
            if loaded == nil { translation = await translate(word) }
            finished = true
        }
    }
}

/// 生词本：加了星标的词，点开在弹窗里看词条
struct StarredWordsView: View {
    let translate: (String) async -> String?

    @ObservedObject private var history = HistoryStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var path: [String] = []
    @State private var pendingDelete: PendingDelete?

    var body: some View {
        NavigationStack(path: $path) {
            List {
                ForEach(history.starred) { item in
                    NavigationLink(value: item.text) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.text).font(.body.weight(.semibold))
                            Text(item.summary).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .swipeActions {
                        Button("移除", role: .destructive) {
                            pendingDelete = PendingDelete(title: "把“\(item.text)”从生词本移除？") { history.toggleStar(item.text) }
                        }
                    }
                }
            }
            .overlay {
                if history.starred.isEmpty {
                    ContentUnavailableView("生词本是空的", systemImage: "star",
                                           description: Text("在词典卡片上点星标，就能把词加进来。"))
                }
            }
            .navigationDestination(for: String.self) { word in
                WordPage(word: word, translate: translate) { path.append($0) }
            }
            .navigationTitle("生词本")
            .inlineNavigationTitle()
            #if os(macOS)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            #endif
        }
        .confirmDelete($pendingDelete, button: "移除")
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 640)
        #endif
        .presentationDragIndicator(.visible)
        .tint(.lxAccent)
    }
}

/// 编辑一张图片：旋转（译文一起转）、重新识别这一张，或删除它和它的译文
struct ImageEditView: View {
    @ObservedObject var controller: ConversationController
    @ObservedObject var store: ConversationStore
    let turnID: UUID
    let imageID: UUID

    @Environment(\.dismiss) private var dismiss
    /// 点“看原图 / 看译文”切换
    @State private var showOriginal = false
    @State private var pendingDelete: PendingDelete?

    var body: some View {
        let turn = store.turn(controller.currentID, turnID)
        let index = turn?.images.firstIndex { $0.id == imageID }
        let item = index.flatMap { turn?.images[$0] }
        NavigationStack {
            VStack(spacing: 16) {
                if let item, let image = store.image(named: item.fileName) {
                    // 双指缩放、双击放大；转了方向就从头看
                    ZoomableView {
                        TranslatedImageView(image: image, blocks: item.blocks ?? [], showTranslation: !showOriginal && item.done)
                    }
                    .id(item.fileName + String(item.blocks?.first?.turns ?? 0))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    if !item.done {
                        ProgressView("重新识别中…").tint(.white).foregroundStyle(.white)
                    } else if (item.blocks ?? []).isEmpty {
                        Text("没有识别到文字").font(.footnote).foregroundStyle(.white.opacity(0.75))
                    }
                } else {
                    Spacer()
                }
                // 四个按钮等宽，图标在上、文字在下，窄屏也不换行
                HStack(spacing: 8) {
                    // 点一下切换原图和译文
                    toolButton(showOriginal ? "看译文" : "看原图", showOriginal ? "character.book.closed" : "photo") {
                        withAnimation(.easeOut(duration: 0.15)) { showOriginal.toggle() }
                    }
                    toolButton("重新识别", "arrow.clockwise") { controller.reprocessImage(turnID, imageID) }
                        .disabled(item?.done != true)
                    toolButton("旋转", "rotate.right") { controller.rotateImage(turnID, imageID) }
                        .disabled(item?.done != true)
                    toolButton("删除", "trash", role: .destructive) {
                        let isLast = (turn?.images.count ?? 0) <= 1
                        pendingDelete = PendingDelete(
                            title: "删除这张图片和它的译文？",
                            message: isLast ? "这是这一轮里的最后一张，整轮翻译会一起删除。" : nil) {
                            controller.deleteImage(turnID, imageID)
                            dismiss()
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(index.map { "图 \($0 + 1) / \(turn?.images.count ?? 0)" } ?? "")
            .inlineNavigationTitle()
            #if os(iOS)
            .toolbarColorScheme(.dark, for: .navigationBar)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
        .preferredColorScheme(.dark)
        .confirmDelete($pendingDelete)
        #if os(macOS)
        .frame(minWidth: 640, minHeight: 560)
        #endif
        .onChange(of: item == nil) { if item == nil { dismiss() } }
    }

    private func toolButton(_ title: String, _ symbol: String, role: ButtonRole? = nil, action: @escaping () -> Void) -> some View {
        Button(role: role, action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 18, weight: .medium))
                Text(title).font(.caption.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(role == .destructive ? Color.red : Color.white)
            .frame(maxWidth: .infinity, minHeight: 58)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 16))
    }
}
