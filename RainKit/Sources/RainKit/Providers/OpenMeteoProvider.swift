import Foundation

/// Open-Meteo "best match" forecast: blends the best local models, with
/// 15-minute precipitation for the next hours and hourly probability.
/// https://open-meteo.com/en/docs — free, no key, CC BY 4.0.
public struct OpenMeteoProvider: WeatherProvider {
    public init() {}

    public var sources: [SourceID] { [.openMeteo] }

    static func url(for point: GeoPoint) -> URL {
        .make("https://api.open-meteo.com/v1/forecast", [
            ("latitude", point.lat4),
            ("longitude", point.lon4),
            ("minutely_15", "precipitation"),
            ("hourly", "precipitation,precipitation_probability"),
            ("past_minutely_15", "1"),
            ("forecast_minutely_15", "32"),
            ("past_hours", "1"),
            ("forecast_hours", "26"),
            ("timeformat", "unixtime"),
            ("timezone", "GMT"),
        ])
    }

    public func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome] {
        await outcomes(for: sources) {
            let data = try await client.fetch(Self.url(for: point))
            return [.openMeteo: .success(clip(try Self.parse(data), now: now))]
        }
    }

    struct Response: Decodable {
        struct Series: Decodable {
            let time: [Double]
            let precipitation: [Double?]?
            let precipitation_probability: [Double?]?
        }
        let minutely_15: Series?
        let hourly: Series?
    }

    /// Open-Meteo values describe the period *preceding* each timestamp.
    static func parse(_ data: Data) throws -> [PrecipitationSample] {
        let response = try JSON.decode(Response.self, from: data)
        guard let hourly = response.hourly else { throw ProviderError.invalidResponse("no hourly data") }

        let hourlySamples: [PrecipitationSample] = hourly.time.indices.compactMap { i in
            let amount = hourly.precipitation?[safe: i] ?? nil
            let probability = hourly.precipitation_probability?[safe: i] ?? nil
            guard amount != nil || probability != nil else { return nil }
            return PrecipitationSample(
                start: Date(timeIntervalSince1970: hourly.time[i] - 3600),
                duration: 3600,
                intensity: amount,
                probability: probability
            )
        }

        var minuteSamples: [PrecipitationSample] = []
        if let minutely = response.minutely_15 {
            for i in minutely.time.indices {
                guard let amount = minutely.precipitation?[safe: i] ?? nil else { continue }
                let start = Date(timeIntervalSince1970: minutely.time[i] - 900)
                let probability = hourlySamples.first { $0.start <= start && start < $0.end }?.probability
                minuteSamples.append(PrecipitationSample(start: start, duration: 900, intensity: amount * 4, probability: probability))
            }
        }

        // 15-minute steps first, then hourly steps after the last 15-minute step.
        let fineEnd = minuteSamples.map(\.end).max() ?? .distantPast
        return minuteSamples + hourlySamples.filter { $0.start >= fineEnd }
    }
}

/// The individual national weather models, all via Open-Meteo in one request.
public struct OpenMeteoModelsProvider: WeatherProvider {
    public init() {}

    static let modelNames: [SourceID: String] = [
        .ecmwf: "ecmwf_ifs025",
        .gfs: "gfs_seamless",
        .icon: "icon_seamless",
        .meteoFrance: "meteofrance_seamless",
        .ukmo: "ukmo_seamless",
        .gem: "gem_seamless",
        .jma: "jma_seamless",
    ]

    public var sources: [SourceID] { [.ecmwf, .gfs, .icon, .meteoFrance, .ukmo, .gem, .jma] }

    static func url(for point: GeoPoint, models: [String]) -> URL {
        .make("https://api.open-meteo.com/v1/forecast", [
            ("latitude", point.lat4),
            ("longitude", point.lon4),
            ("hourly", "precipitation,precipitation_probability"),
            ("models", models.joined(separator: ",")),
            ("past_hours", "1"),
            ("forecast_hours", "26"),
            ("timeformat", "unixtime"),
            ("timezone", "GMT"),
        ])
    }

    public func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome] {
        let wanted = self.sources.filter(sources.contains)
        guard !wanted.isEmpty else { return [:] }
        return await outcomes(for: Set(wanted)) {
            let models = wanted.compactMap { Self.modelNames[$0] }
            let data = try await client.fetch(Self.url(for: point, models: models))
            return try Self.parse(data, sources: wanted).mapValues { outcome in
                if case .success(let samples) = outcome { return .success(clip(samples, now: now)) }
                return outcome
            }
        }
    }

    struct Response: Decodable {
        let hourly: [String: [Double?]]
    }

    static func parse(_ data: Data, sources: [SourceID]) throws -> [SourceID: SourceOutcome] {
        let response = try JSON.decode(Response.self, from: data)
        guard let times = response.hourly["time"]?.compactMap({ $0 }) else {
            throw ProviderError.invalidResponse("no time axis")
        }
        var result: [SourceID: SourceOutcome] = [:]
        for source in sources {
            guard let model = modelNames[source] else { continue }
            let amounts = response.hourly["precipitation_\(model)"] ?? []
            let probabilities = response.hourly["precipitation_probability_\(model)"] ?? []
            let samples: [PrecipitationSample] = times.indices.compactMap { i in
                let amount = amounts[safe: i] ?? nil
                let probability = probabilities[safe: i] ?? nil
                guard amount != nil || probability != nil else { return nil }
                return PrecipitationSample(
                    start: Date(timeIntervalSince1970: times[i] - 3600),
                    duration: 3600,
                    intensity: amount,
                    probability: probability
                )
            }
            // A model returns all-null values where it has no data for the point.
            result[source] = samples.isEmpty ? .notCovered : .success(samples)
        }
        return result
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
