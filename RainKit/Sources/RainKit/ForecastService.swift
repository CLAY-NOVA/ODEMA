import Foundation

/// Asks every enabled public weather API at once and collects the answers.
public struct ForecastService: Sendable {
    public static let defaultProviders: [any WeatherProvider] = [
        METNowcastProvider(),
        OpenMeteoProvider(),
        OpenMeteoModelsProvider(),
        METNorwayProvider(),
        NWSProvider(),
        BrightSkyProvider(),
    ]

    public let client: any HTTPClient
    public let providers: [any WeatherProvider]

    public init(client: any HTTPClient = URLSessionHTTPClient(), providers: [any WeatherProvider] = ForecastService.defaultProviders) {
        self.client = client
        self.providers = providers
    }

    public func report(for point: GeoPoint, sources: Set<SourceID>, now: Date = Date()) async -> ForecastReport {
        let client = self.client
        let outcomes = await withTaskGroup(of: [SourceID: SourceOutcome].self) { group in
            for provider in providers {
                let wanted = Set(provider.sources).intersection(sources)
                guard !wanted.isEmpty else { continue }
                group.addTask { await provider.fetch(point, sources: wanted, now: now, client: client) }
            }
            var merged: [SourceID: SourceOutcome] = [:]
            for await partial in group {
                merged.merge(partial) { first, _ in first }
            }
            return merged
        }
        return ForecastReport(fetchedAt: now, outcomes: outcomes)
    }
}

/// A place-name search result.
public struct GeocodingResult: Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var region: String?
    public var country: String?
    public var coordinate: GeoPoint

    public var detail: String? {
        let parts = [region, country].compactMap { $0 }.filter { !$0.isEmpty && $0 != name }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }
}

/// Open-Meteo geocoding (GeoNames data) — free, no key.
public struct OpenMeteoGeocoder: Sendable {
    public let client: any HTTPClient

    public init(client: any HTTPClient = URLSessionHTTPClient()) {
        self.client = client
    }

    static func url(query: String, language: String) -> URL {
        .make("https://geocoding-api.open-meteo.com/v1/search", [
            ("name", query),
            ("count", "15"),
            ("language", language),
            ("format", "json"),
        ])
    }

    public func search(_ query: String, language: String = "en") async throws -> [GeocodingResult] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return [] }
        let data = try await client.fetch(Self.url(query: trimmed, language: language))
        return try Self.parse(data)
    }

    struct Response: Decodable {
        struct Place: Decodable {
            let id: Int
            let name: String
            let latitude: Double
            let longitude: Double
            let admin1: String?
            let country: String?
        }
        let results: [Place]?
    }

    static func parse(_ data: Data) throws -> [GeocodingResult] {
        let response = try JSON.decode(Response.self, from: data)
        return (response.results ?? []).map {
            GeocodingResult(
                id: $0.id,
                name: $0.name,
                region: $0.admin1,
                country: $0.country,
                coordinate: GeoPoint(latitude: $0.latitude, longitude: $0.longitude)
            )
        }
    }
}
