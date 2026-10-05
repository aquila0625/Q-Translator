import SwiftUI

enum SettingsRoute: Hashable { case ai }

struct SettingsView: View {
    /// startAtAI：直接打开 AI 增强页（别处提示“需要配置 AI”时用）
    init(startAtAI: Bool = false) {
        _path = State(initialValue: startAtAI ? [.ai] : [])
    }

    @State private var path: [SettingsRoute] = []
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var ai = AISettings.shared
    @AppStorage(SettingsKey.accent) private var accent = 2
    @AppStorage(SettingsKey.autoSpeak) private var autoSpeak = false
    @AppStorage(SettingsKey.voiceAutoSend) private var voiceAutoSend = false
    @AppStorage(SettingsKey.appearance) private var appearance = 0
    @AppStorage(SettingsKey.interpreterSpeak) private var interpreterSpeak = false
    @AppStorage(SettingsKey.dialogSpeak) private var dialogSpeak = true
    @AppStorage("interpreter.sourceIsChinese") private var interpreterFromChinese = false
    @AppStorage("voice.language") private var voiceLanguage = "en-US"


    var body: some View {
        NavigationStack(path: $path) {
            Form {
                Section("外观") {
                    Picker("主题", selection: $appearance) {
                        Text("跟随系统").tag(0)
                        Text("浅色").tag(1)
                        Text("深色").tag(2)
                    }
                }

                Section {
                    NavigationLink {
                        OfflineModelsView()
                    } label: {
                        Label("离线翻译和语音识别模型", systemImage: "arrow.down.circle")
                    }
                } footer: {
                    Text("下载后不用联网、不限量，翻译和同声传译都会快很多。")
                }

                Section {
                    NavigationLink(value: SettingsRoute.ai) {
                        HStack {
                            Label("AI 增强", systemImage: "sparkles")
                            Spacer()
                            Text(ai.isConfigured ? ai.provider.title : "未配置")
                                .foregroundStyle(ai.isConfigured ? Color.secondary : Color.lxAI)
                        }
                    }
                } footer: {
                    Text("优化译文、写回复、场景练习、AI 音色要用到。可选，不填也能用词典、翻译、朗读和图片翻译。")
                }

                Section {
                    Toggle("说完自动翻译", isOn: $voiceAutoSend)
                    Picker("“自动”方向时先听", selection: $voiceLanguage) {
                        Text("英语").tag("en-US")
                        Text("中文").tag("zh-CN")
                    }
                } header: {
                    Text("语音输入")
                } footer: {
                    Text("关闭“说完自动翻译”时，说的话先放进输入框，可以改完再翻译。翻译方向选了中→英或英→中时，按方向识别；正在听的时候也可以点一下切换。录音只保存在本机，可以在会话里回放。")
                }

                Section("同声传译") {
                    Picker("默认方向", selection: $interpreterFromChinese) {
                        Text("英 → 中").tag(false)
                        Text("中 → 英").tag(true)
                    }
                    Toggle("默认朗读译文（建议戴耳机）", isOn: $interpreterSpeak)
                }

                Section("面对面对话") {
                    Toggle("翻译后朗读出来", isOn: $dialogSpeak)
                }

                Section("朗读") {
                    SpeechSpeedPicker()
                    Picker("默认英文口音", selection: $accent) {
                        Text("英式").tag(1)
                        Text("美式").tag(2)
                    }
                    Toggle("查词后自动朗读", isOn: $autoSpeak)
                    NavigationLink("音色") { SpeechVoicesView() }
                }

                Section {
                    LabeledContent("单词", value: "有道词典（在线）")
                    LabeledContent("句子和段落", value: "系统离线翻译，其次 MyMemory")
                    LabeledContent("图片文字", value: "本机识别，不上传")
                    LabeledContent("语音", value: "本机识别，不上传")
                } header: {
                    Text("翻译来源")
                } footer: {
                    Text("先用离线和免费的来源，AI 只在你填了 Key 之后作为补充。")
                }

                Section("关于") {
                    NavigationLink("隐私协议") { PrivacyPolicyView() }
                    LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    Link("源代码（MIT 许可）", destination: URL(string: "https://github.com/aquila0625/Q-Translator")!)
                }
            }
            .formStyle(.grouped)
            .navigationDestination(for: SettingsRoute.self) { _ in AISettingsView() }
            .navigationTitle("设置")
            .analyticsPage("设置")
            .inlineNavigationTitle()
            #if os(macOS)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 600)
        #endif
        .presentationDragIndicator(.visible)
        .tint(.lxAccent)
        .appAppearance()
    }
}

/// AI 增强：服务商、API Key、模型、测试连接和用量。从设置进入，也可以在别处提示“需要配置 AI”时直接打开
struct AISettingsView: View {
    @ObservedObject private var ai = AISettings.shared
    @ObservedObject private var usage = UsageStore.shared
    @AppStorage(SettingsKey.showAIUsage) private var showUsage = false
    @State private var testing = false
    @State private var testResult: String?

