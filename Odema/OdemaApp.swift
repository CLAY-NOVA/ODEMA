import SwiftUI

@main
struct OdemaApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = AppModel.shared

    init() {
        NotificationManager.shared.configure()
    }

    var body: some Scene {
        WindowGroup {
            LocationsView()
                .environmentObject(model)
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        Task { await model.refreshAll(reason: .foreground) }
                    case .background:
                        BackgroundRefresh.schedule()
                    default:
                        break
                    }
                }
        }
        // iOS wakes the app every so often (at its discretion, typically 15–60 min
        // for apps you use) to check every saved place and alert if rain is coming.
        .backgroundTask(.appRefresh(BackgroundRefresh.taskIdentifier)) {
            await BackgroundRefresh.handle()
        }
    }
}
