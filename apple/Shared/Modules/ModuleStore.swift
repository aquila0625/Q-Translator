import Foundation

/// 三个针对特定场景的模块：同声传译、面对面对话、场景练习。它们不属于某个翻译会话，各有自己的记录。
enum AppModule: String, Identifiable, CaseIterable {
    case interpret, face, practice

    var id: String { rawValue }

    var title: String {
        switch self {
        case .interpret: "同声传译"
        case .face: "面对面"
        case .practice: "场景练习"
        }
    }

    var fullTitle: String { self == .face ? "面对面对话" : title }

    var symbol: String {
        switch self {
        case .interpret: "captions.bubble"
        case .face: "person.2"
        case .practice: "theatermasks"
        }
    }
}

/// 一条同声传译记录
struct InterpretRecord: Codable, Identifiable, Equatable {
    var id = UUID()
    var title: String
    var createdAt = Date()
    var updatedAt = Date()
    var sourceIsChinese: Bool
    /// 用的是中英自动识别
    var autoLanguage: Bool?
    var lines: [TranscriptLine] = []
    /// 收音时长（秒），继续录时累加
    var duration: Double = 0
    /// AI 总结的要点（有 Key 时可以生成）
    var summary: String?
    /// 录音，继续录时一段一段接在后面；文件在 ConversationStore.mediaURL 里
    var audio: [AudioPart]?
    /// AI 精校：整段录音交给 OpenAI 重新识别、整篇重新翻译的结果
    var refined: [TranscriptLine]?
    var refinedAt: Date?
    /// 正在看精校版（否则看实时字幕）
    var showsRefined: Bool?

    struct AudioPart: Codable, Equatable {
        var name: String
        var duration: Double
    }

    var audioDuration: Double { (audio ?? []).reduce(0) { $0 + $1.duration } }

    /// 正在显示的那一版字幕
    var shownLines: [TranscriptLine] { showsRefined == true ? refined ?? lines : lines }

    static func defaultTitle(_ date: Date) -> String {
        date.formatted(.dateTime.month(.defaultDigits).day().hour().minute()) + " 传译"
    }
}

/// 一次面对面对话
struct DialogRecord: Codable, Identifiable, Equatable {
    var id = UUID()
    var createdAt = Date()
    var lines: [DialogLine] = []
}

/// 一次场景练习
struct PracticeEntry: Codable, Identifiable, Equatable {
    var id = UUID()
    var createdAt = Date()
    var record: PracticeRecord
}

/// 三个模块的记录，存在本机的一个 JSON 文件里。每个列表最新的在前面。
@MainActor
final class ModuleStore: ObservableObject {
    static let shared = ModuleStore()

    @Published private(set) var interpretations: [InterpretRecord] = []
    @Published private(set) var dialogs: [DialogRecord] = []
    @Published private(set) var practices: [PracticeEntry] = []

    private struct Snapshot: Codable {
        var interpretations: [InterpretRecord]
        var dialogs: [DialogRecord]
        var practices: [PracticeEntry]
    }

    private var saveTask: Task<Void, Never>?
    private static var fileURL: URL { ConversationStore.directory.appendingPathComponent("modules.json") }

