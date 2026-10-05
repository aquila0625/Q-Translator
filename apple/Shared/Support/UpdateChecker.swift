import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
#endif

/// 服务器返回的最新版本信息
struct UpdateRelease: Decodable, Equatable {
    let version: String
    let versionCode: Int
    let notes: String?
    let url: String
    let mandatory: Bool?

    var isMandatory: Bool { mandatory ?? false }
}

/// 应用内检查更新。启动后在后台检查（12 小时内不重复），设置里可以手动检查。
/// 网络失败时自动检查静默跳过；手动检查会提示失败。
@MainActor
final class UpdateChecker: ObservableObject {
    static let shared = UpdateChecker()

    enum ManualResult: Equatable {
        case available(UpdateRelease)
        case upToDate
        case failed
    }

    /// 自动检查发现的新版本，根界面弹窗后清空
    @Published var autoRelease: UpdateRelease?
    /// 手动检查的结果，设置页弹窗后清空
    @Published var manualResult: ManualResult?
    @Published private(set) var isChecking = false

    private static let lastCheckKey = "update.lastCheck"
    private static let skippedKey = "update.skippedVersionCode"
    private static let baseURLKey = "update.baseURL"
    private static let defaultBase = "https://translate.yishulabs.com"
    private static let interval: TimeInterval = 12 * 3600

    /// Mac App Store 版由商店更新，不自检；官网 DMG 和开发构建才检查。iOS 版的更新链接就是 App Store 链接，照常检查
    static var isEnabled: Bool {
        #if os(macOS)
        if let receipt = Bundle.main.appStoreReceiptURL, FileManager.default.fileExists(atPath: receipt.path) {
            return false
        }
        #endif
        return true
    }

    private var platform: String {
        #if os(iOS)
        "ios"
        #else
        "macos"
        #endif
    }

    private var currentCode: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }

    private enum FetchError: Error { case bad }

    /// nil 表示服务器还没有发布版本
    private func fetchLatest() async throws -> UpdateRelease? {
        let base = UserDefaults.standard.string(forKey: Self.baseURLKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var components = URLComponents(string: (base?.isEmpty == false ? base! : Self.defaultBase) + "/api/releases/latest")
        components?.queryItems = [URLQueryItem(name: "platform", value: platform)]
        guard let url = components?.url else { throw FetchError.bad }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw FetchError.bad }
        if http.statusCode == 404 { return nil }
        guard http.statusCode == 200 else { throw FetchError.bad }
        return try JSONDecoder().decode(UpdateRelease.self, from: data)
    }

    /// 启动后调用：距离上次检查不足 12 小时跳过；失败、没有新版本、已跳过的版本都不提示
    func checkInBackground() async {
        guard Self.isEnabled, !isChecking else { return }
        let last = UserDefaults.standard.double(forKey: Self.lastCheckKey)
        if last > 0, Date().timeIntervalSince1970 - last < Self.interval { return }
        isChecking = true
        defer { isChecking = false }
        guard let release = try? await fetchLatest() else { return }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastCheckKey)
        guard release.versionCode > currentCode else { return }
        if !release.isMandatory, UserDefaults.standard.integer(forKey: Self.skippedKey) == release.versionCode { return }
        autoRelease = release
    }

    /// 设置里手动检查：忽略节流和“跳过此版本”
    func checkManually() async {
        guard !isChecking else { return }
        isChecking = true
        defer { isChecking = false }
        do {
            let release = try await fetchLatest()
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastCheckKey)
            if let release, release.versionCode > currentCode {
                manualResult = .available(release)
            } else {
                manualResult = .upToDate
            }
        } catch {
            manualResult = .failed
        }
    }

    // MARK: 弹窗里的动作和统计

    func didShow(_ release: UpdateRelease) {
        Analytics.track(.updatePrompt, ["version": release.version])
    }

    func accept(_ release: UpdateRelease) {
        Analytics.track(.updateAccept, ["version": release.version])
        guard let url = URL(string: release.url) else { return }
        #if os(iOS)
        UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }

    func skip(_ release: UpdateRelease) {
        Analytics.track(.updateSkip, ["version": release.version])
        UserDefaults.standard.set(release.versionCode, forKey: Self.skippedKey)
    }
}

// MARK: - 弹窗

/// 发现新版本的弹窗内容：更新、稍后、跳过此版本；强制更新时只有更新按钮
private struct UpdateAlert: ViewModifier {
    @Binding var release: UpdateRelease?
    @State private var shown: UpdateRelease?
    @State private var showing = false

    private var acceptTitle: String {
        #if os(iOS)
        "前往 App Store"
        #else
        "下载更新"
        #endif
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: release) { _, new in
                guard let new else { return }
                shown = new
                showing = true
                release = nil
                UpdateChecker.shared.didShow(new)
            }
            .alert("发现新版本 \(shown?.version ?? "")", isPresented: $showing, presenting: shown) { r in
                Button(acceptTitle) { UpdateChecker.shared.accept(r) }
                if !r.isMandatory {
                    Button("稍后", role: .cancel) {}
                    Button("跳过此版本") { UpdateChecker.shared.skip(r) }
                }
            } message: { r in
                Text(r.notes ?? "")
            }
    }
}

/// 根界面：启动后自动检查，发现新版本弹窗
private struct UpdatePrompt: ViewModifier {
    @ObservedObject private var checker = UpdateChecker.shared

    func body(content: Content) -> some View {
        content
            .modifier(UpdateAlert(release: $checker.autoRelease))
            .task { await checker.checkInBackground() }
    }
}

/// 设置页：手动检查的结果（新版本、已是最新、失败）
private struct ManualUpdateAlerts: ViewModifier {
    @ObservedObject private var checker = UpdateChecker.shared
    @State private var release: UpdateRelease?
    @State private var upToDate = false
    @State private var failed = false

    func body(content: Content) -> some View {
        content
            .onChange(of: checker.manualResult) { _, result in
                guard let result else { return }
                checker.manualResult = nil
                switch result {
                case .available(let r): release = r
                case .upToDate: upToDate = true
                case .failed: failed = true
                }
            }
            .modifier(UpdateAlert(release: $release))
            .alert("已经是最新版本", isPresented: $upToDate) { Button("好") {} }
            .alert("检查失败，请稍后再试", isPresented: $failed) { Button("好") {} }
    }
}

extension View {
    /// 挂在根界面：启动后检查更新并弹窗
    func updatePrompt() -> some View { modifier(UpdatePrompt()) }
    /// 挂在设置页：手动检查更新的结果弹窗
    func manualUpdateAlerts() -> some View { modifier(ManualUpdateAlerts()) }
}

/// 设置“关于”里的“检查更新”行；Mac App Store 版隐藏
struct CheckUpdateRow: View {
    @ObservedObject private var checker = UpdateChecker.shared

    var body: some View {
        if UpdateChecker.isEnabled {
            Button {
                Task { await checker.checkManually() }
            } label: {
                HStack {
                    Text("检查更新")
                    Spacer()
                    if checker.isChecking { ProgressView().controlSize(.small) }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(checker.isChecking)
        }
    }
}
