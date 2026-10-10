import AVFoundation
import PhotosUI
import SwiftUI
import Translation
import Vision

/// 扫描翻译的方向。自动时每段按自己的语言判断
enum LiveDirection: String, CaseIterable {
    case auto, enToZh, zhToEn

    var label: String {
        switch self {
        case .auto: "自动"
        case .enToZh: "英→中"
        case .zhToEn: "中→英"
        }
    }
}

/// 扫描翻译拍下的一张照片：识别文字、翻译，译文按原位盖在原文上（底色取原文周围的颜色）
@MainActor
final class ScanTranslateModel: ObservableObject {
    @Published var direction: LiveDirection = .auto
    /// 定格的照片（已经摆正）；nil 表示在取景
    @Published private(set) var photo: UIImage?
    @Published private(set) var blocks: [ImageBlock] = []
    @Published private(set) var working: String?
    @Published private(set) var message: String?
    @Published private(set) var aiDone = false
    @Published var showTranslation = true
    /// 照片转过几次（每次顺时针 90°），换了就让缩放从头开始
    @Published private(set) var rotation = 0

    /// 两个方向各用一个本机翻译通道
    let englishToChinese: LiveTranslator
    let chineseToEnglish: LiveTranslator
    private var job: Task<Void, Never>?

    init() {
        englishToChinese = LiveTranslator { text, chinese in try? await OnlineTranslator.translate(text, fromChinese: chinese) }
        chineseToEnglish = LiveTranslator { text, chinese in try? await OnlineTranslator.translate(text, fromChinese: chinese) }
    }

    func prepare() {
        Task { await englishToChinese.prepare(fromChinese: false) }
    }

    /// 照片顺时针转 90°，译文跟着一起转，不用重新识别
    func rotate() {
        guard let photo, working == nil else { return }
        let size = CGSize(width: photo.size.height, height: photo.size.width)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        self.photo = UIGraphicsImageRenderer(size: size, format: format).image { context in
            let cg = context.cgContext
            cg.translateBy(x: size.width / 2, y: size.height / 2)
            cg.rotate(by: .pi / 2)
            photo.draw(in: CGRect(x: -photo.size.width / 2, y: -photo.size.height / 2, width: photo.size.width, height: photo.size.height))
        }
        blocks = blocks.map { $0.rotatedClockwise() }
        rotation += 1
    }

    /// 回到取景
    func retake() {
        job?.cancel()
        job = nil
        photo = nil
        blocks = []
        working = nil
        message = nil
        aiDone = false
        showTranslation = true
        rotation = 0
    }

    /// 这一段从哪种语言翻：自动时看它自己；固定方向时，已经是目标语言的段落不翻（nil）
    private func sourceIsChinese(_ text: String) -> Bool? {
        switch direction {
        case .auto: text.isMostlyChinese
        case .enToZh: text.isMostlyChinese ? nil : false
        case .zhToEn: text.isMostlyChinese ? true : nil
        }
    }

    /// 拍下一张：摆正、识别、翻译
    func process(_ image: UIImage) {
        let image = Self.normalized(image)
        photo = image
        blocks = []
        aiDone = false
        message = nil
        working = "正在识别文字…"
        job?.cancel()
        job = Task {
            guard let cgImage = image.cgImage else { return }
            let (found, clockwise) = await Task.detached(priority: .userInitiated) {
                ImageText.recognizeBlocksDetectingOrientation(cgImage)
            }.value
            guard !Task.isCancelled else { return }
            guard !found.isEmpty else {
                working = nil
                message = "没有识别到文字，靠近一点、拿稳再拍"
                return
            }
            // 识别时字是摆正的（照片顺时针转了 clockwise 度），转回照片本身的方向，译文跟着转
            let turns = ((360 - clockwise) / 90) % 4
            var items = found.map { block in
                var item = ImageBlock(text: block.text, x: block.rect.minX, y: block.rect.minY,
                                      width: block.rect.width, height: block.rect.height, lines: block.lines)
                for _ in 0..<turns { item = item.rotatedClockwise() }
                return item
            }
            let colors = ImageText.backgroundColors(image, rects: items.map { CGRect(x: $0.x, y: $0.y, width: $0.width, height: $0.height) })
            for i in items.indices { items[i].background = colors[i] }
            blocks = items
            working = "正在翻译…"
            for item in items {
                guard !Task.isCancelled else { return }
                guard let chinese = sourceIsChinese(item.text) else { continue }
                let channel = chinese ? chineseToEnglish : englishToChinese
                if let result = await channel.translate(item.text, fromChinese: chinese), let i = blocks.firstIndex(where: { $0.id == item.id }) {
                    blocks[i].translation = result
                }
            }
            working = nil
            if blocks.allSatisfy({ $0.translation.isEmpty }) { message = "翻译失败，请检查网络后重试" }
        }
    }

