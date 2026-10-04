import Foundation

struct HistoryItem: Codable, Identifiable, Equatable {
    var id: String { text }
    let text: String
    var summary: String
    var date: Date
    var starred = false
}

/// 查询历史和生词本（星标），保存在本机。
@MainActor
final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()

    @Published private(set) var items: [HistoryItem] = []

    private let key = "history.items"
    private let limit = 200

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([HistoryItem].self, from: data) {
            items = saved
        }
    }

    var starred: [HistoryItem] { items.filter(\.starred) }

    func add(_ text: String, summary: String) {
        let starred = items.first { $0.text == text }?.starred ?? false
        items.removeAll { $0.text == text }
        items.insert(HistoryItem(text: text, summary: summary, date: Date(), starred: starred), at: 0)
        // 超出上限时丢掉最旧的记录，生词本里的保留
        while items.count > limit, let index = items.lastIndex(where: { !$0.starred }) {
            items.remove(at: index)
        }
        save()
    }

    func isStarred(_ text: String) -> Bool {
        items.first { $0.text == text }?.starred ?? false
    }

    func toggleStar(_ text: String) {
        guard let index = items.firstIndex(where: { $0.text == text }) else { return }
        items[index].starred.toggle()
        if items[index].starred { Analytics.track(.wordStar) }
        save()
    }

    func remove(_ item: HistoryItem) {
        items.removeAll { $0.text == item.text }
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}
