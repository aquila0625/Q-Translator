import Vision
#if canImport(UIKit)
import UIKit
#endif

/// 图片文字识别：用系统自带的 Vision，在本机完成，不上传图片。
enum ImageText {
    static func recognize(_ image: PlatformImage) async throws -> String {
        guard let input = image.visionInput else { return "" }
        return try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["zh-Hans", "en-US"]
            // 不开自动检测时，纯英文图片会按中文模型识别，容易认错字母
            request.automaticallyDetectsLanguage = true
            request.usesLanguageCorrection = true
            try VNImageRequestHandler(cgImage: input.image, orientation: input.orientation).perform([request])
            return paragraphs(from: request.results ?? [])
        }.value
    }

    /// 一段文字和它在图里的位置（0…1，左上角为原点）
    struct Block {
        var text: String
        var rect: CGRect
        var lines: Int
    }

    /// 按段识别，带位置，用来把译文覆盖回图片上原来的位置
    static func recognizeBlocks(_ image: PlatformImage) async throws -> [Block] {
        guard let input = image.visionInput else { return [] }
        return try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["zh-Hans", "en-US"]
            request.automaticallyDetectsLanguage = true
            request.usesLanguageCorrection = true
            try VNImageRequestHandler(cgImage: input.image, orientation: input.orientation).perform([request])
            return blocks(from: request.results ?? [])
        }.value
    }

    /// 实时相机用：识别摄像头的一帧。同步执行，调用方放在后台队列；
    /// 太小的字和把握不大的结果直接丢掉，免得画面上乱跳
    static func recognizeLive(_ handler: VNImageRequestHandler) -> [Block] {
        blocks(fromLive: liveObservations(handler))
    }

    /// 实时相机：画面里的文字可能是横着的（手机横过来拍、平放在书上拍）。
    /// 识别器横着的字也认得出来，但会当成竖排，排版和译文方向都不对。所以先按上次的方向认一遍，
    /// 看每行字的走向：是横过来的，就换成让字摆正的方向再认一遍。返回的位置按字摆正之后的画面算
    static func recognizeLive(preferred: CGImagePropertyOrientation,
                              handler: (CGImagePropertyOrientation) -> VNImageRequestHandler)
        -> (blocks: [Block], orientation: CGImagePropertyOrientation) {
        let observations = liveObservations(handler(preferred))
        let extra = extraRotation(observations)
        guard extra != 0 else { return (blocks(fromLive: observations), preferred) }
        let orientation = orientation(clockwise: (clockwise(preferred) + extra) % 360)
        return (recognizeLive(handler(orientation)), orientation)
    }

    /// 扫描翻译拍下的照片（已经摆正成 .up）：字可能是横着的，先认一遍看每行字的走向，横着的就换方向再认。
    /// 返回的位置按“把照片顺时针转 clockwise 度、字摆正之后”的画面算
    static func recognizeBlocksDetectingOrientation(_ image: CGImage) -> (blocks: [Block], clockwise: Int) {
        let observations = liveObservations(VNImageRequestHandler(cgImage: image, orientation: .up), minimumTextHeight: 0)
        let extra = extraRotation(observations)
        guard extra != 0 else { return (blocks(fromLive: observations), 0) }
        let oriented = liveObservations(VNImageRequestHandler(cgImage: image, orientation: orientation(clockwise: extra)), minimumTextHeight: 0)
        return (blocks(fromLive: oriented), extra)
    }

    private static func liveObservations(_ handler: VNImageRequestHandler, minimumTextHeight: Float = 0.008) -> [VNRecognizedTextObservation] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["zh-Hans", "en-US"]
        request.automaticallyDetectsLanguage = true
        request.usesLanguageCorrection = true
        // 书页上的小字也要能认出来
        request.minimumTextHeight = minimumTextHeight
        guard (try? handler.perform([request])) != nil else { return [] }
        return (request.results ?? []).filter { ($0.topCandidates(1).first?.confidence ?? 0) >= 0.45 }
    }

    private static func blocks(fromLive lines: [VNRecognizedTextObservation]) -> [Block] {
        // 至少要有两个字母或汉字，纯数字、符号不翻
        blocks(from: lines).filter { block in
            block.text.unicodeScalars.filter { $0.properties.isAlphabetic }.count >= 2
        }
    }

    /// 文字还要顺时针转多少度才是正的：看每行从左上角到右上角的方向（坐标原点在左下角）
    private static func extraRotation(_ lines: [VNRecognizedTextObservation]) -> Int {
        var x = 0.0, y = 0.0
        for line in lines {
            x += Double(line.topRight.x - line.topLeft.x)
            y += Double(line.topRight.y - line.topLeft.y)
        }
        guard x != 0 || y != 0 else { return 0 }
        if abs(x) >= abs(y) { return x > 0 ? 0 : 180 }
        // 往下读：字顺时针转了 90°，要再转 270°；往上读反过来
        return y < 0 ? 270 : 90
    }

    /// 方向参数和“把画面顺时针转多少度字就正了”之间的对应
    static func clockwise(_ orientation: CGImagePropertyOrientation) -> Int {
        switch orientation {
        case .right, .rightMirrored: 90
        case .down, .downMirrored: 180
        case .left, .leftMirrored: 270
        default: 0
        }
    }

    private static func orientation(clockwise angle: Int) -> CGImagePropertyOrientation {
        switch angle {
        case 90: .right
        case 180: .down
        case 270: .left
        default: .up
        }
    }

    /// 取每段文字周围一圈的颜色（中位数），作为覆盖译文的底色
    static func backgroundColors(_ image: PlatformImage, rects: [CGRect]) -> [UInt32] {
        guard let cgImage = orientedThumbnail(image, maxSide: 640) else { return rects.map { _ in 0xFFFFFF } }
        let width = cgImage.width, height = cgImage.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return rects.map { _ in 0xFFFFFF } }
        return rects.map { rect in
            // 往外扩一点，沿着四条边取样
            let margin = 3.0
            let minX = max(0, Int(rect.minX * Double(width) - margin)), maxX = min(width - 1, Int(rect.maxX * Double(width) + margin))
            let minY = max(0, Int(rect.minY * Double(height) - margin)), maxY = min(height - 1, Int(rect.maxY * Double(height) + margin))
            guard maxX > minX, maxY > minY else { return 0xFFFFFF }
            var reds: [Int] = [], greens: [Int] = [], blues: [Int] = []
            func sample(_ x: Int, _ y: Int) {
                let i = (y * width + x) * 4
                reds.append(Int(pixels[i])); greens.append(Int(pixels[i + 1])); blues.append(Int(pixels[i + 2]))
            }
            let stepX = max(1, (maxX - minX) / 24), stepY = max(1, (maxY - minY) / 8)
            for x in stride(from: minX, through: maxX, by: stepX) { sample(x, minY); sample(x, maxY) }
            for y in stride(from: minY, through: maxY, by: stepY) { sample(minX, y); sample(maxX, y) }
            func median(_ values: [Int]) -> UInt32 { UInt32(values.sorted()[values.count / 2]) }
            return median(reds) << 16 | median(greens) << 8 | median(blues)
        }
    }

    /// 按显示方向摆正的小图，取色用
    private static func orientedThumbnail(_ image: PlatformImage, maxSide: CGFloat) -> CGImage? {
        let size = image.size
        let scale = min(1, maxSide / max(size.width, size.height, 1))
        let target = CGSize(width: max(1, (size.width * scale).rounded()), height: max(1, (size.height * scale).rounded()))
        #if os(macOS)
        guard let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil),
              let context = CGContext(data: nil, width: Int(target.width), height: Int(target.height), bitsPerComponent: 8,
                                      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.draw(cgImage, in: CGRect(origin: .zero, size: target))
        return context.makeImage()
        #else
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }.cgImage
        #endif
    }

    /// 相邻、左右对齐、行距正常的几行合成一段；分栏或隔得远的分开
    private static func blocks(from lines: [VNRecognizedTextObservation]) -> [Block] {
        var result: [Block] = []
        var previous: VNRecognizedTextObservation?
        for line in lines {
            guard let text = line.topCandidates(1).first?.string, !text.isEmpty else { continue }
            let box = line.boundingBox
            // Vision 的原点在左下角，换成左上角
            let rect = CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
            if let previous, var last = result.popLast() {
                let p = previous.boundingBox
                let gap = p.minY - box.maxY
                let lineHeight = min(p.height, box.height)
                let overlapsHorizontally = box.minX < p.maxX && box.maxX > p.minX
                if gap >= -lineHeight * 0.3, gap < lineHeight * 0.8, overlapsHorizontally {
                    let joiner = (last.text.last.map(isCJK) ?? false) && (text.first.map(isCJK) ?? false) ? "" : " "
                    last.text += joiner + text
                    last.rect = last.rect.union(rect)
                    last.lines += 1
                    result.append(last)
                } else {
                    result.append(last)
                    result.append(Block(text: text, rect: rect, lines: 1))
                }
            } else {
                result.append(Block(text: text, rect: rect, lines: 1))
            }
            previous = line
        }
        return result
    }

    /// Vision 按“行”返回结果。同一段里折行的几行要拼回一段，否则会被当成几句话分别翻译。
    private static func paragraphs(from lines: [VNRecognizedTextObservation]) -> String {
        var output = ""
        var previous: VNRecognizedTextObservation?
        for line in lines {
            guard let text = line.topCandidates(1).first?.string, !text.isEmpty else { continue }
            if let previous {
                // 坐标原点在左下角。行距明显大于行高，或上一行明显短于这一行（标题、段末），就认为是新的一段
                let gap = previous.boundingBox.minY - line.boundingBox.maxY
                let lineHeight = min(previous.boundingBox.height, line.boundingBox.height)
                if gap > lineHeight * 0.5 || previous.boundingBox.width < line.boundingBox.width * 0.5 {
                    output += "\n"
                } else if !(output.last.map(isCJK) ?? false) || !(text.first.map(isCJK) ?? false) {
                    output += " "
                }
            }
            output += text
            previous = line
        }
        return output
    }

    /// 汉字或中文标点：这两类字符之间折行时不需要补空格
    private static func isCJK(_ character: Character) -> Bool {
        character.unicodeScalars.contains {
            (0x3000...0x303F).contains($0.value) || (0x3400...0x9FFF).contains($0.value) || (0xFF00...0xFFEF).contains($0.value)
        }
    }
}
