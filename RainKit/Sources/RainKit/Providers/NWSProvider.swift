import Foundation

/// US National Weather Service — hourly probability of precipitation.
/// https://www.weather.gov/documentation/services-web-api — free, public domain.
/// US only: outside, `/points` answers 404 and we report "not covered".
public struct NWSProvider: WeatherProvider {
    public init() {}

    public var sources: [SourceID] { [.nws] }

    static let headers = ["Accept": "application/geo+json"]

    static func pointsURL(for point: GeoPoint) -> URL {
        URL(string: "https://api.weather.gov/points/\(point.lat4),\(point.lon4)")!
    }

    public func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome] {
        await outcomes(for: sources) {
            let pointsData = try await client.fetch(Self.pointsURL(for: point), headers: Self.headers, notCoveredStatuses: [404])
            let hourlyURL = try Self.parseHourlyURL(pointsData)
            let data = try await client.fetch(hourlyURL, headers: Self.headers)
            return [.nws: .success(clip(try Self.parse(data), now: now))]
        }
    }

    struct PointsResponse: Decodable {
        struct Properties: Decodable { let forecastHourly: String? }
        let properties: Properties
    }

    static func parseHourlyURL(_ data: Data) throws -> URL {
        let response = try JSON.decode(PointsResponse.self, from: data)
        guard let string = response.properties.forecastHourly, let url = URL(string: string) else {
            throw ProviderError.notCovered
        }
        return url
    }

    struct HourlyResponse: Decodable {
        struct Properties: Decodable { let periods: [Period] }
        struct Period: Decodable {
            let startTime: String
            let endTime: String
            let probabilityOfPrecipitation: Quantity?
        }
        struct Quantity: Decodable { let value: Double? }
        let properties: Properties
    }

    static func parse(_ data: Data) throws -> [PrecipitationSample] {
        let response = try JSON.decode(HourlyResponse.self, from: data)
        return response.properties.periods.compactMap { period in
            guard let start = JSON.date(period.startTime), let end = JSON.date(period.endTime), end > start else { return nil }
            // NWS leaves the value null when the chance is negligible.
            let probability = period.probabilityOfPrecipitation?.value ?? 0
            return PrecipitationSample(start: start, duration: end.timeIntervalSince(start), intensity: nil, probability: probability)
        }
    }
}
