import Foundation

/// MET Norway Locationforecast 2.0 — worldwide, hourly for ~2.5 days.
/// https://api.met.no/weatherapi/locationforecast/2.0/documentation — free, CC BY 4.0.
/// Requires an identifying User-Agent (set by `URLSessionHTTPClient`).
public struct METNorwayProvider: WeatherProvider {
    public init() {}

    public var sources: [SourceID] { [.metNorway] }

    static func url(for point: GeoPoint) -> URL {
        .make("https://api.met.no/weatherapi/locationforecast/2.0/complete", [
            ("lat", point.lat4),
            ("lon", point.lon4),
        ])
    }

    public func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome] {
        await outcomes(for: sources) {
            let data = try await client.fetch(Self.url(for: point), notCoveredStatuses: [422])
            return [.metNorway: .success(clip(try Self.parse(data), now: now))]
        }
    }

    struct Response: Decodable {
        struct Properties: Decodable { let timeseries: [Entry] }
        struct Entry: Decodable {
            let time: String
            let data: Block
        }
        struct Block: Decodable {
            let next_1_hours: Period?
            let next_6_hours: Period?
        }
        struct Period: Decodable { let details: Details? }
        struct Details: Decodable {
            let precipitation_amount: Double?
            let probability_of_precipitation: Double?
        }
        let properties: Properties
    }

    /// `next_N_hours` describes the period *after* each timestamp.
    static func parse(_ data: Data) throws -> [PrecipitationSample] {
        let response = try JSON.decode(Response.self, from: data)
        var samples: [PrecipitationSample] = []
        var coveredUntil = Date.distantPast
        for entry in response.properties.timeseries {
            guard let start = JSON.date(entry.time), start >= coveredUntil else { continue }
            if let details = entry.data.next_1_hours?.details, details.precipitation_amount != nil || details.probability_of_precipitation != nil {
                samples.append(PrecipitationSample(
                    start: start, duration: 3600,
                    intensity: details.precipitation_amount,
                    probability: details.probability_of_precipitation
                ))
                coveredUntil = start.addingTimeInterval(3600)
            } else if let details = entry.data.next_6_hours?.details, let amount = details.precipitation_amount {
                samples.append(PrecipitationSample(
                    start: start, duration: 6 * 3600,
                    intensity: amount / 6,
                    probability: details.probability_of_precipitation
                ))
                coveredUntil = start.addingTimeInterval(6 * 3600)
            }
        }
        return samples
    }
}

/// MET Norway Nowcast 2.0 — radar-based, 5-minute steps for the next 2 hours.
/// Nordic countries only; elsewhere the API answers 422 and we report "not covered".
public struct METNowcastProvider: WeatherProvider {
    public init() {}

    public var sources: [SourceID] { [.metNowcast] }

    static func url(for point: GeoPoint) -> URL {
        .make("https://api.met.no/weatherapi/nowcast/2.0/complete", [
            ("lat", point.lat4),
            ("lon", point.lon4),
        ])
    }

    public func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome] {
        await outcomes(for: sources) {
            let data = try await client.fetch(Self.url(for: point), notCoveredStatuses: [422])
            return [.metNowcast: .success(clip(try Self.parse(data), now: now))]
        }
    }

    struct Response: Decodable {
        struct Properties: Decodable {
            let meta: Meta?
            let timeseries: [Entry]
        }
        struct Meta: Decodable { let radar_coverage: String? }
        struct Entry: Decodable {
            let time: String
            let data: Block
        }
        struct Block: Decodable { let instant: Instant }
        struct Instant: Decodable { let details: Details? }
        struct Details: Decodable { let precipitation_rate: Double? }
        let properties: Properties
    }

    static func parse(_ data: Data) throws -> [PrecipitationSample] {
        let response = try JSON.decode(Response.self, from: data)
        switch response.properties.meta?.radar_coverage {
        case "no coverage": throw ProviderError.notCovered
        case "temporarily unavailable": throw ProviderError.invalidResponse("radar temporarily unavailable")
        default: break
        }
        return response.properties.timeseries.compactMap { entry in
            guard let start = JSON.date(entry.time), let rate = entry.data.instant.details?.precipitation_rate else { return nil }
            return PrecipitationSample(start: start, duration: 300, intensity: rate, probability: nil)
        }
    }
}
