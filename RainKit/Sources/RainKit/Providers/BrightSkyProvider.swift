import Foundation

/// Bright Sky — the German Weather Service's (DWD) MOSMIX station forecasts as JSON.
/// https://brightsky.dev/docs — free, no key. MOSMIX covers ~5,000 stations worldwide;
/// with no station within 50 km the API answers 404 and we report "not covered".
public struct BrightSkyProvider: WeatherProvider {
    public init() {}

    public var sources: [SourceID] { [.brightSky] }

    static func url(for point: GeoPoint, now: Date) -> URL {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        let from = Date(timeIntervalSince1970: (now.timeIntervalSince1970 / 3600).rounded(.down) * 3600)
        return .make("https://api.brightsky.dev/weather", [
            ("lat", point.lat4),
            ("lon", point.lon4),
            ("date", f.string(from: from)),
            ("last_date", f.string(from: from.addingTimeInterval(26 * 3600))),
            ("tz", "Etc/UTC"),
        ])
    }

    public func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome] {
        await outcomes(for: sources) {
            let data = try await client.fetch(Self.url(for: point, now: now), notCoveredStatuses: [404])
            let samples = try Self.parse(data)
            guard !samples.isEmpty else { throw ProviderError.notCovered }
            return [.brightSky: .success(clip(samples, now: now))]
        }
    }

    struct Response: Decodable {
        struct Record: Decodable {
            let timestamp: String
            let precipitation: Double?
            let precipitation_probability: Double?
        }
        let weather: [Record]
    }

    /// Bright Sky values describe the hour *preceding* each timestamp.
    static func parse(_ data: Data) throws -> [PrecipitationSample] {
        let response = try JSON.decode(Response.self, from: data)
        return response.weather.compactMap { record in
            guard let end = JSON.date(record.timestamp),
                  record.precipitation != nil || record.precipitation_probability != nil else { return nil }
            return PrecipitationSample(
                start: end.addingTimeInterval(-3600),
                duration: 3600,
                intensity: record.precipitation,
                probability: record.precipitation_probability
            )
        }
    }
}
