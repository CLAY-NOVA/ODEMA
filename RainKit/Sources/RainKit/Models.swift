import Foundation

/// A latitude/longitude pair in decimal degrees (WGS84).
public struct GeoPoint: Codable, Hashable, Sendable {
    public var latitude: Double
    public var longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    /// Coordinates rounded to 4 decimals (~11 m), as MET Norway asks for.
    var lat4: String { String(format: "%.4f", latitude) }
    var lon4: String { String(format: "%.4f", longitude) }
}

/// A place the user wants rain alerts for.
public struct SavedLocation: Codable, Hashable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    /// Secondary line, e.g. "Île-de-France, France".
    public var detail: String?
    public var coordinate: GeoPoint
    /// When true the coordinate follows the device's location while the app is open.
    public var followsDevice: Bool
    public var alertsEnabled: Bool

    public init(
        id: UUID = UUID(),
        name: String,
        detail: String? = nil,
        coordinate: GeoPoint,
        followsDevice: Bool = false,
        alertsEnabled: Bool = true
    ) {
        self.id = id
        self.name = name
        self.detail = detail
        self.coordinate = coordinate
        self.followsDevice = followsDevice
        self.alertsEnabled = alertsEnabled
    }
}

/// One forecast step from one source.
///
/// `intensity` is precipitation in mm/h averaged over the step; `probability` is the
/// chance of measurable precipitation in percent (0–100). Either may be missing:
/// some sources only publish amounts, others only probabilities.
public struct PrecipitationSample: Codable, Hashable, Sendable {
    public var start: Date
    public var duration: TimeInterval
    public var intensity: Double?
    public var probability: Double?

    public init(start: Date, duration: TimeInterval, intensity: Double?, probability: Double?) {
        self.start = start
        self.duration = duration
        self.intensity = intensity
        self.probability = probability
    }

    public var end: Date { start.addingTimeInterval(duration) }
}

/// Every forecast feed the app can combine. Several feeds can come from one API
/// (Open-Meteo serves the national weather models individually).
public enum SourceID: String, Codable, CodingKeyRepresentable, CaseIterable, Hashable, Sendable, Identifiable {
    case metNowcast
    case openMeteo
    case metNorway
    case nws
    case brightSky
    case ecmwf
    case gfs
    case icon
    case meteoFrance
    case ukmo
    case gem
    case jma

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .metNowcast: return "MET Nowcast (radar)"
        case .openMeteo: return "Open-Meteo Best Match"
        case .metNorway: return "MET Norway"
        case .nws: return "US National Weather Service"
        case .brightSky: return "DWD MOSMIX (Bright Sky)"
        case .ecmwf: return "ECMWF IFS"
        case .gfs: return "NOAA GFS"
        case .icon: return "DWD ICON"
        case .meteoFrance: return "Météo-France ARPEGE/AROME"
        case .ukmo: return "UK Met Office"
        case .gem: return "Environment Canada GEM"
        case .jma: return "JMA (Japan)"
        }
    }

    /// Where the data comes from, for attribution.
    public var provider: String {
        switch self {
        case .metNowcast, .metNorway: return "api.met.no"
        case .nws: return "api.weather.gov"
        case .brightSky: return "api.brightsky.dev"
        default: return "api.open-meteo.com"
        }
    }

    public var coverage: String {
        switch self {
        case .metNowcast: return "Nordic countries, next 2 h, 5-minute steps"
        case .openMeteo: return "Worldwide, 15-minute steps"
        case .metNorway: return "Worldwide, hourly"
        case .nws: return "United States only, hourly"
        case .brightSky: return "Near ~5,000 weather stations worldwide, hourly"
        case .ecmwf: return "Worldwide, hourly"
        case .gfs: return "Worldwide, hourly"
        case .icon: return "Worldwide (high-res over Europe), hourly"
        case .meteoFrance: return "Worldwide (high-res over France), hourly"
        case .ukmo: return "Worldwide (high-res over UK), hourly"
        case .gem: return "Worldwide (high-res over Canada), hourly"
        case .jma: return "Worldwide (high-res over Japan), hourly"
        }
    }

    /// How much this source's vote counts in the consensus. Radar nowcasts and the
    /// high-resolution blend are better at "rain in the next hour" than global models.
    public var weight: Double {
        switch self {
        case .metNowcast: return 2.0
        case .openMeteo: return 1.5
        default: return 1.0
        }
    }
}

