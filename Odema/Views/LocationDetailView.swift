import Charts
import RainKit
import SwiftUI

/// One place: consensus headline, next-12-hours chart, and what each source says.
struct LocationDetailView: View {
    @EnvironmentObject private var model: AppModel
    let locationID: UUID

    var body: some View {
        if let location = model.location(locationID) {
            content(location)
        } else {
            ContentUnavailableView("Place removed", systemImage: "mappin.slash")
        }
    }

    @ViewBuilder
    private func content(_ location: SavedLocation) -> some View {
        let now = Date()
        let outlook = model.outlook(for: location.id, now: now)
        let report = model.reports[location.id]
        List {
            Section {
                Header(location: location, outlook: outlook, now: now)
            }

            Section {
                RainChart(
                    slots: model.timeline(for: location.id, now: now),
                    threshold: model.settings.requiredAgreement,
                    windowEnd: now.addingTimeInterval(model.settings.lookahead)
                )
                .frame(height: 190)
                .padding(.vertical, 6)
            } header: {
                Text("Next 12 hours")
            } footer: {
                Text("Bars show the share of sources predicting rain. You get an alert when it crosses the dashed line inside the alert window.")
            }

            Section("What each source says") {
                ForEach(SourceID.allCases.filter(model.settings.enabledSources.contains)) { source in
                    SourceRow(
                        source: source,
                        outcome: report?.outcomes[source],
                        verdict: outlook?.verdicts.first { $0.source == source }
                    )
                }
            }

            Section {
                Toggle("Rain alerts", isOn: Binding(
                    get: { location.alertsEnabled },
                    set: { model.setAlerts($0, for: location.id) }
                ))
                Button {
                    Task { await model.refresh([location], reason: .manual) }
                } label: {
                    Label("Check now", systemImage: "arrow.clockwise")
                }
                .disabled(model.refreshing.contains(location.id))
                Button(role: .destructive) {
                    model.remove(location.id)
                } label: {
                    Label("Remove place", systemImage: "trash")
                }
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    if let report {
                        Text("Forecasts fetched \(report.fetchedAt, style: .relative) ago.")
                    }
                    Text(String(format: "%.4f, %.4f", location.coordinate.latitude, location.coordinate.longitude))
                }
            }
        }
        .navigationTitle(location.name)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await model.refresh([location], reason: .manual) }
    }
}

private struct Header: View {
    @EnvironmentObject private var model: AppModel
    let location: SavedLocation
    let outlook: RainOutlook?
    let now: Date

    var body: some View {
        let status = RowStatus(model: model, location: location, now: now)
        VStack(spacing: 10) {
            Image(systemName: status.symbol)
                .symbolRenderingMode(.multicolor)
                .font(.system(size: 54))
            Text(status.headline)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            if let outlook, outlook.availableCount > 0 {
                Text(detail(outlook))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            if let detail = location.detail {
                Text(detail).font(.caption).foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    private func detail(_ outlook: RainOutlook) -> String {
        var parts = ["\(outlook.agreeingCount) of \(outlook.availableCount) sources see rain in the next \(Format.window(model.settings.lookahead))"]
        if outlook.agreeingCount > 0, let peak = outlook.peakIntensity {
            parts.append("up to \(Format.intensity(peak))")
        }
        return parts.joined(separator: " · ")
    }
}

private struct RainChart: View {
    let slots: [TimelineSlot]
    let threshold: Double
    let windowEnd: Date

    var body: some View {
        if slots.isEmpty || slots.allSatisfy({ $0.sourceCount == 0 }) {
            ContentUnavailableView("No forecast data yet", systemImage: "chart.bar")
        } else {
            Chart {
                ForEach(slots) { slot in
                    BarMark(
                        xStart: .value("Start", slot.start),
                        xEnd: .value("End", slot.end),
                        y: .value("Chance", slot.chance * 100)
                    )
                    .foregroundStyle(color(for: slot))
                    .cornerRadius(2)
                }
                RuleMark(y: .value("Alert threshold", threshold * 100))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .foregroundStyle(.secondary)
                RuleMark(x: .value("Alert window", windowEnd))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 3]))
                    .foregroundStyle(.orange)
                    .annotation(position: .top, alignment: .trailing) {
                        Text("alert window").font(.caption2).foregroundStyle(.orange)
                    }
            }
            .chartYScale(domain: 0.0...100.0)
            .chartYAxis {
                AxisMarks(values: [0.0, 50.0, 100.0]) { value in
                    AxisGridLine()
                    AxisValueLabel {
                        if let v = value.as(Double.self) { Text("\(Int(v))%") }
                    }
                }
            }
            .chartXAxis {
                AxisMarks(values: .stride(by: .hour, count: 2)) { _ in
                    AxisGridLine()
                    AxisValueLabel(format: .dateTime.hour())
                }
            }
        }
    }

    private func color(for slot: TimelineSlot) -> Color {
        let intensity = slot.intensity ?? 0
        switch intensity {
        case 7.6...: return .purple
        case 2.5...: return .indigo
        default: return .blue.opacity(slot.chance >= threshold ? 1 : 0.55)
        }
    }
}

private struct SourceRow: View {
    let source: SourceID
    let outcome: SourceOutcome?
    let verdict: SourceVerdict?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(source.displayName)
                Text(source.coverage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            status
                .font(.subheadline)
                .multilineTextAlignment(.trailing)
        }
    }

    @ViewBuilder
    private var status: some View {
        switch outcome {
        case nil:
            Text("—").foregroundStyle(.secondary)
        case .notCovered?:
            Text("Not available here").foregroundStyle(.tertiary)
        case .failure(let message)?:
            VStack(alignment: .trailing) {
                Label("Error", systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                Text(message).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            }
        case .success?:
            if let verdict {
                if verdict.predictsRain, let first = verdict.firstRain {
                    VStack(alignment: .trailing) {
                        Text("Rain \(Format.time(first))").foregroundStyle(.blue).bold()
                        Text(numbers(verdict)).font(.caption2).foregroundStyle(.secondary)
                    }
                } else {
                    VStack(alignment: .trailing) {
                        Text("Dry").foregroundStyle(.green)
                        Text(numbers(verdict)).font(.caption2).foregroundStyle(.secondary)
                    }
                }
            } else {
                Text("No data for window").foregroundStyle(.tertiary)
            }
        }
    }

    private func numbers(_ verdict: SourceVerdict) -> String {
        var parts: [String] = []
        if let peak = verdict.peakIntensity { parts.append(String(format: "%.1f mm/h", peak)) }
        if let probability = verdict.peakProbability { parts.append("\(Int(probability))%") }
        if verdict.coverage < 0.99 { parts.append("covers \(Int(verdict.coverage * 100))%") }
        return parts.joined(separator: " · ")
    }
}
