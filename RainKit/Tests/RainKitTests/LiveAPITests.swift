import Foundation
import XCTest
@testable import RainKit

/// Hits the real APIs. Run with `RAINKIT_LIVE=1 swift test --filter LiveAPITests`.
final class LiveAPITests: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["RAINKIT_LIVE"] == "1", "set RAINKIT_LIVE=1 to call the real APIs")
    }

    func testEverySourceAnswersSomewhere() async {
        let service = ForecastService()
        let places: [(String, GeoPoint)] = [
            ("Oslo", GeoPoint(latitude: 59.9139, longitude: 10.7522)),
            ("New York", GeoPoint(latitude: 40.7128, longitude: -74.0060)),
            ("Berlin", GeoPoint(latitude: 52.52, longitude: 13.405)),
        ]
        var succeeded: Set<SourceID> = []
        for (name, point) in places {
            let report = await service.report(for: point, sources: Set(SourceID.allCases))
            for source in SourceID.allCases {
                let outcome = report.outcomes[source]
                let summary: String
                switch outcome {
                case .success(let s)?:
                    succeeded.insert(source)
                    summary = "\(s.count) samples, first \(s.first.map { "\($0.start)" } ?? "-")"
                case .notCovered?: summary = "not covered"
                case .failure(let m)?: summary = "FAILED: \(m)"
                case nil: summary = "missing"
                }
                print("[live] \(name) \(source.rawValue): \(summary)")
            }
            let outlook = RainAnalyzer.outlook(report: report, settings: .default, now: Date())
            print("[live] \(name) outlook: willRain=\(outlook.willRain) confidence=\(outlook.confidence) \(outlook.agreeingCount)/\(outlook.availableCount)")
        }
        let nonOpenMeteo: Set<SourceID> = [.metNowcast, .metNorway, .nws, .brightSky]
        XCTAssertEqual(succeeded.intersection(nonOpenMeteo), nonOpenMeteo)
    }

    func testGeocoderLive() async throws {
        let results = try await OpenMeteoGeocoder().search("Bergen")
        XCTAssertTrue(results.contains { $0.country == "Norway" })
    }
}
