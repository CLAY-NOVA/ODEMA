import Foundation
import XCTest
@testable import RainKit

final class ServiceTests: XCTestCase {
    /// Routes requests to the recorded fixtures the way the real APIs would answer for Paris.
    static let parisClient = MockHTTPClient { url in
        let host = url.host ?? ""
        switch host {
        case "api.open-meteo.com":
            return (try Fixture.data(url.query?.contains("models=") == true ? "openmeteo_models" : "openmeteo"), 200)
        case "api.met.no" where url.path.contains("nowcast"):
            return (Data("The location is outside the geographic area supported by the product.".utf8), 422)
        case "api.met.no":
            return (try Fixture.data("metnorway"), 200)
        case "api.weather.gov":
            return (Data(#"{"status":404,"detail":"Unable to provide data for requested point"}"#.utf8), 404)
        case "api.brightsky.dev":
            return (try Fixture.data("brightsky"), 200)
        default:
            throw URLError(.cannotFindHost)
        }
    }

    let paris = GeoPoint(latitude: 48.8534, longitude: 2.3488)
    let now = Date(timeIntervalSince1970: 1794246360) // 2026-11-09T17:46:00Z

    func testReportCollectsEverySource() async {
        let service = ForecastService(client: Self.parisClient)
        let report = await service.report(for: paris, sources: Set(SourceID.allCases), now: now)
        XCTAssertEqual(Set(report.outcomes.keys), Set(SourceID.allCases))
        XCTAssertEqual(report.outcomes[.metNowcast], .notCovered)
        XCTAssertEqual(report.outcomes[.nws], .notCovered)
        for source in SourceID.allCases where ![.metNowcast, .nws].contains(source) {
            XCTAssertNotNil(report.outcomes[source]?.samples, "\(source) should succeed")
        }
    }

    func testOnlyRequestedSourcesAreFetched() async {
        let service = ForecastService(client: Self.parisClient)
        let report = await service.report(for: paris, sources: [.ecmwf, .metNorway], now: now)
        XCTAssertEqual(Set(report.outcomes.keys), [.ecmwf, .metNorway])
    }

    func testNetworkErrorsBecomeFailures() async {
        let service = ForecastService(client: MockHTTPClient { _ in throw URLError(.notConnectedToInternet) })
        let report = await service.report(for: paris, sources: [.openMeteo, .nws], now: now)
        for outcome in report.outcomes.values {
            guard case .failure = outcome else { return XCTFail("expected failure, got \(outcome)") }
        }
    }

    func testOutlookFromRecordedParisForecast() async {
        let service = ForecastService(client: Self.parisClient)
        let report = await service.report(for: paris, sources: Set(SourceID.allCases), now: now)
        let outlook = RainAnalyzer.outlook(report: report, settings: AlertSettings(lookahead: 2 * 3600), now: now)

        let openMeteo = outlook.verdicts.first { $0.source == .openMeteo }
        XCTAssertEqual(openMeteo?.predictsRain, true)
        XCTAssertEqual(openMeteo?.firstRain, date("2026-11-09T19:00:00Z"))
        let ecmwf = outlook.verdicts.first { $0.source == .ecmwf }
        XCTAssertEqual(ecmwf?.firstRain, date("2026-11-09T18:00:00Z"))
        XCTAssertFalse(outlook.verdicts.contains { $0.source == .nws }, "NWS doesn't cover Paris")

        // Cached reports must survive a round trip through JSON (the app persists them).
        let decoded = try? JSONDecoder().decode(ForecastReport.self, from: JSONEncoder().encode(report))
        XCTAssertEqual(decoded, report)
    }
}
