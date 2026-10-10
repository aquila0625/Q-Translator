import AVFoundation
import SwiftUI
import UIKit
import Vision

/// 自己的相机：预览、前后摄像头切换、手电筒、缩放、拍照。
/// 扫描翻译（像微信的扫一扫翻译）：画面里有字、手机拿稳约 1.5 秒，就自动拍一张交给翻译。
/// 检测在单独的队列里做，只在画面稳定时才看有没有字。
final class CameraSession: NSObject, ObservableObject, @unchecked Sendable {
    enum Access { case unknown, ready, denied }

    let session = AVCaptureSession()
    @Published private(set) var access: Access = .unknown
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published private(set) var torchOn = false
    @Published private(set) var hasTorch = false
    /// 第一帧到了、方向也摆正了才显示预览，免得一开始闪出一个横着的画面
    @Published private(set) var previewReady = false

    /// 画面里有字、拿稳了多久（0…1，到 1 就自动拍）
    @Published private(set) var steadyProgress = 0.0
    @Published private(set) var textInView = false

    /// 拿稳够久了，该自动拍了（在主线程上回调，之后要 rearm 才会再触发）
    var onAutoCapture: (@MainActor () -> Void)?
    /// 一直晃或者一直在对焦，需要提醒用户拿稳
    var onUnstable: (@Sendable () -> Void)?

    private let sessionQueue = DispatchQueue(label: "qtranslator.camera.session")
    private let frameQueue = DispatchQueue(label: "qtranslator.camera.frames")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var observations: [NSKeyValueObservation] = []
    private var focusObservation: NSKeyValueObservation?
    private weak var previewLayer: AVCaptureVideoPreviewLayer?
    private var configured = false
    private var photoCompletion: ((UIImage?) -> Void)?

    // 下面这些只在 frameQueue 上读写
    private var recognizing = false
    private var lastRun = Date.distantPast
    private var paused = false
    private var recognitionEnabled = true
    private var firstFrameSeen = false
    /// 自动拍过一次之后要等回到取景（rearm）才再拍
    private var armed = true
    private var steadySince: Date?
    private var textFound = false
    private var lastReported = (0.0, false)
    /// 拍照的角度和预览一样，拍下来的照片就是屏幕上看到的样子
    private var photoAngle: CGFloat = 90
    private var adjustingFocus = false
    private var referenceGrid: [UInt8] = []
    private var referenceTime = Date.distantPast
    private var isMoving = false
    private var movingSamples = 0
    private var lastMovement = Date.distantPast
    private var unstableSince: Date?
    private var lastWarning = Date.distantPast

    /// 拿稳多久自动拍（秒）
    private let steadyTime: TimeInterval = 1.5
    /// 看画面里有没有字的最短间隔（秒）
    private let recognizeInterval: TimeInterval = 0.5
    /// 画面变化超过这个值（0…255 的平均差）就算在移动
    /// 轻微手抖不算：门槛高一点，而且要连着两次都超过才算在动
    private let motionThreshold = 14.0
    /// 停止移动之后再等多久才算稳定（秒）
    private let settleTime: TimeInterval = 0.4
    /// 一直不稳定超过多久提醒用户（秒）
    private let warnAfter: TimeInterval = 3.0

    static var hasCamera: Bool {
        AVCaptureDevice.default(for: .video) != nil
    }

    // MARK: 启动和停止

