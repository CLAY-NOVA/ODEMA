import CoreLocation
import Foundation
import RainKit
import SwiftUI

/// App state: saved places, settings, latest forecasts, and the refresh/alert loop.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    enum RefreshReason { case foreground, background, manual, settingsChanged, added }

    @Published var locations: [SavedLocation] {
        didSet { store.save(locations, for: .locations) }
    }
    @Published var settings: AlertSettings {
        didSet { store.save(settings, for: .settings) }
    }
    @Published private(set) var reports: [UUID: ForecastReport]
    @Published private(set) var refreshing: Set<UUID> = []
    @Published private(set) var lastRefresh: Date?
    @Published var path: [UUID] = []

    private var memories: [UUID: AlertMemory] {
        didSet { store.save(memories, for: .memories) }
    }
    private let store: Store
    private let service: ForecastService
    let geocoder: OpenMeteoGeocoder
    let locator: DeviceLocator

    private init() {
        let store = Store()
        self.store = store
        service = ForecastService()
        geocoder = OpenMeteoGeocoder()
        locator = DeviceLocator()
        locations = store.load([SavedLocation].self, for: .locations) ?? []
        settings = store.load(AlertSettings.self, for: .settings) ?? .default
        memories = store.load([UUID: AlertMemory].self, for: .memories) ?? [:]
        reports = store.loadReports()
        lastRefresh = store.lastRefresh
    }

    // MARK: - Reading

    func location(_ id: UUID) -> SavedLocation? {
        locations.first { $0.id == id }
    }

    func outlook(for id: UUID, now: Date = Date()) -> RainOutlook? {
        reports[id].map { RainAnalyzer.outlook(report: $0, settings: settings, now: now) }
    }

    func timeline(for id: UUID, now: Date = Date()) -> [TimelineSlot] {
        reports[id].map { RainAnalyzer.timeline(report: $0, settings: settings, now: now) } ?? []
    }

    // MARK: - Editing places

    func add(_ result: GeocodingResult) {
        add(SavedLocation(name: result.name, detail: result.detail, coordinate: result.coordinate))
    }

    func add(name: String, coordinate: CLLocationCoordinate2D) {
        add(SavedLocation(
            name: name,
            detail: String(format: "%.3f, %.3f", coordinate.latitude, coordinate.longitude),
            coordinate: GeoPoint(latitude: coordinate.latitude, longitude: coordinate.longitude)
        ))
    }

    /// Adds a place that follows the phone's location.
    func addCurrentLocation() async throws {
        let fix = try await locator.currentLocation()
        let name = await locator.placeName(for: fix) ?? "Current location"
        if let index = locations.firstIndex(where: \.followsDevice) {
            locations[index].coordinate = GeoPoint(fix.coordinate)
            locations[index].name = name
            await refresh([locations[index]], reason: .added)
            return
        }
        add(SavedLocation(
            name: name,
            detail: "Follows your location",
            coordinate: GeoPoint(fix.coordinate),
            followsDevice: true
        ))
    }

    private func add(_ location: SavedLocation) {
        let duplicate = locations.contains {
            abs($0.coordinate.latitude - location.coordinate.latitude) < 0.01 &&
                abs($0.coordinate.longitude - location.coordinate.longitude) < 0.01
        }
        guard !duplicate else { return }
        locations.append(location)
        Task {
            _ = await NotificationManager.shared.requestAuthorization()
            await refresh([location], reason: .added)
        }
    }

    func remove(at offsets: IndexSet) {
        let ids = offsets.map { locations[$0].id }
        locations.remove(atOffsets: offsets)
        forget(ids)
    }

    func remove(_ id: UUID) {
        locations.removeAll { $0.id == id }
        path.removeAll { $0 == id }
        forget([id])
    }

    func move(from source: IndexSet, to destination: Int) {
        locations.move(fromOffsets: source, toOffset: destination)
    }

    func setAlerts(_ enabled: Bool, for id: UUID) {
        guard let index = locations.firstIndex(where: { $0.id == id }) else { return }
        locations[index].alertsEnabled = enabled
    }

    private func forget(_ ids: [UUID]) {
        for id in ids {
            reports[id] = nil
            memories[id] = nil
        }
        store.saveReports(reports)
    }

    // MARK: - Refreshing and alerting

    func refreshAll(reason: RefreshReason) async {
        if reason != .background {
            await updateFollowingLocations()
        }
        await refresh(locations, reason: reason)
    }

    /// Fetches every enabled source for each place in parallel, then decides per place
    /// whether to alert.
    func refresh(_ targets: [SavedLocation], reason: RefreshReason) async {
        let targets = targets.filter { !refreshing.contains($0.id) }
        guard !targets.isEmpty else { return }
        refreshing.formUnion(targets.map(\.id))
        defer { refreshing.subtract(targets.map(\.id)) }

        let service = self.service
        let sources = settings.enabledSources
        let fresh = await withTaskGroup(of: (UUID, ForecastReport).self) { group in
            for location in targets {
                let point = location.coordinate
                group.addTask { (location.id, await service.report(for: point, sources: sources)) }
            }
            var collected: [UUID: ForecastReport] = [:]
            for await (id, report) in group { collected[id] = report }
            return collected
        }

        let now = Date()
        for location in targets {
            // The place may have been deleted while we were fetching.
            guard let current = self.location(location.id), let report = fresh[location.id] else { continue }
            let anyData = report.outcomes.values.contains { $0.samples != nil }
            // Offline: keep showing the last good forecast rather than a wall of errors.
            if anyData || reports[current.id] == nil {
                reports[current.id] = report
            }
            guard anyData else { continue }

            let outlook = RainAnalyzer.outlook(report: report, settings: settings, now: now)
            let result = AlertPolicy.evaluate(
                outlook: outlook,
                memory: memories[current.id] ?? AlertMemory(),
                settings: settings,
                alertsEnabled: current.alertsEnabled,
                now: now
            )
            memories[current.id] = result.memory
            if result.decision == .notify {
                let content = AlertComposer.content(locationName: current.name, outlook: outlook, now: now)
                await NotificationManager.shared.post(content, locationID: current.id)
            }
        }
        lastRefresh = now
        store.lastRefresh = now
        store.saveReports(reports)
    }

    /// Moves "current location" places to where the phone is (foreground only).
    private func updateFollowingLocations() async {
        guard locations.contains(where: \.followsDevice), locator.isAuthorized else { return }
        guard let fix = try? await locator.currentLocation() else { return }
        let name = await locator.placeName(for: fix)
        for index in locations.indices where locations[index].followsDevice {
            locations[index].coordinate = GeoPoint(fix.coordinate)
            if let name { locations[index].name = name }
        }
    }

    func sendTestAlert() async {
        _ = await NotificationManager.shared.requestAuthorization()
        let name = locations.first?.name ?? "your place"
        await NotificationManager.shared.post(
            AlertContent(title: "Rain soon in \(name)", body: "This is a test alert from Odema."),
            locationID: locations.first?.id ?? UUID()
        )
    }
}

