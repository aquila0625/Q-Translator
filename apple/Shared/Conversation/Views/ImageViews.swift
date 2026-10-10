import SwiftUI

/// 一张图片，译文覆盖在原文的位置上；showTranslation 为 false 时显示原图
struct TranslatedImageView: View {
    let image: PlatformImage
    let blocks: [ImageBlock]
    var showTranslation = true

    var body: some View {
        Image(platformImage: image)
            .resizable()
            .scaledToFit()
            .overlay {
                if showTranslation {
                    GeometryReader { geometry in
                        let size = geometry.size
                        ForEach(blocks.filter { !$0.translation.isEmpty }) { block in
                            overlay(block, in: size)
                        }
                    }
                }
            }
    }

    private func overlay(_ block: ImageBlock, in size: CGSize) -> some View {
        let turns = (block.turns ?? 0) % 4
        // 屏幕上这一块占的大小；转过 90° / 270° 时，文字是横着排的，排版用的宽高要对调
        let boxWidth: CGFloat = max(CGFloat(block.width) * size.width, 1)
        let boxHeight: CGFloat = max(CGFloat(block.height) * size.height, 1)
        let sideways = turns % 2 == 1
        let width = sideways ? boxHeight : boxWidth
        let height = sideways ? boxWidth : boxHeight
        // 字号跟图片里的原文一样大（中文字看起来比英文大，取行高的 0.7），再按这段译文要几行缩到刚好放得下。
        // 不设最小字号：原文多小译文就多小，看不清可以放大
        let lineHeight: CGFloat = height / CGFloat(max(block.lines, 1))
        let fontSize = OverlayLabel.fittedSize(block.translation, width: width, height: height,
                                               preferred: max(1, lineHeight * 0.7))
        let center = CGPoint(x: CGFloat(block.x + block.width / 2) * size.width,
                             y: CGFloat(block.y + block.height / 2) * size.height)
        return OverlayLabel(text: block.translation, fontSize: fontSize, width: width, height: height,
                            background: block.background ?? 0xFFFFFF)
            .rotationEffect(.degrees(Double(turns) * 90))
            .position(center)
    }
}

/// 盖在原文上的一块译文：用原文周围的底色完全盖住原文，深底配白字、浅底配黑字。
/// 大小严格等于原文的范围，不会压到旁边的行；字号放不下时缩小
private struct OverlayLabel: View {
    let text: String
    let fontSize: CGFloat
    let width: CGFloat
    let height: CGFloat
    let background: UInt32

    private var fill: Color {
        Color(red: Double((background >> 16) & 0xFF) / 255, green: Double((background >> 8) & 0xFF) / 255,
              blue: Double(background & 0xFF) / 255)
    }

    private var isDark: Bool {
        let r = Double((background >> 16) & 0xFF), g = Double((background >> 8) & 0xFF), b = Double(background & 0xFF)
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255 < 0.55
    }

    var body: some View {
        Text(text)
            .font(.system(size: fontSize, weight: .medium))
            .lineSpacing(0)
            .foregroundStyle(isDark ? Color.white : Color.black)
            .minimumScaleFactor(0.7)
            .multilineTextAlignment(.leading)
            .frame(width: width, height: height, alignment: .topLeading)
            .clipped()
            // 底色比原文范围多出一点点，盖住原文的边缘
            .background { RoundedRectangle(cornerRadius: 3).fill(fill).padding(-2) }
            .accessibilityLabel(text)
    }

    /// 估算这段文字放进 width × height 要几行，找一个刚好放得下的字号（不超过 preferred）
    static func fittedSize(_ text: String, width: CGFloat, height: CGFloat, preferred: CGFloat) -> CGFloat {
        // 每个字大约占几个字号的宽度：汉字 1，英文字母和数字 0.55，空格 0.3
        let ems = text.reduce(0.0) { sum, ch in
            if ch.isWhitespace { return sum + 0.3 }
            if ch.unicodeScalars.contains(where: { (0x2E80...0x9FFF).contains($0.value) || (0xFF00...0xFFEF).contains($0.value) }) { return sum + 1 }
            return sum + 0.58
        }
        guard ems > 0, width > 0, height > 0 else { return preferred }
        var size = preferred
        let step = max(preferred / 40, 0.1)
        while size > step {
            // 换行时行尾会空一点，按 90% 的宽度算
            let lines = (ems * size / (width * 0.9)).rounded(.up)
            if lines * size * 1.22 <= height { return size }
            size -= step
        }
        return step
    }
}

