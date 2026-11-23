import AVFoundation
import CoreImage
import Foundation
import QuartzCore
import Shared
import UIKit

final class IosCameraController: NSObject, IosCameraHandler, AVCaptureVideoDataOutputSampleBufferDelegate {
    static let shared = IosCameraController()

    private let sessionQueue = DispatchQueue(label: "com.nowlsantos.drivelive.camera.session")
    private let outputQueue = DispatchQueue(label: "com.nowlsantos.drivelive.camera.output")
    private let ciContext = CIContext(options: nil)
    private let stateLock = NSLock()

    private var captureSession: AVCaptureSession?
    private var captureDevice: AVCaptureDevice?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private weak var previewLayer: AVCaptureVideoPreviewLayer?
    private var frameSink: IosCameraFrameSink?
    private var lastFrameTimestamp: CFTimeInterval = 0
    private var isStreamRequested = false

    private override init() {
        super.init()
    }

    func attachPreviewLayer(previewLayer: AVCaptureVideoPreviewLayer?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.previewLayer = previewLayer
            if let layer = previewLayer, let session = self.captureSession {
                layer.session = session
                self.applyRotationAngles()
            }
        }
    }

    func startStream(previewLayer: AVCaptureVideoPreviewLayer?, sink: IosCameraFrameSink) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let layer = previewLayer {
                self.previewLayer = layer
            }
            self.stateLock.lock()
            self.frameSink = sink
            self.lastFrameTimestamp = 0
            self.stateLock.unlock()

            self.isStreamRequested = true
            self.ensurePermissionAndStart()
        }
    }

    func stopStream() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isStreamRequested = false

            self.stateLock.lock()
            self.frameSink = nil
            self.stateLock.unlock()

            self.previewLayer?.session = nil
            self.rotationCoordinator = nil
            let session = self.captureSession
            self.captureSession = nil
            self.captureDevice = nil
            self.videoOutput = nil

            self.sessionQueue.async {
                session?.stopRunning()
            }
        }
    }

    private func ensurePermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndRunSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted else { return }
                DispatchQueue.main.async {
                    guard let self, self.isStreamRequested else { return }
                    self.configureAndRunSession()
                }
            }
        default:
            break
        }
    }

    private func configureAndRunSession() {
        if let existing = captureSession {
            if let layer = previewLayer {
                layer.session = existing
                applyRotationAngles()
            }
            sessionQueue.async {
                if !existing.isRunning {
                    existing.startRunning()
                }
            }
            return
        }

        sessionQueue.async { [weak self] in
            guard let self else { return }
            let session = AVCaptureSession()
            session.beginConfiguration()
            session.sessionPreset = .hd1280x720

            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                session.commitConfiguration()
                return
            }
            session.addInput(input)

            let output = AVCaptureVideoDataOutput()
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
            ]
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(self, queue: self.outputQueue)

            guard session.canAddOutput(output) else {
                session.commitConfiguration()
                return
            }
            session.addOutput(output)
            session.commitConfiguration()
            session.startRunning()

            DispatchQueue.main.async {
                guard self.isStreamRequested else {
                    self.sessionQueue.async {
                        session.stopRunning()
                    }
                    return
                }
                self.captureSession = session
                self.captureDevice = device
                self.videoOutput = output
                if let layer = self.previewLayer {
                    layer.session = session
                }
                self.applyRotationAngles()
            }
        }
    }

    private func applyRotationAngles() {
        guard let device = captureDevice else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        rotationCoordinator = coordinator

        let previewAngle = coordinator.videoRotationAngleForHorizonLevelPreview
        if let previewConn = previewLayer?.connection,
           previewConn.isVideoRotationAngleSupported(previewAngle) {
            previewConn.videoRotationAngle = previewAngle
        }

        let captureAngle = coordinator.videoRotationAngleForHorizonLevelCapture
        if let outputConn = videoOutput?.connection(with: .video),
           outputConn.isVideoRotationAngleSupported(captureAngle) {
            outputConn.videoRotationAngle = captureAngle
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = CACurrentMediaTime()

        stateLock.lock()
        let sink = frameSink
        let shouldEmit = (sink != nil) && (now - lastFrameTimestamp >= 1.0)
        if shouldEmit {
            lastFrameTimestamp = now
        }
        stateLock.unlock()

        guard shouldEmit, let activeSink = sink else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        var ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let extent = ciImage.extent
        let longestEdge = max(extent.width, extent.height)
        if longestEdge > 768 {
            let scale = 768.0 / longestEdge
            ciImage = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }

        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else { return }
        let uiImage = UIImage(cgImage: cgImage)
        guard let jpegData = uiImage.jpegData(compressionQuality: 0.8) else { return }

        activeSink.onJpegFrame(data: jpegData)
    }
}
