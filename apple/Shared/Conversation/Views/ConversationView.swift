import SwiftUI

/// 当前会话：顶栏、一轮轮的翻译、底部输入栏。
struct ConversationView: View {
    @ObservedObject var controller: ConversationController
    @ObservedObject var store: ConversationStore
    let screenHeight: CGFloat
    let onMenu: () -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void
    /// 宽屏（Mac、iPad 横屏）：左边常驻会话列表，输入记录放在右边一栏
    var wide = false
    /// 宽度够时输入记录才放在右边一栏，不够时和手机一样弹出
    var railAllowed = true

    @FocusState private var composerFocused: Bool
    @State private var editingTurn: UUID?
    @State private var showOutline = false
    @AppStorage("wide.showOutline") private var showOutlineRail = true
    @State private var scrollTarget: UUID?
    /// 从输入记录跳过来的那一轮，短暂高亮
    @State private var highlighted: UUID?
    /// 这次打开后新翻译的轮次默认展开；重新打开会话时长内容都收起
    @State private var expandedTurns: Set<UUID> = []
    /// 往上翻看历史时，右下角出现回到底部的箭头
    @State private var awayFromBottom = false
    /// 滚动位置：回到底部时直接滚到内容最下边，不依赖某一轮是否已经加载
    @State private var position = ScrollPosition(edge: .bottom)
    /// 顶部筛选：nil 表示全部
    @State private var filter: TurnKind?
    /// 点选的那一轮显示操作按钮（最新一轮总是显示）
    @State private var selectedTurn: UUID?
    /// 左滑露出删除按钮的那一轮；要删除、等确认的那一轮
    @State private var swipedTurn: String?
    @State private var deletingTurn: Turn?
    @State private var renaming = false
    @State private var renameText = ""
    @State private var confirmDelete = false
    @State private var wordToShow: WordEntry?
    @State private var replyTo: SentenceResult?
    @State private var editingImage: ImageRef?

    struct ImageRef: Identifiable {
        let turnID: UUID
        let imageID: UUID
        var id: UUID { imageID }
    }

    var body: some View {
        let session = store.session(controller.currentID)
        HStack(spacing: 0) {
            main(session)
            if wide, railAllowed, showOutlineRail {
                Divider().ignoresSafeArea()
                OutlinePanel(turns: session?.turns ?? []) { scrollTarget = $0 } onClose: {
                    withAnimation(.snappy) { showOutlineRail = false }
                }
                .frame(width: 270)
                .transition(.move(edge: .trailing))
            }
        }
    }

    /// 要显示的轮次，以及每一轮上面要不要加时间分隔（和上一轮隔了 10 分钟以上）
    private func rows(_ session: ChatSession?) -> [(turn: Turn, time: String?)] {
        let turns = (session?.turns ?? []).filter { filter == nil || $0.kind == filter }
        var previous: Date?
        return turns.map { turn in
            defer { previous = turn.createdAt }
            guard previous.map({ turn.createdAt.timeIntervalSince($0) > 600 }) ?? true else { return (turn, nil) }
            let time = turn.createdAt.formatted(date: .omitted, time: .shortened)
            let calendar = Calendar.current
            // 和上一轮在同一天时只写时间，换了一天就带上日期
            let sameDay = previous.map { calendar.isDate($0, inSameDayAs: turn.createdAt) } ?? false
            if sameDay { return (turn, time) }
            if calendar.isDateInToday(turn.createdAt) { return (turn, "今天 " + time) }
            if calendar.isDateInYesterday(turn.createdAt) { return (turn, "昨天 " + time) }
            return (turn, turn.createdAt.formatted(.dateTime.month().day()) + " " + time)
        }
    }

    /// 筛选栏：会话里有两种以上的内容时才出现
    @ViewBuilder
    private func filterBar(_ session: ChatSession?) -> some View {
        let turns = session?.turns ?? []
        let counts = TurnKind.allCases.map { kind in (kind, turns.filter { $0.kind == kind }.count) }.filter { $0.1 > 0 }
        if counts.count >= 2 {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    filterChip("全部 \(turns.count)", on: filter == nil, kind: nil) { filter = nil }
                    ForEach(counts, id: \.0) { kind, count in
                        filterChip("\(kind.title) \(count)", on: filter == kind, kind: kind) { filter = filter == kind ? nil : kind }
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.bottom, 6)
        }
    }