    /// 用 AI 把整页重新翻译一遍（前后文一起，比逐段机器翻译好）
    func translateWithAI() {
        guard AISettings.shared.isConfigured else {
            NotificationCenter.default.post(name: .needAI, object: nil)
            return
        }
        guard working == nil, !blocks.isEmpty else { return }
        working = "AI 翻译中…"
        message = nil
        let config = AIClient.currentConfig
        job = Task {
            // 中文的段落译成英文，其他的译成中文，分两批
            for toChinese in [true, false] {
                let indices = blocks.indices.filter { sourceIsChinese(blocks[$0].text).map { $0 != toChinese } ?? false }
                guard !indices.isEmpty else { continue }
                do {
                    let result = try await AITasks.translateImageBlocks(indices.map { blocks[$0].text }, instruction: nil,
                                                                         toChinese: toChinese, config: config)
                    guard !Task.isCancelled else { return }
                    for (index, text) in zip(indices, result.texts) where !text.trimmed.isEmpty { blocks[index].translation = text }
                } catch {
                    message = error.localizedDescription
                }
            }
            working = nil
            if message == nil { aiDone = true }
        }
    }

    /// 照片摆正成 .up、长边不超过 2400：识别和取色都按屏幕上看到的方向
    private static func normalized(_ image: UIImage) -> UIImage {
        let scale = min(1, 2400 / max(image.size.width, image.size.height))
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}

/// 扫描翻译，像微信的扫一扫翻译：对准文字、拿稳一会儿就自动拍下来翻译，也可以自己按快门。
/// 译文按原位盖在照片上；可以切换原图、用 AI 重新翻译、看列表、发到会话里保存
struct LiveCameraView: View {
    /// 发到会话（还没翻译完）：照片交给原来的图片翻译
    let onCapture: (UIImage) -> Void
    /// 发到会话（已经翻译好）：原图和译文直接存进会话，不用再翻译一遍
    let onSaveTranslated: (UIImage, [ImageBlock], Bool) -> Void
    /// 从相册选的图片
    let onPick: ([UIImage]) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @StateObject private var camera = CameraSession()
    @StateObject private var model = ScanTranslateModel()
    /// 自动扫描：拿稳了就自动拍
    @AppStorage("camera.liveTranslate") private var autoScan = true
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var listMode = false
    @State private var capturing = false
    @State private var flash = false
    @State private var baseZoom: CGFloat = 1

