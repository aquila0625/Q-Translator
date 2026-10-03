import SwiftUI

enum DrawerAction {
    case newSession, editScene(UUID), newScene, starred, settings
}

/// 抽屉：搜索、生词本和编辑，两层的场景和会话列表，底部是设置和新建。
/// 平时长按会话就能拖着排序或换场景，其它行实时让位；点“编辑”出现拖动把手。
struct DrawerView: View {
    @ObservedObject var controller: ConversationController
    @ObservedObject var store: ConversationStore
    let onSelect: () -> Void
    let onAction: (DrawerAction) -> Void

    @ObservedObject private var router = ModuleRouter.shared
    @AppStorage("drawer.collapsed") private var collapsedRaw = ""
    @State private var query = ""
    @State private var editing = false
    @State private var renaming: ChatSession?
    @State private var renameText = ""
    @State private var deleting: ChatSession?
    @State private var dropTarget: String?
    /// 拖着会话停在哪个场景上；停够 1 秒后 armedScene 就是它，标题高亮并自动展开
    @State private var hoverScene: String?
    @State private var armedScene: String?
    /// 左滑露出删除按钮的那一行（同一时间只开一行）
    @State private var swipedRow: String?
    @State private var deletingScene: SceneGroup?
    #if os(iOS)
    // 长按拖动排序：被拖的那一行浮起来跟着手指走，其它行实时让位
    @State private var frames = RowFrames()
    @State private var dragID: UUID?
    @State private var dragPoint: CGPoint = .zero
    @State private var grabOffset: CGFloat = 0
    @State private var dragEndedAt = Date.distantPast
    /// 拖动中换过场景：松手后重建一次列表（懒加载列表里跨分组移动的行有时不重绘）
    @State private var draggedAcrossScenes = false
    @State private var listToken = 0
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !editing {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("搜索会话和翻译", text: $query)
                        .textFieldStyle(.plain)
                        .submitLabel(.search)
                }
                .padding(.horizontal, 14)
                .frame(height: 44)
                .background(Color.lxSurface, in: .capsule)