    /// 筛选按钮的配色和会话里对应的卡片一致：单词浅蓝、句子浅绿、图片浅灰；选中时换成同色系的深色底、白字
    private func filterChip(_ title: String, on: Bool, kind: TurnKind?, action: @escaping () -> Void) -> some View {
        let ink = kind?.inkColor ?? Color.primary
        let card = kind?.cardColor ?? Color.secondary.opacity(0.12)
        return Button {
            withAnimation(.snappy) {
                selectedTurn = nil
                action()
            }
        } label: {
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(on ? Color.lxBackground : (kind == nil ? Color.secondary : ink))
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(on ? ink : card, in: .capsule)
                .overlay { Capsule().stroke(ink.opacity(on || kind == nil ? 0 : 0.18), lineWidth: 1) }
                .frame(minHeight: 40)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func main(_ session: ChatSession?) -> some View {
        VStack(spacing: 0) {
            navBar(session)
            filterBar(session)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 20) {
                        ForEach(rows(session), id: \.turn.id) { row in
                            let turn = row.turn
                            if let time = row.time {
                                Text(time)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 3)
                                    .background(Color.lxSurface, in: .capsule)
                                    .frame(maxWidth: .infinity)
                            }
                            // 往左滑露出删除按钮，点了再确认
                            SwipeToDelete(id: turn.id.uuidString, openRow: $swipedTurn, onDelete: { deletingTurn = turn }) {
                            TurnView(turn: turn, controller: controller, editingTurn: $editingTurn,
                                     onOpenWord: { wordToShow = $0 },
                                     onReply: { replyTo = $0 },
                                     onEditImage: { editingImage = ImageRef(turnID: turn.id, imageID: $0) },
                                     onNeedAI: onSettings,
                                expanded: expandedTurns.contains(turn.id),
                                onToggleExpand: {
                                    withAnimation(.snappy) {
                                        if expandedTurns.contains(turn.id) { expandedTurns.remove(turn.id) } else { expandedTurns.insert(turn.id) }
                                    }
                                },
                                showsActions: turn.id == session?.turns.last?.id || turn.id == selectedTurn,
                                onSelect: {
                                    if swipedTurn != nil { swipedTurn = nil; return }
                                    withAnimation(.snappy) { selectedTurn = selectedTurn == turn.id ? nil : turn.id }
                                },
                                onDelete: { deletingTurn = turn })
                            }
                                .background {
                                    // 跳过来的那一轮闪一下；点选的那一轮有一圈淡淡的边框
                                    let flash = highlighted == turn.id
                                    let picked = selectedTurn == turn.id
                                    RoundedRectangle(cornerRadius: 20)
                                        .fill(Color.lxAccent.opacity(flash ? 0.14 : (picked ? 0.04 : 0)))
                                        .stroke(Color.lxAccent.opacity(flash ? 0.6 : (picked ? 0.3 : 0)), lineWidth: flash ? 2 : 1.5)
                                        .padding(-10)
                                }
                                .scaleEffect(highlighted == turn.id ? 1.02 : 1)
                                .id(turn.id)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 16)
                }
                .scrollPosition($position)
                .scrollDismissesKeyboard(.interactively)
                // 平时贴底（最新的在下面）；筛选时从顶部开始排
                .defaultScrollAnchor(filter == nil ? .bottom : .top)
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    geometry.contentOffset.y + geometry.containerSize.height < geometry.contentSize.height - 100
                } action: { _, away in
                    withAnimation(.snappy) { awayFromBottom = away }
                }
                .overlay(alignment: .bottom) {
                    if awayFromBottom, session?.turns.isEmpty == false {
                        Button {
                            withAnimation(.snappy) { position.scrollTo(edge: .bottom) }
                        } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(Color.lxAccent)
                                .frame(width: 44, height: 44)
                                .background(Color.lxBackground, in: .circle)
                                .overlay { Circle().stroke(Color.primary.opacity(0.08)) }
                                .shadow(color: .black.opacity(0.15), radius: 10, y: 3)
                                .contentShape(.circle)
                        }
                        .buttonStyle(.plain)
                        .padding(.bottom, 10)
                        .transition(.scale.combined(with: .opacity))
                        .accessibilityLabel("回到最新的翻译")
                    }
                }
                .overlay {
                    // 空会话的提示放在可见区域中间，不随滚动贴底
                    if let session, session.turns.isEmpty {
                        emptyState(session).padding(.horizontal, 16)
                    }
                }
                .onChange(of: session?.turns.count) { old, new in
                    guard let last = session?.turns.last?.id else { return }
                    // 只在发了新内容时滚到底；删掉一条时停在原处
                    guard (new ?? 0) > (old ?? 0) else { return }
                    expandedTurns.insert(last)
                    // 发了新内容：回到全部，取消点选
                    filter = nil
                    selectedTurn = nil
                    withAnimation { position.scrollTo(edge: .bottom) }
                }
                .onChange(of: scrollTarget) {
                    guard let target = scrollTarget else { return }
                    scrollTarget = nil
                    filter = nil
                    Task {
                        // 等弹窗收起再滚动，然后闪一下这一轮
                        try? await Task.sleep(for: .milliseconds(350))
                        withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(target, anchor: .center) }
                        try? await Task.sleep(for: .milliseconds(350))
                        withAnimation(.spring(duration: 0.35, bounce: 0.4)) { highlighted = target }
                        try? await Task.sleep(for: .seconds(2.2))
                        withAnimation(.easeOut(duration: 0.6)) { highlighted = nil }
                    }
                }
                .onChange(of: controller.currentID) {
                    editingTurn = nil
                    expandedTurns = []
                    filter = nil
                    selectedTurn = nil
                    position.scrollTo(edge: .bottom)
                }
            }
        }
        .analyticsPage("翻译")
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                // 同声传译收起后，在输入框上方显示“正在传译”，点一下回去
                InterpretMiniBar()
                if let session {
                    ComposerView(controller: controller, session: session, focused: $composerFocused,
                                 screenHeight: screenHeight, onNeedAI: onSettings)
                }
            }
        }
        .sheet(isPresented: $showOutline) {
            OutlineView(turns: session?.turns ?? []) { scrollTarget = $0 }
        }
        .sheet(item: Binding(get: { wordToShow.map(IdentifiedWord.init) }, set: { wordToShow = $0?.entry })) { item in
            WordSheet(word: item.entry.word, entry: item.entry) { await controller.quickTranslate($0) }
        }
        .sheet(item: Binding(get: { replyTo.map(IdentifiedSentence.init) }, set: { replyTo = $0?.result })) { item in
            ReplyView(received: item.result.source, receivedTranslation: item.result.displayed)
        }
        #if os(iOS)
        .fullScreenCover(item: $editingImage) { ref in
            ImageEditView(controller: controller, store: store, turnID: ref.turnID, imageID: ref.imageID)
        }
        #else
        .sheet(item: $editingImage) { ref in
            ImageEditView(controller: controller, store: store, turnID: ref.turnID, imageID: ref.imageID)
        }
        #endif
        .alert("重命名会话", isPresented: $renaming) {
            TextField("名称", text: $renameText)
            Button("取消", role: .cancel) {}
            Button("保存") { store.renameSession(controller.currentID, to: renameText) }
        }
        .confirmationDialog(deletingTurn.map(deleteTitle) ?? "", isPresented: Binding(get: { deletingTurn != nil },
                                                                                   set: { if !$0 { deletingTurn = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let turn = deletingTurn {
                    withAnimation(.snappy) { controller.deleteTurn(turn.id) }
                    if selectedTurn == turn.id { selectedTurn = nil }
                }
                deletingTurn = nil
            }
        } message: {
            Text("删除后不能恢复。")
        }
        .confirmationDialog("删除这个会话？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { controller.deleteSession(controller.currentID) }
        } message: {
            Text("会话里的翻译和图片都会被删除。")
        }
        .onAppear { composerFocused = session?.turns.isEmpty ?? true }
    }

    private func navBar(_ session: ChatSession?) -> some View {
        HStack(spacing: 8) {
            if !wide {
                GlassIconButton(systemName: "line.3.horizontal", label: "打开会话列表") {
                    composerFocused = false
                    onMenu()
                }
            }
            Menu {
                Button("重命名", systemImage: "pencil") {
                    renameText = session?.title ?? ""
                    renaming = true
                }
                Menu("移到场景", systemImage: "folder") {
                    Button("不放进场景") { store.moveSession(controller.currentID, to: nil) }
                    ForEach(store.scenes) { scene in
                        Button(scene.name) { store.moveSession(controller.currentID, to: scene.id) }
                    }
                }
                Button("删除会话", systemImage: "trash", role: .destructive) { confirmDelete = true }
            } label: {
                HStack(spacing: 6) {
                    if let scene = store.scene(session?.sceneID) {
                        SceneCoverView(cover: scene.cover, size: 24)
                    }
                    Text(session?.title ?? "").font(.callout.weight(.semibold)).lineLimit(1)
                    Image(systemName: "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14)
                .frame(maxWidth: wide ? nil : .infinity, minHeight: 44)
                .contentShape(.capsule)
            }
            .foregroundStyle(.primary)
            .glassEffect(.regular.interactive(), in: .capsule)
            .accessibilityLabel("当前会话：\(session?.title ?? "")，点按重命名或移动")
            #if os(macOS)
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()
            #endif
            if wide { Spacer(minLength: 0) }
            GlassIconButton(systemName: "clock.arrow.circlepath", label: "输入记录") {
                if wide, railAllowed {
                    withAnimation(.snappy) { showOutlineRail.toggle() }
                } else {
                    showOutline = true
                }
            }
            GlassIconButton(systemName: "square.and.pencil", label: "新建会话") { onNewSession() }
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 6)
    }

    private func emptyState(_ session: ChatSession) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "character.bubble")
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(Color.lxAccent)
                .frame(width: 68, height: 68)
                .background(Color.lxAccentSoft, in: .rect(cornerRadius: 20))
            VStack(spacing: 4) {
                Text(session.title).font(.title3.weight(.bold))
                Text("在下面输入，开始翻译").font(.callout).foregroundStyle(.secondary)
            }
            VStack(alignment: .leading, spacing: 12) {
                tip("text.cursor", "单词、句子或整段文字都可以")
                #if os(macOS)
                tip("photo.on.rectangle", "⌘V 粘贴截图，或把图片拖进来")
                #else
                tip("camera", "点左下角的相机拍照，或相册选图片")
                #endif
                tip("tray.full", "每次翻译都会保存在这个会话里")
            }
            .padding(16)
            .background(Color.lxSurface, in: .rect(cornerRadius: 18))
        }
        .frame(maxWidth: .infinity)
    }

    private func tip(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.callout)
                .foregroundStyle(Color.lxAccent)
                .frame(width: 22)
            Text(text).font(.callout).foregroundStyle(.secondary)
        }
    }
}

