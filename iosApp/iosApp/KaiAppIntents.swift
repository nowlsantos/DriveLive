import AppIntents
import Shared

struct EngageKaiIntent: AppIntent {
    static var title: LocalizedStringResource = "Engage KAI"
    static var description = IntentDescription("Launch DriveLive and start hands-free voice co-pilot KAI.")
    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult {
        MainViewControllerKt.engageKaiFromShortcut()
        return .result()
    }
}

struct DriveLiveShortcutsProvider: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: EngageKaiIntent(),
            phrases: [
                "Engage KAI in \(.applicationName)",
                "Talk to KAI in \(.applicationName)",
                "Start driving co-pilot with \(.applicationName)"
            ],
            shortTitle: "Engage KAI",
            systemImageName: "waveform.circle.fill"
        )
    }
}
