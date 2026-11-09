import CoreLocation
import Foundation

/// One-shot "where am I?" with async/await on top of CLLocationManager.
@MainActor
final class DeviceLocator: NSObject, CLLocationManagerDelegate {
    enum LocatorError: LocalizedError {
        case denied

        var errorDescription: String? {
            "Location access is off. Allow it in Settings → Odema → Location, or search for a place instead."
        }
    }

    private let manager = CLLocationManager()
    private var fixContinuation: CheckedContinuation<CLLocation, Error>?
    private var authContinuation: CheckedContinuation<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    var isAuthorized: Bool {
        [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
    }

    func currentLocation() async throws -> CLLocation {
        if manager.authorizationStatus == .notDetermined {
            await withCheckedContinuation { continuation in
                authContinuation = continuation
                manager.requestWhenInUseAuthorization()
            }
        }
        guard isAuthorized else { throw LocatorError.denied }
        if let recent = manager.location, recent.timestamp.timeIntervalSinceNow > -300 {
            return recent
        }
        return try await withCheckedThrowingContinuation { continuation in
            fixContinuation?.resume(throwing: CancellationError())
            fixContinuation = continuation
            manager.requestLocation()
        }
    }

    /// Town or neighbourhood name for a coordinate, using Apple's geocoder.
    func placeName(for location: CLLocation) async -> String? {
        guard let placemark = try? await CLGeocoder().reverseGeocodeLocation(location).first else { return nil }
        return placemark.locality ?? placemark.subLocality ?? placemark.name ?? placemark.administrativeArea
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        Task { @MainActor in
            self.fixContinuation?.resume(returning: fix)
            self.fixContinuation = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.fixContinuation?.resume(throwing: error)
            self.fixContinuation = nil
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            guard status != .notDetermined else { return }
            self.authContinuation?.resume()
            self.authContinuation = nil
        }
    }
}