/// What happened when a source was asked for a forecast.
public enum SourceOutcome: Codable, Hashable, Sendable {
    case success([PrecipitationSample])
    /// The source doesn't cover this location (e.g. NWS outside the US).
    case notCovered
    case failure(String)

    public var samples: [PrecipitationSample]? {
        if case .success(let samples) = self { return samples }
        return nil
    }
}

/// All source outcomes for one location at one moment.
public struct ForecastReport: Codable, Hashable, Sendable {
    public var fetchedAt: Date
    public var outcomes: [SourceID: SourceOutcome]

    public init(fetchedAt: Date, outcomes: [SourceID: SourceOutcome]) {
        self.fetchedAt = fetchedAt
        self.outcomes = outcomes
    }
}

/// User-tunable alert behaviour.
public struct AlertSettings: Codable, Hashable, Sendable {
    /// How far ahead to look for rain.
    public var lookahead: TimeInterval
    /// A step counts as rainy at or above this intensity (mm/h)...
    public var minIntensity: Double
    /// ...or at or above this probability (%).
    public var minProbability: Double
    /// Weighted share of sources (0–1) that must predict rain before alerting.
    public var requiredAgreement: Double
    /// Minimum time between two alerts for the same location.
    public var cooldown: TimeInterval
    public var quietHoursEnabled: Bool
    /// Minutes after midnight, local time.
    public var quietStart: Int
    public var quietEnd: Int
    public var disabledSources: Set<SourceID>

    public init(
        lookahead: TimeInterval = 2 * 3600,
        minIntensity: Double = 0.2,
        minProbability: Double = 60,
        requiredAgreement: Double = 0.5,
        cooldown: TimeInterval = 3 * 3600,
        quietHoursEnabled: Bool = false,
        quietStart: Int = 22 * 60,
        quietEnd: Int = 7 * 60,
        disabledSources: Set<SourceID> = []
    ) {
        self.lookahead = lookahead
        self.minIntensity = minIntensity
        self.minProbability = minProbability
        self.requiredAgreement = requiredAgreement
        self.cooldown = cooldown
        self.quietHoursEnabled = quietHoursEnabled
        self.quietStart = quietStart
        self.quietEnd = quietEnd
        self.disabledSources = disabledSources
    }

    public static let `default` = AlertSettings()

    public var enabledSources: Set<SourceID> {
        Set(SourceID.allCases).subtracting(disabledSources)
    }

    public init(from decoder: Decoder) throws {
        // Decode leniently so adding settings in later versions never wipes saved ones.
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AlertSettings.default
        lookahead = try c.decodeIfPresent(TimeInterval.self, forKey: .lookahead) ?? d.lookahead
        minIntensity = try c.decodeIfPresent(Double.self, forKey: .minIntensity) ?? d.minIntensity
        minProbability = try c.decodeIfPresent(Double.self, forKey: .minProbability) ?? d.minProbability
        requiredAgreement = try c.decodeIfPresent(Double.self, forKey: .requiredAgreement) ?? d.requiredAgreement
        cooldown = try c.decodeIfPresent(TimeInterval.self, forKey: .cooldown) ?? d.cooldown
        quietHoursEnabled = try c.decodeIfPresent(Bool.self, forKey: .quietHoursEnabled) ?? d.quietHoursEnabled
        quietStart = try c.decodeIfPresent(Int.self, forKey: .quietStart) ?? d.quietStart
        quietEnd = try c.decodeIfPresent(Int.self, forKey: .quietEnd) ?? d.quietEnd
        let raw = try c.decodeIfPresent([String].self, forKey: .disabledSources) ?? []
        disabledSources = Set(raw.compactMap(SourceID.init(rawValue:)))
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(lookahead, forKey: .lookahead)
        try c.encode(minIntensity, forKey: .minIntensity)
        try c.encode(minProbability, forKey: .minProbability)
        try c.encode(requiredAgreement, forKey: .requiredAgreement)
        try c.encode(cooldown, forKey: .cooldown)
        try c.encode(quietHoursEnabled, forKey: .quietHoursEnabled)
        try c.encode(quietStart, forKey: .quietStart)
        try c.encode(quietEnd, forKey: .quietEnd)
        try c.encode(disabledSources.map(\.rawValue).sorted(), forKey: .disabledSources)
    }

    private enum CodingKeys: String, CodingKey {
        case lookahead, minIntensity, minProbability, requiredAgreement, cooldown
        case quietHoursEnabled, quietStart, quietEnd, disabledSources
    }
}