                ModuleTiles(active: router.module) { module in
                    router.open(module)
                    onSelect()
                }
            }

            HStack(spacing: 4) {
                if !editing {
                    Button { onAction(.starred) } label: {
                        Label("生词本", systemImage: "star")
                            .font(.callout.weight(.semibold))
                            .padding(.horizontal, 10)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                } else {
                    Text("拖动排序，或拖到别的场景下面").font(.footnote).foregroundStyle(.secondary).padding(.leading, 6)
                }
                Spacer()
                Button(editing ? "完成" : "编辑") {
                    withAnimation(.snappy) { editing.toggle() }
                }
                .buttonStyle(.borderless)
                .font(.callout.weight(.semibold))
                .frame(minWidth: 44, minHeight: 44)
            }

            if editing {
                DrawerEditList(store: store, onEditScene: { onAction(.editScene($0)) }, onNewScene: { onAction(.newScene) },
                               onDeleteSession: { deleting = $0 }, onDeleteScene: { deletingScene = $0 })
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2, pinnedViews: [.sectionHeaders]) {
                        if query.trimmed.isEmpty {
                            ForEach(store.scenes) { sceneSection($0) }
                            unsortedSection
                        } else {
                            searchResults
                        }
                    }
                    #if os(iOS)
                    .id(listToken)
                    .coordinateSpace(.named("drawerList"))
                    .overlay(alignment: .topLeading) {
                        if let id = dragID, let session = store.session(id) {
                            sessionLabel(session, snippet: nil)
                                .background(Color.lxBackground, in: .rect(cornerRadius: 14))
                                .overlay { RoundedRectangle(cornerRadius: 14).stroke(Color.lxAccent.opacity(0.5), lineWidth: 1.5) }
                                .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
                                .scaleEffect(1.03)
                                .offset(y: dragPoint.y - grabOffset)
                                .allowsHitTesting(false)
                        }
                    }
                    .gesture(LongPressDrag(onBegan: beginDrag, onChanged: moveDrag, onEnded: endDrag))
                    #endif
                }
                .scrollIndicators(.hidden)
            }

            Divider()
            HStack {
                Button { onAction(.settings) } label: {
                    Label("设置", systemImage: "gearshape")
                        .font(.callout.weight(.semibold))
                        .padding(.horizontal, 10)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                Spacer()
                Button { onAction(.newSession) } label: {
                    Label("新建", systemImage: "square.and.pencil")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Color.lxOnAccent)
                        .padding(.horizontal, 16)
                        .frame(height: 40)
                        .background(Color.lxAccent, in: .capsule)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("新建会话")
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color.lxBackground)
        .alert("重命名会话", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("名称", text: $renameText)
            Button("取消", role: .cancel) {}
            Button("保存") { if let renaming { store.renameSession(renaming.id, to: renameText) } }
        }
        .confirmationDialog("删除这个会话？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) { if let deleting { controller.deleteSession(deleting.id) } }
        } message: {
            Text("会话里的翻译和图片都会被删除，不能恢复。")
        }
        .sheet(item: $deletingScene) { scene in
            DeleteSceneSheet(controller: controller, store: store, scene: scene)
        }
    }

    // MARK: 场景分组

    private func key(_ scene: SceneGroup?) -> String { scene?.id.uuidString ?? "none" }

    private func isCollapsed(_ scene: SceneGroup?) -> Bool {
        collapsedRaw.split(separator: ",").contains(Substring(key(scene)))
    }

    private func setCollapsed(_ scene: SceneGroup?, _ collapsed: Bool) {
        var set = Set(collapsedRaw.split(separator: ",").map(String.init))
        if collapsed { set.insert(key(scene)) } else { set.remove(key(scene)) }
        collapsedRaw = set.joined(separator: ",")
    }

    private func sceneSection(_ scene: SceneGroup) -> some View {
        let sessions = store.sessions(in: scene.id)
        let collapsed = isCollapsed(scene)
        return Section {
            if !collapsed {
                ForEach(sessions) { sessionRow($0) }
                if sessions.isEmpty {
                    // 空场景也能把会话拖进来
                    Text(armedScene == key(scene) ? "松手放进“\(scene.name)”" : "还没有会话。点右边的新建按钮，或把会话拖到这里")
                        .font(.footnote)
                        .foregroundStyle(armedScene == key(scene) ? Color.lxAccent : .secondary)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.leading, 18)
                        .background(armedScene == key(scene) ? Color.lxAccentSoft : Color.clear, in: .rect(cornerRadius: 14))
                        .contentShape(.rect)
                        #if os(iOS)
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("drawerList")) } action: { frames.zones["e-" + key(scene)] = $0 }
                        .onDisappear { frames.zones["e-" + key(scene)] = nil }
                        #else
                        .dropDestination(for: String.self) { items, _ in
                            drop(items, into: scene, before: nil)
                        } isTargeted: { hover(scene, $0) }
                        #endif
                }
            }
        } header: {
            sceneHeader(scene, count: sessions.count, collapsed: collapsed)
        }
    }

    /// 不属于任何场景的会话：放在最下面，不显示“未分类”标题
    @ViewBuilder
    private var unsortedSection: some View {
        let sessions = store.sessions(in: nil)
        if !sessions.isEmpty {
            Section {
                ForEach(sessions) { sessionRow($0) }
            } header: {
                // 空白的吸顶标题：滑到这里时把上一个场景的标题顶走，免得看起来像属于那个场景
                Color.lxBackground.frame(height: store.scenes.isEmpty ? 0 : 14)
            }
        } else if !store.scenes.isEmpty {
            // 没有“不属于场景”的会话时，留一块地方，把会话拖到这里就移出场景
            let armed = armedScene == key(nil)
            Label(armed ? "松手移出场景" : "拖到这里，移出场景", systemImage: "tray.and.arrow.down")
                .font(.footnote)
                .foregroundStyle(armed ? Color.lxAccent : .secondary)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background {
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(armed ? Color.lxAccent : Color.secondary.opacity(0.35),
                                      style: StrokeStyle(lineWidth: 1.5, dash: armed ? [] : [5, 4]))
                        .background(armed ? Color.lxAccentSoft : Color.clear, in: .rect(cornerRadius: 14))
                }
                .padding(.top, 16)
                .contentShape(.rect)
                #if os(iOS)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("drawerList")) } action: { frames.zones["none"] = $0 }
                .onDisappear { frames.zones["none"] = nil }
                #else
                .dropDestination(for: String.self) { items, _ in
                    drop(items, into: nil, before: nil)
                } isTargeted: { hover(nil, $0) }
                #endif
        }
    }

    // MARK: 拖动

    private func drop(_ items: [String], into scene: SceneGroup?, before target: UUID?) -> Bool {
        guard let id = items.first.flatMap(UUID.init), id != target else { return false }
        withAnimation(.snappy) { store.moveSession(id, toScene: scene?.id, before: target) }
        if let scene { setCollapsed(scene, false) }
        hoverScene = nil
        armedScene = nil
        return true
    }

    /// 拖着会话停在某个场景上 1 秒：高亮这个场景、自动展开，表示可以放进去
    private func hover(_ scene: SceneGroup?, _ inside: Bool) {
        let k = key(scene)
        if inside {
            guard hoverScene != k else { return }
            hoverScene = k
            armedScene = nil
            Task {
                try? await Task.sleep(for: .seconds(1))
                guard hoverScene == k else { return }
                withAnimation(.snappy) {
                    armedScene = k
                    setCollapsed(scene, false)
                }
            }
        } else if hoverScene == k {
            hoverScene = nil
            armedScene = nil
        }
    }

    /// 场景标题：比会话行矮、颜色淡，不抢会话的注意力。右边依次是新建会话、编辑场景、展开收起。往上滑时吸在顶部。
    private func sceneHeader(_ scene: SceneGroup, count: Int, collapsed: Bool) -> some View {
        let armed = armedScene == key(scene)
        return SwipeToDelete(id: "h-" + key(scene), openRow: $swipedRow, onDelete: { deletingScene = scene }) {
            sceneHeaderContent(scene, count: count, collapsed: collapsed, armed: armed)
        }
        .padding(.top, 6)
        .padding(.bottom, 2)
        .background(Color.lxBackground)
        #if os(iOS)
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("drawerList")) } action: { frames.zones["h-" + key(scene)] = $0 }
        .onDisappear { frames.zones["h-" + key(scene)] = nil }
        #else
        // 把会话拖到场景标题上：移到这个场景的最上面
        .dropDestination(for: String.self) { items, _ in
            drop(items, into: scene, before: nil)
        } isTargeted: { hover(scene, $0) }
        #endif
    }

    private func sceneHeaderContent(_ scene: SceneGroup, count: Int, collapsed: Bool, armed: Bool) -> some View {
        let toggle = {
            if swipedRow != nil { swipedRow = nil; return }
            withAnimation(.snappy) { setCollapsed(scene, !collapsed) }
        }
        return HStack(spacing: 0) {
            Button(action: toggle) {
                HStack(spacing: 8) {
                    SceneCoverView(cover: scene.cover, size: 26)
                    Text(scene.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(armed ? "松手放进来" : "\(count)")
                        .font(.caption)
                        .foregroundStyle(armed ? Color.lxAccent : .secondary)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(scene.name)，\(count) 个会话")
            .accessibilityHint(collapsed ? "展开" : "收起")

            Button {
                controller.newSession(sceneID: scene.id)
                onSelect()
            } label: {
                Image(systemName: "square.and.pencil").font(.footnote.weight(.medium)).frame(width: 38, height: 44).contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("在\(scene.name)里新建会话")

            Button { onAction(.editScene(scene.id)) } label: {
                Image(systemName: "slider.horizontal.3").font(.footnote.weight(.medium)).frame(width: 38, height: 44).contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("编辑场景\(scene.name)的名称和图标")

            Button(action: toggle) {
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
                    .rotationEffect(.degrees(collapsed ? -90 : 0))
                    .frame(width: 34, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(collapsed ? "展开" : "收起")
        }
        .foregroundStyle(.secondary)
        .padding(.leading, 8)
        .padding(.trailing, 2)
        #if os(macOS)
        .contextMenu {
            Button("在这个场景里新建会话", systemImage: "square.and.pencil") {
                controller.newSession(sceneID: scene.id)
                onSelect()
            }
            Button("编辑场景", systemImage: "slider.horizontal.3") { onAction(.editScene(scene.id)) }
            Button("删除场景…", systemImage: "trash", role: .destructive) { deletingScene = scene }
        }
        #endif
        // 很淡的底色，和下面的会话区分开
        .background(armed ? Color.lxAccentSoft : Color.lxSurface.opacity(0.7), in: .rect(cornerRadius: 12))
        .overlay {
            if armed { RoundedRectangle(cornerRadius: 12).stroke(Color.lxAccent, lineWidth: 2) }
        }
        .contentShape(.rect)
    }

    private func sessionRow(_ session: ChatSession, snippet: String? = nil) -> some View {
        #if os(iOS)
        ZStack {
            if dragID == session.id {
                // 被拖走的那一行在列表里留一个空位，浮起来的那份跟着手指走
                Color.clear.frame(height: 50)
            } else {
                SwipeToDelete(id: "s-" + session.id.uuidString, openRow: $swipedRow, onDelete: { deleting = session }) {
                    sessionRowContent(session, snippet: snippet)
                }
                .transition(.identity)
            }
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("drawerList")) } action: { frames.sessions[session.id] = $0 }
        .onDisappear { frames.sessions[session.id] = nil }
        #else
        SwipeToDelete(id: "s-" + session.id.uuidString, openRow: $swipedRow, onDelete: { deleting = session }) {
            sessionRowContent(session, snippet: snippet)
        }
        #endif
    }

    private func sessionLabel(_ session: ChatSession, snippet: String?) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(session.title).font(.callout.weight(.semibold)).lineLimit(1)
                Text(snippet ?? session.lastSnippet).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Text(session.updatedAt.formatted(.relative(presentation: .named)))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(.leading, 18)
        .padding(.trailing, 10)
        .frame(minHeight: 50)
        .background(rowBackground(session), in: .rect(cornerRadius: 14))
    }

    private func sessionRowContent(_ session: ChatSession, snippet: String?) -> some View {
        Button {
            if swipedRow != nil { swipedRow = nil; return }
            #if os(iOS)
            // 刚拖完松手，不算点按
            if Date().timeIntervalSince(dragEndedAt) < 0.4 { return }
            #endif
            controller.select(session.id)
            onSelect()
        } label: {
            sessionLabel(session, snippet: snippet)
                .background(Color.lxBackground, in: .rect(cornerRadius: 14))
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        #if os(macOS)
        // 拖动：放到另一个会话上就排在它前面，并进入它所在的场景
        .draggable(session.id.uuidString) {
            Text(session.title)
                .font(.callout.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.lxAccentSoft, in: .capsule)
        }
        .dropDestination(for: String.self) { items, _ in
            drop(items, into: store.scene(session.sceneID), before: session.id)
        } isTargeted: { inside in
            dropTarget = inside ? "s-" + session.id.uuidString : (dropTarget == "s-" + session.id.uuidString ? nil : dropTarget)
            if let scene = store.scene(session.sceneID) { hover(scene, inside) }
        }
        .contextMenu {
            Button("重命名", systemImage: "pencil") {
                renameText = session.title
                renaming = session
            }
            Menu("移到场景", systemImage: "folder") {
                Button("不放进场景") { store.moveSession(session.id, toScene: nil, before: nil) }
                ForEach(store.scenes) { scene in
                    Button(scene.name) { store.moveSession(session.id, toScene: scene.id, before: nil) }
                }
            }
            Button("删除", systemImage: "trash", role: .destructive) { deleting = session }
        }
        #endif
    }

    #if os(iOS)
    // MARK: 长按拖动（iOS）

    private func beginDrag(_ point: CGPoint) {
        // 吸顶的场景标题盖在会话上面，按在标题上不算
        guard query.trimmed.isEmpty, !frames.zones.contains(where: { $0.key.hasPrefix("h-") && $0.value.contains(point) }),
              let hit = frames.sessions.first(where: { $0.value.contains(point) && store.session($0.key) != nil }) else { return }
        swipedRow = nil
        grabOffset = point.y - hit.value.minY
        dragPoint = point
        draggedAcrossScenes = false
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        withAnimation(.snappy(duration: 0.2)) { dragID = hit.key }
    }

    private func moveDrag(_ point: CGPoint) {
        guard let id = dragID, let dragged = store.session(id) else { return }
        dragPoint = point
        // 拖到场景标题或空场景上：放进这个场景的最上面
        for scene in store.scenes {
            let k = key(scene)
            if frames.zones["h-" + k]?.contains(point) == true || frames.zones["e-" + k]?.contains(point) == true {
                if dragged.sceneID != scene.id {
                    draggedAcrossScenes = true
                    withAnimation(.snappy) {
                        store.moveSession(id, toScene: scene.id, before: nil)
                        setCollapsed(scene, false)
                    }
                    UISelectionFeedbackGenerator().selectionChanged()
                }
                return
            }
        }
        if frames.zones["none"]?.contains(point) == true {
            if dragged.sceneID != nil {
                draggedAcrossScenes = true
                withAnimation(.snappy) { store.moveSession(id, toScene: nil, before: nil) }
            }
            return
        }
        // 拖过另一个会话：和它换位置，并进入它所在的场景
        guard let mine = frames.sessions[id],
              let hit = frames.sessions.first(where: { $0.key != id && $0.value.contains(point) }),
              let other = store.session(hit.key) else { return }
        if other.sceneID != dragged.sceneID { draggedAcrossScenes = true }
        withAnimation(.snappy) {
            if mine.minY < hit.value.minY {
                store.moveSession(id, toScene: other.sceneID, after: other.id)
            } else {
                store.moveSession(id, toScene: other.sceneID, before: other.id)
            }
        }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func endDrag() {
        guard dragID != nil else { return }
        dragEndedAt = Date()
        if draggedAcrossScenes {
            dragID = nil
            frames.sessions = [:]
            listToken += 1
        } else {
            withAnimation(.snappy(duration: 0.2)) { dragID = nil }
        }
    }
    #endif

    private func rowBackground(_ session: ChatSession) -> Color {
        if dropTarget == "s-" + session.id.uuidString { return Color.lxAccentSoft.opacity(0.6) }
        return session.id == controller.currentID ? Color.lxAccentSoft : Color.clear
    }

    @ViewBuilder
    private var searchResults: some View {
        let q = query.trimmed.lowercased()
        let matches = store.sessions.compactMap { session -> (ChatSession, String)? in
            if session.title.lowercased().contains(q) { return (session, session.lastSnippet) }
            if let turn = session.turns.last(where: { $0.searchableText.lowercased().contains(q) }) {
                return (session, turn.outlineText)
            }
            return nil
        }
        if matches.isEmpty {
            Text("没有找到“\(query)”").font(.callout).foregroundStyle(.secondary).padding(.top, 20)
        } else {
            ForEach(matches, id: \.0.id) { session, snippet in sessionRow(session, snippet: snippet) }
        }
    }
}

/// 抽屉的编辑状态：会话和场景都出现拖动把手。会话拖到哪个场景标题下面，就归到那个场景。
struct DrawerEditList: View {
    @ObservedObject var store: ConversationStore
    let onEditScene: (UUID) -> Void
    let onNewScene: () -> Void
    let onDeleteSession: (ChatSession) -> Void
    let onDeleteScene: (SceneGroup) -> Void

    @State private var tab = 0

    private enum Row: Identifiable {
        case header(SceneGroup?)
        case session(ChatSession)

        var id: String {
            switch self {
            case .header(let scene): "h-" + (scene?.id.uuidString ?? "none")
            case .session(let session): "s-" + session.id.uuidString
            }
        }
    }

    /// 和抽屉里一样的顺序：各个场景，最后是未分类
    private var rows: [Row] {
        var result: [Row] = []
        for scene in store.scenes {
            result.append(.header(scene))
            result += store.sessions(in: scene.id).map(Row.session)
        }
        result.append(.header(nil))
        result += store.sessions(in: nil).map(Row.session)
        return result
    }

    var body: some View {
        VStack(spacing: 6) {
            Picker("编辑", selection: $tab) {
                Text("会话").tag(0)
                Text("场景").tag(1)
            }
            .pickerStyle(.segmented)

            List {
                if tab == 0 {
                    ForEach(rows) { row in
                        switch row {
                        case .header(let scene):
                            HStack(spacing: 10) {
                                SceneCoverView(cover: scene?.cover, size: 28)
                                Text(scene?.name ?? "不在场景里").font(.callout.weight(.bold))
                            }
                            .moveDisabled(true)
                            .deleteDisabled(true)
                            .listRowBackground(Color.lxSurface)
                        case .session(let session):
                            Text(session.title).font(.callout).lineLimit(1).padding(.leading, 10)
                        }
                    }
                    .onMove(perform: moveSessions)
                    .onDelete(perform: deleteSessions)
                } else {
                    ForEach(store.scenes) { scene in
                        HStack(spacing: 10) {
                            SceneCoverView(cover: scene.cover, size: 30)
                            Text(scene.name).font(.callout)
                            Spacer()
                            Button { onEditScene(scene.id) } label: {
                                Image(systemName: "pencil").frame(width: 36, height: 36)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("编辑\(scene.name)的名称和图标")
                        }
                    }
                    .onMove { store.moveScenes(from: $0, to: $1) }
                    .onDelete { offsets in
                        if let i = offsets.first { onDeleteScene(store.scenes[i]) }
                    }

                    Button { onNewScene() } label: {
                        Label("新建场景", systemImage: "plus")
                    }
                }
            }
            .listStyle(.plain)
            #if os(iOS)
            .environment(\.editMode, .constant(.active))
            #endif
        }
    }

    private func moveSessions(from source: IndexSet, to destination: Int) {
        var list = rows
        list.move(fromOffsets: source, toOffset: destination)
        // 每个会话归到它上方最近的那个场景标题
        var currentScene: UUID?
        if case .header(let first)? = list.first { currentScene = first?.id }
        var order: [(sessionID: UUID, sceneID: UUID?)] = []
        for row in list {
            switch row {
            case .header(let scene): currentScene = scene?.id
            case .session(let session): order.append((session.id, currentScene))
            }
        }
        store.applyOrder(order)
    }

    private func deleteSessions(at offsets: IndexSet) {
        let list = rows
        for i in offsets {
            if case .session(let session) = list[i] { onDeleteSession(session) }
        }
    }
}

#if os(macOS)
/// Mac 上没有左滑：右键菜单里的“删除”，由调用方弹出确认
struct SwipeToDelete<Content: View>: View {
    let id: String
    @Binding var openRow: String?
    let onDelete: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        content.accessibilityAction(named: "删除") { onDelete() }
    }
}
#else
/// 左滑露出红色“删除”按钮；点按钮后由调用方弹出确认
struct SwipeToDelete<Content: View>: View {
    let id: String
    @Binding var openRow: String?
    let onDelete: () -> Void
    @ViewBuilder let content: Content

    @State private var drag: CGFloat = 0
    private let buttonWidth: CGFloat = 76

    var body: some View {
        let open = openRow == id
        let offset = min(0, max(-buttonWidth - 24, (open ? -buttonWidth : 0) + drag))
        ZStack(alignment: .trailing) {
            if offset < 0 {
                Button {
                    openRow = nil
                    onDelete()
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: "trash")
                        Text("删除").font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .frame(width: buttonWidth)
                    .frame(maxHeight: .infinity)
                    .background(Color.red, in: .rect(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("删除")
            }
            content.offset(x: offset)
        }
        // 只认横向滑动的手势，不和列表的上下滚动、长按拖动抢
        .gesture(HorizontalPan(
            onChange: { drag = $0 },
            onEnd: { translation in
                withAnimation(.snappy) {
                    let end = (open ? -buttonWidth : 0) + translation
                    openRow = end < -buttonWidth / 2 ? id : (open ? nil : openRow)
                    drag = 0
                }
            }
        ))
        .accessibilityAction(named: "删除") { onDelete() }
    }
}

#endif

/// 删除场景的确认：可以勾选“连同里面的会话一起删除”，默认不勾选
struct DeleteSceneSheet: View {
    @ObservedObject var controller: ConversationController
    @ObservedObject var store: ConversationStore
    let scene: SceneGroup

    @Environment(\.dismiss) private var dismiss
    @State private var alsoSessions = false

    var body: some View {
        let count = store.sessions(in: scene.id).count
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                SceneCoverView(cover: scene.cover, size: 44)
                Text("删除场景“\(scene.name)”？").font(.title3.weight(.bold))
            }
            if count > 0 {
                Button {
                    withAnimation(.snappy) { alsoSessions.toggle() }
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: alsoSessions ? "checkmark.square.fill" : "square")
                            .font(.title3)
                            .foregroundStyle(alsoSessions ? Color.red : .secondary)
                        Text("同时删除这个场景里的 \(count) 个会话").font(.body)
                    }
                    .frame(minHeight: 44)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(alsoSessions ? .isSelected : [])
                if alsoSessions {
                    Label("这 \(count) 个会话和里面所有的翻译、图片都会被删除，不能恢复。", systemImage: "exclamationmark.triangle.fill")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.red)
                } else {
                    Text("不勾选时，里面的会话会移到列表最下面，不会被删除。")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("这个场景里没有会话。").font(.callout).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button { dismiss() } label: {
                    Text("取消").frame(maxWidth: .infinity)
                }
                .buttonStyle(.glass)
                Button(role: .destructive) {
                    controller.deleteScene(scene.id, deleteSessions: alsoSessions)
                    dismiss()
                } label: {
                    Text(alsoSessions ? "删除场景和会话" : "删除场景").frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .tint(.red)
            }
            .controlSize(.large)
        }
        .padding(24)
        #if os(macOS)
        .frame(width: 440)
        #endif
        .presentationDetents([.height(alsoSessions ? 330 : 300)])
        .presentationDragIndicator(.visible)
    }
}

#if os(iOS)
/// 列表里各行的位置（相对列表内容），拖动时用来判断手指在哪一行上。不触发界面刷新。
final class RowFrames {
    var sessions: [UUID: CGRect] = [:]
    /// "h-场景"：场景标题；"e-场景"：空场景的提示；"none"：移出场景的虚线框
    var zones: [String: CGRect] = [:]
}

/// 长按后拖动。长按没成立之前手指一动就交给列表滚动
struct LongPressDrag: UIGestureRecognizerRepresentable {
    let onBegan: (CGPoint) -> Void
    let onChanged: (CGPoint) -> Void
    let onEnded: () -> Void

    func makeUIGestureRecognizer(context: Context) -> UILongPressGestureRecognizer {
        let recognizer = UILongPressGestureRecognizer()
        recognizer.minimumPressDuration = 0.3
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        // 和行上的点按并存；但不和滚动、左滑同时进行
        func gestureRecognizer(_ recognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            !(other is UIPanGestureRecognizer)
        }
    }

    func handleUIGestureRecognizerAction(_ recognizer: UILongPressGestureRecognizer, context: Context) {
        let point = context.converter.localLocation
        switch recognizer.state {
        case .began: onBegan(point)
        case .changed: onChanged(point)
        case .ended, .cancelled, .failed: onEnded()
        default: break
        }
    }
}

/// 只在横向滑动时才开始的拖动手势（UIKit 的 pan），竖着滑仍然交给列表滚动
struct HorizontalPan: UIGestureRecognizerRepresentable {
    let onChange: (CGFloat) -> Void
    let onEnd: (CGFloat) -> Void

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let recognizer = UIPanGestureRecognizer()
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let x = recognizer.translation(in: recognizer.view).x
        switch recognizer.state {
        case .changed: onChange(x)
        case .ended, .cancelled, .failed: onEnd(x)
        default: break
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
            let velocity = pan.velocity(in: pan.view)
            return abs(velocity.x) > abs(velocity.y) * 1.2
        }
    }
}
#endif
