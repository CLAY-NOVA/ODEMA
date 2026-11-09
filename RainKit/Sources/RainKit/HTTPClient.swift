import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Minimal HTTP abstraction so providers can be tested with recorded responses.
public protocol HTTPClient: Sendable {
    func get(_ url: URL, headers: [String: String]) async throws -> (data: Data, status: Int)
}

public enum ProviderError: Error, Equatable, Sendable, CustomStringConvertible {
    case notCovered
    /// Non-2xx status, with the API's own explanation when it gives one.
    case http(Int, String?)
    case invalidResponse(String)

    public var description: String {
        switch self {
        case .notCovered: return "Location not covered"
        case .http(429, let reason): return "Rate limited (HTTP 429)" + (reason.map { ": \($0)" } ?? "")
        case .http(let code, let reason): return "HTTP \(code)" + (reason.map { ": \($0)" } ?? "")
        case .invalidResponse(let why): return "Unexpected response: \(why)"
        }
    }
}

/// URLSession-backed client. MET Norway and the NWS require an identifying User-Agent.
public final class URLSessionHTTPClient: HTTPClient, @unchecked Sendable {
    private let session: URLSession
    private let userAgent: String

    public init(
        userAgent: String = "Odema/1.0 (rain alerts; github.com/CLAY-NOVA/ODEMA)",
        timeout: TimeInterval = 15
    ) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout * 2
        config.requestCachePolicy = .useProtocolCachePolicy
        session = URLSession(configuration: config)
        self.userAgent = userAgent
    }

    public func get(_ url: URL, headers: [String: String]) async throws -> (data: Data, status: Int) {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        return try await withCheckedThrowingContinuation { continuation in
            let task = session.dataTask(with: request) { data, response, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                continuation.resume(returning: (data ?? Data(), status))
            }
            task.resume()
        }
    }
}

extension HTTPClient {
    /// GET that throws on non-2xx; `notCoveredStatuses` map to `.notCovered`.
    func fetch(_ url: URL, headers: [String: String] = [:], notCoveredStatuses: Set<Int> = []) async throws -> Data {
        let (data, status) = try await get(url, headers: headers)
        if notCoveredStatuses.contains(status) { throw ProviderError.notCovered }
        guard (200..<300).contains(status) else { throw ProviderError.http(status, JSON.errorReason(data)) }
        return data
    }
}

enum JSON {
    /// Open-Meteo says `{"reason": ...}`, the NWS and Bright Sky say `{"detail": ...}`.
    static func errorReason(_ data: Data) -> String? {
        struct Body: Decodable {
            let reason: String?
            let detail: String?
        }
        guard let body = try? JSONDecoder().decode(Body.self, from: data) else { return nil }
        return (body.reason ?? body.detail).map { String($0.prefix(140)) }
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw ProviderError.invalidResponse(String(describing: error).prefix(160).description)
        }
    }

    /// Parses ISO 8601 timestamps with or without fractional seconds / offsets.
    static func date(_ string: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: string) { return d }
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: string)
    }
}

extension URL {
    static func make(_ base: String, _ query: [(String, String)]) -> URL {
        var components = URLComponents(string: base)!
        components.queryItems = query.map { URLQueryItem(name: $0.0, value: $0.1) }
        return components.url!
    }
}
