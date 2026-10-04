import SwiftUI

// MARK: - 模块的颜色

extension AppModule {
    var card: Color {
        switch self {
        case .interpret: .lxTranscriptCard
        case .face: .lxDialogCard
        case .practice: .lxPracticeCard
        }
    }

    var ink: Color {
        switch self {
        case .interpret: .lxTranscriptInk
        case .face: .lxDialogInk
        case .practice: .lxPracticeInk
        }
    }
}

// MARK: - 抽屉和侧边栏里的三个入口

struct ModuleTiles: View {
    @ObservedObject private var store = ModuleStore.shared
    var active: AppModule?
    let onOpen: (AppModule) -> Void

    private func subtitle(_ module: AppModule) -> String {
        switch module {
        case .interpret: "\(store.interpretations.count) 条记录"
        case .face: "\(store.dialogs.count) 次对话"
        case .practice: "本周 \(store.practicesThisWeek.count) 次"
        }
    }

    var body: some View {
        HStack(spacing: 8) {
            ForEach(AppModule.allCases) { module in
                Button { onOpen(module) } label: {
                    VStack(spacing: 4) {
                        Image(systemName: module.symbol)
                            .font(.system(size: 17, weight: .medium))
                            .frame(width: 34, height: 34)
                            .background(Color.lxBackground.opacity(0.7), in: .rect(cornerRadius: 11))
                        Text(module.title).font(.system(size: 13, weight: .bold))
                        Text(subtitle(module)).font(.system(size: 11)).opacity(0.8)
                    }
                    .foregroundStyle(module.ink)
                    .frame(maxWidth: .infinity, minHeight: 78)
                    .background(module.card, in: .rect(cornerRadius: 16))
                    .overlay {
                        if active == module { RoundedRectangle(cornerRadius: 16).stroke(module.ink, lineWidth: 2) }
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(module.fullTitle)
            }
        }
    }
}

// MARK: - 模块页面

/// 某个模块的页面：上面是开始区，下面是记录；点记录看全文。
/// iPhone 上全屏盖在翻译上（有返回按钮）；Mac 和 iPad 显示在右边，点左边的会话回到翻译。
struct ModulePage: View {
    let module: AppModule
    @ObservedObject var controller: ConversationController
    var wide = false

    /// 点开的记录
    enum Detail: Hashable {
        case interpret(UUID), dialog(UUID), practice(UUID)
    }

    /// 全屏进行中的活动：传译、面对面对话或练习
    struct Activity: Identifiable {
        let id = UUID()
        let launch: ModuleRouter.Launch
    }

    @ObservedObject private var router = ModuleRouter.shared
    @ObservedObject private var store = ModuleStore.shared
    @State private var detail: Detail?
    @State private var activity: Activity?

    var body: some View {
        ZStack {
            WashBackground().ignoresSafeArea()
            if let detail {
                detailPage(detail)
            } else {
                home
            }
        }
        .tint(.lxAccent)
        .analyticsPage(module.fullTitle + "首页")
        .onChange(of: router.launch, initial: true) { _, launch in
            guard let launch else { return }
            router.launch = nil
            detail = nil
            start(launch)
        }

        #if os(iOS)
        .fullScreenCover(item: $activity) { activityView($0) }
        #else
        .sheet(item: $activity) { activityView($0) }
        #endif
    }

    @ViewBuilder
    private var home: some View {
        switch module {
        case .interpret: InterpretHome(top: topBar, store: store, onOpen: { detail = .interpret($0) },
                                       onStart: { start(.interpret(continuing: $0)) })
        case .face: FaceHome(top: topBar, store: store, onOpen: { detail = .dialog($0) },
                             onStart: { start(.face) })
        case .practice: PracticeHome(top: topBar, store: store, onOpen: { detail = .practice($0) },
                                     onStart: { start(.practice(seed: $0)) })
        }
    }

    /// 顶栏，以及传译收起后的小提示条（同声传译首页自己有“回到传译”按钮，不再显示）
    private var topBar: some View {
        VStack(spacing: 8) {
            titleBar
            if module != .interpret { InterpretMiniBar() }
        }
    }

    @ViewBuilder
    private var titleBar: some View {
        if wide {
            Text(module.fullTitle).font(.title2.weight(.bold))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 20)
        } else {
            ModuleTopBar(title: module.fullTitle, onBack: { router.module = nil }) {
                Color.clear
            }
        }
    }

    @ViewBuilder
    private func detailPage(_ detail: Detail) -> some View {
        switch detail {
        case .interpret(let id):
            InterpretRecordPage(id: id, store: store, onBack: { self.detail = nil },
                                onContinue: { start(.interpret(continuing: id)) })
        case .dialog(let id):
            DialogRecordPage(id: id, store: store, onBack: { self.detail = nil })
        case .practice(let id):
            PracticeRecordPage(id: id, store: store, onBack: { self.detail = nil },
                               onAgain: { seed in start(.practice(seed: seed)) })
        }
    }

    /// 开始一项活动。同声传译先在全局会话里开起来（离开页面也继续），已经在传译时只是回到传译页
    private func start(_ launch: ModuleRouter.Launch) {
        if case .interpret(let id) = launch {
            InterpretSession.shared.begin(continuing: id, controller: controller)
        }
        activity = Activity(launch: launch)
    }

    @ViewBuilder
    private func activityView(_ activity: Activity) -> some View {
        switch activity.launch {
        case .interpret: InterpreterView()
        case .face: FaceToFaceView(controller: controller)
        case .practice(let seed): PracticeView(controller: controller, seed: seed)
        }
    }
}

/// 模块页面的顶栏：返回、居中的标题、右边可以放菜单
struct ModuleTopBar<Trailing: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(spacing: 8) {
            GlassIconButton(systemName: "chevron.left", label: "返回", action: onBack)
            Text(title).font(.headline).lineLimit(1).frame(maxWidth: .infinity)
            trailing.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

// MARK: - 记录列表的共用部件

/// 一条记录：点整行打开，右边一个按钮做常用操作（继续、查看、再练）
struct ModuleRow: View {
    let module: AppModule
    let title: String
    let meta: String
    let pill: String
    let onOpen: () -> Void
    let onPill: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    Text(meta).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(minHeight: 44)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            Button(action: onPill) {
                Text(pill)
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(module.ink)
                    .padding(.horizontal, 12)
                    .frame(height: 32)
                    .background(module.card, in: .capsule)
                    .frame(minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(minHeight: 64)
        .background(Color.lxSurface)
    }
}

/// 要删的一条记录和确认时显示的标题
struct PendingDelete: Identifiable {
    let id = UUID()
    let title: String
    var message: String?
    let action: () -> Void
}

extension View {
    /// 删除前的确认对话框：pending 有值时弹出，点“删除”才执行
    func confirmDelete(_ pending: Binding<PendingDelete?>, button: String = "删除") -> some View {
        confirmationDialog(pending.wrappedValue?.title ?? "",
                           isPresented: Binding(get: { pending.wrappedValue != nil }, set: { if !$0 { pending.wrappedValue = nil } }),
                           titleVisibility: .visible) {
            Button(button, role: .destructive) {
                pending.wrappedValue?.action()
                pending.wrappedValue = nil
            }
        } message: {
            if let message = pending.wrappedValue?.message { Text(message) }
        }
    }
}

/// 记录列表：每条左滑出删除，点删除后确认
struct RecordList<Row: View>: View {
    let ids: [UUID]
    let emptyText: String
    let deleteTitle: (UUID) -> String
    let onDelete: (UUID) -> Void
    @ViewBuilder let row: (UUID) -> Row

