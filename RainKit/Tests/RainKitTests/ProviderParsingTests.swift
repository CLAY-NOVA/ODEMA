import Foundation
import XCTest
@testable import RainKit

/// Parses responses recorded from the real APIs on 2026-11-09.
final class ProviderParsingTests: XCTestCase {
    func testOpenMeteoUsesFifteenMinuteStepsThenHourly() throws {
        let samples = try OpenMeteoProvider.parse(Fixture.data("openmeteo"))
        // Values describe the *preceding* period: the first 15-min stamp is 17:45Z.
        XCTAssertEqual(samples.first?.start, date("2026-11-09T17:30:00Z"))
        XCTAssertEqual(samples.first?.duration, 900)
        // 0.1 mm in 15 minutes = 0.4 mm/h, from the step ending 19:15Z.
        let firstWet = try XCTUnwrap(samples.first { ($0.intensity ?? 0) > 0 })
        XCTAssertEqual(firstWet.start, date("2026-11-09T19:00:00Z"))
        XCTAssertEqual(firstWet.intensity ?? 0, 0.4, accuracy: 1e-9)
        // Hourly probability is attached to the 15-minute steps inside that hour.
        XCTAssertEqual(firstWet.probability, 35)
        // After the 15-minute range ends, hourly steps continue without overlap.
        let hourly = samples.filter { $0.duration == 3600 }
        XCTAssertFalse(hourly.isEmpty)
        let fineEnd = samples.filter { $0.duration == 900 }.map(\.end).max()!
        XCTAssertTrue(hourly.allSatisfy { $0.start >= fineEnd })
    }

    func testOpenMeteoModelsSplitsPerModel() throws {
        let all = OpenMeteoModelsProvider().sources
        let outcomes = try OpenMeteoModelsProvider.parse(Fixture.data("openmeteo_models"), sources: all)
        XCTAssertEqual(Set(outcomes.keys), Set(all))
        let ecmwf = try XCTUnwrap(outcomes[.ecmwf]?.samples)
        XCTAssertEqual(ecmwf.count, 4)
        XCTAssertEqual(ecmwf[0].start, date("2026-11-09T16:00:00Z"))
        XCTAssertEqual(ecmwf[2].intensity, 0.7)
        XCTAssertEqual(ecmwf[3].probability, 72)
        // Météo-France publishes no probability; amounts alone are kept.
        let meteoFrance = try XCTUnwrap(outcomes[.meteoFrance]?.samples)
        XCTAssertTrue(meteoFrance.allSatisfy { $0.probability == nil && $0.intensity != nil })
    }

    func testOpenMeteoModelWithOnlyNullsIsNotCovered() throws {
        let json = #"{"hourly":{"time":[1,2],"precipitation_gfs_seamless":[null,null],"precipitation_probability_gfs_seamless":[null,null]}}"#
        let outcomes = try OpenMeteoModelsProvider.parse(Data(json.utf8), sources: [.gfs])
        XCTAssertEqual(outcomes[.gfs], .notCovered)
    }

    func testMETNorwayHourlyThenSixHourlyWithoutOverlap() throws {
        let samples = try METNorwayProvider.parse(Fixture.data("metnorway"))
        XCTAssertEqual(samples.first?.start, date("2026-11-09T17:00:00Z"))
        XCTAssertEqual(samples.first?.duration, 3600)
        XCTAssertTrue(samples.allSatisfy { $0.duration == 3600 || $0.duration == 6 * 3600 })
        for (a, b) in zip(samples, samples.dropFirst()) {
            XCTAssertLessThanOrEqual(a.end, b.start)
        }
        XCTAssertTrue(samples.contains { ($0.intensity ?? 0) >= 0.5 }, "fixture has rain this evening")
    }

    func testMETNowcastFiveMinuteRates() throws {
        let samples = try METNowcastProvider.parse(Fixture.data("nowcast_oslo"))
        XCTAssertEqual(samples.count, 23)
        XCTAssertEqual(samples.first?.start, date("2026-11-09T17:55:00Z"))
        XCTAssertTrue(samples.allSatisfy { $0.duration == 300 && $0.intensity != nil && $0.probability == nil })
    }

    func testMETNowcastWithoutRadarIsNotCovered() {
        let json = #"{"properties":{"meta":{"radar_coverage":"no coverage"},"timeseries":[]}}"#
        XCTAssertThrowsError(try METNowcastProvider.parse(Data(json.utf8))) { error in
            XCTAssertEqual(error as? ProviderError, .notCovered)
        }
    }

    func testNWSPointsAndHourly() throws {
        let hourlyURL = try NWSProvider.parseHourlyURL(Fixture.data("nws_points"))
        XCTAssertEqual(hourlyURL.absoluteString, "https://api.weather.gov/gridpoints/OKX/33,42/forecast/hourly")
        let samples = try NWSProvider.parse(Fixture.data("nws_hourly"))
        XCTAssertEqual(samples.count, 30)
        XCTAssertEqual(samples.first?.start, date("2026-11-09T17:00:00Z"))
        XCTAssertEqual(samples.first?.duration, 3600)
        XCTAssertEqual(samples.first?.probability, 10)
        XCTAssertNil(samples.first?.intensity)
    }

    func testBrightSkyValuesDescribePrecedingHour() throws {
        let samples = try BrightSkyProvider.parse(Fixture.data("brightsky"))
        XCTAssertEqual(samples.count, 27)
        XCTAssertEqual(samples.first?.start, date("2026-11-09T16:00:00Z"))
        XCTAssertEqual(samples[13].intensity, 0.2)
        XCTAssertEqual(samples[13].end, date("2026-11-10T06:00:00Z"))
    }

    func testGeocoding() throws {
        let results = try OpenMeteoGeocoder.parse(Fixture.data("geocoding"))
        XCTAssertEqual(results.first?.name, "Paris")
        XCTAssertEqual(results.first?.detail, "Île-de-France Region, France")
        XCTAssertEqual(results.first?.coordinate.latitude ?? 0, 48.85341, accuracy: 1e-6)
        XCTAssertEqual(try OpenMeteoGeocoder.parse(Fixture.data("geocoding_empty")), [])
    }

    func testRateLimitReasonIsSurfaced() async throws {
        let client = MockHTTPClient { _ in (try Fixture.data("openmeteo_429"), 429) }
        let outcomes = await OpenMeteoProvider().fetch(GeoPoint(latitude: 1, longitude: 2), sources: [.openMeteo], now: Date(), client: client)
        guard case .failure(let message) = outcomes[.openMeteo] else { return XCTFail("expected failure") }
        XCTAssertTrue(message.contains("Rate limited"), message)
        XCTAssertTrue(message.contains("Daily API request limit exceeded"), message)
    }

    func testURLsRoundToFourDecimals() {
        let point = GeoPoint(latitude: 59.912345678, longitude: 10.751234567)
        XCTAssertEqual(
            METNorwayProvider.url(for: point).absoluteString,
            "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=59.9123&lon=10.7512"
        )
        XCTAssertEqual(NWSProvider.pointsURL(for: point).absoluteString, "https://api.weather.gov/points/59.9123,10.7512")
    }
}