    private init() {
        if let data = try? Data(contentsOf: Self.fileURL),
           let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) {
            interpretations = snapshot.interpretations
            dialogs = snapshot.dialogs
            practices = snapshot.practices
        }
    }

    private func save() {
        saveTask?.cancel()
        let snapshot = Snapshot(interpretations: interpretations, dialogs: dialogs, practices: practices)
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(300))
            if Task.isCancelled { return }
            if let data = try? JSONEncoder().encode(snapshot) {
                try? data.write(to: Self.fileURL, options: .atomic)
            }
        }
    }

    // MARK: 同声传译

    func interpretation(_ id: UUID) -> InterpretRecord? { interpretations.first { $0.id == id } }

    /// 结束一段传译：新的一条放在最前面；继续录的接在原来那条后面，时长累加，句子的时间接着前面的录音往后算。
    /// 没说话就不存（录音也删掉）。返回记录的 ID
    @discardableResult
    func saveInterpretation(_ lines: [TranscriptLine], duration: Double, sourceIsChinese: Bool, autoLanguage: Bool,
                            audio: InterpretRecord.AudioPart?, into id: UUID?) -> UUID? {
        if let id, let i = interpretations.firstIndex(where: { $0.id == id }) {
            var record = interpretations.remove(at: i)
            let offset = record.audioDuration
            record.lines += lines.map { line in
                var line = line
                line.start = line.start.map { $0 + offset }
                line.end = line.end.map { $0 + offset }
                return line
            }
            record.duration += duration
            if autoLanguage { record.autoLanguage = true }
            if let audio { record.audio = (record.audio ?? []) + [audio] }
            if !lines.isEmpty { record.updatedAt = Date() }
            interpretations.insert(record, at: 0)
            save()
            return id
        }
        guard !lines.isEmpty else {
            if let audio { ConversationStore.deleteMediaFile(audio.name) }
            return nil
        }
        let now = Date()
        let record = InterpretRecord(title: InterpretRecord.defaultTitle(now), createdAt: now, updatedAt: now,
                                     sourceIsChinese: sourceIsChinese, autoLanguage: autoLanguage ? true : nil, lines: lines, duration: duration,
                                     audio: audio.map { [$0] })
        interpretations.insert(record, at: 0)
        save()
        return record.id
    }

    func updateInterpretation(_ id: UUID, _ change: (inout InterpretRecord) -> Void) {
        guard let i = interpretations.firstIndex(where: { $0.id == id }) else { return }
        change(&interpretations[i])
        save()
    }

    /// 改正在显示的那一版里的一句
    func updateLine(_ id: UUID, _ lineID: UUID, _ change: (inout TranscriptLine) -> Void) {
        updateInterpretation(id) { record in
            if record.showsRefined == true, let i = record.refined?.firstIndex(where: { $0.id == lineID }) {
                change(&record.refined![i])
            } else if let i = record.lines.firstIndex(where: { $0.id == lineID }) {
                change(&record.lines[i])
            }
        }
    }

    func deleteInterpretation(_ id: UUID) {
        interpretation(id)?.audio?.forEach { ConversationStore.deleteMediaFile($0.name) }
        interpretations.removeAll { $0.id == id }
        save()
    }

    // MARK: 面对面对话

    func dialog(_ id: UUID) -> DialogRecord? { dialogs.first { $0.id == id } }

    func addDialog(_ lines: [DialogLine]) {
        guard !lines.isEmpty else { return }
        dialogs.insert(DialogRecord(lines: lines), at: 0)
        save()
    }

    func deleteDialog(_ id: UUID) {
        dialogs.removeAll { $0.id == id }
        save()
    }

    // MARK: 场景练习

    func practice(_ id: UUID) -> PracticeEntry? { practices.first { $0.id == id } }

    /// 练习过程中每一轮都存一下：第一次新建，之后更新同一条。我还没说过话时不存。返回记录的 ID
    @discardableResult
    func savePractice(_ record: PracticeRecord, into id: UUID?) -> UUID? {
        guard record.lines.contains(where: \.isMine) else { return id }
        if let id, let i = practices.firstIndex(where: { $0.id == id }) {
            practices[i].record = record
            save()
            return id
        }
        let entry = PracticeEntry(record: record)
        practices.insert(entry, at: 0)
        save()
        return entry.id
    }

    func deletePractice(_ id: UUID) {
        practices.removeAll { $0.id == id }
        save()
    }

    /// 最近 7 天的练习
    var practicesThisWeek: [PracticeEntry] {
        let start = Date().addingTimeInterval(-7 * 24 * 3600)
        return practices.filter { $0.createdAt >= start }
    }

    // MARK: 从会话迁出

    /// 以前这三种记录存在翻译会话里，作为特殊的一轮。第一次启动新版时把它们搬到各自模块里（只做一次）
    func migrate(from store: ConversationStore) {
        let key = "modules.migrated"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        let turns = store.extractModuleTurns()
        guard !turns.isEmpty else { return }
        for turn in turns {
            if let lines = turn.transcript {
                interpretations.append(InterpretRecord(title: InterpretRecord.defaultTitle(turn.createdAt), createdAt: turn.createdAt,
                                                       updatedAt: turn.createdAt, sourceIsChinese: turn.sourceIsChinese,
                                                       lines: lines, duration: turn.transcriptDuration ?? 0))
            } else if let lines = turn.dialog {
                dialogs.append(DialogRecord(createdAt: turn.createdAt, lines: lines))
            } else if let record = turn.practice {
                practices.append(PracticeEntry(createdAt: turn.createdAt, record: record))
            }
        }
        interpretations.sort { $0.updatedAt > $1.updatedAt }
        dialogs.sort { $0.createdAt > $1.createdAt }
        practices.sort { $0.createdAt > $1.createdAt }
        save()
    }
}

/// 要打开哪个模块、要不要直接开始：抽屉、菜单栏和主屏快捷操作都通过它
@MainActor
final class ModuleRouter: ObservableObject {
    static let shared = ModuleRouter()

    enum Launch: Equatable {
        case interpret(continuing: UUID?)
        case face
        case practice(seed: PracticeRecord?)
    }

    /// 正在看的模块（iPhone 上全屏盖在翻译上；Mac 和 iPad 显示在右边）
    @Published var module: AppModule?
    /// 进入模块后马上开始的那件事（例如主屏快捷操作“同声传译”直接开录）
    @Published var launch: Launch?
    /// 主屏快捷操作“新建翻译”
    @Published var newSessionRequested = false

    func open(_ module: AppModule, start: Bool = false, from: String = "drawer") {
        if self.module != module { Analytics.track(.moduleOpen, ["module": module.rawValue, "from": from]) }
        self.module = module
        guard start else { return }
        switch module {
        case .interpret: launch = .interpret(continuing: nil)
        case .face: launch = .face
        case .practice: launch = nil
        }
    }

    /// 主屏快捷操作的类型
    func handleShortcut(_ type: String) {
        Analytics.track(.quickAction, ["type": type])
        switch type {
        case "interpret": open(.interpret, start: true, from: "shortcut")
        case "face": open(.face, start: true, from: "shortcut")
        case "practice": open(.practice, from: "shortcut")
        case "new":
            module = nil
            newSessionRequested = true
        default: break
        }
    }
}
