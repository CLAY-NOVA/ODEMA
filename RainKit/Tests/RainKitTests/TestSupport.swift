import Foundation
import XCTest
@testable import RainKit

enum Fixture {
    static func data(_ name: String) throws -> Data {
        let url = try XCTUnwrap(
            Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"),
            "missing fixture \(name).json"
        )
        return try Data(contentsOf: url)
    }
}

/// Serves canned responses; `route` decides per URL.
struct MockHTTPClient: HTTPClient {
    let route: @Sendable (URL) throws -> (Data, Int)

    func get(_ url: URL, headers: [String: String]) async throws -> (data: Data, status: Int) {
        let (data, status) = try route(url)
        return (data, status)
    }
}

func date(_ iso: String) -> Date {
    JSON.date(iso)!
}

func sample(_ start: String, minutes: Double = 60, mm: Double? = nil, pct: Double? = nil) -> PrecipitationSample {
    PrecipitationSample(start: date(start), duration: minutes * 60, intensity: mm, probability: pct)
}

var utcCalendar: Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "UTC")!
    return c
}
