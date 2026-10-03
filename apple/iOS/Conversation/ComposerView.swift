import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 底部输入栏：加号（拍照、相册多选、文件、粘贴）、方向、AI 优化开关、发送。
/// 平时最多占屏幕 30%，粘贴长文时放宽到约一半，还可以全屏编辑。
struct ComposerView: View {
    @ObservedObject var controller: ConversationController
    let session: ChatSession
    var focused: FocusState<Bool>.Binding
    let screenHeight: CGFloat
    let onNeedAI: () -> Void

    @State private var showCamera = false
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showFullEditor = false
    @State private var photoItems: [PhotosPickerItem] = []
    @ObservedObject private var voice = VoiceInput.shared

    private var isLong: Bool { controller.draft.count > 200 }
    private var hasImages: Bool { !controller.pendingImages.isEmpty }
    /// 带着图片时，输入框里写的是给 AI 的要求；关着 AI 时不能输入
    private var textLocked: Bool { hasImages && !session.aiEnabled }
    private var canSend: Bool { hasImages || !controller.draft.trimmed.isEmpty }

    private var placeholder: String {
        guard hasImages else { return "输入单词、句子或一段话" }
        return session.aiEnabled ? "告诉 AI 怎么翻译（可不填），例如：只翻译菜名" : "打开“AI 优化”后可以写要求"
    }

    private var maxLines: Int {
        let ratio: CGFloat = isLong ? 0.5 : 0.3
        return min(24, max(3, Int((screenHeight * ratio - 70) / 23)))
    }

    var body: some View {
        VStack(spacing: 0) {
            VoiceErrorBanner()
            if voice.isListening {
                VoiceListeningPanel(onFinish: { controller.finishVoice() }, onCancel: { controller.cancelVoice() })
            } else {
                editor
            }
        }
        .glassEffect(.regular, in: .rect(cornerRadius: 26))
        .padding(.horizontal, 10)
        .padding(.bottom, 4)
        .modifier(Pickers(controller: controller, focused: focused, showCamera: $showCamera, showPhotos: $showPhotos,
                          showFiles: $showFiles, showFullEditor: $showFullEditor, photoItems: $photoItems))
    }

    private var editor: some View {
        VStack(spacing: 0) {
            if hasImages {
                AttachmentTray(controller: controller)
            }
            if isLong {
                HStack {
                    Text("已输入 \(controller.draft.count) 个字符").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Spacer()
                    Button { showFullEditor = true } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 44, height: 36)
                    }
                    .accessibilityLabel("全屏编辑")
                }
                .padding(.leading, 12)
            }
            TextField(placeholder, text: $controller.draft, axis: .vertical)
                .font(.system(size: 17))
                .lineLimit(1...maxLines)
                .focused(focused)
                .disabled(textLocked)
                // 查单词时不要被自动改成首字母大写
                .textInputAutocapitalization(.never)
                .padding(.horizontal, 12)
                .padding(.top, isLong ? 0 : 10)
                .padding(.bottom, 4)

            HStack(spacing: 6) {
                Menu {
                    if CameraPicker.isAvailable {
                        Button("拍照", systemImage: "camera") { showCamera = true }
                    }
                    Button("从相册选图片（可多选）", systemImage: "photo.on.rectangle") { showPhotos = true }
                    Button("从文件选择", systemImage: "folder") { showFiles = true }
                    Button("粘贴剪贴板", systemImage: "doc.on.clipboard") { paste() }
                    Divider()
                    Button("面对面对话", systemImage: "person.2") { controller.showFaceToFace = true }
                    Button("同声传译", systemImage: "captions.bubble") { controller.startInterpretation() }
                    Button("英语场景练习", systemImage: "theatermasks") {
                        if AISettings.shared.isConfigured { controller.startPractice() } else { onNeedAI() }
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .medium))
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .accessibilityLabel("添加图片或粘贴")

                Button { controller.cycleDirection() } label: {
                    chip(directionLabel, systemName: "arrow.left.arrow.right", on: controller.direction != .auto)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("翻译方向：\(directionLabel)，点按切换")

                Button {
                    if !session.aiEnabled, !AISettings.shared.isConfigured {
                        onNeedAI()
                    }
                    controller.setAI(!session.aiEnabled)
                } label: {
                    chip("AI 优化", systemName: "sparkles", on: session.aiEnabled, ai: true)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("AI 优化")
                .accessibilityValue(session.aiEnabled ? "开" : "关")

                Spacer(minLength: 0)

                if !canSend {
                    // 输入框没有内容时，发送按钮换成麦克风
                    MicButton(size: 38) {
                        focused.wrappedValue = false
                        controller.startVoice()
                    }
                } else {
                Button {
                    controller.send()
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color.lxOnAccent)
                        .frame(width: 38, height: 38)
                        .background(canSend ? Color.lxAccent : Color.secondary.opacity(0.4), in: .circle)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .accessibilityLabel("翻译")
                }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 2)
        }
    }
}

/// 加号菜单弹出的拍照、相册、文件和全屏编辑
private struct Pickers: ViewModifier {
    @ObservedObject var controller: ConversationController
    var focused: FocusState<Bool>.Binding
    @Binding var showCamera: Bool
    @Binding var showPhotos: Bool
    @Binding var showFiles: Bool
    @Binding var showFullEditor: Bool
    @Binding var photoItems: [PhotosPickerItem]

    func body(content: Content) -> some View {
        content
        .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 10, matching: .images)
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
                controller.attachImages(images)
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { controller.attachImages([$0]) }.ignoresSafeArea()
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.image], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            let images = urls.compactMap { url -> UIImage? in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return UIImage(contentsOfFile: url.path)
            }
            controller.attachImages(images)
        }
        .sheet(isPresented: $showFullEditor) {
            NavigationStack {
                TextEditor(text: $controller.draft)
                    .font(.system(size: 17))
                    .padding(.horizontal, 12)
                    .navigationTitle("编辑原文")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("完成") { showFullEditor = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("翻译") {
                                showFullEditor = false
                                controller.send()
                            }
                            .disabled(controller.draft.trimmed.isEmpty)
                        }
                    }
            }
            .tint(.lxAccent)
        }
    }
}

extension ComposerView {
    private var directionLabel: String {
        switch controller.direction {
        case .auto: "自动"
        case .englishToChinese: "英→中"
        case .chineseToEnglish: "中→英"
        }
    }

    private func chip(_ title: String, systemName: String, on: Bool, ai: Bool = false) -> some View {
        Label(title, systemImage: systemName)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(on ? (ai ? Color.lxAI : Color.lxAccent) : Color.secondary)
            .padding(.horizontal, 11)
            .frame(height: 34)
            .background(on ? (ai ? Color.lxAISoft : Color.lxAccentSoft) : Color.secondary.opacity(0.12), in: .capsule)
            .frame(minHeight: 44)
            .contentShape(.rect)
    }

    /// 剪贴板里是图片就作为一轮发送，是文字就放进输入框
    private func paste() {
        let board = UIPasteboard.general
        if board.hasImages, let images = board.images, !images.isEmpty {
            controller.attachImages(images)
        } else if let text = board.string {
            controller.draft += text
            focused.wrappedValue = true
        }
    }
}