    @State private var swiped: String?
    @State private var pending: PendingDelete?

    var body: some View {
        Group {
            if ids.isEmpty {
                Text(emptyText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 80)
                    .background(Color.lxSurface, in: .rect(cornerRadius: 22))
            } else {
                VStack(spacing: 0) {
                    ForEach(ids, id: \.self) { id in
                        SwipeToDelete(id: id.uuidString, openRow: $swiped,
                                      onDelete: { pending = PendingDelete(title: deleteTitle(id)) { onDelete(id) } }) {
                            row(id)
                        }
                        if id != ids.last { Divider().padding(.leading, 16) }
                    }
                }
                .clipShape(.rect(cornerRadius: 22))
            }
        }
        .confirmationDialog(pending?.title ?? "", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                pending?.action()
                pending = nil
            }
        }
    }
}

/// 开始区下面的小节标题
struct ModuleCaption: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.footnote.weight(.semibold)).foregroundStyle(.secondary).padding(.leading, 4)
    }
}

/// 模块首页的外框：顶栏 + 可以滚动的内容，宽屏时内容居中、不要太宽
struct ModuleScroll<Top: View, Content: View>: View {
    let top: Top
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            top
            ScrollView {
                VStack(alignment: .leading, spacing: 16) { content }
                    .padding(16)
                    .frame(maxWidth: 640)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: - 同声传译

struct InterpretHome<Top: View>: View {
    let top: Top
    @ObservedObject var store: ModuleStore
    let onOpen: (UUID) -> Void
    let onStart: (UUID?) -> Void

    @AppStorage("interpreter.sourceIsChinese") private var fromChinese = false
    @AppStorage(SettingsKey.interpreterSpeak) private var speak = false
    @ObservedObject private var session = InterpretSession.shared

    var body: some View {
        ModuleScroll(top: top) {
            VStack(spacing: 12) {
                HStack(spacing: 8) {
                    Menu {
                        Picker("语言", selection: $fromChinese) {
                            Text("听英语，译成中文").tag(false)
                            Text("听中文，译成英语").tag(true)
                        }
                    } label: {
                        chipLabel(fromChinese ? "中 → 英" : "英 → 中", symbol: "chevron.down", on: false)
                    }
                    .buttonStyle(.plain)
                    .menuIndicator(.hidden)
                    chip("耳机朗读", symbol: "headphones", on: speak) { speak.toggle() }
                }
                Button { onStart(nil) } label: {
                    Label(session.isActive ? "回到正在进行的传译" : "开始传译", systemImage: session.isActive ? "waveform" : "mic.fill")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 28)
                        .frame(height: 56)
                        .background(Color.red, in: .capsule)
                }
                .buttonStyle(.plain)
                Text("听讲座、开会时用：持续收音，自动断句，上面原文、下面译文。")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(AppModule.interpret.ink)
            }
            .padding(18)
            .frame(maxWidth: .infinity)
            .background(AppModule.interpret.card, in: .rect(cornerRadius: 24))

            ModuleCaption("传译记录")
            RecordList(ids: store.interpretations.map(\.id), emptyText: "还没有传译记录",
                       deleteTitle: { "删除“\(store.interpretation($0)?.title ?? "")”？" },
                       onDelete: { store.deleteInterpretation($0) }) { id in
                if let record = store.interpretation(id) {
                    ModuleRow(module: .interpret, title: record.title,
                              meta: "\(record.updatedAt.formatted(.dateTime.month().day().hour().minute())) · \(record.lines.count) 句 · \(AudioReplayButton.format(record.duration))",
                              pill: "继续", onOpen: { onOpen(id) }, onPill: { onStart(id) })
                }
            }
        }
    }

    private func chipLabel(_ title: String, symbol: String, on: Bool) -> some View {
        Label(title, systemImage: symbol)
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(on ? Color.lxBackground : AppModule.interpret.ink)
            .padding(.horizontal, 14)
            .frame(height: 36)
            .background(on ? AppModule.interpret.ink : Color.lxBackground.opacity(0.8), in: .capsule)
            .frame(minHeight: 44)
    }

    private func chip(_ title: String, symbol: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { chipLabel(title, symbol: symbol, on: on) }
            .buttonStyle(.plain)
    }
}

/// 一条传译记录的全文：对照、原文、译文三种显示，可以复制、导出、让 AI 总结要点、接着录
struct InterpretRecordPage: View {
    let id: UUID
    @ObservedObject var store: ModuleStore
    let onBack: () -> Void
    let onContinue: () -> Void