/// 输入框上方的待发图片：点一下预览，长按拖动排序，右上角的叉删除
struct AttachmentTray: View {
    @ObservedObject var controller: ConversationController
    @State private var previewing: ConversationController.PendingImage?
    @State private var dropTarget: UUID?
    @State private var pendingDelete: PendingDelete?

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 10) {
                ForEach(Array(controller.pendingImages.enumerated()), id: \.element.id) { index, item in
                    thumbnail(item, index: index)
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 12)
            .padding(.bottom, 4)
        }
        .scrollIndicators(.hidden)
        .sheet(item: $previewing) { item in
            PendingImagePreview(controller: controller, item: item)
        }
        .confirmDelete($pendingDelete)
    }

    private func thumbnail(_ item: ConversationController.PendingImage, index: Int) -> some View {
        Image(platformImage: item.image)
            .resizable()
            .scaledToFill()
            .frame(width: 58, height: 58)
            .clipShape(.rect(cornerRadius: 10))
            .overlay {
                RoundedRectangle(cornerRadius: 10)
                    .stroke(dropTarget == item.id ? Color.lxAccent : Color.primary.opacity(0.08), lineWidth: dropTarget == item.id ? 2 : 1)
            }
            .overlay(alignment: .bottomLeading) {
                if controller.pendingImages.count > 1 {
                    Text("\(index + 1)")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(minWidth: 16, minHeight: 16)
                        .background(.black.opacity(0.55), in: .circle)
                        .padding(3)
                }
            }
            .contentShape(.rect)
            .onTapGesture { previewing = item }
            // 长按拖到另一张图上：排到它前面
            .draggable(item.id.uuidString) {
                Image(platformImage: item.image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 64, height: 64)
                    .clipShape(.rect(cornerRadius: 10))
            }
            .dropDestination(for: String.self) { items, _ in
                guard let id = items.first.flatMap(UUID.init) else { return false }
                withAnimation(.snappy) { controller.movePending(id, before: item.id) }
                dropTarget = nil
                return true
            } isTargeted: { inside in
                dropTarget = inside ? item.id : (dropTarget == item.id ? nil : dropTarget)
            }
            .overlay(alignment: .topTrailing) {
                Button {
                    pendingDelete = PendingDelete(title: "删除第 \(index + 1) 张图片？", message: "这张图片还没发送，删除后要重新拍照或选择。") {
                        withAnimation(.snappy) { controller.removePending(item.id) }
                    }
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.black.opacity(0.7))
                        .frame(width: 36, height: 36)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .offset(x: 14, y: -14)
                .accessibilityLabel("移除第 \(index + 1) 张图片")
            }
            .accessibilityLabel("第 \(index + 1) 张待发图片，点按预览")
    }
}

/// 预览一张待发的图片，可以移除
struct PendingImagePreview: View {
    @ObservedObject var controller: ConversationController
    let item: ConversationController.PendingImage
    @Environment(\.dismiss) private var dismiss
    @State private var pendingDelete: PendingDelete?

    var body: some View {
        NavigationStack {
            Image(platformImage: item.image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭") { dismiss() }
                    }
                    ToolbarItem(placement: .destructiveAction) {
                        Button("删除", role: .destructive) {
                            pendingDelete = PendingDelete(title: "删除这张图片？", message: "这张图片还没发送，删除后要重新拍照或选择。") {
                                controller.removePending(item.id)
                                dismiss()
                            }
                        }
                    }
                }
                .inlineNavigationTitle()
                .confirmDelete($pendingDelete)
        }
        #if os(macOS)
        .frame(minWidth: 640, minHeight: 520)
        #endif
        .presentationDragIndicator(.visible)
    }
}
