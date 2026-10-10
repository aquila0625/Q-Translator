import SwiftUI

/// 看图用的容器：双指缩放（1～6 倍）、放大后拖动查看、双击放大到 2.5 倍或还原。
/// 没放大时不拦拖动手势，下拉关闭等照常
struct ZoomableView<Content: View>: View {
    @ViewBuilder let content: Content

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            // 按放大后的尺寸重新排版（不是把画面拉大），放大后字依然清楚
            ZStack {
                content
                    .frame(width: size.width * scale, height: size.height * scale)
                    .offset(offset)
            }
            .frame(width: size.width, height: size.height)
            .contentShape(.rect)
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in
                            let newScale = min(max(lastScale * value.magnification, 1), 6)
                            // 以两指中间那一点为中心放大：那一点在屏幕上的位置不动
                            let anchor = CGPoint(x: value.startAnchor.x * size.width - size.width / 2,
                                                 y: value.startAnchor.y * size.height - size.height / 2)
                            let ratio = newScale / lastScale
                            scale = newScale
                            offset = clamp(CGSize(width: anchor.x - (anchor.x - lastOffset.width) * ratio,
                                                  height: anchor.y - (anchor.y - lastOffset.height) * ratio), in: size)
                        }
                        .onEnded { _ in
                            if scale < 1.05 { reset() } else { lastScale = scale; lastOffset = offset }
                        }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
                            guard scale > 1 else { return }
                            offset = clamp(CGSize(width: lastOffset.width + value.translation.width,
                                                  height: lastOffset.height + value.translation.height), in: size)
                        }
                        .onEnded { _ in lastOffset = offset },
                    including: scale > 1 ? .all : .subviews
                )
                .onTapGesture(count: 2) { location in
                    withAnimation(.easeOut(duration: 0.25)) {
                        if scale > 1 {
                            reset()
                        } else {
                            // 放大到点的那个地方
                            scale = 2.5
                            offset = clamp(CGSize(width: (size.width / 2 - location.x) * 1.5,
                                                  height: (size.height / 2 - location.y) * 1.5), in: size)
                            lastScale = scale
                            lastOffset = offset
                        }
                    }
                }
        }
        .clipped()
        .accessibilityHint("双指缩放，双击放大或还原")
    }

    private func reset() {
        scale = 1
        lastScale = 1
        offset = .zero
        lastOffset = .zero
    }

    /// 拖动不能把图拖出边界
    private func clamp(_ offset: CGSize, in size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2, maxY = size.height * (scale - 1) / 2
        return CGSize(width: min(max(offset.width, -maxX), maxX), height: min(max(offset.height, -maxY), maxY))
    }
}