    @AppStorage("interpreter.display") private var display = 0
    @State private var renaming = false
    @State private var renameText = ""
    @State private var confirmDelete = false
    @State private var summarizing = false
    @State private var summaryError: String?
    @ObservedObject private var ai = AISettings.shared

    var body: some View {
        if let record = store.interpretation(id) {
            VStack(spacing: 0) {
                ModuleTopBar(title: record.title, onBack: onBack) {
                    Menu {
                        Button("改名", systemImage: "pencil") {
                            renameText = record.title
                            renaming = true
                        }
                        Button("删除", systemImage: "trash", role: .destructive) { confirmDelete = true }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 17, weight: .medium))
                            .frame(width: 44, height: 44)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .accessibilityLabel("更多")
                }
                Text("\(record.createdAt.formatted(.dateTime.month().day().hour().minute())) · \(record.lines.count) 句 · \(AudioReplayButton.format(record.duration)) · \(record.sourceIsChinese ? "中 → 英" : "英 → 中")")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                Picker("显示", selection: $display) {
                    Text("对照").tag(0)
                    Text("原文").tag(1)
                    Text("译文").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        if let summary = record.summary { summaryCard(summary) }
                        ForEach(record.lines) { line in
                            VStack(alignment: .leading, spacing: 3) {
                                if display != 2 {
                                    Text(line.original)
                                        .font(.system(size: display == 1 ? 17 : 14))
                                        .foregroundStyle(display == 1 ? .primary : .secondary)
                                }
                                if display != 1 {
                                    Text(line.translation).font(.system(size: 16, weight: .medium))
                                }
                            }
                            .textSelection(.enabled)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Color.clear.frame(height: 80)
                    }
                    .padding(.horizontal, 12)
                    .frame(maxWidth: 680)
                    .frame(maxWidth: .infinity)
                }
                .overlay(alignment: .bottom) { actionBar(record) }
            }
            .alert("改名", isPresented: $renaming) {
                TextField("名称", text: $renameText)
                Button("取消", role: .cancel) {}
                Button("保存") {
                    let name = renameText.trimmed
                    if !name.isEmpty { store.updateInterpretation(id) { $0.title = name } }
                }
            }
            .confirmationDialog("删除“\(record.title)”？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    store.deleteInterpretation(id)
                    onBack()
                }
            }
        } else {
            Color.clear.onAppear(perform: onBack)
        }
    }

    private func exportText(_ record: InterpretRecord) -> String {
        record.title + "\n\n" + record.lines.map { $0.original + "\n" + $0.translation }.joined(separator: "\n\n")
    }

    private func summaryCard(_ summary: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("要点", systemImage: "sparkles").font(.caption.weight(.bold)).foregroundStyle(Color.lxAI)
                Spacer()
                CopyButton(text: summary, label: "复制要点")
            }
            Text(summary).font(.subheadline).textSelection(.enabled)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.lxAISoft, in: .rect(cornerRadius: 16))
    }

    private func actionBar(_ record: InterpretRecord) -> some View {
        VStack(spacing: 6) {
            if let summaryError {
                Text(summaryError).font(.caption).foregroundStyle(Color.lxAI)
            }
            HStack(spacing: 8) {
                barButton("复制", "doc.on.doc") { Clipboard.copy(exportText(record)) }
                ShareLink(item: exportText(record)) {
                    barLabel("导出", "square.and.arrow.up")
                }
                .buttonStyle(.plain)
                barButton(summarizing ? "总结中…" : "要点", "sparkles", tint: .lxAI) { summarize(record) }
                    .disabled(summarizing)
                Button(action: onContinue) {
                    Label("继续", systemImage: "mic.fill")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Color.lxBackground)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.lxTranscriptInk, in: .capsule)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .padding(.top, 8)
        .background(.bar)
    }

    private func barLabel(_ title: String, _ symbol: String, tint: Color = .primary) -> some View {
        Label(title, systemImage: symbol)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .glassEffect(.regular.interactive(), in: .capsule)
    }

    private func barButton(_ title: String, _ symbol: String, tint: Color = .primary, action: @escaping () -> Void) -> some View {
        Button(action: action) { barLabel(title, symbol, tint: tint) }.buttonStyle(.plain)
    }

    private func summarize(_ record: InterpretRecord) {
        guard ai.isConfigured else {
            summaryError = "要先在设置里填写 AI 的 API Key。"
            return
        }
        summaryError = nil
        summarizing = true
        Task {
            defer { summarizing = false }
            do {
                Analytics.track(.interpretSummary)
                let response = try await AITasks.summarizeTranscript(record.lines, config: AIClient.currentConfig)
                store.updateInterpretation(id) { $0.summary = response.text }
            } catch {
                summaryError = error.localizedDescription
            }
        }
    }
}

