import Foundation
import RainKit
import UserNotifications

/// Local notifications. No server, no push: alerts come from the phone itself.
@MainActor
final class NotificationManager: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationManager()

    private var center: UNUserNotificationCenter { .current() }

    func configure() {
        center.delegate = self
    }

    @discardableResult
    func requestAuthorization() async -> Bool {
        let current = await center.notificationSettings()
        guard current.authorizationStatus == .notDetermined else {
            return current.authorizationStatus == .authorized || current.authorizationStatus == .provisional
        }
        return (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    func post(_ alert: AlertContent, locationID: UUID) async {
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        content.threadIdentifier = locationID.uuidString
        content.userInfo = ["locationID": locationID.uuidString]
        content.interruptionLevel = .active
        // One notification per place: a newer alert replaces the older one.
        let request = UNNotificationRequest(identifier: "rain-\(locationID.uuidString)", content: content, trigger: nil)
        do {
            try await center.add(request)
        } catch {
            print("Odema: could not post notification: \(error)")
        }
    }

    // Show alerts even while the app is open.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    // Tapping an alert opens that place.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard let raw = response.notification.request.content.userInfo["locationID"] as? String,
              let id = UUID(uuidString: raw) else { return }
        await MainActor.run {
            let model = AppModel.shared
            guard model.location(id) != nil else { return }
            model.path = [id]
        }
    }
}