    /// 模拟器里没有摄像头，用一张示例图代替，方便检查界面
    static var isAvailable: Bool {
        #if targetEnvironment(simulator)
        true
        #else
        CameraSession.hasCamera
        #endif
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            viewfinder
            if flash { Color.white.ignoresSafeArea().transition(.opacity) }
            VStack(spacing: 0) {
                topBar
                Spacer()
                status
                if listMode, model.photo != nil { listPanel }
                if model.photo == nil { liveBar } else { resultBar }
            }
        }
        .preferredColorScheme(.dark)
        .modifier(ScanTranslationHost(translator: model.englishToChinese))
        .modifier(ScanTranslationHost(translator: model.chineseToEnglish))
        .statusBarHidden()
        .onAppear {
            Analytics.track(.cameraLiveOpen)
            model.prepare()
            camera.onAutoCapture = { shoot(auto: true) }
            camera.setRecognition(autoScan)
            if CameraSession.hasCamera { camera.start() }
        }
        .onDisappear { camera.stop() }
        .onChange(of: autoScan) {
            camera.setRecognition(autoScan)
            Analytics.track(.cameraLiveTranslateSwitch, ["state": autoScan ? "on" : "off"])
        }
        .onChange(of: photoItems) {
            let items = photoItems
            guard !items.isEmpty else { return }
            photoItems = []
            Task {
                var images: [UIImage] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        images.append(image)
                    }
                }
                if !images.isEmpty {
                    onPick(images)
                    dismiss()
                }
            }
        }
    }

    // MARK: 取景和照片

    /// 取景区和照片都是 3:4，整个画面都在屏幕上
    private var viewfinder: some View {
        ZStack {
            preview
                .opacity(camera.previewReady || !CameraSession.hasCamera ? 1 : 0)
                .animation(.easeIn(duration: 0.2), value: camera.previewReady)
            if let photo = model.photo {
                Color.black
                // 双指缩放、双击放大看细节；转了方向就从头看
                ZoomableView {
                    TranslatedImageView(image: photo, blocks: model.blocks, showTranslation: model.showTranslation)
                        .animation(.easeOut(duration: 0.2), value: model.blocks)
                }
                .id(model.rotation)
            } else if autoScan, camera.textInView {
                scanFrame
            }
        }
        .aspectRatio(3.0 / 4.0, contentMode: .fit)
        .clipped()
        .gesture(
            MagnifyGesture()
                .onChanged { if model.photo == nil { camera.zoom(to: baseZoom * $0.magnification) } }
                .onEnded { _ in baseZoom = camera.currentZoom }
        )
    }

    @ViewBuilder private var preview: some View {
        #if targetEnvironment(simulator)
        SimulatedScene(armed: model.photo == nil && autoScan) { shoot(auto: true) }
        #else
        CameraPreview(camera: camera)
        #endif
    }

    /// 看到字、正在等拿稳：框线越稳越亮
    private var scanFrame: some View {
        RoundedRectangle(cornerRadius: 18)
            .strokeBorder(Color.white.opacity(0.3 + camera.steadyProgress * 0.7), lineWidth: 3)
            .padding(28)
            .animation(.easeOut(duration: 0.2), value: camera.steadyProgress)
            .allowsHitTesting(false)
    }

    // MARK: 状态

    @ViewBuilder private var status: some View {
        if camera.access == .denied {
            deniedNotice
        } else if let working = model.working {
            chip(working, systemImage: nil, progress: true)
        } else if let message = model.message {
            chip(message, systemImage: "exclamationmark.circle", progress: false)
        } else if model.photo == nil {
            if !autoScan {
                chip("对准文字，按快门拍下来翻译", systemImage: nil, progress: false)
            } else if camera.textInView {
                chip("拿稳一下，马上翻译…", systemImage: "hand.raised", progress: false)
            } else {
                chip("对准文字，拿稳后自动翻译", systemImage: "text.viewfinder", progress: false)
            }
        }
    }

    private func chip(_ text: String, systemImage: String?, progress: Bool) -> some View {
        HStack(spacing: 8) {
            if progress { ProgressView().controlSize(.small) }
            if let systemImage { Image(systemName: systemImage) }
            Text(text)
        }
        .font(.subheadline.weight(.medium))
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .glassEffect(.regular, in: .capsule)
        .padding(.bottom, 12)
    }

    private var deniedNotice: some View {
        VStack(spacing: 10) {
            Text("没有相机权限")
                .font(.headline)
            Text("到系统设置里允许快译使用相机，才能扫描翻译。")
                .font(.subheadline)
                .multilineTextAlignment(.center)
            Button("打开设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            .buttonStyle(.glassProminent)
        }
        .padding(20)
        .glassEffect(.regular, in: .rect(cornerRadius: 20))
        .padding(.horizontal, 24)
        .padding(.bottom, 20)
    }

    /// 列表：原文和译文一条一条排下来，字多的时候看得更清楚
    private var listPanel: some View {
        let items = model.blocks.sorted { ($0.y, $0.x) < ($1.y, $1.x) }
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(items) { block in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(block.text)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text(block.translation.isEmpty ? "…" : block.translation)
                            .font(.body.weight(.medium))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                }
            }
            .padding(16)
        }
        .frame(maxHeight: 300)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
    }

    // MARK: 按钮

    private var topBar: some View {
        HStack(spacing: 8) {
            circleButton("xmark", label: "关闭") { dismiss() }
            Spacer()
            directionMenu
            Spacer()
            if model.photo == nil {
                autoScanSwitch
                if camera.hasTorch, camera.position == .back {
                    circleButton(camera.torchOn ? "bolt.fill" : "bolt.slash", label: camera.torchOn ? "关闭手电筒" : "打开手电筒") {
                        camera.toggleTorch()
                    }
                }
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
    }

    private var directionMenu: some View {
        Menu {
            ForEach(LiveDirection.allCases, id: \.self) { item in
                Button {
                    model.direction = item
                    Analytics.track(.cameraLiveDirection, ["direction": item.rawValue])
                    // 定格时换了方向：按新方向重新翻译
                    if let photo = model.photo { model.process(photo) }
                } label: {
                    if model.direction == item { Label(item.label, systemImage: "checkmark") } else { Text(item.label) }
                }
            }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: "arrow.left.arrow.right")
                Text(model.direction == .auto ? "自动识别" : model.direction.label)
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .frame(height: 44)
            .glassEffect(.regular.interactive(), in: .capsule)
        }
        .accessibilityLabel("翻译方向")
    }

    /// 自动拍照的开关：打开时拿稳了就自动拍；关掉后要自己按快门
    private var autoScanSwitch: some View {
        Button { autoScan.toggle() } label: {
            HStack(spacing: 5) {
                Image(systemName: autoScan ? "checkmark.circle.fill" : "circle")
                Text("自动拍")
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(autoScan ? Color.white : Color.white.opacity(0.7))
            .padding(.horizontal, 12)
            .frame(height: 44)
            .glassEffect(autoScan ? .regular.tint(Color.lxAccent).interactive() : .regular.interactive(), in: .capsule)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("自动拍照")
        .accessibilityValue(autoScan ? "已打开" : "已关闭")
    }

    /// 取景时：相册、快门、前后摄像头
    private var liveBar: some View {
        HStack {
            PhotosPicker(selection: $photoItems, maxSelectionCount: 10, matching: .images) {
                Image(systemName: "photo")
                    .font(.system(size: 19, weight: .medium))
                    .frame(width: 52, height: 52)
                    .glassEffect(.regular.interactive(), in: .circle)
            }
            .accessibilityLabel("从相册选图片")
            Spacer()
            shutter
            Spacer()
            circleButton("arrow.triangle.2.circlepath.camera", label: camera.position == .back ? "切换到前置摄像头" : "切换到后置摄像头", size: 52) {
                baseZoom = 1
                camera.flip()
                Analytics.track(.cameraLiveFlip, ["to": camera.position == .back ? "front" : "back"])
            }
        }
        .padding(.horizontal, 28)
        .padding(.top, 8)
        .padding(.bottom, 14)
    }

    /// 定格时：重拍、原图 / 译文、AI 翻译、列表、发到会话
    private var resultBar: some View {
        HStack(alignment: .top) {
            labeledButton("arrow.counterclockwise", "重拍") {
                listMode = false
                model.retake()
                camera.setPaused(false)
                camera.rearm()
            }
            Spacer(minLength: 0)
            labeledButton(model.showTranslation ? "photo" : "character.bubble", model.showTranslation ? "原图" : "译文") {
                model.showTranslation.toggle()
            }
            .disabled(model.blocks.isEmpty)
            Spacer(minLength: 0)
            labeledButton("rotate.right", "旋转") { model.rotate() }
                .disabled(model.working != nil)
            Spacer(minLength: 0)
            labeledButton("sparkles", model.aiDone ? "AI 已翻" : "AI 翻译", tint: .lxAI) {
                Analytics.track(.cameraLiveAI)
                model.translateWithAI()
            }
            .disabled(model.blocks.isEmpty || model.working != nil)
            Spacer(minLength: 0)
            labeledButton(listMode ? "photo.on.rectangle" : "list.bullet", listMode ? "收起" : "列表") { listMode.toggle() }
                .disabled(model.blocks.isEmpty)
            Spacer(minLength: 0)
            labeledButton("tray.and.arrow.down", "发到会话") {
                guard let photo = model.photo else { return }
                // 已经翻译好了：原图和译文直接存；还在识别或没翻译出来，就交给会话里的图片翻译
                if model.working == nil, model.blocks.contains(where: { !$0.translation.isEmpty }) {
                    onSaveTranslated(photo, model.blocks, model.aiDone)
                } else {
                    onCapture(photo)
                }
                dismiss()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }

    private func labeledButton(_ systemName: String, _ title: String, tint: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: systemName)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(tint)
                    .frame(width: 48, height: 48)
                    .glassEffect(.regular.interactive(), in: .circle)
                Text(title).font(.caption2).foregroundStyle(.white).lineLimit(1)
            }
            .frame(minWidth: 52)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private var shutter: some View {
        Button { shoot(auto: false) } label: {
            ZStack {
                Circle().stroke(.white, lineWidth: 4).frame(width: 76, height: 76)
                Circle().fill(.white).frame(width: 62, height: 62)
            }
            .opacity(capturing ? 0.5 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("拍照翻译")
    }

    /// 拍一张，定格在这张照片上翻译
    private func shoot(auto: Bool) {
        guard !capturing, model.photo == nil else { return }
        capturing = true
        Analytics.track(.cameraLiveShutter, ["auto": auto ? "yes" : "no", "direction": model.direction.rawValue,
                                             "camera": camera.position == .back ? "back" : "front"])
        withAnimation(.easeOut(duration: 0.08)) { flash = true }
        let finish: (UIImage?) -> Void = { image in
            withAnimation(.easeOut(duration: 0.2)) { flash = false }
            capturing = false
            guard let image else {
                camera.rearm()
                return
            }
            camera.setPaused(true)
            model.process(image)
        }
        #if targetEnvironment(simulator)
        finish(SimulatedScene.current)
        #else
        camera.capture(finish)
        #endif
    }

    private func circleButton(_ systemName: String, label: String, size: CGFloat = 44, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size > 44 ? 19 : 17, weight: .medium))
                .frame(width: size, height: size)
                .glassEffect(.regular.interactive(), in: .circle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct ScanTranslationHost: ViewModifier {
    @ObservedObject var translator: LiveTranslator

    func body(content: Content) -> some View {
        content.translationTask(translator.configuration) { session in await translator.run(session) }
    }
}

#if targetEnvironment(simulator)
/// 模拟器用的示例画面：显示 2 秒后当作“拿稳了”自动拍。
/// 启动参数 -simRotation 90 让画面里的字横过来（模拟手机横着拍），-simScene book 换成字很多的书页
private struct SimulatedScene: View {
    let armed: Bool
    let onSteady: () -> Void

    static let current = makeScene(book: UserDefaults.standard.string(forKey: "simScene") == "book",
                                   rotation: UserDefaults.standard.integer(forKey: "simRotation"))

    var body: some View {
        Color.black
            .overlay { Image(uiImage: Self.current).resizable().scaledToFill() }
            .clipped()
            .task(id: armed) {
                guard armed else { return }
                try? await Task.sleep(for: .seconds(2))
                if !Task.isCancelled { onSteady() }
            }
    }

    static func makeScene(book: Bool, rotation: Int) -> UIImage {
        let size = CGSize(width: 1200, height: 1600)
        let sideways = rotation % 180 != 0
        // 内容先按摆正的方向排版，再转进竖着的画布里
        let canvas = sideways ? CGSize(width: size.height, height: size.width) : size
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(red: 0.93, green: 0.9, blue: 0.84, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            let cg = context.cgContext
            cg.translateBy(x: size.width / 2, y: size.height / 2)
            cg.rotate(by: CGFloat(rotation) * .pi / 180)
            cg.translateBy(x: -canvas.width / 2, y: -canvas.height / 2)
            var y: CGFloat = book ? 120 : 300
            func draw(_ text: String, size fontSize: CGFloat, weight: UIFont.Weight = .regular, gap: CGFloat) {
                let style = NSMutableParagraphStyle()
                style.lineBreakMode = .byWordWrapping
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: fontSize, weight: weight),
                    .foregroundColor: UIColor(white: 0.12, alpha: 1), .paragraphStyle: style,
                ]
                let rect = CGRect(x: 110, y: y, width: canvas.width - 220, height: 2000)
                let used = (text as NSString).boundingRect(with: rect.size, options: .usesLineFragmentOrigin, attributes: attributes, context: nil)
                (text as NSString).draw(in: rect, withAttributes: attributes)
                y += used.height + gap
            }
            if book {
                draw("Chapter 3: Sampling Distributions", size: 44, weight: .bold, gap: 30)
                draw("A sampling distribution describes how a statistic, such as the sample mean, varies from one random sample to another. It is the foundation of statistical inference.", size: 30, gap: 18)
                draw("When the sample size is large, the central limit theorem tells us that the distribution of the sample mean is approximately normal, even if the population itself is not.", size: 30, gap: 18)
                draw("The standard error measures how much the sample mean is expected to vary. It decreases as the sample size grows, which is why larger samples give more precise estimates.", size: 30, gap: 18)
                draw("In practice, we rarely know the population standard deviation, so we estimate it from the sample and use the t distribution instead of the normal distribution.", size: 30, gap: 18)
            } else {
                draw("Sunrise Cafe", size: 96, weight: .bold, gap: 60)
                draw("Open daily from 8 AM to 6 PM", size: 60, gap: 140)
                draw("Please wait to be seated. Today's special is fresh pasta with tomato and basil.", size: 52, gap: 120)
                draw("Free Wi-Fi for all customers", size: 56, weight: .medium, gap: 40)
            }
        }
    }
}
#endif
