import RainKit
import SwiftUI
import UserNotifications

/// Home screen: every saved place with its rain outlook.
struct LocationsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingAdd = false
    @State private var showingSettings = false
    @State private var notificationStatus: UNAuthorizationStatus = .authorized

    var body: some View {
        NavigationStack(path: $model.path) {
            List {
                if notificationStatus == .denied {
                    Section {
                        NotificationsOffBanner()
                    }
                }
                Section {
                    ForEach(model.locations) { location in
                        NavigationLink(value: location.id) {
                            LocationRow(location: location)
                        }
                    }
                    .onDelete { model.remove(at: $0) }
                    .onMove { model.move(from: $0, to: $1) }
                } footer: {
                    if let last = model.lastRefresh, !model.locations.isEmpty {
                        Text("Checked \(last, style: .relative) ago · alerts for rain in the next \(Format.window(model.settings.lookahead))")
                    }
                }
            }
            .overlay {
                if model.locations.isEmpty {
                    ContentUnavailableView {
                        Label("No places yet", systemImage: "cloud.rain")
                    } description: {
                        Text("Add a place and Odema will alert you before rain arrives there.")
                    } actions: {
                        Button("Add a place") { showingAdd = true }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
            .navigationTitle("Rain Alerts")
            .navigationDestination(for: UUID.self) { id in
                LocationDetailView(locationID: id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showingSettings = true } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if !model.locations.isEmpty { EditButton() }
                    Button { showingAdd = true } label: {
                        Label("Add place", systemImage: "plus")
                    }
                }
            }
            .refreshable { await model.refreshAll(reason: .manual) }
            .sheet(isPresented: $showingAdd) {
                AddLocationView().environmentObject(model)
            }
            .sheet(isPresented: $showingSettings, onDismiss: {
                Task { await model.refreshAll(reason: .settingsChanged) }
            }) {
                SettingsView().environmentObject(model)
            }
            .task {
                if !model.locations.isEmpty {
                    await NotificationManager.shared.requestAuthorization()
                }
                notificationStatus = await NotificationManager.shared.authorizationStatus()
            }
        }
    }
}

struct LocationRow: View {
    @EnvironmentObject private var model: AppModel
    let location: SavedLocation

    var body: some View {
        let status = RowStatus(model: model, location: location)
        HStack(spacing: 14) {
            Image(systemName: status.symbol)
                .symbolRenderingMode(.multicolor)
                .font(.title2)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(location.name).font(.headline)
                    if location.followsDevice {
                        Image(systemName: "location.fill").font(.caption2).foregroundStyle(.blue)
                    }
                    if !location.alertsEnabled {
                        Image(systemName: "bell.slash").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Text(status.headline)
                    .font(.subheadline)
                    .foregroundStyle(status.isRain ? Color.blue : Color.secondary)
            }
            Spacer()
            if model.refreshing.contains(location.id) {
                ProgressView()
            } else if let badge = status.badge {
                Text(badge)
                    .font(.caption.monospacedDigit().weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.blue.opacity(0.15), in: Capsule())
                    .foregroundStyle(.blue)
            }
        }
        .padding(.vertical, 4)
    }
}

/// The one-line summary used in the list and the detail header.
struct RowStatus {
    var symbol: String
    var headline: String
    var badge: String?
    var isRain: Bool

    @MainActor
    init(model: AppModel, location: SavedLocation, now: Date = Date()) {
        guard let outlook = model.outlook(for: location.id, now: now) else {
            self.init(symbol: "cloud", headline: model.refreshing.contains(location.id) ? "Checking…" : "No forecast yet", badge: nil, isRain: false)
            return
        }
        self.init(outlook: outlook, lookahead: model.settings.lookahead, now: now)
    }

    init(symbol: String, headline: String, badge: String?, isRain: Bool) {
        self.symbol = symbol
        self.headline = headline
        self.badge = badge
        self.isRain = isRain
    }

    init(outlook: RainOutlook, lookahead: TimeInterval, now: Date) {
        let percent = "\(Int((outlook.confidence * 100).rounded()))%"
        if outlook.availableCount == 0 {
            self.init(symbol: "exclamationmark.icloud", headline: "No source could answer", badge: nil, isRain: false)
        } else if outlook.isRainingNow(now) {
            self.init(symbol: "cloud.heavyrain.fill", headline: "Rain now", badge: percent, isRain: true)
        } else if outlook.willRain, let start = outlook.expectedStart {
            self.init(symbol: "cloud.rain.fill", headline: "Rain \(AlertComposer.relative(start.timeIntervalSince(now))) · \(Format.time(start))", badge: percent, isRain: true)
        } else if outlook.agreeingCount > 0 {
            self.init(symbol: "cloud.sun.rain.fill", headline: "Some sources see rain in the next \(Format.window(lookahead))", badge: percent, isRain: false)
        } else {
            self.init(symbol: "sun.max.fill", headline: "Dry for the next \(Format.window(lookahead))", badge: nil, isRain: false)
        }
    }
}

struct NotificationsOffBanner: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Notifications are off", systemImage: "bell.slash.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text("Odema can't alert you about rain until you allow notifications.")
                .font(.subheadline)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

enum Format {
    static func window(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes < 60 { return "\(minutes) min" }
        let hours = minutes / 60
        return hours == 1 ? "hour" : "\(hours) h"
    }

    static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }

    static func intensity(_ mmPerHour: Double) -> String {
        "\(RainIntensity(mmPerHour: mmPerHour).rawValue), \(String(format: "%.1f", mmPerHour)) mm/h"
    }
}
