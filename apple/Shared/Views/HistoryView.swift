import SwiftUI

/// 历史和生词本。窄屏时作为底部弹出的面板，宽屏时是左侧栏。
struct HistoryView: View {
    @ObservedObject var model: TranslatorModel
    let asSheet: Bool

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var history = HistoryStore.shared
    @State private var starredOnly = false
    @State private var pendingDelete: PendingDelete?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if asSheet {
                HStack {
                    Text("记录").font(.title2.weight(.bold))
                    Spacer()
                    GlassIconButton(systemName: "xmark", label: "关闭") { dismiss() }
                }
            }

            Picker("显示", selection: $starredOnly) {
                Text("历史").tag(false)
                Text("生词本").tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            let items = starredOnly ? history.starred : history.items
            if items.isEmpty {
                Text(starredOnly ? "点词条右上角的星标，把它加入生词本。" : "查过的词和句子会出现在这里。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .padding(.top, 24)
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(items) { item in
                            HistoryRow(item: item, selected: item.text == model.input.trimmed) {
                                model.lookup(item.text)
                                if asSheet { dismiss() }
                            }
                            .contextMenu {
                                Button(item.starred ? "从生词本移除" : "加入生词本", systemImage: item.starred ? "star.slash" : "star") {
                                    if item.starred {
                                        pendingDelete = PendingDelete(title: "把“\(item.text)”从生词本移除？") { history.toggleStar(item.text) }
                                    } else {
                                        history.toggleStar(item.text)
                                    }
                                }
                                Button("删除", systemImage: "trash", role: .destructive) {
                                    pendingDelete = PendingDelete(title: "删除记录“\(item.text)”？") { history.remove(item) }
                                }
                            }
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
        }
        .confirmDelete($pendingDelete)
        .padding(asSheet ? 20 : 14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(asSheet ? Color.lxBackground : Color.clear)
        #if os(macOS)
        .frame(minWidth: asSheet ? 380 : nil, minHeight: asSheet ? 480 : nil)
        #endif
        .tint(.lxAccent)
    }
}

struct HistoryRow: View {
    let item: HistoryItem
    var selected = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.text).font(.callout.weight(.semibold)).lineLimit(1)
                    Text(item.summary).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                if item.starred {
                    Image(systemName: "star.fill").font(.caption).foregroundStyle(.orange)
                }
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .background(selected ? Color.lxAccentSoft : Color.clear, in: .rect(cornerRadius: 14))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
