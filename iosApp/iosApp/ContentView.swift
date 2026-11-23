import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        let apiKey = Bundle.main.object(forInfoDictionaryKey: "GEMINI_API_KEY") as? String ?? ""
        return MainViewControllerKt.MainViewController(geminiApiKey: apiKey)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}