// MARK: - 面对面对话

struct FaceHome<Top: View>: View {
    let top: Top
    @ObservedObject var store: ModuleStore
    let onOpen: (UUID) -> Void
    let onStart: () -> Void

    var body: some View {
        ModuleScroll(top: top) {
            VStack(spacing: 12) {
                Text("我说中文 · 对方说英语")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(AppModule.face.ink)
                    .padding(.horizontal, 14)
                    .frame(height: 36)
                    .background(Color.lxBackground.opacity(0.8), in: .capsule)
                Button(action: onStart) {
                    Label("开始对话", systemImage: "person.2.fill")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 28)
                        .frame(height: 56)
                        .background(AppModule.face.ink, in: .capsule)
                }
                .buttonStyle(.plain)
                Text("手机平放在桌上，上半屏倒过来给对方看；各自按自己的按钮说话，自动翻译并朗读。")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(AppModule.face.ink)
            }
            .padding(18)
            .frame(maxWidth: .infinity)
            .background(AppModule.face.card, in: .rect(cornerRadius: 24))

            ModuleCaption("对话记录")
            RecordList(ids: store.dialogs.map(\.id), emptyText: "还没有对话记录",
                       deleteTitle: { _ in "删除这次对话？" }, onDelete: { store.deleteDialog($0) }) { id in
                if let record = store.dialog(id) {
                    ModuleRow(module: .face, title: record.lines.first?.original ?? "对话",
                              meta: "\(record.createdAt.formatted(.dateTime.month().day().hour().minute())) · \(record.lines.count) 句",
                              pill: "查看", onOpen: { onOpen(id) }, onPill: { onOpen(id) })
                }
            }
        }
    }
}