    func start() {
        previewReady = false
        frameQueue.async { self.firstFrameSeen = false }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            access = .ready
            run()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    self.access = granted ? .ready : .denied
                    if granted { self.run() }
                }
            }
        default:
            access = .denied
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning { self.session.stopRunning() }
            if let device = self.device, device.hasTorch, device.torchMode != .off {
                try? device.lockForConfiguration()
                device.torchMode = .off
                device.unlockForConfiguration()
            }
        }
        DispatchQueue.main.async { self.torchOn = false }
    }

    private func run() {
        sessionQueue.async {
            if !self.configured { self.configure(position: .back) }
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    private func configure(position: AVCaptureDevice.Position) {
        session.beginConfiguration()
        session.sessionPreset = .photo
        if !configured {
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
            if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
            if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
            configured = true
        }
        installInput(position: position)
        session.commitConfiguration()
        // 在开始取景之前就把方向摆好
        applyConnections()
    }

    private func installInput(position: AVCaptureDevice.Position) {
        let found = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
        guard let found, let input = try? AVCaptureDeviceInput(device: found) else { return }
        session.inputs.forEach { session.removeInput($0) }
        if session.canAddInput(input) { session.addInput(input) }
        device = found
        // 近处的小字也要能对上焦
        if found.isFocusModeSupported(.continuousAutoFocus) {
            try? found.lockForConfiguration()
            found.focusMode = .continuousAutoFocus
            if found.isAutoFocusRangeRestrictionSupported { found.autoFocusRangeRestriction = .none }
            found.unlockForConfiguration()
        }
        focusObservation = found.observe(\.isAdjustingFocus, options: [.initial, .new]) { [weak self] device, _ in
            let value = device.isAdjustingFocus
            self?.frameQueue.async { self?.adjustingFocus = value }
        }
        DispatchQueue.main.async {
            self.position = position
            self.hasTorch = found.hasTorch
            self.torchOn = false
        }
    }

    // MARK: 预览和方向

    /// 预览页建好之后调用：绑定预览层
    func attach(_ layer: AVCaptureVideoPreviewLayer) {
        previewLayer = layer
        sessionQueue.async { self.applyConnections() }
    }

    private func onMain<T>(_ work: () -> T) -> T {
        Thread.isMainThread ? work() : DispatchQueue.main.sync(execute: work)
    }

    /// 让预览、识别用的帧、拍下的照片都朝上，前置摄像头也不做镜像（对着文字时，镜像的字没法看）
    private func applyConnections() {
        guard let device else { return }
        for connection in [videoOutput.connection(with: .video), photoOutput.connection(with: .video)].compactMap({ $0 }) {
            connection.automaticallyAdjustsVideoMirroring = false
            if connection.isVideoMirroringSupported { connection.isVideoMirrored = false }
        }
        guard let layer = onMain({ previewLayer }) else { return }
        let coordinator = onMain { AVCaptureDevice.RotationCoordinator(device: device, previewLayer: layer) }
        rotation = coordinator
        onMain {
            if let connection = layer.connection {
                connection.automaticallyAdjustsVideoMirroring = false
                if connection.isVideoMirroringSupported { connection.isVideoMirrored = false }
            }
        }
        updateAngles(coordinator)
        observations = [
            coordinator.observe(\.videoRotationAngleForHorizonLevelPreview) { [weak self] coordinator, _ in self?.updateAngles(coordinator) },
            coordinator.observe(\.videoRotationAngleForHorizonLevelCapture) { [weak self] coordinator, _ in self?.updateAngles(coordinator) },
        ]
    }

    /// iPhone 的界面只有竖屏，预览就一直是竖屏的，像系统相机一样不跟着手机转；iPad 的界面会转，预览跟着界面。
    /// 识别用的帧和预览一样，画面里的文字是横是竖由识别自己判断
    private func updateAngles(_ coordinator: AVCaptureDevice.RotationCoordinator) {
        let preview = onMain { UIDevice.current.userInterfaceIdiom == .phone } ? 90 : coordinator.videoRotationAngleForHorizonLevelPreview
        onMain {
            if let connection = previewLayer?.connection, connection.isVideoRotationAngleSupported(preview) {
                connection.videoRotationAngle = preview
            }
        }
        sessionQueue.async {
            self.photoAngle = preview
            if let connection = self.videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(preview) {
                connection.videoRotationAngle = preview
            }
        }
    }

    // MARK: 操作

    func flip() {
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        sessionQueue.async {
            self.session.beginConfiguration()
            self.installInput(position: next)
            self.session.commitConfiguration()
            self.applyConnections()
        }
    }

    func toggleTorch() {
        guard let device, device.hasTorch else { return }
        let on = !torchOn
        sessionQueue.async {
            guard (try? device.lockForConfiguration()) != nil else { return }
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
            DispatchQueue.main.async { self.torchOn = on }
        }
    }

    /// 缩放倍数（1 倍到 6 倍，不超过镜头能做到的）
    func zoom(to factor: CGFloat) {
        guard let device else { return }
        sessionQueue.async {
            let value = max(1, min(factor, min(device.maxAvailableVideoZoomFactor, 6)))
            guard (try? device.lockForConfiguration()) != nil else { return }
            device.videoZoomFactor = value
            device.unlockForConfiguration()
        }
    }

    var currentZoom: CGFloat { device?.videoZoomFactor ?? 1 }

    /// 暂停或继续识别（预览画面也跟着定格）
    func setPaused(_ value: Bool) {
        frameQueue.async {
            self.paused = value
            if !value {
                self.referenceTime = .distantPast
                self.isMoving = false
                self.unstableSince = nil
            }
        }
        DispatchQueue.main.async { self.previewLayer?.connection?.isEnabled = !value }
    }

    /// 打开或关闭自动扫描（关了就只是一个相机，要自己按快门）
    func setRecognition(_ value: Bool) {
        frameQueue.async {
            self.recognitionEnabled = value
            self.resetSteady()
            if !value {
                self.isMoving = false
                self.unstableSince = nil
            }
        }
    }

    /// 回到取景：可以再自动拍
    func rearm() {
        frameQueue.async {
            self.armed = true
            self.resetSteady()
        }
    }

    private func resetSteady() {
        steadySince = nil
        textFound = false
        report(0, false)
    }

    private func report(_ progress: Double, _ text: Bool) {
        // 变化不大就不刷新界面
        guard abs(progress - lastReported.0) >= 0.05 || text != lastReported.1 || (progress == 0) != (lastReported.0 == 0) else { return }
        lastReported = (progress, text)
        DispatchQueue.main.async {
            self.steadyProgress = progress
            self.textInView = text
        }
    }

    func capture(_ completion: @escaping (UIImage?) -> Void) {
        sessionQueue.async {
            guard self.session.isRunning else { DispatchQueue.main.async { completion(nil) }; return }
            if let connection = self.photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(self.photoAngle) {
                connection.videoRotationAngle = self.photoAngle
            }
            self.photoCompletion = completion
            self.photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
    }

    // MARK: 稳定度

    /// 画面上取 24×32 个点的亮度，和上一次比较，看手机有没有在大幅移动
    private func sampleGrid(_ buffer: CVPixelBuffer) -> [UInt8] {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return [] }
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        let pixels = base.assumingMemoryBound(to: UInt8.self)
        var grid: [UInt8] = []
        grid.reserveCapacity(24 * 32)
        for row in 0..<32 {
            let y = (row * 2 + 1) * height / 64
            for column in 0..<24 {
                let x = (column * 2 + 1) * width / 48
                grid.append(pixels[y * stride + x * 4 + 1])
            }
        }
        return grid
    }

    private func trackMotion(_ buffer: CVPixelBuffer, _ now: Date) {
        guard now.timeIntervalSince(referenceTime) >= 0.15 else { return }
        let current = sampleGrid(buffer)
        let previous = referenceGrid
        referenceGrid = current
        referenceTime = now
        guard previous.count == current.count, !current.isEmpty else { return }
        var total = 0
        for index in current.indices { total += abs(Int(current[index]) - Int(previous[index])) }
        let difference = Double(total) / Double(current.count)
        if difference > motionThreshold {
            movingSamples += 1
            if movingSamples >= 2 {
                lastMovement = now
                isMoving = true
            }
        } else {
            movingSamples = 0
            if isMoving, now.timeIntervalSince(lastMovement) >= settleTime { isMoving = false }
        }
        if isMoving || adjustingFocus {
            if let since = unstableSince {
                if now.timeIntervalSince(since) >= warnAfter, now.timeIntervalSince(lastWarning) >= 8 {
                    lastWarning = now
                    onUnstable?()
                }
            } else {
                unstableSince = now
            }
        } else {
            unstableSince = nil
        }
    }
}

extension CameraSession: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if !firstFrameSeen {
            firstFrameSeen = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { self.previewReady = true }
        }
        guard recognitionEnabled, !paused, armed else { return }
        let now = Date()
        trackMotion(buffer, now)
        // 明显动了就重新计时；正在对焦时只是先不拍（对好焦再拍，照片才清楚）
        if isMoving {
            if steadySince != nil { resetSteady() }
            return
        }
        if steadySince == nil { steadySince = now }
        // 拿稳了：每半秒看一次画面里有没有字，看到了就不用再看
        if !textFound, !recognizing, now.timeIntervalSince(lastRun) >= recognizeInterval {
            recognizing = true
            let blocks = ImageText.recognizeLive(VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up))
            let letters = blocks.reduce(0) { $0 + $1.text.unicodeScalars.filter { $0.properties.isAlphabetic }.count }
            textFound = letters >= 4
            recognizing = false
            lastRun = Date()
        }
        guard textFound, let since = steadySince else {
            report(0, false)
            return
        }
        let progress = min(1, now.timeIntervalSince(since) / steadyTime)
        report(progress, true)
        if progress >= 1, !adjustingFocus {
            armed = false
            resetSteady()
            DispatchQueue.main.async { self.onAutoCapture?() }
        }
    }
}

extension CameraSession: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let image = photo.fileDataRepresentation().flatMap { UIImage(data: $0) }
        let completion = photoCompletion
        photoCompletion = nil
        DispatchQueue.main.async { completion?(image) }
    }
}

/// 相机预览。图层铺满取景区，识别结果的坐标按同样的方式换算
struct CameraPreview: UIViewRepresentable {
    let camera: CameraSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = camera.session
        view.previewLayer.videoGravity = .resizeAspectFill
        camera.attach(view.previewLayer)
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}
}
