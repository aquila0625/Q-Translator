import SwiftUI

/// AI 写回复：针对刚翻译的那段话，按要点写一条回复，或优化用户自己的草稿。
struct ReplyView: View {
    let received: String
    let receivedTranslation: String

    @Environment(\.dismiss) private var dismiss
    @State private var kind: AITasks.ReplyKind = .message
    @State private var mode: AITasks.ReplyMode = .points
    @State private var input = ""
    @State private var reply: AITasks.Reply?
    @State private var working = false
    @State private var error: String?
    @State private var change = ""
    @State private var copied = false
    @State private var showFullReceived = false
    /// 生成这条回复时用的服务商和模型
    @State private var usedModel = ""
    @AppStorage(SettingsKey.showAIUsage) private var showUsage = false
    @FocusState private var inputFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("写回复").font(.title2.weight(.bold))
                    Spacer()
                    #if os(macOS)
                    GlassIconButton(systemName: "xmark", label: "关闭") { dismiss() }
                    #endif
                }

                VStack(alignment: .leading, spacing: 6) {
                    SectionHeader(title: "对方的话")
                    Text(received).font(.callout).lineLimit(showFullReceived ? nil : 4).textSelection(.enabled)
                    Text(receivedTranslation).font(.footnote).foregroundStyle(.secondary)
                        .lineLimit(showFullReceived ? nil : 3).textSelection(.enabled)
                    if received.count > 120 || receivedTranslation.count > 90 {
                        Button {
                            withAnimation(.snappy) { showFullReceived.toggle() }
                        } label: {
                            Label(showFullReceived ? "收起" : "展开全文", systemImage: showFullReceived ? "chevron.up" : "chevron.down")
                                .font(.footnote.weight(.semibold))
                                .frame(minHeight: 36)
                                .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.lxAccent)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.lxSurface, in: .rect(cornerRadius: 18))

                ChoiceBar(title: "回复方式", options: AITasks.ReplyKind.allCases.map { ($0, $0.title, $0 == .message ? "message" : "envelope") },
                          selection: $kind, tint: .lxAccent, soft: .lxAccentSoft)
                ChoiceBar(title: "怎么写", options: AITasks.ReplyMode.allCases.map { ($0, $0.title, $0 == .points ? "list.bullet" : "pencil.line") },
                          selection: $mode, tint: .lxAI, soft: .lxAISoft)

                VStack(alignment: .leading, spacing: 6) {
                    SectionHeader(title: mode == .points ? "想说什么" : "我的草稿")
                    TextField(mode == .points ? "例如：告诉他没问题，下周二下午我都有空" : "把你写好的回复贴在这里",
                              text: $input, axis: .vertical)
                        .textFieldStyle(.plain)
                        .lineLimit(3...8)
                        .focused($inputFocused)
                        .padding(12)
                        .background(Color.lxSurface, in: .rect(cornerRadius: 14))
                    if mode == .points {
                        Text("用中文写就行，回复会用对方的语言。").font(.footnote).foregroundStyle(.secondary)
                    }
                }

                Button {
                    generate(change: nil)
                } label: {
                    HStack(spacing: 8) {
                        if working { ProgressView().controlSize(.small) } else { Image(systemName: "sparkles") }
                        Text(reply == nil ? "生成回复" : "重新生成")
                    }
                    .font(.body.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                }
                .buttonStyle(.glassProminent)
                .disabled(working || input.trimmed.isEmpty)

                if let error {
                    Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(Color.lxAI)
                }

                if let reply { result(reply) }
            }
            .padding(20)
        }
        .background(Color.lxBackground)
        .presentationDragIndicator(.visible)
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 600)
        #endif
        .tint(.lxAccent)
        .onAppear { inputFocused = true }
    }

    @ViewBuilder
    private func result(_ reply: AITasks.Reply) -> some View {
        Text(reply.text)
            .font(.system(size: 18, weight: .medium))
            .lineSpacing(4)
            .textSelection(.enabled)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.lxAISoft, in: .rect(cornerRadius: 18))

        if showUsage, let usage = reply.usage {
            Text(usedModel + " · " + usage.summary)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.lxAI)
        }

        if !reply.chinese.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                SectionHeader(title: "中文对照")
                Text(reply.chinese).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
            }
        }

        HStack(spacing: 8) {
            GlassPillButton(title: copied ? "已复制" : "复制回复", systemName: copied ? "checkmark" : "doc.on.doc",
                            tint: .lxAccent) {
                Clipboard.copy(reply.text)
                copied = true
            }
            SpeakPill(speech: .text(reply.text, isChinese: reply.text.isMostlyChinese))
        }

        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title: "再改一下")
            HStack(spacing: 8) {
                ForEach(["更短", "更正式", "更随意"], id: \.self) { option in
                    GlassPillButton(title: option, systemName: "wand.and.stars") { generate(change: option) }
                }
            }
            HStack(spacing: 4) {
                TextField("或者直接说：再问一下他几点方便", text: $change)
                    .textFieldStyle(.plain)
                    .onSubmit { submitChange() }
                Button(action: submitChange) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.lxOnAccent)
                        .frame(width: 34, height: 34)
                        .background(Color.lxAccent, in: .circle)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .disabled(change.trimmed.isEmpty)
                .accessibilityLabel("按要求重新生成")
            }
            .padding(.leading, 14)
            .background(Color.lxSurface, in: .rect(cornerRadius: 22))
        }
        .disabled(working)
    }

    private func submitChange() {
        let text = change.trimmed
        guard !text.isEmpty else { return }
        change = ""
        generate(change: text)
    }

    /// change 为 nil 时从头生成；否则在上一版回复的基础上修改
    private func generate(change: String?) {
        let config = AIClient.currentConfig
        usedModel = "\(config.provider.title) · \(config.model)"
        let previous = change == nil ? nil : reply?.text
        working = true
        error = nil
        copied = false
        Task {
            defer { working = false }
            do {
                Analytics.track(.replyWrite, ["kind": kind.rawValue, "mode": mode.rawValue])
                reply = try await AITasks.reply(to: received, kind: kind, mode: mode, input: input.trimmed,
                                                previous: previous, change: change, config: config)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// 大一点的分段选择：选中项用颜色高亮。两组用不同颜色，一眼能分开
struct ChoiceBar<Value: Hashable>: View {
    let title: String
    let options: [(Value, String, String)]
    @Binding var selection: Value
    let tint: Color
    let soft: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionHeader(title: title)
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { value, label, icon in
                    let on = value == selection
                    Button {
                        withAnimation(.snappy) { selection = value }
                    } label: {
                        Label(label, systemImage: icon)
                            .font(.callout.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .foregroundStyle(on ? tint : .secondary)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(on ? soft : Color.secondary.opacity(0.08), in: .rect(cornerRadius: 14))
                            .overlay {
                                RoundedRectangle(cornerRadius: 14).stroke(on ? tint : .clear, lineWidth: 1.5)
                            }
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
    }
}