struct DialogRecordPage: View {
    let id: UUID
    @ObservedObject var store: ModuleStore
    let onBack: () -> Void
    @State private var confirmDelete = false

    var body: some View {
        if let record = store.dialog(id) {
            VStack(spacing: 0) {
                ModuleTopBar(title: "面对面对话", onBack: onBack) {
                    Button { confirmDelete = true } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 16, weight: .medium))
                            .frame(width: 44, height: 44)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .accessibilityLabel("删除")
                }
                Text("\(record.createdAt.formatted(.dateTime.month().day().hour().minute())) · \(record.lines.count) 句")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                ScrollView {
                    DialogBubbles(lines: record.lines)
                        .padding(16)
                        .frame(maxWidth: 640)
                        .frame(maxWidth: .infinity)
                }
                HStack {
                    CopyButton(text: record.lines.map { $0.original + "\n" + $0.translation }.joined(separator: "\n\n"),
                               label: "复制全部对话", title: "复制全部")
                }
                .padding(.bottom, 8)
            }
            .confirmationDialog("删除这次对话？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    store.deleteDialog(id)
                    onBack()
                }
            }
        } else {
            Color.clear.onAppear(perform: onBack)
        }
    }
}

// MARK: - 场景练习

struct PracticeHome<Top: View>: View {
    let top: Top
    @ObservedObject var store: ModuleStore
    let onOpen: (UUID) -> Void
    let onStart: (PracticeRecord?) -> Void

    @ObservedObject private var ai = AISettings.shared
    @AppStorage("practice.level") private var level = 1
    @State private var showAll = false

    private var week: [PracticeEntry] { store.practicesThisWeek }

    var body: some View {
        ModuleScroll(top: top) {
            if !ai.isConfigured {
                Button { NotificationCenter.default.post(name: .openSettings, object: nil) } label: {
                    Label("场景练习要用 AI：先去设置里填写 API Key", systemImage: "sparkles")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.lxAI)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.lxAISoft, in: .rect(cornerRadius: 16))
                }
                .buttonStyle(.plain)
            }
            HStack(spacing: 8) {
                stat("\(week.count)", "本周练习", .primary)
                stat("\(week.flatMap(\.record.lines).filter { $0.better != nil }.count)", "处改得更地道", .lxAI)
                stat("\(week.reduce(0) { $0 + $1.record.phrases.count })", "个新说法", .lxSentenceInk)
            }
            Picker("难度", selection: $level) {
                ForEach(AITasks.practiceLevels.indices, id: \.self) { Text(AITasks.practiceLevels[$0]).tag($0) }
            }
            .pickerStyle(.segmented)