/// 删除确认的标题：说清楚删的是哪一条
private func deleteTitle(_ turn: Turn) -> String {
    switch turn.kind {
    case .word: "删除单词“\(turn.word?.word ?? turn.source)”？"
    case .image: "删除这 \(turn.images.count) 张图片和译文？"
    case .sentence: "删除这句话和它的译文？"
    }
}

private struct IdentifiedWord: Identifiable {
    let entry: WordEntry
    var id: String { entry.word }
}

private struct IdentifiedSentence: Identifiable {
    let result: SentenceResult
    var id: String { result.source + result.translation }
}

/// 输入记录：列出这个会话里输入过的每一条，可以搜索，点一条跳过去并高亮
struct OutlineView: View {
    let turns: [Turn]
    let onSelect: (UUID) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            OutlineList(turns: turns, query: query) {
                onSelect($0)
                dismiss()
            }
            #if os(iOS)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索输入过的内容和译文")
            #else
            .searchable(text: $query, prompt: "搜索输入过的内容和译文")
            #endif
            .navigationTitle("输入记录")
            .inlineNavigationTitle()
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .tint(.lxAccent)
    }
}

/// 宽屏右边的一栏输入记录
struct OutlinePanel: View {
    let turns: [Turn]
    let onSelect: (UUID) -> Void
    let onClose: () -> Void

