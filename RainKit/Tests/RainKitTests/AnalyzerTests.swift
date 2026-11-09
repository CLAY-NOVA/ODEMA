import Foundation
import XCTest
@testable import RainKit

final class AnalyzerTests: XCTestCase {
    let now = date("2026-11-09T12:00:00Z")
    var settings = AlertSettings(lookahead: 2 * 3600, minIntensity: 0.2, minProbability: 60, requiredAgreement: 0.5)

    func report(_ outcomes: [SourceID: SourceOutcome]) -> ForecastReport {
        ForecastReport(fetchedAt: now, outcomes: outcomes)
    }

    func testRainyByIntensityOrProbability() {
        XCTAssertTrue(RainAnalyzer.isRainy(sample("2026-11-09T12:00:00Z", mm: 0.2), settings: settings))
        XCTAssertFalse(RainAnalyzer.isRainy(sample("2026-11-09T12:00:00Z", mm: 0.1), settings: settings))
        XCTAssertTrue(RainAnalyzer.isRainy(sample("2026-11-09T12:00:00Z", pct: 60), settings: settings))
        XCTAssertTrue(RainAnalyzer.isRainy(sample("2026-11-09T12:00:00Z", mm: 0, pct: 80), settings: settings))
        XCTAssertFalse(RainAnalyzer.isRainy(sample("2026-11-09T12:00:00Z", mm: 0, pct: 30), settings: settings))
    }

    func testAllDryMeansNoRain() {
        let outlook = RainAnalyzer.outlook(report: report([
            .gfs: .success([sample("2026-11-09T12:00:00Z", mm: 0), sample("2026-11-09T13:00:00Z", mm: 0)]),
            .icon: .success([sample("2026-11-09T12:00:00Z", mm: 0.1, pct: 20)]),
        ]), settings: settings, now: now)
        XCTAssertFalse(outlook.willRain)
        XCTAssertEqual(outlook.confidence, 0)
        XCTAssertEqual(outlook.availableCount, 2)
        XCTAssertNil(outlook.expectedStart)
    }

    func testMajorityAgreementTriggersRain() {
        let outlook = RainAnalyzer.outlook(report: report([
            .gfs: .success([sample("2026-11-09T12:00:00Z", mm: 0), sample("2026-11-09T13:00:00Z", mm: 1.5)]),
            .icon: .success([sample("2026-11-09T12:00:00Z", mm: 0), sample("2026-11-09T13:00:00Z", mm: 2.0)]),
            .ukmo: .success([sample("2026-11-09T12:00:00Z", mm: 0), sample("2026-11-09T13:00:00Z", mm: 0)]),
            .nws: .notCovered,
            .jma: .failure("HTTP 500"),
        ]), settings: settings, now: now)
        XCTAssertTrue(outlook.willRain)
        XCTAssertEqual(outlook.agreeingCount, 2)
        XCTAssertEqual(outlook.availableCount, 3, "not-covered and failed sources don't vote")
        XCTAssertEqual(outlook.confidence, 2.0 / 3.0, accuracy: 1e-9)
        XCTAssertEqual(outlook.expectedStart, date("2026-11-09T13:00:00Z"))
        XCTAssertEqual(outlook.peakIntensity ?? 0, 1.75, accuracy: 1e-9)
        XCTAssertEqual(outlook.verdicts.first?.predictsRain, true, "rainy sources are listed first")
    }

    func testRequiredAgreementAll() {
        settings.requiredAgreement = 1.0
        let outlook = RainAnalyzer.outlook(report: report([
            .gfs: .success([sample("2026-11-09T12:00:00Z", minutes: 120, mm: 1)]),
            .icon: .success([sample("2026-11-09T12:00:00Z", minutes: 120, mm: 0)]),
        ]), settings: settings, now: now)
        XCTAssertFalse(outlook.willRain)
        XCTAssertEqual(outlook.confidence, 0.5, accuracy: 1e-9)
    }

    func testDisabledSourcesAreIgnored() {
        settings.disabledSources = [.gfs]
        let outlook = RainAnalyzer.outlook(report: report([
            .gfs: .success([sample("2026-11-09T12:00:00Z", minutes: 120, mm: 5)]),
            .icon: .success([sample("2026-11-09T12:00:00Z", minutes: 120, mm: 0)]),
        ]), settings: settings, now: now)
        XCTAssertFalse(outlook.willRain)
        XCTAssertEqual(outlook.verdicts.map(\.source), [.icon])
    }

