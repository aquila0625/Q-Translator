import Foundation

/// 会话和场景的本机存储：一个 JSON 文件，图片另存为 JPEG 文件。
@MainActor
final class ConversationStore: ObservableObject {
    @Published private(set) var scenes: [SceneGroup] = []
    /// 数组顺序就是列表里的顺序（同一场景内按出现先后）
    @Published private(set) var sessions: [ChatSession] = []

    private struct Snapshot: Codable {
        var scenes: [SceneGroup]
        var sessions: [ChatSession]
    }

    private var saveTask: Task<Void, Never>?
    private let imageCache = NSCache<NSString, PlatformImage>()

    static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("QTranslator", isDirectory: true)
        #if os(macOS)
        // 改名前（Lexpress）存的会话搬到新目录，Mac 上不丢记录
        let old = base.appendingPathComponent("Lexpress", isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path), FileManager.default.fileExists(atPath: old.path) {
            try? FileManager.default.moveItem(at: old, to: url)
        }
        #endif
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    static let imagesDirectory: URL = {
        let url = directory.appendingPathComponent("images", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    private static var fileURL: URL { directory.appendingPathComponent("conversations.json") }

    init() {
        if let data = try? Data(contentsOf: Self.fileURL),
           let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) {
            scenes = snapshot.scenes
            sessions = snapshot.sessions
        }
        // 上次退出时还在翻译的轮次，重新打开后标成失败，可以点重试
        for i in sessions.indices {
            for j in sessions[i].turns.indices where sessions[i].turns[j].state == .working {
                sessions[i].turns[j].state = .failed
                sessions[i].turns[j].errorMessage = "翻译被中断了"
            }
            for j in sessions[i].turns.indices {
                sessions[i].turns[j].isOptimizing = false
                // 旧版本把 AI 结果直接写进了 translation，这里拆回“机器翻译 + AI 结果”
                if var s = sessions[i].turns[j].sentence, s.calibrated, s.aiTranslation == nil {
                    s.aiTranslation = s.translation
                    s.translation = s.machineTranslation ?? s.translation
                    s.aiShown = true
                    s.calibrated = false
                    sessions[i].turns[j].sentence = s
                }
            }
        }
    }

    // MARK: 保存

    /// 合并短时间内的多次修改，只写一次文件
    func save() {
        saveTask?.cancel()
        let snapshot = Snapshot(scenes: scenes, sessions: sessions)
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(300))
            if Task.isCancelled { return }
            if let data = try? JSONEncoder().encode(snapshot) {
                try? data.write(to: Self.fileURL, options: .atomic)
            }
        }
    }

    /// 取出所有同声传译、面对面对话、场景练习的轮次（搬到各自模块里），并从会话里删掉；
    /// 因此变空的会话一起删掉
    func extractModuleTurns() -> [Turn] {
        var extracted: [Turn] = []
        var emptied: Set<UUID> = []
        for i in sessions.indices {
            let isModule: (Turn) -> Bool = { $0.transcript != nil || $0.dialog != nil || $0.practice != nil }
            let moving = sessions[i].turns.filter(isModule)
            guard !moving.isEmpty else { continue }
            extracted += moving
            sessions[i].turns.removeAll(where: isModule)
            if sessions[i].turns.isEmpty { emptied.insert(sessions[i].id) }
        }
        sessions.removeAll { emptied.contains($0.id) }
        if !extracted.isEmpty { save() }
        return extracted
    }

    // MARK: 会话

    func session(_ id: UUID) -> ChatSession? { sessions.first { $0.id == id } }

    func sessions(in sceneID: UUID?) -> [ChatSession] { sessions.filter { $0.sceneID == sceneID } }

    @discardableResult
    func createSession(title: String?, sceneID: UUID?, aiEnabled: Bool) -> ChatSession {
        let name = title?.trimmed ?? ""
        let session = ChatSession(title: name.isEmpty ? ChatSession.defaultTitle : name, autoTitled: name.isEmpty,
                                  sceneID: sceneID, aiEnabled: aiEnabled)
        sessions.insert(session, at: 0)
        save()
        return session
    }

    func updateSession(_ id: UUID, _ change: (inout ChatSession) -> Void) {
        guard let i = sessions.firstIndex(where: { $0.id == id }) else { return }
        change(&sessions[i])
        save()
    }

    func renameSession(_ id: UUID, to title: String) {
        let name = title.trimmed
        guard !name.isEmpty else { return }
        updateSession(id) {
            $0.title = name
            $0.autoTitled = false
        }
    }

    func moveSession(_ id: UUID, to sceneID: UUID?) {
        updateSession(id) { $0.sceneID = sceneID }
    }

    /// 拖动会话：放到某个会话前面（就进入那个会话所在的场景），或放到某个场景的最上面
    func moveSession(_ id: UUID, toScene sceneID: UUID?, before targetID: UUID?) {
        guard id != targetID, let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        var session = sessions.remove(at: index)
        session.sceneID = sceneID
        if let targetID, let target = sessions.firstIndex(where: { $0.id == targetID }) {
            sessions.insert(session, at: target)
        } else if let first = sessions.firstIndex(where: { $0.sceneID == sceneID }) {
            sessions.insert(session, at: first)
        } else {
            sessions.append(session)
        }
        save()
    }

    /// 拖动会话：放到某个会话后面，并进入那个会话所在的场景
    func moveSession(_ id: UUID, toScene sceneID: UUID?, after targetID: UUID) {
        guard id != targetID, let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        var session = sessions.remove(at: index)
        session.sceneID = sceneID
        if let target = sessions.firstIndex(where: { $0.id == targetID }) {
            sessions.insert(session, at: target + 1)
        } else {
            sessions.append(session)
        }
        save()
    }

    func deleteSession(_ id: UUID) {
        guard let session = session(id) else { return }
        session.turns.flatMap(\.images).forEach { deleteImageFile($0.fileName) }
        sessions.removeAll { $0.id == id }
        save()
    }

    /// 编辑列表里拖动之后：按新顺序排列，并按每个会话上方最近的场景标题重新归类
    func applyOrder(_ rows: [(sessionID: UUID, sceneID: UUID?)]) {
        let byID = Dictionary(uniqueKeysWithValues: sessions.map { ($0.id, $0) })
        var reordered: [ChatSession] = rows.compactMap { row in
            guard var s = byID[row.sessionID] else { return nil }
            s.sceneID = row.sceneID
            return s
        }
        // 不在列表里的（不应该发生）放回末尾，避免丢数据
        let listed = Set(rows.map(\.sessionID))
        reordered += sessions.filter { !listed.contains($0.id) }
        sessions = reordered
        save()
    }

    // MARK: 场景

    func scene(_ id: UUID?) -> SceneGroup? {
        guard let id else { return nil }
        return scenes.first { $0.id == id }
    }

    @discardableResult
    func createScene(name: String, cover: SceneCover) -> SceneGroup {
        let scene = SceneGroup(name: name.trimmed.isEmpty ? "新场景" : name.trimmed, cover: cover)
        scenes.append(scene)
        Analytics.track(.sceneNew)
        save()
        return scene
    }

    func updateScene(_ scene: SceneGroup) {
        guard let i = scenes.firstIndex(where: { $0.id == scene.id }) else { return }
        scenes[i] = scene
        save()
    }

    /// 删除场景。默认里面的会话移到列表最下面；deleteSessions 为 true 时连同会话一起删除
    func deleteScene(_ id: UUID, deleteSessions: Bool = false) {
        if deleteSessions {
            for session in sessions where session.sceneID == id {
                session.turns.flatMap(\.images).forEach { deleteImageFile($0.fileName) }
            }
            sessions.removeAll { $0.sceneID == id }
        } else {
            for i in sessions.indices where sessions[i].sceneID == id { sessions[i].sceneID = nil }
        }
        scenes.removeAll { $0.id == id }
        save()
    }

    func moveScenes(from source: IndexSet, to destination: Int) {
        scenes.move(fromOffsets: source, toOffset: destination)
        save()
    }

    // MARK: 轮次

    func turn(_ sessionID: UUID, _ turnID: UUID) -> Turn? {
        session(sessionID)?.turns.first { $0.id == turnID }
    }

    func appendTurn(_ turn: Turn, to sessionID: UUID) {
        updateSession(sessionID) {
            $0.turns.append(turn)
            $0.updatedAt = Date()
        }
    }

    func updateTurn(_ sessionID: UUID, _ turnID: UUID, _ change: (inout Turn) -> Void) {
        guard let i = sessions.firstIndex(where: { $0.id == sessionID }),
              let j = sessions[i].turns.firstIndex(where: { $0.id == turnID }) else { return }
        change(&sessions[i].turns[j])
        save()
    }

    func deleteTurn(_ sessionID: UUID, _ turnID: UUID) {
        turn(sessionID, turnID)?.images.forEach { deleteImageFile($0.fileName) }
        if let audio = turn(sessionID, turnID)?.audioFile { Self.deleteMediaFile(audio) }
        updateSession(sessionID) { $0.turns.removeAll { $0.id == turnID } }
    }

    // MARK: 图片和录音文件

    /// 录音等其他文件和图片放在同一个文件夹
    static func mediaURL(_ name: String) -> URL { imagesDirectory.appendingPathComponent(name) }

    static func deleteMediaFile(_ name: String) {
        try? FileManager.default.removeItem(at: mediaURL(name))
    }

    static func saveImage(_ image: PlatformImage) -> String? {
        guard let data = image.storageData else { return nil }
        let name = UUID().uuidString + ".jpg"
        do {
            try data.write(to: imagesDirectory.appendingPathComponent(name), options: .atomic)
            return name
        } catch {
            return nil
        }
    }

    func image(named name: String) -> PlatformImage? {
        if let cached = imageCache.object(forKey: name as NSString) { return cached }
        guard let image = PlatformImage(contentsOfFile: Self.imagesDirectory.appendingPathComponent(name).path) else { return nil }
        imageCache.setObject(image, forKey: name as NSString)
        return image
    }

    func replaceImageFile(_ name: String, with image: PlatformImage) {
        guard let data = image.storageData else { return }
        try? data.write(to: Self.imagesDirectory.appendingPathComponent(name), options: .atomic)
        imageCache.setObject(image, forKey: name as NSString)
    }

    func deleteImageFile(_ name: String) {
        imageCache.removeObject(forKey: name as NSString)
        try? FileManager.default.removeItem(at: Self.imagesDirectory.appendingPathComponent(name))
    }
}
