import Foundation

/// A public weather API. One provider may serve several forecast feeds.
public protocol WeatherProvider: Sendable {
    /// Feeds this provider can return.
    var sources: [SourceID] { get }
    /// Fetches the requested subset of `sources` for a point. Never throws: every
    /// requested source gets an outcome so one broken API can't sink the others.
    func fetch(_ point: GeoPoint, sources: Set<SourceID>, now: Date, client: HTTPClient) async -> [SourceID: SourceOutcome]
}

extension WeatherProvider {
    /// Runs `body` and maps thrown errors to an outcome for each requested source.
    func outcomes(
        for sources: Set<SourceID>,
        _ body: () async throws -> [SourceID: SourceOutcome]
    ) async -> [SourceID: SourceOutcome] {
        do {
            return try await body()
        } catch ProviderError.notCovered {
            return Dictionary(uniqueKeysWithValues: sources.map { ($0, .notCovered) })
        } catch let error as ProviderError {
            return Dictionary(uniqueKeysWithValues: sources.map { ($0, .failure(error.description)) })
        } catch {
            let message = (error as NSError).localizedDescription
            return Dictionary(uniqueKeysWithValues: sources.map { ($0, .failure(message)) })
        }
    }
}

/// Drops samples that end before `now - keepPast` or start after `now + horizon`.
func clip(_ samples: [PrecipitationSample], now: Date, horizon: TimeInterval = 30 * 3600) -> [PrecipitationSample] {
    let earliest = now.addingTimeInterval(-3600)
    let latest = now.addingTimeInterval(horizon)
    return samples
        .filter { $0.end > earliest && $0.start < latest }
        .sorted { $0.start < $1.start }
}