    func testDryShortRangeSourceOnlyVouchesForItsCoverage() {
        settings.lookahead = 6 * 3600
        // Nowcast (weight 2) is dry but only covers 1.5 h of a 6 h window: it counts as 2 × 0.25 = 0.5.
        let nowcast = (0..<18).map { i in
            PrecipitationSample(start: now.addingTimeInterval(Double(i) * 300), duration: 300, intensity: 0, probability: nil)
        }
        let outlook = RainAnalyzer.outlook(report: report([
            .metNowcast: .success(nowcast),
            .gfs: .success([sample("2026-11-09T16:00:00Z", mm: 2)] + [sample("2026-11-09T12:00:00Z", minutes: 240, mm: 0)]),
        ]), settings: settings, now: now)
        let verdict = outlook.verdicts.first { $0.source == .metNowcast }
        XCTAssertEqual(verdict?.coverage ?? 0, 0.25, accuracy: 1e-9)
        XCTAssertEqual(outlook.confidence, 1.0 / 1.5, accuracy: 1e-9)
        XCTAssertTrue(outlook.willRain)
    }

    func testRainAlreadyFallingIsClampedToNow() {
        let outlook = RainAnalyzer.outlook(report: report([
            .openMeteo: .success([sample("2026-11-09T11:45:00Z", minutes: 15, mm: 0.1), sample("2026-11-09T11:30:00Z", minutes: 15, mm: 0)].reversed() + [sample("2026-11-09T11:45:00Z", minutes: 30, mm: 1.2)]),
        ]), settings: settings, now: now)
        XCTAssertEqual(outlook.expectedStart, now)
        XCTAssertTrue(outlook.isRainingNow(now))
    }

    func testWeightedMedianStartFavoursHeavierSources() {
        let outlook = RainAnalyzer.outlook(report: report([
            .metNowcast: .success([sample("2026-11-09T12:20:00Z", minutes: 5, mm: 1)]),
            .gfs: .success([sample("2026-11-09T13:00:00Z", mm: 1)]),
        ]), settings: settings, now: now)
        // Weights 2 vs 1: the radar nowcast's 12:20 wins.
        XCTAssertEqual(outlook.expectedStart, date("2026-11-09T12:20:00Z"))
    }

    func testCoveredFractionHandlesGapsAndOverlap() {
        let window = DateInterval(start: now, duration: 4 * 3600)
        let samples = [
            sample("2026-11-09T11:00:00Z", minutes: 120),  // 1 h inside
            sample("2026-11-09T12:30:00Z", minutes: 60),   // overlaps, adds 30 min
            sample("2026-11-09T15:00:00Z", minutes: 120),  // 1 h inside
        ]
        XCTAssertEqual(RainAnalyzer.coveredFraction(of: window, by: samples), 2.5 / 4, accuracy: 1e-9)
    }

    func testTimeline() {
        let slots = RainAnalyzer.timeline(report: report([
            .gfs: .success([sample("2026-11-09T12:00:00Z", mm: 0), sample("2026-11-09T13:00:00Z", mm: 3)]),
            .icon: .success([sample("2026-11-09T12:00:00Z", mm: 0), sample("2026-11-09T13:00:00Z", mm: 0)]),
        ]), settings: settings, now: now.addingTimeInterval(600), hours: 3, step: 1800)
        XCTAssertEqual(slots.count, 6)
        XCTAssertEqual(slots[0].start, now, "slots start on a step boundary")
        XCTAssertEqual(slots[0].chance, 0)
        XCTAssertEqual(slots[2].chance, 0.5, accuracy: 1e-9)
        XCTAssertEqual(slots[2].intensity ?? 0, 1.5, accuracy: 1e-9)
        XCTAssertEqual(slots[5].sourceCount, 0)
    }

    func testIntensityWording() {
        XCTAssertEqual(RainIntensity(mmPerHour: 0.3), .veryLight)
        XCTAssertEqual(RainIntensity(mmPerHour: 1), .light)
        XCTAssertEqual(RainIntensity(mmPerHour: 4), .moderate)
        XCTAssertEqual(RainIntensity(mmPerHour: 12), .heavy)
        XCTAssertEqual(RainIntensity(mmPerHour: 80), .violent)
    }
}