    @State private var query = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("输入记录").font(.headline)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "sidebar.trailing").frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("收起输入记录")
            }
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("在会话里搜索", text: $query).textFieldStyle(.plain)
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(Color.lxSurface, in: .capsule)
            OutlineList(turns: turns, query: query, onSelect: onSelect)
        }
        .padding(.horizontal, 10)
        .padding(.top, 12)
        .background(Color.lxBackground.ignoresSafeArea())
    }
}

/// 输入记录的列表本体
struct OutlineList: View {
    let turns: [Turn]
    let query: String
    let onSelect: (UUID) -> Void

    var body: some View {
        List {
            let q = query.trimmed.lowercased()
            ForEach(Array(turns.enumerated()), id: \.element.id) { index, turn in
                if q.isEmpty || turn.searchableText.lowercased().contains(q) {
                    Button {
                        onSelect(turn.id)
                    } label: {
                        HStack(alignment: .top, spacing: 10) {
                            Text("\(index + 1)")
                                .font(.footnote.weight(.bold).monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(width: 22, alignment: .trailing)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(turn.outlineText).lineLimit(2)
                                HStack(spacing: 6) {
                                    Text(meta(turn)).font(.caption).foregroundStyle(.secondary)
                                    if turn.edited {
                                        Text("已编辑").font(.caption2.weight(.bold))
                                            .padding(.horizontal, 5)
                                            .background(Color.secondary.opacity(0.15), in: .rect(cornerRadius: 5))
                                    }
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        #if os(macOS)
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        #endif
        .overlay {
            if turns.isEmpty { ContentUnavailableView("还没有内容", systemImage: "text.bubble") }
        }
    }

    private func meta(_ turn: Turn) -> String {
        let kind = turn.isImage ? "图片" : (turn.word != nil ? "单词" : (turn.sourceIsChinese ? "中文" : "英文"))
        return kind + " · " + turn.createdAt.formatted(date: .omitted, time: .shortened)
    }
}
