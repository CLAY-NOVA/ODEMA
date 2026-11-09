import RainKit
import SwiftUI
import UserNotifications

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var notificationStatus: UNAuthorizationStatus = .notDetermined
    @State private var testSent = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Look ahead", selection: $model.settings.lookahead) {
                        Text("30 minutes").tag(TimeInterval(30 * 60))
                        Text("1 hour").tag(TimeInterval(3600))
                        Text("2 hours").tag(TimeInterval(2 * 3600))
                        Text("3 hours").tag(TimeInterval(3 * 3600))
                        Text("6 hours").tag(TimeInterval(6 * 3600))
                        Text("12 hours").tag(TimeInterval(12 * 3600))
                    }
                    Picker("Sources that must agree", selection: $model.settings.requiredAgreement) {
                        Text("Any one").tag(0.01)
                        Text("A quarter").tag(0.25)
                        Text("Half").tag(0.5)
                        Text("Three quarters").tag(0.75)
                        Text("All").tag(1.0)
                    }
                    Picker("Rain counts from", selection: $model.settings.minIntensity) {
                        Text("Drizzle (0.1 mm/h)").tag(0.1)
                        Text("Light (0.2 mm/h)").tag(0.2)
                        Text("Light (0.5 mm/h)").tag(0.5)
                        Text("Steady (1 mm/h)").tag(1.0)
                        Text("Moderate (2.5 mm/h)").tag(2.5)
                    }
                    Picker("…or a chance of at least", selection: $model.settings.minProbability) {
                        ForEach([30.0, 40, 50, 60, 70, 80, 90], id: \.self) { value in
                            Text("\(Int(value))%").tag(value)
                        }
                    }
                    Picker("Don't repeat within", selection: $model.settings.cooldown) {
                        Text("1 hour").tag(TimeInterval(3600))
                        Text("2 hours").tag(TimeInterval(2 * 3600))
                        Text("3 hours").tag(TimeInterval(3 * 3600))
                        Text("6 hours").tag(TimeInterval(6 * 3600))
                        Text("12 hours").tag(TimeInterval(12 * 3600))
                    }
                } header: {
                    Text("When to alert")
                } footer: {
                    Text("A source votes “rain” when its forecast reaches the amount or the chance above. You get one alert per rain spell.")
                }

                Section {
                    Toggle("Quiet hours", isOn: $model.settings.quietHoursEnabled)
                    if model.settings.quietHoursEnabled {
                        DatePicker("From", selection: minutesBinding(\.quietStart), displayedComponents: .hourAndMinute)
                        DatePicker("Until", selection: minutesBinding(\.quietEnd), displayedComponents: .hourAndMinute)
                    }
                } footer: {
                    Text("Alerts during quiet hours wait until they end, if the rain is still on its way.")
                }

                Section {
                    ForEach(SourceID.allCases) { source in
                        Toggle(isOn: sourceBinding(source)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.displayName)
                                Text("\(source.coverage) · \(source.provider)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Weather sources")
                } footer: {
                    Text("All free public APIs, no account or key needed. Sources that don't cover a place are skipped automatically.")
                }

                Section {
                    LabeledContent("Notifications", value: notificationText)
                    LabeledContent("Background refresh", value: backgroundRefreshText)
                    Button(testSent ? "Test alert sent" : "Send a test alert") {
                        Task {
                            await model.sendTestAlert()
                            testSent = true
                            notificationStatus = await NotificationManager.shared.authorizationStatus()
                        }
                    }
                    if notificationStatus == .denied || UIApplication.shared.backgroundRefreshStatus != .available {
                        Button("Open iPhone Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                    }
                } header: {
                    Text("Alerts on this iPhone")
                } footer: {
                    Text("iOS decides when Odema checks in the background, usually every 15–60 minutes. Don't swipe Odema away in the app switcher, and keep Low Power Mode off for the most reliable alerts.")
                }

                Section("Data") {
                    Link("Open-Meteo (CC BY 4.0)", destination: URL(string: "https://open-meteo.com/")!)
                    Link("MET Norway (CC BY 4.0)", destination: URL(string: "https://api.met.no/")!)
                    Link("US National Weather Service", destination: URL(string: "https://www.weather.gov/documentation/services-web-api")!)
                    Link("Bright Sky / DWD", destination: URL(string: "https://brightsky.dev/")!)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                notificationStatus = await NotificationManager.shared.authorizationStatus()
            }
        }
    }

    private var notificationText: String {
        switch notificationStatus {
        case .authorized, .provisional, .ephemeral: return "On"
        case .denied: return "Off"
        case .notDetermined: return "Not asked yet"
        @unknown default: return "Unknown"
        }
    }

    private var backgroundRefreshText: String {
        switch UIApplication.shared.backgroundRefreshStatus {
        case .available: return "On"
        case .denied: return "Off"
        case .restricted: return "Restricted"
        @unknown default: return "Unknown"
        }
    }

    private func sourceBinding(_ source: SourceID) -> Binding<Bool> {
        Binding(
            get: { !model.settings.disabledSources.contains(source) },
            set: { isOn in
                if isOn {
                    model.settings.disabledSources.remove(source)
                } else {
                    model.settings.disabledSources.insert(source)
                }
            }
        )
    }

    /// Bridges "minutes after midnight" to a DatePicker.
    private func minutesBinding(_ keyPath: WritableKeyPath<AlertSettings, Int>) -> Binding<Date> {
        Binding(
            get: {
                let minutes = model.settings[keyPath: keyPath]
                return Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: Date()) ?? Date()
            },
            set: { date in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                model.settings[keyPath: keyPath] = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
            }
        )
    }
}