extension GeoPoint {
    init(_ coordinate: CLLocationCoordinate2D) {
        self.init(latitude: coordinate.latitude, longitude: coordinate.longitude)
    }

    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

/// Small JSON-in-UserDefaults store; forecasts go to a cache file since they are larger.
struct Store {
    enum Key: String {
        case locations = "odema.locations"
        case settings = "odema.settings"
        case memories = "odema.alertMemories"
        case lastRefresh = "odema.lastRefresh"
    }

    private let defaults = UserDefaults.standard

    var lastRefresh: Date? {
        get { defaults.object(forKey: Key.lastRefresh.rawValue) as? Date }
        nonmutating set { defaults.set(newValue, forKey: Key.lastRefresh.rawValue) }
    }

    func load<T: Decodable>(_ type: T.Type, for key: Key) -> T? {
        guard let data = defaults.data(forKey: key.rawValue) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    func save<T: Encodable>(_ value: T, for key: Key) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key.rawValue)
    }

    private var reportsURL: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("odema-reports.json")
    }

    func loadReports() -> [UUID: ForecastReport] {
        guard let data = try? Data(contentsOf: reportsURL) else { return [:] }
        return (try? JSONDecoder().decode([UUID: ForecastReport].self, from: data)) ?? [:]
    }

    func saveReports(_ reports: [UUID: ForecastReport]) {
        guard let data = try? JSONEncoder().encode(reports) else { return }
        try? data.write(to: reportsURL, options: .atomic)
    }
}
