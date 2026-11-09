import BackgroundTasks
import Foundation

/// Background App Refresh: iOS decides when to run it based on how often you use
/// the app, battery and network. We ask for every 15 minutes; expect 15–60 minutes
/// in practice. Low Power Mode or turning off Background App Refresh stops it.
enum BackgroundRefresh {
    /// Must match `BGTaskSchedulerPermittedIdentifiers` in Config/Info.plist.
    static let taskIdentifier = "com.odema.rainalert.refresh"

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Fails in the Simulator and when Background App Refresh is off.
            print("Odema: could not schedule background refresh: \(error)")
        }
    }

    @MainActor
    static func handle() async {
        // Queue the next run first so the chain continues even if this one is cut short.
        schedule()
        await AppModel.shared.refreshAll(reason: .background)
    }
}