    var body: some View {
        Form {
            Section {
                Picker("服务商", selection: $ai.provider) {
                    ForEach(AIProvider.allCases) { Text($0.title).tag($0) }
                }
                .onChange(of: ai.provider) { _, provider in Analytics.track(.settingsAIProvider, ["provider": provider.rawValue]) }
                SecureField("API Key", text: $ai.apiKey)
                    .textContentType(.password)
                    .autocorrectionDisabled()
                if let url = ai.provider.signupURL {
                    Link("去 \(ai.provider.title) 注册并获取 API Key", destination: url)
                }
                if ai.provider == .custom {
                    TextField("接口地址，例如 https://api.openai.com/v1", text: $ai.baseURL)
                        .autocorrectionDisabled()
                }
                if !ai.provider.suggestedModels.isEmpty {
                    Picker("模型", selection: $ai.model) {
                        ForEach(ai.provider.modelGroups, id: \.title) { group in
                            Section(group.title) {
                                ForEach(group.models, id: \.self) { Text($0).tag($0) }
                            }
                        }
                        if !ai.provider.suggestedModels.contains(ai.model) {
                            Text(ai.model.isEmpty ? "未选择" : ai.model).tag(ai.model)
                        }
                    }
                }
                TextField("或手动填写模型名称", text: $ai.model)
                    .autocorrectionDisabled()
                Toggle("AI 优化：翻译句子后自动优化译文", isOn: $ai.autoCalibrate)
                Toggle("在译文下显示每次消耗的 token", isOn: $showUsage)
                Button {
                    test()
                } label: {
                    HStack {
                        Text("测试连接")
                        if testing { ProgressView().controlSize(.small) }
                        Spacer()
                        if let testResult { Text(testResult).foregroundStyle(.secondary) }
                    }
                }
                .disabled(testing || !ai.isConfigured)
                let summary = UsageStore.summarize(usage.records(for: ai.provider))
                LabeledContent("\(ai.provider.title) 累计用量") {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("\(summary.total.formatted()) tokens")
                        Text(summary.total == 0 ? "还没有用过" :
                             (summary.cost > 0 ? "约 " + Pricing.format(summary.cost) + "（按标价估算）" : "无价格数据"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                NavigationLink("用量报表") { UsageReportView() }
            } header: {
                Text("AI 增强（可选）")
            } footer: {
                Text("Q-Translator 不提供 AI 额度，也不经过任何中间服务器：你自己在服务商那里注册，把 API Key 填在这里，费用由服务商向你收取。Key 只保存在本机钥匙串。注意 ChatGPT 的会员订阅不包含 API 额度，API Key 要在 OpenAI 开发者平台单独申请。不填也能使用词典、翻译、朗读和图片翻译。AI 用于优化句子翻译和帮你写回复；上面的开关关闭时不会自动优化，只有你点“AI 优化”才会运行。单词和短语只查词典，不用 AI。token 用量默认不显示在译文下面，可以在用量报表里查看。")
            }

        }
        .formStyle(.grouped)
        .navigationTitle("AI 增强")
        .inlineNavigationTitle()
        .analyticsPage("AI 增强")
    }

    private func test() {
        let config = AIClient.currentConfig
        testing = true
        testResult = nil
        Task {
            defer { testing = false }
            do {
                _ = try await AIClient.complete(system: "This is a connectivity check from an app's settings screen. Reply with the single word OK.",
                                                user: "ping", config: config)
                testResult = "连接正常"
            } catch {
                testResult = error.localizedDescription
            }
        }
    }
}
