import Foundation
import Security

/// AI 服务商。Q-Translator 不内置任何 key，用户自己去服务商那里注册，把 key 填进来。
enum AIProvider: String, CaseIterable, Identifiable {
    case claude
    case openai
    case deepseek
    case custom

    var id: String { rawValue }

    var title: String {
        switch self {
        case .claude: "Claude"
        case .openai: "ChatGPT"
        case .deepseek: "DeepSeek"
        case .custom: "自定义"
        }
    }

    /// 去哪里注册并获取 API Key
    var signupURL: URL? {
        switch self {
        case .claude: URL(string: "https://platform.claude.com/")
        case .openai: URL(string: "https://platform.openai.com/api-keys")
        case .deepseek: URL(string: "https://platform.deepseek.com/")
        case .custom: nil
        }
    }

    var defaultModel: String {
        switch self {
        case .claude: "claude-opus-5-5"
        case .openai: "gpt-6-luna"
        case .deepseek: "deepseek-chat"
        case .custom: ""
        }
    }

    /// 下拉里直接可选的模型，按代分组，每组里从便宜到贵；其它模型可以手动填写
    var modelGroups: [(title: String, models: [String])] {
        switch self {
        case .claude:
            [("Claude", ["claude-haiku-4-5", "claude-sonnet-5-5", "claude-opus-5-5"])]
        case .openai:
            [("GPT-6", ["gpt-6-luna", "gpt-6.1-sol", "gpt-6-sol", "gpt-6-astra"]),
             ("GPT-5", ["gpt-5-nano", "gpt-5-mini", "gpt-5", "gpt-5.1", "gpt-5.2", "gpt-5.4-nano", "gpt-5.4-mini",
                        "gpt-5.4", "gpt-5.5", "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol"]),
             ("GPT-4", ["gpt-4.1-nano", "gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini", "gpt-4o"])]
        case .deepseek:
            [("DeepSeek", ["deepseek-chat", "deepseek-reasoner"])]
        case .custom:
            []
        }
    }

    var suggestedModels: [String] { modelGroups.flatMap(\.models) }

    /// OpenAI 兼容接口的地址（Claude 走自己的 Messages API，不用这个）
    var defaultBaseURL: String {
        switch self {
        case .claude: ""
        case .openai: "https://api.openai.com/v1"
        case .deepseek: "https://api.deepseek.com"
        case .custom: ""
        }
    }
}

@MainActor
final class AISettings: ObservableObject {
    static let shared = AISettings()

    @Published var provider: AIProvider { didSet { defaults.set(provider.rawValue, forKey: "ai.provider"); load() } }
    @Published var apiKey = "" { didSet { if apiKey != oldValue { Keychain.set(apiKey, account: provider.rawValue) } } }
    @Published var model = "" { didSet { defaults.set(model, forKey: "ai.model.\(provider.rawValue)") } }
    @Published var baseURL = "" { didSet { defaults.set(baseURL, forKey: "ai.baseURL.\(provider.rawValue)") } }
    /// 每次翻译句子后自动用 AI 校准；关闭时只在点“AI 校准”时运行
    @Published var autoCalibrate: Bool { didSet { defaults.set(autoCalibrate, forKey: "ai.autoCalibrate") } }

    private let defaults = UserDefaults.standard

    private init() {
        provider = AIProvider(rawValue: defaults.string(forKey: "ai.provider") ?? "") ?? .claude
        autoCalibrate = defaults.bool(forKey: "ai.autoCalibrate")
        load()
    }

    var isConfigured: Bool {
        !apiKey.trimmed.isEmpty && !model.trimmed.isEmpty && (provider == .claude || !baseURL.trimmed.isEmpty)
    }

    /// 语音转写（AI 精校同声传译）用 OpenAI 的 Key，和服务商“ChatGPT”用的是同一个
    var openAIKey: String {
        get { provider == .openai ? apiKey : Keychain.get(account: AIProvider.openai.rawValue) ?? "" }
        set {
            if provider == .openai {
                apiKey = newValue
            } else {
                Keychain.set(newValue.trimmed, account: AIProvider.openai.rawValue)
                objectWillChange.send()
            }
        }
    }

    /// 切换服务商后，读出这家服务商各自保存的 key、模型和地址
    private func load() {
        apiKey = Keychain.get(account: provider.rawValue) ?? ""
        model = defaults.string(forKey: "ai.model.\(provider.rawValue)") ?? provider.defaultModel
        baseURL = defaults.string(forKey: "ai.baseURL.\(provider.rawValue)") ?? provider.defaultBaseURL
    }
}

/// API Key 只存在本机钥匙串里
enum Keychain {
    private static let service = "com.yishulabs.qtranslator.ai"

    static func get(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ value: String, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        guard !value.isEmpty else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(item as CFDictionary, nil)
    }
}