            HStack {
                ModuleCaption("选一个场景")
                Spacer()
                Button(showAll ? "收起" : "全部 \(PracticePreset.all.count) 个 · 自己描述") { showAll.toggle() }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppModule.practice.ink)
                    .buttonStyle(.plain)
                    .frame(minHeight: 32)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8)], spacing: 8) {
                ForEach(showAll ? PracticePreset.all : Array(PracticePreset.all.prefix(4))) { preset in
                    Button { onStart(PracticeRecord(scenario: preset.scenario, role: preset.role, level: level)) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Label(preset.title, systemImage: preset.symbol)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(AppModule.practice.ink)
                            Text("AI 是\(preset.role)").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(AppModule.practice.card, in: .rect(cornerRadius: 16))
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
                if showAll {
                    Button { onStart(nil) } label: {
                        Label("自己描述一个场景", systemImage: "square.and.pencil")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(AppModule.practice.ink)
                            .padding(.horizontal, 12)
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                            .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(AppModule.practice.ink.opacity(0.4), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])) }
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
            }

            ModuleCaption("练习记录")
            RecordList(ids: store.practices.map(\.id), emptyText: "还没有练习记录",
                       deleteTitle: { _ in "删除这次练习？" }, onDelete: { store.deletePractice($0) }) { id in
                if let entry = store.practice(id) {
                    let record = entry.record
                    ModuleRow(module: .practice, title: Self.title(record),
                              meta: "\(entry.createdAt.formatted(.dateTime.month().day().hour().minute())) · \(record.lines.count) 句 · \(record.lines.filter { $0.better != nil }.count) 处更地道",
                              pill: "再练", onOpen: { onOpen(id) },
                              onPill: { onStart(PracticeRecord(scenario: record.scenario, role: record.role, level: record.level)) })
                }
            }
        }
    }

    static func title(_ record: PracticeRecord) -> String {
        (record.scenario.components(separatedBy: "：").first ?? record.scenario) + " · AI 是" + (record.role.isEmpty ? "对方" : record.role)
    }

    private func stat(_ value: String, _ title: String, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.system(size: 22, weight: .bold, design: .rounded)).foregroundStyle(color)
            Text(title).font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 64)
        .background(Color.lxSurface, in: .rect(cornerRadius: 16))
    }
}

/// 一次练习的回看：对话和每句的“更地道”，右上角看小结（新说法可以加入生词本）
struct PracticeRecordPage: View {
    let id: UUID
    @ObservedObject var store: ModuleStore
    let onBack: () -> Void
    let onAgain: (PracticeRecord) -> Void

    @State private var showSummary = false
    @State private var confirmDelete = false

    var body: some View {
        if let entry = store.practice(id) {
            let record = entry.record
            VStack(spacing: 0) {
                ModuleTopBar(title: PracticeHome<EmptyView>.title(record), onBack: onBack) {
                    Menu {
                        Button("小结和新说法", systemImage: "list.star") { showSummary = true }
                        Button("删除", systemImage: "trash", role: .destructive) { confirmDelete = true }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 17, weight: .medium))
                            .frame(width: 44, height: 44)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .accessibilityLabel("更多")
                }
                Text(record.scenario).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.top, 8)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(record.lines) { line in
                            if line.isMine { mine(line) } else { partner(line) }
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: 680)
                    .frame(maxWidth: .infinity)
                }
                Button { onAgain(PracticeRecord(scenario: record.scenario, role: record.role, level: record.level)) } label: {
                    Label("再练一次", systemImage: "arrow.counterclockwise")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Color.lxBackground)
                        .padding(.horizontal, 24)
                        .frame(height: 48)
                        .background(Color.lxPracticeInk, in: .capsule)
                }
                .buttonStyle(.plain)
                .padding(.bottom, 12)
            }
            .sheet(isPresented: $showSummary) {
                PracticeSummary(record: record, onAgain: {
                    showSummary = false
                    onAgain(PracticeRecord(scenario: record.scenario, role: record.role, level: record.level))
                }, onDone: { showSummary = false })
                .presentationDetents([.large])
            }
            .confirmationDialog("删除这次练习？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    store.deletePractice(id)
                    onBack()
                }
            }
        } else {
            Color.clear.onAppear(perform: onBack)
        }
    }

    private func partner(_ line: PracticeLine) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(line.text).font(.system(size: 17))
                if let chinese = line.chinese, !chinese.isEmpty {
                    Text(chinese).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .textSelection(.enabled)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.lxSurface, in: .rect(cornerRadius: 18))
            Spacer(minLength: 40)
        }
    }

    private func mine(_ line: PracticeLine) -> some View {
        VStack(alignment: .trailing, spacing: 6) {
            Text(line.text)
                .font(.system(size: 17))
                .foregroundStyle(Color.lxOnAccent)
                .textSelection(.enabled)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.lxAccent, in: .rect(cornerRadius: 18))
            if let better = line.better { CorrectionNote(better: better, reason: line.reason) }
        }
        .padding(.leading, 48)
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}
