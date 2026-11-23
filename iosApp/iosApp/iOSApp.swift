import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    init() {
        IosCameraBridge.shared.registerHandler(handler: IosCameraController.shared)
    }

    var body: some Scene {
        WindowGroup {
            DriveLiveComposeContainer()
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
        }
    }
}

private struct DriveLiveComposeContainer: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let apiKey = resolveGeminiApiKey()
        return MainViewControllerKt.MainViewController(geminiApiKey: apiKey)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // ComposeUIViewController manages its own reactive state via Koin DriveLiveViewModel
    }

    private func resolveGeminiApiKey() -> String {
        if let plistKey = Bundle.main.object(forInfoDictionaryKey: "GEMINI_API_KEY") as? String,
           !plistKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           !plistKey.hasPrefix("$(") {
            return plistKey
        }
        if let envKey = ProcessInfo.processInfo.environment["GEMINI_API_KEY"],
           !envKey.isEmpty {
            return envKey
        }
        return ""
    }
}